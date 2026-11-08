// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** LTS major versions, as published by the foojay Disco API (the source SDKMAN uses as well). */
object Lts {
    const val FOOJAY_URL =
        "https://api.foojay.io/disco/v3.0/major_versions?ea=false&ga=true&maintained=true&include_build=false"

    /** Extracts the LTS majors from a foojay `major_versions` response. */
    fun parseFoojay(json: String): Set<Int> =
        json.filterNot { it.isWhitespace() }
            .split('{')
            .filter { "\"term_of_support\":\"LTS\"" in it }
            .mapNotNull { Regex("\"major_version\":(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
            .toSortedSet()

    /** The pre-foojay rule: 8, 11, then every fourth release since 17. */
    fun fallback(upTo: Int = 99): Set<Int> =
        (8..upTo).filter { it == 8 || it == 11 || (it >= 17 && (it - 17) % 4 == 0) }.toSortedSet()

    /**
     * LTS majors with a [cache] file that is reused for [maxAge]; falls back to a stale cache and
     * finally to [fallback] when [fetch] fails.
     */
    fun majors(fetch: (String) -> String, cache: Path, maxAge: Duration = Duration.ofDays(1)): Set<Int> {
        fun cached(): Set<Int>? = runCatching {
            cache.readText().lines().mapNotNull { it.trim().toIntOrNull() }.toSortedSet().ifEmpty { null }
        }.getOrNull()

        if (cache.exists() && cache.getLastModifiedTime().toInstant().isAfter(Instant.now().minus(maxAge))) {
            cached()?.let { return it }
        }
        val fresh = runCatching { parseFoojay(fetch(FOOJAY_URL)) }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            runCatching {
                Files.createDirectories(cache.parent)
                cache.writeText(fresh.joinToString("\n", postfix = "\n"))
            }
            return fresh
        }
        return cached() ?: fallback()
    }
}
