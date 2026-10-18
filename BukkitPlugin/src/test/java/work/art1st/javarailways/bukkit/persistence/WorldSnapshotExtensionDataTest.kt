package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotCodec
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState
import work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState
import work.art1st.javarailways.bukkit.extension.train.BukkitExtensionDataSerializers
import work.art1st.javarailways.core.physics.train.CarConfigV1
import work.art1st.javarailways.core.physics.train.SavedCarV1
import work.art1st.javarailways.core.physics.train.SavedBogieV1
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.SavedTrainV1

class WorldSnapshotExtensionDataTest {
    @Test
    fun `fixed current split payloads write directions only in Basic and preserve all paired data`() {
        val railId = "00000000-0000-0000-0000-000000000001"
        val basic = BasicInfrastructureSnapshotCodec.decode("""
            {"formatVersion":2,"infrastructureRevision":7,
             "wayPoints":[{"id":"standalone","displayName":"Standalone","railId":"00000000-0000-0000-0000-000000000001","arcLengthInAbMeters":0.25}],
             "railMetadata":[],
             "operatingDirections":[{"railId":"00000000-0000-0000-0000-000000000001","permittedDirection":"BA"}]}
        """.trimIndent())
        val simpleAto = SimpleATOInfrastructureSnapshotCodec.decode("""
            {"formatVersion":1,"infrastructureRevision":7,
             "destinations":[{"id":"central","displayName":"Central","railId":"00000000-0000-0000-0000-000000000001","arcLengthInAbMeters":0.75}],
             "lines":[{"id":"main","displayName":"Main","upStationIds":[],"downStationIds":[],"color":"#123456"}],
             "lineRevision":4}
        """.trimIndent())
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1.0, segmentCount = 1))
        val core = CoreWorld.SavedInfrastructureV2(2, 7L, listOf(rail.save().copy(id = railId)))
        val coreRuntime = CoreWorld.SavedRuntimeV1(1, 7L, 9L, emptyList())
        val basicRuntime = BasicRuntimeSnapshotV3(3, 7L, 9L)
        val simpleAtoRuntime = SimpleATORuntimeSnapshotV1(1, 7L, 9L,
            dispatcher = SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList()),
            timetables = emptyList(), rollingStock = emptyList())
        val encoded = WorldSnapshotCapture(core, basic, simpleAto, coreRuntime, basicRuntime, simpleAtoRuntime).encode()
        val infrastructure = SplitWorldSnapshotCodec.decodeInfrastructure(SplitWorldSnapshotCodec.encode(encoded.infrastructure))
        val runtime = SplitWorldSnapshotCodec.decodeRuntime(SplitWorldSnapshotCodec.encode(encoded.runtime))

        assertEquals(2, infrastructure.formatVersion)
        assertEquals(2, infrastructure.basic().formatVersion)
        assertEquals(1, infrastructure.simpleAto().formatVersion)
        assertTrue(infrastructure.basicSnapshotJson.contains("operatingDirections"))
        assertFalse(infrastructure.simpleAtoSnapshotJson.contains("operatingDirections"))
        assertEquals(WorldSnapshotCodec.encodeInfrastructure(core), WorldSnapshotCodec.encodeInfrastructure(infrastructure.core()))
        assertEquals(basic, infrastructure.basic())
        assertEquals(simpleAto, infrastructure.simpleAto())
        assertEquals(coreRuntime, runtime.core())
        assertEquals(basicRuntime, runtime.basic())
        assertEquals(simpleAtoRuntime, runtime.simpleAto())
    }

    @Test
    fun `current infrastructure outer document requires SimpleATO payload`() {
        assertThrows(Exception::class.java) {
            SplitWorldSnapshotCodec.decodeInfrastructure(
                """
                {"formatVersion":2,"infrastructureRevision":1,
                 "coreSnapshotJson":"{}","basicSnapshotJson":"{}"}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `split snapshot requires one infrastructure revision and physics epoch`() {
        val infrastructure = CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 7L, emptyList())
        val runtime = CoreWorld.SavedRuntimeV1(1, 7L, 9L, emptyList())
        val basicInfrastructure = BasicInfrastructureSnapshotV2(
            formatVersion = BasicInfrastructureSnapshotV2.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            wayPoints = emptyList()
        )
        val simpleAtoInfrastructure = SimpleATOInfrastructureSnapshotV1(
            formatVersion = SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            destinations = emptyList(),
            lines = emptyList(),
            lineRevision = 0L
        )
        val basicRuntime = BasicRuntimeSnapshotV3(
            formatVersion = BasicRuntimeSnapshotV3.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 9L
        )
        val simpleAtoRuntime = SimpleATORuntimeSnapshotV1(
            formatVersion = SimpleATORuntimeSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 9L,
            dispatcher = SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList()),
            timetables = emptyList(),
            rollingStock = emptyList()
        )

        val capture = WorldSnapshotCapture(
            infrastructure,
            basicInfrastructure,
            simpleAtoInfrastructure,
            runtime,
            basicRuntime,
            simpleAtoRuntime
        ).encode()
        val decodedInfrastructure = SplitWorldSnapshotCodec.decodeInfrastructure(
            SplitWorldSnapshotCodec.encode(capture.infrastructure)
        )
        val decodedRuntime = SplitWorldSnapshotCodec.decodeRuntime(
            SplitWorldSnapshotCodec.encode(capture.runtime)
        )

        assertEquals(7L, decodedInfrastructure.infrastructureRevision)
        assertEquals(7L, decodedInfrastructure.simpleAto().infrastructureRevision)
        assertEquals(9L, decodedRuntime.physicsEpoch)
        assertEquals(7L, decodedRuntime.basic().infrastructureRevision)
        assertEquals(9L, decodedRuntime.simpleAto().physicsEpoch)
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure.copy(infrastructureRevision = 8L),
                simpleAtoInfrastructure,
                runtime,
                basicRuntime,
                simpleAtoRuntime
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure,
                simpleAtoInfrastructure,
                runtime,
                basicRuntime,
                simpleAtoRuntime.copy(physicsEpoch = 10L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure,
                simpleAtoInfrastructure.copy(infrastructureRevision = 8L),
                runtime,
                basicRuntime,
                simpleAtoRuntime
            )
        }
    }

    @Test
    fun `world snapshot codec preserves bukkit render extension data`() {
        BukkitExtensionDataSerializers.register()
        BukkitExtensionDataSerializers.register()
        val location = RailLocationWithFacing.SavedRailLocationWithFacingV1(
            "00000000-0000-0000-0000-00000000000b", 0.0, ViewDirection.AB
        )
        val trainState = SavedTrainRenderState(packageName = "s100", packageSnapshotHash = "package-hash")
        val carState = SavedCarRenderState("s100/passenger", mapOf("door" to "open"), "model-hash", "package-hash", "creation-template")
        val car = SavedCarV1("car-a", SavedBogieV1(location, true, emptyMap()),
            SavedBogieV1(location, true, emptyMap()),
            CarConfigV1(10.0, 0.5, null, 3.0, null, -3.0, 0.3, 0.3, mapOf("BUKKIT" to carState)))
        val snapshot = CoreWorld.SavedRuntimeV1(
            formatVersion = CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 1L,
            physicsEpoch = 1L,
            trains = listOf(
                SavedTrainV1(
                    id = "00000000-0000-0000-0000-00000000000a",
                    name = "train-a",
                    cars = listOf(car),
                    reversed = false,
                    extension = mapOf("BUKKIT" to trainState),
                    headLocation = location,
                    tailLocation = location
                )
            )
        )

        val json = WorldSnapshotCodec.encodeRuntime(snapshot)
        val decoded = WorldSnapshotCodec.decodeRuntime(json)
        assertEquals(snapshot, decoded)
        assertEquals(trainState, decoded.trains.single().extension.getValue("BUKKIT"))
        assertEquals(carState, decoded.trains.single().cars.single().config.extension.getValue("BUKKIT"))
        assertTrue(json.contains("\"typeId\":\"javarailways:car_render_state\""))
        assertTrue(json.contains("\"typeId\":\"javarailways:train_render_state\""))
        assertFalse(json.contains("work.art1st"))
    }
}
