# P6 娱乐横向画廊、应用内设置（8 行）与数值调节覆盖层（S22–S26）

每张 `*-compare.png` 左侧为 Chromium 原型（1280×800 @ 2x），右侧为 Production Debug（2560×1600 @ 320 dpi）。同目录保留 2560×1600 全屏原图 `*-prototype.png` 与 `*-app.png`。

| 状态 | 日间对照 | 夜间对照 | 检查重点 |
|---|---|---|---|
| S22 娱乐画廊（默认右滑） | [对照](s22-ent-right-light-compare.png) | [对照](s22-ent-right-dark-compare.png) | 三行横排画廊（`700×438` 居中主卡、左右 `0.60`/`0.50` 缩略卡）、`1/8` 计数器、中心固定焦点 `迷宫`、右侧「下一个 ▶」胶囊与 `.gnext` 圆形箭头、底部 9 项索引条（`返回主页` 在最左） |
| S23 娱乐画廊（左看翻转方向） | [对照](s23-ent-left-light-compare.png) | [对照](s23-ent-left-dark-compare.png) | 左看一次将 `entDir` 翻转为 `-1`（`◀ 下一个` 移至左卡、`.gnext` 切换至主卡左侧并触发左滑、下栏左看高亮「当前方向」） |
| S24 设置 · 自动扫描 | [对照](s24-settings-light-compare.png) | [对照](s24-settings-dark-compare.png) | `1.1fr : 1fr` 两列对称布局、左侧 8 等高设置行（首行虚线「← 返回主页」，默认焦点第 2 行「音量 60%」）、右侧 `.sdesc` 说明卡片大号当前值 + 详细说明 |
| S25 设置 · 手动扫描 | [对照](s25-settings-manual-light-compare.png) | [对照](s25-settings-manual-dark-compare.png) | 手动模式下扫描方式切换为「手动」、顶部标签显示「手动扫描」、焦点项取消自动停留进度条、底部操作栏同步手动引导文案 |
| S26 设置 · 数值调节弹层 | [对照](s26-settings-adjust-light-compare.png) | [对照](s26-settings-adjust-dark-compare.png) | 选定「每项停留」弹出全屏调节覆盖层（97% `--ov` 遮罩、大字当前值「1.5 秒」、`.meter` 进度条、`760×100` 三项扫描「减小 / 增大 / 完成」且默认焦点落在「增大」） |

## 复现

Chromium 使用 1280×800 视口、`--force-device-scale-factor=2`，加载本地 `archivo.ttf` 与 `noto_sans_sc.ttf`：

- S22：`prototype-v3.html#solo&freeze&mode=<light|dark>&screen=ent`
- S23：`prototype-v3.html#solo&freeze&mode=<light|dark>&screen=ent&keys=L`
- S24：`prototype-v3.html#solo&freeze&mode=<light|dark>&screen=settings`
- S25：`prototype-v3.html#solo&freeze&mode=<light|dark>&screen=settings&scan=manual`
- S26：`prototype-v3.html#solo&freeze&mode=<light|dark>&screen=settings&keys=TTB`

App 运行于 `BCI_Pad_API_35`（2560×1600、320 dpi）：

```sh
bci() { adb shell "am broadcast -a com.example.input_ds.DEBUG --es cmd '$1'"; }
bci 'freeze on'; bci 'mode light'                                            # 或 mode dark
bci 'scan auto'; bci 'nav ent'                                               # S22
bci 'signal L'                                                               # S23
bci 'nav settings'                                                           # S24
bci 'scan manual'                                                            # S25
bci 'scan auto'; bci 'nav settings'; bci 'tick'; bci 'tick'; bci 'signal B'  # S26
```

## 测量与剩余差异

- **色值对齐（Δ 0 RGB）**：
  - 日间背景 `bg`：Prototype `(246, 243, 239)` vs App `(246, 243, 239)`；右侧 `.sdesc` 卡片底色 `sdesc_bg`：Prototype `(252, 250, 248)` vs App `(252, 250, 248)`。
  - 夜间背景 `bg`：Prototype `(20, 17, 14)` vs App `(20, 17, 14)`；右侧 `.sdesc` 卡片底色 `sdesc_bg`：Prototype `(23, 20, 15)` vs App `(23, 20, 15)`。
- **几何对齐（Δ ≤ 1.0 dp）**：
  - S22 `.gnext` X 范围：Prototype `(973.0, 1038.5)` dp vs App `(972.5, 1039.0)` dp（Δ ≤ 0.5 dp）。
  - S24 第 1 行焦点框（`音量`）Y 范围：Prototype `(150.5, 217.0)` dp vs App `(151.0, 218.0)` dp（Δ ≤ 1.0 dp）。
  - S26 进度条（`.meter`）：Prototype `y=(454.0, 536.0), x=(48.5, 296.5)` dp vs App `y=(454.0, 536.0), x=(48.0, 297.0)` dp（Δ ≤ 0.5 dp）；焦点按钮「增大」`y=(653.5, 655.0)` dp（Δ 0.0 dp）。
- **剩余差异（预期 / 有意 / 后续期处理）**：
  1. **S26 弹层遮罩层级**：原型 HTML 中 `.srow.f` 的 `z-index: 2` 导致第 3 行焦点项渗漏至 `--ov` 半透明遮罩上方；App 端 `AdjustOverlay` 作为独立顶层覆盖层完整遮盖底层 8 行设置。
  2. **模拟器状态栏与悬浮球**：无蓝牙耳机时顶栏如实显示「耳机未连接 / L -- · R --」；屏幕右缘绿色模拟悬浮球保留至 P8 统一调整。

## P6 验证记录

- `./gradlew :app:testProductionDebugUnitTest`：36 个测试套件共 162 项单测全部通过（0 fail, 0 skip），新增覆盖 `EntertainmentHubModule.initialIndexForEntry`、硬件音量步进转换、以及 `SpeedPracticeEngine` 12 题练习计算与跨来电暂停反应时间不膨胀。
- `./gradlew :app:connectedProductionDebugAndroidTest`（`BCI_Pad_API_35`）：17/17 项仪器测试通过（`8 test classes, 17 tests, 0 failures, 0 errors, 0 skipped`），覆盖 `EntertainmentEntryTest`（主页进娱乐聚焦迷宫）、`EntertainmentScreenTest`（2 项）及 `AppSettingsScreenTest`（6 项：调节弹层与照护返回优先、来电暂停并隔离返回键、来电界面点击隐藏的「屏幕亮度」坐标不穿透、暂停清零延迟补偿与重置 `+1` 方向、实体音量回读对齐、12 题速度练习完整闭环并写回 `ScanSettings`）。
- `./gradlew :app:assembleProductionDebug :app:assembleDemoDebug :app:lintProductionDebug`：BUILD SUCCESSFUL（0 error）。
- `node app/src/test/js/mahjong_engine_test.js`：通过。
