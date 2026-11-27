package com.openjm.reader

import androidx.compose.ui.window.ComposeUIViewController
import com.openjm.reader.data.InMemoryBookRepository
import com.openjm.reader.ui.App
import platform.UIKit.UIViewController

/** Swift 侧 `ContentView` 通过这个入口拿到 Compose 页面。 */
fun MainViewController(): UIViewController {
    val repository = InMemoryBookRepository()
    return ComposeUIViewController {
        App(
            books = repository.recent(),
            onImport = { /* TODO(ios): 触发 UIDocumentPicker */ },
            onOpen = { /* TODO(ios): 跳到阅读页 */ },
        )
    }
}
