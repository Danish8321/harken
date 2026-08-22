package com.harken.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harken.android.ui.theme.LocalInk

// The two surfaces the whole app is built from. Every screen uses these rather than
// rolling its own Card/Surface, which is what let four screens drift into four different
// layout languages in the previous build.

/**
 * The standard content card: one radius (shapes.large), one padding, one hairline.
 * A row, a settings group and an empty state are all this component.
 */
@Composable
fun HarkenCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colour = MaterialTheme.colorScheme.surface
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = colour, border = border) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = colour, border = border) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/**
 * The ink surface — the dark anchor the cream ground needs in order to have a
 * foreground at all. Reserved for audio: the capture stage, the player, the floating
 * toolbar. Using it anywhere else dilutes the one signal it carries.
 */
@Composable
fun InkSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = LocalInk.current.ink,
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** A status chip. Always a shape or icon plus a WORD — never colour alone. */
@Composable
fun StatusChip(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    leading: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = com.harken.android.ui.theme.PillShape, color = container) {
        Row(
            modifier = Modifier.padding(start = if (leading != null) 9.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke(this)
            androidx.compose.material3.Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
        }
    }
}
