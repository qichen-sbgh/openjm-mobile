package com.openjm.reader.crypto

/**
 * `.jm` 的三类失败场景，UI 文案直接按类型映射，不做字符串匹配。
 *
 * 与协议 `docs/tech/protocol.md` §6 一一对应。
 */
sealed class JmError(message: String) : Exception(message) {

    /** 不是 .jm、版本不支持、结构坏了 → 让发送方重新封装。 */
    class Format(message: String) : JmError(message)

    /** 口令文件但没给口令。 */
    class PasswordRequired(message: String = "这份文档需要口令") : JmError(message)

    /** HMAC 不过：下载不完整、损坏、篡改，或口令错误。 */
    class Auth(message: String = "文件不完整或已损坏，请在 QQ 重新下载") : JmError(message)
}
