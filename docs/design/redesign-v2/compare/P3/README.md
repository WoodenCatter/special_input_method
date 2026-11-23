# P3 统一输入器（当面说话、清空确认、常用语、助手输入）

每张 `*-compare.png` 左侧为 Chromium 原型（1280×800 @ 2x），右侧为 Production Debug（2560×1600 @ 320 dpi）。同目录保留 2560×1600 全屏原图 `*-prototype.png` 与 `*-app.png`。

| 状态 | 日间对照 | 夜间对照 | 检查重点 |
|---|---|---|---|
| S11 当面说话·朗读中弹层 | [对照](s11-speak-light-compare.png) | [对照](s11-speak-dark-compare.png) | 顶部「正在朗读 · 照护者请看这里」、104 dp 粗体大字、3 按钮（返回修改 / 再说一遍 / 清空，开始新的一句）、焦点默认在「再说一遍」 |
| S12 清空确认弹层 | [对照](s12-clear-confirm-light-compare.png) | [对照](s12-clear-confirm-dark-compare.png) | 60 dp 标题「清空这句话？」、22 dp 副文、左右双卡（未聚焦 0.55 透明度）、先停取消卡「取消 / 保留这句话」+ 右侧「清空 / 开始新的一句」 |
| S13 当面说话·常用语 | [对照](s13-phrases-light-compare.png) | [对照](s13-phrases-dark-compare.png) | 左右 6 块翼栏（左侧含删除/返回、右侧含说出/常用语）、大预览区、4×3 候选格、3 条紧急短语置顶带左侧 4 dp 红条与红框 |
| S19 生活助手·输入 | [对照](s19-asst-input-light-compare.png) | [对照](s19-asst-input-dark-compare.png) | 顶栏「生活助手 › 输入」、助手专用「问助手」标签与占位文案、右侧「发送 / 常用语」、左翼 L1 自动轮转高亮 ABC 与中央大预览 |

## 复现

Chromium 使用 1280×800 视口、DPR 2，加载本地 `archivo.ttf` 与 `noto_sans_sc.ttf`，URL 为 `prototype-v3.html#solo&freeze&mode=<light|dark>&<参数>`：

- S11：`screen=talk&keys=LRTLBRTBRTTTTBTTTTTB`
- S12：`screen=talk&keys=LRTLBRTBRTTTTBTTTTTBTB`
- S13：`screen=talk&keys=B`
- S19：`screen=asst-input`

App 运行于 `BCI_Pad_API_35`（2560×1600、320 dpi）：

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
# S13（当面说话·常用语）
bci 'freeze on'; bci 'scan auto'; bci 'mode light'; bci 'nav talk'; bci 'signal B'

# S11（输入「你好」并触发「说出」朗读弹层；真实引擎返回 7 条预测词，需 7 次 tick 到「说出」）
bci 'nav talk'
bci 'signal L'; bci 'signal R'; bci 'tick'; bci 'signal L'; bci 'signal B'       # 键 6(MNO) + 键 4(GHI) -> mi/ni
bci 'signal R'; bci 'signal T'; bci 'signal B'                                   # 选 ni
bci 'signal R'; bci 'signal T'; bci 'signal B'                                   # 选「你」
bci 'signal B'                                                                   # 自动进入预测，确认「你好」（或在预测格里走到「好」再走到「说出」）

# S12（从 S11 再走一格到「清空，开始新的一句」并确认）
bci 'tick'; bci 'signal B'

# S19（生活助手·打字）
bci 'nav asst'
```

## 测量与剩余差异

- **色值（误差 ≤ 1）**：日间背景 `#F6F3EF`、夜间背景 `#14110E`、焦点叠底 `#F8ECE6` / `#3E2C22`、紧急短语描边与左红条 `#C8102E` / `#E0243F`、动作栏 `#FCFAF7` / `#0E0C0A`，与原型逐通道误差 0–1。
- **结构与布局**：
  - 统一输入器 `ComposerScreen` 服务三种模式（`Talk` / `Chat` / `Assistant`），左右 6 块翼栏（左侧 `ABC/DEF/GHI/JKL/删除/返回`，右侧 `MNO/PQRS/TUV/WXYZ/说出或发送/常用语`），中央输出框 + 大预览 + 4×3 候选格。
  - S13 常用语每页 9 条 +「返回打字」+「下一页/回第一页」，3 条紧急短语（「叫医生」「我疼」「需要吸痰」）固定置顶。
  - S11 朗读弹层默认焦点落在中间「再说一遍」，S12 确认弹层默认焦点落在左侧「取消」（首轮 1.5× 停留 + 3 轮超时自动取消）。
- **剩余差异（预期 / 有意 / 后续期处理）**：
  1. **S11 / S12 原型层叠瑕疵**：原型 HTML 中 `.item.f` 带有 `z-index: 2` 而 `#overlay` 未设 `z-index`，导致原型 S11/S12 截图中底层聚焦的「说出」虚线框渗漏到弹层之上；App 按第 5 节层级规范完整遮盖底层内容。
  2. **预测候选数量**：真实 Rime/本地预测引擎在「你好」后返回 7 条预测词（共 9 项：`继续打字` + 7 词 + `说出`），原型仅写死 5 条预测词（共 7 项）；符合第 13 节真实引擎优先规则。
  3. **模拟器无蓝牙耳机与悬浮球**：顶栏如实显示「耳机未连接 / L -- R --」；屏幕右缘绿色模拟悬浮球将在 P8 统一换皮。
  4. **单页展示占位差异**：S19 仅替换了助手输入页面，生活助手主页（S18/S20/S21）与聊天历史栏（S4–S10）分别在 P4、P5 完整重写。

## P3 验证记录

- `./gradlew :app:testProductionDebugUnitTest`：31 个测试套件共 141 项单测全部通过（改写 `SelectionPagingTest` 覆盖新分页与紧急标记，新增 `InputMethodComposerTest` 覆盖 6 块翼栏、紧急置顶、`InputMode`、朗读/确认游标规则）。
- `./gradlew :app:connectedProductionDebugAndroidTest`：模拟器仪器测试全部通过（含 `InputMethodViewModelTest` 新模式下的连续同向不跳项校验）。
- `./gradlew :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：构建与 lint 全部通过（0 error）。
- `node app/src/test/js/mahjong_engine_test.js`：通过。
