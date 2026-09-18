# What a time zone costs on Kotlin/Native

A self-contained benchmark for the question raised in
[Kotlin/kotlinx-datetime#640](https://github.com/Kotlin/kotlinx-datetime/issues/640) —
*"Decide on the caching strategy for timezone data"* — which asks to **measure the impact** of
typical timezone usage before choosing a strategy.

The same Kotlin source is compiled four ways — `linuxX64` and JVM, against the released
**0.8.0** and against **`master`** — and the four runs are interleaved on one idle machine, so the
numbers differ by the target and the library version and by nothing else.

`master` matters here because
[PR #610](https://github.com/Kotlin/kotlinx-datetime/pull/610) (merged 2026-08-20, after 0.8.0,
still unreleased) deprecates `TimeZone.currentSystemDefault()` and `TimeZone.of()` in favour of an
injected `TimeZoneContext`. The question this repository answers is whether the new facade also
changed the cost.

## The result

Medians in nanoseconds per operation, system zone `Etc/UTC`, Kotlin 2.4.20 for the benchmark,
`master` at `b372c49`.

| subject | 0.8.0 native | master native | 0.8.0 JVM | master JVM |
|---|---:|---:|---:|---:|
| `Clock.System.now()` — baseline | 69 | 64 | 59 | 58 |
| get `UTC` — constant fast path, control | 19 | 22 | 27 | 14 |
| **find the current zone id alone** | — | **12 477** | — | **13.6** |
| get the current zone by name | 18 970 | 18 845 | 75 | 95 |
| get `Europe/Berlin` | 182 094 | 176 893 | 66 | 85 |
| **the current zone, end to end** | **31 663** | **30 549** | **38** | **33** |
| deprecated companion API, on `master` | — | 31 945 | — | 38 |
| convert with the zone already in hand | 207 | 225 | 107 | 97 |

### The facade changed; the cost did not

`TimeZoneContext.System.currentTimeZone()` on `master` costs 30.5 µs against 31.7 µs for
`TimeZone.currentSystemDefault()` on 0.8.0, and `get("Europe/Berlin")` costs 177 µs against 182 µs.
The deprecated companion functions, still present on `master`, cost the same as the new ones.
No cache was introduced along with the new API — which is consistent with #640 being opened
minutes after #610 was merged, to settle that question separately.

### The cost has two halves, and they differ in how safe they are to cache

The new API makes the decomposition directly measurable: `currentTimeZoneId()` is now public, so
"find which zone is current" can be timed on its own instead of being inferred by subtraction.

| half | cost on Kotlin/Native |
|---|---:|
| find which zone is current (chases the `/etc/localtime` symlink) | **12.5 µs** |
| read and parse that zone's file — `Etc/UTC`, a degenerate file | **18.8 µs** |
| read and parse `Europe/Berlin`, a zone with real transition history | **176.9 µs** |

The two add up: 12.5 + 18.8 ≈ 31 µs against 30.5 µs measured end to end, so there is nothing else
hiding in `currentTimeZone()`.

Caching **parsed rules per zone id** changes no observable behaviour — tzdb files do not change
under a running process, and this is what the JVM already does. On these numbers it removes about
90% of the cost for a real zone (177 µs → ~12 µs) and leaves a floor.

Caching **which zone is current** is a behaviour decision rather than an optimisation: a process
that caches it stops noticing a time zone change. That is the remaining ~12 µs, and whether it is
worth paying for is a question the numbers cannot answer.

### A machine in UTC understates all of this

`Etc/UTC` — what most CI runners and containers report — is a degenerate file with almost no
transitions, and parsing it costs 19 µs. `Europe/Berlin` costs **ten times** more. Any measurement
of this taken in UTC, including the first version of this one, understates what a device in a real
time zone pays.

## Why the JVM is unaffected

Two caches, neither of which exists on Kotlin/Native:

1. `ZoneId.systemDefault()` is `TimeZone.getDefault().toZoneId()` (`java/time/ZoneId.java:275`), and
   `getDefault()` returns a clone of the static field `defaultTimeZone`
   (`java/util/TimeZone.java:834`). No filesystem access per call — which is why finding the
   current zone id costs 13.6 ns on the JVM and 12 477 ns on Native.
2. `TzdbZoneRulesProvider` holds `private final Map<String, Object> regionToRules =
   new ConcurrentHashMap<>()` and, in `provideRules`, deserialises a zone once and **writes the
   parsed object back into the map**, so a zone is parsed at most once per process. The tzdb is a
   single file (`lib/tzdb.dat`) read at provider initialisation, not one file per zone.

On Kotlin/Native, `TzdbOnFilesystem.rulesForId` opens `/usr/share/zoneinfo/<Zone>` and parses the
TZif contents on **every** call, and `currentSystemTimeZonePath` is a `get()` that chases the
`/etc/localtime` symlink on **every** access.

## Method

Each subject has its own loop: a shared per-iteration lambda would cost more than the cheapest
subjects here, and the benchmark would be measuring itself. Batches are calibrated to a fixed
duration (~120 ms) rather than to a fixed iteration count, because the subjects differ by four
orders of magnitude. Subjects are interleaved within a run and the order is reversed on alternate
rounds; the four builds are interleaved across two passes, so drift becomes spread rather than
bias. Getting `UTC` takes a constant fast path and never opens a file: it is the control that
sizes the harness itself.

Each number above is a median of two passes, each of which is a median of 21 batches. Run to run,
the expensive subjects move by about 10%; nothing in the conclusions turns on a difference that
small.

## Running it

```bash
# against the released 0.8.0
./gradlew bundleJvm linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/tzbench.kexe released 7
java -cp "build/jvmlib/*" tzbench.ReleasedKt released 7

# against master, once it is published to mavenLocal as 0.8.0-SNAPSHOT
./gradlew -Pmaster bundleJvm linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/tzbench.kexe master 7
java -cp "build/jvmlib/*" tzbench.master.MasterKt master 7
```

The first argument is a label, the second the number of rounds. Raw output of the run quoted above
is in [results/all-runs.txt](results/all-runs.txt).

Publishing `master` locally needs more than a current JDK, which is worth knowing before starting:
its build asks for **JDK 8** (for `compileJavaModuleInfo`) and **JDK 11** toolchains and configures
no toolchain download repositories, so both have to be present and pointed at with
`-Porg.gradle.java.installations.paths=...`. The project is also renamed in `settings.gradle.kts`
— the directory is `core`, the Gradle project is `:kotlinx-datetime`, and task paths follow the
latter.

## The machine

A dedicated 4-core Intel Xeon (Skylake) virtual machine, 7.5 GiB, Ubuntu 24.04, JDK 25,
system zone `Etc/UTC`, idle apart from the benchmark, with Gradle daemons stopped before measuring.

**Absolute nanoseconds belong to this machine.** What carries across hardware is the ratio between
subjects measured by the same harness in the same run — which is why the baseline and the control
are in the table.
