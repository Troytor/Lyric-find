package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.model.SearchLanguageOption
import com.example.model.SongCandidate
import com.example.model.SupportedSearchLanguages
import com.example.ui.theme.AmbientPalette

@Composable
fun SearchOverlaySheet(
    query: String,
    results: List<SongCandidate>,
    isSearching: Boolean,
    selectedLanguage: SearchLanguageOption,
    palette: AmbientPalette,
    onQueryChange: (String) -> Unit,
    onSelectLanguage: (SearchLanguageOption) -> Unit,
    onSelectCandidate: (SongCandidate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val focusRequester = remember { FocusRequester() }
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }

    val trimmedQuery = query.trim()
    val hasValidQuery = trimmedQuery.length >= 2 || trimmedQuery.any { it.code > 127 }
    val languageDropdownCd = stringResource(R.string.search_language_dropdown_cd)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xD9060B16))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            // Top liquid glass search bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LiquidGlassIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("search_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.lyrics_back_cd),
                        tint = Color.White,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                LiquidGlassSurface(
                    shape = RoundedCornerShape(percent = 50),
                    fillAlpha = 0.56f,
                    highlightAlpha = 0.30f,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.48f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                cursorBrush = SolidColor(palette.highlightTint),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .testTag("search_query_input"),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_clear_cd),
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onQueryChange("") }
                                    .padding(2.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Universal Language Filter Dropdown Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.search_language_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.62f),
                )

                Box(modifier = Modifier.weight(1f)) {
                    val selectedLabel = when {
                        selectedLanguage.isUniversal ->
                            "${selectedLanguage.displayName} (${selectedLanguage.nativeName})"
                        selectedLanguage.nativeName.equals(selectedLanguage.displayName, ignoreCase = true) ->
                            selectedLanguage.displayName
                        else ->
                            "${selectedLanguage.nativeName} · ${selectedLanguage.displayName}"
                    }

                    LiquidGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        tintColor = if (selectedLanguage.isUniversal) Color(0xFF0E172B) else palette.highlightTint,
                        fillAlpha = if (selectedLanguage.isUniversal) 0.44f else 0.18f,
                        highlightAlpha = if (selectedLanguage.isUniversal) 0.22f else 0.36f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { isLanguageMenuExpanded = true }
                            .semantics { contentDescription = "$languageDropdownCd: $selectedLabel" }
                            .testTag("search_language_dropdown_button"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                tint = if (selectedLanguage.isUniversal) {
                                    Color.White.copy(alpha = 0.80f)
                                } else {
                                    palette.highlightTint
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = selectedLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = if (isLanguageMenuExpanded) {
                                    Icons.Rounded.KeyboardArrowUp
                                } else {
                                    Icons.Rounded.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isLanguageMenuExpanded,
                        onDismissRequest = { isLanguageMenuExpanded = false },
                        containerColor = Color(0xF50E172B),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .widthIn(min = 240.dp, max = 320.dp)
                            .heightIn(max = 380.dp)
                            .testTag("search_language_dropdown_menu"),
                    ) {
                        SupportedSearchLanguages.all.forEach { option ->
                            val isSelected = option.code.equals(selectedLanguage.code, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (option.isUniversal) {
                                                    option.displayName
                                                } else {
                                                    option.nativeName
                                                },
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                ),
                                                color = if (isSelected) palette.highlightTint else Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val subtitle = when {
                                                option.isUniversal -> option.nativeName
                                                option.nativeName.equals(option.displayName, ignoreCase = true) -> null
                                                else -> option.displayName
                                            }
                                            if (subtitle != null) {
                                                Text(
                                                    text = subtitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White.copy(alpha = 0.56f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = palette.highlightTint,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    isLanguageMenuExpanded = false
                                    onSelectLanguage(option)
                                },
                                modifier = Modifier
                                    .background(
                                        if (isSelected) palette.highlightTint.copy(alpha = 0.14f) else Color.Transparent,
                                    )
                                    .testTag("search_language_item_${option.code}"),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            when {
                isSearching -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = palette.highlightTint,
                            trackColor = Color.White.copy(alpha = 0.14f),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                !hasValidQuery -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_empty_prompt),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.60f),
                        )
                    }
                }

                results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.testTag("search_results_list"),
                    ) {
                        items(
                            items = results,
                            key = { it.metadata.id },
                        ) { candidate ->
                            SearchResultRow(
                                candidate = candidate,
                                highlightTint = palette.highlightTint,
                                onClick = { onSelectCandidate(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    candidate: SongCandidate,
    highlightTint: Color,
    onClick: () -> Unit,
) {
    LiquidGlassSurface(
        shape = RoundedCornerShape(20.dp),
        fillAlpha = 0.36f,
        highlightAlpha = 0.18f,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("search_result_${candidate.metadata.id}"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                if (candidate.metadata.artworkUrl != null) {
                    AsyncImage(
                        model = candidate.metadata.artworkUrl,
                        contentDescription = stringResource(R.string.lyrics_album_art_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = highlightTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.metadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = candidate.metadata.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            val badgeText = if (candidate.hasSyncedLyrics) {
                stringResource(R.string.search_synced_badge)
            } else {
                stringResource(R.string.search_plain_badge)
            }
            val badgeBg = if (candidate.hasSyncedLyrics) {
                highlightTint.copy(alpha = 0.22f)
            } else {
                Color.White.copy(alpha = 0.10f)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (candidate.hasSyncedLyrics) highlightTint else Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
}
