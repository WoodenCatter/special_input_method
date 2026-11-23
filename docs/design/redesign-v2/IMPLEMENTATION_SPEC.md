# BCI Agent 床旁交互重构 v3 · 施工规格

版本：2026-11-23。基线源码：`special_input_method` @ `e14c3f3`。
配套原型：同目录 `prototype-v3.html`（下称“原型”）。

## 0. 怎么用这份文档

1. **原型是唯一的视觉与行为依据。** 本文列出原型里的数值和规则，方便直接写成 Compose 代码。两者冲突时以原型为准，第 13 节“有意差异”除外。
2. **尺寸 1:1。** 目标设备是横屏 Pad，2560×1600 px、320 dpi，等于 1280×800 dp。原型的设备画布正好是 1280×800 CSS px，所以原型里的 `px` 直接当 `dp` 用，字号直接当 `sp` 用（见 3.2，忽略系统字体缩放）。
3. **先读后写。** 开工前完整读一遍原型的 `<style>` 和 `<script>`。各页面的状态机在这些函数里：
   - 总入口：`ctx()`、`labels()`、`choose()`、`tickBody()`、`input()`、`go()`
   - 列表：`contactsItems()`、`cactItems()`、`commItems()`、`asstItems()`、`videoItems()`、`settingsItems()`
   - 流程：`openComposer()`、`compBack()`、`doSend()`、`asstSubmit()`、`asstDecide()`、`startVideo()`、`incoming()`、`endCall()`、`backFromMedia()`、`updateGallery()`
4. **原型里的模拟只用于演示**（第 14 节）。正式版接现有服务：沟通服务器、VideoSession、生活助手服务、SimulatedHome、电视页、音乐库。
5. **不确定就问，不要自行发挥。** 原型没覆盖、本文也没写清的地方，先列问题。

## 1. 范围

### 要做
- **主题与外壳**：珊瑚配色（日间 / 夜间 / 自动）、状态栏、底部动作栏、视线方向反馈、放松 / 就绪指示、提示条（toast）。
- **统一扫描引擎 `ScanCursor`**：自动和手动两种扫描方式、停留计时、延迟补偿，并保证计时器永不停止。
- **首页**：5 个入口，含呼叫护士。
- **实时沟通**：联系人列表（含「当面说话」）、通讯方式、文字消息、视频通话、邀请家属。
- **统一输入界面**：当面说话、文字消息、生活助手三种模式共用一套。
- **生活助手**：对话、草稿、发送、家居操作确认与预览、点播电视和音乐后返回。
- **其他页面**：娱乐画廊、应用设置（含扫描方式、显示模式）、电视观看页、照护面板。
- **确认框**：统一成两项自动扫描，原生页面和 WebView 棋牌都改。
- **「说出」**：系统朗读 + 全屏展示。
- 同步更新对应的规范文档与测试。

### 不要碰
- EEG 采集、对齐、滤波、ONNX 推理，`TemporalEvidenceAccumulator` 的判定逻辑与全部阈值，模型与训练服务器协议。
- 沟通服务器的 HTTP / WebSocket 协议、本地待发缓存与重试逻辑（`CommunicationViewModel` 的 `queue` / `flushOutbox` / `persist` / `restore`）、`VideoSession` 的信令与 LiveKit 媒体逻辑。
- 生活助手服务的协议与校验（`AssistantService`）、`SimulatedHome` 的规则。
- 拼音恢复、Rime、LLM 预测的算法与排序（只改每页数量和导航项位置）。
- 游戏规则引擎和 Godot 包。

## 2. 已定决策

| 决策 | 结论 |
|---|---|
| 配色 | 只做**珊瑚**。原型里的钴蓝、紫藤不实现。 |
| 显示模式 | 自动 / 日间 / 夜间，默认自动：7:00–19:00 为日间。 |
| 扫描方式 | **默认自动**。把 `ScanSettings.readMode` 的默认值从 MANUAL 改成 AUTO；已保存过偏好的用户沿用其设置。 |
| 同向看跳过一项 | **删除**源码 `ScanInputPolicy` 这条规则：T9 候选阶段左右看只改方向，不跳项。 |
| 来电焦点 | **「接听」先亮**。这是“取消类先亮”规则唯一的例外。 |
| 「当面说话」 | 放在联系人列表第一位，首页不单独设入口。 |
| 助手输入 | 输入界面右侧直接「发送」，不再“完成 → 回底部 → 发送”。「返回」把文字留在草稿里。 |
| 画廊 | 只用于娱乐页。 |
| 确认框 | 两项自动扫描，取消类先亮且第一次停留 1.5 倍，三轮不选按取消处理（见 6.5）。 |
| 「说出」* | 接 Android `TextToSpeech`（zh-CN，语速 0.9）。不可用时仍全屏显示，并提示“朗读不可用”。 |
| 呼叫护士* | 只做本地：全屏警报 + 循环警报音，直到关闭。 |
| 照护面板* | 长按状态栏「照护」2 秒打开。首页不显示波形和设备列表。 |

\* 这三项是默认决定，产品方没有提出异议。如有不同意见以产品方为准。

## 3. 基础

### 3.1 字体
- **拉丁字母与数字**：Archivo 500 / 700 / 800。中文：Noto Sans SC 可变字体，400 / 500 / 700 / 900。
- 字体文件放 `res/font/`，并在 `LICENSES/` 中补 OFL 许可。API 26 以下回退系统字体。
- **原型写法对应**：`font: 700 36px var(--page-latin)` 等于 Archivo 700、36 sp；没写 latin 的一律用 Noto Sans SC。`letter-spacing` 的 em 值换算成 `TextStyle.letterSpacing = x.em`。

### 3.2 字号不随系统字体缩放
所有字号用 `with(LocalDensity.current) { N.dp.toSp() }` 生成，或在根部把 `fontScale` 固定为 1。

### 3.3 图标与插图
- **图标来源**：Lucide（ISC 许可），转成 VectorDrawable，放 `res/drawable/ic_lucide_<name>.xml`。用到的有：
  - 首页与导航：message-square-text、bot、gamepad-2、sliders-horizontal、bell-ring、arrow-left、house、user-plus、video
  - 设置：volume-2、sun、gauge、arrow-left-right、sun-moon、circle-dot、target
  - 音乐控制：skip-back、play、pause、skip-forward
- **描边粗细**：首页图标 52 dp、stroke 1.75；通讯方式卡图标 46 dp、stroke 1.75；设置页图标 28 dp、stroke 2。
- **画廊占位线稿**：原型 `ART` 对象里 9 个 SVG（viewBox 320×200、stroke 9、圆头圆角）转成 `res/drawable/gallery_art_<id>.xml`。

## 4. 设计 Token（珊瑚）

新建 `ui/theme/BciColors.kt`，通过 `CompositionLocal` 提供。`Theme.kt` 里的 Aurora 色值全部替换；旧名（`AuroraViolet` 等）先做别名指向新 token，最后一期再删。

| Token | 日间 | 夜间 | 用途 |
|---|---|---|---|
| bg | `#F6F3EF` | `#14110E` | 页面底 |
| panel | `#FFFFFF` | `#1C1915` | 卡片 |
| panel2 | `#FBF9F6` | `#231F1A` | 焦点卡底、候选格底 |
| mag | `#FCFAF8` | `#17140F` | 放大镜、侧栏预览、设置说明 |
| line | `#E5DED5` | `#312B25` | 边框、分隔线 |
| text | `#211C18` | `#EFE8DF` | 正文 |
| muted | `#6A6159` | `#A79D92` | 次要文字 |
| dim | `#ADA59C` | `#625A51` | 占位、未激活 |
| focus | `#D9502A` | `#FF8C5E` | **只用于**“光标在这里” |
| focusTint | focus @ 8% | focus @ 12% | 焦点卡底色叠加 |
| focusRing | focus @ 20% | focus @ 28% | 焦点外圈 |
| bar | `#FCFAF7` | `#0E0C0A` | 动作栏底 |
| ov | `#F6F3EF` @ 97% | `#14110E` @ 97% | 弹层底 |
| ok | `#138A5A` | `#4CCB8A` | 已写入、已送达、就绪、在线 |
| okTint | ok @ 14% | ok @ 16% | 选中闪烁、家居执行完成 |
| alarm | `#C8102E` | `#E0243F` | **只用于**呼叫护士和紧急常用语 |
| onAlarm | `#FFFFFF` | `#FFFFFF` | 警报底上的文字 |
| careBg / careText | `#1C2230` / `#EDEBE6` | `#ECEAE4` / `#1B1E24` | 照护面板，始终与当前模式反色 |
| careLine | 白 @ 14% | 黑 @ 12% | 照护面板分隔线 |
| careBtn / careBtnText | `#EDEBE6` / `#1C2230` | `#1B1E24` / `#ECEAE4` | 照护面板按钮 |
| careOff | `#545C6B` | `#A3A8B0` | 开关关闭 |

