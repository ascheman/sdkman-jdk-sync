// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaVersionIdTest {

    private fun id(s: String) = requireNotNull(JavaVersionId.parse(s)) { "cannot parse $s" }

    @Test
    fun `parses classic and build-numbered identifiers`() {
        with(id("25.0.4-tem")) {
            assertEquals(listOf(25, 0, 4), numbers)
            assertEquals("tem", vendor)
            assertNull(variant)
            assertEquals(emptyList<Int>(), build)
        }
        with(id("25.0.5.0+8-tem")) {
            assertEquals(listOf(25, 0, 5, 0), numbers)
            assertEquals(listOf(8), build)
            assertEquals(25, major)
        }
        assertEquals(25, id("25-tem").major)
    }

    @Test
    fun `parses variants in old and new notation`() {
        assertEquals("fx", id("8.0.402.fx-zulu").variant)
        with(id("27.0.1.0-fx+3.1-zulu")) {
            assertEquals("fx", variant)
            assertEquals(listOf(3, 1), build)
            assertEquals("zulu", vendor)
        }
        assertEquals("crac", id("25.0.4.1-crac+1-zulu").variant)
        with(id("11.0.14.1-jbr")) {
            assertNull(variant)
            assertEquals("jbr", vendor)
        }
    }

    @Test
    fun `detects early access builds`() {
        assertTrue(id("28.0.0.0+ea.23-open").earlyAccess)
        assertFalse(id("27.0.0+35-open").earlyAccess)
    }

    @Test
    fun `rejects non-identifiers`() {
        assertNull(JavaVersionId.parse("current"))
        assertNull(JavaVersionId.parse(""))
        assertNull(JavaVersionId.parse("25.0.4"))
    }

    @Test
    fun `orders by version, then build, padding missing components`() {
        assertTrue(id("25.0.5.0+8-tem") > id("25.0.4-tem"))
        assertTrue(id("25.0.4.1+1-tem") > id("25.0.4-tem"))
        assertTrue(id("21.0.13.0+9-tem") > id("21.0.13.0+8-tem"))
        assertEquals(0, id("25.0.4-tem").compareTo(id("25.0.4.0-tem")))
        assertTrue(id("11.0.32.1+1-tem") > id("11.0.30-tem"))
    }

    @Test
    fun `extracts majors from IDE version strings`() {
        assertEquals(8, javaMajorFromVersionString("java version \"1.8.0_362\""))
        assertEquals(8, javaMajorFromVersionString("Azul Zulu 1.8.0_422 - aarch64"))
        assertEquals(25, javaMajorFromVersionString("Eclipse Temurin 25.0.4 - aarch64"))
        assertEquals(19, javaMajorFromVersionString("Eclipse Temurin version 19"))
        assertEquals(21, javaMajorFromVersionString("Amazon Corretto 21.0.6 - aarch64"))
        assertNull(javaMajorFromVersionString(null))
    }
}
