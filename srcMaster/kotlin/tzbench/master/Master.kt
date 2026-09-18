@file:Suppress("DEPRECATION")

package tzbench.master

import tzbench.Sink
import tzbench.runSuite
import tzbench.timed

import kotlinx.datetime.TimeZone
import kotlinx.datetime.TimeZoneContext
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Subjects for the API introduced by
 * [PR #610](https://github.com/Kotlin/kotlinx-datetime/pull/610), merged after 0.8.0 and not yet
 * released, where the companion functions are deprecated in favour of an injected
 * `TimeZoneContext`.
 *
 * Two things are measured that the released API could not express:
 *
 * - `ctx_current_id` calls `currentTimeZoneId()` **on its own**. On the released API the cost of
 *   "find which zone is current" could only be reached by subtraction; here it is a public
 *   function and can be timed directly.
 * - the deprecated companion functions are kept in the set, so the two versions are comparable
 *   in the same run rather than across two different tables.
 */
private val cachedZone = TimeZoneContext.System.currentTimeZone()

private val SUBJECTS =
    listOf(
        "now",
        "ctx_get_utc",
        "ctx_current_id",
        "ctx_get_current_id",
        "ctx_get_region",
        "ctx_current",
        "deprecated_current",
        "deprecated_of_region",
        "to_local_cached",
    )

private fun batch(subject: String, iters: Int): Double = when (subject) {
    "now" -> timed(iters) { Sink.acc += Clock.System.now().epochSeconds }
    "ctx_get_utc" -> timed(iters) { Sink.acc += TimeZoneContext.System.get("UTC").id.length.toLong() }
    "ctx_current_id" -> timed(iters) { Sink.acc += TimeZoneContext.System.currentTimeZoneId().length.toLong() }
    "ctx_get_current_id" -> timed(iters) { Sink.acc += TimeZoneContext.System.get(cachedZone.id).id.length.toLong() }
    "ctx_get_region" -> timed(iters) { Sink.acc += TimeZoneContext.System.get("Europe/Berlin").id.length.toLong() }
    "ctx_current" -> timed(iters) { Sink.acc += TimeZoneContext.System.currentTimeZone().id.length.toLong() }
    "deprecated_current" -> timed(iters) { Sink.acc += TimeZone.currentSystemDefault().id.length.toLong() }
    "deprecated_of_region" -> timed(iters) { Sink.acc += TimeZone.of("Europe/Berlin").id.length.toLong() }
    "to_local_cached" -> timed(iters) { Sink.acc += Clock.System.now().toLocalDateTime(cachedZone).second.toLong() }
    else -> error("unknown subject: $subject")
}

public fun main(args: Array<String>) {
    runSuite(
        label = args.getOrNull(0) ?: "master",
        header = "api=TimeZoneContext zone=${cachedZone.id}",
        rounds = args.getOrNull(1)?.toInt() ?: 7,
        subjects = SUBJECTS,
        batch = ::batch,
    )
}
