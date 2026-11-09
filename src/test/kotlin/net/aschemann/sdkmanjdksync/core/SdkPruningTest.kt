// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `removes SDKs under SDKMAN and SDKs with a missing home`() {
        val jdk = sdkman.resolve("27.0.0.0+35-tem").createDirectories()
        assertTrue(remove("temurin-27", jdk.toString()))
        assertTrue(remove("gone", tmp.resolve("does-not-exist").toString()))
        assertTrue(remove("no home", null))
        assertTrue(remove("blank home", ""))
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
        assertFalse(remove("dead but wanted", tmp.resolve("gone").toString(), keep))
    }

    @Test
    fun `parses names that contain spaces`() {
        assertEquals(setOf("temurin-21 (7)", "27"), SdkPruning.parseNames(" temurin-21 (7) , 27,, "))
        assertEquals(none, SdkPruning.parseNames(null))
    }
}
