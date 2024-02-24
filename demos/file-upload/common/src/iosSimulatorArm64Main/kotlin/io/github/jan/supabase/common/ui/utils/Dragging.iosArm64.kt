package io.github.jan.supabase.common.ui.utils

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput


@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun Modifier.applyDragging(isDragging: MutableState<Boolean>, onSuccess: (List<String>) -> Unit): Modifier {
    return composed {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { isDragging.value = true },
                onDrag = { it -> },
                onDragEnd = { },
                onDragCancel = {  }
            )

        }

    }
}