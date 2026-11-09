// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import java.nio.file.Path

/** Decides which unmanaged Java SDKs may be removed from the IDE's SDK list. */
object SdkPruning {

    /**
     * An SDK on the [keep] list always stays. Otherwise SDKs without a usable home are removed, as
     * are SDKs living under [sdkmanJava] (also via symlinks), whether or not their home still
     * exists. SDKs elsewhere (JBang, Gradle, manual installations, unplugged disks) are only
     * removed with [includeOutside].
     */
    fun shouldRemove(name: String, home: String?, keep: Set<String>, sdkmanJava: Path, includeOutside: Boolean): Boolean {
        if (name in keep) return false
        if (includeOutside || home.isNullOrBlank()) return true
        val path = runCatching { Path.of(home) }.getOrNull() ?: return true
        val sdkmanReal = runCatching { sdkmanJava.toRealPath() }.getOrNull()
        val real = runCatching { path.toRealPath() }.getOrNull()
        if (real != null) return sdkmanReal != null && real.startsWith(sdkmanReal)
        val absolute = path.toAbsolutePath().normalize()
        return absolute.startsWith(sdkmanJava.toAbsolutePath().normalize()) ||
            (sdkmanReal != null && absolute.startsWith(sdkmanReal))
    }

    /** The only managed major open projects may be moved to without asking: their own. */
    fun automaticReplacement(major: Int?, managed: Set<Int>): Int? = major?.takeIf { it in managed }

    /** Offered to the user when there is no [automaticReplacement]: next higher managed major, otherwise the newest. */
    fun suggestedReplacement(major: Int?, managed: Set<Int>): Int? =
        major?.let { m -> managed.filter { it > m }.minOrNull() } ?: managed.maxOrNull()

    /** Comma-separated SDK names; SDK names may contain spaces, e.g. `temurin-21 (7)`. */
    fun parseNames(text: String?): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
