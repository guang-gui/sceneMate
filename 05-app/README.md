# SceneMate Android · P0 实现（R01–R08）

> 按 `../02-architecture/tech-architecture-scenemate-2026-10-29.md`（TAD v1.2）实现的 Android 首发版本。
> 一句话：**端侧几何给站位，规则引擎给场景，大模型只在用户自带密钥时做增强**。

| 项 | 状态 |
|---|---|
| 构建 | ✅ `assembleDebug` / `assembleRelease` 通过 |
| 单测 | ✅ 48 条 JVM 单测全绿（几何 / 仲裁 / 权威 / 指令模板 / schema） |
| 真机 | ⬜ 未跑（模拟器已验证：预览、规则标签、兜底指令、快门落盘、参数面板、BYOK 设置页） |
| 包体 | ⚠️ arm64 release **62MB**，超 TAD ≤25MB 预算（见 §6） |

---

## 1. 环境（已装好，路径固定）

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK | Temurin 17.0.12 | `D:\devSoft\jdk17` |
| Android SDK | platform-tools / platforms;android-35 / build-tools;35.0.0 | `D:\devSoft\android-sdk` |
| Gradle | 8.9 | `D:\devSoft\gradle-8.9` |
| 模拟器 | emulator 37.2.13 + system-images;android-35;default;x86_64 | 同上 SDK |

`dl.google.com` 在本机被墙，全部依赖走镜像：SDK 包走 `mirrors.cloud.tencent.com/AndroidSDK/`，
Maven 走 `maven.aliyun.com/repository/{google,public}`（见 `settings.gradle.kts`），
Gradle 分发包走 `mirrors.cloud.tencent.com/gradle/`（见 `gradle/wrapper/gradle-wrapper.properties`）。

**每次开新终端都要设这两个变量**（Windows 路径，不要用 `/d/...`）：

```bash
export JAVA_HOME='D:\devSoft\jdk17'
export ANDROID_HOME='D:\devSoft\android-sdk'
```

## 2. 构建与运行

```bash
cd deliverables/scenemate/05-app

# 单测（纯 JVM，秒级）
/d/devSoft/gradle-8.9/bin/gradle.bat :app:testDebugUnitTest

# 打包
/d/devSoft/gradle-8.9/bin/gradle.bat :app:assembleDebug
/d/devSoft/gradle-8.9/bin/gradle.bat :app:assembleRelease   # 未签名

# 装到真机/模拟器
$ANDROID_HOME/platform-tools/adb install -r -t \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

模拟器调试：`app/build.gradle.kts` 的 `splits.abi.include` 默认只有 `arm64-v8a` / `armeabi-v7a`，
需要 x86_64 时把它加进去重编（否则 x86 模拟器装不上）。

## 2.1 真机测试包（`dist/`）

签名 release 包已生成在 `dist/`，附 `安装与测试说明.md` 与 `SHA256SUMS.txt`：

| 文件 | 大小 |
|---|---|
| `SceneMate-p0-1.0.0-arm64-v8a.apk` | 62MB（默认） |
| `SceneMate-p0-1.0.0-armeabi-v7a.apk` | 53MB |
| `SceneMate-p0-1.0.0-universal.apk` | 138MB（双 ABI 合集） |

签名密钥不在仓库中（`.gitignore` 排除了 `keystore/` 和 `*.jks`）。
签名配置从 `local.properties` 或环境变量读取（`STORE_FILE` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。
重新出包：

```bash
/d/devSoft/gradle-8.9/bin/gradle.bat :app:assembleRelease
# 产物在 app/build/outputs/apk/release/，universal 由 splits.isUniversalApk 控制
```

## 3. 代码结构 → TAD 映射

```
app/src/main/java/com/scenemate/app/
├── domain/                     纯逻辑，无 Android 依赖，全部可单测
│   ├── Suggestion.kt           建议对象 + Tier 推导（§8.1）
│   ├── SuggestionSchema.kt     schema 校验：actor 正则、≤14 字、禁止词、range_tier 约束
│   ├── Arbiter.kt              ADR-009 仲裁：硬约束 + 打分，机械保证首屏只有 1 条
│   ├── SuggestionStore.kt      §5.3 单调版本 / 1.2s 最小展示 / 兜底优先
│   ├── AuthorityStore.kt       ADR-002 + R05：AI 写参数的唯一入口，锁定后零回写
│   └── InstructionWriter.kt    §6.4 指令 DSL：1 actor + 1 direction + 1 magnitude
├── capability/
│   ├── geometry/               ★ 差异化核心，纯数学
│   │   ├── RangeEstimator.kt   ADR-010：鼻-踝外接框 + 相似三角形 + 三级降级链
│   │   └── StanceSolver.kt     §6.3：alpha 闭环，输出 Δback / Δlateral / 脚印
│   ├── perception/
│   │   ├── PerceptionPipeline.kt  ML Kit bundled：姿态 33 点 + 人脸计数
│   │   ├── RuleSceneClassifier.kt §6.5：YUV 统计量 → 三标签，置信度上限 0.82
│   │   └── FrameStatsReader.kt    直接从 Y plane 算亮度/色度，<1ms
│   ├── guidance/               三个建议生产者（站位 / 参数 / 2s 兜底）
│   ├── capture/Camera2Controller.kt  Camera2 采集 + 参数下发 + 快门
│   └── telemetry/EventQueue.kt  R08：先落盘再上报，离线补报
├── platform/
│   ├── byok/                   ADR-015：Keystore 加密存密钥 + OpenAI 兼容适配 + L1 人脸模糊
│   └── config/CapabilityManifest.kt  ADR-007 兜底 manifest
└── presentation/
    ├── CameraViewModel.kt      编排：采集→感知→几何→建议→仲裁→渲染
    ├── overlay/OverlayView.kt  ADR-003 图层树：轮廓 / 脚印 / 距离 / 指令，含 15% 避让
    └── ui/                     Compose：取景页 / 参数面板 / BYOK 设置页
