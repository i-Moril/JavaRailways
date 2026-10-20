package work.art1st.javarailways.offline

import kotlinx.serialization.json.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.bukkit.persistence.*
import work.art1st.javarailways.bukkit.config.PhysicsConfig
import work.art1st.javarailways.bukkit.config.JavaRailwaysConfig
import work.art1st.javarailways.bukkit.config.model.PackageLoader
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotCodec
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*
import kotlin.math.abs

/** Bounded one-time converter for the audited empty-train pre-SimpleATO development save. */
object DevelopmentWorldMigration {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    data class ConvertedPair(val infrastructure: InfrastructureFileV2, val runtime: RuntimeFileV2,
                             val repairs: List<String>)

    fun validateConfigDirectory(directory: Path): List<String> {
        fun yaml(name: String) = YamlConfiguration().also { it.load(directory.resolve(name).toFile()) }
        PhysicsConfig.fromYaml(yaml("physics.yml"))
        JavaRailwaysConfig.fromYaml(yaml("config.yml"))
        return listOf("Validated current config.yml and physics.yml")
    }

    fun validateDataDirectory(directory: Path, progress: (String) -> Unit = {}): List<String> {
        val report = validateConfigDirectory(directory).toMutableList()
        report.forEach(progress)
        // Real cached Paper registries, as in model tests; this does not start a Minecraft server.
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        listOf("trains", "rails").forEach { kind ->
            val packages = directory.resolve(kind)
            require(packages.isDirectory()) { "Missing package directory: $packages" }
            Files.list(packages).use { entries ->
                entries.filter { it.isDirectory() }.sorted().forEach { path ->
                    val loader = PackageLoader(path.toFile())
                    requireNotNull(if (kind == "trains") loader.loadTrainPackage() else loader.loadRailPackage()) {
                        "Missing package manifest: $path"
                    }
                    report += "Validated current $kind package: ${path.name}"
                    progress(report.last())
                }
            }
        }
        return report
    }

    fun convert(infrastructure: String, runtime: String, repairRedundantPoints: Boolean): ConvertedPair {
        val repairs = mutableListOf<String>()
        val source = json.parseToJsonElement(infrastructure).jsonObject
        val core = json.parseToJsonElement(source.getValue("coreSnapshotJson").jsonPrimitive.content).jsonObject
        val repaired = RailEndpointMigration.convert(repairCurvePoints(core, repairRedundantPoints, repairs), repairs)
        val infra = RailInfrastructureMetadataMigration.migrate(json.encodeToString(JsonObject.serializer(),
            JsonObject(source + ("coreSnapshotJson" to JsonPrimitive(repaired.toString()))))).file
        val oldRuntime = json.parseToJsonElement(runtime).jsonObject
        requireKeys(oldRuntime, setOf("formatVersion", "infrastructureRevision", "physicsEpoch", "coreSnapshotJson", "basicSnapshotJson"))
        require(oldRuntime.long("formatVersion") == 1L) { "Expected old outer runtime format 1" }
        val revision = oldRuntime.long("infrastructureRevision")
        val epoch = oldRuntime.long("physicsEpoch")
        require(revision == infra.infrastructureRevision) { "Infrastructure/runtime revision mismatch" }
        val oldCore = json.parseToJsonElement(oldRuntime.getValue("coreSnapshotJson").jsonPrimitive.content).jsonObject
        require(oldCore.getValue("trains").jsonArray.isEmpty()) {
            "This audited migration does not support non-empty legacy trains; no records were discarded"
        }
        val oldBasic = json.parseToJsonElement(oldRuntime.getValue("basicSnapshotJson").jsonPrimitive.content).jsonObject
        requireKeys(oldBasic, setOf("formatVersion", "infrastructureRevision", "physicsEpoch", "timetables", "dispatcher", "rollingStock"))
        require(oldBasic.long("formatVersion") == 1L) { "Expected old Basic runtime format 1" }
        require(oldBasic.long("infrastructureRevision") == revision && oldBasic.long("physicsEpoch") == epoch)
        require(oldBasic.getValue("timetables").jsonArray.isEmpty() && oldBasic.getValue("rollingStock").jsonArray.isEmpty()) {
            "Non-empty legacy timetable/rolling-stock conversion needs a separately audited mapping"
        }
        val dispatcher = oldBasic.getValue("dispatcher").jsonObject
        require(dispatcher.getValue("clocks").jsonArray.isEmpty() && dispatcher.getValue("runs").jsonArray.isEmpty()) {
            "Non-empty legacy dispatcher checkpoints are outside this migration"
        }
        val ato = SimpleATORuntimeSnapshotCodec.decode(JsonObject(oldBasic + ("formatVersion" to JsonPrimitive(1))).toString())
        val targetRuntime = SplitWorldSnapshotCodec.runtime(
            work.art1st.javarailways.core.persistence.WorldSnapshotCodec.decodeRuntime(oldCore.toString()),
            BasicRuntimeSnapshotV3.capture(revision, epoch), ato)
        val strictInfra = SplitWorldSnapshotCodec.decodeInfrastructure(SplitWorldSnapshotCodec.encode(infra))
        val strictRuntime = SplitWorldSnapshotCodec.decodeRuntime(SplitWorldSnapshotCodec.encode(targetRuntime))
        strictInfra.core(); strictInfra.basic(); strictInfra.simpleAto()
        strictRuntime.core(); strictRuntime.basic(); strictRuntime.simpleAto()
        materialize(strictInfra, strictRuntime)
        return ConvertedPair(strictInfra, strictRuntime, repairs)
    }

