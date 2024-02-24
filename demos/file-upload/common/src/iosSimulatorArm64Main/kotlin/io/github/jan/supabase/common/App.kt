package io.github.jan.supabase.common

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.UIKit.UIViewController

class RootComponent : KoinComponent {
    private val viewModel: UploadViewModel by inject()
    fun getViewModel(): UploadViewModel = viewModel
}

fun AppIos(viewModel: UploadViewModel): UIViewController = ComposeUIViewController {
    App(viewModel = viewModel)
}