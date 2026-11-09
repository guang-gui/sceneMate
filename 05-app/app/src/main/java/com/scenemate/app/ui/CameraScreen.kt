package com.scenemate.app.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scenemate.app.capability.capture.Camera2Controller
import com.scenemate.app.capability.capture.CameraCapabilities
import com.scenemate.app.domain.ParamKey
import com.scenemate.app.presentation.CameraViewModel
import com.scenemate.app.presentation.SessionState
import com.scenemate.app.presentation.UiState
import com.scenemate.app.presentation.overlay.OverlayView
import java.io.File
import android.graphics.Bitmap

@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controller by remember { mutableStateOf<Camera2Controller?>(null) }

    val listener = remember(viewModel) {
        object : Camera2Controller.Listener {
            override fun onCameraReady(capabilities: CameraCapabilities) =
                viewModel.onCameraReady(capabilities)

            override fun onFrame(image: android.media.Image) = viewModel.onFrame(image)

            override fun onCaptureState(iso: Int, exposureNs: Long) =
                viewModel.onCaptureState(iso, exposureNs)

            override fun onPhotoSaved(file: File, thumbnail: Bitmap?) =
                viewModel.onPhotoSaved(file, thumbnail)

            override fun onCameraError(message: String) = viewModel.onCameraError(message)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.release()
            viewModel.detachCamera()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    val preview = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val created = Camera2Controller(ctx, preview, listener)
                            controller = created
                            viewModel.attachCamera(created)
                            created.start()
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> OverlayView(ctx) },
            update = { view -> view.setFrame(state.overlay) },
        )

        // 顶部：玻璃胶囊。青色只表示「AI 给出的信息」。
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 56.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusChip(state)
        }

        // 右侧竖排工具
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RoundTool(label = "灯") { viewModel.toggleTorch() }
            RoundTool(label = "深") { viewModel.requestDeepAnalysis() }
            RoundTool(label = "设") { viewModel.toggleSettings(true) }
        }

        // 底部：EXIF 双轨 + 快门
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExifBar(state = state, onClick = { viewModel.toggleParamPanel(true) })
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ThumbSlot(state)
                ShutterButton { viewModel.onShutter() }
                RoundTool(label = "翻") { viewModel.flipCamera() }
            }
            if (state.cloudNote != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.cloudNote.orEmpty(),
                    color = SceneColors.Accent,
                    fontSize = 12.sp,
                )
            }
        }

        if (state.showParamPanel) {
            ParamPanel(
                state = state,
                onDismiss = { viewModel.toggleParamPanel(false) },
                onApply = { viewModel.applyParamSuggestion() },
                onParamChange = { key, value -> viewModel.onUserParamChange(key, value) },
                onUnlock = { key -> viewModel.unlockParam(key) },
            )
        }

        if (state.showSettings) {
            SettingsSheet(
                state = state,
                onDismiss = { viewModel.toggleSettings(false) },
                onSaveKey = { provider, key -> viewModel.saveByokKey(provider, key) },
                onClearKey = { provider -> viewModel.clearByokKey(provider) },
                onActorChange = { viewModel.setActorIsSubject(it) },
                onPronounChange = { viewModel.setSubjectPronoun(it) },
            )
        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage.orEmpty(),
                color = SceneColors.Warn,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(state: UiState) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xB30A0B0F))
            .border(1.dp, SceneColors.AccentDim, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state.offline) SceneColors.Warn else SceneColors.Accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "AI 识别 · ${state.chipText}",
            color = SceneColors.Text,
            fontSize = 13.sp,
        )
        if (state.bandDisplay.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.bandDisplay,
                color = SceneColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = state.stateLabel, color = SceneColors.TextDim, fontSize = 11.sp)
    }
}

/**
 * EXIF 双轨（R07）：AI 值青色，用户手动值中性白 + 锁标记。
 * 参数条始终可见，两值不同时并排显示。
 */
@Composable
private fun ExifBar(state: UiState, onClick: () -> Unit) {
    val camera = state.cameraParams
    val ai = state.aiParams
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x990A0B0F))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.lockedParams.isEmpty()) "AI 自动" else "手动 ${state.lockedParams.size}",
            color = SceneColors.Accent,
            fontSize = 12.sp,
        )
        ParamPair("ISO", camera.iso.toString(), ai?.iso?.toString(), ParamKey.ISO in state.lockedParams)
        ParamPair("快门", camera.shutterDisplay(), ai?.shutterDisplay(), ParamKey.SHUTTER in state.lockedParams)
        ParamPair("EV", camera.evDisplay(), ai?.evDisplay(), ParamKey.EV in state.lockedParams)
        ParamPair("WB", camera.wbDisplay(), ai?.wbDisplay(), ParamKey.WB in state.lockedParams)
    }
}

@Composable
private fun ParamPair(label: String, actual: String, aiValue: String?, locked: Boolean) {
    Spacer(Modifier.width(10.dp))
    val text = when {
        aiValue != null && aiValue != actual -> "$actual→$aiValue"
        else -> actual
    }
    Text(
        text = if (locked) "$label $text 锁" else "$label $text",
        color = if (locked) SceneColors.UserValue else SceneColors.Accent,
        fontSize = 12.sp,
    )
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .border(3.dp, SceneColors.Text, CircleShape)
            .clickable(onClick = onClick)
            .padding(7.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(SceneColors.Text),
        )
    }
}

@Composable
private fun RoundTool(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0x800A0B0F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = SceneColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun ThumbSlot(state: UiState) {
    val thumb = state.lastThumbnail
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x800A0B0F))
            .border(1.dp, SceneColors.TextDim, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "最近一张",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "相册", color = SceneColors.TextDim, fontSize = 10.sp)
        }
    }
}
