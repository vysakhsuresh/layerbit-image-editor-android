package com.layerbit.studio.ui.theme

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
import com.layerbit.studio.R
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.prefs.ThemeChoice

/**
 * Ink and paper, with one amber band.
 *
 * The family reads as a set: the cool blue-black base Sheaf, LayerLink and Deja share, and
 * one saturated colour per app spent on the primary action alone. Sheaf's is magenta; Studio's
 * is the amber of a darkroom safelight, which is also the band on its icon. Everything else is
 * ink, paper and the greys between, so the picture is the brightest thing in the window.
 *
 * Observable state rather than constants, as Sheaf does it: the palette is a holder that
 * [StudioTheme] sets, and every screen recomposes into the new colours.
 */
object StudioColors {
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
    background = Color(0xFF0B1220), surface = Color(0xFF141E33), surfaceDim = Color(0xFF0F182A),
    border = Color(0xFF223049), borderStrong = Color(0xFF34476A),
    text = Color(0xFFF3F6FB), muted = Color(0xFFA3B1C8), dim = Color(0xFF6B7A93),
    band = Color(0xFFF2A531), bandBright = Color(0xFFFFC15C), bandDim = Color(0xFF3A2A0C), onBand = Color(0xFF1A1200),
    canvas = Color(0xFF1A2130),
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

/** Space Grotesk, the family face. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

private val StudioTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.0).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun StudioTheme(theme: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val palette = if (dark) Dark else Light
    StudioColors.apply(palette)
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
    MaterialTheme(colorScheme = scheme, typography = StudioTypography) {
        CompositionLocalProvider(LocalTextStyle provides StudioTypography.bodyLarge, content = content)
    }
}

/** One quiet colour per family of tools, for the icon tiles and the dots beside headings. */
object GroupAccents {
    fun of(group: ToolId.Group): Color = if (StudioColors.isDark) dark(group) else light(group)

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
