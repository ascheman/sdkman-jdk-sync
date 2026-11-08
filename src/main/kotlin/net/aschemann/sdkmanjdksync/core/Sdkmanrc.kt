// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

/** Minimal `.sdkmanrc` handling: `candidate=version` lines, `#` comments, everything else untouched. */
object Sdkmanrc {
    private val JAVA_LINE = Regex("""^(\s*java\s*=\s*)(\S+)(.*)$""")

    fun java(text: String): String? =
        text.lineSequence().firstNotNullOfOrNull { JAVA_LINE.matchEntire(it)?.groupValues?.get(2) }

    fun withJava(text: String, identifier: String): String {
        var replaced = false
        val lines = text.split('\n').map { line ->
            val m = JAVA_LINE.matchEntire(line)
            if (m != null && !replaced) {
                replaced = true
                m.groupValues[1] + identifier + m.groupValues[3]
            } else {
                line
            }
        }
        return lines.joinToString("\n")
    }
}
