package com.pixr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Palette photo neutre calibrée : aucune teinte ne doit fausser la perception des couleurs d'une image. */
private val PhotoScheme = darkColorScheme(
    primary = Color(0xFFF4F4F6),
    onPrimary = Color(0xFF111114),
    secondary = Color(0xFFCACAD2),
    onSecondary = Color(0xFF16161A),
    tertiary = Color(0xFFE5A93C),
    onTertiary = Color(0xFF1F1600),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF0E0E10),
    onSurface = Color(0xFFF2F2F5),
    onSurfaceVariant = Color(0xFFA8A8B3),
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainerLow = Color(0xFF151518),
    surfaceContainer = Color(0xFF1C1C20),
    surfaceContainerHigh = Color(0xFF26262B),
    outlineVariant = Color(0xFF2E2E35),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFF3B1517),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun PixRTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhotoScheme, content = content)
}

/** Chiffres tabulaires pour toutes les mesures techniques (MP, Mo, px, zoom, %, progression). */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")
