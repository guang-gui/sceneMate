package com.scenemate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 设计 token（来自 03-design/scenemate-ui.html）。 */
object SceneColors {
    val Accent = Color(0xFF4FE3C1)
    val AccentDim = Color(0x264FE3C1)
    val Ink = Color(0xFF0A0B0F)
    val InkSoft = Color(0xFF14161C)
    val Text = Color(0xFFF4F6F8)
    val TextDim = Color(0x99F4F6F8)
    val Warn = Color(0xFFFFC46B)
    /** 用户手动项一律中性白——这是整套设计最核心的视觉契约。 */
    val UserValue = Color(0xFFF4F6F8)
    val AiValue = Accent
}

private val scheme = darkColorScheme(
    primary = SceneColors.Accent,
    onPrimary = SceneColors.Ink,
    background = SceneColors.Ink,
    onBackground = SceneColors.Text,
    surface = SceneColors.InkSoft,
    onSurface = SceneColors.Text,
    secondary = SceneColors.Warn,
)

private val typography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp, color = SceneColors.Text),
    labelSmall = TextStyle(fontSize = 11.sp, color = SceneColors.TextDim, fontWeight = FontWeight.Medium),
)

@Composable
fun SceneMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
