package com.nekosu.manager.ui.mochi

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.nekosu.manager.data.AppIcons
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiTheme

/** iOS 设置风格的分类色，Mochi 风格下会按主题主色重新推导成柔和的色块。 */
object KsuTileColors {
    val Gray = Color(0xFF8E8E93)
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF30B158)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)
    val Indigo = Color(0xFF5E5CE6)
    val Teal = Color(0xFF30B0C7)
}

class KsuTone(
    val container: Color,
    val content: Color,
)

/**
 * 色块配色：以主题主色为基准按分类微调色相，浅色主题用淡底 + 深色图形，深色主题反过来。
 * 红色分类跟随 error 色。
 */
private fun mochiTileTone(
    category: Color,
    primary: Color,
    error: Color,
    dark: Boolean,
): KsuTone {
    val base =
        FloatArray(3).also {
            ColorUtils.colorToHSL((if (category == KsuTileColors.Red) error else primary).toArgb(), it)
        }
    val (shift, sat) =
        when (category) {
            KsuTileColors.Blue -> 0f to 1f
            KsuTileColors.Indigo -> 26f to 0.9f
            KsuTileColors.Teal -> -26f to 0.9f
            KsuTileColors.Green -> -48f to 0.8f
            KsuTileColors.Orange -> 48f to 0.85f
            KsuTileColors.Red -> 0f to 1f
            else -> 0f to 0.18f
        }
    val hue = ((base[0] + shift) % 360f + 360f) % 360f
    val s = (base[1].coerceIn(0.35f, 0.75f) * sat).coerceIn(0.06f, 0.7f)
    fun hsl(
        saturation: Float,
        light: Float,
    ) = Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), light)))
    return if (dark) {
        KsuTone(container = hsl(s * 0.55f, 0.26f), content = hsl(s * 1.1f, 0.80f))
    } else {
        KsuTone(container = hsl(s * 0.9f, 0.89f), content = hsl(s * 1.15f, 0.36f))
    }
}

@Composable
fun rememberKsuTone(category: Color = KsuTileColors.Blue): KsuTone {
    val scheme = MochiTheme.colorScheme
    return remember(category, scheme.primary, scheme.error, scheme.isDark) {
        mochiTileTone(category, scheme.primary, scheme.error, scheme.isDark)
    }
}

private val MochiTileShape = MochiRoundedRectangle(9.5.dp)

/** 设置列表行首的 28dp 彩色图标块。 */
@Composable
fun KsuTile(
    icon: String,
    color: Color = KsuTileColors.Gray,
    enabled: Boolean = true,
) {
    val tone = rememberKsuTone(color)
    Box(
        Modifier
            .size(28.dp)
            .background(if (enabled) tone.container else tone.container.copy(alpha = 0.4f), MochiTileShape),
        contentAlignment = Alignment.Center,
    ) {
        MochiIonicon(
            icon,
            tint = if (enabled) tone.content else tone.content.copy(alpha = 0.6f),
            size = 16.dp,
        )
    }
}

/** 开关行：图标 + 标题 + 副标题 + 右侧开关。 */
@Composable
fun KsuSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: String? = null,
    color: Color = KsuTileColors.Gray,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    MochiListRow(
        title,
        subtitle = subtitle,
        enabled = enabled,
        leading = icon?.let { { KsuTile(it, color, enabled) } },
        trailing = { MochiSwitch(checked, onCheckedChange, enabled = enabled) },
    )
}

/**
 * 真实应用图标：先读缓存，未命中再异步加载；加载中或取不到时回退成首字色块，
 * 避免列表滚动时因为图标 IPC 而抖动。
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    accent: Color,
    size: Dp = 36.dp,
) {
    // 同一个组合位置切换到另一个应用（列表行复用、连续打开两个授权页）时，
    // produceState 会保留上一份 value：新应用会先显示旧图标，而且因为 value 非空
    // 会跳过加载，一直停在旧图标上。用 key 强制重建这段状态，producer 里也重新取值。
    key(packageName) {
        val context = LocalContext.current
        val icon by produceState<ImageBitmap?>(AppIcons.cached(packageName), packageName) {
            value = AppIcons.cached(packageName)
            if (value == null) value = AppIcons.load(context, packageName)
        }
        Box(
            Modifier.size(size).testTag("app-icon-$packageName"),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(size)
                            .clip(MochiRoundedRectangle(size * 0.26f)),
                )
            } else {
                AppIconBadge(label, accent, size)
            }
        }
    }
}

/** 应用图标占位：用首字 + 主题色块代替真实图标。 */
@Composable
fun AppIconBadge(
    label: String,
    accent: Color,
    size: Dp = 36.dp,
) {
    val shape = MochiRoundedRectangle(size * 0.26f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.take(1),
            color = Color.White,
            style = MochiTheme.textStyles.body.copy(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 页脚署名。 */
@Composable
fun MochiCredit(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MochiIonicon("sparkles", tint = MochiTheme.colorScheme.onSurfaceVariant, size = 14.dp)
        Text(
            text,
            style = MochiTheme.textStyles.footnote,
            color = MochiTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 6.1.118-android14-11-g3a9c1d2e-ab123 → 6.1.118-android14-11 */
fun shortKernelVersion(full: String): String =
    Regex("""^\d+\.\d+(\.\d+)?(-android\d+-\d+)?""").find(full)?.value?.takeIf { it.isNotEmpty() } ?: full

/** 一段可滚动的说明文字卡片。 */
@Composable
fun MochiFootnote(
    text: String,
    color: Color = MochiTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(text, style = MochiTheme.textStyles.footnote, color = color)
    }
}
