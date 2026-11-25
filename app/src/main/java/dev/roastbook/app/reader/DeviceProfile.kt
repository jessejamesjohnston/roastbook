package dev.roastbook.app.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Whether this device can run the on-device reader at all. */
enum class Eligibility { ELIGIBLE, LOW_MEMORY, UNSUPPORTED_CPU }

/**
 * The reader needs about 4 GB resident while it reads (measured on a flagship: the memory-mapped
 * model plus the CPU runtime), so it is offered only on phones sold with 8 GB of RAM or more.
 *
 * `ActivityManager.MemoryInfo.totalMem` reports what the kernel manages, which is less than the
 * number on the box (firmware, modem and GPU carve-outs): a phone sold with 8 GB reports roughly
 * 7.2 to 7.6 GiB, and one sold with 6 GB roughly 5.4 to 5.8 GiB. The floor sits between the two,
 * at 6.5 GiB, so every "8 GB" phone qualifies and no "6 GB" phone does.
 *
 * The runtime ships native code for arm64-v8a and x86_64 (emulators) only.
 */
object DeviceProfile {
    const val MIN_TOTAL_MEM_BYTES: Long = 6_979_321_856L // 6.5 GiB
    val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    fun eligibility(totalMemBytes: Long, abis: List<String>): Eligibility = when {
        abis.none { it in SUPPORTED_ABIS } -> Eligibility.UNSUPPORTED_CPU
        totalMemBytes < MIN_TOTAL_MEM_BYTES -> Eligibility.LOW_MEMORY
        else -> Eligibility.ELIGIBLE
    }

    fun totalMem(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun of(context: Context): Eligibility = eligibility(totalMem(context), Build.SUPPORTED_ABIS.toList())
}
