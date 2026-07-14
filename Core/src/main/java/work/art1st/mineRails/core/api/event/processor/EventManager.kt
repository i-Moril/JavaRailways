package work.art1st.mineRails.core.api.event.processor

import work.art1st.mineRails.core.api.event.Event
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType

/**
 * Process-wide synchronous event bus for Core events.
 *
 * Listener functions are discovered with Kotlin reflection at registration time. The bus is not
 * scoped per world and has no cancellation model. [fire] and [fireAsync] both execute handlers on
 * the caller's thread; the latter adds asynchronous opt-in semantics and listener failure isolation.
 */
object EventManager {
    private class HandlerList {
        val lists: List<CopyOnWriteArrayList<Pair<EventListener, KFunction<*>>>> = listOf(CopyOnWriteArrayList(), CopyOnWriteArrayList(), CopyOnWriteArrayList(), CopyOnWriteArrayList(), CopyOnWriteArrayList())

        fun getList(priority: Priority): CopyOnWriteArrayList<Pair<EventListener, KFunction<*>>> = lists[priority.value]
    }

    private val handlers = ConcurrentHashMap<KClass<*>, HandlerList>()

    /** Register all valid [HandlesEvent] functions declared by [eventListener]. */
    fun register(eventListener: EventListener) {
        eventListener::class.functions.forEach { function ->
            if (function.hasAnnotation<HandlesEvent>()) {
                val annotation = function.findAnnotation<HandlesEvent>()!!
                val parameters = function.parameters
                if (parameters.size == 2 && parameters[1].type.isSubtypeOf(Event::class.starProjectedType)) {
                    handlers.getOrPut(parameters[1].type.classifier as KClass<*>) { HandlerList() }.getList(annotation.priority).add(Pair(eventListener, function))
                }
            }
        }
    }

    /** Remove all handlers previously registered for the same listener instance. */
    fun unregister(eventListener: EventListener) {
        eventListener::class.functions.forEach { function ->
            if (function.hasAnnotation<HandlesEvent>()) {
                val annotation = function.findAnnotation<HandlesEvent>()!!
                val parameters = function.parameters
                if (parameters.size == 2 && parameters[1].type.isSubtypeOf(Event::class.starProjectedType)) {
                    handlers[parameters[1].type.classifier as KClass<*>]!!.getList(annotation.priority).remove(Pair(eventListener, function))
                }
            }
        }
    }

    /**
     * Dispatch [event] synchronously on the caller's thread.
     *
     * Handler exceptions propagate and stop dispatch; use only where that failure policy is intended.
     */
    fun fire(event: Event) {
        event::class.getAllSuperClasses().forEach { type ->
            handlers[type]?.lists?.forEach { it.forEach { p -> p.second.call(p.first, event) } }
        }
    }

    /** Async dispatch stays on the caller's physics worker and isolates each listener failure. */
    @AsyncEventApi
    fun fireAsync(event: Event) {
        event::class.getAllSuperClasses().forEach { type ->
            handlers[type]?.lists?.forEach { list ->
                list.forEach { handler ->
                    try {
                        handler.second.call(handler.first, event)
                    } catch (t: Throwable) {
                        System.err.println(
                            "[MineRails] Async event listener ${handler.first::class.qualifiedName}." +
                                "${handler.second.name} failed for ${event::class.qualifiedName}: ${t.message}"
                        )
                        t.printStackTrace()
                    }
                }
            }
        }
    }

    fun KClass<*>.getAllSuperClasses(): Set<KClass<*>> {
        val result = mutableSetOf<KClass<*>>()
        val queue = ArrayDeque<KClass<*>>()
        queue.add(this)
        result.add(this)

         while (queue.isNotEmpty()) {
            val currentClass = queue.removeFirst()
            currentClass.supertypes.forEach {
                val superClass = it.classifier as KClass<*>
                if (result.add(superClass)) {
                    queue.add(superClass)
                }
            }
        }
        return result
    }
}