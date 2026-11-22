package com.breakthebarriers.unbar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animated dialog wrapper (A4, 1.0.15): dialogs scale in (0.95) +
 * fade in (150ms) on enter and scale out + fade out (120ms) on
 * dismiss, instead of popping in/out instantly. The [dismiss]
 * lambda plays the exit animation first and only then calls the
 * parent's [onDismiss], so the exit is actually seen. Pass it to
 * the dialog's onDismissRequest and to its dismiss/confirm buttons
 * (after they have done their action).
 */
@Composable
fun AnimatedDialog(
    onDismiss: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { visible = true }
    fun dismiss() {
        if (!visible) return
        visible = false
        scope.launch {
            delay(120)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(initialScale = 0.95f, animationSpec = tween(150)) + fadeIn(animationSpec = tween(150)),
        exit = scaleOut(targetScale = 0.95f, animationSpec = tween(120)) + fadeOut(animationSpec = tween(120))
    ) {
        content(::dismiss)
    }
}
