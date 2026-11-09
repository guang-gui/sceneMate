package com.scenemate.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scenemate.app.presentation.CameraViewModel
import com.scenemate.app.ui.CameraScreen
import com.scenemate.app.ui.SceneColors
import com.scenemate.app.ui.SceneMateTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SceneMateTheme {
                CameraApp()
            }
        }
    }
}

@Composable
private fun CameraApp(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraScreen(viewModel)
    } else {
        PermissionScreen(
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            onRetry = { launcher.launch(Manifest.permission.CAMERA) },
        )
    }
}

/** 权限拒绝态：全屏说明 + 一键跳系统设置，不用空白页（PRD 屏 1 状态机）。 */
@Composable
private fun PermissionScreen(onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(SceneColors.Ink),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
        ) {
            Text(
                text = "需要相机权限",
                color = SceneColors.Text,
                fontSize = 22.sp,
            )
            Text(
                text = "SceneMate 用相机画面算出「人该站哪」。画面只在你的手机上处理，" +
                    "不上传、不保存，除非你自己点了「深度分析」。",
                color = SceneColors.TextDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) { Text("重新授权") }
            Button(onClick = onOpenSettings) { Text("去系统设置") }
        }
    }
}
