package work.art1st.javarailways.simplesignals.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SimpleSignalsWorldDefinitionStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test fun `fixed pre-name fixture loads through world store with empty names`() {
        val fixture = requireNotNull(javaClass.getResource("/fixed-block-without-bidirectional.json")).readText()
        val world = UUID(0, 32)
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        Files.createDirectories(store.primaryFile(world).parent)
        Files.writeString(store.primaryFile(world), fixture)
        val loaded = assertInstanceOf(SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java, store.load(world))
        assertTrue(loaded.definitions.boundaryNames.isEmpty())
    }

    @Test
    fun `unbound fixed signals and indicators persist explicit null in current schema`() {
        val signal = FixedBlockSignalDefinition("test:unbound", 2, null, 0.0, "automatic:block")
        val indicator = RouteIndicatorDefinition("test:indicator", 3, null, "stop", emptyMap())
        val document = SimpleSignalsWorldDefinitions(WORLD_ID, 4, emptyList(), emptyList(),
            fixedBlockSignals = listOf(signal), routeIndicators = listOf(indicator))
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(document)
        assertEquals(document, SimpleSignalsWorldDefinitionsCodec.decode(encoded, WORLD_ID))
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, root.getValue("fixedBlockSignals").jsonArray.single().jsonObject.getValue("controlledBlockId"))
        assertEquals(kotlinx.serialization.json.JsonNull, root.getValue("routeIndicators").jsonArray.single().jsonObject.getValue("entryBoundaryId"))
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertThrows(IllegalArgumentException::class.java) {
            RouteIndicatorDefinition("test:invalid", 1, null, "stop", mapOf("test:route" to "proceed"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(encoded.replace("\"controlledBlockId\": null,", ""), WORLD_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(encoded.replace("\"entryBoundaryId\": null,", ""), WORLD_ID)
        }
    }

    @Test
    fun `fixed absent bidirectional fixture defaults false and explicit true stays in current schema`() {
        val fixture = requireNotNull(javaClass.getResource("/fixed-block-without-bidirectional.json")).readText()
        val fixtureWorld = UUID(0L, 32L)
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(fixture, fixtureWorld)
        val block = decoded.fixedBlocks.single()
        assertFalse(block.bidirectional)
        val enabled = SimpleSignalsWorldDefinitions(fixtureWorld, 2L, emptyList(), emptyList(),
            listOf(FixedBlockDefinition(block.blockId, 2L, block.traversals, true)))
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(enabled)
        assertTrue(encoded.contains("\"bidirectional\": true"))
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertEquals(enabled, SimpleSignalsWorldDefinitionsCodec.decode(encoded, fixtureWorld))
        assertTrue(SimpleSignalsWorldDefinitionsCodec.encode(decoded).contains("\"bidirectional\": false"))
        listOf("null", "1", "\"true\"", "[]").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SimpleSignalsWorldDefinitionsCodec.decode(encoded.replace("\"bidirectional\": true",
                    "\"bidirectional\": $invalid"), fixtureWorld)
            }.also { assertTrue(it.message.orEmpty().isNotBlank()) }
        }
    }

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
        assertTrue(roundTrip.pendingProtectionBoundaries.isEmpty())
        assertEquals(null, roundTrip.regions.single().boundaryDraft)
        assertTrue(encoded.contains("\"fixedBlocks\": []"))
        assertTrue(encoded.contains("\"fixedBlockSignals\": []"))
        assertFalse(encoded.contains("defaultRouteIds"))
        assertFalse(encoded.contains("interior"))
        assertFalse(encoded.contains("topologyRevision"))
    }

    @Test
    fun `current codec preserves pending markers and empty boundary draft separately from accepted cuts`() {
        val current = SimpleSignalsWorldDefinitionsCodec.decode(CURRENT_FIXTURE, WORLD_ID)
        val region = current.regions.single()
        val draft = work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition(
            region.regionId, region.revision + 1, region.boundaries, emptyList())
        val value = SimpleSignalsWorldDefinitions(WORLD_ID, 10, listOf(draft), current.routeTables,
            pendingProtectionBoundaries = listOf(region.boundaries.first().cut().copy(boundaryId = "test:pending")))
        val text = SimpleSignalsWorldDefinitionsCodec.encode(value)
        assertEquals(value, SimpleSignalsWorldDefinitionsCodec.decode(text, WORLD_ID))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(text.contains("\"boundaryDraft\": []"))
        assertFalse(text.contains("interior"))
        assertTrue(value.regions.single().effectiveCuts.isEmpty())
        assertEquals(region.boundaries, value.regions.single().boundaries)
    }

    @Test
    fun `pending and repair draft cuts have no direction and reject obsolete facing fields`() {
        val current = SimpleSignalsWorldDefinitionsCodec.decode(CURRENT_FIXTURE, WORLD_ID)
        val accepted = current.regions.single()
        val draft = work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition(
            accepted.regionId, accepted.revision + 1, accepted.boundaries, listOf(accepted.boundaries.last().cut()))
        val value = SimpleSignalsWorldDefinitions(WORLD_ID, 10, listOf(draft), current.routeTables,
            pendingProtectionBoundaries = listOf(accepted.boundaries.first().cut().copy(boundaryId = "test:pending")))
        val text = SimpleSignalsWorldDefinitionsCodec.encode(value)
        assertEquals(value, SimpleSignalsWorldDefinitionsCodec.decode(text, WORLD_ID))
        val root = Json.parseToJsonElement(text).jsonObject
        val pending = root.getValue("pendingProtectionBoundaries").jsonArray.single().jsonObject
        val region = root.getValue("protectionRegions").jsonArray.single().jsonObject
        val cut = region.getValue("boundaryDraft").jsonArray.single().jsonObject
        val fields = setOf("boundaryId", "railId", "arcLengthInAbMeters")
        assertEquals(fields, pending.keys)
        assertEquals(fields, cut.keys)
        assertTrue(region.getValue("boundaries").jsonArray.all { "insideFacing" in it.jsonObject })
        val obsoletePending = JsonObject(pending + ("insideFacing" to JsonPrimitive("AB")))
        val obsoleteDraft = JsonObject(cut + ("insideFacing" to JsonPrimitive("BA")))
        val oldPendingDocument = JsonObject(root + ("pendingProtectionBoundaries" to JsonArray(listOf(obsoletePending))))
        val oldRegion = JsonObject(region + ("boundaryDraft" to JsonArray(listOf(obsoleteDraft))))
        val oldDraftDocument = JsonObject(root + ("protectionRegions" to JsonArray(listOf(oldRegion))))
        assertThrows(IllegalArgumentException::class.java) { SimpleSignalsWorldDefinitionsCodec.decode(oldPendingDocument.toString(), WORLD_ID) }
        assertThrows(IllegalArgumentException::class.java) { SimpleSignalsWorldDefinitionsCodec.decode(oldDraftDocument.toString(), WORLD_ID) }
    }

    @Test
    fun `removed default route field at schema one and unsupported version are rejected without changing files`() {
        val oldField = CURRENT_FIXTURE.replace("\"routes\": [",
            "\"defaultRouteIds\": [], \"routes\": [")
        val oldVersion = CURRENT_FIXTURE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        listOf(oldField, oldVersion).forEach { contents ->
            assertThrows(IllegalArgumentException::class.java) {
                SimpleSignalsWorldDefinitionsCodec.decode(contents, WORLD_ID)
            }
        }
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        Files.createDirectories(store.primaryFile(WORLD_ID).parent)
        Files.writeString(store.primaryFile(WORLD_ID), oldField)
        Files.writeString(store.backupFile(WORLD_ID), oldVersion)
        assertInstanceOf(SimpleSignalsWorldDefinitionLoadResult.Corrupted::class.java, store.load(WORLD_ID))
        assertEquals(oldField, Files.readString(store.primaryFile(WORLD_ID)))
        assertEquals(oldVersion, Files.readString(store.backupFile(WORLD_ID)))
    }

    @Test
    fun `fixed-block signals round trip within current schema`() {
        val block = FixedBlockDefinition(
            "simplesignals:block",
            3L,
            listOf(FixedBlockTraversal(ENTRY_RAIL, ViewDirection.AB, 2.0, 10.0))
        )
        val signal = FixedBlockSignalDefinition(
            "simplesignals:signal",
            2L,
            block.blockId,
            12.0,
            signalType = "automatic:block"
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
        assertTrue(encoded.contains("\"inspectionLeadTimeSeconds\": 12.0"))
        assertTrue(encoded.contains("\"type\": \"automatic:block\""))
        assertFalse(encoded.contains("\"provider\""))
        assertFalse(encoded.contains("\"occupiedOutput\""))
        assertFalse(encoded.contains("\"clearLevels\""))
        assertFalse(encoded.contains("\"downstreamSignals\""))
        assertFalse(encoded.contains("\"distanceMeters\""))
        assertThrows(Exception::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(
                encoded.replace(Regex("""\s*"type": "automatic:block",?"""), ""),
                WORLD_ID
            )
        }
        listOf("provider", "packageSignalId", "occupiedOutput", "clearLevels", "downstreamSignals").forEach { old ->
            assertThrows(Exception::class.java) {
                SimpleSignalsWorldDefinitionsCodec.decode(
                    encoded.replace("\"type\": \"automatic:block\"", "\"type\": \"automatic:block\", \"$old\": null"),
                    WORLD_ID
                )
            }
        }
    }

    @Test
    fun `fixed blocks round trip within current schema and reject malformed domain input`() {
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
        val futureVersion = CURRENT_FIXTURE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
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
    fun `directory preparation failure releases the world for a later save`() {
        val store = SimpleSignalsWorldDefinitionStore(directory.toFile())
        val worldsPath = directory.resolve("worlds")
        Files.writeString(worldsPath, "blocks directory creation")

        assertThrows(Exception::class.java) {
            store.prepareSave(WORLD_ID, definitions(1L))
        }

        Files.delete(worldsPath)
        store.prepareSave(WORLD_ID, definitions(1L)).use {
            assertThrows(IllegalStateException::class.java) {
                store.prepareSave(WORLD_ID, definitions(1L))
            }
        }
        store.save(WORLD_ID, definitions(1L))

        val loaded = assertInstanceOf(
            SimpleSignalsWorldDefinitionLoadResult.Loaded::class.java,
            store.load(WORLD_ID)
        )
        assertEquals(1L, loaded.definitions.definitionRevision)
        assertEquals(SimpleSignalsWorldDefinitionSource.PRIMARY, loaded.source)
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
