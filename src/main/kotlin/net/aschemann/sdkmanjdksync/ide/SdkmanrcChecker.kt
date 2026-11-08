// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import net.aschemann.sdkmanjdksync.core.JavaVersionId
import net.aschemann.sdkmanjdksync.core.Sdkmanrc
import net.aschemann.sdkmanjdksync.core.upgradeOptions

/** Warns about an outdated `java=` entry in the project's `.sdkmanrc` and offers to update it. */
object SdkmanrcChecker {

    suspend fun check(project: Project) {
        val file = readAction { project.guessProjectDir()?.findChild(".sdkmanrc") } ?: return
        val current = readAction { Sdkmanrc.java(VfsUtilCore.loadText(file)) }?.let { JavaVersionId.parse(it) } ?: return

        val sync = service<JdkSyncService>()
        val available = sync.catalog()
        if (available.isEmpty()) return
        val lts = if (SyncSettings.get().state.latestLineLtsOnly) sync.ltsMajors() else null
        val options = upgradeOptions(current, available, lts)
        if (!options.isOutdated) return

        val actions = listOfNotNull(
            options.latestOfLine?.let { "Use ${it.identifier} (latest ${it.major})" to { p: Project? -> apply(p ?: project, file, it) } },
            options.latestLine?.let { "Use ${it.identifier} (latest ${if (lts != null) "LTS" else "release"})" to { p: Project? -> apply(p ?: project, file, it) } },
        )
        Notifier.notify(
            project,
            "Outdated Java in .sdkmanrc",
            "java=${current.identifier} is outdated.",
            NotificationType.WARNING,
            *actions.toTypedArray(),
        )
    }

    private fun apply(project: Project, file: VirtualFile, id: JavaVersionId) {
        service<JdkSyncService>().install(project, id) {
            ApplicationManager.getApplication().invokeLater {
                WriteCommandAction.runWriteCommandAction(project, "Update .sdkmanrc", null, {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runWriteCommandAction
                    document.setText(Sdkmanrc.withJava(document.text, id.identifier))
                    FileDocumentManager.getInstance().saveDocument(document)
                })
            }
        }
    }
}
