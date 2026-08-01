package work.art1st.javarailways.core.api.event.processor

import work.art1st.javarailways.core.api.event.Event
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.isAccessible

/** Internal synchronous dispatcher shared by the main-thread and physics event buses. */
internal class TypedRailEventBus<E : Event>(
    private val eventRoot: KClass<E>,
    private val busName: String
) {
    private class Handler(
        val listener: EventListener,
        val function: KFunction<*>
    )

    private class HandlerList {
        val lists: List<CopyOnWriteArrayList<Handler>> = Priority.entries.map {
            CopyOnWriteArrayList()
        }

        fun get(priority: Priority): CopyOnWriteArrayList<Handler> = lists[priority.value]
    }

    private val handlers = ConcurrentHashMap<KClass<*>, HandlerList>()

    fun register(listener: EventListener): EventRegistration {
        val added = ArrayList<Pair<KClass<*>, Handler>>()
        listener::class.functions.forEach { function ->
            if (!function.hasAnnotation<HandlesEvent>()) return@forEach
            val parameters = function.parameters
            if (parameters.size != 2 || !parameters[1].type.isSubtypeOf(eventRoot.starProjectedType)) {
                return@forEach
            }
            val eventType = parameters[1].type.classifier as? KClass<*> ?: return@forEach
            val priority = function.findAnnotation<HandlesEvent>()!!.priority
            function.isAccessible = true
            val handler = Handler(listener, function)
            handlers.computeIfAbsent(eventType) { HandlerList() }.get(priority).add(handler)
            added += eventType to handler
        }

        return object : EventRegistration {
            private val closed = AtomicBoolean(false)

            override val isClosed: Boolean
                get() = closed.get()

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                added.forEach { (eventType, handler) ->
                    handlers[eventType]?.lists?.forEach { it.remove(handler) }
                }
                removeEmptyHandlerLists()
            }
        }
    }

    fun unregister(listener: EventListener) {
        handlers.values.forEach { handlerList ->
            handlerList.lists.forEach { list ->
                list.removeIf { it.listener === listener }
            }
        }
        removeEmptyHandlerLists()
    }

    fun publish(event: E) {
        event::class.allSuperTypes().forEach { eventType ->
            handlers[eventType]?.lists?.forEach { priorityHandlers ->
                priorityHandlers.forEach { handler ->
                    try {
                        handler.function.call(handler.listener, event)
                    } catch (failure: Throwable) {
                        System.err.println(
                            "[JavaRailways] $busName event listener ${handler.listener::class.qualifiedName}." +
                                "${handler.function.name} failed for ${event::class.qualifiedName}: ${failure.message}"
                        )
                        failure.printStackTrace()
                    }
                }
            }
        }
    }

    private fun removeEmptyHandlerLists() {
        handlers.entries.removeIf { (_, value) -> value.lists.all { it.isEmpty() } }
    }

    private fun KClass<*>.allSuperTypes(): Set<KClass<*>> {
        val result = LinkedHashSet<KClass<*>>()
        val pending = ArrayDeque<KClass<*>>()
        result += this
        pending += this
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            current.supertypes.forEach { superType ->
                val superClass = superType.classifier as? KClass<*> ?: return@forEach
                if (result.add(superClass)) pending += superClass
            }
        }
        return result
    }
}
