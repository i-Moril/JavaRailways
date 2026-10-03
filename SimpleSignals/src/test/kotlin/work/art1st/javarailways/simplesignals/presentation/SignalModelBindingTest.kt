package work.art1st.javarailways.simplesignals.presentation

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.bukkit.api.model.*
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.*
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.*
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import work.art1st.javarailways.simplesignals.signal.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Production runtime/loader/placement composition with recording public handles, not live Folia. */
class SignalModelBindingTest {
    @TempDir lateinit var directory: Path
    private val packages = Path.of(requireNotNull(javaClass.getResource("/model-packages")).toURI())
    private val models get() = SimpleSignalsModelPackages.load(packages)
    private val worldId = UUID(0, 880)
    private val railId = UUID(0, 881)
    private val topology = lineTopologyState(7, railId)
    private val plugin = proxy(Plugin::class.java) { method, _ ->
        when (method) { "isEnabled" -> true; "getName", "toString" -> "SimpleSignals"; else -> error(method) }
    }
    private val curve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
    private var curveQueries = 0
    private val rails = proxy(RailTargetService::class.java) { method, _ ->
        check(method == "findCurve")
        curveQueries++
        Optional.of(curve)
    }

    @Test
    fun `fixture package discovery is immutable empty when missing and rejects duplicate or broken packages`() {
        assertEquals(emptyMap<String, LoadedPackageModel>(), SimpleSignalsModelPackages.load(directory.resolve("absent")))
        copy(packages.resolve("demo_signals"), directory.resolve("one"))
        assertEquals(setOf("demo_signals:home", "demo_signals:wall"), SimpleSignalsModelPackages.load(directory).keys)
        copy(packages.resolve("demo_signals"), directory.resolve("two"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { SimpleSignalsModelPackages.load(directory) }
            .message!!.contains("Duplicate"))
        Files.writeString(directory.resolve("two/manifest.yml"), "broken")
        assertThrows(ModelPackageException::class.java) { SimpleSignalsModelPackages.load(directory) }
    }

    @Test
    fun `real fixture and demo prepare endpoint pose while ground never queries rail and validation creates no handles`() {
        val configuration = DeviceModelDefinition("demo_signals:home", ModelPlacement.RailAttached(
            RailPositionSnapshot(railId, ViewDirection.BA, 10.0), ModelPose(0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)))
        val prepared = PreparedSignalModels.prepare(definitions(configuration), models, rails)
        assertEquals(1, curveQueries)
        assertEquals(10.0, prepared.signals.getValue("test:first").pose.x())
        assertEquals(-2.0, prepared.signals.getValue("test:first").pose.z())
        val ground = ModelPose(9.0, 64.0, -3.0, 0.0, 0.0, 0.0, 1.0)
        val demo = ModelPackageLoader.load(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/model-package")).toURI()))
        val preparedGround = PreparedSignalModels.prepare(definitions(DeviceModelDefinition("jr_four_light:head",
            ModelPlacement.Ground(ground))), demo.models, rails)
        assertSame(ground, preparedGround.signals.getValue("test:first").pose)
        assertEquals(1, curveQueries)
    }

    @Test
    fun `all configured explicit variants and missing models are rejected before handle allocation`() {
        val model = model()
        val definitions = definitions(model)
        val signal = definitions.fixedBlockSignals.single()
        val badSignal = FixedBlockSignalDefinition(signal.signalId, signal.revision, signal.controlledBlockId,
            signal.inspectionLeadTimeSeconds, ConfiguredSignalOutput(0.0, "typo"), signal.clearLevels,
            signal.downstreamSignals, signal.model)
        val bad = SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), definitions.fixedBlocks,
            listOf(badSignal))
        val failure = assertThrows(IllegalArgumentException::class.java) { PreparedSignalModels.prepare(bad, models, rails) }
        assertTrue(failure.message!!.contains("test:first"))
        assertTrue(failure.message!!.contains("typo"))
        assertThrows(IllegalArgumentException::class.java) { PreparedSignalModels.prepare(definitions, emptyMap(), rails) }
    }

