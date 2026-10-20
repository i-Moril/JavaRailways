package work.art1st.javarailways.basic.rail

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class RailInfrastructureMetadataTopologyTest {
    @Test
    fun `split lineage without source role decodes with the compatibility default`() {
        val decoded = Json.decodeFromString<RailSplitMetadata>(
            """{"operationId":"old-split","segmentIndex":0,"parent":null}"""
        )

        assertNull(decoded.sourceAssemblyRole)
    }

    @Test
    fun `split moves metadata and records lineage outside Core`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val original = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0)
        )
        store.put(source, original)

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-1")

        assertNull(store[source])
        assertEquals(0, store.getValue(before).authoring?.createdBySplit?.segmentIndex)
        assertEquals(1, store.getValue(after).authoring?.createdBySplit?.segmentIndex)
        assertEquals("split-1", store.getValue(before).authoring?.createdBySplit?.operationId)
    }

    @Test
    fun `split keeps endpoint metadata only on original outer endpoints`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(25.0, SpeedLimitType.CURVE),
            SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE)
        ))
        val curveSafety = CurveSafetyMetadata(28.0)
        val original = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(gradeLockedAtA = true, gradeLockedAtB = true),
            speedLimits = speedLimits,
            curveSafety = curveSafety
        )
        store.put(source, original)

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-endpoints")

        val beforeMetadata = store.getValue(before)
        val afterMetadata = store.getValue(after)
        assertEquals(true, beforeMetadata.authoring?.gradeLockedAtA)
        assertEquals(false, beforeMetadata.authoring?.gradeLockedAtB)
        assertEquals(false, afterMetadata.authoring?.gradeLockedAtA)
        assertEquals(true, afterMetadata.authoring?.gradeLockedAtB)
        listOf(beforeMetadata, afterMetadata).forEach {
            assertEquals(speedLimits, it.speedLimits)
            assertEquals(curveSafety, it.curveSafety)
        }

        val merged = UUID.randomUUID()
        RailInfrastructureMetadataTopology.merge(store, before, after, merged)
        val mergedMetadata = store.getValue(merged)
        assertEquals(true, mergedMetadata.authoring?.gradeLockedAtA)
        assertEquals(true, mergedMetadata.authoring?.gradeLockedAtB)
        assertEquals(original, mergedMetadata)
    }

    @Test
    fun `splitting metadata without authoring adds only required lineage and keeps other groups`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val original = RailInfrastructureMetadata(
            speedLimits = SpeedLimitMetadata(listOf(SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE))),
            curveSafety = CurveSafetyMetadata(30.0)
        )
        assertNull(original.authoring)
        store.put(source, original)

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-without-authoring")

        listOf(before, after).forEachIndexed { index, railId ->
            assertEquals(
                original.copy(authoring = RailAuthoringMetadata(
                    createdBySplit = RailSplitMetadata("split-without-authoring", index)
                )),
                store.getValue(railId)
            )
        }
    }

    @Test
    fun `split turns one assembly lane into two independently editable assemblies`() {
        val leading = UUID.randomUUID()
        val source = UUID.randomUUID()
        val trailing = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val groupId = "assembly"
        listOf(
            Triple(leading, 0, RailAssemblyRole.LEADING_TANGENT),
            Triple(source, 1, RailAssemblyRole.CURVE),
            Triple(trailing, 2, RailAssemblyRole.TRAILING_TANGENT)
        ).forEach { (id, index, role) ->
            store.put(id, assemblyMetadata(groupId, 0, 1, index, 3, role))
        }

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-assembly")

        val leadingAssembly = requireNotNull(store.getValue(leading).authoring?.assembly)
        val beforeAssembly = requireNotNull(store.getValue(before).authoring?.assembly)
        val afterAssembly = requireNotNull(store.getValue(after).authoring?.assembly)
        val trailingAssembly = requireNotNull(store.getValue(trailing).authoring?.assembly)
        assertEquals(leadingAssembly.groupId, beforeAssembly.groupId)
        assertEquals(2, leadingAssembly.segmentCount)
        assertEquals(RailAssemblyRole.LEADING_TANGENT, leadingAssembly.role)
        assertEquals(RailAssemblyRole.CURVE, beforeAssembly.role)
        assertEquals(afterAssembly.groupId, trailingAssembly.groupId)
        assertEquals(2, afterAssembly.segmentCount)
        assertEquals(RailAssemblyRole.CURVE, afterAssembly.role)
        assertEquals(RailAssemblyRole.TRAILING_TANGENT, trailingAssembly.role)
        assertEquals(false, leadingAssembly.groupId == afterAssembly.groupId)
    }

    @Test
    fun `splitting one parallel assembly lane does not split companion lanes`() {
        val ids = List(6) { UUID.randomUUID() }
        val source = ids[1]
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val roles = listOf(
            RailAssemblyRole.LEADING_TANGENT,
            RailAssemblyRole.CURVE,
            RailAssemblyRole.TRAILING_TANGENT
        )
        repeat(2) { lane ->
            repeat(3) { segment ->
                val id = ids[lane * 3 + segment]
                val parallel = ParallelRailMetadata("parallel-$segment", lane, 2, 4.5)
                store.put(
                    id,
                    assemblyMetadata("parallel-assembly", lane, 2, segment, 3, roles[segment], parallel)
                )
            }
        }

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-one-lane")

        assertNull(store[source])
        ids.filter { it != source }.forEach { assertEquals(null, store.getValue(it).authoring?.geometry?.parallel) }
        assertEquals(null, store.getValue(before).authoring?.geometry?.parallel)
        assertEquals(null, store.getValue(after).authoring?.geometry?.parallel)
        val companionAssemblies = ids.drop(3).map { requireNotNull(store.getValue(it).authoring?.assembly) }
        assertEquals(1, companionAssemblies.map { it.groupId }.distinct().size)
        assertEquals(listOf(0, 1, 2), companionAssemblies.map { it.segmentIndex })
        assertEquals(listOf(1, 1, 1), companionAssemblies.map { it.laneCount })
    }

    @Test
    fun `merge rejoins assemblies created by a split`() {
        val leading = UUID.randomUUID()
        val source = UUID.randomUUID()
        val trailing = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val merged = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        listOf(
            Triple(leading, 0, RailAssemblyRole.LEADING_TANGENT),
            Triple(source, 1, RailAssemblyRole.CURVE),
            Triple(trailing, 2, RailAssemblyRole.TRAILING_TANGENT)
        ).forEach { (id, index, role) ->
            store.put(id, assemblyMetadata("assembly", 0, 1, index, 3, role))
        }
        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-assembly")

        RailInfrastructureMetadataTopology.merge(store, before, after, merged)

        val rejoined = listOf(leading, merged, trailing).map { requireNotNull(store.getValue(it).authoring?.assembly) }
        assertEquals(1, rejoined.map { it.groupId }.distinct().size)
        assertEquals(listOf(0, 1, 2), rejoined.map { it.segmentIndex })
        assertEquals(3, rejoined.first().segmentCount)
        assertEquals(RailAssemblyRole.CURVE, rejoined[1].role)
    }

    @Test
    fun `merge restores parent lineage`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val merged = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        store.put(source, RailInfrastructureMetadata(authoring = RailAuthoringMetadata()))
        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-1")
        RailInfrastructureMetadataTopology.merge(store, before, after, merged)
        assertNull(store[before])
        assertNull(store[after])
        assertNull(store.getValue(merged).authoring?.createdBySplit)
    }

    private fun assemblyMetadata(
        groupId: String,
        laneIndex: Int,
        laneCount: Int,
        segmentIndex: Int,
        segmentCount: Int,
        role: RailAssemblyRole,
        parallel: ParallelRailMetadata? = null
    ): RailInfrastructureMetadata = RailInfrastructureMetadata(
        authoring = RailAuthoringMetadata(
            geometry = RailGeometryMetadata(parallel = parallel),
            assembly = RailAssemblyMetadata(
                groupId,
                RailAssemblyKind.FIVE_SEGMENT_CURVE,
                laneIndex,
                laneCount,
                segmentIndex,
                segmentCount,
                role
            )
        )
    )
}
