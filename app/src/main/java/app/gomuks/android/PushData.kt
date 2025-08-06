package app.gomuks.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushData(
    @SerialName("dismiss") val dismiss: List<PushDismiss>? = null,
    @SerialName("messages") val messages: List<PushMessage>? = null,
    @SerialName("image_auth") val imageAuth: String? = null,
    @SerialName("image_auth_expiry") val imageAuthExpiry: Long? = null,
)

@Serializable
data class PushDismiss(
    @SerialName("room_id") val roomID: String,
)

enum class RoomType {
    DIRECT_MESSAGE,
    GROUP_MESSAGE
}

@Serializable
data class PushMessage(
    val timestamp: Long,
    @SerialName("event_id") val eventID: String,
    @SerialName("event_rowid") val eventRowID: Long,

    @SerialName("room_id") val roomID: String,
    @SerialName("room_name") val roomName: String,
    @SerialName("room_avatar") val roomAvatar: String? = null,
    val sender: PushUser,
    val self: PushUser,

    val text: String,
    val image: String? = null,
    val mention: Boolean = false,
    val reply: Boolean = false,
    val sound: Boolean = false,
) {
    // Helper function to determine room type based on existing data
    // Logic: If roomName == sender.name, it's a direct message (1-on-1 chat)
    //        If roomName != sender.name, it's a group message (multiple participants)
    fun getRoomType(): RoomType {
        return if (roomName == sender.name) {
            RoomType.DIRECT_MESSAGE
        } else {
            RoomType.GROUP_MESSAGE
        }
    }
}

@Serializable
data class PushUser(
    val id: String,
    val name: String,
    val avatar: String? = null,
)
