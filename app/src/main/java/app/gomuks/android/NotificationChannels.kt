package app.gomuks.android

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

internal const val SILENT_NOTIFICATION_CHANNEL_ID = "silent_notification"
internal const val NOISY_NOTIFICATION_CHANNEL_ID = "noisy_notification"
internal const val DM_SILENT_NOTIFICATION_CHANNEL_ID = "dm_silent_notification"
internal const val DM_NOISY_NOTIFICATION_CHANNEL_ID = "dm_noisy_notification"
internal const val GROUP_SILENT_NOTIFICATION_CHANNEL_ID = "group_silent_notification"
internal const val GROUP_NOISY_NOTIFICATION_CHANNEL_ID = "group_noisy_notification"
internal const val CONVERSATION_CHANNEL_ID = "conversation_channel"

fun createNotificationChannels(context: Context) {
    val notificationManager = NotificationManagerCompat.from(context)
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            SILENT_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_silent))
            .setDescription(context.getString(R.string.notification_channel_silent))
            .setSound(null, null)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            NOISY_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_noisy))
            .setDescription(context.getString(R.string.notification_channel_noisy))
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // DM Silent Channel
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            DM_SILENT_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_dm_silent))
            .setDescription(context.getString(R.string.notification_channel_dm_silent))
            .setSound(null, null)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // DM Noisy Channel
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            DM_NOISY_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_dm_noisy))
            .setDescription(context.getString(R.string.notification_channel_dm_noisy))
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // Group Silent Channel
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            GROUP_SILENT_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_group_silent))
            .setDescription(context.getString(R.string.notification_channel_group_silent))
            .setSound(null, null)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // Group Noisy Channel
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            GROUP_NOISY_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_group_noisy))
            .setDescription(context.getString(R.string.notification_channel_group_noisy))
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // Single conversation channel for all conversations
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            CONVERSATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_conversation))
            .setDescription(context.getString(R.string.notification_channel_conversation))
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )
}

/**
 * Creates a conversation channel for a specific room/conversation
 * This is required for per-conversation notification settings
 */
fun createConversationChannel(context: Context, roomId: String, roomName: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val notificationManager = NotificationManagerCompat.from(context)
        
        // Create a unique channel ID for this conversation
        val conversationChannelId = "${CONVERSATION_CHANNEL_ID}_$roomId"
        
        val channel = NotificationChannelCompat.Builder(
            conversationChannelId,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(roomName)
            .setDescription("Notifications for $roomName")
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
        
        // Set conversation ID for Android 11+ conversation features
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val androidChannel = channel.toAndroidChannel()
                androidChannel.setConversationId(CONVERSATION_CHANNEL_ID, roomId)
                notificationManager.createNotificationChannel(androidChannel)
            } catch (e: Exception) {
                // Fallback to regular channel creation
                notificationManager.createNotificationChannel(channel)
            }
        } else {
            notificationManager.createNotificationChannel(channel)
        }
    }
}