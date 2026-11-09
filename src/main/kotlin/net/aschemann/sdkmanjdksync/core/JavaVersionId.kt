// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

/**
 * A SDKMAN Java identifier such as `25.0.4-tem`, `25.0.5.0+8-tem`, `8.0.402.fx-zulu`
 * or `27.0.1.0-fx+3.1-zulu`.
 *
 * A *line* is the combination of major version, vendor and variant (`fx`, `crac`, ...):
 * updating within a line never switches vendor or variant.
 */
data class JavaVersionId(
    val identifier: String,
    val numbers: List<Int>,
    val variant: String?,
    val build: List<Int>,
    val vendor: String,
    val earlyAccess: Boolean,
) : Comparable<JavaVersionId> {

    val major: Int get() = numbers.first()

    fun sameFlavor(other: JavaVersionId): Boolean = vendor == other.vendor && variant == other.variant

    fun sameLine(other: JavaVersionId): Boolean = major == other.major && sameFlavor(other)

    override fun compareTo(other: JavaVersionId): Int =
        compareLists(numbers, other.numbers).takeIf { it != 0 }
            ?: other.earlyAccess.compareTo(earlyAccess).takeIf { it != 0 }
            ?: compareLists(build, other.build)

    override fun toString(): String = identifier

    companion object {
        private val PATTERN = Regex(
            """^(\d+(?:\.\d+)*)(?:\.([a-z]+))?(?:-([a-z]+))?(?:\+([0-9A-Za-z.]+))?-([a-z]+)$""",
        )

        fun parse(identifier: String): JavaVersionId? {
            val m = PATTERN.matchEntire(identifier.trim()) ?: return null
            val (numbers, oldVariant, newVariant, build, vendor) = m.destructured
            val buildParts = build.split('.').filter { it.isNotEmpty() }
            return JavaVersionId(
                identifier = identifier.trim(),
                numbers = numbers.split('.').map { it.toInt() },
                variant = newVariant.ifEmpty { oldVariant }.ifEmpty { null },
                build = buildParts.mapNotNull { it.toIntOrNull() },
                vendor = vendor,
                earlyAccess = buildParts.any { it == "ea" } || newVariant == "ea",
            )
        }

        private fun compareLists(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/** Java major version from an IDE SDK version string like `java version "1.8.0_362"` or `Eclipse Temurin 25.0.4`. */
fun javaMajorFromVersionString(versionString: String?): Int? {
    val m = Regex("""(?<![\w.])(\d+)(?:\.(\d+))?""").find(versionString ?: return null) ?: return null
    val first = m.groupValues[1].toInt()
    return if (first == 1) m.groupValues[2].toIntOrNull() else first
}
