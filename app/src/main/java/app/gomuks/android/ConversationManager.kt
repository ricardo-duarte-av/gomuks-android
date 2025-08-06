package app.gomuks.android

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.net.toUri

class ConversationManager(private val context: Context) {
    companion object {
        private const val LOGTAG = "Gomuks/ConversationManager"
        
        // Conversation channel IDs
        const val DIRECT_MESSAGE_CHANNEL_ID = "direct_message"
        const val GROUP_MESSAGE_CHANNEL_ID = "group_message"
        
        // Shortcut categories
        const val SHORTCUT_CATEGORY_DIRECT = "direct_message"
        const val SHORTCUT_CATEGORY_GROUP = "group_message"
    }

    private val shortcutManager: ShortcutManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        context.getSystemService(ShortcutManager::class.java)
    } else {
        null
    }
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createConversationChannels()
    }

    private fun createConversationChannels() {
        // Direct message channel - High importance for 1-on-1 chats
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                DIRECT_MESSAGE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(context.getString(R.string.direct_message_channel))
                .setDescription(context.getString(R.string.direct_message_channel_description))
                .setConversationId("direct_messages", DIRECT_MESSAGE_CHANNEL_ID)
                .setVibrationEnabled(true)
                .setLightsEnabled(true)
                .setLightColor(R.color.primary_color)
                .setShowBadge(true)
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                .build()
        )

        // Group message channel - Default importance for group chats
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                GROUP_MESSAGE_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
                .setName(context.getString(R.string.group_message_channel))
                .setDescription(context.getString(R.string.group_message_channel_description))
                .setConversationId("group_messages", GROUP_MESSAGE_CHANNEL_ID)
                .setVibrationEnabled(true)
                .setLightsEnabled(true)
                .setLightColor(R.color.primary_color)
                .setShowBadge(true)
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                .build()
        )
    }

    fun createOrUpdateConversationShortcut(
        roomId: String,
        roomName: String,
        roomType: RoomType,
        sender: PushUser? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || shortcutManager == null) {
            Log.d(LOGTAG, "Shortcuts not supported on this Android version")
            return
        }

        try {
            val shortcutId = "room_$roomId"
            val category = when (roomType) {
                RoomType.DIRECT_MESSAGE -> SHORTCUT_CATEGORY_DIRECT
                RoomType.GROUP_MESSAGE -> SHORTCUT_CATEGORY_GROUP
            }

            val shortcut = ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(roomName)
                .setLongLabel(roomName)
                .setIcon(Icon.createWithResource(context, R.drawable.matrix))
                .setIntent(Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = "matrix:roomid/${roomId.substring(1)}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                .setCategories(setOf(category))
                .setPerson(android.app.Person.Builder()
                    .setKey(roomId)
                    .setName(roomName)
                    .setUri("matrix:roomid/${roomId.substring(1)}")
                    .build())
                .build()

            // Update or add the shortcut
            val existingShortcuts = shortcutManager.dynamicShortcuts
            val shortcutExists = existingShortcuts.any { it.id == shortcutId }
            
            if (shortcutExists) {
                shortcutManager.updateShortcuts(listOf(shortcut))
                Log.d(LOGTAG, "Updated shortcut for room: $roomName")
            } else {
                // Check if we can add more shortcuts
                if (shortcutManager.dynamicShortcutCount < shortcutManager.maxShortcutCountPerActivity) {
                    shortcutManager.dynamicShortcuts = shortcutManager.dynamicShortcuts + shortcut
                    Log.d(LOGTAG, "Created shortcut for room: $roomName")
                } else {
                    Log.w(LOGTAG, "Cannot create shortcut: maximum shortcuts reached")
                }
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to create/update shortcut for room $roomName", e)
        }
    }

    fun removeConversationShortcut(roomId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || shortcutManager == null) {
            return
        }

        try {
            val shortcutId = "room_$roomId"
            shortcutManager.removeDynamicShortcuts(listOf(shortcutId))
            Log.d(LOGTAG, "Removed shortcut for room: $roomId")
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to remove shortcut for room $roomId", e)
        }
    }

    fun getConversationChannelId(roomType: RoomType): String {
        return when (roomType) {
            RoomType.DIRECT_MESSAGE -> DIRECT_MESSAGE_CHANNEL_ID
            RoomType.GROUP_MESSAGE -> GROUP_MESSAGE_CHANNEL_ID
        }
    }

    fun getConversationId(roomId: String, roomType: RoomType): String {
        return when (roomType) {
            RoomType.DIRECT_MESSAGE -> "dm_$roomId"
            RoomType.GROUP_MESSAGE -> "group_$roomId"
        }
    }
} 