package com.harken.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Tracks whether a staggered list row should have played its entrance yet. [revealedIds]
 * is owned by the caller (one `remember { mutableStateSetOf() }` per list) so scrolling a
 * row off-screen and back doesn't replay the animation — only a genuine first appearance
 * waits out `index * stepMs`, capped at `cap` so a long list doesn't visibly take a beat
 * to finish settling. `reducedMotion` skips the delay outright.
 */
@Composable
fun <T> rememberStaggerShown(
    id: T,
    index: Int,
    revealedIds: MutableSet<T>,
    reducedMotion: Boolean,
    cap: Int,
    stepMs: Long,
): Boolean {
    val alreadyRevealed = id in revealedIds
    var shown by remember(id) { mutableStateOf(alreadyRevealed || reducedMotion) }
    LaunchedEffect(id) {
        if (!alreadyRevealed) {
            if (!reducedMotion) {
                delay(index.coerceAtMost(cap) * stepMs)
                shown = true
            }
            revealedIds += id
        }
    }
    return shown
}
