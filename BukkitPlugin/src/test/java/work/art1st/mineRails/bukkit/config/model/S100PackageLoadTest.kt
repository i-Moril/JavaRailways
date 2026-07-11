package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class S100PackageLoadTest {
    @Test
    fun `loads S100 package with installed lights`() {
        val packageDir = File("../models/s100").canonicalFile
        assertTrue(packageDir.isDirectory, "S100 model package should exist at ${packageDir.path}")

        val loaded = PackageLoader(packageDir).loadTrainPackage()
        assertNotNull(loaded, "S100 model package should load")
        loaded!!

        assertTrue(loaded.partRegistry.getAllNames().containsAll(listOf(
            "s100/head_light",
            "s100/tail_light",
            "s100/cabin_light",
            "s100/cabin_light_head",
            "s100/cabin_light_connector",
            "s100/cabin_light_connector_toilet"
        )))
        assertTrue(loaded.cars["head"]!!.bodyParts.any { it.name == "head_light" })
        assertTrue(loaded.cars["head_tail"]!!.bodyParts.any { it.name == "tail_light" })
        loaded.cars.values.forEach { car ->
            assertTrue(car.bodyParts.any { it.name.startsWith("cabin_light") }, "${car.name} should have cabin lights")
            car.bodyParts.forEach { partRef ->
                loaded.partRegistry.resolve(partRef.name, car.namespace)
            }
        }
    }
}