```

## 4. P0 需求对照

| 需求 | 落点 | 验收口径 |
|---|---|---|
| R01 实时站位引导 | `RangeEstimator` + `StanceSolver` + `OverlayView` | 脚印/虚线框/相对位移；**不显示绝对米数** |
| R02 场景识别 | `RuleSceneClassifier`（主）+ `CloudSceneEnhancer`（BYOK 增强） | 规则 <1ms；云端异步、默认关闭 |
| R03 参数推荐与应用 | `ParamPlanner` + `ParamActuator`（经 `Camera2Controller`） | explain 四要素强制；AE/手动曝光互斥已处理 |
| R04 置信度分档 | `ConfidenceBand` + `SessionState` 能力矩阵 | <0.85 不出站位框；<0.70 不给位移 |
| R05 手动优先 | `AuthorityStore` | 锁定后 AI 写入被拒 + 审计；跨会话持久化 |
| R06 快门不阻塞 | `Camera2Controller.capture()` 只读 plan 快照 | 分析在独立线程，快门路径无锁无 IO |
| R07 EXIF 双轨 | `ExifBar` | AI 值青色、手动值中性白 + 锁标记 |
| R08 三事件埋点 | `EventQueue` | `suggestion_shown` / `suggestion_adopted` / `photo_saved` |

**未做（按 TAD 属 v1.1+）**：构图线 / 海平面 / 姿势提示 / 复盘评分 / 抽屉俯视图 / Replay Harness。

## 5. 几处偏离文档的实现决策

1. **参数建议不进首屏指令位**。TAD ADR-009 说 top-1 上屏，但 PRD 屏 1 要求「任何时刻只显示 1 条**指令**」。
   参数走 EXIF 条与面板，用独立槽位存，不参与 store 竞争——否则兜底建议一占位，参数面板就永远是空的。
2. **`calibrated` 的判定**：端侧几何置信度是按构造可解释的（降级层级 + sigma 的确定映射），标 `true`；
   厂商大模型 self-report 一律 `false` 且 `tier=HINT`，永远进不了强引导。
3. **WB 用 AWB 预设而非色温增益**：`CONTROL_AWB_MODE` 全机型可用，`COLOR_CORRECTION_GAINS` 在多数机型上不生效。
   3200/4500/5600/6500K 映射到最接近的预设，这是诚实的降级。
4. **人脸检测只服务两件事**：多人计数、BYOK 上传前模糊。不参与测距（ADR-011）。
5. **摘掉了 `androidx.work` 带进来的三个权限**（`RECEIVE_BOOT_COMPLETED` / `WAKE_LOCK` / `FOREGROUND_SERVICE`）。
   我们从不调度后台任务，相机应用声明「开机自启」在审核与备案时是明确的减分项。
   最终权限只剩 `CAMERA` / `INTERNET` / `ACCESS_NETWORK_STATE`（已用 `dumpsys package` 验证）。

## 6. 已知问题

**① 包体 62MB，超预算 2.5 倍。** 拆解（arm64 release）：

| 项 | 大小 |
|---|---|
| `libxeno_native.so`（人脸检测引擎） | 21.6MB |
| `libface_detector_v2_jni.so` | 8.5MB |
| dex | 28MB（未开 R8） |
| 姿态模型（2 个 tflite） | 5.7MB |

三条路：a) 开 R8（dex 可砍到 8–10MB，预计总包 ~40MB）；b) 砍掉 face-detection（省 30MB，代价是失去多人计数与 L1 人脸模糊）；
c) 接受 62MB。**建议 a + 保留人脸检测**，因为 L1 模糊是 ADR-006 的合规前提，不该为体积牺牲。

**② 兜底态下参数不可一键应用。** 按 TAD §5.5 状态矩阵，`Fallback` 行「参数建议 ❌」。
后果：场景里一直没人（或姿态检不出）时，参数面板永远是「建议仅供参考」。
这是**规格本身的取舍**，不是 bug，但产品侧要想清楚——建议 v1.1 把「兜底」与「参数可用性」解耦。

**③ 分析线程每 1–3 秒触发一次 GC**（模拟器实测）。每帧新建 `InputImage` / lambda / 中间集合。
真机 S/A 级可能没问题，B 级机需要复用对象池。M0 机型矩阵必须带上这条。

**④ 没有 Replay Harness（ADR-012）。** 几何改动目前只能靠 48 条单测兜底，
真机回归仍然缺手段——这是 TAD 里 W2 的硬性插入项，不是可选项。

**⑤ 服务端不存在。** `config` 与 `telemetry` 都没有，`EventQueue` 的 endpoint 传 `null`，
事件只落本地 `files/events.jsonl`。埋点「上报成功率」现在无意义。

## 7. 模拟器实测

| 项 | 结果 |
|---|---|
| 启动 | 冷启动到首帧 <1.2s（模拟器，非真机基线） |
| 规则标签 | 画面偏绿 → `foliage`，置信 0.56，档位「中」 |
| 兜底 | 2s 后出现「让她站到画面中间」，位置避开快门行（修复过一版锚点） |
| 快门 | 落盘 `files/photos/IMG_*.jpg`，缩略图回填 |
| 参数面板 | 四条滑轨 + `AI 控制` 标记 + 四要素解释条 |
| BYOK | 设置页可输入密钥、切换 DeepSeek/GLM、显示本地用量估算 |

截图见 `docs-viewfinder.png` / `docs-param-panel.png`。
