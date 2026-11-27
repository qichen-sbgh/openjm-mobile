# openjm-mobile

`.jm` 加密文档的移动端阅读器：在 QQ 里点开 `.jm`，校验、解密、进书架、原生渲染 PDF。

`compatible: proto-v1`

协议与跨端测试向量在 [`openjm`](https://github.com/qichen-sbgh/openjm) 仓库，本仓只实现消费端，不改协议。

> **状态：Android 在开发，iOS 冻结。**
>
> 平台优先级由 [`openjm` 的 D10 决策](https://github.com/qichen-sbgh/openjm/blob/main/docs/tech/decisions.md) 定：先把 Android 跑到稳定，iOS 代码保留在仓库但**不编译、不验证、不作为发版门槛**。
>
> | 平台 | 状态 |
> | --- | --- |
> | Android | `assembleDebug` 通过；11 个冻结向量在 `androidHostTest` 全过 |
> | iOS | ⏸ 冻结：仅保留源码，等 Android 稳定后再对齐 |

## 结构

```text
shared/                        # Kotlin Multiplatform：协议核心 + 数据 + Compose UI
  src/commonMain/kotlin/com/openjm/reader/
    crypto/
      JmProtocol.kt            # 28B 头解析、常量
      JmKeyDerivation.kt       # HKDF-SHA256 派生（与 Python 逐字节对齐）
      JmPrimitives.kt          # expect：HMAC / PBKDF2 / AES-CTR / 流接口
      JmMeta.kt                # 加密 Meta 解析 + 字段校验
      JmImporter.kt            # 单遍流式校验 + 解密（尾部 32B 留一手）
      JmErrors.kt              # Format / PasswordRequired / Auth
      RootKeys.kt              # 根密钥 JSON 装载（只解析，不决定密钥从哪来）
    data/Book.kt               # 书架模型 + 内存仓库（Room KMP 待接入）
    ui/App.kt                  # 书架页
    ui/PdfReaderCanvas.kt      # expect：平台 PDF 渲染桥
  src/androidMain/…            # javax.crypto、ContentResolver 流、沙盒落盘（JmSandbox）、PDFium 占位
  src/iosMain/…                # CommonCrypto、NSInputStream、PDFKit 占位、ComposeUIViewController
androidApp/                    # Android 壳：QQ 唤起、SAF 兜底、私有沙盒
iosApp/                        # Xcode 壳：自定义 UTI + onOpenURL
```

> 与原设计文档的一处偏离：文档里写的是单个 `composeApp` 模块，这里改成 `shared + androidApp + iosApp`。
> 原因是 Android 的 Intent/Manifest 与 Xcode 的 Info.plist/UTI 都需要各自的原生入口，
> 拆开后壳工程可以做得很薄，共享层只放协议与 UI。功能边界不变。

## 已经写完的（共享层）

- 28 字节头解析：魔数、版本、算法、flag、`key_id`、nonce、`meta_len`，任何非法值直接拒；
- HKDF-SHA256 派生：`PRK = HMAC(nonce, IKM)` 这个“salt 是 nonce”的反常点已按协议写死；
- 口令模式：PBKDF2-HMAC-SHA256 10 万次，`key_id` 恒为 0；
- 单遍流式导入：64KB 缓冲 + 尾部 32 字节滑动窗口，一遍同时算 HMAC 和解密；
- 校验通过才落盘：明文先写 `tmp/{uuid}.tmp`，成功才原子改名成 `books/{doc_id}.{ext}`，失败删临时文件；
- 书架 upsert：同一 `doc_id` 重复导入保留阅读进度，只刷新最近阅读时间；
- 长度恒等式校验、常量时间 MAC 比对、Meta 路径穿越过滤；
- 三类错误与 UI 文案分开，不靠字符串匹配。

## 两个必须守住的坑

1. **iOS 的 CTR 计数器必须是大端**。本仓的 iOS 实现绕开了 `kCCModeOptionCTR_BE` 选项，
   改成“ECB 批量加密计数器块再异或”，计数器递增由 Kotlin 显式做大端进位，错不了。
2. **PBKDF2 的口令编码**。Python 用 `password.encode("utf-8")` 的字节，
   Android 的 `PBEKeySpec` 吃 `char[]`，所以要把 UTF-8 字节还原成 String 再转 char[]；
   iOS 的 `CCKeyDerivationPBKDF` 直接吃字节，不需要转。

## 跑起来

```bash
# Android（当前唯一目标平台）：无需 Android Studio，JDK 21 + Android SDK 即可
export JAVA_HOME=/opt/homebrew/opt/openjdk@21        # macOS 本机路径
$env:JAVA_HOME = "C:\path\to\jdk-21"                 # Windows PowerShell

./gradlew :androidApp:assembleDebug                  # Windows 用 .\gradlew.bat
# 产物：androidApp/build/outputs/apk/debug/androidApp-debug.apk

# 回放协议向量（指不到 testvectors 时这几个用例会自动 skip，不会因此变红）
OPENJM_TESTVECTORS=../openjm/testvectors ./gradlew :shared:testAndroidHostTest
#   Windows: $env:OPENJM_TESTVECTORS="..\openjm\testvectors"; .\gradlew.bat :shared:testAndroidHostTest

# 真机导入需要根密钥（不入库，.gitignore 已挡）：
#   把 {"1":"<64 位 hex>"} 放到 androidApp/src/main/assets/keys.json，格式见 openjm 的 key-registry.md
#   缺这个文件时 App 能起来，但导入前会提示“缺少根密钥”

# iOS（⏸ 冻结）：等 Android 稳定后再用 Xcode 打开 iosApp/iosApp.xcodeproj
```

## 待办

**共享层**
- [x] 向量回放与落盘链路：`androidHostTest` 13 例（3 个向量回放 + 6 个沙盒落盘 + 4 个书架 upsert）
- [ ] CI 里回放真实向量（现在只有构建 + 编译门槛；向量仓私有，需要配一个只读 token）
- [ ] Room KMP + KSP 接入，替换 `InMemoryBookRepository`
- [ ] 导入进度回调（`Flow<ImportProgress>`），给 UI 显示百分比

**Android**
- [x] 后台线程完整导入：`filesDir/tmp/{uuid}.tmp` → 校验通过后 rename 到 `filesDir/books/{doc_id}.pdf`
      （书架仍是内存实现，重启 App 会清空 —— 等 Room 接入）
- [ ] 口令弹窗：现在遇到口令文件只提示“需要口令”，不弹输入框
- [ ] PDFium 接入（`AndroidView`）+ 首页封面渲染
- [ ] `.jm.1` 等被 QQ 改名的情况：靠魔数判定，入口里已兼容
- [ ] 阅读进度 500ms 防抖写入 + 退后台补写

**iOS（⏸ 冻结，等 Android 稳定后启动，见 D10）**
- [ ] `onOpenURL` 里补 security-scoped 访问与导入
- [ ] `PDFKit` 接入（`UIKitView`）+ 封面缩略图
- [ ] Info.plist 的 UTI 已经写了，需真机验证 QQ 是否出现在“用其他应用打开”列表
- [ ] `platform.CoreCrypto` 的 cinterop 类型校对（从未编译过）

## 与其他两仓的关系

| 依赖 | 来源 | 说明 |
| --- | --- | --- |
| 协议语义 | `openjm/docs/tech/protocol.md` | 冻结，任何改动先改协议 |
| 测试向量 | `openjm/testvectors/` | 11 个样本，三端必须一致 |
| 测试根密钥 | `openjm/testvectors/keys.test.json` | `key_id=254`，只进测试 source set |

生产根密钥不放任何仓库，见 `openjm/docs/tech/key-registry.md`。
