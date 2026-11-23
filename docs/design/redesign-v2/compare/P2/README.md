# P2 首页、呼叫护士、照护面板与就绪状态

每张 `*-compare.png` 左侧为 Chromium 原型（1280×800 @ 2x），右侧为 Production Debug（2560×1600 @ 320 dpi）。同目录保留 2560×1600 全屏原图 `*-prototype.png` 与 `*-app.png`。

| 状态 | 日间对照 | 夜间对照 | 检查重点 |
|---|---|---|---|
| S1 首页（焦点：沟通） | [对照](s1-home-light-compare.png) | [对照](s1-home-dark-compare.png) | 09:41 时钟 + 日期、5 张卡片 1.5:1:1:1:1 比例、图标与焦点样式 |
| S2 首页（焦点：呼叫护士） | [对照](s2-home-call-light-compare.png) | [对照](s2-home-call-dark-compare.png) | 红底呼叫卡展开至 1.5fr、4 dp 底色间隙 + 5 dp 焦点外圈、白色进度条与方向标 |
| S3 呼叫护士倒计时 | [对照](s3-call-countdown-light-compare.png) | [对照](s3-call-countdown-dark-compare.png) | 满幅红底弹层（盖住 View 区、保留顶栏与底栏）、210 dp 白环、动作栏文案 |
| S28 照护面板 | [对照](s28-care-light-compare.png) | [对照](s28-care-dark-compare.png) | 480 dp 右侧反色抽屉、3 开关、信息行、生活助手服务行（默认折叠）、调试行、150 dp 波形 |

## 复现

Chromium 使用 1280×800 视口、DPR 2，加载本地 `archivo.ttf` 与 `noto_sans_sc.ttf`，URL 为 `prototype-v3.html#solo&freeze&mode=<light|dark>&<参数>`：

- S1：`screen=home`
- S2：`screen=home&keys=TTTT`
- S3：`screen=home&keys=TTTTB`
- S28：`screen=home&care`

App 运行于 `BCI_Pad_API_35`（2560×1600、320 dpi）：

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
bci 'freeze on'; bci 'scan auto'; bci 'mode light'; bci 'nav home'   # S1
bci 'tick'; bci 'tick'; bci 'tick'; bci 'tick'                       # S2
bci 'signal B'                                                        # S3
bci 'nav home'; bci 'care on'                                         # S28
```

## 测量与剩余差异

- **色值（误差 ≤ 1）**：日间背景 `#F6F3EF`、夜间背景 `#14110E`、正常卡底 `#FFFFFF` / `#1C1915`、焦点叠加底 `#F8ECE6` / `#3E2C22`、呼叫警报红 `#C8102E` / `#E0243F`、动作栏 `#FCFAF7` / `#0E0C0A`、照护面板反色底 `#1C2230` / `#ECEAE4`，与原型逐通道对比最大误差 ≤ 1。
- **位置与尺寸（误差 ≤ 4 dp）**：
  - S1 / S2 夜间 5 张卡片水平边界与原型完全一致（误差 0.0 dp），含 S2 呼叫卡 4 dp 底色间隙与 5 dp 焦点外圈（929–934 dp、938–1252 dp、1256–1261 dp）；垂直范围 182.0–667.5 dp（日间 181.0–671.0 dp，误差 ≤ 1.0 dp）。
  - S3 倒计时白环垂直范围：原型 188.0–397.5 dp，App 192.0–401.5 dp（差 4.0 dp，位于 4 dp 误差阈值内）。
  - S28 照护面板左边缘均为 800.0 dp（宽 480.0 dp，误差 0.0 dp）。
- **剩余差异（预期 / 有意 / 后续期处理）**：
  1. **S3 原型层叠瑕疵**：原型 HTML 中 `.tile.f` 带有 `z-index: 2` 而 `#overlay` 未设 `z-index`，导致原型 S3 截图中呼叫护士卡片露在红底弹层之上；App 按第 5 节层级规范正确盖住整个 View 区。
  2. **模拟悬浮球**：App 默认显示悬浮控制球（屏幕右缘绿边圆钮），待 P8 统一换皮；照护面板打开时自动隐藏，不遮挡抽屉。
  3. **真实设备 / 服务状态**：模拟器无蓝牙耳机且未配置服务器，状态栏与照护面板如实显示「耳机未连接 / 正在扫描…」「L -- R --」「内置四分类模型」「smu-brain.online · 未配置」「生活助手服务：未配置」及真实 `EegStereoView` 波形组件；原型为写死的已连接示例值与 Canvas 假波形。
  4. **生活助手服务编辑**：按确认的交互方式，默认折叠保持与原型 S28 一致，点击「生活助手服务」行原地展开地址、连接码、测试/保存与调试草稿编辑区。
  5. **照护面板内「模拟：妈妈发起视频来电」**：按钮已按仅 debug 包规则就位，`fake incoming` 将在 P4 接入视频层后生效。

## P2 验证记录

- `./gradlew :app:testProductionDebugUnitTest`：30 个测试套件共 137 项单测全部通过（新增 `NurseCallStateTest` 3 项、`FourClassActionDetectorTest` 和 `SixActionDetectorTest` 只读 readiness 透传测试各 1 项，更新 `AppNavigationTest` 为 5 入口及演示包直进联系人列表）。
- `./gradlew :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：全部构建与 lint 通过（0 error）。
- `node app/src/test/js/mahjong_engine_test.js`：通过。
- 模拟器行为复核：照护面板打开时拦截信号与 tick 并弹提示；呼叫护士左看取消后焦点留在呼叫护士、右看立即呼叫后显示 `✓` 且任意动作关闭；从生活助手返回首页后焦点停在「生活助手」。
