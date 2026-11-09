// SPDX-License-Identifier: Apache-2.0
package net.aschemann.sdkmanjdksync.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {
    const val GROUP = "Sync SDKMAN"

    fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        vararg actions: Pair<String, (Project?) -> Unit>,
    ) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content, type)
        for ((text, action) in actions) {
            notification.addAction(NotificationAction.createSimpleExpiring(text) { action(project) })
        }
        notification.notify(project)
    }
}
