package work.art1st.javarailways.simpleato.control

import kotlin.math.*

/**
 * Discrete affine sums for a frozen nominal actuator response. A segment changes at a device
 * event (delay expiry, slew catch or saturation), never at every future physical tick.
 * The slew/catch identities are independently checked against the deployed dynamics kernel.
 */
internal object AffineActuatorResponse {
    const val LAST: Long = 1_000_000_000L
    data class Segment(val start: Long, val end: Long, val first: Double, val slope: Double = 0.0) {
        fun at(tick: Long) = first + slope * (tick - start)
    }
    class Signal(val segments: List<Segment>) {
        fun at(tick: Long) = segments.first { tick <= it.end }.at(tick)
    }
    fun constant(value: Double) = Signal(listOf(Segment(1, LAST, value)))

    fun slew(target: Signal, initial: Double, rise: Double, fall: Double): Signal {
        var current = initial
        val result = mutableListOf<Segment>()
        for (segment in target.segments) {
            var start = segment.start
            repeat(8) {
                if (start <= segment.end) {
                    val first = segment.at(start)
                    val rate = if (first < current) fall else rise
                    if (abs(first - current) <= rate || abs(first - current) < 1e-9) {
                        result += Segment(start, start, first)
                        current = first
                        start++
                        val trackingRate = if (segment.slope < 0.0) fall else rise
                        if (start <= segment.end && abs(segment.slope) <= trackingRate) {
                            result += Segment(start, segment.end, segment.at(start), segment.slope)
                            current = segment.at(segment.end)
                            start = segment.end + 1
                        }
                    } else {
                        val step = sign(first - current) * rate
                        val denominator = step - segment.slope
                        val catch = if (denominator == 0.0) Double.POSITIVE_INFINITY
                            else (first - segment.slope - current) / denominator
                        val remaining = segment.end - start + 1
                        val count = if (catch.isFinite() && catch > 1.0)
                            min(remaining, ceil(catch).toLong() - 1) else remaining
                        result += Segment(start, start + count - 1, current + step, step)
                        current += step * count
                        start += count
                    }
                }
            }
            check(start > segment.end) { "affine slew exceeded its finite catch budget" }
        }
        return Signal(result)
    }

    fun combine(signals: List<Signal>, operation: (List<Double>) -> Double): Signal {
        val events = sortedSetOf(1L, LAST + 1)
        for (signal in signals) for (segment in signal.segments) {
            events += segment.start
            events += segment.end + 1
        }
        return Signal(events.toList().zipWithNext { start, end ->
            val first = operation(signals.map { it.at(start) })
            val slope = if (end > start + 1) operation(signals.map { it.at(start + 1) }) - first else 0.0
            Segment(start, end - 1, first, slope)
        })
    }

    fun clamp(signal: Signal, lower: Double, upper: Double): Signal = Signal(buildList {
        for (segment in signal.segments) {
            val events = sortedSetOf(segment.start, segment.end + 1)
            if (segment.slope != 0.0) for (bound in listOf(lower, upper).filter { it.isFinite() }) {
                val crossing = segment.start + (bound - segment.first) / segment.slope
                if (crossing > segment.start && crossing <= segment.end) events += ceil(crossing).toLong()
            }
            events.toList().zipWithNext { start, end ->
                val first = segment.at(start).coerceIn(lower, upper)
                val slope = if (end > start + 1)
                    segment.at(start + 1).coerceIn(lower, upper) - first else 0.0
                add(Segment(start, end - 1, first, slope))
            }
        }
    })

    /** First zero crossing of velocity under the actual trapezoidal, jerk-limited integrator. */
    fun distanceToRest(acceleration: Signal, initialSpeed: Double, h: Double): Double {
        var speed = initialSpeed
        var distance = 0.0
        for (segment in acceleration.segments) {
            val count = segment.end - segment.start + 1
            val a0 = segment.first - segment.slope
            val quadratic = segment.slope * h / 2.0
            val linear = a0 * h + quadratic
            val crossing = if (quadratic == 0.0) {
                if (linear < 0.0) -speed / linear else Double.POSITIVE_INFINITY
            } else {
                val discriminant = linear * linear - 4.0 * quadratic * speed
                if (discriminant < 0.0) Double.POSITIVE_INFINITY else {
                    val q = -0.5 * (linear + Math.copySign(sqrt(discriminant), linear))
                    min((q / quadratic).takeIf { it > 0.0 } ?: Double.POSITIVE_INFINITY,
                        (speed / q).takeIf { it > 0.0 } ?: Double.POSITIVE_INFINITY)
                }
            }
            val stop = if (crossing.isFinite()) ceil(crossing).toLong().coerceAtLeast(1) else Long.MAX_VALUE
            val n = min(count, if (stop <= count) stop - 1 else count).toDouble()
            distance += speed * n * h + a0 * h * h * n * n / 2.0 +
                segment.slope * h * h * n * (n + 1) * (2 * n + 1) / 12.0
            speed += a0 * h * n + segment.slope * h * n * (n + 1) / 2.0
            if (stop <= count) return distance + max(0.0, speed) * h / 2.0
        }
        return Double.POSITIVE_INFINITY
    }
}
