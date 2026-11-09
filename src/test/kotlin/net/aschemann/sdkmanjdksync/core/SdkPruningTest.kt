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

class SdkPruningTest {

    @TempDir
    lateinit var tmp: Path

    private val sdkman: Path by lazy { tmp.resolve("sdkman/candidates/java").createDirectories() }
    private val none = emptySet<String>()

    private fun remove(name: String, home: String?, keep: Set<String> = none, includeOutside: Boolean = false) =
        SdkPruning.shouldRemove(name, home, keep, sdkman, includeOutside)

    @Test
    fun `removes SDKs under SDKMAN even if their home is gone`() {
        val jdk = sdkman.resolve("27.0.0.0+35-tem").createDirectories()
        assertTrue(remove("temurin-27", jdk.toString()))
        assertTrue(remove("gone", sdkman.resolve("21.0.1-tem").toString()))
        assertTrue(remove("no home", null))
        assertTrue(remove("blank home", ""))
    }

    @Test
    fun `recognizes a missing home under a symlinked SDKMAN directory`() {
        val link = tmp.resolve("link-to-candidates")
        Files.createSymbolicLink(link, sdkman)
        val gone = sdkman.toRealPath().resolve("21.0.1-tem").toString()
        assertTrue(SdkPruning.shouldRemove("gone", gone, none, link, false))
    }

    @Test
    fun `keeps SDKs with a missing home outside SDKMAN unless asked to remove them`() {
        val unplugged = tmp.resolve("Volumes/external/jdk-21").toString()
        assertFalse(remove("external", unplugged))
        assertTrue(remove("external", unplugged, includeOutside = true))
    }

    @Test
    fun `follows symlinks into SDKMAN`() {
        val jdk = sdkman.resolve("8.0.462-zulu").createDirectories()
        val link = tmp.resolve("links").createDirectories().resolve("8")
        Files.createSymbolicLink(link, jdk)
        assertTrue(remove("1.8 (71)", link.toString()))
    }

    @Test
    fun `keeps existing SDKs outside SDKMAN unless asked to remove them`() {
        val jbang = tmp.resolve("jbang/jdks/19").createDirectories()
        assertFalse(remove("19 (jbang)", jbang.toString()))
        assertTrue(remove("19 (jbang)", jbang.toString(), includeOutside = true))
    }

    @Test
    fun `keep list wins over everything`() {
        val jdk = sdkman.resolve("27.0.0.0+35-tem").createDirectories()
        val keep = setOf("my 27", "dead but wanted")
        assertFalse(remove("my 27", jdk.toString(), keep, includeOutside = true))
        assertFalse(remove("dead but wanted", sdkman.resolve("gone").toString(), keep))
    }

    @Test
    fun `moves projects automatically only within the same major`() {
        val managed = setOf(17, 21, 25)
        assertEquals(21, SdkPruning.automaticReplacement(21, managed))
        assertNull(SdkPruning.automaticReplacement(23, managed))
        assertNull(SdkPruning.automaticReplacement(28, managed))
        assertNull(SdkPruning.automaticReplacement(null, managed))
    }

    @Test
    fun `suggests the next higher managed major, otherwise the newest`() {
        val managed = setOf(17, 21, 25)
        assertEquals(25, SdkPruning.suggestedReplacement(23, managed))
        assertEquals(25, SdkPruning.suggestedReplacement(28, managed))
        assertEquals(25, SdkPruning.suggestedReplacement(null, managed))
        assertNull(SdkPruning.suggestedReplacement(23, emptySet()))
    }

    @Test
    fun `parses names that contain spaces`() {
        assertEquals(setOf("temurin-21 (7)", "27"), SdkPruning.parseNames(" temurin-21 (7) , 27,, "))
        assertEquals(none, SdkPruning.parseNames(null))
    }
}
