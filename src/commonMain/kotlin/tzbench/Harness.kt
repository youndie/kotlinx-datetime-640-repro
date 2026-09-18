package tzbench

import kotlin.time.TimeSource

/**
 * The measuring loop, shared by both subject sets so that the released API and the current `master`
 * are measured by the same instrument rather than by two similar-looking ones.
 *
 * Three properties matter here, and each is a correction of a way this kind of benchmark usually
 * goes wrong:
 *
 * - **every subject has its own loop** (see the `batch` lambdas): a shared per-iteration lambda
 *   costs more than the cheapest subjects here, and the benchmark would be measuring itself;
 * - **batches are calibrated to a fixed duration**, not to a fixed iteration count: the subjects
 *   differ by four orders of magnitude, and one iteration count cannot serve both;
 * - **subjects are interleaved**, with the order reversed on alternate rounds, so that machine
 *   drift turns into spread instead of bias.
 */
public object Sink {
    public var acc: Long = 0
}

private fun calibrate(subject: String, targetMs: Int, batch: (String, Int) -> Double): Int {
    var iters = 200
    repeat(3) {
        val ns = batch(subject, iters)
        iters = ((targetMs * 1_000_000.0 / ns).toLong()).coerceIn(200L, 20_000_000L).toInt()
    }
    return iters
}

private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun round1(x: Double): String {
    val v = (x * 10).toLong()
    return "${v / 10}.${v % 10}"
}

/** Runs [subjects] through [batch] and prints one median per subject. */
public fun runSuite(
    label: String,
    header: String,
    rounds: Int,
    subjects: List<String>,
    batch: (String, Int) -> Double,
) {
    println("# label=$label $header rounds=$rounds")

    val iters = HashMap<String, Int>()
    for (s in subjects) {
        var n = calibrate(s, 200, batch)
        repeat(2) { batch(s, n) }          // warm up at the real batch size
        n = calibrate(s, 120, batch)       // then calibrate again, now warm
        iters[s] = n
    }

    val samples = HashMap<String, MutableList<Double>>().apply { subjects.forEach { put(it, mutableListOf()) } }
    for (round in 1..rounds) {
        for (s in if (round % 2 == 0) subjects.reversed() else subjects) {
            repeat(3) { samples.getValue(s).add(batch(s, iters.getValue(s))) }
        }
    }

    println("subject".padEnd(22) + "ns/op (median)".padStart(16) + "iters".padStart(12))
    for (s in subjects) {
        println(s.padEnd(22) + round1(median(samples.getValue(s))).padStart(16) + iters.getValue(s).toString().padStart(12))
    }
    println("# sink=${Sink.acc}")
}

/** Times one batch of [iters] repetitions of [body], in nanoseconds per operation. */
public inline fun timed(iters: Int, body: (Int) -> Unit): Double {
    val mark = TimeSource.Monotonic.markNow()
    for (i in 0 until iters) body(i)
    return mark.elapsedNow().inWholeNanoseconds.toDouble() / iters
}
