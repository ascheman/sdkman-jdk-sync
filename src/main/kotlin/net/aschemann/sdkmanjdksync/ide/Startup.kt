// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SdkmanJdkSyncStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<JdkSyncService>().ensureStarted()
        if (SyncSettings.get().state.checkSdkmanrc) SdkmanrcChecker.check(project)
    }
}

class SyncNowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        service<JdkSyncService>().apply {
            ensureStarted()
            requestSync()
        }
    }
}
