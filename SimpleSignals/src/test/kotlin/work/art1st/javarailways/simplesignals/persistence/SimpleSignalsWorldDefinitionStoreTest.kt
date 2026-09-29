package work.art1st.javarailways.simplesignals.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.signal.ConfiguredSignalOutput
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalClearLevel
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDependency
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SimpleSignalsWorldDefinitionStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `strict current fixture round trips without derived topology`() {
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(CURRENT_FIXTURE, WORLD_ID)
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(decoded)
        val roundTrip = SimpleSignalsWorldDefinitionsCodec.decode(encoded, WORLD_ID)

        assertEquals(decoded, roundTrip)
        assertEquals(9L, roundTrip.definitionRevision)
        assertEquals("simplesignals:test-region", roundTrip.regions.single().regionId)
        assertEquals(ViewDirection.BA, roundTrip.regions.single().boundaries[1].insideFacing)
        assertEquals(3, roundTrip.routeTables.single().routes.single().traversals.size)
        assertEquals(emptyList<FixedBlockDefinition>(), roundTrip.fixedBlocks)
        assertEquals(emptyList<FixedBlockSignalDefinition>(), roundTrip.fixedBlockSignals)
        assertTrue(encoded.contains("\"fixedBlocks\": []"))
        assertTrue(encoded.contains("\"fixedBlockSignals\": []"))
        assertFalse(encoded.contains("interior"))
        assertFalse(encoded.contains("topologyRevision"))
    }

    @Test
    fun `fixed-block signals round trip within schema one`() {
        val block = FixedBlockDefinition(
            "simplesignals:block",
            3L,
            listOf(FixedBlockTraversal(ENTRY_RAIL, ViewDirection.AB, 2.0, 10.0))
        )
        val signal = FixedBlockSignalDefinition(
            "simplesignals:signal",
            2L,
            block.blockId,
            320.0,
            ConfiguredSignalOutput(0.0, "occupied/custom"),
            listOf(
                FixedBlockSignalClearLevel(
                    0.0,
                    ConfiguredSignalOutput(12.5, "clear/restrictive")
                ),
                FixedBlockSignalClearLevel(
                    20.0,
                    ConfiguredSignalOutput(33.0, "clear/permissive")
                )
            ),
            listOf(FixedBlockSignalDependency("simplesignals:next-signal", 180.0))
        )
        val definitions = SimpleSignalsWorldDefinitions(
            WORLD_ID,
            11L,
            emptyList(),
            emptyList(),
            listOf(block),
            listOf(signal)
        )

        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(encoded, WORLD_ID)

        assertEquals(definitions, decoded)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertTrue(encoded.contains("\"inspectionDistanceMeters\": 320.0"))
        assertTrue(encoded.contains("\"modelVariant\": \"clear/permissive\""))
    }

    @Test
    fun `fixed blocks round trip within schema one and reject malformed domain input`() {
        val definitions = SimpleSignalsWorldDefinitions(
            WORLD_ID,
            10L,
            emptyList(),
            emptyList(),
            listOf(
                FixedBlockDefinition(
                    "simplesignals:block",
                    3L,
                    listOf(
                        FixedBlockTraversal(ENTRY_RAIL, ViewDirection.AB, 2.0, 10.0),
                        FixedBlockTraversal(MIDDLE_RAIL, ViewDirection.BA, 0.0, 10.0)
                    )
                )
            )
        )

        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(encoded, WORLD_ID)

        assertEquals(definitions, decoded)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertTrue(encoded.contains("\"fixedBlocks\""))

        val emptyTraversal = encoded.replace(
            Regex("\\\"traversals\\\": \\[.*?]", RegexOption.DOT_MATCHES_ALL),
            "\"traversals\": []"
        )
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(emptyTraversal, WORLD_ID)
        }
    }

    @Test
    fun `strict codec rejects unknown fields versions and wrong world identity`() {
        val unknownField = CURRENT_FIXTURE.replace(
            "\"definitionRevision\": 9,",
            "\"definitionRevision\": 9,\n  \"unexpected\": true,"
        )
        val futureVersion = CURRENT_FIXTURE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val nonCanonicalWorldId = CURRENT_FIXTURE.replace(
            WORLD_ID.toString(),
            WORLD_ID.toString().uppercase()
        )

        assertThrows(Exception::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(unknownField, WORLD_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(futureVersion, WORLD_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(CURRENT_FIXTURE, UUID.randomUUID())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(nonCanonicalWorldId, WORLD_ID)
        }
    }

    @Test
    fun `invalid primary recovers valid backup without changing either file`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        val primary = store.primaryFile(WORLD_ID)
        val backup = store.backupFile(WORLD_ID)
        Files.createDirectories(primary.parent)
        Files.writeString(primary, "{broken")
        Files.writeString(backup, CURRENT_FIXTURE)

        val loaded = assertInstanceOf(
            SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(WORLD_ID)
        )

        assertEquals(SimpleSignalsWorldDefinitionSource.BACKUP, loaded.source)
        assertEquals(9L, loaded.definitions.definitionRevision)
        assertEquals(1, loaded.ignoredFailures.size)
        assertEquals("{broken", Files.readString(primary))
        assertEquals(CURRENT_FIXTURE, Files.readString(backup))
    }

    @Test
    fun `two invalid candidates stay on disk and report corruption`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        val primary = store.primaryFile(WORLD_ID)
        val backup = store.backupFile(WORLD_ID)
        Files.createDirectories(primary.parent)
        Files.writeString(primary, "primary-broken")
        Files.writeString(backup, "backup-broken")

        val corrupted = assertInstanceOf(
            SimpleSignalsWorldDefinitionLoadResult.Corrupted::class.java,
            store.load(WORLD_ID)
        )

        assertEquals(2, corrupted.failures.size)
        assertEquals("primary-broken", Files.readString(primary))
        assertEquals("backup-broken", Files.readString(backup))
    }

    @Test
    fun `non readable candidate shape is corruption rather than missing definitions`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        Files.createDirectories(store.primaryFile(WORLD_ID))

        val corrupted = assertInstanceOf(
            SimpleSignalsWorldDefinitionLoadResult.Corrupted::class.java,
            store.load(WORLD_ID)
        )

        assertEquals(1, corrupted.failures.size)
    }

    @Test
    fun `save replaces primary atomically and retains previous primary as backup`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        val first = definitions(1L)
        val second = definitions(2L)

        store.save(WORLD_ID, first)
        assertFalse(Files.exists(store.backupFile(WORLD_ID)))
        store.save(WORLD_ID, second)

        val primary = SimpleSignalsWorldDefinitionsCodec.decode(
            Files.readString(store.primaryFile(WORLD_ID)),
            WORLD_ID
        )
        val backup = SimpleSignalsWorldDefinitionsCodec.decode(
            Files.readString(store.backupFile(WORLD_ID)),
            WORLD_ID
        )
        assertEquals(2L, primary.definitionRevision)
        assertEquals(1L, backup.definitionRevision)
        assertFalse(Files.exists(store.temporaryFile(WORLD_ID)))
    }

    @Test
    fun `prepared save leaves primary unchanged until its bounded commit`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        store.save(WORLD_ID, definitions(1L))
        val primaryBefore = Files.readString(store.primaryFile(WORLD_ID))

        store.prepareSave(WORLD_ID, definitions(2L)).use { prepared ->
            assertEquals(primaryBefore, Files.readString(store.primaryFile(WORLD_ID)))
            assertTrue(Files.exists(store.temporaryFile(WORLD_ID)))

            prepared.commitPrimary()

            assertEquals(
                2L,
                SimpleSignalsWorldDefinitionsCodec.decode(
                    Files.readString(store.primaryFile(WORLD_ID)),
                    WORLD_ID
                ).definitionRevision
            )
            assertFalse(Files.exists(store.backupFile(WORLD_ID)))
        }

        assertEquals(
            1L,
            SimpleSignalsWorldDefinitionsCodec.decode(
                Files.readString(store.backupFile(WORLD_ID)),
                WORLD_ID
            ).definitionRevision
        )
        assertFalse(Files.exists(store.temporaryFile(WORLD_ID)))
        assertFalse(Files.exists(store.backupTemporaryFile(WORLD_ID)))
    }

    @Test
    fun `save after backup recovery does not replace the last valid backup with corrupt primary`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        val primary = store.primaryFile(WORLD_ID)
        val backup = store.backupFile(WORLD_ID)
        Files.createDirectories(primary.parent)
        Files.writeString(primary, "{broken")
        Files.writeString(backup, CURRENT_FIXTURE)

        store.save(WORLD_ID, definitions(10L))

        assertEquals(
            10L,
            SimpleSignalsWorldDefinitionsCodec.decode(Files.readString(primary), WORLD_ID)
                .definitionRevision
        )
        assertEquals(
            9L,
            SimpleSignalsWorldDefinitionsCodec.decode(Files.readString(backup), WORLD_ID)
                .definitionRevision
        )
    }

    @Test
    fun `save failure before the commit point preserves valid primary and backup`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        store.save(WORLD_ID, definitions(1L))
        store.save(WORLD_ID, definitions(2L))
        val primary = store.primaryFile(WORLD_ID)
        val backup = store.backupFile(WORLD_ID)
        val primaryBefore = Files.readString(primary)
        val backupBefore = Files.readString(backup)
        val failingStore = SimpleSignalsWorldDefinitionStore(directory.toFile()) {
            throw java.io.IOException("injected before primary replace")
        }

        assertThrows(java.io.IOException::class.java) {
            failingStore.save(WORLD_ID, definitions(3L))
        }

        assertEquals(primaryBefore, Files.readString(primary))
        assertEquals(backupBefore, Files.readString(backup))
        assertFalse(Files.exists(failingStore.temporaryFile(WORLD_ID)))
        assertFalse(Files.exists(failingStore.backupTemporaryFile(WORLD_ID)))
    }

    private fun definitions(revision: Long) = SimpleSignalsWorldDefinitions(
        WORLD_ID,
        revision,
        emptyList(),
        emptyList()
    )

    companion object {
        private val WORLD_ID: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000001")
        private val ENTRY_RAIL: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val MIDDLE_RAIL: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        private val EXIT_RAIL: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")

        private val CURRENT_FIXTURE = """
            {
              "schemaVersion": 1,
              "worldId": "$WORLD_ID",
              "definitionRevision": 9,
              "protectionRegions": [
                {
                  "regionId": "simplesignals:test-region",
                  "revision": 4,
                  "boundaries": [
                    {
                      "boundaryId": "simplesignals:entry",
                      "railId": "$ENTRY_RAIL",
                      "arcLengthInAbMeters": 2.5,
                      "insideFacing": "AB"
                    },
                    {
                      "boundaryId": "simplesignals:exit",
                      "railId": "$EXIT_RAIL",
                      "arcLengthInAbMeters": 7.5,
                      "insideFacing": "BA"
                    }
                  ]
                }
              ],
              "protectionRouteTables": [
                {
                  "tableId": "simplesignals:test-table",
                  "revision": 6,
                  "regionId": "simplesignals:test-region",
                  "regionRevision": 4,
                  "routes": [
                    {
                      "routeId": "simplesignals:test-route",
                      "entryBoundaryId": "simplesignals:entry",
                      "exitBoundaryId": "simplesignals:exit",
                      "traversals": [
                        {
                          "railId": "$ENTRY_RAIL",
                          "facing": "AB",
                          "lowerAbMeters": 2.5,
                          "upperAbMeters": 10.0
                        },
                        {
                          "railId": "$MIDDLE_RAIL",
                          "facing": "AB",
                          "lowerAbMeters": 0.0,
                          "upperAbMeters": 10.0
                        },
                        {
                          "railId": "$EXIT_RAIL",
                          "facing": "AB",
                          "lowerAbMeters": 0.0,
                          "upperAbMeters": 7.5
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
