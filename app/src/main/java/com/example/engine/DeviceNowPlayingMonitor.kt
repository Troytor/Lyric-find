package com.example.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.model.DevicePlayingTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zero-permission on-device music playback monitor listening to standard Android
 * music player broadcasts (Spotify Device Broadcast Status, Samsung Music, Apple Music,
 * Poweramp, Amazon Music, AOSP/Last.fm players) plus AudioManager playback state.
 */
class DeviceNowPlayingMonitor(private val appContext: Context) {

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _currentDeviceTrack = MutableStateFlow<DevicePlayingTrack?>(null)
    val currentDeviceTrack: StateFlow<DevicePlayingTrack?> = _currentDeviceTrack.asStateFlow()

    private val _isDeviceMusicActive = MutableStateFlow(false)
    val isDeviceMusicActive: StateFlow<Boolean> = _isDeviceMusicActive.asStateFlow()

    private var registered = false

    private val musicActions = listOf(
        // Spotify (requires "Device Broadcast Status" enabled in Spotify settings)
        "com.spotify.music.metadatachanged",
        "com.spotify.music.playbackstatechanged",
        // Standard Android / AOSP / Lineage / YouTube / Generic scrobble broadcasts
        "com.android.music.metachanged",
        "com.android.music.playstatechanged",
        // Samsung Music
        "com.sec.android.app.music.metachanged",
        "com.sec.android.app.music.playstatechanged",
        "com.samsung.sec.android.MusicPlayer.metachanged",
        "com.samsung.sec.android.MusicPlayer.playstatechanged",
        // Apple Music Android
        "com.apple.android.music.metachanged",
        "com.apple.android.music.playstatechanged",
        // Poweramp
        "com.maxmpz.audioplayer.TRACK_CHANGED",
        "com.maxmpz.audioplayer.STATUS_CHANGED",
        // Amazon Music & popular local players
        "com.amazon.mp3.metachanged",
        "com.amazon.mp3.playstatechanged",
        "fm.last.android.metachanged",
        "com.miui.player.metachanged",
        "com.htc.music.metachanged",
        "com.andrew.apollo.metachanged",
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            handleBroadcast(intent)
        }
    }

    fun start() {
        refreshAudioActiveState()
        if (registered) return
        val filter = IntentFilter().apply {
            musicActions.forEach { addAction(it) }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    fun refreshAudioActiveState() {
        _isDeviceMusicActive.value = audioManager?.isMusicActive == true
    }

    private fun handleBroadcast(intent: Intent) {
        val action = intent.action.orEmpty()
        val extras = intent.extras ?: return
        val nowMs = SystemClock.elapsedRealtime()

        // Poweramp sends nested "track" bundle
        val trackBundle = extras.getBundle("track")

        val rawTitle = firstNonBlank(
            extras.getString("track"),
            extras.getString("title"),
            trackBundle?.getString("title"),
        )
        val rawArtist = firstNonBlank(
            extras.getString("artist"),
            trackBundle?.getString("artist"),
        )
        val rawAlbum = firstNonBlank(
            extras.getString("album"),
            trackBundle?.getString("album"),
        )

        val playing = extractPlayingFlag(extras, action)
        val positionMs = extractLongExtra(extras, "playbackPosition", "position", "pos")
        val durationMs = extractLongExtra(extras, "length", "duration")
            ?: trackBundle?.let { extractLongExtra(it, "durMs", "dur") }

        val existing = _currentDeviceTrack.value
        val resolvedTitle = rawTitle ?: existing?.title
        val resolvedArtist = rawArtist ?: existing?.artist

        if (!resolvedTitle.isNullOrBlank()) {
            val sourceLabel = resolveSourceLabel(action)
            val isSameTrack = existing != null &&
                existing.title.equals(resolvedTitle, ignoreCase = true) &&
                existing.artist.equals(resolvedArtist.orEmpty(), ignoreCase = true)

            val resolvedPosMs = when {
                positionMs != null && positionMs >= 0L -> positionMs
                isSameTrack && existing != null -> existing.estimatedCurrentPositionMs(nowMs)
                else -> 0L
            }

            _currentDeviceTrack.value = DevicePlayingTrack(
                title = resolvedTitle.trim(),
                artist = resolvedArtist.orEmpty().trim(),
                album = (rawAlbum ?: if (isSameTrack) existing?.album else null).orEmpty().trim(),
                isPlaying = playing ?: (audioManager?.isMusicActive == true),
                positionMs = resolvedPosMs,
                durationMs = (durationMs ?: if (isSameTrack) existing?.durationMs else null) ?: 0L,
                sourceApp = sourceLabel,
                receivedAtElapsedMs = nowMs,
            )
            _isDeviceMusicActive.value = playing ?: (audioManager?.isMusicActive == true)
        } else if (playing != null && existing != null) {
            _currentDeviceTrack.value = existing.copy(
                isPlaying = playing,
                positionMs = positionMs ?: existing.estimatedCurrentPositionMs(nowMs),
                receivedAtElapsedMs = nowMs,
            )
            _isDeviceMusicActive.value = playing
        }
    }

    private fun extractPlayingFlag(extras: Bundle, action: String): Boolean? {
        if (extras.containsKey("playing")) return extras.getBoolean("playing")
        if (extras.containsKey("isplaying")) return extras.getBoolean("isplaying")
        if (extras.containsKey("paused")) return !extras.getBoolean("paused")
        if (action.contains("metachanged", ignoreCase = true) || action.contains("TRACK_CHANGED")) {
            return audioManager?.isMusicActive == true
        }
        return null
    }

    private fun extractLongExtra(bundle: Bundle, vararg keys: String): Long? {
        for (key in keys) {
            if (!bundle.containsKey(key)) continue
            @Suppress("DEPRECATION")
            val raw = bundle.get(key)
            when (raw) {
                is Long -> return raw
                is Int -> return raw.toLong()
                is Double -> return raw.toLong()
                is Float -> return raw.toLong()
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun resolveSourceLabel(action: String): String = when {
        action.startsWith("com.spotify") -> "Spotify"
        action.startsWith("com.apple") -> "Apple Music"
        action.startsWith("com.sec.") || action.startsWith("com.samsung") -> "Samsung Music"
        action.startsWith("com.maxmpz") -> "Poweramp"
        action.startsWith("com.amazon") -> "Amazon Music"
        else -> "Phone Player"
    }
}
