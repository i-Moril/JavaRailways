package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class FullBlockSnapshotTest {
    @Test
    fun `captures a Paper snapshot and force-restores it without physics`() {
        var snapshotRequested = false
        var updateArguments: Pair<Boolean, Boolean>? = null
        val state = blockState(12, 34, 56) { force, applyPhysics ->
            updateArguments = force to applyPhysics
            true
        }
        val block = proxy<Block> { _, method, args ->
            when (method.name) {
                "getState" -> {
                    if (args?.size == 1) {
                        snapshotRequested = args[0] == true
                        state
                    } else {
                        error("FullBlockSnapshot must explicitly request a Paper snapshot")
                    }
                }
                else -> defaultValue(method.returnType)
            }
        }

        val snapshot = FullBlockSnapshot.capture(block)
        assertTrue(snapshotRequested)
        assertEquals(12, snapshot.x)
        assertEquals(34, snapshot.y)
        assertEquals(56, snapshot.z)

        snapshot.restore()
        assertEquals(true to false, updateArguments)
    }

    @Test
    fun `reports a failed state restore`() {
        val state = blockState(1, 2, 3) { _, _ -> false }
        val block = proxy<Block> { _, method, _ ->
            when (method.name) {
                "getState" -> state
                else -> defaultValue(method.returnType)
            }
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            FullBlockSnapshot.capture(block).restore()
        }
        assertTrue(failure.message.orEmpty().contains("1,2,3"))
    }

    private fun blockState(
        x: Int,
        y: Int,
        z: Int,
        update: (Boolean, Boolean) -> Boolean
    ): BlockState = proxy { _, method, args ->
        when (method.name) {
            "getX" -> x
            "getY" -> y
            "getZ" -> z
            "update" -> when (args?.size) {
                2 -> update(args[0] as Boolean, args[1] as Boolean)
                else -> false
            }
            else -> defaultValue(method.returnType)
        }
    }

    private inline fun <reified T> proxy(
        crossinline invocation: (Any, java.lang.reflect.Method, Array<out Any?>?) -> Any?
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { proxy, method, args -> invocation(proxy, method, args) } as T

    private fun defaultValue(type: Class<*>): Any? = when {
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Byte.TYPE -> 0.toByte()
        type == java.lang.Short.TYPE -> 0.toShort()
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Float.TYPE -> 0f
        type == java.lang.Double.TYPE -> 0.0
        type == java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
