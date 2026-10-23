package work.art1st.javarailways.bukkit.render.model

import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import java.lang.reflect.Proxy
import java.util.UUID

class SeatDismountTest {
    @Test
    fun `cancelled dismount is ignored and accepted dismount aligns and schedules cleanup at monitor`() {
        val listener = SeatModel.SeatInteractionListener
        val annotation = listener.javaClass.getMethod("onPlayerDismountSeat", EntityDismountEvent::class.java).getAnnotation(EventHandler::class.java)
        assertEquals(EventPriority.MONITOR, annotation.priority)
        assertTrue(annotation.ignoreCancelled)
        var alignments = 0
        var scheduled = 0
        var vacated = 0
        lateinit var ownerCar: Car
        val train = proxy<Train> { if (it == "getCars") listOf(ownerCar) else null }
        val world = proxy<World> { if (it == "getAllTrains") listOf(train) else null }
        ownerCar = proxy<Car> { if (it == "getWorld") world else null }
        val vacatedHandler: (Player, Train) -> Unit = { _, actual -> assertSame(train, actual); vacated++ }
        listener.addDriverSeatVacatedHandler(vacatedHandler)
        val carrierId = UUID.randomUUID()
        val carrier = proxy<Entity> { if (it == "getUniqueId") carrierId else null }
        val player = proxy<Player> { null }
        val binding = SeatModel.SeatBinding(proxy<Interaction> { null }, carrier, ownerCar, true, null,
            { carrier }, {}, { alignments++ })
        val dispatcher = proxy<PlatformDispatcher> {
            if (it == "runForEntityLater") scheduled++
            null
        }
        listener.bindCarrier(binding, carrier)
        try {
            val registered = RegisteredListener(listener, EventExecutor { _, event ->
                listener.handleDismount(event as EntityDismountEvent, dispatcher)
            }, annotation.priority, proxy<Plugin> { null }, annotation.ignoreCancelled)
            val event = EntityDismountEvent(player, carrier)
            event.isCancelled = true
            registered.callEvent(event)
            assertEquals(0, alignments)
            assertEquals(0, scheduled)
            assertEquals(0, vacated)
            event.isCancelled = false
            registered.callEvent(event)
            assertEquals(1, alignments)
            assertEquals(1, scheduled)
            assertEquals(1, vacated)
        } finally {
            listener.unbindCarrier(carrier)
            val handlers = listener.javaClass.getDeclaredField("driverSeatVacatedHandlers").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (handlers.get(listener) as MutableCollection<(Player, Train) -> Unit>).remove(vacatedHandler)
        }
    }

    private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T
}