**视频页专用色**：日夜都相同，见 7.5。

- **阴影**：日间卡片用约 `shadow(2.dp)`，夜间无阴影。
- **画廊暗色渐变**：从下往上 `rgba(6,8,12,.86)` 0% → `.55` 34% → `0` 62%。
- **日夜切换**：颜色用 300 ms `animateColorAsState` 过渡。
- **头像底色**：`hsl(h, 42%, 48%)`，h 取自联系人 ID 的哈希（取模 360）。原型中写死为：妈妈 12、小雨 200、张医生 160、李阿姨 300。

## 5. 全局外壳与通用组件

所有患者页面都放进同一个 `PatientScaffold`，由 `MainActivity.AppContent` 统一包裹。照护者专用的“设备与模型”（CollectionScreen、DeviceFlow）不套外壳。

```
┌ StatusBar 56dp ──────────────────────────────────────────┐
│ View 区：padding 水平 28 / 垂直 20                        │
│ Overlay 层：与 View 区同一矩形（top 56, bottom 112）      │
├ ActionBar 112dp ─────────────────────────────────────────┤
└──────────────────────────────────────────────────────────┘
最上层：GazeFeedback（边缘光、中心脉冲）、Toast、CaregiverSheet
视频通话层：盖住 View 区与 Overlay 层，但不盖 StatusBar 和 ActionBar（7.5）
```

### 5.1 StatusBar（顶部 56 dp）
- **整体**：底边 1 dp line，左右内边距 28。
- **左侧面包屑**：20 sp muted，当前页用 text 色加粗。文案按原型 `crumb()`，例如：
  - `主页 › 沟通`
  - `沟通 › 妈妈 › 文字消息`
  - `沟通 › 当面说话`
  - `生活助手 › 输入`
  - `生活助手 › 看电视`
  - `来电 › 妈妈`
- **右侧胶囊**：间距 10。每个胶囊 15 sp Archivo、内边距 5×12、1 dp line 描边、全圆角。从左到右依次是：
  1. 连接状态：已连接为 ok 色点 +“耳机已连接”；数据中断为 alarm 色点 +“信号中断”；未连接为 dim 色点 +“耳机未连接”。
  2. 电量：`L 86% · R 82%`。
  3. 扫描：自动时显示 `每项 1.5 s`，手动时显示 `手动扫描`。
  4. 显示模式：`日间` / `夜间`，自动模式下追加“ · 自动”。
  5. 「照护」按钮：text 色底、bg 色字，长按 2 秒打开照护面板。

### 5.2 ActionBar（底部 112 dp）
- **整体**：顶边 1 dp line，bar 色底。三列宽度比 1 : 1.15 : 1，中列左右各 1 dp line。每列左右内边距 30，元素间距 18。
- **左列（左看）**：48 dp 圆形「◀」（2 dp line 描边，22 sp/800 dim 色）；上方小标签“左看”（15 sp muted），下方是当前含义（25 sp/700，最宽 300，超出省略）。
- **右列（右看）**：左列的镜像。
- **中列（咬牙）**：从左到右依次是
  - 三个就绪点（12 dp，间距 6）：就绪时为实心 ok 色；锁定时为空心 dim 描边，每收到一个合格静息窗点亮一个。
  - 状态文字（44 dp 宽，15 sp）：ok 色“就绪”，或 muted 色“放松”。
  - 小标签“咬牙”和当前含义。
- **当前方向**：扫描方向朝哪边，哪一列高亮：圆形改为实心 focus 底 + bg 色符号，小标签后追加 focus 色“ · 当前方向”。`direction = 0` 时两边都不高亮。
- **命中闪烁**：对应列 focusTint 底，持续 350 ms。
- **文案**：完全按原型 `labels()`。

| 模式 | 左 | 咬牙 | 右 | direction |
|---|---|---|---|---|
| 自动扫描列表 | 向左轮转 | 选「<高亮项>」 | 向右轮转 | ±1 |
| 娱乐画廊（自动） | 向左滑 | 选「<高亮项>」 | 向右滑 | ±1 |
| 两项确认 / 助手家居确认只剩两项 | 多停一会 | 选「<高亮项>」 | 多停一会 | 0 |
| **手动扫描（任何列表）** | 上一项 | 选「<高亮项>」 | 下一项 | 0 |
| 输入界面第一层（字母块） | 正扫左侧时选「ABC」；否则切到左侧 | 已选字母块时去选拼音；否则打开常用语 | 正扫右侧时选「MNO」；否则切到右侧 | 正扫侧 |
| 呼叫护士倒计时 | 取消呼叫 | 立即呼叫 | 立即呼叫 | 0 |
| 呼叫已发出 | 关闭 | 关闭 | 关闭 | 0 |
| 助手正在回复 | （无） | 正在回复… | （无） | 0 |
| 电视观看 | 上一台 | 打开菜单 | 下一台 | 0 |
| 游戏直接控制 | 各游戏提供 | 各游戏提供 | 各游戏提供 | 0 |

- **实现**：新建 `ActionHints(left, bite, right, direction: Int)`。页面通过 `LocalActionHints` 或 ViewModel 的 `StateFlow` 上报，ActionBar 只负责显示。

### 5.3 就绪状态（只读）
- `BciController` 新增 `readiness: StateFlow<Readiness(locked, restStreak, required)>`，数据取自 `TemporalEvidenceAccumulator.Result`（type、lockedClass、restStreak），并透传到 `DetectorResult` / `SixDetectorResult`。**只加字段，不改任何判定。**
- 只有真实耳机连接且控制已启用时才显示锁定状态。悬浮球的模拟输入不触发锁定。

### 5.4 GazeFeedback
在 `MainActivity.routeSignal` / `routeHeadsetSignal` 分发动作前调用 `GazeFeedback.emit(signal)`：
- **左看**：左缘 36 dp 宽渐变条，从 focus 色到透明，透明度 0.85→0，450 ms ease-out。
- **右看**：右缘，同上。
- **咬牙**：屏幕中心 160 dp 圆环，6 dp focus 色描边，缩放 0.6→1.5，透明度 0.9→0，450 ms。
- **提示音**：照护面板里可关，默认开。用 `AudioTrack` 合成正弦波：左 440 Hz、右 660 Hz、咬牙 880 Hz，160 ms，峰值增益 0.14。
- **照护面板日志**：记录最近 7 条动作，格式 `HH:mm:ss  左看  → 主页 › 娱乐`。
- 照护面板打开期间：不反馈、不分发。

### 5.5 焦点样式 `Modifier.scanItem(focused, direction, picked)`

| 状态 | 样式 |
|---|---|
| 默认 | panel 底、2 dp line 描边、圆角 16（各组件可覆盖）、日间加阴影 |
| 焦点 | panel2 底叠加 focusTint；focus 描边；外圈 5 dp focusRing；缩放 1.025；z 序置顶 |
| 停留进度条 | 只在自动扫描时显示。焦点项底部画 5 dp 高、圆角 3 的 focus 条，左右各距 14、距底 8；按停留时长线性变满；向右扫时从左往右长，向左扫时从右往左长 |
| 方向标记 | 右上角（top 8、right 12），16 sp/700 Archivo focus 色「→」或「←」 |
| 选中闪烁 | okTint 底 + ok 描边，450 ms |
| 导航项 | 透明底、虚线描边、muted 文字、无阴影；获得焦点时文字变 text 色 |
| 不可用项 | 透明度 0.32，不参与扫描，不能触摸 |
| 过渡 | 140 ms ease-out |

例外：首页卡片和确认框大卡不缩放，见 7.1、6.5。

### 5.6 Toast
- **位置**：水平居中，距底 128 dp。
- **样式**：text 色底 + bg 色字，内边距 12×22，圆角 12，20 sp 单行；成功类提示用 ok 底白字。
- **时长**：1.6 s 后用 200 ms 淡出。

