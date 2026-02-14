package work.art1st.mineRails.core.physics.rail.curve

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.util.BinarySearch
import work.art1st.mineRails.core.util.Heavy
import work.art1st.mineRails.core.api.math.Vector3D
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.persistence.IndependentPersistentMapper
import work.art1st.mineRails.core.api.util.persistence.LoadPersistentDataException
import work.art1st.mineRails.core.api.util.persistence.PersistentBy
import java.util.stream.Stream

/**
 * PrimeCurve should have curve length starting from 0.0
 */
@PersistentBy(PrimeCurveImpl.PrimeCurveMapper::class)
open class PrimeCurveImpl : PrimeCurve {
    private lateinit var pointList: List<Curve.Point>
    override val totalArcLength: Double
        get() = pointList.last().arcLength
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
        return pointList[idx].copy()
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return pointList.stream()
    }

    object PrimeCurveSerializer : KSerializer<PrimeCurveImpl> {
        private val delegateSerializer = ListSerializer(String.serializer())
        override val descriptor: SerialDescriptor = SerialDescriptor("PrimeCurve", delegateSerializer.descriptor)

        private fun pointToString(p: Curve.Point): String {
            return p.position.flatten() + ';' + p.tangent.flatten() + ';' + p.normal.flatten() + ';' + p.arcLength
        }

        private fun stringToPoint(s: String, curve: PrimeCurveImpl, idx: Int): Curve.Point {
            val a = s.split(';')
            return Curve.Point(
                position = Vector3D.fromFlattenString(a[0]),  // position
                tangent = Vector3D.fromFlattenString(a[1]),  // tangent
                normal = Vector3D.fromFlattenString(a[2]),  // normal
                curve = curve,
                idx = idx,
                arcLength = a[3].toDouble()  // arcLength
            )
        }

        override fun serialize(encoder: Encoder, value: PrimeCurveImpl) {
            val stringList = value.pointList.map { pointToString(it) }
            delegateSerializer.serialize(encoder, stringList)
        }

        override fun deserialize(decoder: Decoder): PrimeCurveImpl {
            val stringList = delegateSerializer.deserialize(decoder)
            val curve = PrimeCurveImpl()
            val pointList = stringList.mapIndexed { idx, s -> stringToPoint(s, curve, idx) }
            curve.pointList = pointList
            return curve
        }
    }

    class PrimeCurveMapper : IndependentPersistentMapper<PrimeCurveImpl, String> {
        // Point: {... ; ... ; ... ; ...}
        internal fun pointToString(p: Curve.Point): String {
            return p.position.flatten() + ';' + p.tangent.flatten() + ';' + p.normal.flatten() + ';' + p.arcLength
        }

        internal fun stringToPoint(s: String, curve: PrimeCurveImpl, idx: Int): Curve.Point {
            val a = s.split(';')
            return Curve.Point(
                position = Vector3D.fromFlattenString(a[0]),  // position
                tangent = Vector3D.fromFlattenString(a[1]),  // tangent
                normal = Vector3D.fromFlattenString(a[2]),  // normal
                curve = curve,
                idx = idx,
                arcLength = a[3].toDouble()  // arcLength
            )
        }

        // Curve: {p:<point>|<point>|...}
        fun saveAsPointList(obj: PrimeCurveImpl): String {
            return "p:" + obj.pointList.joinToString("|") { pointToString(it) }
        }

        fun loadAsPointList(pList: String): PrimeCurveImpl {
            val curve = PrimeCurveImpl()
            val pList = pList.split("|")
            val pointList = pList.mapIndexed { idx, s -> stringToPoint(s, curve, idx) }
            curve.pointList = pointList
            return curve
        }

        override fun save(obj: PrimeCurveImpl): String {
            return saveAsPointList(obj)
        }

        override fun load(persistent: String): PrimeCurveImpl {
            val split = persistent.split(':', limit = 2)
            val type = split[0]
            if (type == "p") {
                return loadAsPointList(split[1])
            } else {
                throw LoadPersistentDataException(PrimeCurveImpl::class, persistent)
            }
        }
    }
}