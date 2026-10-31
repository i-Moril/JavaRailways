package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.signal.*
import java.util.UUID

class SignalEditorProblemsTest {
    @Test fun `normal no request is healthy while explicit unbound devices and their attachments glow before baseline`() {
        val f = Fixture()
        assertEquals(setOf("fixed-block-signal/test:unbound", "protection-signal/test:unbound-protection",
            "attached-signal/test:attached-unbound", "route-indicator/test:unbound-indicator",
            "route-indicator/test:no-routes-indicator"), f.policy().models(emptyList()))
        f.runtime.retire()
    }

    @Test fun `shared signal faults include their attached models and clearing issues clears highlight with same policy`() {
        val f = Fixture()
        val policy = f.policy()
        val baseline = policy.models(emptyList())
        val issues = WorldIssueService()
        for (source in listOf("simplesignals:topology", "simplesignals:rules", "simplesignals:models",
            "simplesignals:preparation", "simplesignals:model-preparation")) {
            issues.report(f.world, source, WorldIssueInput(WorldIssueKind.SIGNAL, "test:healthy", "fault", "broken"))
            assertEquals(baseline + setOf("protection-signal/test:healthy", "attached-signal/test:attached"),
                policy.models(issues.snapshot(f.world)))
            issues.clearSource(source)
            assertEquals(baseline, policy.models(issues.snapshot(f.world)))
        }
        issues.report(f.world, "simplesignals:models", WorldIssueInput(WorldIssueKind.SIGNAL,
            "test:attached", "fault", "broken"))
        assertEquals(baseline + "attached-signal/test:attached", policy.models(issues.snapshot(f.world)))
        issues.clearSource("simplesignals:models")
        issues.report(f.world, "other:rules", WorldIssueInput(WorldIssueKind.SIGNAL, "test:healthy", "fault", "broken"))
        issues.report(f.world, "simplesignals:rules", WorldIssueInput(WorldIssueKind.TRAIN, "test:healthy", "fault", "broken"))
        assertEquals(baseline, policy.models(issues.snapshot(f.world)))
        f.runtime.retire()
    }

    @Test fun `pending topology faults mark bound devices and static entry indicators without needing a train request`() {
        val f = Fixture()
        val next = lineTopologyState(2, f.rail)
        f.runtime.topologyGuard.invalidate(RailTopologyChange(f.topology, next, setOf(f.rail)))
        val models = f.policy().models(emptyList())
        assertTrue("protection-signal/test:healthy" in models)
        assertTrue("attached-signal/test:attached" in models)
        assertTrue("fixed-block-signal/test:block-signal" in models)
        assertTrue("route-indicator/test:indicator" in models)
        f.runtime.retire()
    }

    private class Fixture {
        val world = UUID(0, 1)
        val rail = UUID(0, 2)
        val topology = lineTopologyState(1, rail)
        val runtime = SimpleSignalsWorldRuntime(world)
        val placement = ModelPlacement.Ground(ModelPose.IDENTITY)
        val model = DeviceModelDefinition("test:model", placement)
        init {
            val region = ProtectionRegionDefinition("test:region", 0, listOf(
                ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(rail, ViewDirection.AB, 2.0)),
                ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(rail, ViewDirection.BA, 8.0))))
            val route = ProtectionRouteDefinition("test:route", "test:entry", "test:exit",
                listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 2.0, 8.0)))
            val definitions = SimpleSignalsWorldDefinitions(world, 1, listOf(region),
                listOf(ProtectionRouteTableDefinition("test:table", 0, region.regionId, 0, listOf(route))),
                fixedBlocks = listOf(FixedBlockDefinition("test:block", 0,
                    listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.0, 1.0)))),
                fixedBlockSignals = listOf(
                    FixedBlockSignalDefinition("test:block-signal", 0, "test:block", 0.0, "test:type", placement = placement),
                    FixedBlockSignalDefinition("test:unbound", 0, null, 0.0, "test:type", placement = placement)),
                protectionSignals = listOf(
                    ProtectionSignalDefinition("test:healthy", 0, ProtectionSignalKind.FIXED_ROUTE, "test:route", 0.0, "test:type", placement),
                    ProtectionSignalDefinition("test:current", 0, ProtectionSignalKind.CURRENT_ENTRY, "test:entry", 0.0, "test:type", placement),
                    ProtectionSignalDefinition("test:unbound-protection", 0, ProtectionSignalKind.FIXED_ROUTE, null, 0.0, "test:type", placement)),
                routeIndicators = listOf(
                    RouteIndicatorDefinition("test:indicator", 0, "test:entry", "none", emptyMap(), model),
                    RouteIndicatorDefinition("test:unbound-indicator", 0, null, "none", emptyMap(), model),
                    RouteIndicatorDefinition("test:no-routes-indicator", 0, "test:exit", "none", emptyMap(), model)),
                attachedSignals = listOf(
                    AttachedSignalDefinition("test:attached", 0, "test:healthy", model),
                    AttachedSignalDefinition("test:attached-unbound", 0, "test:unbound", model)))
            val result = runtime.initializeDefinitions(topology, definitions)
            assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.toString())
        }
        fun policy() = SignalEditorProblems.prepare(runtime.authoringPublication(), runtime.topologyGuard.capture())
    }
}
