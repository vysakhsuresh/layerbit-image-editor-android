package com.layerbit.still.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.still.R
import com.layerbit.still.ops.ToolId
import com.layerbit.still.prefs.ThemeChoice

/**
 * Ink and paper, with one amber band.
 *
 * The family reads as a set: the cool blue-black base Sheaf, LayerLink and Deja share, and
 * one saturated colour per app spent on the primary action alone. Sheaf's is magenta; Still's
 * is the amber of a darkroom safelight, which is also the band on its icon. Everything else is
 * ink, paper and the greys between, so the picture is the brightest thing in the window.
 *
 * Observable state rather than constants, as Sheaf does it: the palette is a holder that
 * [StillTheme] sets, and every screen recomposes into the new colours.
 */
object StillColors {
    var Background by mutableStateOf(Dark.background); private set
    var Surface by mutableStateOf(Dark.surface); private set
    var SurfaceDim by mutableStateOf(Dark.surfaceDim); private set
    var Border by mutableStateOf(Dark.border); private set
    var BorderStrong by mutableStateOf(Dark.borderStrong); private set
    var Text by mutableStateOf(Dark.text); private set
    var Muted by mutableStateOf(Dark.muted); private set
    var Dim by mutableStateOf(Dark.dim); private set
    var Band by mutableStateOf(Dark.band); private set
    var BandBright by mutableStateOf(Dark.bandBright); private set
    var BandDim by mutableStateOf(Dark.bandDim); private set
    var OnBand by mutableStateOf(Dark.onBand); private set
    /** The canvas behind a picture: neutral, so colour judgements are honest. */
    var Canvas by mutableStateOf(Dark.canvas); private set
    var Running by mutableStateOf(Dark.running); private set
    var Done by mutableStateOf(Dark.done); private set
    var Failed by mutableStateOf(Dark.failed); private set
    var Skipped by mutableStateOf(Dark.skipped); private set
    var isDark by mutableStateOf(true); private set

    /** A card's fill: the surface with the page showing through, so the colour behind it is felt. */
    val Glass: Color get() = Surface.copy(alpha = if (isDark) 0.74f else 0.82f)
    /** The light catching a card's top edge. */
    val Edge: Color get() = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White

    internal fun apply(p: Palette) {
        if (Background == p.background && isDark == p.dark) return
        Background = p.background; Surface = p.surface; SurfaceDim = p.surfaceDim; Border = p.border
        BorderStrong = p.borderStrong; Text = p.text; Muted = p.muted; Dim = p.dim; Band = p.band
        BandBright = p.bandBright; BandDim = p.bandDim; OnBand = p.onBand; Canvas = p.canvas
        Running = p.running; Done = p.done; Failed = p.failed; Skipped = p.skipped; isDark = p.dark
    }
}

internal data class Palette(
    val dark: Boolean, val background: Color, val surface: Color, val surfaceDim: Color, val border: Color,
    val borderStrong: Color, val text: Color, val muted: Color, val dim: Color, val band: Color, val bandBright: Color,
    val bandDim: Color, val onBand: Color, val canvas: Color, val running: Color, val done: Color, val failed: Color, val skipped: Color
)

internal val Dark = Palette(
    dark = true,
    background = Color(0xFF070A12), surface = Color(0xFF121828), surfaceDim = Color(0xFF0C1120),
    border = Color(0xFF1F2940), borderStrong = Color(0xFF35425F),
    text = Color(0xFFF6F7FB), muted = Color(0xFFA8B3C9), dim = Color(0xFF6F7B95),
    band = Color(0xFFFFAD33), bandBright = Color(0xFFFFD36B), bandDim = Color(0xFF3A2708), onBand = Color(0xFF1A1000),
    canvas = Color(0xFF10151F),
    running = Color(0xFF5BA9E8), done = Color(0xFF4CCB8F), failed = Color(0xFFE8705F), skipped = Color(0xFFF0A93D)
)

