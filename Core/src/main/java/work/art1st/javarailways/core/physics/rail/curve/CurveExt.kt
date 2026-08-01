package work.art1st.javarailways.core.physics.rail.curve

import work.art1st.javarailways.core.api.physics.curve.Curve

/** 弧长反转：新弧长对应的原曲线位置 */
fun reverseArcLength(arcLength: Double, curve: Curve) = curve.totalArcLength - arcLength
