package com.kuroko.teleport.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Braun / Dieter Rams Light Industrial Neumorphism + Glassmorphism design system for Kuroko Teleport.
 * Uniform matte cool alabaster surfaces (#EFF3F8 / #ECF0F5), true dual-light soft sculpted shadows,
 * carved inset LCD wells (#E4ECE9 / #E5EAF2), tactile press-scale physics, sliding segmented pills,
 * breathing hardware LEDs, and crisp slate ink.
 */
object NeuColors {
    val Surface = Color(0xFFECF0F5)
    val SurfaceMatte = Color(0xFFEFF3F8)
    val SurfaceHighlight = Color(0xFFF6F9FC)
    val SurfaceGlass = Color(0xF0EFF3F8)
    val SurfaceGlassStrong = Color(0xF6F2F6FA)
    val InsetBg = Color(0xFFE5EAF2)
    val LcdWellBg = Color(0xFFE4ECE9)        // Braun cool-sage recessed LCD instrument tint

    val ShadowLight = Color(0xEBFFFFFF)      // top-left bright highlight
    val ShadowDark = Color(0xB8C2CBD8)       // bottom-right soft cool shadow
    val MapDropShadow = Color(0x4A1E293B)    // soft diffused drop shadow over map tiles

    val InsetDark = Color(0xCCBAC5D4)
    val InsetLight = Color(0xF5FFFFFF)
    val GlassRimTop = Color(0xE8FFFFFF)
    val GlassRimBottom = Color(0x99CFD8E6)

    val InkPrimary = Color(0xFF1E293B)
    val InkSecondary = Color(0xFF64748B)
    val InkMuted = Color(0xFF94A3B8)

    val Cobalt = Color(0xFF3B82F6)
    val CobaltDeep = Color(0xFF2563EB)
    val CobaltSoft = Color(0xFFDBEAFE)

    val Emerald = Color(0xFF10B981)
    val EmeraldDeep = Color(0xFF059669)
    val EmeraldSoft = Color(0xFFD1FAE5)
    val LedGreen = Color(0xFF22C55E)

    val Coral = Color(0xFFEF4444)
    val CoralDeep = Color(0xFFDC2626)
    val CoralSoft = Color(0xFFFEE2E2)

    val Amber = Color(0xFFF59E0B)
    val AmberDeep = Color(0xFFD97706)
    val AmberSoft = Color(0xFFFEF3C7)
}

val KurokoLightColors = lightColorScheme(
    primary = NeuColors.CobaltDeep,
    onPrimary = Color.White,
    primaryContainer = NeuColors.CobaltSoft,
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = NeuColors.Emerald,
    onSecondary = Color.White,
    secondaryContainer = NeuColors.EmeraldSoft,
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = NeuColors.AmberDeep,
    onTertiary = Color.White,
    tertiaryContainer = NeuColors.AmberSoft,
    onTertiaryContainer = Color(0xFF78350F),
    background = NeuColors.Surface,
    onBackground = NeuColors.InkPrimary,
    surface = NeuColors.Surface,
    onSurface = NeuColors.InkPrimary,
    surfaceVariant = NeuColors.InsetBg,
    onSurfaceVariant = NeuColors.InkSecondary,
    outline = Color(0xFFCBD5E1),
    error = NeuColors.Coral,
    onError = Color.White,
    errorContainer = NeuColors.CoralSoft,
    onErrorContainer = Color(0xFF991B1B)
)

/**
 * Dual-light sculpted neumorphic raised modifier (top-left bright white highlight +
 * bottom-right cool-gray shadow; weakened borders for soft extruded volume).
 */
fun Modifier.neuRaised(
    cornerRadius: Dp = 16.dp,
    surfaceColor: Color = NeuColors.Surface,
    elevation: Dp = 5.dp,
    lightShadowColor: Color = NeuColors.ShadowLight,
    darkShadowColor: Color = NeuColors.ShadowDark
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    val off = (elevation * 0.72f).toPx()
    val blur = (elevation * 1.65f).toPx().coerceAtLeast(1f)
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val darkPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = surfaceColor.toArgb()
            setShadowLayer(blur, off, off, darkShadowColor.toArgb())
        }
        nc.drawRoundRect(0f, 0f, size.width, size.height, r, r, darkPaint)

        val lightPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = surfaceColor.toArgb()
            setShadowLayer(blur, -off, -off, lightShadowColor.toArgb())
        }
        nc.drawRoundRect(0f, 0f, size.width, size.height, r, r, lightPaint)
    }
}

