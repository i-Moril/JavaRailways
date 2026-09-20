package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogStatus
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.basic.api.route.ProtectionRegionValidationCode
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ProtectionRegionCatalogTest {
    private val railA = UUID.randomUUID()
    private val railB = UUID.randomUUID()
    private val railC = UUID.randomUUID()
    private var topology = lineTopologyState(7L, railA, railB, railC)
    private val topologyRevision: Long
        get() = topology.revision
    private val catalog = ProtectionRegionCatalog(
        topologyState = { topology }
    )

    @Test
    fun `register publishes resolved interior for lookup`() {
        val result = catalog.register(
            provider(
                "test:provider/main",
                region(
                    "test:region/main",
                    boundary("test:boundary/entry", railA, 5.0, ViewDirection.AB),
                    boundary("test:boundary/exit", railC, 5.0, ViewDirection.BA)
                )
            )
        )

        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, result.status)
        val found = assertInstanceOf(
            ProtectionRegionLookup.Found::class.java,
            catalog.lookup(ProtectionRegionRef("test:region/main", 1L))
        )
        assertEquals(
            setOf(
                Triple(railA, 5.0, 10.0),
                Triple(railB, 0.0, 10.0),
                Triple(railC, 0.0, 5.0)
            ),
            found.region.interior.mapTo(linkedSetOf()) {
                Triple(it.railId, it.startAbMeters, it.endAbMeters)
            }
        )
    }

    @Test
    fun `overlapping provider update is rejected without replacing published catalog`() {
        assertEquals(
            ProtectionRegionCatalogStatus.REGISTERED,
            catalog.register(
                provider(
                    "test:provider/first",
                    region(
                        "test:region/first",
                        boundary("test:boundary/first-entry", railA, 5.0, ViewDirection.AB),
                        boundary("test:boundary/first-exit", railC, 5.0, ViewDirection.BA)
                    )
                )
            ).status
        )

        val rejected = catalog.register(
            provider(
                "test:provider/second",
                region(
                    "test:region/second",
                    boundary("test:boundary/second-entry", railB, 2.0, ViewDirection.AB),
                    boundary("test:boundary/second-exit", railC, 8.0, ViewDirection.BA)
                )
            )
        )

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, rejected.status)
        assertEquals(ProtectionRegionValidationCode.REGION_OVERLAP, rejected.diagnostics.single().code)
        assertEquals(listOf("test:region/first"), catalog.definitions().map { it.region.regionId })
        assertEquals(ProtectionRegionCatalogStatus.NOT_FOUND, catalog.unregister("test:provider/second").status)
    }

    @Test
    fun `stale refresh preserves previous resolved snapshot`() {
        val provider = provider(
            "test:provider/main",
            region(
                "test:region/main",
                boundary("test:boundary/entry", railA, 5.0, ViewDirection.AB),
                boundary("test:boundary/exit", railC, 5.0, ViewDirection.BA)
            )
        )
        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, catalog.register(provider).status)

        topology = lineTopologyState(8L, railA, railB, railC)
        val rejected = catalog.refresh()

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, rejected.status)
        assertEquals(ProtectionRegionValidationCode.STALE_TOPOLOGY, rejected.diagnostics.single().code)
        assertInstanceOf(
            ProtectionRegionLookup.StaleTopology::class.java,
            catalog.lookup(ProtectionRegionRef("test:region/main", 1L))
        )
    }

    @Test
    fun `topology publication during provider callback rejects the whole rebuild`() {
        val provider = object : ProtectionRegionProvider {
            override val providerId: String = "test:provider/racing"

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> {
                val definition = ProtectionRegionDefinition(
                    ProtectionRegionRef("test:region/racing", 1L),
                    topologyRevision,
                    listOf(boundary("test:boundary/racing", railA, 5.0, ViewDirection.AB))
                )
                topology = lineTopologyState(topologyRevision + 1L, railA, railB, railC)
                return listOf(definition)
            }
        }

        val result = catalog.register(provider)

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, result.status)
        assertEquals(ProtectionRegionValidationCode.STALE_TOPOLOGY, result.diagnostics.single().code)
        assertTrue(catalog.definitions().isEmpty())
    }

    @Test
    fun `refresh rebuilds regions entirely on the newly published root`() {
        val provider = object : ProtectionRegionProvider {
            override val providerId: String = "test:provider/dynamic"

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> = listOf(
                ProtectionRegionDefinition(
                    ProtectionRegionRef("test:region/dynamic", topologyRevision),
                    topologyRevision,
                    listOf(boundary("test:boundary/dynamic", railA, 5.0, ViewDirection.AB))
                )
            )
        }
        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, catalog.register(provider).status)

        topology = lineTopologyState(8L, railA, railB, railC)
        val refreshed = catalog.refresh()

        assertEquals(ProtectionRegionCatalogStatus.REFRESHED, refreshed.status)
        assertInstanceOf(
            ProtectionRegionLookup.Found::class.java,
            catalog.lookup(ProtectionRegionRef("test:region/dynamic", 8L))
        )
    }

    @Test
    fun `duplicate provider identity is typed and does not invoke replacement`() {
        val first = provider("test:provider/main", region("test:region/first", boundary(
            "test:boundary/first",
            railA,
            5.0,
            ViewDirection.AB
        )))
        val second = provider("test:provider/main", region("test:region/second", boundary(
            "test:boundary/second",
            railC,
            5.0,
            ViewDirection.BA
        )))
        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, catalog.register(first).status)

        val result = catalog.register(second)

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, result.status)
        assertEquals(ProtectionRegionValidationCode.DUPLICATE_PROVIDER, result.diagnostics.single().code)
        assertEquals(listOf("test:region/first"), catalog.definitions().map { it.region.regionId })
    }

    @Test
    fun `provider failure rejects registration and preserves previous catalog`() {
        assertEquals(
            ProtectionRegionCatalogStatus.REGISTERED,
            catalog.register(
                provider(
                    "test:provider/first",
                    region(
                        "test:region/first",
                        boundary("test:boundary/first", railA, 5.0, ViewDirection.AB)
                    )
                )
            ).status
        )
        val failing = object : ProtectionRegionProvider {
            override val providerId: String = "test:provider/failing"

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> =
                error("fixture provider failure")
        }

        val result = catalog.register(failing)

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, result.status)
        assertEquals(ProtectionRegionValidationCode.PROVIDER_FAILED, result.diagnostics.single().code)
        assertEquals(listOf("test:region/first"), catalog.definitions().map { it.region.regionId })
    }

    @Test
    fun `unregister removes only regions owned by that provider`() {
        assertEquals(
            ProtectionRegionCatalogStatus.REGISTERED,
            catalog.register(
                provider(
                    "test:provider/main",
                    region(
                        "test:region/main",
                        boundary("test:boundary/main", railA, 5.0, ViewDirection.AB)
                    )
                )
            ).status
        )

        assertEquals(
            ProtectionRegionCatalogStatus.UNREGISTERED,
            catalog.unregister("test:provider/main").status
        )
        assertTrue(catalog.definitions().isEmpty())
        assertInstanceOf(
            ProtectionRegionLookup.Missing::class.java,
            catalog.lookup(ProtectionRegionRef("test:region/main", 1L))
        )
    }

    private fun region(id: String, vararg boundaries: ProtectionBoundaryDefinition) =
        ProtectionRegionDefinition(ProtectionRegionRef(id, 1L), topologyRevision, boundaries.toList())

    private fun boundary(id: String, railId: UUID, arc: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(railId, facing, arc))

    private fun provider(id: String, vararg definitions: ProtectionRegionDefinition) =
        object : ProtectionRegionProvider {
            override val providerId: String = id

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> =
                definitions.toList()
        }

}
