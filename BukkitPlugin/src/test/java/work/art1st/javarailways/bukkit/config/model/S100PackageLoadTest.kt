package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import work.art1st.javarailways.basic.control.TrainDynamics
import java.util.zip.ZipFile
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
    @Test
    fun `S100 source and distribution carry current per-car equipment settings`() {
        val packageDir = File("../models/s100").canonicalFile
        val loaded = checkNotNull(PackageLoader(packageDir).loadTrainPackage())
        assertEquals(6, loaded.cars.size)
        loaded.cars.forEach { (name, car) ->
            assertEquals(TrainDynamics.Equipment(), car.control.equipment, name)
            assertEquals(if (name.startsWith("motor")) 30000.0 else 0.0, car.control.electricBrakeMaxForce, name)
        }
        ZipFile(File(packageDir.parentFile, "s100.zip")).use { zip ->
            loaded.cars.keys.forEach { name ->
                val entry = checkNotNull(zip.getEntry("s100/cars/s100/$name.yml"))
                assertArrayEquals(File(packageDir, "cars/s100/$name.yml").readBytes(), zip.getInputStream(entry).readBytes(), name)
            }
        }
    }

}
