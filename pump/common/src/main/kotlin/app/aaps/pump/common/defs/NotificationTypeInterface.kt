package app.aaps.pump.common.defs

import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel

interface NotificationTypeInterface {

    var notificationType: NotificationId

    val resourceId: Int

    val notificationUrgency: NotificationLevel

    val validMinutes: Int?

}