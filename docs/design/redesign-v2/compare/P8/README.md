# P8 视觉验收与换皮自查（S27 + 电视菜单 + §7.13 页面）

## S27 电视观看与电视菜单（原型 vs App）

| 状态 | 日间对照 | 夜间对照 | 说明 |
|---|---|---|---|
| S27 电视 | [s27-tv-light-compare.png](s27-tv-light-compare.png) | [s27-tv-dark-compare.png](s27-tv-dark-compare.png) | 1280×800@2x（2560×1600, 320 dpi），`freeze on` + `scan auto` + `nav tv` |
| S27 电视菜单 | [s27-tv-menu-light-compare.png](s27-tv-menu-light-compare.png) | [s27-tv-menu-dark-compare.png](s27-tv-menu-dark-compare.png) | `signal B` 打开全舞台层「电视菜单」（`LocalStageOverlay`，无双重内边距） |

### 色彩与几何采样核对（误差要求 ≤ 2 RGB / ≤ 4 dp）

#### 1. S27 电视观看

| 采样点（逻辑 dp） | 日间原型 RGB | 日间 App RGB | 日间最大差值 | 夜间原型 RGB | 夜间 App RGB | 夜间最大差值 |
|---|---|---|---|---|---|---|
| 页面底色 `(10, 10)` | `(246, 243, 239)` | `(246, 243, 239)` | `0` | `(20, 17, 14)` | `(20, 17, 14)` | `0` |
| 动作栏底色 `(640, 740)` | `(252, 250, 247)` | `(252, 250, 247)` | `0` | `(14, 12, 10)` | `(14, 12, 10)` | `0` |
| 屏幕中心渐变 `(640, 350)` | `(25, 33, 39)` | `(25, 33, 39)` | `0` | `(25, 33, 39)` | `(25, 33, 39)` | `0` |
| 屏幕左上径向渐变 `(400, 200)` | `(41, 54, 64)` | `(41, 54, 63)` | `1` | `(41, 54, 64)` | `(41, 54, 63)` | `1` |
| 频道条中心高亮底色 `(640, 610)` | `(252, 241, 238)` | `(252, 241, 238)` | `0` | `(55, 39, 29)` | `(56, 39, 30)` | `1` |
| 频道条普通格底色 `(140, 610)` | `(255, 255, 255)` | `(255, 255, 255)` | `0` | `(28, 25, 21)` | `(28, 25, 21)` | `0` |
| 频道条中心描边 `(640, 577)` | `(217, 80, 42)` | `(217, 80, 42)` | `0` | `(255, 140, 94)` | `(255, 140, 94)` | `0` |

#### 2. S27 电视菜单（打开态）

| 采样点（逻辑 dp） | 日间原型 | 日间 App | 最大差值 | 夜间原型 | 夜间 App | 最大差值 |
|---|---|---|---|---|---|---|
| 遮罩中心 `(640, 350)` | `(238, 236, 233)` | `(239, 236, 233)` | `1` | `(19, 17, 15)` | `(20, 17, 15)` | `1` |
| 第 0 项焦点填充 `(120, 560)` | `(248, 235, 230)` | `(248, 236, 230)` | `1` | `(61, 44, 34)` | `(62, 44, 34)` | `1` |
| 第 1 项卡片底色 `(640, 595)` | `(255, 255, 255)` | `(255, 255, 255)` | `0` | `(28, 25, 21)` | `(28, 25, 21)` | `0` |
| 第 2 项卡片底色 `(1040, 595)` | `(255, 255, 255)` | `(255, 255, 255)` | `0` | `(28, 25, 21)` | `(28, 25, 21)` | `0` |
| 左侧边距遮罩 `(36, 595)` | `(246, 243, 240)` | `(246, 243, 240)` | `0` | `(19, 16, 14)` | `(20, 17, 15)` | `1` |
| 菜单按钮行垂直位置（逻辑 dp） | `top=538.0, bottom=651.5, h=113.5, above_bar=36.5` | `top=538.0, bottom=651.5, h=113.5, above_bar=36.5` | `0.0 dp` | — | — | — |
| 第 0 项外框左边距（含 1.025× 缩放） | `43.5 dp` | `43.5 dp` | `0.0 dp` | — | — | — |
| 标题字形尺寸与左边距（`40.sp`） | `left=52.0 dp, h=37.5 dp` | `left=52.5 dp, h=37.0 dp` | `0.5 dp` | — | — | — |