### 5.7 弹层
- **统一规格**：铺满 View 区，ov 底，内边距 34×48，元素间距 22。底部选项行高 118，间距 16，30 sp/700。

| 弹层 | 内容 | 初始焦点 |
|---|---|---|
| 说出 | 小字“正在朗读 · 照护者请看这里”；整句 84 sp/900 居中；底部三项：返回修改 / 再说一遍 / 清空，开始新的一句 | 「再说一遍」 |
| 确认 | 见 6.5 | 取消类 |
| 调节 | 标题 40 sp/900；数值 120 sp/800 Archivo focus 色；进度条高 30；底部三项：减小 / 增大 / 完成 | 「增大」 |
| 电视菜单 | 继续观看 / 重新加载 / 返回娱乐（从助手进入时为「返回助手」） | 「继续观看」 |
| 呼叫护士 | 见 7.1 | — |

## 6. 扫描引擎 `ScanCursor`

新建 `model/ScanCursor.kt`，写成可单测的纯 Kotlin。所有扫描列表都用它，包括视频通话按钮和助手底栏。输入界面第一层的双翼复用它的计时与延迟补偿。

```kotlin
data class ScanCursor(
  val size: Int, val index: Int, val direction: Int /* ±1 */,
  val previousIndex: Int = -1, val advancedAtMs: Long = 0,
  val ticks: Int = 0 /* 确认类使用 */
)
```

### 6.1 计时（自动扫描）
- **前进**：每隔 `intervalMs`（1100–3000，默认 1500）前进一项，同时记录 `previousIndex` 和 `advancedAtMs`。
- **计时器永不停止**：每次 tick 放在 `try/finally` 里，并在 finally 中重排下一次。每个页面上下文用一个 `while(isActive){ delay; tick }` 协程，不要用 `LaunchedEffect(index)` 那种每次跳项都重启的写法。

### 6.2 方向（自动扫描）
- 左看 `direction = -1`，右看 `+1`，都**不跳项**，并让当前项重新计时（进度条归零）。
- 如果已经是该方向，同样重新计时。

### 6.3 延迟补偿（自动扫描）
- **规则**：咬牙时，若 `now - advancedAtMs < graceMs` 且 `previousIndex >= 0`，执行 `previousIndex` 那一项，并提示“已按上一项执行（延迟补偿）”。
- **参数**：`graceMs` 默认 400，存 `AppUiSettings`。
- **范围**：输入界面第一层的同侧选块同样适用。

### 6.4 上下文切换
切页、开关弹层、输入界面换阶段或翻页、开关照护面板、视频层出现或消失时，统一执行：
1. 作废尚未投递的识别结果，沿用 `BciCommandGate.invalidate` 和 `InputContextBoundary`；
2. `direction` 设为 +1；
3. `previousIndex` 设为 -1；
4. 设置初始 index：按该页规则，或按第 8 节的返回焦点；
5. 重新计时。

照护面板打开时暂停，关闭后从当前项重新计时。

### 6.5 确认（两项扫描）
- **适用范围**：清空句子、离开游戏、挂断视频、删除音乐、棋牌 CONFIRM，以及生活助手家居确认（多项，规则相同）。
- **选项与初始焦点**：取消类在最前（左），初始焦点为 0，**第一次停留 1.5 倍**，进度条时长同步 1.5 倍。
- **轮转**：之后自动轮流。只有两项时，左看、右看只重新计时，动作栏写“多停一会”。
- **超时**：累计前进 `3 × 项数` 次仍未选，按取消类处理，并提示“三轮都没有选择，已按「X」处理”。
- **例外**：来电的「接听 / 拒绝」**接听在前**，没有三轮自动拒绝，未接超时由服务器决定。
- **布局（两项确认弹层）**：
  - 标题 44 sp/900，副标题 22 sp muted。
  - 左右两张大卡，间距 20：圆角 22、描边 3、内边距 34；未聚焦时透明度 0.55，聚焦时为 1，**不缩放**。
  - 卡片顶部小字“先亮 · 默认”/“后亮”，22 sp/700，聚焦时为 focus 色。
  - 主文字 72 sp/900，说明 24 sp；右卡文字右对齐。
  - 进度条左右各距 34、距底 16、高 7。
  - 最底一行说明：“两项自动轮流高亮，亮到哪项咬牙就选哪项 · 三轮不选按「X」处理”。

### 6.6 手动扫描
- **何时生效**：设置「扫描方式」为手动时。
- **普通列表**：
  - 不自动前进，没有停留进度条，也没有延迟补偿。
  - 左看 index - 1，右看 index + 1，首尾循环。
  - 咬牙执行当前项。
  - 动作栏显示“上一项 / 下一项”，direction = 0。
- **确认框**：同样手动移动，**不会三轮自动取消**，因为不会自己变化。
- **输入界面第一层（字母块双翼）**：**始终自动扫描**，不受此设置影响。从第二层起（拼音、选字、预测、常用语）按扫描方式处理。
- **适用范围**：视频通话按钮、助手底栏与家居确认也适用。

### 6.7 删除 `ScanInputPolicy`
- 删除 `communication/ScanInputPolicy.kt`，以及 `InputMethodViewModel.handleSignal` 里“同方向信号跳过当前候选”的分支。
- 删除 `ScanInputPolicyTest.kt`，另在 InputMethodViewModel 测试中加一条“同向看不跳项”。

## 7. 页面规格

### 7.1 首页（HomeScreen 重写）
- **整体**：两行，间距 22。
  - **第一行**：时钟 `HH:mm`（84 sp/800 Archivo，字距 -0.01 em）+ 日期 `11月23日 星期一 · 上午`（26 sp muted，基线对齐，间距 22）。
  - **第二行**：5 张卡横排，间距 18。**默认等宽，光标所在卡为 1.5、其余为 1**，用 300 ms ease-out 过渡：`Modifier.weight(animateFloatAsState(if (focused) 1.5f else 1f, tween(300, easing = FastOutSlowInEasing)).value)`。
- **卡片**：圆角 24，内边距 24 / 24 / 26，左对齐。
  - 左上：52 dp 图标，muted 色，聚焦时为 focus 色。
  - 底部：标题 38 sp/900（行高 1.1，不换行），间距 8，说明 18 sp muted（行高 1.35，可换行）。
  - **不缩放**，其余焦点样式照旧。
- **顺序**（初始焦点为沟通）：

| # | 标题 | 说明 | 图标 | 进入 |
|---|---|---|---|---|
| 0 | 沟通 | 家属消息 · 视频 · 当面说 | message-square-text | 联系人列表 |
| 1 | 生活助手 | 问答 · 点播 · 家居 | bot | 生活助手 |
| 2 | 娱乐 | 游戏 · 音乐 · 电视 | gamepad-2 | 娱乐画廊 |
| 3 | 设置 | 音量 · 亮度 · 扫描 | sliders-horizontal | 应用设置 |
| 4 | 呼叫护士 | 选中后 5 秒内可取消 | bell-ring | 呼叫弹层 |

- **呼叫护士卡**：
  - 整卡 alarm 实心底，图标和文字用 onAlarm 色，说明透明度 0.88。
  - 聚焦时加 5 dp bg 间隙 + 5 dp focus 外圈，进度条为 onAlarm 色。
- **呼叫流程**：
  - 呼叫弹层整个 View 区为 alarm 底：210 dp 圆环（8 dp onAlarm 描边），环内倒计时数字 110 sp/800；标题“正在呼叫护士” 56 sp/700；说明“N 秒后自动呼叫 · 左看取消 · 咬牙或右看立即呼叫” 26 sp。
  - 倒计时 5 秒，到 0 自动呼叫。左看取消，提示“已取消呼叫”；咬牙或右看立即呼叫。
  - 呼叫发出后，圆环内显示 ✓（80 sp），标题“已呼叫护士”，说明“做任意动作关闭”。用 `USAGE_ALARM` 循环播放 `RingtoneManager.TYPE_ALARM`，直到关闭。封装在 `alert/NurseCallAlarm.kt`。
- **删除**：`HomeModule.HEADSET_SETTINGS`，以及首页上的设备列表、波形、设备状态行（移入照护面板）。BLE 自动扫描连接逻辑保留，挪到 Activity 或 ViewModel。
- **`resolveDestination(demoCommunication)` 的特例**：演示包不再从首页直达 T9，统一进入联系人列表（演示包里只有「当面说话」，见 7.2）。

