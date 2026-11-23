# P1 状态栏与动作栏

每张对照图左侧为 Chromium 原型，右侧为 Production Debug。在同一张图中，上排保留顶部 56 dp，下排保留底部 112 dp。同目录的 `*-prototype.png`、`*-app.png` 是 2560×1600 全屏原图。

| 扫描状态 | 日间 | 夜间 | 动作栏检查 |
|---|---|---|---|
| 自动向右 | [对照](light-right-bars.png) | [对照](dark-right-bars.png) | 右列方向圆与「当前方向」 |
| 自动向左 | [对照](light-left-bars.png) | [对照](dark-left-bars.png) | 左看后焦点保留，左列高亮 |
| 手动 | [对照](light-manual-bars.png) | [对照](dark-manual-bars.png) | 上一项 / 下一项，两列均无方向标记 |

## 复现

Chromium 使用 1280×800 视口、DPR 2。打开 `prototype-v3.html#solo&freeze&screen=home&mode=light` 或 `mode=dark`。自动向左追加 `&keys=L`，手动追加 `&scan=manual`。截图时等待字体就绪和 1 秒稳定时间。字体和 Lucide 0.469.0 使用本地资源供 Chromium 加载。

App 使用现有 API 35 平板模拟器 `BCI_Pad_API_35`，2560×1600、320 dpi。打开 App 后重置到同一起点，再选对应状态。

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
bci 'freeze on'
bci 'nav home'
bci 'scan auto'
bci 'mode light'
# 自动向左用 bci 'signal L'，手动用 bci 'scan manual'。
adb exec-out screencap -p > app.png
```

## 测量与差异

- 日间顶栏底色为 `#F6F3EF`，动作栏底色为 `#FCFAF7`。夜间分别为 `#14110E`、`#0E0C0A`。六组截图的底色及上下分隔线取样，RGB 各通道误差均为 0。
- 自动向右时，App 高亮圆的实色范围为 x=1202–1250 dp、y=720.5–768.5 dp。原型为 x=1202–1250 dp、y=721–769 dp。自动向左时两者的 x 均为 30–78 dp。实色就绪点轮廓最大偏移 0.5 dp。
- 原型显示已连接及 `L 86% · R 82%`。模拟器没有耳机，App 显示「耳机未连接」及 `L -- · R --`。电量文本较短，左侧胶囊随内容向右移动。实际耳机电量读取 BLE 遥测。
- 当前首页首项为「设置」，原型为「沟通」。动作栏各自显示会被咬牙选中的项目。首页卡片、正文及悬浮控件仍可在全屏图中看见，按第 12 节在所属期验收。
- 两端文字边缘有字形栅格化差别。真实耳机的锁定和静息进度、照护面板长按打开在 P2 联调。P1 外壳只展示就绪点的基态。
- 已接通的截图广播为 `nav home`、`signal L|R|B`、首页 `tick`、`freeze on|off`、`mode light|dark|auto`、`scan auto|manual`。其余合法命令返回 `pending`，随对应页面接入。截图模式和显示模式覆盖只影响当前调试会话。

## P1 验证记录

- 第 10.4 节的 Production Debug JVM 测试通过 132 项。Production Debug、Demo Debug 构建成功。Production Debug lint 有 0 个错误、98 个警告和 5 项提示。麻将引擎及扫描测试通过。
- Production Debug APK 已装入上述平板模拟器。两项 Android 仪器测试均通过，其中包含聊天候选阶段经真实 ViewModel 入口的重复同向、反向及咬牙选中测试。
- 首页默认 1500 ms 停留，连续观察 31 秒，四个可扫描入口按顺序发生 20 次切换。手动模式下左右越界循环，调试 tick 保持当前项；自动模式左看后保持当前项，下一个 tick 反向前进。
- 首页从「设置」换到「实时沟通」后立即咬牙，实际进入设置。相同序列等待 550 ms 后咬牙，实际进入沟通。399 / 400 / 401 ms 精确边界由 JVM 测试覆盖。
- 将系统字号调到 140%，同一冻结状态下整条动作栏像素一致。随后恢复模拟器原设置。退到系统桌面时模拟咬牙返回 inactive，回前台保留原焦点。
