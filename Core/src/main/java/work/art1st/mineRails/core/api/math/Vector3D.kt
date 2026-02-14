package work.art1st.mineRails.core.api.math

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.Any
import kotlin.ArithmeticException
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.math.sqrt
import kotlin.text.format

/**
 * 三维向量类，用于替代Bukkit的Vector
 * 提供基本的向量运算功能
 */
@Serializable(Vector3D.Serializer::class)
data class Vector3D (var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
    constructor(other: Vector3D) : this(other.x, other.y, other.z)

    /**
     * 创建向量的副本
     */
    fun copy(): Vector3D {
        return Vector3D(this)
    }

    /**
     * 向量长度
     */
    fun length(): Double {
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * 向量长度的平方
     */
    fun lengthSquared(): Double {
        return x * x + y * y + z * z
    }

    /**
     * 归一化向量（单位向量）
     */
    fun normalize(): Vector3D {
        val lengthSquared = lengthSquared()
        if (lengthSquared < 1e-10) {
            throw ArithmeticException("Cannot normalize zero vector")
        }
        val length = sqrt(lengthSquared)
        this.x /= length
        this.y /= length
        this.z /= length
        return this
    }

    fun distanceSquared(other: Vector3D): Double {
        val dx = this.x - other.x
        val dy = this.y - other.y
        val dz = this.z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * 计算到另一个向量的距离
     */
    fun distance(other: Vector3D): Double {
        return sqrt(distanceSquared(other))
    }

    /**
     * 点积
     */
    infix fun dot(other: Vector3D): Double {
        return this.x * other.x + this.y * other.y + this.z * other.z
    }

    /**
     * 叉积
     */
    infix fun cross(other: Vector3D): Vector3D {
        val newX = this.y * other.z - this.z * other.y
        val newY = this.z * other.x - this.x * other.z
        val newZ = this.x * other.y - this.y * other.x
        this.x = newX
        this.y = newY
        this.z = newZ
        return this
    }

    operator fun plus(other: Vector3D): Vector3D {
        return Vector3D(this.x + other.x, this.y + other.y, this.z + other.z)
    }

    operator fun minus(other: Vector3D): Vector3D {
        return Vector3D(this.x - other.x, this.y - other.y, this.z - other.z)
    }

    operator fun times(scalar: Double): Vector3D {
        return Vector3D(this.x * scalar, this.y * scalar, this.z * scalar)
    }

    operator fun div(scalar: Double): Vector3D {
        return Vector3D(this.x / scalar, this.y / scalar, this.z / scalar)
    }

    operator fun unaryMinus(): Vector3D {
        return Vector3D(-x, -y, -z)
    }

    val isZeroVector: Boolean
        get() = lengthSquared() < 1e-10

    override fun toString(): String {
        return String.format("Vector3D(%.2f, %.2f, %.2f)", x, y, z)
    }

    fun flatten(): String {
        return "$x,$y,$z"
    }

    object Serializer : KSerializer<Vector3D> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Vector3D", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Vector3D
        ) {
            encoder.encodeString(value.flatten())
        }

        override fun deserialize(decoder: Decoder): Vector3D {
            return Vector3D.fromFlattenString(decoder.decodeString())
        }

    }

    companion object {
        @JvmStatic
        val ZERO = Vector3D(0.0, 0.0, 0.0)

        @JvmStatic
        fun fromFlattenString(string: String): Vector3D {
            val xyz = string.split(',')
            return Vector3D(xyz[0].toDouble(), xyz[1].toDouble(), xyz[2].toDouble())
        }

    }
}

