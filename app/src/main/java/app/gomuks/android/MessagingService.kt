package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Log
import java.io.InputStream
import java.net.URL
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MessagingService : FirebaseMessagingService() {
    companion object {
        private const val LOGTAG = "Gomuks/MessagingService"
    }

    override fun onNewToken(token: String) {
        val sharedPref =
            getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(getString(R.string.push_token_key), token)
            apply()
        }
        Log.d(LOGTAG, "Got new push token: $token")
        CoroutineScope(Dispatchers.IO).launch {
            tokenFlow.emit(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pushEncKey = getExistingPushEncryptionKey(this)
        if (pushEncKey == null) {
            Log.e(LOGTAG, "No push encryption key found to handle $message")
            return
        }
        val decryptedPayload: String = try {
            Encryption.fromPlainKey(pushEncKey).decrypt(message.data.getValue("payload"))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt $message", e)
            return
        }
        val data = try {
            Json.decodeFromString<PushData>(decryptedPayload)
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to parse $decryptedPayload as JSON", e)
            return
        }
        Log.i(LOGTAG, "Decrypted payload: $data")
        if (!data.dismiss.isNullOrEmpty()) {
            with(NotificationManagerCompat.from(this)) {
                for (dismiss in data.dismiss) {
                    cancel(dismiss.roomID.hashCode())
                }
            }
        }
        data.messages?.forEach {
            showMessageNotification(it)
        }
        
        // Clean up shortcuts for dismissed rooms
        data.dismiss?.forEach { dismissData ->
            removeRoomShortcut(dismissData.roomID)
        }
    }

    private fun pushUserToPerson(data: PushUser): Person {
        val userAvatar = downloadAvatar(data.avatar)
        return Person.Builder()
            .setKey(data.id)
            .setName(data.name)
            .setUri("matrix:u/${data.id.substring(1)}")
            .setIcon(if (userAvatar != null) IconCompat.createWithBitmap(userAvatar) else null)
            .build()
    }

    private fun createOrUpdateRoomShortcut(data: PushMessage) {
        val roomId = data.roomID
        val roomName = data.roomName
        val isGroupRoom = data.roomName != data.sender.name
        
        // Create intent for the room
        val roomIntent = Intent(this, MainActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            setData("matrix:roomid/${roomId.substring(1)}".toUri())
        }
        
        // Download room avatar
        val roomAvatar = downloadAvatar(data.roomAvatar)
        val shortcutIcon = if (roomAvatar != null) {
            IconCompat.createWithBitmap(roomAvatar)
        } else {
            IconCompat.createWithResource(this, R.drawable.matrix)
        }
        
        // Create shortcut for the room
        val shortcut = ShortcutInfoCompat.Builder(this, roomId)
            .setShortLabel(roomName)
            .setLongLabel("$roomName - ${if (isGroupRoom) "Group Chat" else "Direct Message"}")
            .setIcon(shortcutIcon)
            .setIntent(roomIntent)
            .setCategories(setOf("android.shortcut.conversation"))
            .build()
        
        // Add or update the shortcut
        try {
            ShortcutManagerCompat.addDynamicShortcuts(this, listOf(shortcut))
            Log.d(LOGTAG, "Created/updated shortcut for room: $roomName")
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to create shortcut for room: $roomName", e)
        }
    }

    private fun removeRoomShortcut(roomId: String) {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(roomId))
            Log.d(LOGTAG, "Removed shortcut for room: $roomId")
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to remove shortcut for room: $roomId", e)
        }
    }

    private fun downloadAvatar(avatarUrl: String?): Bitmap? {
        if (avatarUrl.isNullOrEmpty()) return null
        
        return try {
            val inputStream: InputStream = URL(avatarUrl).openStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bitmap
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to download avatar from $avatarUrl", e)
            null
        }
    }

    private fun showMessageNotification(data: PushMessage) {
        // Create or update shortcut for this room
        createOrUpdateRoomShortcut(data)
        
        val sender = pushUserToPerson(data.sender)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifID = data.roomID.hashCode()
        val isGroupRoom = data.roomName != data.sender.name
        
        // Download room avatar for the conversation
        val roomAvatar = downloadAvatar(data.roomAvatar)
        val conversationIcon = if (roomAvatar != null) {
            IconCompat.createWithBitmap(roomAvatar)
        } else {
            null
        }
        
        val messagingStyle = (manager.activeNotifications.lastOrNull { it.id == notifID }?.let {
            MessagingStyle.extractMessagingStyleFromNotification(it.notification)
        } ?: MessagingStyle(pushUserToPerson(data.self)))
            .setConversationTitle(
                if (isGroupRoom) data.roomName else null
            )
            .addMessage(MessagingStyle.Message(data.text, data.timestamp, sender))
        
        // Choose channel based on room type and sound preference
        val channelID = when {
            isGroupRoom && data.sound -> GROUP_NOISY_NOTIFICATION_CHANNEL_ID
            isGroupRoom && !data.sound -> GROUP_SILENT_NOTIFICATION_CHANNEL_ID
            !isGroupRoom && data.sound -> DM_NOISY_NOTIFICATION_CHANNEL_ID
            !isGroupRoom && !data.sound -> DM_SILENT_NOTIFICATION_CHANNEL_ID
            else -> if (data.sound) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setData("matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}".toUri())
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, channelID)
            .setSmallIcon(R.drawable.matrix)
            .setStyle(messagingStyle)
            .setWhen(data.timestamp)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setShortcutId(data.roomID)  // Link to the room shortcut for per-room settings
            .setLargeIcon(roomAvatar)  // Use room avatar as large icon
        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MessagingService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(notifID.hashCode(), builder.build())
        }
    }
}
