plugins { kotlin("multiplatform") version "2.4.20" }

// Две измеряемые версии: выпущенная и текущий master, у которого другой фасад API.
// Переключается флагом, чтобы гарнитура и машина остались теми же, а менялся только субъект:
//   ./gradlew ...                 — kotlinx-datetime 0.8.0, API через TimeZone.companion
//   ./gradlew ... -Pmaster        — 0.8.0-SNAPSHOT из mavenLocal, API через TimeZoneContext
val useMaster = providers.gradleProperty("master").isPresent
val dtVersion = if (useMaster) "0.8.0-SNAPSHOT" else (project.findProperty("dtVersion") as String? ?: "0.8.0")
val entry = if (useMaster) "tzbench.master.main" else "tzbench.main"

kotlin {
    jvm()
    linuxX64 { binaries.executable { entryPoint = entry; optimized = true } }

    sourceSets {
        commonMain {
            if (useMaster) kotlin.srcDir("srcMaster/kotlin")
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-datetime:$dtVersion") }
        }
    }
}

val bundleJvm by tasks.registering(Sync::class) {
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("jvmlib"))
}

tasks.register("printSetup") {
    val v = dtVersion
    val e = entry
    doLast { println("kotlinx-datetime=$v entryPoint=$e") }
}
