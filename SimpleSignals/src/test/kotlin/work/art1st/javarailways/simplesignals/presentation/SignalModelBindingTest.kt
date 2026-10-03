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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val prepared = PreparedSignalModels.prepare(definitions(configuration), models, rails, topology)
        assertEquals(1, curveQueries)
        assertEquals(10.0, prepared.signals.getValue("test:first").single().pose.x())
        assertEquals(-2.0, prepared.signals.getValue("test:first").single().pose.z())
        val ground = ModelPose(9.0, 64.0, -3.0, 0.0, 0.0, 0.0, 1.0)
        val demo = ModelPackageLoader.load(Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/model-package")).toURI()))
        val preparedGround = PreparedSignalModels.prepare(definitions(DeviceModelDefinition("jr_four_light:head",
            ModelPlacement.Ground(ground))), demo.models, rails, topology)
        assertSame(ground, preparedGround.signals.getValue("test:first").single().pose)
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
        val failure = assertThrows(IllegalArgumentException::class.java) { PreparedSignalModels.prepare(bad, models, rails, topology) }
        assertTrue(failure.message!!.contains("test:first"))
        assertTrue(failure.message!!.contains("typo"))
        assertThrows(IllegalArgumentException::class.java) { PreparedSignalModels.prepare(definitions, emptyMap(), rails, topology) }
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
    fun `unmodeled primary fans one rule output only to related independent attached models`() {
        var fail = false
        var speed = 12.0
        val base = definitions(null, two = true)
        val first = AttachedSignalDefinition("test:repeat-one", 1, "test:first", model())
        val second = AttachedSignalDefinition("test:repeat-two", 1, "test:first", DeviceModelDefinition(
            "demo_signals:wall", ModelPlacement.Ground(ModelPose(9.0, 64.0, 3.0, 0.0, 0.0, 0.0, 1.0))))
        val unrelated = AttachedSignalDefinition("test:repeat-other", 1, "test:second", model())
        val configured = withAttached(base, listOf(first, second, unrelated))
        var evaluations = 0
        val f = fixture(definitionOverride = configured, rule = {
            evaluations++
            if (fail) error("unavailable primary")
            SectionSignalOutput(speed, "proceed")
        })
        val baseline = SimpleSignalsWorldRuntime(worldId).also { it.initializeDefinitions(topology, base) }
        val publication = f.world.authoringPublication()
        assertEquals(baseline.authoringPublication().fixedBlockSignals.bindings.size, publication.fixedBlockSignals.bindings.size)
        assertEquals(baseline.fixedBlockSnapshot().blocksById.keys, f.world.fixedBlockSnapshot().blocksById.keys)
        assertEquals(2, publication.fixedBlockSignals.bindings.size)
        assertEquals(3, f.binding.prepared.signals.values.sumOf { it.size })
        assertThrows(UnsupportedOperationException::class.java) {
            (f.binding.prepared.signals.getValue("test:first") as MutableList).clear()
        }
        f.start()
        assertEquals(setOf("attached-signal/test:repeat-one", "attached-signal/test:repeat-two",
            "attached-signal/test:repeat-other"), f.service.handles.map { it.id }.toSet())
        assertTrue(f.service.handles.all { it.submissions.single().animationScenes() == mapOf("lamp" to "dark") })
        f.process(1, false)
        assertEquals(2, evaluations, "one evaluation per logical signal, independent of displays")
        val own = f.service.byId("attached-signal/test:repeat-one")
        val otherModel = f.service.byId("attached-signal/test:repeat-two")
        val otherPrimary = f.service.byId("attached-signal/test:repeat-other")
        assertEquals(9.0, otherModel.submissions.last().transform().x())
        assertEquals(0.0, own.submissions.last().transform().x())
        speed = 3.0
        f.process(2, true)
        assertEquals(setOf("test:first"), f.world.sectionSignalSnapshot()!!.changedSignalIds)
        assertTrue(f.service.handles.all { it.submissions.size == 2 }, "same variant produces no display submissions")
        fail = true
        f.process(3, false)
        assertEquals(mapOf("lamp" to "dark"), own.submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "dark"), otherModel.submissions.last().animationScenes())
        assertEquals(2, otherPrimary.submissions.size)
        assertEquals(0.0, f.world.sectionSignalSnapshot()!!.states.getValue("test:first").effectiveSpeedLimitMetersPerSecond)
        fail = false
        f.process(4, true)
        assertEquals(mapOf("lamp" to "green"), own.submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "green"), otherModel.submissions.last().animationScenes())
        assertEquals(2, otherPrimary.submissions.size)
        assertEquals(0, curveQueries)
        assertTrue(f.errors.isEmpty())
    }

    @Test
    fun `attachment rebind and movement retain handles while old callbacks and cleanup cannot revive them`() {
        val base = definitions(model(), two = true)
        val attached = AttachedSignalDefinition("test:repeater", 1, "test:first", model())
        val f = fixture(definitionOverride = withAttached(base, listOf(attached)))
        f.start()
        f.process(1, false)
        val original = f.service.byId("attached-signal/test:repeater")
        val primary = f.service.byId("fixed-block-signal/test:first")
        val second = f.service.byId("fixed-block-signal/test:second")
        val moved = attached.copy(revision = 2, primarySignalId = "test:second", model = DeviceModelDefinition(
            "demo_signals:home", ModelPlacement.Ground(ModelPose(8.0, 2.0, 4.0, 0.0, 0.0, 0.0, 1.0))))
        val replacement = replace(f, withAttached(base, listOf(moved), revision = 2))
        f.world.startModels()
        assertEquals(3, f.service.handles.size)
        assertSame(original, f.service.byId(original.id))
        assertSame(primary, f.service.byId(primary.id))
        assertSame(second, f.service.byId(second.id))
        assertEquals(8.0, original.submissions.last().transform().x())
        val stamp = RailwayStateStamp(worldId.toString(), 2, topology.revision)
        val state = SectionOccupancyRuntime.PublishedState(
            SectionOccupancySnapshot(stamp, emptyMap(), emptyList(), emptyMap()),
            SectionSignalSnapshot(stamp, mapOf(
                "test:first" to SectionSignalState.Available(SectionSignalOutput(12.0, "proceed")),
                "test:second" to SectionSignalState.Available(SectionSignalOutput(0.0, "stop"))),
                listOf("test:second")), null, emptyMap())
        replacement.published(state, listOf("test:second"), emptyList())
        assertEquals(mapOf("lamp" to "red"), original.submissions.last().animationScenes())
        val submissions = original.submissions.size
        f.binding.published(published(99, "proceed"), listOf("test:first"), emptyList())
        assertEquals(submissions, original.submissions.size)
        assertEquals((0L until submissions.toLong()).toList(), original.submissions.map { it.revision() })
        f.world.retire()
        f.world.closeModels()
        f.world.closeModels()
        replacement.published(published(100, "proceed"), listOf("test:second"), emptyList())
        assertEquals(submissions, original.submissions.size)
        assertTrue(f.service.handles.all { it.closeCalls == 1 })
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
                mapOf(route.routeId to "proceed"), model())),
            attachedSignals = listOf(AttachedSignalDefinition("test:current-repeater", 1, "test:current",
                DeviceModelDefinition("demo_signals:wall", ModelPlacement.Ground(ModelPose.IDENTITY))),
                AttachedSignalDefinition("test:fixed-repeater", 1, "test:fixed", model())))
        val world = SimpleSignalsWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, world.initializeDefinitions(topology, definitions).status)
        val occupancy = world.initializeSectionOccupancy()
        val service = RecordingModels()
        val errors = mutableListOf<String>()
        val binding = SignalModelBinding(plugin, worldId, topology.revision,
            PreparedSignalModels.prepare(definitions, models, rails, topology), service, { context, _ -> errors += context }, world.modelHandles)
        assertTrue(world.attachModels(world.authoringPublication(), binding))
        world.startModels()
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
        assertEquals(mapOf("lamp" to "red"), service.byId("attached-signal/test:current-repeater").submissions.last().animationScenes())
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
        assertEquals(mapOf("lamp" to "green"), service.byId("attached-signal/test:current-repeater").submissions.last().animationScenes())
        assertEquals(mapOf("lamp" to "green"), service.byId("attached-signal/test:fixed-repeater").submissions.last().animationScenes())
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
        val replacement = binding(prepared.definitions, f.service, f.errors, f.world)
        occupancy.attachModelBinding(replacement)
        f.service.beforeCreate = {
            assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED,
                f.world.commitPrepared(prepared, occupancy, replacement))
            f.service.beforeCreate = {}
        }

        f.start()

        assertEquals(3, f.service.handles.size)
        assertTrue(f.service.handles.first().closed)
        assertTrue(f.service.handles.first().submissions.isEmpty())
        assertTrue(f.errors.isEmpty(), f.errors.toString())
        f.world.startModels()
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
            val binding = binding(prepared.definitions, f.service, f.errors, f.world)
            occupancy.attachModelBinding(binding)
            assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, f.world.commitPrepared(prepared, occupancy, binding))
            return binding
        }
        val first = replacement(2)
        val second = replacement(3)
        f.world.startModels()
        assertFalse(original.closed)
        assertEquals(1, f.service.handles.size)
        val current = f.service.handles.last()
        f.world.startModels()
        first.start({ false }) { published(10, "proceed") }
        f.binding.published(f.occupancy.presentationSnapshot() ?: published(5, "proceed"), listOf("test:first"), emptyList())
        assertFalse(current.closed)
        assertEquals(1, current.submissions.size)
        assertTrue(f.errors.isEmpty())
        f.world.retire()
        f.world.closeModels()
        assertTrue(current.closed)
    }

    @Test
    fun `old epoch callback already evaluating cannot repaint or close a retained handle after commit`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(rule = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            SectionSignalOutput(12.0, "proceed")
        })
        f.start()
        val handle = f.service.handles.single()
        val oldCallback = CompletableFuture.runAsync { f.process(1, false) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val replacement = replace(f, definitions(model(), revision = 2))
            f.world.startModels()
            replacement.published(published(2, "stop"), listOf("test:first"), emptyList())
            assertEquals(mapOf("lamp" to "red"), handle.submissions.last().animationScenes())
        } finally { release.countDown() }
        oldCallback.get(5, TimeUnit.SECONDS)
        f.world.startModels()
        f.binding.published(published(20, "proceed"), listOf("test:first"), emptyList())
        assertEquals(1, f.service.handles.size)
        assertFalse(handle.closed)
        assertEquals(mapOf("lamp" to "red"), handle.submissions.last().animationScenes())
        assertEquals(listOf(0L, 1L), handle.submissions.map { it.revision() })
    }

    @Test
    fun `publication swap fences a public submit already in flight without replacing its handle`() {
        val f = fixture()
        f.start()
        val handle = f.service.handles.single()
        val submitting = CountDownLatch(1)
        val release = CountDownLatch(1)
        handle.beforeSubmit = {
            submitting.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val oldCallback = CompletableFuture.runAsync { f.process(1, true) }
        assertTrue(submitting.await(5, TimeUnit.SECONDS))
        val committing = CountDownLatch(1)
        val commit = CompletableFuture.supplyAsync {
            replace(f, definitions(model(), revision = 2), beforeCommit = { committing.countDown() })
        }
        try {
            assertTrue(committing.await(5, TimeUnit.SECONDS))
            assertFalse(commit.isDone, "publication must fence the old submit before reusing the handle")
            assertEquals(1, f.service.handles.size)
        } finally { release.countDown() }
        oldCallback.get(5, TimeUnit.SECONDS)
        val replacement = commit.get(5, TimeUnit.SECONDS)
        handle.beforeSubmit = {}
        f.world.startModels()
        replacement.published(published(2, "proceed"), listOf("test:first"), emptyList())
        f.binding.published(published(20, "stop"), listOf("test:first"), emptyList())
        assertSame(handle, f.service.handles.single())
        assertEquals(0, handle.closeCalls)
        assertEquals(mapOf("lamp" to "green"), handle.submissions.last().animationScenes())
        assertEquals((0L..3L).toList(), handle.submissions.map { it.revision() })
    }

    @Test
    fun `failed or cancelled additions clean only new handles and retained devices keep working`() {
        val f = fixture()
        f.start()
        val retained = f.service.handles.single()
        val two = definitions(model(), two = true, revision = 2)
        val thirdBlock = FixedBlockDefinition("test:third-block", 1,
            listOf(FixedBlockTraversal(railId, ViewDirection.BA, 2.0, 4.0)))
        val third = FixedBlockSignalDefinition("test:third", 1, thirdBlock.blockId, 0.0,
            ConfiguredSignalOutput(0.0, "stop"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed"))), model = model())
        val three = SimpleSignalsWorldDefinitions(worldId, 2, emptyList(), emptyList(), two.fixedBlocks + thirdBlock,
            two.fixedBlockSignals + third)
        val added = replace(f, three)
        f.service.failCreate = 3
        f.world.startModels()
        assertEquals(2, f.service.handles.size)
        assertEquals(0, retained.closeCalls)
        assertEquals(1, f.service.handles.last().closeCalls)
        added.published(published(1, "proceed"), listOf("test:first"), emptyList())
        assertEquals(mapOf("lamp" to "green"), retained.submissions.last().animationScenes())
        assertEquals(1, f.errors.size)

        // A later committed edit can retry the missing additions. Supersede during create;
        // the lifecycle worker must reconcile the final collection, preserving the first.
        f.service.failCreate = -1
        val again = SimpleSignalsWorldDefinitions(worldId, 3, emptyList(), emptyList(), three.fixedBlocks, three.fixedBlockSignals)
        replace(f, again)
        f.service.beforeCreate = {
            f.service.beforeCreate = {}
            replace(f, definitions(model(), revision = 4))
        }
        f.world.startModels()
        assertTrue(f.service.handles.drop(1).all { it.closed })
        assertEquals(0, retained.closeCalls)
        assertSame(retained, f.service.byId(retained.id))
        assertEquals(1, f.errors.size, "supersession is cancellation, not a rendering failure")
        f.world.retire()
        f.world.closeModels()
        assertTrue(f.service.handles.all { it.closeCalls == 1 })
    }

    @Test
    fun `same model identity with changed immutable tree replaces only the matching device`() {
        val f = fixture(two = true)
        f.start()
        val first = f.service.byId("fixed-block-signal/test:first")
        val oldSecond = f.service.byId("fixed-block-signal/test:second")
        val previous = definitions(model(), two = true, revision = 2)
        val second = previous.fixedBlockSignals[1]
        val secondWall = FixedBlockSignalDefinition(second.signalId, 2, second.controlledBlockId, 0.0,
            second.occupiedOutput, second.clearLevels, model = DeviceModelDefinition("demo_signals:wall", model().placement))
        val mixed = SimpleSignalsWorldDefinitions(worldId, 2, emptyList(), emptyList(), previous.fixedBlocks,
            listOf(previous.fixedBlockSignals[0], secondWall))
        replace(f, mixed)
        f.world.startModels()
        assertTrue(oldSecond.closed)
        val wall = f.service.byId(oldSecond.id)
        val loaded = models
        val home = loaded.getValue("demo_signals:home")
        val altered = LoadedPackageModel(home.id, ModelDefinition(home.definition.parts() +
            ModelPart.Group(ModelPose.IDENTITY, emptyList(), emptyList())), home.defaultScenes, home.variants,
            home.followRailTilt, home.railPlacementPresets)
        replace(f, SimpleSignalsWorldDefinitions(worldId, 3, emptyList(), emptyList(), mixed.fixedBlocks,
            mixed.fixedBlockSignals), loaded + (home.id to altered))
        f.world.startModels()
        assertEquals(1, first.closeCalls)
        assertNotSame(first, f.service.byId(first.id))
        assertSame(wall, f.service.byId(wall.id))
        assertEquals(0, wall.closeCalls)
        assertEquals(4, f.service.handles.size)
    }

    private fun replace(f: Fixture, definitions: SimpleSignalsWorldDefinitions,
        loaded: Map<String, LoadedPackageModel> = models, beforeCommit: () -> Unit = {}): SignalModelBinding {
        val prepared = (f.world.prepareDefinitions(topology, definitions) as SimpleSignalsDefinitionPreparationResult.Prepared).value
        val occupancy = SectionOccupancyRuntime.from(prepared.candidatePublication)
        val binding = SignalModelBinding(plugin, worldId, topology.revision,
            PreparedSignalModels.prepare(definitions, loaded, rails, topology), f.service,
            { context, _ -> f.errors += context }, f.world.modelHandles)
        occupancy.attachModelBinding(binding)
        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED,
            f.world.commitPrepared(prepared, occupancy, binding, beforeCommit))
        return binding
    }

    private fun published(epoch: Long, variant: String): SectionOccupancyRuntime.PublishedState {
        val stamp = RailwayStateStamp(worldId.toString(), epoch, topology.revision)
        return SectionOccupancyRuntime.PublishedState(SectionOccupancySnapshot(stamp, emptyMap(), emptyList(), emptyMap()),
            SectionSignalSnapshot(stamp, mapOf("test:first" to SectionSignalState.Available(SectionSignalOutput(1.0, variant))),
                listOf("test:first")), null, emptyMap())
    }

    private fun model() = DeviceModelDefinition("demo_signals:home", ModelPlacement.Ground(ModelPose.IDENTITY))

    private fun definitions(model: DeviceModelDefinition?, two: Boolean = false, revision: Long = 1): SimpleSignalsWorldDefinitions {
        val blocks = listOf(FixedBlockDefinition("test:first-block", 1,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 2.0, 4.0)))) + if (two) listOf(FixedBlockDefinition("test:second-block", 1,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 6.0, 8.0)))) else emptyList()
        return SimpleSignalsWorldDefinitions(worldId, revision, emptyList(), emptyList(), blocks,
            blocks.mapIndexed { index, block -> FixedBlockSignalDefinition(if (index == 0) "test:first" else "test:second", 1,
                block.blockId, 0.0, ConfiguredSignalOutput(0.0, "stop"),
                listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "proceed"))), model = model) })
    }

    private fun binding(definitions: SimpleSignalsWorldDefinitions, service: RecordingModels,
        errors: MutableList<String>, world: SimpleSignalsWorldRuntime,
        report: ((String, Throwable) -> Unit)? = null) = SignalModelBinding(plugin, worldId,
        topology.revision, PreparedSignalModels.prepare(definitions, models, rails, topology), service,
        report ?: { context, _ -> errors += context }, world.modelHandles)

    private fun fixture(two: Boolean = false, rule: ((SectionSignalRuleInput) -> SectionSignalOutput)? = null,
        report: ((String, Throwable) -> Unit)? = null,
        definitionOverride: SimpleSignalsWorldDefinitions? = null): Fixture {
        val definitions = definitionOverride ?: definitions(model(), two)
        val world = SimpleSignalsWorldRuntime(worldId)
        world.initializeDefinitions(topology, definitions)
        val occupancy = if (rule == null) world.initializeSectionOccupancy() else world.initializeSectionOccupancy(
            world.authoringPublication().fixedBlockSignals.bindings.map { original -> SectionSignalRuleBinding(original.signalId,
                original.controlledSection, emptyList()) { rule(it) } })
        val service = RecordingModels()
        val errors = mutableListOf<String>()
        val binding = binding(definitions, service, errors, world, report)
        assertTrue(world.attachModels(world.authoringPublication(), binding))
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision) })
        epoch.replaceSources(occupancy.sources)
        return Fixture(world, occupancy, service, binding, errors, epoch)
    }

    private fun withAttached(base: SimpleSignalsWorldDefinitions, attached: List<AttachedSignalDefinition>,
        revision: Long = base.definitionRevision) = SimpleSignalsWorldDefinitions(worldId, revision, base.regions,
        base.routeTables, base.fixedBlocks, base.fixedBlockSignals, base.protectionSignals, base.routeIndicators, attached)

    private inner class Fixture(val world: SimpleSignalsWorldRuntime, val occupancy: SectionOccupancyRuntime,
        val service: RecordingModels, val binding: SignalModelBinding, val errors: MutableList<String>, val epoch: SignalEpochRuntime) {
        fun start() = world.startModels()
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
            var closeCalls = 0
            var beforeSubmit: () -> Unit = {}
            override fun submit(presentation: ModelPresentation) {
                if (closed) return
                if (failSubmit) error("submit failed")
                beforeSubmit()
                submissions += presentation
            }
            override fun close() { closeCalls++; closed = true }
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
            handles.filterNot { it.closed }.forEach(Handle::close)
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
