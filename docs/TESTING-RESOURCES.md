# BeLoong 资源兼容验证

测试日期：2026-10-04。环境为 Windows Server 2025、Microsoft OpenJDK 21.0.11、Minecraft 1.21.1 和 NeoForge 21.1.248。资源模组使用 [BeLoong 官方快照](https://github.com/PorkChop-ZLG/BeLoong/tree/7f95d49553a38ba29a8b497e9e6f12daf4280178) 中的四个原始 JAR。测试使用独立世界。

版本和文件名含构建号的区别见[资源兼容](RESOURCE-COMPAT.md)。原始 JAR 哈希见 [mod-sha256.txt](test-results/resources-compat/mod-sha256.txt)。结果日志保留在 `test-results/resources-compat/`。

## 安装组合

GameTest 注册 21 项用例。缺少依赖的用例会跳过。下表只将实际执行功能检查的用例计入“实际检查”。五种组合均完成服务器启动和实际部署。

| 组合 | 实际检查 | 无依赖而跳过 | 日志 |
| --- | --- | --- | --- |
| 无资源兼容模组 | 6 | 15 | [none.log](test-results/resources-compat/none.log) |
| 精妙背包 + 精妙核心 | 14 | 7 | [backpacks_no_curios.log](test-results/resources-compat/backpacks_no_curios.log) |
| 精妙背包 + 精妙核心 + Curios | 15 | 6 | [backpacks.log](test-results/resources-compat/backpacks.log) |
| 仅超越维度 | 11 | 10 | [network.log](test-results/resources-compat/network.log) |
| 四模组完整组合 | 21 | 0 | [full.log](test-results/resources-compat/full.log) |

材料与来源检查覆盖以下场景：

- 库存、多个背包和网络合并支付。
- 主物品栏、胸甲、副手和 Curios 中的顶层背包。
- 普通背包按内容 UUID 去重，链接背包按共享组 UUID 去重。
- 排除嵌套背包和背包储罐。
- 背包本体与内容同时作为费用。
- 同一物品的不同名称组件按原组件扣取和退款。
- 自动水免费，手动水桶收费。
- 岩浆桶与网络流体合并支付自动岩浆费用。
- 999 mB 不足，1000 mB 足额。
- 网络流体不能支付手动岩浆桶费用。
- 网络数量为 `Long.MAX_VALUE - 1` 时准确扣取和退款。

交易与恢复检查覆盖以下场景：

- 两个玩家竞争共享网络资源。
- 报价后、扣费前资源改变。
- 玩家凭据保存后撤销网络成员权限。
- 另一包装器在扣费前改变普通背包内容。
- 扣费后移除背包或更换网络，退款仍返回原来源。
- 原来源文件缺失时保留凭据。
- 背包或网络退款空间不足时，腾出空间后重试。
- 物品组件缺失时拒绝部分解析，并保留退款凭据。

## 硬崩溃与冷启动

写入运行执行 `Runtime.halt(91)`，不经过正常关服。读入运行用同一个世界重新启动。恢复依据玩家文件、恢复日志和三个外部 SavedData 文件。恢复不使用上一 JVM 的对象，也不通过余额变化猜测结果。

夹具在库存放置 2 个钻石，在普通背包放置 3 个，在链接背包放置 4 个，在网络放置 5 个。网络另有 1000 mB 岩浆。部署收取 12 个钻石和一格岩浆。

PREPARED 冷启动后，资源退回各原来源，地形恢复原状。COMMITTED 冷启动后，建筑和收费结果保持不变。每次读入完成后，再重复退款或结算三次。测试核对每个来源和资源总量。

首轮 20 个崩溃点全部通过。随后修正普通背包包装器刷新，并复测两个崩溃点：最终玩家确认前、普通背包退款保存后。两次复测均通过，且核对了三个外部来源的冷启动恢复。对应日志保留最后一次运行的结果。其余 18 个崩溃点未在该修正后重跑。

| 崩溃点 | 数量 | 日志前缀 |
| --- | --- | --- |
| 玩家原始凭据保存后、最终确认前、最终确认后 | 3 | `crash-player_saved`、`crash-resources_before_confirmation`、`crash-resources_ready` |
| 玩家结算保存后、玩家退款保存后 | 2 | `crash-player_commit_saved`、`crash-player_refund_saved` |
| 三个外部来源各自扣费保存前/后 | 6 | `crash-<kind>-source_before_save`、`crash-<kind>-source_saved` |
| 三个外部来源各自结算保存后 | 3 | `crash-<kind>-source_commit_saved` |
| 三个外部来源各自退款保存前/后 | 6 | `crash-<kind>-source_refund_before_save`、`crash-<kind>-source_refund_saved` |

`kind` 为 `backpack`、`linked_backpack` 或 `network`。每个前缀均有 `-write.log` 和 `-read.log`。写入日志包含崩溃标记和退出码 91。读入日志包含 GameTest 成功标记。测试脚本会拒绝缺失成功标记的运行。

## 原有回归与交付

最终构建的 10 项 JUnit 检查通过。基础服务器的 35 个 GameTest 入口通过，其中 4 个可选联动入口跳过。记录见[最终基础回归](test-results/resources-base-final/)。初始基础回归通过 BLOCKS、TICKS、COMMIT 三个原有硬崩溃场景。记录见[原有恢复回归](test-results/resources-base/)。

KubeJS、FTB Library/Teams/Chunks/Quests 和 ViScriptShop 1.2.2.4 组合通过原有 35 项 GameTest。记录见[原有联动回归](test-results/resources-existing-compat/gametest-compat.log)。检查覆盖 XP、原生货币、自定义费用、创造豁免、位置保护和恢复协议。该组合不包含新增的四个资源模组。资源兼容组合由上表单独验证。

`jar` 与 `sourcesJar` 构建通过。正式 JAR 包含来源桥接和持久化 Mixin。测试类和测试建筑未进入正式 JAR。中英文各有 230 个翻译键。键名和参数数量一致。全部主资源 JSON 解析通过。构建日志见 [build.log](test-results/resources-compat/build.log)。交付文件哈希见 [build-sha256.txt](test-results/resources-compat/build-sha256.txt)。

资源兼容 ZIP 和外部校验清单已上传到 GitHub。GitHub 文件大小和 SHA-256 与本地文件一致。重新下载后的文件及 ZIP 内校验清单均通过核对。发布记录见 [publication.json](test-results/resources-compat/publication.json)。

## 复现与范围

```powershell
.\Test-Resources.ps1 -CacheRoot C:/pdr -ResourceModsDir C:/mods/beloong-resources -Crash
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/resources-base -Crash
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/resources-existing-compat -Compat -VssJar C:/mods/ViScriptShop-neoforge-1.21.1-1.2.2.4.jar
.\Build.ps1 -CacheRoot C:/pdb -Task @('jar', 'sourcesJar')
```

测试使用 NeoForge GameTest 服务器和 FakePlayer。本次未启动整套 BeLoong 客户端。背包 GUI、网络 GUI 和其他存储附属模组未完成人工验证。崩溃测试覆盖进程异常退出与重启。断电、硬盘损坏和操作系统缓存丢失未模拟。

第一次资源测试因命名空间配置错误而未运行用例。该次运行不计为通过。修正配置后，脚本增加了成功标记检查。正式用例读取 Curios 官方装备槽和原存储身份。

缺少部分可选联动模组时，超越维度输出四条对应战利品表注册错误。资源组合仍完成测试。这些错误未造成 Prefab Deploy 收费失败。该测试结果不表示整套整合包日志无错误。
