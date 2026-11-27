import SwiftUI
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onOpenURL { url in
            // TODO(ios)：在 security-scoped 权限窗口内把 url 交给 Shared 的导入逻辑，
            // 单遍读流、校验通过后落私有沙盒，然后刷新书架。
            NSLog("openjm received url: \(url.absoluteString)")
        }
    }
}
