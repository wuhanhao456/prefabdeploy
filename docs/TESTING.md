# Prefab Deploy 编译状态与历史测试记录

**0.1.1 已按后续用户要求运行测试。** 当前报告见 [TESTING-0.1.1.md](TESTING-0.1.1.md)：10 项 JUnit、无联动/全联动各 31 个 GameTest 入口、三个阶段冷启动恢复、真实客户端及性能目标通过，均使用 NeoForge 21.1.248。日志位于 `test-results/release-0.1.1/`，当前结果位于 `test-results/acceptance.json`。初次“仅编译”状态另存 `acceptance-0.1.1-build-only.json`，手工清单见 RELEASE-0.1.1.md。

下文全部为 **0.1.0 历史测试**，测试日期为 2026 年 10 月 3 日。结果来自当时实际 Java、NeoForge GameTest、独立 JVM 强制退出和 OpenGL 客户端运行；未使用模拟图像代替游戏截图。完整日志与截图保留在 `docs/test-results/`，旧验收摘要另存 `acceptance-0.1.0.json`，不计为 0.1.1 通过结果。以下复现命令本次未执行。

## 测试环境和复现

Minecraft 1.21.1、NeoForge 21.1.252、Java 21.0.11，Windows Server 2025。CPU 为 AMD EPYC 7F52，16 核 32 线程，内存 128 GiB。客户端使用 NVIDIA RTX 5060 Ti，1280×800，GUI 缩放 2，渲染距离 8、模拟距离 5，关闭 VSync、限制 200 FPS。调度器为默认 2 ms 软预算、512 次操作。

PowerShell 7 和 Java 21 环境中执行：

```powershell
.\Test.ps1
.\Test.ps1 -Compat -VssJar 'C:\mods\ViScriptShop-neoforge-1.21.1-1.2.2.4.jar' -Crash -Client
```

脚本在本地缓存中为每轮创建新测试目录，避免上一轮未完成日志、世界内容或领地影响下一轮。`-Client` 创建脚本自己的 `prefab-smoke` 单人世界，以真实网络请求浏览、验证和部署；客户端结束后自动退出。`-Crash` 对 BLOCKS、TICKS、COMMIT 三阶段分别运行写入 JVM 和读取 JVM。写入 JVM 有意使用 `Runtime.halt(91)`，脚本同时核对准备标记和退出码，读取 JVM 必须正常通过。

开发测试类及强制终止入口从发布 JAR 排除。可选依赖只在 `-Compat` / `-VssJar` 测试配置中加入，不打进安装包。

## NeoForge 最低版本适配

当前安装包以 **21.1.248** 编译，元数据声明 `[21.1.248,)`，Minecraft 版本仍限定为 1.21.1。编译版本 `neo_version` 和运行最低版本 `neo_min_version` 分开配置，测试较新的 NeoForge 不会提高安装包的最低要求。

0.1.0 的最低版本兼容性测试写入 `test-results/neoforge-21.1.248/`。复现时使用独立本地缓存，例如：

```powershell
.\Test.ps1 -CacheRoot 'C:\prefab-test\248' -NeoForgeVersion '21.1.248' -ReportDir '.\docs\test-results\neoforge-21.1.248'
.\Test.ps1 -CacheRoot 'C:\prefab-test\248' -NeoForgeVersion '21.1.248' -ReportDir '.\docs\test-results\neoforge-21.1.248' -Compat -VssJar 'C:\mods\ViScriptShop-neoforge-1.21.1-1.2.2.4.jar' -Crash -Client
```

248 已通过编译、10 项单元测试，以及无联动与全联动两套 24 入口 GameTest。无联动套件实际执行 20 项核心场景，另 4 项为可选依赖场景；全联动套件全部执行。本次适配的强制重启和客户端复测已停止，未将未完成的检查计为通过。

下文原始性能表、客户端和硬退出记录对应此前的 21.1.252，保留原记录供参考。它们不是 248 的复测结果。

## 功能和恢复

