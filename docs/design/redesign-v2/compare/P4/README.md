# P4 联系人、通讯方式、文字消息记录列、邀请页、视频层与来电恢复

每张 `*-compare.png` 左侧为 Chromium 原型（1280×800 @ 2x），右侧为 Production Debug（2560×1600 @ 320 dpi）。同目录保留 2560×1600 全屏原图 `*-prototype.png` 与 `*-app.png`。

| 状态 | 日间对照 | 夜间对照 | 检查重点 |
|---|---|---|---|
| S4 联系人列表（焦点：妈妈，未读 2） | [对照](s4-contacts-light-compare.png) | [对照](s4-contacts-dark-compare.png) | 左 500 dp 列表（行 0「当面说话」+ 服务器联系人 + 底部固定「邀请家属 / 返回主页」）、未读优先聚焦、右侧预览（84 dp 头像 + 最近 3 条消息） |
| S5 通讯方式（妈妈） | [对照](s5-cact-light-compare.png) | [对照](s5-cact-dark-compare.png) | 左列表 0.42 淡化且当前联系人焦点描边、右侧最近 2 条消息 + 两张 190 dp 大卡（文字消息 / 视频通话）+ 68 dp 返回行 |
| S6 文字消息（空） | [对照](s6-chat-empty-light-compare.png) | [对照](s6-chat-empty-dark-compare.png) | 300 dp 左侧消息记录列 + 右侧紧凑双翼（200 dp）统一输入器、「发给 妈妈」胶囊 |
| S7 文字消息（已选 MNO + GHI） | [对照](s7-chat-blocks-light-compare.png) | [对照](s7-chat-blocks-dark-compare.png) | 翼块顺序序号 `1`/`2`、顶栏组合预览 `MNO GHI → mi · ni`、中央放大镜与候选预览 |
| S8 拼音层（焦点 ni） | [对照](s8-chat-pinyin-light-compare.png) | [对照](s8-chat-pinyin-dark-compare.png) | 离开第一层双翼收窄至 110 dp、放大镜显示 `ni` 与对应候选字、5 列拼音网格（含第 13 节保留的「首字母整句」） |
| S9 预测层（「你好」） | [对照](s9-chat-pred-light-compare.png) | [对照](s9-chat-pred-dark-compare.png) | 放大镜「加上它，这句话会变成」高亮拼接词、预测格末项含「发送」 |
| S10 已发送 · 妈妈已回复 | [对照](s10-chat-sent-light-compare.png) | [对照](s10-chat-sent-dark-compare.png) | 左侧记录列追加己方气泡（「09:41 · 已送达」，右对齐焦点叠底）与对方回复、输入器清空并回到 L1 ABC |
| S14 视频通话中 | [对照](s14-video-active-light-compare.png) | [对照](s14-video-active-dark-compare.png) | 日夜统一深色视频面 `#0E1114`、径向渐变 + 150 dp 头像 + `01:23` 计时、右上 230×144 本地画面、底部 4 按钮（静音聚焦、挂断红字 `#FF8A7E`） |
| S15 挂断确认 | [对照](s15-video-hangup-light-compare.png) | [对照](s15-video-hangup-dark-compare.png) | 视频层上方盖住 View 区的确认弹层「挂断和妈妈的视频？」、先亮「继续通话 / 回到通话」+ 后亮「挂断 / 结束视频」 |
| S16 来电（主页之上） | [对照](s16-video-incoming-light-compare.png) | [对照](s16-video-incoming-dark-compare.png) | 顶栏「来电 › 妈妈」、隐藏本地画面框、绿字「接听」先亮（`#5EE0A0`）+ 红字「拒绝」、来电结束原样恢复底层页面与状态 |
| S17 邀请家属 | [对照](s17-invite-light-compare.png) | [对照](s17-invite-dark-compare.png) | 6 格 104×128 邀请码（前 3 后 3 加 18 dp 间隔，冻结态 `K7Q2M9` / `09:51`）、照护者触摸复制虚线按钮、底部 760×100 两项扫描行 |

