plugins { kotlin("multiplatform") version "2.4.20" }

kotlin {
    jvm()
    linuxX64 { binaries.executable { entryPoint = "tzbench.main"; optimized = true } }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.findProperty("dtVersion") ?: "0.8.0"}")
        }
    }
}

val bundleJvm by tasks.registering(Sync::class) {
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("jvmlib"))
}
