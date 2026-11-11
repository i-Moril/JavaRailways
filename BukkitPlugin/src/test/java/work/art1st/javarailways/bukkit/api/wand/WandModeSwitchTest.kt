package work.art1st.javarailways.bukkit.api.wand

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import java.lang.reflect.Proxy

class WandModeSwitchTest {
    @Test
    fun `main hand F consumes once before callback without requiring sneak`() {
        val wand = ItemStack(Material.STICK)
        val other = ItemStack(Material.DIRT)
        val player = player(wand, other)
        val event = PlayerSwapHandItemsEvent(player, other, wand)
        var calls = 0
        assertTrue(WandModeSwitch.handle(event, { it === wand }) {
            assertSame(player, it)
            assertTrue(event.isCancelled)
            calls++
        })
        assertFalse(WandModeSwitch.handle(event, { true }) { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun `offhand wand and previously cancelled events do not take over main hand`() {
        val wand = ItemStack(Material.STICK)
        val other = ItemStack(Material.DIRT)
        val event = PlayerSwapHandItemsEvent(player(other, wand), wand, other)
        assertFalse(WandModeSwitch.handle(event, { it === wand }) { fail("offhand callback") })
        assertFalse(event.isCancelled)
        event.isCancelled = true
        assertFalse(WandModeSwitch.handle(event, { fail("cancelled event predicate") }) {
            fail("cancelled event callback")
        })
    }

    private fun player(mainHand: ItemStack, offHand: ItemStack): Player {
        val inventory = Proxy.newProxyInstance(PlayerInventory::class.java.classLoader,
            arrayOf(PlayerInventory::class.java)) { _, method, _ ->
            when (method.name) {
                "getItemInMainHand" -> mainHand
                "getItemInOffHand" -> offHand
                else -> error("Unexpected inventory access: ${method.name}")
            }
        } as PlayerInventory
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
            _, method, _ ->
            when (method.name) {
                "getInventory" -> inventory
                else -> error("Unexpected player access: ${method.name}")
            }
        } as Player
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
    }
}
