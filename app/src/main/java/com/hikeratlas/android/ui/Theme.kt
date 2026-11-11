package com.hikeratlas.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Greens from the basemap's parks, and its route magenta. */
private val colors = lightColorScheme(
    primary = Color(0xFF2F6B3F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBDC),
    onPrimaryContainer = Color(0xFF1F4D2C),
    secondary = Color(0xFF4F6B57),
    secondaryContainer = Color(0xFFD6EBDC),
    onSecondaryContainer = Color(0xFF1F4D2C),
    tertiary = Color(0xFFA21CAF),
    surface = Color.White,
    onSurface = Color(0xFF1F2A24),
    onSurfaceVariant = Color(0xFF4F5D55),
    surfaceContainerLowest = Color(0xFFF7FAF8),
    outlineVariant = Color(0xFFC9D2CC),
)

@Composable
fun HikerAtlasTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
