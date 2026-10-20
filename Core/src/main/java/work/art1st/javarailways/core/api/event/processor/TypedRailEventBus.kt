package work.art1st.javarailways.core.api.event.processor

import work.art1st.javarailways.core.api.event.Event
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
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
    private val registrationLock = Any()

    fun register(listener: EventListener): EventRegistration {
        val prepared = ArrayList<Triple<KClass<*>, Priority, Handler>>()
        listener::class.functions.forEach { function ->
            if (!function.hasAnnotation<HandlesEvent>()) return@forEach
            val declared = "${listener::class.qualifiedName ?: listener::class.simpleName}." +
                "${function.name}()"
            require(!function.isSuspend) { "@HandlesEvent function $declared must not be suspend" }
            val parameters = function.parameters
            require(parameters.size == 2) {
                "@HandlesEvent function $declared must declare exactly one event parameter"
            }
            val parameterType = parameters[1].type
            require(parameterType.isSubtypeOf(Event::class.starProjectedType)) {
                "@HandlesEvent function $declared parameter must be a JavaRailways Event"
            }
            // A listener for the other typed bus is legal and belongs to that bus, not this one.
            if (!parameterType.isSubtypeOf(eventRoot.starProjectedType)) return@forEach
            val eventType = parameterType.classifier as? KClass<*>
                ?: throw IllegalArgumentException(
                    "@HandlesEvent function $declared parameter must be a concrete event type"
                )
            val priority = function.findAnnotation<HandlesEvent>()!!.priority
            function.isAccessible = true
            val handler = Handler(listener, function)
            prepared += Triple(eventType, priority, handler)
        }
        synchronized(registrationLock) {
            prepared.forEach { (eventType, priority, handler) ->
                handlers.computeIfAbsent(eventType) { HandlerList() }.get(priority).add(handler)
            }
        }

        return object : EventRegistration {
            private val closed = AtomicBoolean(false)

            override val isClosed: Boolean
                get() = closed.get()

            override fun close() {
                synchronized(registrationLock) {
                    if (!closed.compareAndSet(false, true)) return
                    prepared.forEach { (eventType, priority, handler) ->
                        handlers[eventType]?.get(priority)?.remove(handler)
                    }
                    removeEmptyHandlerLists()
                }
            }
        }
    }

    fun unregister(listener: EventListener) {
        synchronized(registrationLock) {
            handlers.values.forEach { handlerList ->
                handlerList.lists.forEach { list ->
                    list.removeIf { it.listener === listener }
                }
            }
            removeEmptyHandlerLists()
        }
    }

    fun publish(event: E) {
        publishAndReportFailures(event)
    }

    /** Dispatch normally, additionally reporting whether every listener completed successfully. */
    internal fun publishAndReportFailures(event: E): Boolean {
        var successful = true
        val eventTypes = event::class.allSuperTypes()
        Priority.entries.forEach { priority ->
            eventTypes.forEach { eventType ->
                handlers[eventType]?.get(priority)?.forEach { handler ->
                    try {
                        handler.function.call(handler.listener, event)
                    } catch (failure: Throwable) {
                        successful = false
                        LOGGER.log(
                            Level.SEVERE,
                            "$busName event listener ${handler.listener::class.qualifiedName}." +
                                "${handler.function.name} failed for ${event::class.qualifiedName}",
                            failure
                        )
                    }
                }
            }
        }
        return successful
    }

    private fun removeEmptyHandlerLists() {
        // Caller holds registrationLock, so a bucket cannot detach between lookup and add.
        handlers.entries.removeIf { (_, value) -> value.lists.all { it.isEmpty() } }
    }

    companion object {
        private val LOGGER = Logger.getLogger(TypedRailEventBus::class.java.name)
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
