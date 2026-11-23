# P5 生活助手、模拟家居预览与电视/音乐来源返回

每张 `*-compare.png` 左侧为 Chromium 原型（1280×800 @ 2x），右侧为 Production Debug（2560×1600 @ 320 dpi）。同目录保留 2560×1600 全屏原图 `*-prototype.png` 与 `*-app.png`。

| 状态 | 日间对照 | 夜间对照 | 检查重点 |
|---|---|---|---|
| S18 生活助手首页 | [对照](s18-asst-light-compare.png) | [对照](s18-asst-dark-compare.png) | 左 `1fr`（最近 6 轮对话区贴底 + 欢迎语 + 72 dp 草稿栏 + 92 dp 4 项底栏，禁用项 0.32 透明度并跳过扫描） \| 右 330 dp「模拟家居」6 行设备状态；右上角旧「设置」已移入照护面板 |
| S20 家居确认（取消先亮） | [对照](s20-asst-confirm-light-compare.png) | [对照](s20-asst-confirm-dark-compare.png) | 对话框内全宽确认气泡（2 dp focus 描边，`panel2` 底）、第 0 项「取消」先亮且首轮 1.5× 停留、底栏 0.45 变暗不参与扫描、右侧底部提示「取消：保持现状，不做改变」 |
| S21 家居变化实时预览 | [对照](s21-asst-preview-light-compare.png) | [对照](s21-asst-preview-dark-compare.png) | 焦点移至「降温到 25°C」，右侧「空调」行高亮 focus 描边与叠底，原值 `制冷 26°C` 删除线 + 新值 `制冷 25°C` focus 色、底部提示「预览：确认后会这样变化」 |

## 复现

Chromium 使用 1280×800 视口、DPR 2，加载本地 `archivo.ttf` 与 `noto_sans_sc.ttf`，URL 为 `prototype-v3.html#solo&freeze&mode=<light|dark>&<参数>`：

- S18：`screen=asst`
- S20：`screen=asst&keys=BRTTTTTRTTTTTTTTTBBTTTTTTTB`
- S21：`screen=asst&keys=BRTTTTTRTTTTTTTTTBBTTTTTTTBT`

App 运行于 `BCI_Pad_API_35`（2560×1600、320 dpi）：

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
bci 'freeze on'; bci 'scan auto'; bci 'mode light'; bci 'fake assistant'
bci 'nav asst'; bci 'fake home reset'                                          # S18

# 进入助手输入 -> 常用指令第 2 页 -> 选「我热」-> 绕回末尾「发送」-> 家居确认（S20）
bci 'signal B'; bci 'signal B'
for i in 1 2 3 4 5 6 7 8 9; do bci 'tick'; done
bci 'signal B'; bci 'signal B'; bci 'signal L'; bci 'tick'; bci 'tick'; bci 'signal B'

# 移动焦点到「降温到 25°C」（S21）
bci 'tick'
```

## 测量与剩余差异

- **色值与布局**：
  - 左列 `1fr`（对话区使用 `LazyColumn(reverseLayout = true)` 锚定底部最新确认/输入气泡、72 dp 草稿栏 / 92 dp 四项底栏，间距 12 dp）+ 右列 330 dp「模拟家居」卡片（间距 20 dp）；未配置服务时发送给出「请照护者在照护面板「生活助手服务」中完成连接配置」。
  - `AssistantHomePreview` 基于真实 `SimulatedHome.apply(option, now)` 计算确认预览差异行与执行后 1.3 s `ok` 闪亮；确认项按第 7.7 节固定「取消」在第 0 项、首轮 1.5× 停留、3 轮超时自动取消（仅在 `Lifecycle.State.RESUMED` 下计数），且 2 项确认时左右看 `hold` 延长当前项停留。
  - 电视与音乐页根据 `assistantMediaId != null` 动态显示「返回助手」或「返回娱乐」；电视播放态直接由左右看切换上下台、咬牙打开菜单（取消自动轮播并同步 `ActionHints("上一台", "打开菜单", "下一台")`），从助手打开音乐后自动聚焦播放/暂停，返回助手后焦点落回「输入」。
- **S21 关键几何测量（1280×800 dp，实测 2560×1600 @ 320 dpi）**：
  - **日间**：确认框 `(256.0, 461.5)` dp vs 原型 `(256.0, 461.5)` dp（Δ `0.0 dp`）；聚焦选项按钮 `(331.5, 418.0)` dp vs 原型 `(332.5, 418.0)` dp（Δ `≤ 1.0 dp`）；右侧「空调」预览行 `(362.0, 417.5)` dp vs 原型 `(362.0, 418.0)` dp（Δ `≤ 0.5 dp`）。
  - **夜间**：确认框 `(256.0, 461.5)` dp vs 原型 `(256.0, 461.5)` dp（Δ `0.0 dp`）；聚焦选项按钮 `(332.0, 417.5)` dp vs 原型 `(332.5, 418.0)` dp（Δ `≤ 0.5 dp`）；右侧「空调」预览行 `(362.0, 417.5)` dp vs 原型 `(362.0, 418.0)` dp（Δ `≤ 0.5 dp`）。
- **剩余差异（预期 / 有意 / 后续期处理）**：
  1. **S18 / S20 / S21 对话框顶部微差**：原型 HTML `.ashist` 以固定高度贴底裁剪旧气泡；App 改为 `LazyColumn(reverseLayout = true)` 锚定最新回复/确认于底部，旧多行消息向上自然滚动溢出，确保确认项永不被历史气泡挤出视口。
  2. **模拟器状态栏与悬浮球**：无蓝牙耳机时顶栏如实显示「耳机未连接 / L -- R --」；右缘绿色模拟悬浮球将在 P8 统一换皮。
  3. **调试模拟命令**：`fake assistant` 与 `fake home reset` 仅用于 debug 广播截图对照；正式路径仍走 `AssistantService.submit` 与真实 `SimulatedHome`。

## P5 验证记录

- `./gradlew :app:testProductionDebugUnitTest`：34 个测试套件共 152 项单测全部通过，0 失败 / 0 跳过（含 `AssistantHomePreviewTest` 5 项、`TvPlaybackControlTest` 2 项）。
- `./gradlew :app:connectedProductionDebugAndroidTest`：模拟器 `BCI_Pad_API_35` 仪器测试 8/8 全部通过：
  - `AssistantScreenTest#longConversationKeepsConfirmationVisibleAndSelectable`
  - `AssistantScreenTest#backgroundingDoesNotTimeoutConfirmation`
  - `AssistantBackBehaviorTest#backPressWithCaregiverSheetOverConfirmationClosesSheetOnly`
  - `TvScreenTest#fullscreenBiteExitsFullscreenAndOpensMenuFocusedOnContinue`
  - `TvScreenTest#fullscreenLeftAndRightSwitchChannelsAndExitFullscreen`
  - `TvScreenTest#backPressClosesTvMenuWithoutInvokingOnBack`
  - `ExampleInstrumentedTest#useAppContext`
  - `InputMethodViewModelTest#sameDirectionDoesNotSkipTheHighlightedChatCandidate`
- `./gradlew :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：构建与 lint 全部通过（0 error）。
- `node app/src/test/js/mahjong_engine_test.js`：通过。
