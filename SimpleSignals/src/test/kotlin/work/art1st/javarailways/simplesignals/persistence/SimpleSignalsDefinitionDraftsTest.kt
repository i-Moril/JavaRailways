package work.art1st.javarailways.simplesignals.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.signal.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SimpleSignalsDefinitionDraftsTest {
    @TempDir lateinit var directory: Path
    private val world = UUID(0, 20)
    private val rail = UUID(0, 1)

    @Test fun `export uses current codec and preserves existing draft`() {
        val transport = SimpleSignalsDefinitionDrafts(directory)
        val definitions = document("one", 1)
        transport.export("draft-one", definitions)
        val path = directory.resolve("drafts/draft-one.json")
        assertEquals(SimpleSignalsWorldDefinitionsCodec.encode(definitions), Files.readString(path))
        assertEquals(definitions, transport.read("draft-one", world))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { transport.export("draft-one", document("two", 2)) }
        assertEquals(definitions, transport.read("draft-one", world))
        assertFalse(Files.exists(directory.resolve("worlds")))
    }

    @Test fun `reject traversal arbitrary paths symlink files and symlink parents`() {
        val transport = SimpleSignalsDefinitionDrafts(directory)
        listOf("../worlds/primary", "..", "a.json", "a/b", "/tmp/x", "a\\b", "", ".hidden").forEach { stem ->
            assertThrows(IllegalArgumentException::class.java) { transport.export(stem, document("one", 1)) }
        }
        assertFalse(Files.exists(directory.resolve("drafts")))
        transport.export("good", document("one", 1))
        Files.createSymbolicLink(directory.resolve("drafts/link.json"), directory.resolve("drafts/good.json"))
        assertThrows(IllegalArgumentException::class.java) { transport.read("link", world) }
        assertThrows(IllegalArgumentException::class.java) { transport.export("link", document("two", 2)) }
        val alias = directory.resolve("alias")
        Files.createSymbolicLink(alias, directory.resolve("drafts"))
        assertThrows(IllegalArgumentException::class.java) { SimpleSignalsDefinitionDrafts(alias).export("bad", document("one", 1)) }
        assertFalse(Files.exists(directory.resolve("drafts/drafts")))
    }

    @Test fun `strict draft read rejects wrong world unknown fields versions and nonfiles without writes`() {
        val transport = SimpleSignalsDefinitionDrafts(directory)
        assertThrows(IllegalArgumentException::class.java) { transport.read("missing", world) }
        assertFalse(Files.exists(directory.resolve("drafts")))
        transport.export("source", document("one", 1))
        val original = Files.readString(directory.resolve("drafts/source.json"))
        assertThrows(IllegalArgumentException::class.java) { transport.read("source", UUID(0, 99)) }
        Files.writeString(directory.resolve("drafts/unknown.json"), original.replaceFirst("{", "{\"typo\":1,"))
        Files.writeString(directory.resolve("drafts/future.json"), original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        Files.createDirectory(directory.resolve("drafts/nonfile.json"))
        assertThrows(Exception::class.java) { transport.read("unknown", world) }
        assertThrows(IllegalArgumentException::class.java) { transport.read("future", world) }
        assertThrows(IllegalArgumentException::class.java) { transport.read("nonfile", world) }
        assertEquals(original, Files.readString(directory.resolve("drafts/source.json")))
    }

    @Test fun `all six definition kinds produce exact create update delete diff and reject duplicates`() {
        val original = document("old", 1)
        val changed = document("old", 2)
        val created = document("new", 1)
        val deleted = document("deleted", 1)
        val before = combine(original, deleted)
        val after = combine(changed, created)
        val patch = definitionDraftPatch(before, after)
        assertEquals(changed.regions + created.regions, patch.upsertRegions)
        assertEquals(changed.routeTables + created.routeTables, patch.upsertRouteTables)
        assertEquals(changed.fixedBlocks + created.fixedBlocks, patch.upsertFixedBlocks)
        assertEquals(changed.fixedBlockSignals + created.fixedBlockSignals, patch.upsertFixedBlockSignals)
        assertEquals(changed.protectionSignals + created.protectionSignals, patch.upsertProtectionSignals)
        assertEquals(changed.routeIndicators + created.routeIndicators, patch.upsertRouteIndicators)
        assertEquals(deleted.regions.map { it.regionId }, patch.removeRegionIds)
        assertEquals(deleted.routeTables.map { it.tableId }, patch.removeRouteTableIds)
        assertEquals(deleted.fixedBlocks.map { it.blockId }, patch.removeFixedBlockIds)
        assertEquals(deleted.fixedBlockSignals.map { it.signalId }, patch.removeFixedBlockSignalIds)
        assertEquals(deleted.protectionSignals.map { it.signalId }, patch.removeProtectionSignalIds)
        assertEquals(deleted.routeIndicators.map { it.indicatorId }, patch.removeRouteIndicatorIds)
        assertTrue(definitionDraftSummary(before, after).startsWith("create=6, update=6, delete=6"))
        assertTrue(definitionDraftPatch(original, original).upsertRegions.isEmpty())
        val duplicateDocuments = listOf(
            SimpleSignalsWorldDefinitions(world, 7, original.regions + original.regions, original.routeTables,
                original.fixedBlocks, original.fixedBlockSignals, original.protectionSignals, original.routeIndicators),
            SimpleSignalsWorldDefinitions(world, 7, original.regions, original.routeTables + original.routeTables,
                original.fixedBlocks, original.fixedBlockSignals, original.protectionSignals, original.routeIndicators),
            SimpleSignalsWorldDefinitions(world, 7, original.regions, original.routeTables,
                original.fixedBlocks + original.fixedBlocks, original.fixedBlockSignals, original.protectionSignals, original.routeIndicators),
            SimpleSignalsWorldDefinitions(world, 7, original.regions, original.routeTables,
                original.fixedBlocks, original.fixedBlockSignals + original.fixedBlockSignals, original.protectionSignals, original.routeIndicators),
            SimpleSignalsWorldDefinitions(world, 7, original.regions, original.routeTables,
                original.fixedBlocks, original.fixedBlockSignals, original.protectionSignals + original.protectionSignals, original.routeIndicators),
            SimpleSignalsWorldDefinitions(world, 7, original.regions, original.routeTables,
                original.fixedBlocks, original.fixedBlockSignals, original.protectionSignals, original.routeIndicators + original.routeIndicators))
        duplicateDocuments.forEach { duplicate ->
            assertThrows(IllegalArgumentException::class.java) { definitionDraftPatch(original, duplicate) }
        }
    }

    private fun combine(a: SimpleSignalsWorldDefinitions, b: SimpleSignalsWorldDefinitions) = SimpleSignalsWorldDefinitions(
        world, 7, a.regions + b.regions, a.routeTables + b.routeTables, a.fixedBlocks + b.fixedBlocks,
        a.fixedBlockSignals + b.fixedBlockSignals, a.protectionSignals + b.protectionSignals, a.routeIndicators + b.routeIndicators)

    private fun document(suffix: String, revision: Long): SimpleSignalsWorldDefinitions {
        val stop = ConfiguredSignalOutput(0.0, "stop")
        val levels = listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(12.0, "clear")))
        val region = ProtectionRegionDefinition("test:region-$suffix", revision, listOf(
            ProtectionBoundaryDefinition("test:entry-$suffix", RailPositionSnapshot(rail, ViewDirection.AB, 1.0)),
            ProtectionBoundaryDefinition("test:exit-$suffix", RailPositionSnapshot(rail, ViewDirection.BA, 5.0))))
        val route = ProtectionRouteDefinition("test:route-$suffix", "test:entry-$suffix", "test:exit-$suffix",
            listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 1.0, 5.0)))
        return SimpleSignalsWorldDefinitions(world, 7, listOf(region), listOf(ProtectionRouteTableDefinition(
            "test:table-$suffix", revision, region.regionId, revision, listOf(route))),
            listOf(FixedBlockDefinition("test:block-$suffix", revision, listOf(FixedBlockTraversal(rail, ViewDirection.AB, 6.0, 9.0)))),
            listOf(FixedBlockSignalDefinition("test:fixed-$suffix", revision, "test:block-$suffix", 0.0, stop, levels)),
            listOf(ProtectionSignalDefinition("test:protection-$suffix", revision, ProtectionSignalKind.FIXED_ROUTE,
                route.routeId, 0.0, stop, stop, levels)),
            listOf(RouteIndicatorDefinition("test:indicator-$suffix", revision, "test:entry-$suffix",
                RailPositionSnapshot(rail, ViewDirection.AB, 1.0), "none", mapOf(route.routeId to "selected"))))
    }
}