/**
 * Glassmorphic floating container for overlays directly over satellite/vector map tiles.
 */
fun Modifier.neuGlass(
    cornerRadius: Dp = 20.dp,
    surfaceColor: Color = NeuColors.SurfaceGlass,
    elevation: Dp = 10.dp
): Modifier = this
    .drawBehind {
        val r = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
        val blur = (elevation * 1.35f).toPx().coerceAtLeast(1f)
        val dy = (elevation * 0.42f).toPx()
        drawIntoCanvas { canvas ->
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = surfaceColor.toArgb()
                setShadowLayer(blur, 0f, dy, NeuColors.MapDropShadow.toArgb())
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }
    .clip(RoundedCornerShape(cornerRadius))
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(listOf(NeuColors.GlassRimTop, NeuColors.GlassRimBottom)),
        shape = RoundedCornerShape(cornerRadius)
    )

/**
 * Carved neumorphic inset well (inner top-left cool-gray shadow + bottom-right bright white rim).
 */
fun Modifier.neuInset(
    cornerRadius: Dp = 14.dp,
    surfaceColor: Color = NeuColors.InsetBg,
    depth: Dp = 3.5.dp
): Modifier = this.drawWithContent {
    val r = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    drawRoundRect(
        color = surfaceColor,
        cornerRadius = CornerRadius(r, r)
    )
    drawContent()

    val path = AndroidPath().apply {
        addRoundRect(0f, 0f, size.width, size.height, r, r, AndroidPath.Direction.CW)
    }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        nc.save()
        nc.clipPath(path)

        val d = depth.toPx()
        val strokeW = (d * 1.6f).coerceAtLeast(2f)
        val blurR = (d * 1.35f).coerceAtLeast(1f)

        val darkInner = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = strokeW
            color = NeuColors.InsetDark.toArgb()
            maskFilter = BlurMaskFilter(blurR, BlurMaskFilter.Blur.NORMAL)
        }
        nc.drawRoundRect(
            -strokeW * 0.35f + d * 0.65f,
            -strokeW * 0.35f + d * 0.65f,
            size.width + strokeW * 0.65f + d,
            size.height + strokeW * 0.65f + d,
            r, r, darkInner
        )

        val lightInner = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            strokeWidth = strokeW
            color = NeuColors.InsetLight.toArgb()
            maskFilter = BlurMaskFilter(blurR, BlurMaskFilter.Blur.NORMAL)
        }
        nc.drawRoundRect(
            -strokeW * 0.65f - d,
            -strokeW * 0.65f - d,
            size.width + strokeW * 0.35f - d * 0.65f,
            size.height + strokeW * 0.35f - d * 0.65f,
            r, r, lightInner
        )
        nc.restore()
    }
}

/**
 * Braun hardware status LED dot with optional smooth breathing pulse when active.
 */
@Composable
fun NeuLedDot(
    color: Color,
    lit: Boolean = true,
    isPulsing: Boolean = false,
    size: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val pulseAlpha = if (lit && isPulsing) {
        val transition = rememberInfiniteTransition(label = "led_breath")
        val a by transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.90f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "led_alpha"
        )
        a
    } else {
        0.65f
    }

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (lit) {
                    val glowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        this.color = color.copy(alpha = pulseAlpha).toArgb()
                        maskFilter = BlurMaskFilter(
                            size.toPx() * (if (isPulsing) 1.05f else 0.85f),
                            BlurMaskFilter.Blur.NORMAL
                        )
                    }
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawCircle(
                            this.size.width / 2f,
                            this.size.height / 2f,
                            this.size.minDimension * (if (isPulsing) 0.72f else 0.62f),
                            glowPaint
                        )
                    }
                }
            }
            .clip(CircleShape)
            .background(if (lit) color else NeuColors.InkMuted.copy(alpha = 0.55f))
    )
}

