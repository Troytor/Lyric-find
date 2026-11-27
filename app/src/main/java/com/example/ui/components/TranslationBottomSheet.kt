package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.SupportedTranslationLanguages
import com.example.model.TranslationLanguage
import com.example.ui.theme.AmbientPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationBottomSheet(
    isTranslationEnabled: Boolean,
    selectedLanguage: TranslationLanguage,
    detectedSourceLang: String?,
    palette: AmbientPalette,
    onSelectLanguage: (TranslationLanguage) -> Unit,
    onDisableTranslation: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20B1222),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        scrimColor = Color.Black.copy(alpha = 0.54f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = palette.highlightTint,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.translate_sheet_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Text(
                        text = if (!detectedSourceLang.isNullOrBlank()) {
                            stringResource(R.string.translate_detected_label, detectedSourceLang.uppercase())
                        } else {
                            stringResource(R.string.translate_sheet_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }

            if (isTranslationEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                LiquidGlassPillButton(
                    onClick = onDisableTranslation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("disable_translation_button"),
                ) {
                    Text(
                        text = stringResource(R.string.translate_off_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
            ) {
                items(
                    items = SupportedTranslationLanguages.all,
                    key = { it.code },
                ) { lang ->
                    val isSelected = isTranslationEnabled && lang.code == selectedLanguage.code
                    LanguageGlassTile(
                        language = lang,
                        isSelected = isSelected,
                        accentColor = palette.highlightTint,
                        onClick = { onSelectLanguage(lang) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageGlassTile(
    language: TranslationLanguage,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    LiquidGlassSurface(
        shape = shape,
        tintColor = if (isSelected) accentColor else Color(0xFF152038),
        fillAlpha = if (isSelected) 0.38f else 0.32f,
        highlightAlpha = if (isSelected) 0.42f else 0.16f,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .testTag("translate_lang_${language.code}")
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = language.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accentColor.copy(alpha = 0.26f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
