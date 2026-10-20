package work.art1st.javarailways.bukkit.logic

import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.RailDisplayType
import work.art1st.javarailways.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.javarailways.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.javarailways.bukkit.extension.rail.RailDecorationLayout
import work.art1st.javarailways.bukkit.extension.rail.RailElementTemplateSnapshot
import work.art1st.javarailways.bukkit.extension.rail.RailExtension
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.DestructionSteps
import work.art1st.javarailways.core.api.util.Destruction
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object RailVisualBootstrap {
    private val railVisuals = ConcurrentHashMap<UUID, RailVisualLifetime>()

    /** The rail owns both current visuals and cleanup already started by a replacement. */
    internal class RailVisualLifetime(val rail: CoreRail) :
        RenderableExtension<Rail> {
        var segments: List<RailArcVisualSegment> = emptyList()
        private val pending = ConcurrentLinkedQueue<CompletionStage<Void>>()
        private val destruction = Destruction()

        override fun initialize(host: Rail,
            savedExtensionData: Map<String, SerializableExtensionData>) = Unit
        override fun save(): Pair<String, SerializableExtensionData>? = null
        override fun commit() = Unit

        fun clear(): CompletionStage<Void> {
            val removed = segments
            segments = emptyList()
            val steps = DestructionSteps()
            removed.forEach { segment -> steps.await { segment.destroy() } }
            val current = steps.finish()
            pending.add(current)
            current.whenComplete { _, failure -> if (failure == null) pending.remove(current) }
            val all = DestructionSteps()
            pending.forEach { result -> all.await { result } }
            return rail.world.trackRailCleanup(all.finish())
        }

        override fun destroy(): CompletionStage<Void> = destruction.run {
            val result = clear()
            railVisuals.remove(rail.id, this)
            result
        }
    }

    fun registerRail(rail: CoreRail, railType: String = "standard"): PersistedRailRenderMeta =
        registerRailWithTemplate(rail, createTemplateSnapshot(railType))

    /** Captures the immutable model template before an asynchronous RailWand prepare stage. */
    fun captureTemplateSnapshot(railType: String = "standard"): RailTemplateSnapshot =
        createTemplateSnapshot(railType)

    fun registerRailWithTemplate(
        rail: CoreRail,
        templateSnapshot: RailTemplateSnapshot
    ): PersistedRailRenderMeta {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.registerRailWithTemplate")
        val segments = createSegments(rail, templateSnapshot)
        registerSegments(rail, segments)
        return PersistedRailRenderMeta(
            railId = rail.id,
            segments = segments.map { RailArcSegmentMeta(it.arcStart, it.arcEnd, it.pointArcs) },
            templateSnapshot = templateSnapshot
        )
    }

    fun unregisterRail(railId: UUID): CompletionStage<Void> {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.unregisterRail")
        val owner = railVisuals.remove(railId)
            ?: return CompletableFuture.completedFuture(null)
        return owner.clear().whenComplete { _, failure ->
            if (failure != null) BukkitPlugin.INSTANCE.logger.log(java.util.logging.Level.SEVERE,
                "Rail visual cleanup failed: rail=$railId", failure)
        }
    }

    fun fromPersisted(coreWorld: CoreWorld, persisted: PersistedRailRenderMeta): Boolean {
        ThreadGuard.ensureMainThread("RailVisualBootstrap.fromPersisted")
        val rail = coreWorld.getRail(persisted.railId) ?: return false
        registerSegments(rail, createSegmentsFromPersisted(rail, persisted))
        return true
    }

    /**
     * Builds persistence/render input without touching a live world. [assemblyLengths] is the
     * ordered length of every segment in the same lane when [assemblyIndex] is non-null.
     */
    fun preparePersistedMeta(
        railId: UUID,
        totalArcLength: Double,
        templateSnapshot: RailTemplateSnapshot,
        assemblyIndex: Int? = null,
        assemblyLengths: List<Double> = emptyList()
    ): PersistedRailRenderMeta {
        require(totalArcLength >= 0.0 && totalArcLength.isFinite())
        val allArcs = if (assemblyIndex == null) {
            RailDecorationLayout.endpointInclusiveArcs(
                totalArcLength,
                templateSnapshot.repeatLength
            )
        } else {
            require(assemblyIndex in assemblyLengths.indices)
            require(assemblyLengths.all { it >= 0.0 && it.isFinite() })
            val offset = assemblyLengths.take(assemblyIndex).sum()
            val assemblyTotal = assemblyLengths.sum()
            RailDecorationLayout.endpointInclusiveArcs(
                assemblyTotal,
                templateSnapshot.repeatLength
            ).filter { globalArc ->
                globalArc >= offset - 1.0e-9 &&
                    (globalArc < offset + totalArcLength - 1.0e-9 || assemblyIndex == assemblyLengths.lastIndex)
            }.map { it - offset }
        }
        val segmentCount = kotlin.math.ceil(totalArcLength / RailArcVisualSegment.SEGMENT_ARC_LENGTH).toInt()
        val segments = (0 until segmentCount).mapNotNull { index ->
            val arcStart = index * RailArcVisualSegment.SEGMENT_ARC_LENGTH
            val arcEnd = minOf((index + 1) * RailArcVisualSegment.SEGMENT_ARC_LENGTH, totalArcLength)
            val pointArcs = RailDecorationLayout.arcsInSegment(
                allArcs,
                arcStart,
                arcEnd,
                includeEnd = index == segmentCount - 1
            )
            pointArcs.takeIf { it.isNotEmpty() }?.let {
                RailArcSegmentMeta(arcStart, arcEnd, it)
            }
        }
        return PersistedRailRenderMeta(railId, segments, templateSnapshot)
    }

    @OptIn(ExtensionData::class)
    private fun registerSegments(rail: CoreRail, segments: List<RailArcVisualSegment>) {
        val existing = railVisuals[rail.id]
        val owner = if (existing != null && existing.rail === rail) existing else {
            existing?.let { rail.world.trackRailCleanup(it.destroy()) }
            val registered = rail.getExtensionDataOrNull<RenderableExtension<Rail>>(BukkitPlugin.INSTANCE)
            check(registered == null || registered is RailVisualLifetime) {
                "Rail already has a different Bukkit lifecycle extension"
            }
            (registered as? RailVisualLifetime ?: RailVisualLifetime(rail).also {
                rail.registerExtensionData(BukkitPlugin.INSTANCE, it)
            }).also { railVisuals[rail.id] = it }
        }
        owner.clear()
        // Attach the full batch before indexing, so partial registration failures remain owned.
        owner.segments = segments
        if (segments.isEmpty()) return
        val renderManager = segments.first().world.renderChunkManager
        segments.forEach { segment ->
            val extension = RailExtension()
            segment.registerExtensionData(BukkitPlugin.INSTANCE, extension)
            extension.initialize(segment, emptyMap())
            renderManager.update(segment, null, segment.position)
        }
    }

    private fun createSegments(rail: CoreRail, templateSnapshot: RailTemplateSnapshot): List<RailArcVisualSegment> {
        val total = rail.curve.totalArcLength
        if (total <= 0.0) return emptyList()
        val segmentCount = kotlin.math.ceil(total / RailArcVisualSegment.SEGMENT_ARC_LENGTH).toInt()
        val assembly = rail.metadata.authoring?.assembly
        val allArcs = if (assembly == null) {
            RailDecorationLayout.endpointInclusiveArcs(
                totalArcLength = total,
                repeatLength = templateSnapshot.repeatLength
            )
        } else {
            val candidates = rail.world.allRails.filter { candidate ->
                candidate.metadata.authoring?.assembly?.let {
                    it.groupId == assembly.groupId && it.laneIndex == assembly.laneIndex
                } == true
            }
            val siblings = (0 until assembly.segmentCount).map { segmentIndex ->
                val atIndex = candidates.filter { it.metadata.authoring?.assembly!!.segmentIndex == segmentIndex }
                require(atIndex.isNotEmpty()) { "Incomplete rail assembly ${assembly.groupId}" }
                if (segmentIndex == assembly.segmentIndex) atIndex.firstOrNull { it.id == rail.id } ?: atIndex.first()
                else atIndex.first()
            }
            val offset = siblings.take(assembly.segmentIndex).sumOf { it.curve.totalArcLength }
            val assemblyTotal = siblings.sumOf { it.curve.totalArcLength }
            RailDecorationLayout.endpointInclusiveArcs(
                totalArcLength = assemblyTotal,
                repeatLength = templateSnapshot.repeatLength
            ).filter { globalArc ->
                globalArc >= offset - 1.0e-9 &&
                    (globalArc < offset + total - 1.0e-9 || assembly.segmentIndex == assembly.segmentCount - 1)
            }.map { it - offset }
        }
        return (0 until segmentCount).mapNotNull { index ->
            val arcStart = index * RailArcVisualSegment.SEGMENT_ARC_LENGTH
            val arcEnd = minOf((index + 1) * RailArcVisualSegment.SEGMENT_ARC_LENGTH, total)
            val pointArcs = RailDecorationLayout.arcsInSegment(
                allArcs,
                arcStart,
                arcEnd,
                includeEnd = index == segmentCount - 1
            )
            pointArcs.takeIf { it.isNotEmpty() }?.let {
                RailArcVisualSegment(
                    rail = rail,
                    arcStart = arcStart,
                    arcEnd = arcEnd,
                    pointArcs = it,
                    templateSnapshot = templateSnapshot
                )
            }
        }
    }

    private fun createSegmentsFromPersisted(
        rail: CoreRail,
        persisted: PersistedRailRenderMeta
    ): List<RailArcVisualSegment> = persisted.segments.map { segment ->
        require(segment.arcStart >= 0.0 && segment.arcEnd <= rail.curve.totalArcLength + 1.0e-6)
        RailArcVisualSegment(
            rail = rail,
            arcStart = segment.arcStart,
            arcEnd = segment.arcEnd,
            pointArcs = segment.pointArcs,
            templateSnapshot = persisted.templateSnapshot
        )
    }

    private fun createTemplateSnapshot(railType: String): RailTemplateSnapshot {
        // A named rail package overrides the bundled standard rail configuration.
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val config = if (modelManager.hasRailPackages) {
            modelManager.getRailConfig(railType) ?: BukkitPlugin.INSTANCE.railModelConfig
        } else {
            BukkitPlugin.INSTANCE.railModelConfig
        }
        val elements = config.elements.map { element ->
            RailElementTemplateSnapshot(
                type = element.type,
                itemStack = element.item?.toItemStack(),
                blockMaterial = element.blockMaterial,
                brightnessBlock = element.brightness?.block,
                brightnessSky = element.brightness?.sky,
                offsetX = element.offset.x,
                offsetY = element.offset.y,
                offsetZ = element.offset.z,
                rotationX = element.rotation.x,
                rotationY = element.rotation.y,
                rotationZ = element.rotation.z,
                scaleX = element.scale.x,
                scaleY = element.scale.y,
                scaleZ = element.scale.z,
                lightProbeOffsetX = element.lightProbeOffset.x,
                lightProbeOffsetY = element.lightProbeOffset.y,
                lightProbeOffsetZ = element.lightProbeOffset.z
            )
        }
        val raw = buildString {
            append(config.repeatLength)
            elements.forEach { e ->
                append('|')
                append(e.type.name)
                append(',')
                append(e.blockMaterial ?: "")
                append(',')
                append(e.itemStack?.type?.name ?: "")
                append(',')
                val itemMeta = e.itemStack?.itemMeta
                val customModelData = if (itemMeta != null && itemMeta.hasCustomModelData()) {
                    itemMeta.customModelData
                } else null
                append(customModelData ?: "")
                append(',')
                append(e.brightnessBlock ?: "").append(',').append(e.brightnessSky ?: "")
                append(',')
                append(e.offsetX).append(',').append(e.offsetY).append(',').append(e.offsetZ)
                append(',')
                append(e.rotationX).append(',').append(e.rotationY).append(',').append(e.rotationZ)
                append(',')
                append(e.scaleX).append(',').append(e.scaleY).append(',').append(e.scaleZ)
                append(',')
                append(e.lightProbeOffsetX).append(',')
                    .append(e.lightProbeOffsetY).append(',')
                    .append(e.lightProbeOffsetZ)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return RailTemplateSnapshot(
            templateId = hash,
            repeatLength = config.repeatLength,
            elements = elements
        )
    }
}