### 7.2 联系人列表（CommunicationScreen 重写 · 阶段 CONTACT_LIST）
- **整体**：两列，宽度 500 | 剩余，间距 20。
- **左列：列表**（纵向，间距 8）

| 行 | 内容 |
|---|---|
| 0 | **当面说话**（本地固定项，不来自服务器）：ok 底头像，字“说”；标题“当面说话”；副标题“打字后朗读，并放大给身边的人看” |
| 1…n | 服务器联系人：头像（56 dp 圆，首字 24 sp/900 白），在线时右下角 16 dp ok 色圆点并带 3 dp panel 色环；名字 28 sp/800，关系标签 17 sp/500 muted 跟在名字后；副标题为最后一条消息，没有消息时显示“在线 / 离线”（17 sp muted，单行省略）；有未读时右侧显示未读徽标（高 36、最小宽 36、圆角 18、focus 底、bg 色字、18 sp/800 Archivo） |
| 末行 | 两格并排（间距 8，高 88）：「邀请家属」（user-plus）、「返回主页」（house），导航样式，图标 24 + 文字 22 sp |

  - 每行高 88，内边距 0×22，元素间距 16。
  - 联系人多于一屏时，列表随焦点滚动，焦点行始终完整可见；末行导航固定在底部。
  - **关系标签**：`ChatContact` 目前没有 relation 字段。服务器有就显示，没有就不显示（属于协议扩展，不在本期范围）。
- **右列：预览区**：mag 底、2 dp 描边、圆角 20、内边距 26×30、元素间距 14。内容按 `rContactSide()` 随焦点变化：
  - **联系人**：头部（84 dp 头像、名字 48 sp/900、状态行“关系 · 在线 · N 条未读” 20 sp muted）+ 最近 3 条消息气泡（见 7.4）+ 底部提示“咬牙选择：发文字消息，或视频通话”（20 sp muted）。
  - **当面说话**：说明文字 + 提示“咬牙开始打字”。没有家属时追加一句“还没有绑定家属。选「邀请家属」生成邀请码，交给家属在手机上输入。”
  - **邀请家属 / 返回主页**：标题 40 sp/900 + 说明 24 sp muted。
- **初始焦点**：第一个有未读的联系人；都没有未读时为第 0 行（当面说话）。从其他页返回时见第 8 节。
- **咬牙**：
  - 当面说话：打开输入界面（当面说话模式）。
  - 联系人：进入通讯方式。
  - 邀请家属：进入邀请页。
  - 返回主页：回首页。
- **触摸**：点任意行等同于咬牙。
- **状态**：
  - 加载中：列表区显示“正在加载联系人…”。
  - 出错：预览区显示错误文字（muted），列表仍可用。
  - 未配置服务器：只显示「当面说话」，预览区显示“请照护者在「设备与模型」中配置服务器后再邀请家属”。
  - 演示包：同“未配置服务器”。
- **`ContactSelectionPolicy`** 改为按 `[self] + contacts + [invite, home]` 计算。

### 7.3 通讯方式（阶段 CONTACT_ACTIONS）
- **左列**：联系人列表保持显示，透明度 0.42，不参与扫描；当前联系人行描边为 focus 色；触摸其他联系人可切换。
- **右列**：头部（同 7.2）+ 最近 2 条消息 + 两张大卡 + 返回行。
  - **大卡**：两列，间距 16，高 190；圆角 20，内边距 22×24。左上 46 dp 图标，底部标题 38 sp/900，说明 19 sp muted。
    - 「文字消息」，说明“打字发给 X”。
    - 「视频通话」，说明“X 在线，可以直接呼叫”或“对方离线，可能无人接听”。
  - **返回行**：「← 返回联系人」，高 68，导航样式，22 sp。
- **扫描顺序**：文字消息 → 视频通话 → 返回联系人。初始焦点为文字消息。
- **咬牙**：
  - 文字消息：进入输入界面（文字消息模式），并调用现有 `markRead`。
  - 视频通话：调用 `video.start`。
  - 返回：回联系人列表，焦点在该联系人。

### 7.4 输入界面（三种模式共用）
沿用 v2 的输入界面：输出栏、放大镜、候选区、拼音 / 选字 / 预测 / 常用语各层，紧急常用语。布局和数值同 v2（见原型 `rComm()`、`mag()`、`cands()` 和对应 CSS）。v3 的改动如下。

**字母块（`LetterBlockMapping`）**
```
LEFT  = [2 ABC, 3 DEF, 4 GHI, 5 JKL, DELETE 删除, BACK 返回]
RIGHT = [6 MNO, 7 PQRS, 8 TUV, 9 WXYZ, SEND 发送 | SAY 说出, PHRASES 常用语]
```
- 当面说话模式用「说出」，样式为 ok 色字 + 描边；文字消息和助手模式用「发送」，样式为 focus 色字 + 描边。
- 「返回」用 muted 色。
- 原来的 `chatAction` 索引写法（highlightedBlockIndex ≥ 4 时调用 0 返回 / 1 发送 / 2 删除）删除，改为显式的功能块。

**模式配置**：新增 `InputMode`，替代 `setChatAction` 和 `setStandaloneInputActive`。

| 模式 | 输出栏左侧标签 | 占位文字 | 右侧功能块 | 常用语 | 「发送 / 说出」的效果 | 「返回」 |
|---|---|---|---|---|---|---|
| TALK 当面说话 | 无 | 想说什么？看向左右挑字母块，或咬牙打开常用语 | 说出 | 患者常用语（紧急语在前） | 打开说出弹层（v2） | 回联系人，焦点「当面说话」 |
| CHAT(contact) 文字消息 | `发给 X` 胶囊（17 sp muted，1 dp 描边） | 写给 X 的话…（咬牙打开常用语） | 发送 | 患者常用语 | `chat.queue(text)`；成功则清空输入、提示“已发送给X”、回第一层左侧第 0 块；失败保留文字并提示错误 | 回通讯方式，焦点「文字消息」 |
| ASSISTANT 助手 | `问助手` 胶囊 | 想问什么，或想让助手做什么？ | 发送 | 助手常用指令（见 7.6） | 回助手页并立即提交 | 回助手页，文字留在草稿；草稿非空时焦点「发送」，否则「输入」 |

- **放大镜提示**（按原型 `mag()` 中的 H 表）：
  - 发送：“把这句话发给 X”或“把这句话发给生活助手”。
  - 返回：当面说话为“回到联系人，没说完的话会保留”；文字消息为“回到 X，没写完的会保留”；助手为“回到生活助手，文字留在草稿里”。
- **预测层末尾增加「发送 / 说出」**，导航样式：`[继续打字, 预测词 ≤7, 发送]`，写完一个词可以直接发。
- **草稿按对象保存**：每个联系人一份、当面说话一份、助手草稿一份。切换联系人时不再清空输入，删除 `LaunchedEffect(contact) { input.clearChatInput() }`。
- **左右看**：只改方向（6.7）。
- **文字消息模式的整体布局**：两列，300 | 剩余，间距 16。
  - **左列消息记录**：panel 底、2 dp 描边、圆角 18、内边距 16、间距 10。标题“名字 + 在线 / 离线”（24 sp/900 + 15 sp muted）；显示最近 7 条，最新在底部；气泡 19 sp，最宽 92%，内边距 9×14。
  - **右列输入界面**：两翼宽 200（离开第一层后收窄为 110），字母块 30 sp、字距 0.1 em（收窄后 18 sp）。
  - **加载更早消息**：在消息记录顶部放一个小按钮，只供照护者触摸，不参与扫描，调用 `chat.older()`。
- **气泡**：
  - 对方：panel 底、1 dp line 描边，靠左。
  - 自己：panel 底叠加 focusTint，靠右。
  - 圆角 18，行高 1.45。时间等信息 14 sp Archivo muted，自己的气泡右对齐。
- **消息状态**（只是显示，逻辑沿用现有 outbox）：
  - 在 outbox 中、WebSocket 已连接：`HH:mm · 发送中…`。
  - 在 outbox 中、未连接：`HH:mm · 待发送 · 网络恢复后自动发出`。
  - 已入库：`HH:mm · 已送达`。