### 确认的有意差异
- **频道列表数量**：按用户确认，App 保留现有全部 18 个央视频道（`TV_CHANNELS`，含暂不可用频道）及原顺序，底部 5 格频道条仍以当前频道居中并循环展示（CCTV-1 时为 `CCTV-16 | CCTV-17 | CCTV-1 | CCTV-2 | CCTV-3`；原型静态示例为 9 个频道，显示 `CCTV-13 | CCTV-15 | CCTV-1 | CCTV-2 | CCTV-4`）。
- **状态栏耳机状态与悬浮球**：模拟器未连接真机耳机（显示 `耳机未连接`、`L -- · R --`），右侧保留默认开启的模拟悬浮球入口（已换皮为珊瑚主题）。

## §7.13 换皮页面日/夜自查

| 页面 / 组件 | 日间 / 夜间自查结果 |
|---|---|
| 电视与电视菜单（`TvScreen`） | 通过：`LocalStageOverlay` 全舞台层（48×34 dp 内边距、40 sp/900 标题）与原型 0.0 dp 按钮对齐；`ScanCursor` 菜单扫描与 `graceMs` 延迟补偿；菜单打开/关闭/返回键均同步调用 `onBoundary()` 失效未决耳机指令；每次 `AndroidView` 退出组合（含 `freeze on/off` 循环）通过 `onRelease = ::releaseTvWebView` 及局部全屏清理回调释放 `WebView`。 |
| 音乐（`MusicScreen` + `MusicScanState`） | 通过：`MusicScanState` 统一驱动控制栏、歌单、音量、设置（无曲目时自动跳过禁用项）、删除列表的 `ScanCursor` + `runScanTimer` + `repeatOnLifecycle(RESUMED)`；含 400 ms `graceMs` 延迟补偿、同向左/右看重启定时与停留条、暂停边界重置及 `onBoundary()` 同步失效；分模式隔离游标提交（歌单扫描期间点底栏不污染 `playlistCursor`）且空曲库保持控制栏焦点不陷入 0 长度游标；按钮高 76 dp 保证与停留条无重叠。 |
| 速度练习（`SpeedPracticeScreen`） | 通过：12 题练习卡片、目标绿标与焦点珊瑚框、日夜切换清晰。 |
| 迷宫（`MazeScreen`） | 通过：嵌入 `PatientScaffold` 动作栏，墙体采用 `colors.dim(0.45) over bg`、路径采用 `colors.panel`；终点改为实心 `colors.ok` 五角星 + `colors.text` 描边，与六分类圆形/方形障碍明确区分，日夜双版清晰可辨。 |
| 向上贪吃蛇（`SnakeClimbScreen`） | 通过：嵌入 `PatientScaffold` 动作栏，`SnakeBoard` 启用 `clipToBounds()` 防止顶行景物越界遮挡标题，后台自动暂停移动计时，返回按钮文案统一为「返回娱乐」。 |
| 设备与模型（`CollectionScreen` / `SignalMonitorScreen` / `BleScanScreen`） | 通过：迁移至 `BciComponents`（`GlassPanel`、`BciButton`、`BciBadge`、`BciTextField`）与 `LocalBciColors`，`EegStereoView` 支持 `setPalette(BciColors)` 随主题更新。 |
| 悬浮球与悬浮波形窗（`FloatingControlBall` / `FloatingEegSignalWindow`） | 通过：统一使用 `LocalBciColors`（珊瑚主题焦点、面板、边框、状态色）与 `EegStereoView.setPalette(LocalBciColors.current)`。 |
| `Aurora` 别名清理 | 通过：删除 `AuroraComponents.kt` 与 `Theme.kt` 中全部 `Aurora*` 及旧颜色别名，`git grep "Aurora" app/src/` 为 0 处命中。 |

## 验证命令与结果

- `./gradlew :app:testProductionDebugUnitTest :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：通过（`UNIT: files=37 tests=168 failures=0 errors=0 skipped=0`，含新增 `MusicScanStateTest` 全部分支与边界回归）
- `node app/src/test/js/mahjong_engine_test.js`：通过
- `node app/src/test/js/interaction_contract_test.mjs`：通过（5/5）
- `./gradlew :app:connectedProductionDebugAndroidTest`（`BCI_Pad_API_35`）：通过（`ANDROID: files=1 tests=24 failures=0 errors=0 skipped=0`，含 `TvScreenTest` 菜单边界指令失效、全舞台边距与抖动点击、`freeze` 循环释放 WebView 等新增回归测试）
- `git grep -n "Aurora" app/src/`：0 处命中