    /** Already ownership-migrated documents: only replace Core connection fields. */
    fun convertEndpoints(infrastructure: String, runtime: String): ConvertedPair {
        val source = SplitWorldSnapshotCodec.decodeInfrastructure(infrastructure)
        val reports = mutableListOf<String>()
        val core = json.parseToJsonElement(source.coreSnapshotJson).jsonObject
        require(core.getValue("formatVersion").jsonPrimitive.int == CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION) {
            "Endpoint mode requires current Core infrastructure"
        }
        val converted = RailEndpointMigration.convert(core, reports)
        val target = source.copy(coreSnapshotJson = if (converted == core) source.coreSnapshotJson else converted.toString())
        val currentRuntime = SplitWorldSnapshotCodec.decodeRuntime(runtime)
        require(target.infrastructureRevision == currentRuntime.infrastructureRevision) { "Infrastructure/runtime revision mismatch" }
        target.core(); target.basic(); target.simpleAto()
        currentRuntime.core(); currentRuntime.basic(); currentRuntime.simpleAto()
        materialize(target, currentRuntime)
        return ConvertedPair(target, currentRuntime, reports)
    }

    /** Only coincident DISCRETE duplicates with audited floating-point-sized frame differences. */
    fun repairCurvePoints(core: JsonObject, enabled: Boolean, report: MutableList<String>): JsonObject {
        val rails = core.getValue("rails").jsonArray.map { element ->
            val rail = element.jsonObject
            val curve = rail.getValue("curveAB").jsonObject
            val input = curve.getValue("pointList").jsonArray
            val output = mutableListOf<JsonElement>()
            input.forEachIndexed { index, point ->
                val previous = output.lastOrNull()?.jsonObject
                val current = point.jsonObject
                if (previous != null && current.double("arcLength") <= previous.double("arcLength")) {
                    val label = "rail=${rail.getValue("id").jsonPrimitive.content}, point=$index"
                    require(enabled) { "$label: non-increasing curve arc; explicit repair option required" }
                    val pointKeys = setOf("position", "tangent", "normal", "arcLength", "segmentType")
                    requireKeys(current, pointKeys)
                    requireKeys(previous, pointKeys)
                    listOf(current, previous).forEach { frame ->
                        listOf("position", "tangent", "normal").forEach { vector ->
                            requireKeys(frame.getValue(vector).jsonObject, setOf("x", "y", "z"))
                        }
                    }
                    require(current.getValue("position") == previous.getValue("position") &&
                        current.getValue("normal") == previous.getValue("normal") &&
                        current["segmentType"]?.jsonPrimitive?.content == "DISCRETE" &&
                        previous["segmentType"]?.jsonPrimitive?.content == "DISCRETE" &&
                        abs(current.double("arcLength") - previous.double("arcLength")) <= 1e-12 &&
                        index < input.lastIndex) { "$label: not an audited redundant DISCRETE point" }
                    val tangentDelta = listOf("x", "y", "z").maxOf { component ->
                        abs(current.getValue("tangent").jsonObject.double(component) -
                            previous.getValue("tangent").jsonObject.double(component))
                    }
                    require(tangentDelta.isFinite() && tangentDelta <= 1e-7) { "$label: distinct tangent frame" }
                    report += "$label, removedArc=${current.double("arcLength")}, keptArc=${previous.double("arcLength")}, maxTangentComponentDelta=$tangentDelta"
                } else output += point
            }
            JsonObject(rail + ("curveAB" to JsonObject(curve + ("pointList" to JsonArray(output)))))
        }
        return JsonObject(core + ("rails" to JsonArray(rails)))
    }

