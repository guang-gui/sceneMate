package com.scenemate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scenemate.app.presentation.UiState

/**
 * 设置页（BYOK，ADR-015）。
 *
 * 三条产品要求：
 *  1. 首次开启弹一次性说明（画面将发送至所选服务商），不藏在隐私政策二级页；
 *  2. 随时关闭并一键清除密钥；
 *  3. 显示本地用量估算——BYOK 的头号客诉是「为什么不能用」，不是「不准」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onSaveKey: (String, String) -> Unit,
    onClearKey: (String) -> Unit,
    onActorChange: (Boolean) -> Unit,
    onPronounChange: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var provider by remember { mutableStateOf("deepseek") }
    var apiKey by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SceneColors.InkSoft,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("设置", color = SceneColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            Text("云端增强（自带密钥）", color = SceneColors.Text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "开启后，画面会在本机先模糊人脸，再发送给你选择的服务商。" +
                    "密钥只存这台手机的加密存储里，不上传、不进日志。默认关闭，飞行模式下全部功能可用。",
                color = SceneColors.TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChip("DeepSeek", provider == "deepseek") { provider = "deepseek" }
                ProviderChip("智谱 GLM", provider == "glm") { provider = "glm" }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (apiKey.isNotBlank()) {
                        onSaveKey(provider, apiKey.trim())
                        apiKey = ""
                    }
                }) { Text("保存并启用") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onClearKey(provider) }) {
                    Text("清除密钥", color = SceneColors.TextDim)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !state.cloudEnabled -> "当前：未开启（端侧规则独立工作）"
                    else -> "当前：已开启 · 本会话 ${state.cloudCallsThisSession} 次 · 约 ¥%.3f"
                        .format(state.cloudCallsThisSession * 0.004)
                },
                color = if (state.cloudEnabled) SceneColors.Accent else SceneColors.TextDim,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            Text("指令对象", color = SceneColors.Text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChip("让被摄者动", state.actorIsSubject) { onActorChange(true) }
                ProviderChip("我自己动", !state.actorIsSubject) { onActorChange(false) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("她", "他", "TA").forEach { p ->
                    ProviderChip(p, state.subjectPronoun == p) { onPronounChange(p) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "版本 ${com.scenemate.app.BuildConfig.VERSION_NAME} · 端侧引导 + BYOK 增强",
                color = SceneColors.TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) SceneColors.Accent else SceneColors.TextDim,
            fontSize = 13.sp,
        )
    }
}
