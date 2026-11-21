plugins {
    id("com.android.application") version "9.4.1" apply false
    // :baselineprofile (1.7.4): scripted journeys through Prism that write its baseline profile and time its frames.
    id("com.android.test") version "9.4.1" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}

// Unit tests run in half as many forked JVMs as the machine has cores (eight here). Half the cores keeps the CPU busy
// without one full Robolectric runtime (plus its bitmaps and ONNX bound model) per core: sixteen forks measured slower
// than eight, 2m45s against 2m30s. The suite is shared-state-free enough for forks: 2,851 tests, all green.
// Reports stay on: the HTML page and the JUnit XML under app/build/test-results are what Android Studio reads a run's
// per-test results from.
subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}
