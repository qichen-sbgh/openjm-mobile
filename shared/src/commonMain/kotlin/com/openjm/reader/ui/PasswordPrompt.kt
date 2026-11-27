package com.openjm.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 口令输入弹窗（FR-04 / FR-17）。
 *
 * 两个设计点：
 * - **先验证再落盘**：口令错了只会得到 `JMAuthError`，沙盒里不会留下半点明文（这是 `JmSandboxImporter` 的约定，不是这里做的）；
 * - **错误提示模糊处理**：口令错与文件损坏在协议层不可区分（D2），所以文案统一成「口令不对，或文件已损坏」，不去猜。
 */
@Composable
fun PasswordPrompt(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    errorMessage: String? = null,
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这份文档需要口令") },
        text = {
            Column {
                Text(
                    text = "口令正确才会解密落盘；输错不会留下任何文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("口令") },
                    isError = errorMessage != null,
                    supportingText = if (errorMessage != null) {
                        { Text(errorMessage) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }, enabled = value.isNotEmpty()) { Text("解锁") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
