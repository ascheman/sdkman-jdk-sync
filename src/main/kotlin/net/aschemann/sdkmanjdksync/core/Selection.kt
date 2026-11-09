// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.core

/**
 * Newest installed JDK for each requested major, taken from the most preferred vendor that has
 * one installed (a newer release of a less preferred vendor does not win). Only plain builds
 * (no `fx`, `crac`, EA) of the vendors in [vendorPreference] are considered.
 */
fun newestInstalledPerMajor(
    installed: Collection<JavaVersionId>,
    majors: Set<Int>,
    vendorPreference: List<String>,
): Map<Int, JavaVersionId> =
    installed
        .filter { it.major in majors && it.variant == null && !it.earlyAccess && it.vendor in vendorPreference }
        .groupBy { it.major }
        .mapValues { (_, ids) ->
            val vendor = vendorPreference.first { v -> ids.any { it.vendor == v } }
            ids.filter { it.vendor == vendor }.max()
        }
        .toSortedMap()

data class UpgradeOptions(
    val current: JavaVersionId,
    /**
     * Newest version of the same line (major, vendor, variant), if newer than [current]. An early
     * access [current] also gets newer early access builds until there is a release.
     */
    val latestOfLine: JavaVersionId?,
    /** Newest version of the newest (LTS) major of the same vendor/variant, if that major is newer. */
    val latestLine: JavaVersionId?,
) {
    val isOutdated: Boolean get() = latestOfLine != null || latestLine != null
}

/**
 * Upgrade options for [current] given the [available] catalog. If [ltsMajors] is non-null, the
 * "latest line" option only considers those majors.
 */
fun upgradeOptions(current: JavaVersionId, available: Collection<JavaVersionId>, ltsMajors: Set<Int>?): UpgradeOptions {
    val candidates = available.filter { it.sameFlavor(current) && !it.earlyAccess }
    val newestEarlyAccess = available
        .filter { current.earlyAccess && it.earlyAccess && it.sameLine(current) }
        .maxOrNull()
    val latestOfLine = (candidates.filter { it.major == current.major }.maxOrNull() ?: newestEarlyAccess)
        ?.takeIf { it > current }
    val newestMajor = candidates
        .map { it.major }
        .filter { ltsMajors == null || it in ltsMajors }
        .maxOrNull()
        ?.takeIf { it > current.major }
    val latestLine = newestMajor?.let { major -> candidates.filter { it.major == major }.maxOrNull() }
    return UpgradeOptions(current, latestOfLine, latestLine)
}
