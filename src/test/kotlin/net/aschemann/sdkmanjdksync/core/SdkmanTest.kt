// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SdkmanTest {

    @Test
    fun `parses foojay LTS majors from pretty-printed JSON`() {
        val json = """
            {
              "result":[
                { "major_version":27, "term_of_support":"STS", "versions": [ "27.0.1", "27" ] }
            ,
                { "major_version":25,
                  "term_of_support":"LTS" },
                { "major_version":21, "term_of_support":"LTS" }
              ]
            }
        """.trimIndent()
        assertEquals(setOf(21, 25), Lts.parseFoojay(json))
    }

    @Test
    fun `LTS fallback formula`() {
        assertEquals(setOf(8, 11, 17, 21, 25, 29), Lts.fallback(30))
    }

    @Test
    fun `LTS lookup uses fresh cache, then fetch, then stale cache, then formula`(@TempDir tmp: Path) {
        val cache = tmp.resolve("lts.txt")
        val failing: (String) -> String = { error("offline") }

        assertEquals(Lts.fallback(), Lts.majors(failing, cache))

        assertEquals(setOf(21, 25), Lts.majors({ """{"major_version":25,"term_of_support":"LTS"},{"major_version":21,"term_of_support":"LTS"}""" }, cache))
        assertEquals("21\n25\n", cache.readText())

        assertEquals(setOf(21, 25), Lts.majors({ error("must not fetch while cache is fresh") }, cache))

        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minus(Duration.ofDays(2))))
        assertEquals(setOf(21, 25), Lts.majors(failing, cache))
    }

    @Test
    fun `lists installed JDKs and finds their homes`(@TempDir tmp: Path) {
        val java = tmp.resolve("candidates/java").createDirectories()
        java.resolve("25.0.4-tem").createDirectories().resolve("release").writeText("JAVA_VERSION=\"25.0.4\"")
        java.resolve("8.0.462-zulu/zulu-8.jdk/Contents/Home").createDirectories().resolve("release").writeText("JAVA_VERSION=\"1.8.0_462\"")
        Files.createSymbolicLink(java.resolve("current"), java.resolve("25.0.4-tem"))
        java.resolve("not-a-jdk").createDirectories()

        val sdkman = SdkmanInstallation(tmp)
        assertEquals(listOf("25.0.4-tem", "8.0.462-zulu"), sdkman.installedJava().map { it.identifier }.sorted())
        assertEquals(java.resolve("25.0.4-tem"), sdkman.javaHome(JavaVersionId.parse("25.0.4-tem")!!))
        assertEquals(java.resolve("8.0.462-zulu/zulu-8.jdk/Contents/Home"), sdkman.javaHome(JavaVersionId.parse("8.0.462-zulu")!!))
        assertNull(sdkman.javaHome(JavaVersionId.parse("21.0.1-tem")!!))
    }

    @Test
    fun `parses the SDKMAN catalog`() {
        val ids = SdkmanCatalog.parse("11.0.14.1-jbr,25.0.5.0+8-tem,garbage,8.0.512.0-fx+9-zulu\n")
        assertEquals(listOf("11.0.14.1-jbr", "25.0.5.0+8-tem", "8.0.512.0-fx+9-zulu"), ids.map { it.identifier })
        assertEquals("https://api.sdkman.io/2/candidates/java/darwinarm64/versions/all", SdkmanCatalog.url("darwinarm64"))
    }

    @Test
    fun `reads and rewrites the java line of an sdkmanrc`() {
        val rc = "# Enable auto-env through the sdkman_auto_env config\njava=21.0.10-tem\nmaven=3.9.11\n"
        assertEquals("21.0.10-tem", Sdkmanrc.java(rc))
        assertEquals(
            "# Enable auto-env through the sdkman_auto_env config\njava=25.0.5.0+8-tem\nmaven=3.9.11\n",
            Sdkmanrc.withJava(rc, "25.0.5.0+8-tem"),
        )
        assertEquals("java = 25-tem  # pinned", Sdkmanrc.withJava("java = 21-tem  # pinned", "25-tem"))
        assertNull(Sdkmanrc.java("maven=3.9.11"))
    }
}
