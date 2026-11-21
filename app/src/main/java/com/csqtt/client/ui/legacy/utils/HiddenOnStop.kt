package com.csqtt.client.ui.legacy.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A "show the password" switch that turns itself off when the app leaves the screen (ON_STOP) and never outlives the
 * process, so a revealed secret is not what the recents thumbnail or the next opening shows (L-37).
 */
@Composable
fun rememberHiddenOnStop(): MutableState<Boolean> {
    val visible = remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) visible.value = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return visible
}