## 复现

Chromium 使用 1280×800 视口、DPR 2，加载本地 `archivo.ttf` 与 `noto_sans_sc.ttf`，URL 为 `prototype-v3.html#solo&freeze&mode=<light|dark>&<参数>`（参数同规格 11.1 表 S4–S10、S14–S17）。

App 运行于 `BCI_Pad_API_35`（2560×1600、320 dpi）：

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
bci 'freeze on'; bci 'scan auto'; bci 'mode light'; bci 'fake contacts'
bci 'nav contacts'                                                               # S4
bci 'nav cact'                                                                   # S5
bci 'nav chat'                                                                   # S6
bci 'signal R'; bci 'signal R'; bci 'signal L'; bci 'tick'; bci 'tick'; bci 'signal L' # S7
bci 'signal B'; bci 'tick'                                                       # S8
bci 'signal B'; bci 'signal B'; bci 'signal B'                                   # S9
for i in 1 2 3 4 5 6 7; do bci 'tick'; done; bci 'signal B'                      # S10（真实引擎 7 预测词）
bci 'fake contacts'; bci 'nav cact'; bci 'tick'; bci 'signal B'                  # S14
bci 'tick'; bci 'tick'; bci 'tick'; bci 'signal B'                               # S15
bci 'nav home'; bci 'fake incoming'                                              # S16
bci 'fake contacts'; bci 'nav invite'                                            # S17
```

## 测量与剩余差异

- **色值与布局**：
  - 联系人左列 500 dp、行高 88 dp、头像 56 dp / 84 dp / 150 dp、消息记录列 300 dp、紧凑双翼 200 dp / 110 dp、邀请码 104×128 dp、视频面 `#0E1114` 与接听 `#5EE0A0` / 挂断 `#FF8A7E`，日夜两版均与原型像素对齐。
  - `PatientScaffold` 将 `videoLayer` 置于 `overlay` 之上，保障来电完全覆盖底层页面与弹层，挂断/拒绝后底层页面焦点、弹层与草稿原样保留；呼出视频结束后焦点精确回到通讯方式「视频通话」。
- **剩余差异（预期 / 有意 / 后续期处理）**：
  1. **S7 / S8 / S9 真实词库与「首字母整句」**：真实词库返回完整候选字列表与 7 条预测词（原型为静态小词库），且按第 13 节有意差异在拼音层末尾保留「首字母整句」入口。
  2. **S4 联系人头像字**：按规格 7.2「首字 24 sp/900 白」取首字，故「小雨」显示「小」（原型静态数据手写为「雨」）。
  3. **S14 左上角「● 通话中」与 S15 弹层层叠**：原型 HTML 中 `.remote` 遮住了 `.tag` 且 `.item.f` 的 `z-index: 2` 导致挂断按钮渗漏到 S15 确认弹层之上；App 按第 5 节和 7.5 节规范正确显示「● 通话中」并完整遮盖底层视频按钮。
  4. **模拟器状态栏与悬浮球**：无蓝牙耳机时顶栏如实显示「耳机未连接 / L -- R --」；右缘绿色模拟悬浮球将在 P8 统一换皮。

## P4 验证记录

- `./gradlew :app:testProductionDebugUnitTest`：32 个测试套件共 145 项单测全部通过（更新 `ContactSelectionPolicyTest` 覆盖 `[self] + contacts + [invite, home]` 与未读优先规则，新增 `CommunicationFlowTest` 覆盖各阶段动作栏文案、面包屑路径与时间格式化）。
- `./gradlew :app:connectedProductionDebugAndroidTest`：模拟器仪器测试 2/2 全部通过。
- `./gradlew :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：构建与 lint 全部通过（0 error）。
- `node app/src/test/js/mahjong_engine_test.js`：通过。
