package tzbench

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Во что обходится получение часового пояса. Минимальный репро к
 * https://github.com/Kotlin/kotlinx-datetime/issues/640
 *
 * Субъекты подобраны так, чтобы РАЗЛОЖИТЬ цену, а не назвать её одним числом — от разложения
 * зависит, что именно стоит кэшировать:
 *
 *   now                  — чтение часов, опорная величина;
 *   of_utc               — TimeZone.of("UTC"), быстрый путь на константу: гарнитура и диспетчеризация;
 *   of_current_id        — TimeZone.of(<id текущей зоны>): чтение и разбор ТОГО ЖЕ файла, что
 *                          нужен current, но без поиска «какая зона текущая»;
 *   of_region            — TimeZone.of("Europe/Berlin"): то же для зоны с настоящей историей
 *                          переходов. Показывает, как цена разбора зависит от содержимого файла;
 *   current              — TimeZone.currentSystemDefault(): то же самое ПЛЮС проход по симлинку
 *                          /etc/localtime на каждый вызов;
 *   to_local_cached      — перевод в локальное время с зоной, взятой один раз (что сейчас
 *                          рекомендуется пользователю делать руками);
 *   to_local_current     — то же выражение, но с currentSystemDefault() внутри — форма, которая
 *                          встречается в реальном коде.
 *
 * Разница current − of_region и есть цена поиска текущей зоны; разница of_region − of_utc —
 * цена чтения и разбора файла.
 */
private object Sink {
    var acc: Long = 0
}

private val SUBJECTS =
    listOf("now", "of_utc", "of_current_id", "of_region", "current", "to_local_cached", "to_local_current")

private val cachedZone = TimeZone.currentSystemDefault()

private fun batch(subject: String, iters: Int): Double {
    val mark = TimeSource.Monotonic.markNow()
    when (subject) {
        "now" -> for (i in 0 until iters) Sink.acc += Clock.System.now().epochSeconds
        "of_utc" -> for (i in 0 until iters) Sink.acc += TimeZone.of("UTC").id.length.toLong()
        // Та же зона, что вернёт система, но названная по имени: без прохода по симлинку.
        // Разница current - of_current_id и есть цена поиска «какая зона сейчас текущая».
        "of_current_id" -> for (i in 0 until iters) Sink.acc += TimeZone.of(cachedZone.id).id.length.toLong()
        "of_region" -> for (i in 0 until iters) Sink.acc += TimeZone.of("Europe/Berlin").id.length.toLong()
        "current" -> for (i in 0 until iters) Sink.acc += TimeZone.currentSystemDefault().id.length.toLong()
        "to_local_cached" ->
            for (i in 0 until iters) Sink.acc += Clock.System.now().toLocalDateTime(cachedZone).second.toLong()
        "to_local_current" ->
            for (i in 0 until iters) {
                Sink.acc += Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).second.toLong()
            }
        else -> error("unknown subject: $subject")
    }
    return mark.elapsedNow().inWholeNanoseconds.toDouble() / iters
}

private fun calibrate(subject: String, targetMs: Int): Int {
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

public fun main(args: Array<String>) {
    val label = args.getOrNull(0) ?: "unknown"
    val rounds = args.getOrNull(1)?.toInt() ?: 7

    println("# label=$label zone=${cachedZone.id} rounds=$rounds")
    val iters = HashMap<String, Int>()
    for (s in SUBJECTS) {
        var n = calibrate(s, 200)
        repeat(2) { batch(s, n) }
        n = calibrate(s, 120)
        iters[s] = n
    }

    // Субъекты чередуются внутри раунда: блоками подряд дрейф машины записался бы в разницу.
    val samples = HashMap<String, MutableList<Double>>().apply { SUBJECTS.forEach { put(it, mutableListOf()) } }
    for (round in 1..rounds) {
        for (s in if (round % 2 == 0) SUBJECTS.reversed() else SUBJECTS) {
            repeat(3) { samples.getValue(s).add(batch(s, iters.getValue(s))) }
        }
    }

    // Без String.format: его нет в общем коде — на Kotlin/Native он отсутствует.
    fun round1(x: Double): String {
        val v = (x * 10).toLong()
        return "${v / 10}.${v % 10}"
    }
    println("subject".padEnd(20) + "ns/op (median)".padStart(16) + "iters".padStart(12))
    for (s in SUBJECTS) {
        println(s.padEnd(20) + round1(median(samples.getValue(s))).padStart(16) + iters.getValue(s).toString().padStart(12))
    }
    println("# sink=${Sink.acc}")
}
