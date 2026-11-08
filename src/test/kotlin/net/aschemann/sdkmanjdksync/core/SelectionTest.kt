// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SelectionTest {

    private fun ids(vararg s: String) = s.map { requireNotNull(JavaVersionId.parse(it)) }

    private val catalog = ids(
        "27.0.0.0+35-tem", "26.0.2.1+1-tem", "25.0.5.0+8-tem", "21.0.13.0+9-tem", "17.0.21.0+9-tem", "11.0.32.1+1-tem",
        "27.0.1.0+9-zulu", "25.0.5.0+8-zulu", "25.0.5.0-fx+3.1-zulu", "8.0.512.0+9-zulu", "8.0.512.0-fx+9-zulu",
        "28.0.0.0+ea.23-open",
    )
    private val lts = setOf(8, 11, 17, 21, 25)

    @Test
    fun `picks newest installed per LTS major, preferring vendors in order`() {
        val installed = ids(
            "25.0.4-tem", "25.0.5.0+8-tem", "25.0.5.0+8-zulu", "25.0.5.0-fx+3.1-zulu",
            "21.0.10-tem", "8.0.462-zulu", "8.0.402.fx-zulu", "27.0.0.0+35-tem", "21.0.2-graal",
        )
        val best = newestInstalledPerMajor(installed, lts, listOf("tem", "zulu", "amzn"))
        assertEquals(
            mapOf(8 to "8.0.462-zulu", 21 to "21.0.10-tem", 25 to "25.0.5.0+8-tem"),
            best.mapValues { it.value.identifier },
        )
    }

    @Test
    fun `vendor preference beats a newer version of a less preferred vendor`() {
        val installed = ids("21.0.10-tem", "21.0.13.0+10-amzn", "17.0.21.0+9-zulu", "17.0.18-amzn")
        val best = newestInstalledPerMajor(installed, lts, listOf("tem", "zulu", "amzn"))
        assertEquals(
            mapOf(17 to "17.0.21.0+9-zulu", 21 to "21.0.10-tem"),
            best.mapValues { it.value.identifier },
        )
    }

    @Test
    fun `offers latest of line and latest LTS line of the same vendor`() {
        val opts = upgradeOptions(ids("21.0.10-tem").single(), catalog, lts)
        assertEquals("21.0.13.0+9-tem", opts.latestOfLine?.identifier)
        assertEquals("25.0.5.0+8-tem", opts.latestLine?.identifier)
    }

    @Test
    fun `latest line may include non-LTS when no LTS filter is given`() {
        val opts = upgradeOptions(ids("21.0.10-tem").single(), catalog, null)
        assertEquals("27.0.0.0+35-tem", opts.latestLine?.identifier)
    }

    @Test
    fun `non-LTS lines get same-line and LTS options`() {
        val opts = upgradeOptions(ids("26.0.1-tem").single(), catalog, lts)
        assertEquals("26.0.2.1+1-tem", opts.latestOfLine?.identifier)
        assertNull(opts.latestLine, "25 is older than 26, so no newer LTS line")
    }

    @Test
    fun `keeps variant and never offers early access`() {
        val fx = upgradeOptions(ids("8.0.402.fx-zulu").single(), catalog, lts)
        assertEquals("8.0.512.0-fx+9-zulu", fx.latestOfLine?.identifier)
        assertEquals("25.0.5.0-fx+3.1-zulu", fx.latestLine?.identifier)
        val open = upgradeOptions(ids("27.0.0+35-open").single(), catalog, null)
        assertNull(open.latestOfLine)
        assertNull(open.latestLine)
    }

    @Test
    fun `up to date version is not outdated`() {
        assertFalse(upgradeOptions(ids("25.0.5.0+8-tem").single(), catalog, lts).isOutdated)
    }
}