| 检查 | 结果和证据 |
| --- | --- |
| 10 项 JUnit | 四种旋转及 48 格边界、地下参考层、Litematic 跨 64 位打包、负体积、截断数据、校验和与原子替换；`unit-test.log` 和 `junit/` |
| 无联动 GameTest | 24 个入口，20 项核心场景实际执行，4 项依赖专用场景跳过；`gametest-base.log` |
| 全联动 GameTest | 24 项全部实际执行；`gametest-compat.log`，版本见 API 文档 |
| 导入和完整复制 | 多区域、负尺寸、未选空隙、未知状态、缺失库存物品、损坏数据、重叠冲突；库存、告示牌、旗帜、装备实体四种旋转；FTB detector 的 Object、Radius 和位置 NBT |
| 费用和规则 | 材料数量、只收建筑材料、物品/XP/VSS 混合保存重载、重复退款/结算、满库存待领取、缺失嵌套规则、资金不足、重载期间固定成本提供者 |
| 世界权限 | 基岩、FTB 他人领地跨区块整栋拒绝、源 Tick 位置保护、未生成区块不被生成、不收费 |
| 区域保护 | 直接方块写入拒绝、跨区块漏斗暂停、重叠任务拒绝、全服共享操作预算 |
| 故障注入 | 扣款后、方块放置、NBT 写入、实体生成、结果保存阶段异常；恢复原箱子/库存/地形、清除本任务实体、退还费用 |
| 强制退出和冷启动 | BLOCKS：已扣费未施工；TICKS：方块/NBT/实体已生成；COMMIT：新世界和提交日志已落盘。三轮各一个新 JVM 冷读成功，重复处理没有复制 |
| 真实客户端 | 中文库、拖动/缩放 3D、世界预览与实际建筑对齐；真实浮空 A/B/C、候选点、两阶段确认、三枚信标消耗并归还 |

当前普通示例和 FTB 方块的结果不能证明所有模组机器都能重建专有网络。复杂多方块及外部服务限制保留在管理员说明中。

## 性能

`client-benchmark.json` 同时记录正常 20 TPS 单人服务器的调度器与全 Tick 耗时、TPS、冷网格准备、热 GUI 提交时间、GPU timestamp、移动世界预览和进程 Java 堆占用。每个热 GUI 场景至少 400 帧；世界场景每 Tick 移动锚点、循环四种旋转，并开启视锥裁剪。测试源包含石头、玻璃和约 1/17 的明确空气，使用 `tools/package_examples.py` 可再生成。

| 位置数 | 冷准备 ms | 部署调度 p95 ms | GUI CPU p95 ms | GUI GPU p95 ms | 世界预览 CPU p95 ms | 世界预览 GPU p95 ms | TPS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10,000 | 252.83 | 2.916 | 0.201 | 0.024 | 0.238 | 0.021 | 20.00 |
| 50,000 | 750.33 | 2.007 | 0.535 | 0.058 | 0.574 | 0.063 | 20.00 |
| 100,000 | 1098.12 | 2.000 | 0.789 | 0.112 | 0.824 | 0.127 | 20.00 |

所有样例的部署额外主线程 p95 低于 5 ms，热预览的 CPU 和 GPU 计时分别低于 3 ms。预览指标是额外渲染代码的 CPU 时间和异步 GPU 时间，不是整个游戏帧的基线相减；全帧仅记录单帧示例值。Java 堆是整个客户端及集成服务器的占用，约 418 至 664 MiB，未单独归因给本 Mod。

十万位置场景完整服务器 Tick p95 为 4.574 ms，最终邻居更新也纳入测量并确认排空。一次调度的最大值达到 46.81 ms，说明 fsync、区块序列化和单操作回调仍会产生尖峰，2 ms 是软预算。硬件、其他模组、蓝图形状与方块实体数量变化时需要重新测量。

`server-benchmark.json` 的 GameTestServer 为加速 Tick，只用于调度器与回归测试，不将其墙钟速度当作生存服务器 TPS。`multiplayer-benchmark.json` 记录两个玩家各 50,000 位置同时部署，并额外提交一个冲突任务；操作次数不超过全服 512，成功建筑和冲突拒绝分别核对。日志保留故障注入产生的预期 ERROR，是否通过以末尾 GameTest 结果为准。

## 截图

`screenshots/gallery-library.png` 展示中文独立 3D 预览；`gallery-world-preview.png` 与 `gallery-deployed.png` 用于位置对照；`beacon-a-candidates.png`、`beacon-c-candidate.png`、`beacon-complete-preview.png` 展示三点定位；`100000-preview.png` 与 `100000-world-preview.png` 展示大型缓存模型。
