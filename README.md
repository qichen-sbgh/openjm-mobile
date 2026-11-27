# openjm-mobile

`.jm` 加密文档的移动端阅读器：在 QQ 里点开 `.jm`，校验、解密、进书架、原生渲染 PDF。

`compatible: proto-v1`

协议与跨端测试向量在 [`openjm`](https://github.com/qichen-sbgh/openjm) 仓库，本仓只实现消费端，不改协议。

> **状态：骨架**。共享模块的协议核心已经写完，Android/iOS 的加密原语也已落代码，但**本仓库尚未在任何机器上编译通过**——生成它的环境没有 JDK 与 Xcode。第一次用 Android Studio / Xcode 打开时，请按下面的清单逐项过。

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
    data/Book.kt               # 书架模型 + 内存仓库（Room KMP 待接入）
    ui/App.kt                  # 书架页
    ui/PdfReaderCanvas.kt      # expect：平台 PDF 渲染桥
  src/androidMain/…            # javax.crypto、ContentResolver 流、PDFium 占位
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
# Android：直接用 Android Studio 打开仓库根目录，等 Gradle 同步
./gradlew :androidApp:assembleDebug

# iOS：Xcode 打开 iosApp/iosApp.xcodeproj
# 首次需在 Configuration/Config.xcconfig 里填 TEAM_ID
```

## 待办

**共享层**
- [ ] `commonTest`：用 `openjm/testvectors` 回放（需要在 Gradle 里加测试源集与 kotlinx-coroutines-test）
- [ ] Room KMP + KSP 接入，替换 `InMemoryBookRepository`
- [ ] 导入进度回调（`Flow<ImportProgress>`），给 UI 显示百分比

**Android**
- [ ] 后台线程完整导入：`filesDir/tmp/{uuid}.tmp` → 校验通过后 rename 到 `filesDir/books/{doc_id}.pdf`
- [ ] PDFium 接入（`AndroidView`）+ 首页封面渲染
- [ ] `.jm.1` 等被 QQ 改名的情况：靠魔数判定，入口里已兼容
- [ ] 阅读进度 500ms 防抖写入 + 退后台补写

**iOS**
- [ ] `onOpenURL` 里补 security-scoped 访问与导入
- [ ] `PDFKit` 接入（`UIKitView`）+ 封面缩略图
- [ ] Info.plist 的 UTI 已经写了，需真机验证 QQ 是否出现在“用其他应用打开”列表

## 与其他两仓的关系

| 依赖 | 来源 | 说明 |
| --- | --- | --- |
| 协议语义 | `openjm/docs/protocol-v1.md` | 冻结，任何改动先改协议 |
| 测试向量 | `openjm/testvectors/` | 11 个样本，三端必须一致 |
| 测试根密钥 | `openjm/testvectors/keys.test.json` | `key_id=254`，只进测试 source set |

生产根密钥不放任何仓库，见 `openjm/docs/key-registry.md`。
