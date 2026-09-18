# What a time zone costs on Kotlin/Native

A minimal, self-contained reproducer for
[Kotlin/kotlinx-datetime#640](https://github.com/Kotlin/kotlinx-datetime/issues/640) —
*"Decide on the caching strategy for timezone data"*.

That issue asks to **measure the impact** before choosing a strategy. This repository is the
measurement: the same Kotlin source compiled for `linuxX64` and for the JVM, run back to back on
one idle machine, so the two numbers differ by the target and by nothing else.

## The result

Medians, nanoseconds per operation. `kotlinx-datetime` 0.8.0, Kotlin 2.4.20, system zone `Etc/UTC`.

| subject | Kotlin/Native | JVM | ratio |
|---|---:|---:|---:|
| `Clock.System.now()` — baseline | 60 | 60 | 1.0× |
| `TimeZone.of("UTC")` — constant fast path, control | 14 | 45 | — |
| `TimeZone.of("Etc/UTC")` — the current zone, by name | **18 423** | 79 | 233× |
| `TimeZone.of("Europe/Berlin")` | **188 670** | 68 | **2 775×** |
| `TimeZone.currentSystemDefault()` | **32 694** | 38 | **861×** |
| `now().toLocalDateTime(cachedZone)` | 223 | 107 | 2.1× |
| `now().toLocalDateTime(TimeZone.currentSystemDefault())` | **32 315** | 141 | 229× |

Two things are worth reading off this table before any conclusion about caching.

**The conversion itself is fine.** With the zone already in hand, Kotlin/Native is 2.1× the JVM —
ordinary. Everything else in the table is the cost of *obtaining* the zone, not of using it.

**The cost has two independent halves**, and the subjects were chosen to separate them:

| half | how it is derived | cost |
|---|---|---:|
| finding *which* zone is current | `currentSystemDefault()` − `of("Etc/UTC")` | **14.3 µs** |
| reading and parsing *that* zone's file | `of("Etc/UTC")` − `of("UTC")` | **18.4 µs** |
| the same for a zone with real transition history | `of("Europe/Berlin")` − `of("UTC")` | **188.7 µs** |

## Why this matters for the caching decision

The two halves are not equally safe to cache.

Caching **parsed rules per zone id** changes no observable behaviour: tzdb files do not change
under a running process, and this is what the JVM already does. On these numbers it would take
`currentSystemDefault()` from 32.7 µs to about 14 µs on this machine, and a service in a real zone
from ~203 µs to ~14 µs — roughly 90% of the cost, for free.

Caching **which zone is current** is a behaviour decision, not an optimisation: a process that
caches it stops noticing a time zone change. That is the remaining ~14 µs floor, and whether it is
worth paying for is a policy question the numbers cannot answer.

The measurement also shows that a machine in `Etc/UTC` — most CI runners, most containers — sees
the *cheapest possible* version of this. `Etc/UTC` is a degenerate file with almost no transitions;
`Europe/Berlin` costs ten times more to parse. Benchmarks taken in UTC understate the problem for
every device that is in a real time zone.

## Why the JVM is unaffected

Two caches, both absent on Kotlin/Native:

1. `ZoneId.systemDefault()` is `TimeZone.getDefault().toZoneId()` (`java/time/ZoneId.java:275`), and
   `getDefault()` returns a clone of the static field `defaultTimeZone`
   (`java/util/TimeZone.java:834`). No filesystem access per call.
2. `TzdbZoneRulesProvider` keeps `private final Map<String, Object> regionToRules =
   new ConcurrentHashMap<>()` and, in `provideRules`, deserialises a zone once and **writes the
   parsed object back into the map**, so the parse happens at most once per zone per process.
   The tzdb itself is one file (`lib/tzdb.dat`), read at provider initialisation — not one file
   per zone.

On Kotlin/Native, `TzdbOnFilesystem.rulesForId` opens `/usr/share/zoneinfo/<Zone>` and parses the
TZif contents on **every** call, and `currentSystemTimeZonePath` is a `get()` that chases the
`/etc/localtime` symlink on **every** access.

## Method

Each subject has its own loop — a shared lambda per iteration would cost more than the cheapest
subjects and the benchmark would be measuring itself. Batches are calibrated to a fixed duration
(~120 ms) rather than to a fixed iteration count, because the subjects differ by four orders of
magnitude. Subjects are interleaved and the order is reversed on alternate rounds, so machine drift
becomes spread rather than bias. `TimeZone.of("UTC")` takes a constant fast path and never touches
a file: it is the control that sizes the harness itself.

## Running it

```bash
./gradlew linkReleaseExecutableLinuxX64 bundleJvm
./build/bin/linuxX64/releaseExecutable/tzbench.kexe native 7
java -cp "build/jvmlib/*" tzbench.MainKt jvm 7
```

The first argument is a label, the second the number of rounds. Raw output from the run quoted
above is in [results/](results/).

## The machine

A dedicated 4-core Intel Xeon (Skylake) virtual machine, 7.5 GiB, Ubuntu 24.04, JDK 25,
system zone `Etc/UTC`, idle apart from the benchmark.

**Absolute nanoseconds belong to this machine.** What carries across hardware is the ratio between
subjects measured by the same harness in the same run — which is why the control and the baseline
are in the table.
