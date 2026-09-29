package com.arnav.island.settings.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnav.island.settings.ui.theme.LocalIslandColors

/**
 * Standard settings page in the One UI layout: a tall header with the title centred in the top
 * part of the screen (easy to read, and the content starts within thumb reach), which folds into
 * the toolbar as the page scrolls.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    val list = rememberLazyListState()
    val headerHeight = (LocalConfiguration.current.screenHeightDp * 0.26f).coerceIn(140f, 250f).dp
    val headerPx = with(LocalDensity.current) { headerHeight.toPx() }
    val collapse by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / (headerPx * 0.72f)).coerceIn(0f, 1f)
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(((collapse - 0.55f) / 0.45f).coerceIn(0f, 1f)),
            )
            actions()
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 40.dp),
        ) {
            item(key = "one-ui-header") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .graphicsLayer {
                            alpha = 1f - collapse
                            translationY = collapse * headerPx * 0.3f
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            content()
        }
    }
}

/**
 * One UI style switch: a white thumb on a pill track that fills with the accent colour, with a
 * small springy overshoot. [onCheckedChange] null means the row around it handles the toggle.
 */
@Composable
fun OneUiSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val t by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "switch")
    val dark = LocalIslandColors.current.isDark
    val on = MaterialTheme.colorScheme.primary
    val off = if (dark) Color(0xFF48484E) else Color(0xFFCFCFD4)
    val track = lerp(off, on, t.coerceIn(0f, 1f))
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Canvas(
        Modifier
            .size(width = 50.dp, height = 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(toggle),
    ) {
        val r = size.height / 2f
        drawRoundRect(track, cornerRadius = CornerRadius(r, r))
        val inset = 3.dp.toPx()
        val thumb = r - inset
        val x = inset + thumb + (size.width - 2 * (inset + thumb)) * t
        drawCircle(Color(0x33000000), thumb + 0.8.dp.toPx(), Offset(x, r + 0.8.dp.toPx()))
        drawCircle(Color.White, thumb, Offset(x, r))
    }
}

/** A rounded group of rows, One UI style. */
@Composable
fun Group(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 18.dp, bottom = 8.dp, top = 6.dp),
            )
        }
        Surface(shape = RoundedCornerShape(26.dp), color = LocalIslandColors.current.card) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Int = 34) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.58f).dp))
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.alpha(alpha)) { IconBadge(icon, iconColor) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).alpha(alpha)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, iconColor: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true, onClick: () -> Unit) {
    SettingRow(title, subtitle, icon, iconColor, enabled, onClick) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.45f
        if (icon != null) {
            Box(Modifier.alpha(alpha)) { IconBadge(icon, iconColor) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).alpha(alpha)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        OneUiSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Slider row. By default the value is committed when the finger lifts; [live] commits on every
 * change (used by calibration so the real island follows the finger).
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    format: (Float) -> String,
    subtitle: String? = null,
    steps: Int = 0,
    live: Boolean = false,
    enabled: Boolean = true,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = local,
            onValueChange = {
                local = it
                if (live) onValueChange(it)
            },
            onValueChangeFinished = { if (!live) onValueChange(local) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    title: String? = null,
    subtitle: String? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (title != null || subtitle != null) Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
fun <T> ChipsRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    title: String? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Prominent explanatory card with an optional action. */
@Composable
fun InfoCard(
    title: String,
    body: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = LocalIslandColors.current.card,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, color, 36)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actionLabel != null && onAction != null) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                    if (secondaryLabel != null && onSecondary != null) TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}

/** Permission row: shows a check when granted, otherwise a button. */
@Composable
fun PermissionRow(title: String, subtitle: String, granted: Boolean, icon: ImageVector, color: Color, onGrant: () -> Unit, grantLabel: String = "Allow") {
    SettingRow(title, subtitle, icon, color, onClick = if (granted) null else onGrant) {
        if (granted) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Granted", tint = LocalIslandColors.current.positive)
        } else {
            FilledTonalButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(grantLabel) }
        }
    }
}

/** Expands/collapses dependent settings smoothly. */
@Composable
fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(content = content)
    }
}

@Composable
fun SectionSpacer() = Spacer(Modifier.height(8.dp))