    /** Run the real current business loaders without Bukkit, tick publication or train movement. */
    fun materialize(infrastructure: InfrastructureFileV2, runtime: RuntimeFileV2) {
        val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                IsolatedSimulationThreadMarker.run {
                    val restored = CoreWorld.load(infrastructure.core(), runtime.core(),
                        CoreWorld.SnapshotDependency(tick, physics))
                    val basic = BasicWorldData()
                    restored.registerExtensionData(basicPlugin, basic)
                    val coordinator = AtoWorldCoordinator(safetyInterlock = basic.safetyInterlock)
                    val restrictions = TrainRestrictionSnapshotStore(coordinator.worldId)
                    val inspection = InspectionRuntime(publisher = restrictions,
                        sourceUnavailable = { _, _, failure -> throw failure })
                    val signal = SignalEpochRuntime(coordinator.worldId, restrictions,
                        restrictions::completeEpoch, inspection::markDirty)
                    val dependencies = object : InfrastructureDependencyService {
                        override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult =
                            error("Offline materialization must not install live dependencies")
                        override fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult =
                            error("Offline materialization must not remove live dependencies")
                    }
                    val dispatcher = TimetableDispatcher({ null }, restrictions, inspection, dependencies)
                    restored.registerExtensionData(atoPlugin,
                        SimpleATOWorldData(coordinator, dispatcher, signal, restrictions, inspection,
                            basic.railObjects, basic.safetyInterlock))
                    infrastructure.basic().restore(restored)
                    infrastructure.simpleAto().restore(restored)
                    runtime.basic().restore()
                    runtime.simpleAto().restore(restored)
                }
            }
        }
    }

    fun run(source: Path, output: Path?, repairRedundantPoints: Boolean, endpointsOnly: Boolean = false): List<String> {
        require(!endpointsOnly || !repairRedundantPoints) { "Endpoint mode does not repair curves" }
        val root = source.toRealPath()
        require(root.isDirectory()) { "Source must be a worlds directory" }
        if (output != null) {
            require(!output.exists() && output.toAbsolutePath().normalize() != root) { "Output must be a NEW independent directory" }
            val resolved = output.toAbsolutePath().normalize().parent.toRealPath().resolve(output.fileName)
            require(!resolved.startsWith(root)) { "Output must not be inside source (including symbolic links)" }
        }
        val originals = Files.list(root).use { paths -> paths.filter { it.isRegularFile() }.sorted().toList() }
        val originalBytes = originals.associateWith { it.readBytes() }
        val converted = linkedMapOf<String, ByteArray>()
        val reports = mutableListOf<String>()
        originals.filter { Regex("[0-9a-f-]{36}-infrastructure\\.json(?:\\.bak)?").matches(it.name) }.forEach { infraPath ->
            UUID.fromString(infraPath.name.substring(0, 36))
            val runtimePath = root.resolve(infraPath.name.replace("-infrastructure", "-runtime"))
            val runtimeBytes = requireNotNull(originalBytes[runtimePath]) { "Missing paired runtime: $runtimePath" }
            val infraBytes = originalBytes.getValue(infraPath)
            val infraText = unpack(infraBytes)
            val runtimeText = unpack(runtimeBytes)
            val pair = if (endpointsOnly) convertEndpoints(infraText, runtimeText)
                else convert(infraText, runtimeText, repairRedundantPoints)
            val unchanged = endpointsOnly && pair.infrastructure == SplitWorldSnapshotCodec.decodeInfrastructure(infraText)
            converted[infraPath.name] = if (unchanged) infraBytes else pack(SplitWorldSnapshotCodec.encode(pair.infrastructure), infraBytes)
            converted[runtimePath.name] = if (endpointsOnly) runtimeBytes else pack(SplitWorldSnapshotCodec.encode(pair.runtime), runtimeBytes)
            reports += "${infraPath.name}: revision=${pair.infrastructure.infrastructureRevision}, epoch=${pair.runtime.physicsEpoch}, rails=${pair.infrastructure.core().rails.size}, directedConnections=${pair.infrastructure.core().rails.sumOf { it.connectionsAtA.size + it.connectionsAtB.size }}, metadata=${pair.infrastructure.basic().railMetadata.size}, directions=${pair.infrastructure.basic().operatingDirections.size}, destinations=${pair.infrastructure.simpleAto().destinations.size}, lines=${pair.infrastructure.simpleAto().lines.size}"
            reports += pair.repairs.map { "${infraPath.name}: $it" }
        }
        require(converted.isNotEmpty()) { "No split world file pairs found" }
        val runtimeNames = originals.filter { Regex("[0-9a-f-]{36}-runtime\\.json(?:\\.bak)?").matches(it.name) }.map { it.name }.toSet()
        require(runtimeNames.all { it in converted }) { "Orphan runtime file: no paired infrastructure" }
        originalBytes.forEach { (path, bytes) -> require(path.readBytes().contentEquals(bytes)) { "Source changed during migration: $path" } }
        converted.forEach { (name, bytes) -> reports += "$name: sourceSha256=${sha(originalBytes.getValue(root.resolve(name)))}, targetSha256=${sha(bytes)}" }
        if (output != null) {
            Files.createDirectory(output)
            converted.forEach { (name, bytes) -> Files.write(output.resolve(name), bytes, java.nio.file.StandardOpenOption.CREATE_NEW) }
            output.resolve("migration-report.txt").writeText(reports.joinToString("\n", postfix = "\n"))
        }
        return reports
    }

    private fun requireKeys(value: JsonObject, keys: Set<String>) {
        require(value.keys == keys) { "Unexpected legacy fields: expected $keys, got ${value.keys}" }
    }
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
    private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun unpack(bytes: ByteArray) = if (gzip(bytes)) GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() } else bytes.toString(Charsets.UTF_8)
    private fun pack(text: String, original: ByteArray): ByteArray = if (gzip(original)) java.io.ByteArrayOutputStream().also {
        target -> GZIPOutputStream(target).use { it.write(text.toByteArray()) }
    }.toByteArray() else text.toByteArray()
    private fun gzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
}

fun main(args: Array<String>) {
    if (args.size == 2 && args[0] == "--validate-data-directory") {
        DevelopmentWorldMigration.validateDataDirectory(Path.of(args[1]), ::println)
        return
    }
    if (args.size == 2 && args[0] == "--validate-config-directory") {
        DevelopmentWorldMigration.validateConfigDirectory(Path.of(args[1])).forEach(::println)
        return
    }
    require(args.isNotEmpty()) { "Usage: source/worlds [--output NEW-directory] [--repair-redundant-curve-points] [--endpoints-only]" }
    var output: Path? = null
    var repair = false
    var endpointsOnly = false
    var index = 1
    while (index < args.size) {
        when (args[index]) {
            "--output" -> { require(output == null); output = Path.of(args[++index]) }
            "--repair-redundant-curve-points" -> repair = true
            "--endpoints-only" -> endpointsOnly = true
            else -> error("Unknown option: ${args[index]}")
        }
        index++
    }
    DevelopmentWorldMigration.run(Path.of(args[0]), output, repair, endpointsOnly).forEach(::println)
    println(if (output == null) "Dry-run validated; no files written." else "Validated migration written to NEW directory: $output")
}
