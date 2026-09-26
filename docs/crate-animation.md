# 开箱动画 1.1.35

以 `../临时/开箱参考视频.mp4` 的实际画面校准。保留游戏内真实奖池和服务端开箱结果，
界面采用固定比例画布、落箱扶正、带独立铰链的箱盖、横向卡片转盘、暗场和物品展示。

仓库与开箱之间硬切；开箱背景用 533ms 从 24px 失焦合到 4px。确认后 333ms 撤去面板和物品条，
1634ms 持握，6033ms 减速，停稳 100ms 后撤卡并压暗，133ms 后开始物品登场和背景淡回。
卡片对齐的补偿函数在首尾导数为零，避免转盘末端突然加速。

`textures/gui/crate/` 的庭院和箱体表面来自提供的参考视频。庭院被箱子遮挡的区域用邻近石板重建，
UI、水印、字幕和鼠标已移除；表面贴图经透视校正后贴在实时投影的箱体上。它是视频素材的重建，
并非原游戏的庭院和箱体三维模型。最终物品、名称、品质色来自安装的皮肤提供者。

像素素材生成脚本保存在工作区 `.video-analysis/make_courtyard_plate.py` 和
`.video-analysis/extract_case_materials.py`。模组运行不依赖参考视频、Python 或这些脚本。

## 客户端验证

先构建，再单独启动录制，不能并发运行两份 Gradle 客户端。

```powershell
.\gradlew.bat build --gradle-user-home "D:\Backup\mc mod\.gradle-user-home"
.\gradlew.bat -I tools\crate-smoke.gradle runClient --gradle-user-home "D:\Backup\mc mod\.gradle-user-home" -PcrateSmokeW=1920 -PcrateSmokeH=1080 -PcrateSmokeScale=2 -PcrateSmokeOut=frames-release-hd
powershell -ExecutionPolicy Bypass -File tools\crate-smoke\render-video.ps1 -FrameFolder frames-release-hd -Out "D:\Backup\mc mod\临时\开箱动画-1.1.35-1080p.mp4"
```

`-PcrateSmokeChecks=true` 额外点击取消、重新打开确认框，再确认开箱。
录制从仓库开始，记录真实帧缓冲和毫秒时间戳，结果展示完成后鼠标点击关闭并验证返回仓库。
`timing.csv` 为采集时间，`events.csv` 为动作与相位断言。编码保持原始时长，输出 30fps 视频。

录制运行真实 Minecraft 客户端与生产界面；库存和服务端结果使用测试夹具，皮肤使用测试专用
API 提供者与已安装的武器模型。它验证画面、动画和交互，不覆盖联网发奖。测试源码、奖池和语言
文件只在 `crateSmoke` 源集内，不进入发布 jar。

2026-09-25 验证：274 项测试通过；1920×1080 / GUI 2 采集 413 帧、16.094 秒，
1024×768 / GUI 2 采集 473 帧、17.051 秒。两轮均完成仓库进出和完整开箱；4:3 额外通过
取消、重新打开和鼠标确认。最终录像位于工作区 `临时`，对应帧、时间戳和事件位于
`build/crate-client-run/frames-release-hd` 与 `frames-release-4x3`。
