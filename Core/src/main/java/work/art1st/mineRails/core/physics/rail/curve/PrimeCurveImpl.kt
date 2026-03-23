package work.art1st.mineRails.core.physics.rail.curve

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.joml.Vector3d
import org.joml.Vector3f
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.util.BinarySearch
import work.art1st.mineRails.core.util.Heavy
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import java.util.stream.Stream

/**
 * PrimeCurve should have curve length starting from 0.0
 */
open class PrimeCurveImpl : PrimeCurve {
    @Serializable
    private class InternalCurvePoint(
        @Serializable(with = Vector3fSerializer::class)
        val position: Vector3f,
        @Serializable(with = Vector3fSerializer::class)
        val tangent: Vector3f,
        @Serializable(with = Vector3fSerializer::class)
        val normal: Vector3f,
        val arcLength: Float,
        val idx: Int
    ) {
        
    }

    private lateinit var pointList: List<InternalCurvePoint>
    override val totalArcLength: Double
        get() = pointList.last().arcLength.toDouble()
    override val totalPoints: Int
        get() = pointList.size
    @Transient
    override val reversed: PrimeCurve = ReversedCurve(this)

    override fun getIdxAt(arcLength: Double) : Int {
        if (arcLength == 0.0) { return 0 }
        if (arcLength == totalArcLength) { return totalPoints - 1 }
        require(arcLength in 0.0..totalArcLength) {
            "Invalid point arcLength $arcLength. Range: [0, ${totalArcLength}]"
        }
        return BinarySearch.searchList(
            pointList
        ) { p -> p.arcLength - arcLength }.first
    }

    override fun getPointAt(idx: Int): Curve.Point {
        require(idx in 0 until pointList.size) {
            "Invalid point idx $idx. Range: [0, ${pointList.size - 1}]"
        }
        val p = pointList[idx]
        return Curve.Point(
            position = Vector3d(p.position),
            tangent = Vector3d(p.tangent),
            normal = Vector3d(p.normal),
            curve = this,
            idx = idx,
            arcLength = p.arcLength.toDouble()
        )
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return pointList.stream().map { Curve.Point(
            position = Vector3d(it.position),
            tangent = Vector3d(it.tangent),
            normal = Vector3d(it.normal),
            curve = this,
            idx = it.idx,
            arcLength = it.arcLength.toDouble()
        ) }
    }

    override fun save(): PrimeCurve.SavedPrimeCurve {
        return SavedPrimeCurveV1.build(this)
    }

    @ConsistentCopyVisibility
    @Serializable
    data class SavedPrimeCurveV1 private constructor(
        private val pointList: List<InternalCurvePoint>
    ) : PrimeCurve.SavedPrimeCurve {
        override fun load(dependency: Unit): PrimeCurve {
            return PrimeCurveImpl().apply {
                this@apply.pointList = this@SavedPrimeCurveV1.pointList
            }
        }

        companion object {
            fun build(curve: PrimeCurveImpl): SavedPrimeCurveV1 {
                return SavedPrimeCurveV1(curve.pointList)
            }
        }

    }

//    object PrimeCurveSerializer : KSerializer<PrimeCurveImpl> {
//        private val delegateSerializer = ListSerializer(String.serializer())
//        override val descriptor: SerialDescriptor = SerialDescriptor("PrimeCurve", delegateSerializer.descriptor)
//
//        private fun pointToString(p: Curve.Point): String {
//            return p.position.flatten() + ';' + p.tangent.flatten() + ';' + p.normal.flatten() + ';' + p.arcLength
//        }
//
//        private fun stringToPoint(s: String, curve: PrimeCurveImpl, idx: Int): Curve.Point {
//            val a = s.split(';')
//            return Curve.Point(
//                position = Vector3d.fromFlattenString(a[0]),  // position
//                tangent = Vector3d.fromFlattenString(a[1]),  // tangent
//                normal = Vector3d.fromFlattenString(a[2]),  // normal
//                curve = curve,
//                idx = idx,
//                arcLength = a[3].toDouble()  // arcLength
//            )
//        }
//
//        override fun serialize(encoder: Encoder, value: PrimeCurveImpl) {
//            val stringList = value.pointList.map { pointToString(it) }
//            delegateSerializer.serialize(encoder, stringList)
//        }
//
//        override fun deserialize(decoder: Decoder): PrimeCurveImpl {
//            val stringList = delegateSerializer.deserialize(decoder)
//            val curve = PrimeCurveImpl()
//            val pointList = stringList.mapIndexed { idx, s -> stringToPoint(s, curve, idx) }
//            curve.pointList = pointList
//            return curve
//        }
//    }
//
//    class PrimeCurveMapper : IndependentPersistentMapper<PrimeCurveImpl, String> {
//        // Point: {... ; ... ; ... ; ...}
//        internal fun pointToString(p: Curve.Point): String {
//            return p.position.flatten() + ';' + p.tangent.flatten() + ';' + p.normal.flatten() + ';' + p.arcLength
//        }
//
//        internal fun stringToPoint(s: String, curve: PrimeCurveImpl, idx: Int): Curve.Point {
//            val a = s.split(';')
//            return Curve.Point(
//                position = Vector3d.fromFlattenString(a[0]),  // position
//                tangent = Vector3d.fromFlattenString(a[1]),  // tangent
//                normal = Vector3d.fromFlattenString(a[2]),  // normal
//                curve = curve,
//                idx = idx,
//                arcLength = a[3].toDouble()  // arcLength
//            )
//        }
//
//        // Curve: {p:<point>|<point>|...}
//        fun saveAsPointList(obj: PrimeCurveImpl): String {
//            return "p:" + obj.pointList.joinToString("|") { pointToString(it) }
//        }
//
//        fun loadAsPointList(pList: String): PrimeCurveImpl {
//            val curve = PrimeCurveImpl()
//            val pList = pList.split("|")
//            val pointList = pList.mapIndexed { idx, s -> stringToPoint(s, curve, idx) }
//            curve.pointList = pointList
//            return curve
//        }
//
//        override fun save(obj: PrimeCurveImpl): String {
//            return saveAsPointList(obj)
//        }
//
//        override fun load(persistent: String): PrimeCurveImpl {
//            val split = persistent.split(':', limit = 2)
//            val type = split[0]
//            if (type == "p") {
//                return loadAsPointList(split[1])
//            } else {
//                throw LoadPersistentDataException(PrimeCurveImpl::class, persistent)
//            }
//        }
//    }
}

object Vector3fSerializer : KSerializer<Vector3f> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3f") {
        element<Float>("x")
        element<Float>("y")
        element<Float>("z")
    }

    override fun serialize(encoder: Encoder, value: Vector3f) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.x)
            encodeFloatElement(descriptor, 1, value.y)
            encodeFloatElement(descriptor, 2, value.z)
        }
    }

    override fun deserialize(decoder: Decoder): Vector3f {
        var x = 0.0f
        var y = 0.0f
        var z = 0.0f
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> x = decodeFloatElement(descriptor, 0)
                    1 -> y = decodeFloatElement(descriptor, 1)
                    2 -> z = decodeFloatElement(descriptor, 2)
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return Vector3f(x, y, z)
    }
}