@Composable
fun NeuFloatingSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    surfaceColor: Color = NeuColors.SurfaceGlass,
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "flt_scale"
    )
    val mod = modifier
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        .neuGlass(cornerRadius = cornerRadius, surfaceColor = surfaceColor, elevation = elevation)
        .let {
            if (onClick != null) {
                it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            } else {
                it
            }
        }
    Box(modifier = mod) {
        CompositionLocalProvider(LocalContentColor provides NeuColors.InkPrimary) {
            content()
        }
    }
}

@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 4.5.dp,
    surfaceColor: Color = NeuColors.SurfaceMatte,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val mod = modifier
        .neuRaised(cornerRadius = cornerRadius, surfaceColor = surfaceColor, elevation = elevation)
        .clip(shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(modifier = mod) {
        CompositionLocalProvider(LocalContentColor provides NeuColors.InkPrimary) {
            content()
        }
    }
}

enum class NeuTone {
    NEUTRAL,
    COBALT,
    EMERALD,
    CORAL
}

/**
 * Tactile Neumorphic button with spring press-scale (0.96f) & shadow compression.
 */
@Composable
fun NeuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NeuTone = NeuTone.NEUTRAL,
    cornerRadius: Dp = 14.dp,
    elevation: Dp = 4.dp,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    if (!enabled) {
        Row(
            modifier = modifier
                .neuInset(cornerRadius = cornerRadius, surfaceColor = NeuColors.InsetBg, depth = 1.8.dp)
                .clip(shape)
                .border(0.8.dp, Color(0xFFD5DEE9), shape)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides NeuColors.InkMuted) {
                content()
            }
        }
        return
    }

    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "neu_btn_scale"
    )
    val effElevation by animateDpAsState(
        targetValue = if (isPressed) (elevation * 0.42f).coerceAtLeast(1.dp) else elevation,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "neu_btn_elev"
    )

    val (bgBrush, contentColor) = when (tone) {
        NeuTone.COBALT -> Brush.verticalGradient(listOf(Color(0xFF4F8FF7), NeuColors.CobaltDeep)) to Color.White
        NeuTone.EMERALD -> Brush.verticalGradient(listOf(Color(0xFF22C58B), NeuColors.EmeraldDeep)) to Color.White
        NeuTone.CORAL -> Brush.verticalGradient(listOf(Color(0xFFF35B5B), NeuColors.CoralDeep)) to Color.White
        NeuTone.NEUTRAL -> Brush.verticalGradient(listOf(NeuColors.SurfaceHighlight, NeuColors.Surface)) to NeuColors.InkPrimary
    }
    val baseFill = when (tone) {
        NeuTone.COBALT -> NeuColors.Cobalt
        NeuTone.EMERALD -> NeuColors.Emerald
        NeuTone.CORAL -> NeuColors.Coral
        NeuTone.NEUTRAL -> NeuColors.SurfaceMatte
    }
    val borderBrush = when (tone) {
        NeuTone.NEUTRAL -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.Transparent))
        else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent))
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .neuRaised(
                cornerRadius = cornerRadius,
                surfaceColor = baseFill,
                elevation = effElevation,
                darkShadowColor = if (tone == NeuTone.NEUTRAL) NeuColors.ShadowDark else baseFill.copy(alpha = 0.38f)
            )
            .clip(shape)
            .background(bgBrush)
            .border(0.8.dp, borderBrush, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Braun sculpted rounded-square icon button (14.dp corner radius) with tactile spring press-scale.
 */
@Composable
fun NeuSquareIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = 14.dp,
    floatingOverMap: Boolean = false,
    contentColor: Color = NeuColors.InkPrimary,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "sq_btn_scale"
    )
    val baseElev = if (floatingOverMap) 8.dp else 3.5.dp
    val effElev by animateDpAsState(
        targetValue = if (isPressed) (baseElev * 0.45f).coerceAtLeast(1.dp) else baseElev,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "sq_btn_elev"
    )

    val shape = RoundedCornerShape(cornerRadius)
    val mod = if (floatingOverMap) {
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .neuGlass(cornerRadius = cornerRadius, surfaceColor = NeuColors.SurfaceGlassStrong, elevation = effElev)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .neuRaised(cornerRadius = cornerRadius, surfaceColor = NeuColors.SurfaceMatte, elevation = effElev)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }
    Box(modifier = mod, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Tactile segmented control with a smooth spring-sliding raised white indicator pill.
 */
@Composable
fun NeuSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    fontSize: TextUnit = 13.sp
) {
    val safeIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    Box(
        modifier = modifier
            .height(height)
            .neuInset(cornerRadius = 14.dp, surfaceColor = NeuColors.InsetBg, depth = 3.5.dp)
            .padding(4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val count = options.size.coerceAtLeast(1)
            val segWidth = maxWidth / count
            val pillOffsetX by animateDpAsState(
                targetValue = segWidth * safeIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "seg_pill_slide"
            )

            // Smoothly sliding raised porcelain pill
            Box(
                modifier = Modifier
                    .offset(x = pillOffsetX)
                    .width(segWidth)
                    .fillMaxHeight()
                    .neuRaised(
                        cornerRadius = 10.dp,
                        surfaceColor = NeuColors.SurfaceHighlight,
                        elevation = 3.5.dp
                    )
                    .clip(RoundedCornerShape(10.dp))
            )

            // Option labels
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                options.forEachIndexed { idx, label ->
                    val selected = idx == safeIndex
                    val interaction = remember { MutableInteractionSource() }
                    val textColor by animateColorAsState(
                        targetValue = if (selected) NeuColors.CobaltDeep else NeuColors.InkSecondary,
                        animationSpec = tween(180),
                        label = "seg_label_color"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(idx) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = fontSize,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tactile Neumorphic chip with spring press-scale and optional long-press support.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeuChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    floatingOverMap: Boolean = false,
    accentColor: Color = NeuColors.CobaltDeep
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "chip_scale"
    )

    val corner = 12.dp
    val mod = when {
        selected -> modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neuRaised(
                cornerRadius = corner,
                surfaceColor = accentColor,
                elevation = 3.5.dp,
                darkShadowColor = accentColor.copy(alpha = 0.35f)
            )
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.9f), accentColor))
            )
        floatingOverMap -> modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neuGlass(cornerRadius = corner, surfaceColor = NeuColors.SurfaceGlass, elevation = 6.dp)
        else -> modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neuRaised(cornerRadius = corner, surfaceColor = NeuColors.SurfaceMatte, elevation = 3.dp)
            .clip(RoundedCornerShape(corner))
    }

    Box(
        modifier = mod
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else NeuColors.InkPrimary,
            maxLines = 1
        )
    }
}

