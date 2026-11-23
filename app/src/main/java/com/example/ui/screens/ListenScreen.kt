package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MicMode
import com.example.ui.components.LiquidGlassPillButton
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.ListeningOrb
import com.example.viewmodel.CadenceUiState

@Composable
fun ListenScreen(
    uiState: CadenceUiState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOrbClick: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            // Minimal top wordmark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }

            // Centerpiece interactive listening orb and live status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListeningOrb(
                    micMode = uiState.micMode,
                    amplitude = uiState.telemetry.amplitude,
                    spectrum = uiState.telemetry.spectrum,
                    palette = uiState.ambientPalette,
                    onClick = {
                        if (hasMicPermission) onOrbClick() else onRequestMicPermission()
                    },
                )

                Spacer(modifier = Modifier.height(22.dp))

                AnimatedContent(
                    targetState = uiState.micMode to uiState.isLoadingTrack,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "listenHeadline",
                ) { (mode, loading) ->
                    val headline = when {
                        loading -> stringResource(R.string.listen_headline_loading)
                        mode == MicMode.MATCHING_ONLINE -> stringResource(R.string.listen_headline_matching)
                        mode == MicMode.IDENTIFYING -> stringResource(R.string.listen_headline_active)
                        else -> stringResource(R.string.listen_headline_idle)
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live heard vocal words or clean minimal subtitle
                if (uiState.liveTranscript.isNotBlank()) {
                    LiquidGlassSurface(
                        shape = RoundedCornerShape(20.dp),
                        fillAlpha = 0.36f,
                        highlightAlpha = 0.24f,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .widthIn(max = 420.dp),
                    ) {
                        Text(
                            text = "“${uiState.liveTranscript}”",
                            style = MaterialTheme.typography.bodyLarge,
                            color = uiState.ambientPalette.highlightTint,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    Text(
                        text = if (uiState.micMode == MicMode.IDENTIFYING) {
                            stringResource(R.string.listen_tap_to_stop)
                        } else {
                            stringResource(R.string.listen_subtitle_idle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.68f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                // Inline mic permission glass card only when permission is not yet granted
                AnimatedVisibility(
                    visible = !hasMicPermission,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    LiquidGlassSurface(
                        shape = RoundedCornerShape(22.dp),
                        fillAlpha = 0.44f,
                        highlightAlpha = 0.24f,
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MicOff,
                                contentDescription = null,
                                tint = uiState.ambientPalette.highlightTint,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.listen_mic_permission_needed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            LiquidGlassPillButton(
                                onClick = onRequestMicPermission,
                                isActive = true,
                                accentColor = uiState.ambientPalette.highlightTint,
                                modifier = Modifier.testTag("grant_mic_permission_button"),
                            ) {
                                Text(
                                    text = stringResource(R.string.listen_mic_permission_grant),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Minimal floating bottom liquid glass Search Pill
            LiquidGlassPillButton(
                onClick = onOpenSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("open_search_pill"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.listen_search_pill),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.84f),
                    )
                }
            }
        }
    }
}