- **收到新消息**：如果正在看这个联系人的输入界面，左列直接追加；否则该联系人未读数 +1。

### 7.5 视频通话层（PadVideoScreen 重写样式）
- **层级**：有通话（`video.state.call != null`）时，视频层盖住 View 区和 Overlay 层，状态栏和动作栏保留。底下的页面状态（目的地、扫描焦点、弹层、输入内容）原样保留，但不接收指令。指令全部交给视频层，沿用现有 MainActivity 的分流，改用 ScanCursor。
- **视觉**：视频面日夜都用深色。
  - 底色 `#0E1114`，圆角 18；主文字 `#F2EEE8`；次要文字 `#B9B2A8`。
  - 左上角状态：“● 通话中 / 来电 / 视频通话”，18 sp。
  - 中间远端区：有画面时铺满；没有画面时显示 150 dp 头像（64 sp）+ 名字 44 sp/900 + 状态 26 sp Archivo（“正在呼叫…”“视频来电…”或 `mm:ss` 计时）。
  - 右上角本地画面：230×144，圆角 14，`#252B31` 底，1 dp 白 12% 描边。摄像头关闭时显示“摄像头已关闭”，静音时追加“ · 已静音”。
  - 底部按钮行：距左右 22、距底 20，高 92，间距 14，等宽；只有一个按钮时居中，左右各留 32%。按钮底为白 8%、描边白 16%，26 sp/700；焦点时描边为 focus 色、底为白 15%。挂断、拒绝、取消呼叫的文字色 `#FF8A7E`；接听 `#5EE0A0`。
- **按钮**（来自 `VideoSession.actions`）：

| 状态 | 按钮（扫描顺序） | 初始焦点 |
|---|---|---|
| 呼出中 | 取消呼叫 | 0 |
| 来电 | **接听**、拒绝 | 0（接听） |
| 通话中 | 静音 / 取消静音、关闭 / 打开摄像头、切换摄像头、挂断 | 0 |

- **挂断确认**：`confirmEnd` 时改用 6.5 的确认弹层，画在视频层上方：标题“挂断和 X 的视频？”，副标题“通话会立即结束。”；左卡“继续通话 / 回到通话”，右卡“挂断 / 结束视频”。
- **结束**（挂断、取消、拒绝、无人接听、断线）：视频层消失，用 toast 显示现有 `applyCall` 的结束文案（如“通话已结束 · 01:23”“无人接听”）。焦点恢复见第 8 节。
- **权限被拒**：toast“请允许摄像头和麦克风权限后再发起视频通话”，留在原页面。

### 7.6 邀请家属（阶段 PAIRING）
- **整体**：居中纵列，间距 16。
  - 标题“邀请家属” 40 sp/900；说明“请家属打开手机端，在「兑换邀请码」里输入下面 6 位字符” 22 sp muted。
  - 邀请码：6 格，每格 104×128、圆角 18、2 dp 描边、panel 底，76 sp/800 Archivo focus 色；第 3 格后加 18 dp 间隔。
  - 有效期：“10 分钟内有效（HH:mm 前）· 只能使用一次” 22 sp muted，时间取 `invitation_expires`。
  - 「复制邀请码（照护者触摸）」：虚线 muted 按钮，17 sp，**不参与扫描**。
  - 底部扫描行：宽 760、高 100、两项、间距 16、28 sp/700：「返回联系人」「换一个邀请码」（重新调用 `chat.pairing()`）。初始焦点为返回。
- **生成中**：6 格显示“·”，下方显示“正在生成…”。
- **失败或未配置**：显示错误或配置提示，扫描行只保留返回。

### 7.7 生活助手（AssistantScreen 重写）
- **整体**：两列，剩余 | 330，间距 20。
- **左列主区**：三行，高度 剩余 / 72 / 92，间距 12。
  1. **对话区**：panel 底、2 dp 描边、圆角 18、内边距 16×20、间距 12，内容贴底。
     - 显示最近 6 轮。用户气泡同“自己”样式；助手气泡同“对方”样式，气泡内顶部有“助手”小字（14 sp muted）。正文 24 sp。
     - 首次进入（没有历史）显示一条助手欢迎语：“你好，我是生活助手。可以问我问题、点播音乐和电视，或者让我操作房间里的设备（演示为模拟）。”
     - 等待回复时：末尾显示助手气泡“正在回复…”（muted）。
     - **家居确认**：末尾显示确认气泡，全宽，2 dp focus 描边，panel2 底。内容为问题“要执行这项操作吗？”（22 sp/700）+ 选项行（等分，间距 12，高 84，24 sp/700）+ 提示“「取消」先亮 · 右侧「模拟家居」预览变化 · 三轮不选按取消处理”（15 sp muted）。
  2. **草稿栏**：panel 底、2 dp 描边、圆角 16，左右内边距 22，间距 16。左侧“草稿”胶囊（16 sp muted），右侧为草稿文字（30 sp/500，单行省略）；为空时显示占位“选「输入」开始，也可以直接用常用语”（22 sp dim）。
  3. **底栏**：4 项等分，间距 12，26 sp/700，顺序为：

| 项 | 可用条件 | 咬牙 |
|---|---|---|
| 输入（草稿非空时显示“继续输入”） | 未在回复中 | 打开输入界面（助手模式），并带入草稿 |
| 清空草稿 | 草稿非空，且未在回复中 | 清空，提示“已清空草稿”，焦点移到「输入」 |
| 发送 | 草稿非空白，且未在回复中 | 提交 |
| 退出 | 未在回复中 | 回首页，焦点「生活助手」 |

  - 不可用的项变灰（0.32），扫描时跳过。
  - **回复中或确认中**：整个底栏透明度 0.45，不参与扫描。
- **右列「模拟家居」**：mag 底、2 dp 描边、圆角 20、内边距 20、间距 8。
  - 标题“模拟家居”（22 sp/800），副标题“演示用，不控制真实家电”（15 sp muted）。
  - 6 行设备状态：大灯 / 床头灯 / 窗帘 / 空调 / 风向 / 定时关机。每行内边距 11×14、圆角 12、2 dp 透明描边，名称 21 sp，值 20 sp/700 Archivo 右对齐。值的文案按原型 `devRows()`：窗帘 0 = “关闭”、100 = “全开”，其余为百分比；空调关闭显示“关”，否则显示“模式 温度°C”；定时关机显示“N 分钟后”或“无”。
  - **预览**：确认中，焦点落在某个候选上时，用 `SimulatedHome.apply(option, now)` 算出新状态。值发生变化的行加 focus 描边和 focusTint 底，值显示为 `旧值`（muted、删除线）+ `新值`（focus 色）。底部提示“预览：确认后会这样变化”；焦点在「取消」时提示“取消：保持现状，不做改变”（16 sp/700 focus 色）。
  - **执行后**：变化的行显示 ok 描边 + okTint 底，持续 1.3 s。
- **提交流程**（沿用 `submit()` 和 `AssistantService`）：
  - 用户消息进入对话，草稿清空，进入回复中（ActionBar 显示“正在回复…”，扫描暂停）。
  - **Message**：回复进入对话，焦点移到「输入」。`clarify = true` 时保留原草稿，这时「输入」显示为“继续输入”。
  - **PlayTv / PlayMusic**：回复进入对话，约 1 s 后打开电视或音乐（沿用 `onOpenMedia` 和 `assistantMediaId`）。
  - **ConfirmHome**：显示确认气泡，焦点**「取消」在第 0 项**（现有代码把取消放在最后，要改），第一次停留 1.5 倍。
  - **选中候选**：执行 `confirmHome`，对话追加“模拟：已执行「…」”。
  - **选取消或三轮超时**：对话追加“已取消操作”。
  - 结束后焦点回「输入」。
  - 出错：沿用现有错误文案，作为助手回复进入对话。
- **助手常用指令**（输入界面常用语，按顺序）：开灯、关灯、打开床头灯、关闭床头灯、打开窗帘、关闭窗帘、窗帘开到一半、开空调、关空调、我热、空调一分钟后关机、看央视新闻、看体育台、播放音乐、播放《演员》、为什么天空是蓝色的。
- **移到照护面板**：连接设置（`AssistantConnectionDialog`：地址、连接码、测试连接、保存）和触摸测试键盘（`TestKeyboardInput`）。页面右上角的「设置」按钮删除。
- **未配置连接**：发送后助手回复“请照护者在照护面板「生活助手服务」中完成连接配置”，替代原来的“右上角「设置」”。

