// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import java.nio.file.Path

/** Decides which unmanaged Java SDKs may be removed from the IDE's SDK list. */
object SdkPruning {

    /**
     * An SDK on the [keep] list always stays. Otherwise SDKs with a missing home are removed, as
     * are SDKs living under [sdkmanJava] (also via symlinks); SDKs elsewhere (JBang, Gradle,
     * manual installations) are only removed with [includeOutside].
     */
    fun shouldRemove(name: String, home: String?, keep: Set<String>, sdkmanJava: Path, includeOutside: Boolean): Boolean {
        if (name in keep) return false
        val real = home?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
            ?: return true
        if (includeOutside) return true
        return runCatching { real.startsWith(sdkmanJava.toRealPath()) }.getOrDefault(false)
    }

    /** Comma-separated SDK names; SDK names may contain spaces, e.g. `temurin-21 (7)`. */
    fun parseNames(text: String?): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
