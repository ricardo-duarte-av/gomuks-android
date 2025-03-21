package app.gomuks.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.util.UUID

// Make sure these imports are in your file
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.mozilla.geckoview.WebExtension


class MainActivity : ComponentActivity() {
    companion object {
        private const val LOGTAG = "Gomuks/MainActivity"
        private const val BUNDLE_KEY = "gecko"
        // Add these notification constants
        private const val NOTIFICATION_ID = 1000
        private const val CHANNEL_ID_MESSAGE = "message_channel"
        private const val CHANNEL_ID_CALL = "call_channel"
        private var runtime: GeckoRuntime? = null


        private fun getRuntime(activity: MainActivity): GeckoRuntime {
            return runtime ?: run {
                val rt = GeckoRuntime.create(activity)
                rt.settings.enterpriseRootsEnabled = true
                rt.settings.consoleOutputEnabled = true
                rt.settings.doubleTapZoomingEnabled = false
                runtime = rt
                rt
            }
        }
    }
    private val navigation = NavigationDelegate(this)
    private val messageDelegate = MessageDelegate(this)
    internal val portDelegate = PortDelegate(this)
    private val promptDelegate = GeckoPrompts(this)

    private lateinit var view: GeckoView
    private lateinit var session: GeckoSession
    private var sessionState: GeckoSession.SessionState? = null

    internal lateinit var sharedPref: SharedPreferences
    private lateinit var prefEnc: Encryption
    internal lateinit var deviceID: UUID

    internal var port: WebExtension.Port? = null