### 7.8 娱乐画廊
同 v2 规格（中心卡 700×438、邻卡缩放 0.60 / 0.50、下一个标签贴近中心一侧、72 dp 方向箭头、名称条、环形回绕、图片层 `assets/entertainment/{id}.webp`），数值见原型 `.gcard` 相关 CSS 和 `updateGallery()`。
- **顺序**：返回主页 → 迷宫 → 向上贪吃蛇 → 魔法师 → 中国象棋 → 斗地主 → 麻将 → 看电视 → 音乐（环形）。初始焦点为迷宫。
- **音乐卡**：进入音乐页，parent 为娱乐。
- **手动扫描**：左看、右看直接移动一张，画廊照常平移，没有进度条。

### 7.9 应用设置
- **整体**：两列，宽度比 1.1 : 1，间距 22。
  - **左列**：8 行等高，间距 9，每行 24 sp/700。
  - **右列说明区**：同 v2（数值 92 sp/800 focus 色，说明 24 sp）。
- **各项**（初始焦点为第 1 项）：

| # | 名称 | 图标 | 数值 | 咬牙 | 说明 |
|---|---|---|---|---|---|
| 0 | 返回主页（导航样式） | arrow-left | — | 回首页，焦点「设置」 | 回到主页，选择其他功能。 |
| 1 | 音量 | volume-2 | 60% | 调节弹层，每步 10% | 整个应用的声音大小，包括朗读、通话和提示音。 |
| 2 | 屏幕亮度 | sun | 80% | 调节弹层，10–100% | 只在使用本应用时生效。夜间建议调低。 |
| 3 | 每项停留 | gauge | 1.5 秒 | 调节弹层，每步 0.1 s | 自动扫描时，光标在每一项停留多久。范围 1.1–3.0 秒。 |
| 4 | 扫描方式 | arrow-left-right | 自动 / 手动 | 直接切换，提示“扫描方式：X” | 自动：光标按停留时间自己移动，看向左右改变方向。手动：光标不自己动，左看上一项、右看下一项。沟通的字母块始终自动扫描。 |
| 5 | 显示模式 | sun-moon | 自动 / 日间 / 夜间 | 自动 → 日间 → 夜间 → 自动 | 咬牙依次切换：自动 → 日间 → 夜间。自动为 7:00–19:00 日间，其余时间夜间。 |
| 6 | 模拟悬浮球 | circle-dot | 显示 / 隐藏 | 直接切换 | 照护者测试用的模拟按钮，不影响真实耳机控制。 |
| 7 | 速度练习 | target | 12 题 | 进入现有速度练习 | 做 12 次目标选择，系统推荐合适的停留时间，由你决定是否采用。 |

- 原右栏的“医护人员辅助设置”、耳机控制开关和“页面导航扫描模式”按钮删除；耳机控制移入照护面板，扫描方式改由第 4 项控制。

### 7.10 电视观看
- 布局同 v2：WebView + 5 格频道条，换台后在左下角显示频道号和名称 3 s。
- 左看上一台，右看下一台，咬牙打开电视菜单。
- **从助手进入**（`assistantMediaId != null`）：初始频道为助手指定的频道，菜单第 3 项文案为「返回助手」，返回后见第 8 节。

### 7.11 音乐
- 页面**沿用现有 MusicScreen 的布局与功能**（歌单、播放方式、音量、删除、导入），只换皮（7.13）。原型的音乐页只是演示流程用的占位。
- 改动只有两处：
  - 返回项的文案按来源写「返回娱乐」或「返回助手」；
  - 从助手进入时，自动播放指定曲目，焦点在暂停 / 播放上。

### 7.12 照护面板 `CaregiverSheet`
- **样式**：从右侧滑入，宽 480，careBg / careText 配色，内边距 22×26，间距 14，可滚动。
- **打开期间**：不分发指令，扫描暂停。
- **内容**（从上到下）：
  1. 标题“照护面板” + 「关闭」按钮。
  2. 说明框：“面板打开期间，患者的耳机指令暂停，扫描停止。关闭后恢复。”
  3. 开关：
     - 耳机控制（真实识别结果驱动界面），调用 `setHeadsetControlEnabled`。
     - 提示音（识别到动作时），默认开。
     - 悬浮波形窗口，默认关。
  4. 信息行：设备、电量 / 佩戴、模型、沟通服务（`BACKNET_URL` · 已绑定 / 未配置）。
  5. **生活助手服务**：地址、连接码、「测试连接」「保存」（`AssistantConnectionDialog` 的内容内嵌到这里），以及「键盘编辑助手草稿（调试）」（`TestKeyboardInput`）。
  6. 双通道实时波形：`EegStereoView`，高 150。
  7. 最近识别到的动作，最多 7 条。
  8. 「进入设备与模型（采集 · 训练 · 用户）」，进入 `AppDestination.SETTINGS`。
- **原型里的“模拟：还没有绑定家属”“模拟：妈妈来电”**：只在 debug 包显示，对应 11.2 的 `fake` 命令。

### 7.13 只换皮、不改布局的页面
- **范围**：音乐、扫描速度练习、迷宫、向上贪吃蛇、设备与模型、悬浮球、悬浮波形窗口。
- **改动**：换成新 token、`scanItem` 焦点样式和新字体；放进 PatientScaffold（设备与模型除外）；删除页面里重复的操作说明文字；确认框和结束二选一换成 6.5 的确认框。
- **棋牌 WebView 页**：只改 CONFIRM 逻辑（10.3），并在宿主层加状态栏和动作栏。
- **魔法师**（Godot，独立 Activity）：本轮不动。

## 8. 切页与返回后的焦点

| 从哪里返回 | 回到哪里、焦点在哪 |
|---|---|
| 任一模块 → 首页 | 首页，焦点在该模块的卡片 |
| 通讯方式「返回联系人」 | 联系人列表，焦点在同一个联系人 |
| 文字消息「返回」 | 通讯方式，焦点「文字消息」，草稿保留 |
| 当面说话「返回」 | 联系人列表，焦点「当面说话」，文字保留 |
| 呼出视频结束（挂断 / 取消 / 无人接听 / 断线） | 通讯方式，焦点「视频通话」 |
| 来电结束（接听后挂断 / 拒绝 / 未接） | 来电前的页面，焦点、弹层、输入内容原样保留 |
| 邀请页「返回联系人」 | 联系人列表，焦点「邀请家属」 |
| 助手输入「返回」 | 助手页，草稿非空时焦点「发送」，否则「输入」 |
| 助手打开的电视 / 音乐返回 | 助手页，焦点「输入」 |
| 娱乐打开的游戏 / 电视 / 音乐返回 | 娱乐画廊，该卡居中 |
| 设置「返回主页」 | 首页，焦点「设置」 |
| 任何弹层关闭 | 底层页面焦点不变 |

- **系统返回键优先级**：确认弹层 → 其他弹层 → 输入界面的非第一层（回第一层）→ 上表中的上一级 → 首页。
- **进入焦点**：进入任何页面时，焦点按上表或该页的初始规则设置，方向重置为向右。

## 9. 文件改动清单

