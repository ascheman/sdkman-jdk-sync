// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText

class LinksAndToolchainsTest {

    @Test
    fun `creates, updates and removes per-major links`(@TempDir tmp: Path) {
        val sdk = tmp.resolve("candidates/java")
        val old21 = sdk.resolve("21.0.10-tem").createDirectories()
        val new21 = sdk.resolve("21.0.13.0+9-tem").createDirectories()
        val jdk17 = sdk.resolve("17.0.21.0+9-tem").createDirectories()
        val links = tmp.resolve(".jdks-lts")

        assertEquals(2, StableLinks.sync(links, mapOf(17 to jdk17, 21 to old21)).size)
        assertEquals(old21, links.resolve("21").readSymbolicLink())

        assertEquals(listOf("${links.resolve("21")} -> $new21"), StableLinks.sync(links, mapOf(17 to jdk17, 21 to new21)))
        assertEquals(new21, links.resolve("21").readSymbolicLink())
        assertEquals(emptyList<String>(), StableLinks.sync(links, mapOf(17 to jdk17, 21 to new21)))

        StableLinks.sync(links, mapOf(21 to new21))
        assertFalse(links.resolve("17").exists() || links.resolve("17").isSymbolicLink())
    }

    @Test
    fun `never replaces a real directory or touches non-numeric entries`(@TempDir tmp: Path) {
        val links = tmp.resolve(".jdks-lts")
        links.resolve("21").createDirectories()
        links.resolve("README").also { Files.createDirectories(links) }.writeText("mine")
        val changes = StableLinks.sync(links, mapOf(21 to tmp.resolve("jdk").createDirectories()))
        assertTrue(changes.single().startsWith("skipped"))
        assertTrue(links.resolve("README").exists())
    }

    private val toolchains = """
        <?xml version="1.0" encoding="UTF-8"?>
        <toolchains>
          <!-- sdkman-managed JDKs -->
          <toolchain>
            <type>jdk</type>
            <provides>
              <version>1.8</version>
              <vendor>zulu</vendor>
            </provides>
            <configuration>
              <jdkHome>/u/.sdkman/candidates/java/8.0.462-zulu</jdkHome>
            </configuration>
          </toolchain>
          <toolchain>
            <type>jdk</type>
            <provides>
              <version>25</version>
              <vendor>temurin</vendor>
            </provides>
            <configuration>
              <jdkHome>/u/.sdkman/candidates/java/25.0.3-tem</jdkHome>
            </configuration>
          </toolchain>
          <toolchain>
            <type>jdk</type>
            <provides>
              <version>[17,)</version>
            </provides>
            <configuration>
              <jdkHome>/opt/jdk17</jdkHome>
            </configuration>
          </toolchain>
          <toolchain>
            <type>netbeans</type>
            <provides><version>21</version></provides>
            <configuration><installDir>/opt/nb</installDir></configuration>
          </toolchain>
        </toolchains>
    """.trimIndent()

    private val jdks = mapOf(
        8 to MavenToolchains.Jdk("/u/.jdks-lts/8", "zulu"),
        17 to MavenToolchains.Jdk("/u/.jdks-lts/17", "tem"),
        21 to MavenToolchains.Jdk("/u/.jdks-lts/21", "tem"),
        25 to MavenToolchains.Jdk("/u/.jdks-lts/25", "tem"),
    )

    @Test
    fun `updates only matching JDK toolchains and keeps everything else`() {
        val result = MavenToolchains.update(toolchains, jdks)
        assertEquals(
            listOf(
                "Java 8: /u/.sdkman/candidates/java/8.0.462-zulu -> /u/.jdks-lts/8",
                "Java 25: /u/.sdkman/candidates/java/25.0.3-tem -> /u/.jdks-lts/25",
            ),
            result.changes,
        )
        val expected = toolchains
            .replace("/u/.sdkman/candidates/java/8.0.462-zulu", "/u/.jdks-lts/8")
            .replace("/u/.sdkman/candidates/java/25.0.3-tem", "/u/.jdks-lts/25")
        assertEquals(expected, result.text)
        assertEquals(emptyList<String>(), MavenToolchains.update(result.text, jdks).changes)
    }

    @Test
    fun `skips toolchains of another vendor`() {
        val corretto25 = mapOf(25 to MavenToolchains.Jdk("/u/.jdks-lts/25", "amzn"))
        assertEquals(emptyList<String>(), MavenToolchains.update(toolchains, corretto25).changes)
    }

    @Test
    fun `optionally appends toolchains for missing majors`() {
        val result = MavenToolchains.update(toolchains, jdks, addMissing = true)
        assertEquals(listOf("Java 17: added /u/.jdks-lts/17", "Java 21: added /u/.jdks-lts/21"), result.changes.drop(2))
        assertTrue(result.text.contains("<version>21</version>\n      <vendor>temurin</vendor>"))
        assertTrue(result.text.trimEnd().endsWith("</toolchain>\n</toolchains>"))
        assertEquals(emptyList<String>(), MavenToolchains.update(result.text, jdks, addMissing = true).changes)
    }

    @Test
    fun `parses plain toolchain versions only`() {
        assertEquals(8, MavenToolchains.majorOf("1.8"))
        assertEquals(17, MavenToolchains.majorOf("17"))
        assertEquals(21, MavenToolchains.majorOf("21.0"))
        assertNull(MavenToolchains.majorOf("[17,)"))
        assertNull(MavenToolchains.majorOf("17+"))
    }
}
