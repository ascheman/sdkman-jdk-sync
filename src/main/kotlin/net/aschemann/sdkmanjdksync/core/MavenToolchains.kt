// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

/**
 * Text-preserving updates of a Maven `toolchains.xml`: only the `<jdkHome>` of matching JDK
 * toolchains changes (and, optionally, toolchains for missing majors are appended); comments,
 * formatting and all other entries stay as they are.
 */
object MavenToolchains {

    /** A JDK to point toolchains to: [home] is written as `jdkHome`, [vendor] is the SDKMAN suffix. */
    data class Jdk(val home: String, val vendor: String)

    data class Result(val text: String, val changes: List<String>)

    /** SDKMAN vendor suffix -> vendor names commonly used in toolchains.xml. */
    private val VENDOR_NAMES = mapOf(
        "tem" to setOf("temurin", "adoptium", "eclipse", "tem"),
        "zulu" to setOf("zulu", "azul"),
        "amzn" to setOf("corretto", "amazon", "amzn"),
        "librca" to setOf("liberica", "bellsoft", "librca"),
        "ms" to setOf("microsoft", "ms"),
        "oracle" to setOf("oracle"),
        "sapmchn" to setOf("sapmachine", "sap", "sapmchn"),
        "sem" to setOf("semeru", "ibm", "sem"),
        "graalce" to setOf("graalvm", "graalce"),
        "open" to setOf("openjdk", "open"),
    )

    private val TOOLCHAIN = Regex("""<toolchain>.*?</toolchain>""", RegexOption.DOT_MATCHES_ALL)
    private val TYPE = Regex("""<type>\s*jdk\s*</type>""")
    private val VERSION = Regex("""<version>\s*([^<]*?)\s*</version>""")
    private val VENDOR = Regex("""<vendor>\s*([^<]*?)\s*</vendor>""")
    private val JDK_HOME = Regex("""(<jdkHome>)\s*([^<]*?)\s*(</jdkHome>)""")

    /** Major of a plain toolchain version (`1.8`, `11`, `17.0`); ranges and other specs yield null. */
    fun majorOf(version: String): Int? {
        val m = Regex("""^(\d+)(?:\.(\d+))?(?:\.\d+)*$""").matchEntire(version.trim()) ?: return null
        val first = m.groupValues[1].toInt()
        return if (first == 1) m.groupValues[2].toIntOrNull() else first
    }

    fun vendorMatches(toolchainVendor: String?, sdkmanVendor: String): Boolean =
        toolchainVendor.isNullOrBlank() ||
            toolchainVendor.trim().lowercase() in (VENDOR_NAMES[sdkmanVendor] ?: setOf(sdkmanVendor))

    fun update(text: String, jdks: Map<Int, Jdk>, addMissing: Boolean = false): Result {
        val changes = mutableListOf<String>()
        val covered = mutableSetOf<Int>()
        val updated = TOOLCHAIN.replace(text) { match ->
            val block = match.value
            if (!TYPE.containsMatchIn(block)) return@replace block
            val major = VERSION.find(block)?.groupValues?.get(1)?.let { majorOf(it) } ?: return@replace block
            val jdk = jdks[major] ?: return@replace block
            val vendor = VENDOR.find(block)?.groupValues?.get(1)
            if (!vendorMatches(vendor, jdk.vendor)) return@replace block
            covered += major
            val home = JDK_HOME.find(block) ?: return@replace block
            if (home.groupValues[2] == jdk.home) return@replace block
            changes += "Java $major: ${home.groupValues[2]} -> ${jdk.home}"
            block.replaceRange(home.range, home.groupValues[1] + jdk.home + home.groupValues[3])
        }
        if (!addMissing) return Result(updated, changes)

        val missing = jdks.keys.filter { it !in covered }.sorted()
        val close = updated.lastIndexOf("</toolchains>")
        if (missing.isEmpty() || close < 0) return Result(updated, changes)
        val blocks = missing.joinToString("") { major ->
            val jdk = jdks.getValue(major)
            val vendor = VENDOR_NAMES[jdk.vendor]?.first() ?: jdk.vendor
            changes += "Java $major: added ${jdk.home}"
            """
            |  <toolchain>
            |    <type>jdk</type>
            |    <provides>
            |      <version>${if (major == 8) "1.8" else major.toString()}</version>
            |      <vendor>$vendor</vendor>
            |    </provides>
            |    <configuration>
            |      <jdkHome>${jdk.home}</jdkHome>
            |    </configuration>
            |  </toolchain>
            |""".trimMargin()
        }
        return Result(updated.substring(0, close) + blocks + updated.substring(close), changes)
    }

    /** An empty toolchains.xml, used with `addMissing` when the file does not exist yet. */
    fun empty(): String =
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"
        |            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        |            xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 http://maven.apache.org/xsd/toolchains-1.1.0.xsd">
        |</toolchains>
        |""".trimMargin()
}