| 文件 | 改动 |
|---|---|
| `ui/theme/Theme.kt`、新增 `BciColors.kt`、`BciType.kt` | 第 3、4 节 |
| 新增 `ui/design/` | PatientScaffold、StatusBar、ActionBar、ActionHints、ScanItem、GazeFeedback、BciToast、Overlays（Confirm / Adjust / Speak / Call / TvMenu）、CaregiverSheet、Gallery、ChatBubble、Avatar |
| 新增 `model/ScanCursor.kt` | 第 6 节，含手动扫描；配套 `ScanCursorTest` |
| `model/ScanSettings.kt` | `readMode` 默认值改为 AUTO |
| `model/AppUiSettings.kt` | 新增 displayMode、feedbackSound、floatingEegVisible、graceMs |
| `model/AppNavigation.kt` | `HomeModule` = COMMUNICATION / LIFE_ASSISTANT / ENTERTAINMENT / APP_SETTINGS / CALL_NURSE；删除 HEADSET_SETTINGS 和 `resolveDestination` 里的演示特例；`EntertainmentHubModule` 中 BACK 排第一；`HomeSelectionState.onDirection` 改用 ScanCursor |
| `model/SelectionPaging.kt` | 预测层末尾加 SEND；各层数量按 v2 表 |
| `data/LetterBlockMapping.kt` | 左右各 6 块（7.4） |
| `data/CharacterDictionary.kt`、`CommonPhraseUsage.kt` | 紧急常用语固定在前 |
| `viewmodel/InputMethodViewModel.kt` | 新增 `InputMode`（TALK / CHAT / ASSISTANT）及 onSend / onBack 回调；删除 chatAction 索引逻辑和 ScanInputPolicy 分支；按模式保存草稿；接入 ScanCursor |
| 删除 `communication/ScanInputPolicy.kt` | 6.7 |
| `communication/ContactSelectionPolicy.kt` | 按 `[self] + contacts + [invite, home]` 计算 |
| `communication/CommunicationViewModel.kt` | 列表扫描与通讯方式扫描改用 ScanCursor（删除自带的 `startActionScan` 计时）；新增 selfSelected、按 conversationId 保存的草稿表；邀请页「换一个邀请码」；对外暴露 outbox 连接状态用于显示“待发送” |
| `ui/communication/CommunicationScreen.kt` | 重写（7.2、7.3、7.4 的消息记录列、7.6） |
| `ui/communication/PadVideoScreen.kt` | 重写样式（7.5），作为视频层放进 PatientScaffold |
| `communication/VideoSession.kt` | 只调整 `actions()` 的文案和顺序：确认挂断改由确认弹层承担；接听仍在前 |
| `ui/components/MainScreen.kt` | 删除，改为 `ui/communication/ComposerScreen.kt`（三种模式共用） |
| `ui/assistant/AssistantScreen.kt` | 重写（7.7）；确认选项顺序改为取消在前；连接设置和测试键盘移入照护面板 |
| `ui/home/HomeScreen.kt` | 重写（7.1） |
| `ui/home/EntertainmentScreen.kt` | 重写为画廊；删除未使用的 `ui/entertainment/` |
| `ui/settings/AppSettingsScreen.kt` | 重写（7.9） |
| `ui/tv/TvScreen.kt`、`ui/music/MusicScreen.kt` | 返回项文案按来源；换皮 |
| `ui/game/*`、`ui/calibration/*`、`ui/components/Floating*.kt` | 7.13 |
| `MainActivity.kt` | 套 PatientScaffold；分发前调用 GazeFeedback；照护面板打开时屏蔽指令；视频层分流改用 ScanCursor；记录 media 来源并恢复焦点；呼叫护士；debug 广播（11.2） |
| `bci/BciController.kt` 等 | 只新增只读的 `readiness`（5.3） |
| 新增 `speech/SpeechOutput.kt`、`alert/NurseCallAlarm.kt` | 朗读、警报 |
| `res/font/`、`res/drawable/`、`assets/entertainment/` | 字体、图标、画廊图片位 |
| `assets/chinese_chess/scan.js`、`assets/mahjong/js/scan.js`、`assets/doudizhu/js/bci.js` | CONFIRM 阶段改为两项扫描（10.3） |
| 文档 | 第 10.1 节 |

## 10. 文档与测试

### 10.1 文档
- **新建** `docs/INTERACTION_SPEC.md`（本仓库没有这份文件）：写入第 5.2 节动作栏文案表、第 6 节扫描规则、第 8 节返回焦点表和页面层级。
- **更新** `docs/patient-chat-v1.md` 的“页面与 EEG 操作”一节：改为 7.2–7.6 的流程，删除“同向跳过一项”和“返回 / 发送 / 删除依次高亮”的描述。
- **更新** `docs/life-assistant.md`：
  - “连接平板”第 2 步改为“照护面板 → 生活助手服务”。
  - 底部扫描文案改为“输入 / 清空草稿 / 发送 / 退出”。
  - 输入法改为“右侧「发送」直接提交，「返回」保留草稿”。
  - 确认改为“取消先亮”。
- **更新** `README.md` 中的页面层级与模块表。

### 10.2 JVM 测试
- **新增 `ScanCursorTest`**：
  - 自动计时；
  - 方向切换后重新计时；
  - 延迟补偿边界 399 / 400 / 401 ms；
  - 上下文切换后重置；
  - 确认框首轮 1.5 倍、`3 × 项数` 次后自动取消；
  - tick 抛异常后计时仍继续；
  - 手动模式：左右看移动一项，不自动前进，确认框不超时。
- **删除 `ScanInputPolicyTest`**；新增 `InputMethodViewModel` 测试“候选阶段同向看不跳项”。
- **更新**：
  - `ContactSelectionPolicyTest`：含 self、invite、home 的循环。
  - `AppNavigationTest`：5 个首页入口；演示包也进联系人列表。
  - `EntertainmentHubNavigationTest`、`SelectionPagingTest`、`EntertainmentNavigationTest`：按新规则改写，覆盖范围不减少。
- **新增**：
  - 输入界面 6 块布局；三种模式下「发送 / 返回」的去向；按对象保存草稿；紧急常用语排序；清空前确认。
  - 助手确认选项顺序（取消在前）与预览计算（`SimulatedHome.apply` 的差异行）。
- `SimulatedHomeTest` 保持通过。

### 10.3 WebView 棋牌
- **CONFIRM 阶段**：`advance()` 在两项间轮流；初始停在第 0 项（取消），首轮停留 1.5 倍；累计 6 次 tick 后自动取消。
- **CONFIRM 阶段的左看 / 右看**：只重新计时。
- **测试**：本仓库只有 `mahjong_engine_test.js`。新建 `app/src/test/js/interaction_contract_test.mjs`，覆盖三套棋牌 CONFIRM 的“取消先亮、三轮超时取消、左右看不选择”。

### 10.4 每期必跑
```sh
./gradlew :app:testProductionDebugUnitTest :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug
node app/src/test/js/mahjong_engine_test.js
node app/src/test/js/interaction_contract_test.mjs   # P7 起
```

## 11. 视觉验收：和原型逐屏对照

### 11.1 原型截图
- **打开方式**：在 `prototype-v3.html` 后加 `#solo&freeze&mode=light&<参数>`。
- **截图设置**：Chromium，viewport 1280×800，`deviceScaleFactor: 2`，打开后等 1 s 再截。
- **参数说明**：
  - `screen` 可选：home、contacts、cact、chat、talk、invite、asst、ent、settings、tv、music。
  - `keys` 依次回放动作：L 左看、R 右看、B 咬牙、T 前进一格。
  - `incoming`：弹出妈妈来电。
  - `care`：打开照护面板。
  - `scan=manual`：手动扫描。
- 下表每个状态都已用脚本回放核对过；每个状态再截一份 `mode=dark`。

| # | 状态 | 参数 |
|---|---|---|
| S1 | 首页 | `screen=home` |
| S2 | 首页 · 焦点在呼叫护士（变宽） | `screen=home&keys=TTTT` |
| S3 | 呼叫护士倒计时 | `screen=home&keys=TTTTB` |
| S4 | 联系人 · 焦点在妈妈（未读 2） | `screen=contacts` |
| S5 | 通讯方式 · 妈妈 | `screen=cact` |
| S6 | 文字消息 · 空 | `screen=chat` |
| S7 | 文字消息 · 已选 MNO+GHI | `screen=chat&keys=RRLTTL` |
| S8 | 拼音层 · 焦点 ni | `screen=chat&keys=RRLTTLBT` |
| S9 | 预测层 ·“你好” | `screen=chat&keys=RRLTTLBTBBB` |
| S10 | 已发送 · 妈妈已回复 | `screen=chat&keys=RRLTTLBTBBBTTTTTB` |
| S11 | 当面说话 · 说出弹层 | `screen=talk&keys=RRLTTLBTBBBTTTTTB` |
| S12 | 清空确认框 | `screen=talk&keys=RRLTTLBTBBBTTTTTBTB` |
| S13 | 当面说话 · 常用语 | `screen=talk&keys=B` |
| S14 | 视频通话中 | `screen=cact&keys=TB` |
| S15 | 挂断确认 | `screen=cact&keys=TBTTTB` |
| S16 | 来电 | `screen=home&incoming` |
| S17 | 邀请家属 | `screen=invite` |
| S18 | 生活助手 | `screen=asst` |
| S19 | 助手 · 输入界面 | `screen=asst&keys=B` |
| S20 | 助手 · 家居确认（取消先亮） | `screen=asst&keys=BRTTTTTRTTTTTTTTTBBTTTTTTTB` |
| S21 | 助手 · 焦点在“降温到 25°C”（右侧预览） | `screen=asst&keys=BRTTTTTRTTTTTTTTTBBTTTTTTTBT` |
| S22 | 娱乐画廊 · 向右 | `screen=ent` |
| S23 | 娱乐画廊 · 向左 | `screen=ent&keys=L` |
| S24 | 设置 | `screen=settings` |
| S25 | 设置 · 手动扫描 | `screen=settings&scan=manual` |
| S26 | 调节弹层 · 每项停留 | `screen=settings&keys=TTB` |
| S27 | 电视 | `screen=tv` |
| S28 | 照护面板 | `screen=home&care` |