    @Test
    fun `initial default available zero unavailable and restoration use complete configured scenes`() {
        var fail = false
        val f = fixture(rule = { input ->
            if (fail) error("rule unavailable")
            SectionSignalOutput(if (input.controlledSection.occupants.isEmpty()) 12.0 else 0.0,
                if (input.controlledSection.occupants.isEmpty()) "proceed" else "stop")
        })
        f.start()
        val handle = f.service.handles.single()
        assertEquals(mapOf("lamp" to "dark"), handle.submissions.last().animationScenes())
        f.process(1, false)
        assertEquals(mapOf("lamp" to "green"), handle.submissions.last().animationScenes())
        f.process(2, true)
        assertEquals(mapOf("lamp" to "red"), handle.submissions.last().animationScenes())
        assertEquals(0.0, f.world.sectionSignalSnapshot()!!.states.getValue("test:first").effectiveSpeedLimitMetersPerSecond)
        fail = true
        f.process(3, false)
        assertEquals(mapOf("lamp" to "dark"), handle.submissions.last().animationScenes())
        fail = false
        f.process(4, true)
        f.process(5, false)
        assertEquals(mapOf("lamp" to "green"), handle.submissions.last().animationScenes())
        assertEquals((0L..5L).toList(), handle.submissions.map { it.revision() })
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `changed-only dispatch never submits unrelated devices and speed-only changes retain scenes`() {
        var speed = 12.0
        val f = fixture(two = true, rule = { SectionSignalOutput(speed, "proceed") })
        f.start()
        f.process(1, false)
        val first = f.service.byId("fixed-block-signal/test:first")
        val second = f.service.byId("fixed-block-signal/test:second")
        speed = 3.0
        f.process(2, true)
        assertEquals(setOf("test:first"), f.world.sectionSignalSnapshot()!!.changedSignalIds)
        assertEquals(2, first.submissions.size)
        assertEquals(2, second.submissions.size)
        assertEquals(0, curveQueries)
    }

    @Test
    fun `real protection devices and indexed indicator use explicit noRoute and suppress selection-info-only updates`() {
        val branch = UUID(0, 882)
        val topology = topologyState(8, listOf(railId, branch), listOf(
            endpoint(railId, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)))
        val region = ProtectionRegionDefinition("test:region", 1, listOf(
            ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(railId, ViewDirection.AB, 5.0)),
            ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(branch, ViewDirection.BA, 5.0))))
        val route = ProtectionRouteDefinition("test:route", "test:entry", "test:exit", listOf(
            ProtectionRouteTraversal(railId, ViewDirection.AB, 5.0, 10.0),
            ProtectionRouteTraversal(branch, ViewDirection.AB, 0.0, 5.0)))
        val stop = ConfiguredSignalOutput(0.0, "stop")
        val levels = listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed")))
        val definitions = SimpleSignalsWorldDefinitions(worldId, 1, listOf(region), listOf(
            ProtectionRouteTableDefinition("test:table", 1, region.regionId, 1, listOf(route))),
            protectionSignals = listOf(
                ProtectionSignalDefinition("test:fixed", 1, ProtectionSignalKind.FIXED_ROUTE, route.routeId, 0.0,
                    stop, stop, levels, model = model()),
                ProtectionSignalDefinition("test:current", 1, ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 0.0,
                    stop, stop, levels, model = model())),
            routeIndicators = listOf(RouteIndicatorDefinition("test:indicator", 1, "test:entry", "stop",
                mapOf(route.routeId to "proceed"), model())))
        val world = SimpleSignalsWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, world.initializeDefinitions(topology, definitions).status)
        val occupancy = world.initializeSectionOccupancy()
        val service = RecordingModels()
        val errors = mutableListOf<String>()
        val binding = SignalModelBinding(plugin, worldId, topology.revision,
            PreparedSignalModels.prepare(definitions, models, rails), service, { context, _ -> errors += context })
        assertTrue(world.attachModels(world.authoringPublication(), binding))
        world.startModels(binding)
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision) })
        epoch.replaceSources(occupancy.sources)
        fun process(number: Long) {
            val position = RailPositionSnapshot(railId, ViewDirection.AB, 0.0)
            epoch.process(RailwayWorldFrameSnapshot.create(number, topology.revision, listOf(
                TrainFrameSnapshot.create("train", "Train", number, number, 0.0, false, false, emptyList(), position,
                    position, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
                ActiveRailContinuationsSnapshot.empty(topology.revision)))
        }
        process(1)
        val indicator = service.byId("route-indicator/test:indicator")
        assertEquals(mapOf("lamp" to "red"), indicator.submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "red"), service.byId("protection-signal/test:current").submissions.last().animationScenes())
        val frozen = RouteSnapshot("test:mission", 1, topology.revision, listOf(
            RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(branch, ViewDirection.AB, 0.0, 10.0)))
        val owner = InfrastructureOwner("test:owner", 1)
        fun admission(state: ActiveRouteAdmissionState, committed: Double?) = ActiveRouteAdmissionSnapshot(owner,
            "train", frozen, state, RouteBoundary(20.0), committed?.let(::RouteBoundary))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(ActiveRouteAdmissionState.PREPARING, null)))
        process(2)
        assertEquals(mapOf("lamp" to "green"), indicator.submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "red"), service.byId("protection-signal/test:fixed").submissions.last().animationScenes())
        val count = indicator.submissions.size
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(ActiveRouteAdmissionState.GRANTED, 20.0)))
        process(3)
        assertTrue(world.routeIndicatorStates().getValue("test:indicator").selection.routeReady)
        assertEquals(count, indicator.submissions.size)
        assertEquals(mapOf("lamp" to "green"), service.byId("protection-signal/test:current").submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "green"), service.byId("protection-signal/test:fixed").submissions.last().animationScenes())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `attach before allocation catches signal transition during creation without replaying default`() {
        val f = fixture()
        f.service.beforeCreate = { f.process(1, true) }
        f.start()
        assertEquals(listOf(mapOf("lamp" to "red")), f.service.handles.single().submissions.map { it.animationScenes() })
    }

    @Test
    fun `partial live change winning initial catchup initializes all models from newer complete snapshot`() {
        val f = fixture(two = true)
        f.process(1, false)
        val old = f.occupancy.presentationSnapshot()!!
        f.process(2, true)
        val newest = f.occupancy.presentationSnapshot()!!
        f.binding.start({ true }) {
            f.binding.published(newest, listOf("test:first"), emptyList())
            old
        }
        assertEquals(listOf(mapOf("lamp" to "red")), f.service.byId("fixed-block-signal/test:first").submissions.map { it.animationScenes() })
        assertEquals(listOf(mapOf("lamp" to "green")), f.service.byId("fixed-block-signal/test:second").submissions.map { it.animationScenes() })
    }

    @Test
    fun `unknown runtime explicit variant reports failure without default and reporting failure cannot escape safety`() {
        val f = fixture(rule = { SectionSignalOutput(5.0, "unknown") }, report = { _, _ -> error("report unavailable") })
        f.start()
        assertDoesNotThrow { f.process(1, false) }
        assertEquals("unknown", (f.world.sectionSignalSnapshot()!!.states.getValue("test:first") as SectionSignalState.Available).output.modelVariant)
        assertEquals(1, f.service.handles.single().submissions.size)
    }

    @Test
    fun `partial create and submit failures are contained and creation cleans existing handles`() {
        val f = fixture(two = true)
        f.service.failCreate = 2
        assertDoesNotThrow { f.start() }
        assertTrue(f.service.handles.single().closed)
        assertTrue(f.service.handles.single().submissions.isEmpty())
        assertEquals(1, f.errors.size)
        val submitting = fixture()
        submitting.start()
        submitting.service.handles.single().failSubmit = true
        assertDoesNotThrow { submitting.process(1, true) }
        assertTrue(submitting.errors.isNotEmpty())
        assertEquals(0.0, submitting.world.sectionSignalSnapshot()!!.states.getValue("test:first").effectiveSpeedLimitMetersPerSecond)
    }

    @Test
    fun `definition supersession during multi-device creation closes partial handles without reporting a failure`() {
        val f = fixture(two = true)
        val prepared = (f.world.prepareDefinitions(topology, definitions(model(), two = true, revision = 2)) as
            SimpleSignalsDefinitionPreparationResult.Prepared).value
        val occupancy = SectionOccupancyRuntime.from(prepared.candidatePublication)
        val replacement = binding(prepared.definitions, f.service, f.errors)
        occupancy.attachModelBinding(replacement)
        f.service.beforeCreate = {
            assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED,
                f.world.commitPrepared(prepared, occupancy, replacement))
        }

        f.start()

        assertEquals(1, f.service.handles.size)
        assertTrue(f.service.handles.single().closed)
        assertTrue(f.service.handles.single().submissions.isEmpty())
        assertTrue(f.errors.isEmpty(), f.errors.toString())
        f.service.beforeCreate = {}
        f.world.startModels(replacement)
        assertEquals(3, f.service.handles.size)
        assertTrue(f.service.handles.drop(1).all { !it.closed && it.submissions.size == 1 })
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `reverse postcommit starts close the actually allocated binding and superseded starts never close current handles`() {
        val f = fixture()
        f.start()
        val original = f.service.handles.single()
        fun replacement(revision: Long): SignalModelBinding {
            val prepared = (f.world.prepareDefinitions(topology, definitions(model(), revision = revision)) as
                SimpleSignalsDefinitionPreparationResult.Prepared).value
            val occupancy = SectionOccupancyRuntime.from(prepared.candidatePublication)
            val binding = binding(prepared.definitions, f.service, f.errors)
            occupancy.attachModelBinding(binding)
            assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, f.world.commitPrepared(prepared, occupancy, binding))
            return binding
        }
        val first = replacement(2)
        val second = replacement(3)
        f.world.startModels(second)
        assertTrue(original.closed)
        assertEquals(2, f.service.handles.size)
        val current = f.service.handles.last()
        f.world.startModels(first)
        f.binding.published(f.occupancy.presentationSnapshot() ?: published(5, "proceed"), listOf("test:first"), emptyList())
        assertFalse(current.closed)
        assertEquals(1, current.submissions.size)
        assertTrue(f.errors.isEmpty())
        f.world.retire()
        f.world.closeModels()
        assertTrue(current.closed)
    }

    private fun published(epoch: Long, variant: String): SectionOccupancyRuntime.PublishedState {
        val stamp = RailwayStateStamp(worldId.toString(), epoch, topology.revision)
        return SectionOccupancyRuntime.PublishedState(SectionOccupancySnapshot(stamp, emptyMap(), emptyList(), emptyMap()),
            SectionSignalSnapshot(stamp, mapOf("test:first" to SectionSignalState.Available(SectionSignalOutput(1.0, variant))),
                listOf("test:first")), null, emptyMap())
    }

    private fun model() = DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY))

    private fun definitions(model: DeviceModelDefinition, two: Boolean = false, revision: Long = 1): SimpleSignalsWorldDefinitions {
        val blocks = listOf(FixedBlockDefinition("test:first-block", 1,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 2.0, 4.0)))) + if (two) listOf(FixedBlockDefinition("test:second-block", 1,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 6.0, 8.0)))) else emptyList()
        return SimpleSignalsWorldDefinitions(worldId, revision, emptyList(), emptyList(), blocks,
            blocks.mapIndexed { index, block -> FixedBlockSignalDefinition(if (index == 0) "test:first" else "test:second", 1,
                block.blockId, 0.0, ConfiguredSignalOutput(0.0, "stop"),
                listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed"))), model = model) })
    }

    private fun binding(definitions: SimpleSignalsWorldDefinitions, service: RecordingModels,
        errors: MutableList<String>, report: ((String, Throwable) -> Unit)? = null) = SignalModelBinding(plugin, worldId,
        topology.revision, PreparedSignalModels.prepare(definitions, models, rails), service,
        report ?: { context, _ -> errors += context })

    private fun fixture(two: Boolean = false, rule: ((SectionSignalRuleInput) -> SectionSignalOutput)? = null,
        report: ((String, Throwable) -> Unit)? = null): Fixture {
        val definitions = definitions(model(), two)
        val world = SimpleSignalsWorldRuntime(worldId)
        world.initializeDefinitions(topology, definitions)
        val occupancy = if (rule == null) world.initializeSectionOccupancy() else world.initializeSectionOccupancy(
            world.authoringPublication().fixedBlockSignals.bindings.map { original -> SectionSignalRuleBinding(original.signalId,
                original.controlledSection, emptyList()) { rule(it) } })
        val service = RecordingModels()
        val errors = mutableListOf<String>()
        val binding = binding(definitions, service, errors, report)
        assertTrue(world.attachModels(world.authoringPublication(), binding))
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision) })
        epoch.replaceSources(occupancy.sources)
        return Fixture(world, occupancy, service, binding, errors, epoch)
    }

    private inner class Fixture(val world: SimpleSignalsWorldRuntime, val occupancy: SectionOccupancyRuntime,
        val service: RecordingModels, val binding: SignalModelBinding, val errors: MutableList<String>, val epoch: SignalEpochRuntime) {
        fun start() = world.startModels(binding)
        fun process(number: Long, occupied: Boolean) {
            val position = RailPositionSnapshot(railId, ViewDirection.AB, 0.0)
            val intervals = listOf(RailInterval(railId, if (occupied) 2.5 else 0.0, if (occupied) 3.5 else 1.0))
            epoch.process(RailwayWorldFrameSnapshot.create(number, topology.revision,
                listOf(TrainFrameSnapshot.create("train", "Train", number, number, 0.0, false, false, emptyList(),
                    position, position, intervals, emptyList(), emptyList(), emptyList(), emptyList())),
                ActiveRailContinuationsSnapshot.empty(topology.revision)))
        }
    }

    internal class RecordingModels : ModelPresentationService {
        class Handle(val id: String) : ModelHandle {
            val submissions = mutableListOf<ModelPresentation>()
            var closed = false
            var failSubmit = false
            override fun submit(presentation: ModelPresentation) {
                if (closed) return
                if (failSubmit) error("submit failed")
                submissions += presentation
            }
            override fun close() { closed = true }
        }
        val handles = mutableListOf<Handle>()
        var failCreate = -1
        var beforeCreate: () -> Unit = {}
        var cleanup = CompletableFuture<Void>()
        var closeOwnerCalls = 0
        override fun create(owner: Plugin, worldId: UUID, objectId: String, definition: ModelDefinition): ModelHandle {
            check(handles.none { it.id == objectId && !it.closed }) { "duplicate active model $objectId" }
            if (handles.size + 1 == failCreate) error("create failed")
            beforeCreate()
            return Handle(objectId).also(handles::add)
        }
        fun byId(id: String) = handles.last { it.id == id }
        override fun closeOwner(owner: Plugin): CompletionStage<Void> {
            closeOwnerCalls++
            handles.forEach(Handle::close)
            return cleanup
        }
    }

    private fun copy(source: Path, target: Path) {
        Files.walk(source).use { paths -> paths.forEach { path ->
            val destination = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
        } }
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments -> handler(method.name, arguments) })
    }
}
