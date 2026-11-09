package com.scenemate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scenemate.app.domain.ParamKey
import com.scenemate.app.presentation.UiState
import kotlin.math.roundToInt

private val SHUTTER_STEPS = listOf(
    "1/4000s" to 250_000L,
    "1/2000s" to 500_000L,
    "1/1000s" to 1_000_000L,
    "1/500s" to 2_000_000L,
    "1/250s" to 4_000_000L,
    "1/125s" to 8_000_000L,
    "1/60s" to 16_666_000L,
    "1/30s" to 33_333_000L,
    "1/15s" to 66_666_000L,
)

/**
 * 屏 3｜参数面板。
 *
 * 交互铁律（R05）：拖动任一滑轨 → 该项立即转中性白 + 锁图标，AI 本会话内不再回写。
 * 这条不是 UI 提示，是 AuthorityStore 的状态迁移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamPanel(
    state: UiState,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onParamChange: (ParamKey, Int) -> Unit,
    onUnlock: (ParamKey) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SceneColors.InkSoft,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("参数", color = SceneColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.manualSensorSupported) "本机支持手动参数" else "该机型不支持手动参数",
                    color = if (state.manualSensorSupported) SceneColors.TextDim else SceneColors.Warn,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))

            ParamRow(
                label = "ISO",
                value = state.cameraParams.iso.toString(),
                aiValue = state.aiParams?.iso?.toString(),
                locked = ParamKey.ISO in state.lockedParams,
                enabled = state.manualSensorSupported,
                onUnlock = { onUnlock(ParamKey.ISO) },
                slider = {
                    Slider(
                        value = state.cameraParams.iso.toFloat(),
                        onValueChange = { onParamChange(ParamKey.ISO, it.roundToInt()) },
                        valueRange = 50f..3200f,
                        enabled = state.manualSensorSupported,
                    )
                },
            )

            ParamRow(
                label = "快门",
                value = state.cameraParams.shutterDisplay(),
                aiValue = state.aiParams?.shutterDisplay(),
                locked = ParamKey.SHUTTER in state.lockedParams,
                enabled = state.manualSensorSupported,
                onUnlock = { onUnlock(ParamKey.SHUTTER) },
                slider = {
                    val index = SHUTTER_STEPS.indexOfFirst { it.second == state.cameraParams.exposureNs }
                        .coerceAtLeast(0)
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { onParamChange(ParamKey.SHUTTER, SHUTTER_STEPS[it.roundToInt().coerceIn(0, SHUTTER_STEPS.lastIndex)].second.toInt()) },
                        valueRange = 0f..SHUTTER_STEPS.lastIndex.toFloat(),
                        steps = SHUTTER_STEPS.size - 2,
                        enabled = state.manualSensorSupported,
                    )
                },
            )

            ParamRow(
                label = "EV",
                value = state.cameraParams.evDisplay(),
                aiValue = state.aiParams?.evDisplay(),
                locked = ParamKey.EV in state.lockedParams,
                enabled = state.manualSensorSupported,
                onUnlock = { onUnlock(ParamKey.EV) },
                slider = {
                    Slider(
                        value = state.cameraParams.evSteps.toFloat(),
                        onValueChange = { onParamChange(ParamKey.EV, it.roundToInt()) },
                        valueRange = -6f..6f,
                        steps = 11,
                        enabled = state.manualSensorSupported,
                    )
                },
            )

            ParamRow(
                label = "白平衡",
                value = state.cameraParams.wbDisplay(),
                aiValue = state.aiParams?.wbDisplay(),
                locked = ParamKey.WB in state.lockedParams,
                enabled = true,
                onUnlock = { onUnlock(ParamKey.WB) },
                slider = {
                    Slider(
                        value = (state.cameraParams.wbKelvin ?: 5000).toFloat(),
                        onValueChange = { onParamChange(ParamKey.WB, (it / 100).roundToInt() * 100) },
                        valueRange = 2000f..8000f,
                        steps = 59,
                    )
                },
            )

            Spacer(Modifier.height(8.dp))
            ExplainBar(state)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭", color = SceneColors.TextDim) }
                Spacer(Modifier.width(8.dp))
                val canApply = state.paramSuggestion != null && state.sessionState.allowsParamApply
                Button(onClick = onApply, enabled = canApply) {
                    Text(if (canApply) "应用建议" else "建议仅供参考")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    aiValue: String?,
    locked: Boolean,
    enabled: Boolean,
    onUnlock: () -> Unit,
    slider: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = SceneColors.TextDim, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            Text(
                text = value,
                color = if (locked) SceneColors.UserValue else SceneColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (aiValue != null && aiValue != value) {
                Spacer(Modifier.width(6.dp))
                Text("→ $aiValue", color = SceneColors.Accent, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            if (locked) {
                Text(
                    text = "已锁定 · 点此恢复",
                    color = SceneColors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SceneColors.AccentDim)
                        .clickable(onClick = onUnlock)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            } else {
                Text("AI 控制", color = SceneColors.TextDim, fontSize = 11.sp)
            }
        }
        slider()
    }
}

/** 变化解释条：before / after / mechanism / benefit 四要素，缺一不显示（R03）。 */
@Composable
private fun ExplainBar(state: UiState) {
    val explain = state.paramExplain
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SceneColors.Ink)
            .padding(12.dp),
    ) {
        if (explain == null) {
            Text("还没有可应用的参数建议", color = SceneColors.TextDim, fontSize = 12.sp)
            return@Column
        }
        Text(
            text = "${explain.before} → ${explain.after}",
            color = SceneColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = explain.mechanism, color = SceneColors.Text, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(text = explain.benefit, color = SceneColors.TextDim, fontSize = 12.sp)
    }
}
