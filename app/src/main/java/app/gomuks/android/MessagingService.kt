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
import java.net.HttpURLConnection
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
        Log.i(LOGTAG, "Raw decrypted payload text: $decryptedPayload")
        if (!data.dismiss.isNullOrEmpty()) {
            with(NotificationManagerCompat.from(this)) {
                for (dismiss in data.dismiss) {
                    cancel(dismiss.roomID.hashCode())
                }
            }
        }
        data.messages?.forEach {
            showMessageNotification(it, data.imageAuth)
        }
        
        // Clean up shortcuts for dismissed rooms
        data.dismiss?.forEach { dismissData ->
            removeRoomShortcut(dismissData.roomID)
        }
    }

    private fun pushUserToPerson(data: PushUser, imageAuth: String?): Person {
        val userAvatar = downloadAvatar(data.avatar, imageAuth)
        return Person.Builder()
            .setKey(data.id)
            .setName(data.name)
            .setUri("matrix:u/${data.id.substring(1)}")
            .setIcon(if (userAvatar != null) IconCompat.createWithBitmap(userAvatar) else null)
            .build()
    }

    private fun createOrUpdateRoomShortcut(data: PushMessage, imageAuth: String?) {
        val roomId = data.roomID
        val roomName = data.roomName
        val isGroupRoom = data.roomName != data.sender.name
        
        // Create intent for the room
        val roomIntent = Intent(this, MainActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            setData("matrix:roomid/${roomId.substring(1)}".toUri())
        }
        
        // Download room avatar with authentication
        val roomAvatar = downloadAvatar(data.roomAvatar, imageAuth)
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

    private fun downloadAvatar(avatarUrl: String?, imageAuth: String?): Bitmap? {
        Log.d(LOGTAG, "downloadAvatar called with:")
        Log.d(LOGTAG, "  avatarUrl: $avatarUrl")
        Log.d(LOGTAG, "  imageAuth: $imageAuth")
        
        if (avatarUrl.isNullOrEmpty()) {
            Log.w(LOGTAG, "Avatar URL is null or empty, returning null")
            return null
        }
        
        // Get server URL from shared preferences
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), null)
        
        if (serverURL.isNullOrEmpty()) {
            Log.e(LOGTAG, "Server URL not found in preferences")
            return null
        }
        
        Log.d(LOGTAG, "Server URL: $serverURL")
        
        // Construct the full URL by combining server URL with avatar path
        val baseUrl = if (serverURL.endsWith("/")) serverURL else "$serverURL/"
        val avatarPath = if (avatarUrl.startsWith("/")) avatarUrl.substring(1) else avatarUrl
        
        // Construct the full URL with authentication key
        val fullUrl = if (imageAuth != null && avatarPath.contains("?")) {
            "$baseUrl$avatarPath&image_auth=$imageAuth"
        } else if (imageAuth != null) {
            "$baseUrl$avatarPath?image_auth=$imageAuth"
        } else {
            "$baseUrl$avatarPath"
        }
        
        Log.d(LOGTAG, "Constructed full URL: $fullUrl")
        
        return try {
            Log.d(LOGTAG, "Creating URL object...")
            val url = URL(fullUrl)
            Log.d(LOGTAG, "Opening connection...")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            // Add required headers
            Log.d(LOGTAG, "Setting headers...")
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            connection.setRequestProperty("Sec-Fetch-Site", "cross-site")
            connection.setRequestProperty("Sec-Fetch-Dest", "image")
            
            Log.d(LOGTAG, "Connecting...")
            connection.connect()
            
            val responseCode = connection.responseCode
            Log.d(LOGTAG, "Response code: $responseCode")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(LOGTAG, "HTTP error: $responseCode - ${connection.responseMessage}")
                connection.disconnect()
                return null
            }
            
            val contentLength = connection.contentLength
            Log.d(LOGTAG, "Content length: $contentLength")
            
            val contentType = connection.contentType
            Log.d(LOGTAG, "Content type: $contentType")
            
            Log.d(LOGTAG, "Reading input stream...")
            val inputStream: InputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()
            
            if (bitmap != null) {
                Log.d(LOGTAG, "Successfully downloaded avatar: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e(LOGTAG, "BitmapFactory.decodeStream returned null")
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(LOGTAG, "Exception during avatar download from $fullUrl", e)
            Log.e(LOGTAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(LOGTAG, "Exception message: ${e.message}")
            null
        }
    }

    private fun showMessageNotification(data: PushMessage, imageAuth: String?) {
        // Create or update shortcut for this room
        createOrUpdateRoomShortcut(data, imageAuth)
        
        val sender = pushUserToPerson(data.sender, imageAuth)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifID = data.roomID.hashCode()
        val isGroupRoom = data.roomName != data.sender.name
        
        // Download room avatar for the conversation
        val roomAvatar = downloadAvatar(data.roomAvatar, imageAuth)
        val conversationIcon = if (roomAvatar != null) {
            IconCompat.createWithBitmap(roomAvatar)
        } else {
            null
        }
        
        val messagingStyle = (manager.activeNotifications.lastOrNull { it.id == notifID }?.let {
            MessagingStyle.extractMessagingStyleFromNotification(it.notification)
        } ?: MessagingStyle(pushUserToPerson(data.self, imageAuth)))
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
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)  // Mark as message category
            .setGroup(data.roomID)  // Group notifications by room
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
