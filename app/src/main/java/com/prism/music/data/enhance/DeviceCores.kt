package com.prism.music.data.enhance

import java.io.File

/**
 * The phone's cores, for sizing the engines' thread pools. Inference threads all compete for the same clusters, and on a
 * big.LITTLE phone only the fast ones are worth giving them to: a pool as wide as the little cores makes the model slower
 * (more synchronisation, less work per thread), and lets the scheduler park half of it on slow cores.
 *
 * Read once from the kernel's cpufreq files; a phone that doesn't publish them (or a desktop JVM running the tests) falls
 * back to the processor count.
 */
internal object DeviceCores {
    val total: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Cores that can reach (nearly) the phone's top clock: big and middle cores on a big.LITTLE SoC. */
    val big: Int by lazy {
        val clocks = (0 until total).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (clocks.isEmpty()) return@lazy total
        val top = clocks.max()
        // Within a quarter of the top clock counts as fast. Never fewer than one, never more than the phone has.
        clocks.count { it >= top - top / 4 }.coerceIn(1, total)
    }

    /** Threads for an engine: its own cap, but never more than the fast cores (and always at least one). */
    fun threads(cap: Int): Int = minOf(cap, big).coerceAtLeast(1)
}
