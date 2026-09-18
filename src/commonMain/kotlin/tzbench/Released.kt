package tzbench

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Subjects for the released API (kotlinx-datetime 0.8.0), where a time zone is obtained through
 * the `TimeZone` companion.
 *
 * The set is chosen to decompose the cost rather than to name it with one number, because the two
 * halves differ in how safe they are to cache:
 *
 *   now             — reading the clock; a baseline the reader can anchor everything else to;
 *   of_utc          — `TimeZone.of("UTC")` takes a constant fast path and never opens a file:
 *                     the control that sizes the harness itself;
 *   of_current_id   — the system's own zone, but named explicitly: reading and parsing its file
 *                     WITHOUT looking up which zone is current;
 *   of_region       — the same for a zone with real transition history, which is what a device
 *                     outside UTC actually has;
 *   current         — `currentSystemDefault()`: of_current_id PLUS finding the current zone;
 *   to_local_*      — the conversion itself, with the zone cached and with it obtained per call.
 */
private val cachedZone = TimeZone.currentSystemDefault()

private val SUBJECTS =
    listOf("now", "of_utc", "of_current_id", "of_region", "current", "to_local_cached", "to_local_current")

private fun batch(subject: String, iters: Int): Double = when (subject) {
    "now" -> timed(iters) { Sink.acc += Clock.System.now().epochSeconds }
    "of_utc" -> timed(iters) { Sink.acc += TimeZone.of("UTC").id.length.toLong() }
    "of_current_id" -> timed(iters) { Sink.acc += TimeZone.of(cachedZone.id).id.length.toLong() }
    "of_region" -> timed(iters) { Sink.acc += TimeZone.of("Europe/Berlin").id.length.toLong() }
    "current" -> timed(iters) { Sink.acc += TimeZone.currentSystemDefault().id.length.toLong() }
    "to_local_cached" -> timed(iters) { Sink.acc += Clock.System.now().toLocalDateTime(cachedZone).second.toLong() }
    "to_local_current" ->
        timed(iters) { Sink.acc += Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).second.toLong() }
    else -> error("unknown subject: $subject")
}

public fun main(args: Array<String>) {
    runSuite(
        label = args.getOrNull(0) ?: "released",
        header = "api=TimeZone.companion zone=${cachedZone.id}",
        rounds = args.getOrNull(1)?.toInt() ?: 7,
        subjects = SUBJECTS,
        batch = ::batch,
    )
}