internal val Light = Palette(
    dark = false,
    background = Color(0xFFF5F4EF), surface = Color(0xFFFFFFFF), surfaceDim = Color(0xFFECEAE3),
    border = Color(0xFFE2DED4), borderStrong = Color(0xFFC4BDAE),
    text = Color(0xFF121A26), muted = Color(0xFF4C5869), dim = Color(0xFF78849B),
    band = Color(0xFFB8690A), bandBright = Color(0xFF8F4E06), bandDim = Color(0xFFFCEBD0), onBand = Color(0xFFFFFBF2),
    canvas = Color(0xFFE6E3DA),
    running = Color(0xFF1C6FB8), done = Color(0xFF17784E), failed = Color(0xFFA83C27), skipped = Color(0xFF8A5C0B)
)

/** Syne, the loud one: display sizes and titles only, so a screen has one voice that carries. */
val Syne = FontFamily(
    Font(R.font.syne_variable, FontWeight.SemiBold),
    Font(R.font.syne_variable, FontWeight.Bold),
    Font(R.font.syne_variable, FontWeight.ExtraBold)
)

/** Space Grotesk, the family face. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

private val StillTypography = Typography(
    displayLarge = TextStyle(fontFamily = Syne, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.6).sp, lineHeight = 54.sp),
    displayMedium = TextStyle(fontFamily = Syne, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = Syne, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Syne, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Syne, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/** The loud colours the tiles wear. Saturated on ink, deeper on paper, so each reads in both themes. */
object Neon {
    val Lime: Color get() = if (StillColors.isDark) Color(0xFFC8FF4D) else Color(0xFF5E9A00)
    val Cyan: Color get() = if (StillColors.isDark) Color(0xFF4DE1FF) else Color(0xFF0A86A8)
    val Magenta: Color get() = if (StillColors.isDark) Color(0xFFFF4DA6) else Color(0xFFC8186E)
    val Violet: Color get() = if (StillColors.isDark) Color(0xFF9B6BFF) else Color(0xFF6A3FD6)
    val Coral: Color get() = if (StillColors.isDark) Color(0xFFFF7A59) else Color(0xFFD1431F)
    val Sky: Color get() = if (StillColors.isDark) Color(0xFF5B9DFF) else Color(0xFF2360C8)
    val Mint: Color get() = if (StillColors.isDark) Color(0xFF3ED3C0) else Color(0xFF0E8C7D)
}

@Composable
fun StillTheme(theme: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val palette = if (dark) Dark else Light
    StillColors.apply(palette)
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.band, onPrimary = palette.onBand, background = palette.background, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text, surfaceVariant = palette.surfaceDim, onSurfaceVariant = palette.muted,
            outline = palette.border, error = palette.failed
        )
    } else {
        lightColorScheme(
            primary = palette.band, onPrimary = palette.onBand, background = palette.background, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text, surfaceVariant = palette.surfaceDim, onSurfaceVariant = palette.muted,
            outline = palette.border, error = palette.failed
        )
    }
    MaterialTheme(colorScheme = scheme, typography = StillTypography) {
        CompositionLocalProvider(LocalTextStyle provides StillTypography.bodyLarge, content = content)
    }
}

/** One quiet colour per family of tools, for the icon tiles and the dots beside headings. */
object GroupAccents {
    fun of(group: ToolId.Group): Color = if (StillColors.isDark) dark(group) else light(group)

    private fun dark(group: ToolId.Group): Color = when (group) {
        ToolId.Group.EDIT -> Color(0xFF5B9DFF)
        ToolId.Group.MARK -> Color(0xFFB08CFF)
        ToolId.Group.SIZE -> Color(0xFF3ED3C0)
        ToolId.Group.PRIVACY -> Color(0xFF5DD48F)
        ToolId.Group.BATCH -> Color(0xFFFF6BA8)
    }

    private fun light(group: ToolId.Group): Color = when (group) {
        ToolId.Group.EDIT -> Color(0xFF2360C8)
        ToolId.Group.MARK -> Color(0xFF6B45D6)
        ToolId.Group.SIZE -> Color(0xFF0E8C7D)
        ToolId.Group.PRIVACY -> Color(0xFF1E8A4D)
        ToolId.Group.BATCH -> Color(0xFFC81E6C)
    }
}
