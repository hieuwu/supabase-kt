package io.github.jan.supabase.common.ui.dialog

import androidx.compose.runtime.Composable
import io.github.jan.supabase.common.MPFile

@Composable
actual fun MPFilePicker(
    showFileDialog: Boolean,
    onFileSelected: (MPFile) -> Unit,
    close: () -> Unit
) {
}