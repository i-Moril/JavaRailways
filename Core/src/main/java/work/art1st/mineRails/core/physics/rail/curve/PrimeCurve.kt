package work.art1st.mineRails.core.physics.rail.curve

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.json.JsonObject
import work.art1st.mineRails.core.util.BinarySearch
import work.art1st.mineRails.core.util.DoubleCompare
import work.art1st.mineRails.core.util.Heavy
import work.art1st.mineRails.core.util.Vector3D
import java.util.stream.Stream

@Serializable(PrimeCurve.PrimeCurveSerializer::class)
class PrimeCurve : Curve {
    private lateinit var pointList: List<Curve.Point>
    override val totalArcLength: Double
        get() = pointList.last().arcLength
    override val totalPoints: Int
        get() = pointList.size
    @Transient
    override val reversed: Curve = ReversedCurve(this)

    private fun getIndexAt(arcLength: Double) : Int? {
        val baseArcLength: Double = pointList.first().arcLength
        return BinarySearch.searchInteger(
            { i: Int ->
                DoubleCompare.compare(
                    pointList[i].arcLength - arcLength - baseArcLength,
                    Curve.TOLERANCE
                )
            },
            0, pointList.size
        )
    }

    override fun getPointAt(idx: Int): Curve.Point? {
        if (idx < 0 || idx >= pointList.size) return null
        return pointList[idx]
    }

    override fun getPointAt(arcLength: Double): Curve.Point? {
        return getIndexAt(arcLength)?.let { pointList.getOrNull(it) }
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return pointList.stream()
    }

    object PrimeCurveSerializer : KSerializer<PrimeCurve> {
        private val delegateSerializer = ListSerializer(String.serializer())
        override val descriptor: SerialDescriptor = SerialDescriptor("PrimeCurve", delegateSerializer.descriptor)

        private fun pointToString(p: Curve.Point): String {
            return p.position.flatten() + ';' + p.normal.flatten() + ';' + p.tangent.flatten() + ';' + p.arcLength
        }

        private fun stringToPoint(s: String, curve: PrimeCurve, idx: Int): Curve.Point {
            val a = s.split(';')
            return Curve.Point(
                Vector3D.fromFlattenString(a[0]),  // position
                Vector3D.fromFlattenString(a[1]),  // normal
                Vector3D.fromFlattenString(a[2]),  // tangent
                curve,
                idx,
                a[3].toDouble()  // arcLength
            )
        }

        override fun serialize(encoder: Encoder, value: PrimeCurve) {
            val stringList = value.pointList.map { pointToString(it) }
            delegateSerializer.serialize(encoder, stringList)
        }

        override fun deserialize(decoder: Decoder): PrimeCurve {
            val stringList = delegateSerializer.deserialize(decoder)
            val curve = PrimeCurve()
            val pointList = stringList.mapIndexed { idx, s -> stringToPoint(s, curve, idx) }
            curve.pointList = pointList
            return curve
        }
    }
}
