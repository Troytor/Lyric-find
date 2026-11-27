package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppStage
import com.example.ui.components.LiquidGlassBackground
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.SearchOverlaySheet
import com.example.ui.components.SyncEngineBottomSheet
import com.example.ui.components.TranslationBottomSheet
import com.example.ui.screens.ListenScreen
import com.example.ui.screens.LyricsScreen
import com.example.ui.theme.CadenceTheme
import com.example.viewmodel.CadenceViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var cadenceViewModel: CadenceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        cadenceViewModel = ViewModelProvider(this)[CadenceViewModel::class.java]
        processIncomingShareIntent(intent)

        setContent {
            CadenceTheme {
                CadenceAppRoot(viewModel = cadenceViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::cadenceViewModel.isInitialized) {
            cadenceViewModel.refreshDeviceAudioState()
        }
    }

    private fun processIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action == Intent.ACTION_SEND &&
            incomingIntent.type?.startsWith("text/") == true
        ) {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                ?: incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!sharedText.isNullOrBlank()) {
                cadenceViewModel.handleSharedIntentText(sharedText)
                // Clear extra so configuration changes do not re-trigger the share intent
                incomingIntent.removeExtra(Intent.EXTRA_TEXT)
                incomingIntent.removeExtra(Intent.EXTRA_SUBJECT)
            }
        }
    }
}

@Composable
fun CadenceAppRoot(
    viewModel: CadenceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    var pendingActionAfterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            pendingActionAfterGrant?.invoke()
        }
        pendingActionAfterGrant = null
    }

    fun requestMicAndRun(action: () -> Unit) {
        if (hasMicPermission) {
            action()
        } else {
            pendingActionAfterGrant = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-clear transient error banners after 6s
    LaunchedEffect(uiState.errorBanner) {
        if (uiState.errorBanner != null) {
            delay(6_000L)
            viewModel.dismissError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed live liquid glass ambient background
        LiquidGlassBackground(
            palette = uiState.ambientPalette,
            beatIntensity = uiState.telemetry.beatIntensity,
            audioAmplitude = uiState.telemetry.amplitude,
        )

        // Primary stage transition between Listen and Synced Lyrics
        AnimatedContent(
            targetState = uiState.stage,
            transitionSpec = {
                (fadeIn(tween(340)) + scaleIn(initialScale = 0.96f, animationSpec = tween(340))) togetherWith
                    (fadeOut(tween(240)) + scaleOut(targetScale = 1.03f, animationSpec = tween(240)))
            },
            label = "stageTransition",
            modifier = Modifier.fillMaxSize(),
        ) { stage ->
            when (stage) {
                AppStage.LISTEN -> {
                    ListenScreen(
                        uiState = uiState,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = {
                            requestMicAndRun { viewModel.startSongIdentification() }
                        },
                        onOrbClick = {
                            requestMicAndRun { viewModel.toggleListeningForSong() }
                        },
                        onOpenSearch = { viewModel.openSearch() },
                        onSyncDeviceTrack = { viewModel.syncFromDeviceTrack() },
                    )
                }

                AppStage.LYRICS -> {
                    val activeTrack = uiState.activeTrack
                    if (activeTrack != null) {
                        LyricsScreen(
                            uiState = uiState,
                            track = activeTrack,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                requestMicAndRun { viewModel.startBeatSyncEngine() }
                            },
                            onBack = { viewModel.returnToListenStage() },
                            onOpenSearch = { viewModel.openSearch() },
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onToggleBeatSync = {
                                requestMicAndRun { viewModel.toggleBeatSync() }
                            },
                            onRelockLine = {
                                requestMicAndRun { viewModel.relockCurrentLineWithMic() }
                            },
                            onOpenTranslation = { viewModel.openTranslationSheet() },
                            onQuickNudgeOffset = { delta -> viewModel.adjustSyncOffset(delta) },
                            onOpenCalibration = { viewModel.openCalibrationSheet() },
                            onSeekToMs = { ms -> viewModel.seekTo(ms, resumeIfPaused = true) },
                            onArtworkBitmapLoaded = { bmp -> viewModel.onArtworkLoaded(bmp) },
                        )
                    } else {
                        ListenScreen(
                            uiState = uiState,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                requestMicAndRun { viewModel.startSongIdentification() }
                            },
                            onOrbClick = {
                                requestMicAndRun { viewModel.toggleListeningForSong() }
                            },
                            onOpenSearch = { viewModel.openSearch() },
                            onSyncDeviceTrack = { viewModel.syncFromDeviceTrack() },
                        )
                    }
                }
            }
        }

        // Live internet track search overlay
        AnimatedVisibility(
            visible = uiState.isSearchOpen,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 8 },
        ) {
            SearchOverlaySheet(
                query = uiState.searchQuery,
                results = uiState.searchResults,
                isSearching = uiState.isSearching,
                selectedLanguage = uiState.selectedSearchLang,
                palette = uiState.ambientPalette,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                onSelectLanguage = { lang -> viewModel.selectSearchLanguage(lang) },
                onSelectCandidate = { candidate -> viewModel.selectSearchCandidate(candidate) },
                onDismiss = { viewModel.closeSearch() },
            )
        }

        // Musixmatch-style lyric translation language picker sheet
        if (uiState.isTranslationSheetOpen) {
            TranslationBottomSheet(
                isTranslationEnabled = uiState.isTranslationEnabled,
                selectedLanguage = uiState.selectedTranslationLang,
                detectedSourceLang = uiState.detectedSourceLang,
                palette = uiState.ambientPalette,
                onSelectLanguage = { lang -> viewModel.selectTranslationLanguage(lang) },
                onDisableTranslation = { viewModel.disableTranslation() },
                onDismiss = { viewModel.closeTranslationSheet() },
            )
        }

        // Acoustic Sync Engine calibration & spectrum sheet
        val activeTrack = uiState.activeTrack
        if (uiState.isCalibrationOpen && activeTrack != null) {
            SyncEngineBottomSheet(
                track = activeTrack,
                telemetry = uiState.telemetry,
                syncOffsetMs = uiState.syncOffsetMs,
                isTrackSynced = uiState.isTrackSynced,
                isMicSyncEnabled = uiState.isMicSyncEnabled,
                engineNotice = uiState.recognitionEngineNote,
                customAudDToken = uiState.customAudDToken,
                palette = uiState.ambientPalette,
                onAdjustOffset = { delta -> viewModel.adjustSyncOffset(delta) },
                onResetOffset = { viewModel.resetSyncOffset() },
                onForceResync = {
                    requestMicAndRun { viewModel.relockCurrentLineWithMic(forceResync = true) }
                },
                onSaveAudDToken = { token -> viewModel.saveCustomAudDToken(token) },
                onDismiss = { viewModel.closeCalibrationSheet() },
            )
        }

        // Floating liquid glass error notice banner
        AnimatedVisibility(
            visible = uiState.errorBanner != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            val message = uiState.errorBanner.orEmpty()
            LiquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                tintColor = Color(0xFF28121E),
                fillAlpha = 0.72f,
                highlightAlpha = 0.32f,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { viewModel.dismissError() },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = Color(0xFFFDA4AF),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