    // Add this function to create notification channels
    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Message notifications channel
            val messageChannel = NotificationChannel(
                CHANNEL_ID_MESSAGE,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_messages_description)
                enableLights(true)
                enableVibration(true)
            }
            
            // Call notifications channel with higher importance
            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                context.getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_calls_description)
                enableLights(true)
                enableVibration(true)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    private lateinit var geckoSession: GeckoSession

    // Add showNotification method
    internal fun showNotification(title: String, content: String, roomId: String? = null, isCall: Boolean = false) {
        // Create an intent to open the app when notification is tapped
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            
            // If we have a room ID, create a matrix URI and add it to the intent
            if (roomId != null) {
                data = Uri.parse("matrix:r/$roomId")
            }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val channelId = if (isCall) CHANNEL_ID_CALL else CHANNEL_ID_MESSAGE
        
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        
        // Set a default notification icon if it exists, or use the app icon
        try {
            val notificationIconId = resources.getIdentifier("notification_icon", "drawable", packageName)
            if (notificationIconId != 0) {
                builder.setSmallIcon(notificationIconId)
            } else {
                builder.setSmallIcon(R.mipmap.ic_launcher)
            }
        } catch (e: Exception) {
            builder.setSmallIcon(R.mipmap.ic_launcher)
        }
        
        // For calls, add answer/decline actions if the icon resources exist
        if (isCall) {
            // Answer call intent
            val answerIntent = Intent(this, CallActionReceiver::class.java).apply {
                action = "ANSWER_CALL"
                putExtra("ROOM_ID", roomId)
            }
            val answerPendingIntent = PendingIntent.getBroadcast(
                this, 1, answerIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Decline call intent
            val declineIntent = Intent(this, CallActionReceiver::class.java).apply {
                action = "DECLINE_CALL"
                putExtra("ROOM_ID", roomId)
            }
            val declinePendingIntent = PendingIntent.getBroadcast(
                this, 2, declineIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            try {
                val answerIconId = resources.getIdentifier("ic_call_answer", "drawable", packageName)
                val declineIconId = resources.getIdentifier("ic_call_decline", "drawable", packageName)
                
                builder.addAction(
                    if (answerIconId != 0) answerIconId else android.R.drawable.ic_menu_call, 
                    getString(R.string.answer), 
                    answerPendingIntent
                )
                
                builder.addAction(
                    if (declineIconId != 0) declineIconId else android.R.drawable.ic_menu_close_clear_cancel, 
                    getString(R.string.decline), 
                    declinePendingIntent
                )
                
                builder.setFullScreenIntent(pendingIntent, true)
            } catch (e: Exception) {
                Log.e(LOGTAG, "Failed to add call actions", e)
            }
        }
        
        // Check notification permission and show notification
        try {
            NotificationManagerCompat.from(this).notify(
                roomId?.hashCode() ?: NOTIFICATION_ID, 
                builder.build()
            )
        } catch (e: SecurityException) {
            Log.w(LOGTAG, "Notification permission denied", e)
        }
    }

    private fun initSharedPref() {
        sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        prefEnc = Encryption(getString(R.string.pref_enc_key_name))
        sharedPref.getString(getString(R.string.device_id_key), null).let {
            if (it == null) {
                deviceID = UUID.randomUUID()
                with(sharedPref.edit()) {
                    putString(getString(R.string.device_id_key), deviceID.toString())
                    apply()
                }
                Log.d(LOGTAG, "Generated new device ID $deviceID")
            } else {
                Log.d(LOGTAG, "Parsing UUID $it")
                deviceID = UUID.fromString(it)
            }
        }
    }

    internal fun getPushEncryptionKey(): String {
        return Base64.encodeToString(getOrCreatePushEncryptionKey(this, prefEnc, sharedPref), Base64.NO_WRAP)
    }

    private fun setCredentials(serverURL: String, username: String, password: String) {
        with(sharedPref.edit()) {
            putString(getString(R.string.server_url_key), serverURL)
            putString(getString(R.string.username_key), username)
            putString(getString(R.string.password_key), prefEnc.encrypt(password))
            apply()
        }
    }

    internal fun getCredentials(): Triple<String, String, String>? {
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), null)
        val username = sharedPref.getString(getString(R.string.username_key), null)
        val encPassword = sharedPref.getString(getString(R.string.password_key), null)
        if (serverURL == null || username == null || encPassword == null) {
            return null
        }
        try {
            return Triple(serverURL, username, prefEnc.decrypt(encPassword))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt password", e)
            return null
        }
    }

    private fun hideSystemUI() {
        val decorView = window?.decorView ?: return // Ensure decorView is not null
    
        val controller = decorView.windowInsetsController
        controller?.let {
            it.hide(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

        // Add this function before onCreate:
    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Message notifications channel
            val messageChannel = NotificationChannel(
                CHANNEL_ID_MESSAGE,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_messages_description)
                enableLights(true)
                enableVibration(true)
            }
            
            // Call notifications channel with higher importance
            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                context.getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_calls_description)
                enableLights(true)
                enableVibration(true)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        hideSystemUI()
        
        // Call createNotificationChannels before using notifications
        createNotificationChannels(this)
        
        // Listen for UI visibility changes and reapply fullscreen
        // Force fullscreen mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
    
        window?.decorView?.windowInsetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        initSharedPref()
        createNotificationChannels(this)
        view = GeckoView(this)
        session = GeckoSession()
        val runtime = getRuntime(this)
        session.open(runtime)
        view.setSession(session)

              

        File(cacheDir, "upload").mkdirs()

        session.progressDelegate = object : ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                newState: GeckoSession.SessionState
            ) {
                super.onSessionStateChange(session, newState)
                Log.d(LOGTAG, "onSessionStateChange $newState")
                sessionState = newState
            }
        }
        session.promptDelegate = promptDelegate
        session.navigationDelegate = navigation

        val sessWebExtController = session.webExtensionController
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/bridge/", "android@gomuks.app")
            .accept(
                { extension ->
                    if (extension != null) {
                        Log.i(LOGTAG, "Extension installed: $extension")
                        sessWebExtController.setMessageDelegate(
                            extension,
                            messageDelegate,
                            "gomuksAndroid"
                        )
                    } else {
                        Log.e(LOGTAG, "Installed extension is null?")
                    }
                },
                { e -> Log.e(LOGTAG, "Error registering WebExtension", e) }
            )

        CoroutineScope(Dispatchers.Main).launch {
            tokenFlow.collect { pushToken ->
                Log.i(
                    LOGTAG,
                    "Received push token from messaging service: $pushToken"
                )
                portDelegate.registerPush(port ?: return@collect, pushToken)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.canGoBack) {
                    session.goBack()
                    return
                }
                finish()
            }
        })
        val parcel = savedInstanceState?.getParcelable(BUNDLE_KEY, GeckoSession.SessionState::class.java)
        if (parcel != null) {
            session.restoreState(parcel)
            setContentView(view)
        } else if (!loadWeb()) {
            setContent {
                ServerInput()
            }
        }
        Log.i(LOGTAG, "Initialization complete (loaded saved state: ${parcel != null})")
    }

    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    
    override fun onStart() {
        super.onStart()
        Log.i(LOGTAG, "onStart")
        session.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        Log.i(LOGTAG, "onPause")
    }

    override fun onResume() {
        super.onResume()
        Log.i(LOGTAG, "onResume")
    }

    override fun onStop() {
        super.onStop()
        Log.i(LOGTAG, "onStop")
        session.setActive(false)
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(LOGTAG, "onRestart")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOGTAG, "onDestroy")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Log.d(LOGTAG, "onSaveInstanceState $sessionState")
        outState.putParcelable(BUNDLE_KEY, sessionState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (this::session.isInitialized) {
            parseIntentURL(intent)?.let {
                session.loadUri(it)
            }
        }
    }

    fun getServerURL(): String? {
        return sharedPref.getString(getString(R.string.server_url_key), null)
    }

    fun openServerInputWithError(error: String) {
        val (serverURL, username, password) = getCredentials() ?: Triple("", "", "")
        setContent {
            ServerInput(serverURL, username, password, error)
        }
    }

    private fun parseIntentURL(overrideIntent: Intent? = null): String? {
        var serverURL = getServerURL() ?: return null
        val intent = overrideIntent ?: this.intent
        var targetURI = intent.data
        if (intent.action == Intent.ACTION_VIEW && targetURI != null) {
            if (targetURI.host == "matrix.to") {
                targetURI = matrixToURLToMatrixURI(targetURI)
                if (targetURI == null) {
                    Log.w(LOGTAG, "Failed to parse matrix.to URL ${intent.data}")
                } else {
                    Log.d(LOGTAG, "Parsed matrix.to URL ${intent.data} -> $targetURI")
                }
            }
            if (targetURI?.scheme == "matrix") {
                serverURL = Uri.parse(serverURL)
                    .buildUpon()
                    .encodedFragment("/uri/${Uri.encode(targetURI.toString())}")
                    .build()
                    .toString()
                Log.d(LOGTAG, "Converted view intent $targetURI -> $serverURL")
                return serverURL
            }
        }
        if (overrideIntent != null) {
            Log.w(
                LOGTAG,
                "No intent URL found ${overrideIntent.action} ${overrideIntent.data}"
            )
            return null
        }
        return serverURL
    }

    private fun loadWeb(): Boolean {
        session.loadUri(parseIntentURL() ?: return false)
        setContentView(view)
        return true
    }

    @Composable
    fun ServerInput(
        initialURL: String = "",
        initialUsername: String = "",
        initialPassword: String = "",
        error: String? = null
    ) {
        var serverURL by remember { mutableStateOf(initialURL) }
        var username by remember { mutableStateOf(initialUsername) }
        var password by remember { mutableStateOf(initialPassword) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextField(
                value = serverURL,
                onValueChange = { serverURL = it },
                label = { Text(getString(R.string.server_url)) }
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(getString(R.string.username)) }
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(getString(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Button(onClick = {
                setCredentials(serverURL, username, password)
                loadWeb()
            }) {
                Text(getString(R.string.connect))
            }
            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// Add this class to handle call actions
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra("ROOM_ID")
        
        // Create an intent to open the app
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            // Add the room ID and action to the intent
            data = Uri.parse("matrix:r/$roomId")
            
            // Pass the action (answer/decline) to the app
            putExtra("CALL_ACTION", intent.action)
        }
        
        context.startActivity(mainIntent)
        
        // Cancel the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(roomId?.hashCode() ?: 1000) // Use a simple constant instead of referencing the companion object
    }
}