原型音乐页只是占位（7.11），不做对照。

### 11.2 App 截图
- **debug 广播接收器**：只在 debug 包注册。
  ```
  adb shell am broadcast -a com.example.input_ds.DEBUG --es cmd "<cmd>"
  ```
  - `nav <home|contacts|cact|chat|talk|invite|asst|ent|settings|tv>`：chat 和 cact 打开第 1 个联系人。
  - `signal <L|R|B>`：走 `routeSignal`，和悬浮球同一路径。
  - `tick`：前进一格。
  - `freeze <on|off>`：停止扫描计时；进度条固定 50%；时钟固定为 09:41 和对应日期；关闭锁定与延迟补偿；邀请码固定为 `K7Q2M9`，有效期显示 09:51。
  - `mode <light|dark|auto>`、`scan <auto|manual>`、`care <on|off>`。
  - `fake contacts`：给 CommunicationViewModel 注入与原型相同的联系人和消息（下表），断开网络请求；对方“在线”时，发送后 0 s 回复第一句预设回复。
  - `fake incoming`：注入一个“妈妈”来电的 VideoState，不连接媒体。
  - `fake assistant`：AssistantService 改用与原型 `asstReply()` 相同的关键词回复，0 s 返回。
  - `fake home <reset>`：SimulatedHome 恢复为原型初值（灯全关、窗帘 0、空调开 · 制冷 26°C · 自动摆风）。
- **假联系人数据**（与原型 `CONTACTS` 一致）：

| 名字 | 关系 | 在线 | 未读 | 消息（时间） | 预设回复 |
|---|---|---|---|---|---|
| 妈妈 | — | 是 | 2 | 对方“今天感觉怎么样？”(09:12)、对方“中午我给你带粥”(09:13) | 好的，我马上过来 |
| 小雨 | 女儿 | 是 | 0 | 我“想你了”(昨天)、对方“晚上我过来看你”(昨天) | 我晚上七点到 |
| 张医生 | 主治医生 | 否 | 0 | 对方“明天上午查房”(周一) | — |
| 李阿姨 | 护工 | 是 | 0 | — | 好的，马上来 |

- **设备**：Pixel Tablet 模拟器（2560×1600、320 dpi），用 `adb exec-out screencap -p` 截图。
- **复现**：每个状态用与 11.1 相同的动作序列复现。

### 11.3 比对标准
- 原型图与 App 图左右并排，或做 50% 叠图。
- **必须一致**：色值（误差 ≤ 2）、位置和尺寸（误差 ≤ 4 dp）、字号字重、圆角、描边、外圈、初始焦点、动作栏文案、各页文案。
- **允许不同**：字形渲染的细微差别。
- **存放位置**：`docs/design/redesign-v2/compare/`。

### 11.4 行为验收（Pad 人工）
1. **扫描**：每页等 30 s，扫描不停；切到后台再回来，不执行积压指令。
2. **方向与延迟补偿**：左右看不跳项、重新计时；换项后 400 ms 内咬牙执行上一项。
3. **手动扫描**：左右看逐项移动、无进度条；字母块仍自动扫描。
4. **确认**：取消先亮，首轮更久，三轮自动取消；来电接听先亮。
5. **沟通全流程**：
   - 联系人 → 文字消息 → 发送 → 对方回复；
   - 断网发送显示“待发送”，恢复后只出现一条；
   - 返回焦点逐项核对第 8 节。
6. **视频**：
   - 呼出 → 接通 → 挂断确认 → 回到「视频通话」；
   - 在输入界面写到一半时来电 → 拒绝 → 文字和焦点都在；
   - 拒绝权限时留在原页面。
7. **邀请码**：生成、换一个、返回；未配置服务器时显示提示。
8. **生活助手**：
   - 问答；
   - “看央视新闻”→ 电视 → 返回助手，焦点「输入」；
   - “我热”→ 取消先亮、预览、确认后设备行变化；
   - “看体育台”保留草稿；
   - 回复中底栏不可选。
9. **其他**：呼叫护士、说出、照护面板、日夜切换、真实耳机就绪点（同 v2）。

## 12. 分期（每期单独提交，跑 10.4，附 11.3 对照图）

| 期 | 内容 | 对照状态 |
|---|---|---|
| P1 | token、字体、图标、`ScanCursor`（含手动）及测试、删除 ScanInputPolicy、扫描默认改为 AUTO、PatientScaffold、StatusBar、ActionBar、GazeFeedback、Toast、debug 广播 | 状态栏、动作栏 |
| P2 | 首页（5 入口）、呼叫护士、照护面板（含助手服务设置）、`readiness` | S1–S3、S28 |
| P3 | 统一输入界面（三种模式、6 块布局、预测层发送、按对象保存草稿、说出、紧急常用语） | S11–S13、S19 |
| P4 | 联系人、通讯方式、文字消息记录列、邀请页、视频层与来电恢复 | S4–S10、S14–S17 |
| P5 | 生活助手页、模拟家居预览、电视和音乐的来源与返回焦点 | S18、S20、S21 |
| P6 | 娱乐画廊、应用设置（扫描方式、显示模式） | S22–S26 |
| P7 | 确认框统一（原生 + WebView）、文档与测试 | S12、S15，以及游戏、棋牌确认 |
| P8 | 电视、音乐、速度练习、迷宫、贪吃蛇、悬浮组件换皮；删除 Aurora 别名 | S27，其余页面自查 |

## 13. 有意差异（以本节为准）

| 项 | 原型 | App |
|---|---|---|
| 照护面板入口 | 点击打开 | 长按 2 秒打开 |
| 呼叫警报音 | 响两声 | 循环播放，直到关闭 |
| 音乐页 | 简化的占位页 | 现有 MusicScreen 换皮，返回文案与焦点按 7.11 |
| 视频画面 | 头像占位 | 真实远端和本地画面；没有画面时才显示头像 |
| 拼音层 | 返回 + 拼音 | 保留现有「首字母整句」入口，放在末尾，导航样式 |
| 拼音网格 | 1 行 | 5 列，按需最多 3 行 |
| 电视频道名 | 常驻显示 | 换台后显示 3 s |
| 联系人关系标签 | 有 | 服务器有该字段才显示 |
| 加载更早消息 | 无 | 消息记录顶部的触摸按钮 |
| 来电未接 | 20 s 本地超时 | 服务器状态（timeout） |
| 原型下方模拟面板、配色切换按钮 | 有 | 不实现 |
| 照护面板“模拟家属 / 来电”开关 | 常驻 | 仅 debug 包 |

## 14. 原型里的模拟（不要实现成正式逻辑）

| 内容 | 原型做法 | 正式来源 |
|---|---|---|
| 联系人、消息、对方回复 | 写死 5 人；约 3 s 后按固定句子回复；发送 0.7 s 后变“已送达” | 沟通服务器 + WebSocket；outbox |
| 视频 | 在线 2.5 s 接通，离线 6 s 后无人接听；来电由按钮触发 | VideoSession / LiveKit |
| 邀请码 | 本地随机 6 位 | `chat.pairing()` |
| 助手回复 | 关键词匹配（`asstReply()`） | AssistantService |
| 模拟家居 | 页面内状态；“一分钟后关机”实时倒计时 | SimulatedHome |
| 电视、音乐 | 只显示信息，不播放 | 电视页、本地音乐库 |
| 词库与预测 | 小词库 | 现有引擎 |

这些模拟数据只在 debug 的 `fake` 命令（11.2）里复用，用于截图对照。
