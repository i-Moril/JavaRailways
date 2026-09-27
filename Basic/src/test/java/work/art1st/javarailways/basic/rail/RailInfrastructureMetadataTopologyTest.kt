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
        assertEquals(0, store.getValue(before).createdBySplit?.segmentIndex)
        assertEquals(1, store.getValue(after).createdBySplit?.segmentIndex)
        assertEquals("split-1", store.getValue(before).createdBySplit?.operationId)
    }

    @Test
    fun `split keeps endpoint metadata only on original outer endpoints`() {
        val source = UUID.randomUUID()
        val before = UUID.randomUUID()
        val after = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()
        val turnoutA = TurnoutEndpointMetadata(20.0, 5.0)
        val turnoutB = TurnoutEndpointMetadata(30.0, 5.0)
        store.put(source, RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(gradeLockedAtA = true, gradeLockedAtB = true),
            operating = RailOperatingMetadata(turnoutAtA = turnoutA, turnoutAtB = turnoutB)
        ))

        RailInfrastructureMetadataTopology.split(store, source, before, after, "split-endpoints")

        val beforeMetadata = store.getValue(before)
        val afterMetadata = store.getValue(after)
        assertEquals(turnoutA, beforeMetadata.turnoutAtA)
        assertNull(beforeMetadata.turnoutAtB)
        assertEquals(true, beforeMetadata.gradeLockedAtA)
        assertEquals(false, beforeMetadata.gradeLockedAtB)
        assertNull(afterMetadata.turnoutAtA)
        assertEquals(turnoutB, afterMetadata.turnoutAtB)
        assertEquals(false, afterMetadata.gradeLockedAtA)
        assertEquals(true, afterMetadata.gradeLockedAtB)

        val merged = UUID.randomUUID()
        RailInfrastructureMetadataTopology.merge(store, before, after, merged)
        val mergedMetadata = store.getValue(merged)
        assertEquals(turnoutA, mergedMetadata.turnoutAtA)
        assertEquals(turnoutB, mergedMetadata.turnoutAtB)
        assertEquals(true, mergedMetadata.gradeLockedAtA)
        assertEquals(true, mergedMetadata.gradeLockedAtB)
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

        val leadingAssembly = requireNotNull(store.getValue(leading).assembly)
        val beforeAssembly = requireNotNull(store.getValue(before).assembly)
        val afterAssembly = requireNotNull(store.getValue(after).assembly)
        val trailingAssembly = requireNotNull(store.getValue(trailing).assembly)
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
        ids.filter { it != source }.forEach { assertEquals(null, store.getValue(it).geometry.parallel) }
        assertEquals(null, store.getValue(before).geometry.parallel)
        assertEquals(null, store.getValue(after).geometry.parallel)
        val companionAssemblies = ids.drop(3).map { requireNotNull(store.getValue(it).assembly) }
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

        val rejoined = listOf(leading, merged, trailing).map { requireNotNull(store.getValue(it).assembly) }
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
        assertNull(store.getValue(merged).createdBySplit)
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