/**
 * Tactile Braun-style `on` / `off` pill switch with spring-sliding thumb & smooth track tint.
 */
@Composable
fun NeuPillSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onText: String = "on",
    offText: String = "off",
    activeColor: Color = NeuColors.LedGreen
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 28.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "neu_switch_slide"
    )
    val trackTint by animateColorAsState(
        targetValue = if (checked) activeColor.copy(alpha = 0.14f) else NeuColors.InsetBg,
        animationSpec = tween(200),
        label = "neu_switch_track"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(62.dp)
            .height(30.dp)
            .neuInset(cornerRadius = 15.dp, surfaceColor = trackTint, depth = 3.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(3.dp)
    ) {
        Text(
            text = onText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (checked) activeColor else NeuColors.InkMuted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 7.dp)
        )
        Text(
            text = offText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (!checked) NeuColors.InkSecondary else NeuColors.InkMuted,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
        )
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(width = 28.dp, height = 24.dp)
                .neuRaised(cornerRadius = 12.dp, surfaceColor = NeuColors.SurfaceHighlight, elevation = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            NeuLedDot(color = activeColor, lit = checked, size = 6.dp)
        }
    }
}

/**
 * Braun tactile slider: recessed carved groove + luminous active bar + raised white cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = NeuColors.Cobalt
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = NeuColors.SurfaceHighlight,
            activeTrackColor = activeColor,
            inactiveTrackColor = NeuColors.InsetBg
        ),
        track = { sliderState ->
            val fraction = ((sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f))
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .neuInset(cornerRadius = 5.dp, surfaceColor = NeuColors.InsetBg, depth = 2.5.dp)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(activeColor.copy(alpha = 0.65f), activeColor)
                            )
                        )
                )
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .neuRaised(cornerRadius = 11.dp, surfaceColor = NeuColors.SurfaceHighlight, elevation = 3.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        }
    )
}
