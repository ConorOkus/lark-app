package xyz.lark.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

@Suppress("FunctionNaming") // UIKit factory convention
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
