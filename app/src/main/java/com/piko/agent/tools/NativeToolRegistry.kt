package com.piko.agent.tools

import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.piko.agent.accessibility.ExecutionResult
import com.piko.agent.core.AgentAction
import com.piko.agent.core.SensitiveApps
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Resolves only catalogued capabilities. A failed/ambiguous dispatch never retries via UI. */
class NativeToolRegistry(private val context: Context) {
    fun unavailableReason(spec: ToolSpec): String? = when {
        spec.permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED } -> "Grant ${spec.permissions.joinToString()} in Piko's Assistant screen."
        spec.id == "flashlight" && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) -> "No camera flash is available."
        spec.id == "media_control" && context.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context) -> "Enable Piko notification access in Assistant to control active media."
        spec.id == "media_volume" && context.getSystemService(AudioManager::class.java).isVolumeFixed -> "This device has fixed volume."
        else -> null
    }

    suspend fun execute(action: AgentAction): ExecutionResult {
        val resolution = CapabilityResolver.resolve(action.toolId, action.parameters, ::unavailableReason)
        if (resolution is CapabilityResolution.Unavailable) return ExecutionResult(false, resolution.reason)
        val selected = resolution as CapabilityResolution.Available
        val spec = selected.tool
        val started = System.currentTimeMillis()
        return try {
            withTimeout(spec.timeoutMs) {
                val a = action.parameters
                fun text(key: String) = a[key]?.jsonPrimitive?.content.orEmpty()
                fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                val intent = when (spec.id) {
                    "wifi_settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    "bluetooth_settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "display_settings" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "app_details" -> {
                        val pkg = text("package")
                        if (SensitiveApps.isBlocked(pkg)) return@withTimeout ExecutionResult(false, "Protected app settings are blocked.")
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    }
                    "website" -> view(text("url"))
                    "dialer" -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", text("number"), null))
                    "sms" -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", text("number"), null)).putExtra("sms_body", text("text"))
                    "email" -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", text("address"), null))
                        .putExtra(Intent.EXTRA_SUBJECT, text("subject")).putExtra(Intent.EXTRA_TEXT, text("text"))
                    "share_text" -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text("text")), "Share with")
                    "navigation" -> view("geo:0,0?q=${Uri.encode(text("destination"))}")
                    "youtube_search" -> view("https://www.youtube.com/results?search_query=${Uri.encode(text("query"))}")
                    "spotify_search" -> view("https://open.spotify.com/search/${Uri.encode(text("query"))}")
                    "calendar" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                        putExtra(CalendarContract.Events.TITLE, text("title"))
                        a["beginMs"]?.jsonPrimitive?.longOrNull?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                        a["endMs"]?.jsonPrimitive?.longOrNull?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                    }
                    "alarm" -> Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, text("hour").toInt())
                        .putExtra(AlarmClock.EXTRA_MINUTES, text("minute").toInt()).putExtra(AlarmClock.EXTRA_MESSAGE, text("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    "memory_suggest" -> {
                        val app = com.piko.agent.PikoApplication.from(context)
                        if (!app.settingsRepository.current().memorySuggestions) return@withTimeout ExecutionResult(false, "Memory suggestions are disabled.")
                        app.memoryRepository.save(text("key"), text("content"), approved = true, source = "User-approved model suggestion")
                        return@withTimeout ExecutionResult(true, "Approved preference saved locally in Assistant → Memory.", acceptedByAndroid = true, resultVerified = true)
                    }
                    "media_control" -> {
                        val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
                        val sessions = manager.getActiveSessions(ComponentName(context, com.piko.agent.assistant.PikoMediaListener::class.java))
                            .filter { text("package").isBlank() || it.packageName == text("package") }
                        if (sessions.size != 1) return@withTimeout ExecutionResult(false, "Specify one compatible active media package; found ${sessions.size} sessions.")
                        val session = sessions.single()
                        val desired = if (text("operation") == "play") android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED
                        if (desired == android.media.session.PlaybackState.STATE_PLAYING) session.transportControls.play() else session.transportControls.pause()
                        val verified = withTimeoutOrNull(2000) {
                            while (session.playbackState?.state != desired) delay(100)
                            true
                        } == true
                        return@withTimeout ExecutionResult(verified, if (verified) "Playback state verified." else "Media command accepted; playback state is unverified.", acceptedByAndroid = true, resultVerified = verified)
                    }
                    "media_volume" -> {
                        val audio = context.getSystemService(AudioManager::class.java)
                        val requested = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * text("percent").toInt() / 100f).roundToInt()
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
                        val verified = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == requested
                        return@withTimeout ExecutionResult(verified, if (verified) "Media volume verified at $requested volume units." else "Volume readback did not match.", acceptedByAndroid = true, resultVerified = verified)
                    }
                    "clipboard" -> {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Piko", text("text")))
                        val verified = clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text("text")
                        return@withTimeout ExecutionResult(verified, if (verified) "Clipboard copy verified." else "Clipboard requested; readback unavailable.", acceptedByAndroid = true, resultVerified = verified)
                    }
                    "flashlight" -> return@withTimeout torch(text("enabled").toBooleanStrict())
                    else -> return@withTimeout ExecutionResult(false, "Unsupported tool.")
                }
                try {
                    val expectedPackage = intent.resolveActivity(context.packageManager)?.packageName
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ExecutionResult(true, "${spec.description}: launch requested. Completion is unverified; inspect the destination app. Do not repeat a draft/event/alarm without checking it.", acceptedByAndroid = true, launchPackage = expectedPackage)
                } catch (_: ActivityNotFoundException) {
                    ExecutionResult(false, "No installed app handles ${spec.description}.")
                }
            }.copy(startedAtMs = started, finishedAtMs = System.currentTimeMillis())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ExecutionResult(false, "${spec.id} failed (${error.javaClass.simpleName}); no automatic fallback.", startedAtMs = started) }
    }

    private suspend fun torch(enabled: Boolean): ExecutionResult {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ExecutionResult(false, "No usable flashlight.")
        val observed = CompletableDeferred<Boolean>()
        val requested = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, state: Boolean) {
                if (requested.get() && cameraId == id && state == enabled) observed.complete(true)
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                if (requested.get() && cameraId == id) observed.complete(false)
            }
        }
        camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        return try {
            requested.set(true)
            camera.setTorchMode(id, enabled)
            val verified = withTimeoutOrNull(2500) { observed.await() } == true
            ExecutionResult(verified, if (verified) "Flashlight ${if (enabled) "on" else "off"}, verified by Android." else "Flashlight request accepted but state is unverified; do not assume success.", acceptedByAndroid = true, resultVerified = verified)
        } finally { camera.unregisterTorchCallback(callback) }
    }
}
