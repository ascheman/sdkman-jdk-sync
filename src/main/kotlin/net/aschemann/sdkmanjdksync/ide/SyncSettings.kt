// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import net.aschemann.sdkmanjdksync.core.SdkPruning
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.APP)
@State(name = "SdkmanJdkSync", storages = [Storage("sdkman-jdk-sync.xml")])
class SyncSettings : SimplePersistentStateComponent<SyncSettings.Options>(Options()) {

    class Options : BaseState() {
        /** SDKMAN directory; blank means `$SDKMAN_DIR` or `~/.sdkman`. */
        var sdkmanDir by string("")

        /** Comma-separated majors to manage; blank means all LTS majors (foojay). */
        var majors by string("")

        var vendorPreference by string("tem,zulu,amzn")

        /** Keep the IDE's Java SDK list at exactly one entry per managed major. */
        var manageSdkTable by property(true)

        /** Remove unmanaged Java SDKs that are dead or live under SDKMAN (open projects are remapped first). */
        var pruneUnmanaged by property(true)

        /** Also remove existing Java SDKs outside SDKMAN (JBang, Gradle, manual installations). */
        var pruneOutsideSdkman by property(false)

        /** Comma-separated names of Java SDKs that are never removed. */
        var keepSdks by string("")

        /** Comma-separated majors whose managed SDK the user deleted; they are not re-created. */
        var excludedMajors by string("")

        /** Major for "Settings for New Projects"; blank means the newest managed major. */
        var defaultMajor by string("")

        /** Maintain stable per-major symlinks (`~/.jdks-lts/21` -> newest 21) for tools outside the IDE. */
        var maintainLinks by property(true)

        /** Directory for the symlinks; blank means `~/.jdks-lts`. */
        var linksDir by string("")

        /** Update `jdkHome` of matching JDK toolchains in Maven's toolchains.xml. */
        var updateMavenToolchains by property(false)

        /** toolchains.xml location; blank means `~/.m2/toolchains.xml`. */
        var toolchainsFile by string("")

        /** Point toolchains to the stable symlinks (if maintained) instead of the versioned SDKMAN path. */
        var toolchainsUseLinks by property(true)

        /** Also add toolchains for managed majors that have none yet. */
        var toolchainsAddMissing by property(false)

        /** Notify about newer patch releases of the managed lines. */
        var checkUpdates by property(true)

        /** Check `.sdkmanrc` on project open. */
        var checkSdkmanrc by property(true)

        /** Offer only LTS majors as "latest line" for `.sdkmanrc` updates. */
        var latestLineLtsOnly by property(true)

        /** Identifiers the user has already been notified about (to avoid repeated balloons). */
        var notifiedUpdates by string("")
    }

    val parsedMajors: Set<Int>?
        get() = state.majors.orEmpty().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.toSortedSet().ifEmpty { null }

    val vendors: List<String>
        get() = state.vendorPreference.orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    val keptSdks: Set<String> get() = SdkPruning.parseNames(state.keepSdks)

    val excludedMajors: Set<Int> get() = SdkPruning.parseNames(state.excludedMajors).mapNotNull { it.toIntOrNull() }.toSortedSet()

    fun keepSdks(names: Collection<String>) {
        state.keepSdks = (keptSdks + names).joinToString(", ")
    }

    fun setExcluded(major: Int, excluded: Boolean) {
        state.excludedMajors = (if (excluded) excludedMajors + major else excludedMajors - major).joinToString(", ")
    }

    val linksPath: Path
        get() = expandHome(state.linksDir).ifBlank { "~/.jdks-lts" }.let { Paths.get(expandHome(it)) }

    val toolchainsPath: Path
        get() = expandHome(state.toolchainsFile).ifBlank { "~/.m2/toolchains.xml" }.let { Paths.get(expandHome(it)) }

    companion object {
        fun get(): SyncSettings = service()

        private fun expandHome(path: String?): String =
            path.orEmpty().trim().let { if (it.startsWith("~")) System.getProperty("user.home") + it.substring(1) else it }
    }
}
