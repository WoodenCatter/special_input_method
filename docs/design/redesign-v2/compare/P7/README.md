# P7 确认操作

原始截图为 2560×1600。并排图左为 Chromium 原型，右为 320 dpi Android 模拟器。两端冻结扫描后截图。

| 状态 | 日间 | 夜间 |
|---|---|---|
| S12 清空句子 | [并排](s12-light-compare.png) | [并排](s12-dark-compare.png) |
| S15 挂断视频 | [并排](s15-light-compare.png) | [并排](s15-dark-compare.png) |
| 贪吃蛇离开确认 | [App](snake-exit-light-app.png) | [App](snake-exit-dark-app.png) |
| 象棋退出确认 | [App](chess-exit-light-app.png) | [App](chess-exit-dark-app.png) |

原型用第 11.1 节 S12、S15 参数。App 通过 DEBUG 广播启用 `freeze on`、`scan auto` 和 `mode light/dark`。

- S12 从 `nav talk` 咬牙进入常用语，再咬牙写入「叫医生」，点「说出」，执行 `tick`、`signal B`。
- S15 用 `fake contacts`、`nav cact`、`tick`、`signal B` 打开调试通话，再前进三次并咬牙。
- 贪吃蛇从 `nav ent` 前进一次进入，按系统返回。象棋从娱乐前进三次进入，点游戏内退出按钮。

两张确认卡均先亮左项，S12 显示「取消」，S15 显示「继续通话」。原型底层高亮框会透过弹层，App 遮盖底层。App 状态栏显示模拟器实际耳机状态，调试悬浮球按设备设置显示。游戏内未提供对应 HTML 原型画面，象棋保留棋盘与棋局按钮原有样式。真实双端视频和实体耳机不在这组模拟器截图中。
