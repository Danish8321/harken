package com.harken.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.harken.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.PillShape
import com.harken.android.ui.theme.ProtoBodyFont

// Empty, error and loading are the SAME card at the SAME radius as a populated row, so a
// list that is empty, broken or loading still reads as the same screen. The previous
// build had none of these three states on any screen.

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    HarkenCard(modifier = modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, shape = PillShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

/**
 * Errors name the address and the recovery. They never surface a raw HTTP code — the
 * previous build printed "HTTP 500" and left the user with nowhere to go.
 */
@Composable
fun ErrorState(
    title: String,
    body: String,
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    HarkenCard(
        modifier = modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onRetry != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onRetry,
                    shape = PillShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                ) { Text(stringResource(R.string.state_retry), fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(
                    onClick = onSecondary,
                    shape = PillShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(secondaryLabel, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
    }
}

/**
 * Blocking failure with no natural inline slot to render into (e.g. a foreground service
 * that has no Compose surface of its own). Dismiss-only — these are technical failures the
 * user cannot retry from here, they just need to know the operation didn't happen.
 */
@Composable
fun HarkenErrorDialog(title: String, body: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.state_dismiss)) }
        },
    )
}

/** Skeleton row, shaped like the real row so the list does not reflow when data lands. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.62f,
        // Deliberately a tween, not a spring: this is an idle ambient pulse, not a
        // state change, so no spatial or effects token applies.
        animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    // The pulse is an infinite transition, so no motion token can snap it — under
    // reduced motion the skeleton simply holds its dim state.
    val steady = if (LocalReducedMotion.current) 0.35f else alpha
    HarkenCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.alpha(steady), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Bar(0.62f, 15.dp)
            Bar(0.40f, 11.dp)
            Bar(1f, 6.dp)
        }
    }
}

@Composable
private fun Bar(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
