package dev.roastbook.app.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Gives the control after this modifier the focus when it first appears: a destructive question's
 * Cancel, so Enter pressed by habit keeps the thing. With touch, where nothing holds focus, the
 * request does nothing.
 */
fun Modifier.initialFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { requester.requestFocus() }
    focusRequester(requester)
}
