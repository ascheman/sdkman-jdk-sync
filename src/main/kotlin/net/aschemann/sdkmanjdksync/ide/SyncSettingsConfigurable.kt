// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class SyncSettingsConfigurable : BoundConfigurable("JDK Sync for SDKMAN") {

    private val options get() = SyncSettings.get().state

    override fun createPanel(): DialogPanel = panel {
        group("SDKMAN") {
            row("SDKMAN directory:") {
                textField().bindText({ options.sdkmanDir.orEmpty() }, { options.sdkmanDir = it })
                    .align(AlignX.FILL)
                    .comment("Empty: \$SDKMAN_DIR or ~/.sdkman")
            }
            row("Managed versions:") {
                textField().bindText({ options.majors.orEmpty() }, { options.majors = it })
                    .comment("Comma-separated majors, e.g. 17, 21, 25. Empty: all LTS versions (from foojay)")
            }
            row("Vendor preference:") {
                textField().bindText({ options.vendorPreference.orEmpty() }, { options.vendorPreference = it })
                    .comment("SDKMAN vendor suffixes in order of preference, e.g. tem, zulu, amzn")
            }
        }
        group("IDE SDK List") {
            row {
                checkBox("Keep one Java SDK per managed version (named by major)")
                    .bindSelected({ options.manageSdkTable }, { options.manageSdkTable = it })
            }
            row {
                checkBox("Remove all other Java SDKs (open projects are switched to the matching version first)")
                    .bindSelected({ options.pruneUnmanaged }, { options.pruneUnmanaged = it })
            }
            row("Default for new projects:") {
                textField().bindText({ options.defaultMajor.orEmpty() }, { options.defaultMajor = it })
                    .comment("Major version. Empty: newest managed version")
            }
        }
        group("Stable Paths Outside the IDE") {
            row {
                checkBox("Maintain one symlink per managed version")
                    .bindSelected({ options.maintainLinks }, { options.maintainLinks = it })
                    .comment("Like SDKMAN's <code>current</code>, but per major: <code>~/.jdks-lts/21</code> always points to the newest Java 21")
            }
            row("Symlink directory:") {
                textField().bindText({ options.linksDir.orEmpty() }, { options.linksDir = it })
                    .comment("Empty: ~/.jdks-lts")
            }
            row {
                checkBox("Update Maven toolchains.xml")
                    .bindSelected({ options.updateMavenToolchains }, { options.updateMavenToolchains = it })
                    .comment("Only <code>jdkHome</code> of JDK toolchains with a plain version (1.8, 17, ...) and matching vendor changes")
            }
            indent {
                row("toolchains.xml:") {
                    textField().bindText({ options.toolchainsFile.orEmpty() }, { options.toolchainsFile = it })
                        .comment("Empty: ~/.m2/toolchains.xml")
                }
                row {
                    checkBox("Point toolchains to the symlinks instead of versioned SDKMAN paths")
                        .bindSelected({ options.toolchainsUseLinks }, { options.toolchainsUseLinks = it })
                }
                row {
                    checkBox("Add toolchains for managed versions that have none")
                        .bindSelected({ options.toolchainsAddMissing }, { options.toolchainsAddMissing = it })
                }
            }
        }
        group("Updates") {
            row {
                checkBox("Notify about newer releases of the managed versions")
                    .bindSelected({ options.checkUpdates }, { options.checkUpdates = it })
            }
            row {
                checkBox("Check .sdkmanrc when a project is opened")
                    .bindSelected({ options.checkSdkmanrc }, { options.checkSdkmanrc = it })
            }
            row {
                checkBox("Offer only LTS versions as \"latest line\"")
                    .bindSelected({ options.latestLineLtsOnly }, { options.latestLineLtsOnly = it })
            }
        }
    }

    override fun apply() {
        super.apply()
        service<JdkSyncService>().requestSync()
    }
}
