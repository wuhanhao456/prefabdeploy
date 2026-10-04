# BeLoong 1.4 资源兼容

版本基准为 [BeLoong 官方快照 7f95d495](https://github.com/PorkChop-ZLG/BeLoong/tree/7f95d49553a38ba29a8b497e9e6f12daf4280178)。测试使用 Minecraft 1.21.1、Java 21 和 NeoForge 21.1.248。

下载 [资源兼容构建](https://github.com/wuhanhao456/prefabdeploy/releases/download/v0.1.2/prefabdeploy-0.1.2-resource-compat.zip)。版本号仍为 Prefab Deploy 0.1.2。发布页中的旧独立 JAR 和旧 ZIP 保留各自的内容。

| 模组 | 基准 JAR 版本 | JAR 内声明版本 |
| --- | --- | --- |
| 超越维度 Beyond Dimensions | 0.7.30 | 0.7.30 |
| 精妙背包 Sophisticated Backpacks | 3.26.3.2158 | 3.26.3 |
| 精妙核心 Sophisticated Core | 1.5.1.2341 | 1.5.1 |
| Curios | 9.5.1 | 9.5.1+1.21.1 |

将 ZIP 内的 JAR 放入客户端和服务器的 `mods/`。同一实例只保留一个 Prefab Deploy JAR。兼容模组均为可选依赖。

库存足够支付时，不调用背包和网络来源。免费任务和创造任务也不调用这些来源。需要外部来源时，适配器检查对应模组的声明版本。声明版本不同于表中值时，适配器拒绝该来源。其他构建未验证。安装包不包含第三方 JAR。

## 扣取规则

已有建筑 `cost` JSON 无需修改。物品按以下顺序扣取，数量可以跨来源合并：

1. 玩家库存。
2. 随身精妙背包。
3. 当前绑定的超越维度主网络。

背包依次读取主物品栏、胸甲和副手。Curios 背包最后读取，按槽位名称和索引排列。

适配器通过官方包装器读取顶层背包的直接库存。普通背包按内容 UUID 去重。链接背包按共享存储组 UUID 去重。Curios 只读取已启用的装备槽。装饰槽、嵌套背包、升级内部库存、无限槽位和不可访问槽位不参与扣取。

网络来源只使用玩家当前主网络。扣费前确认玩家仍是所有者、管理员或成员。模拟提取和实际提取使用真实资源键。扣取与退款保留物品组件。网络数量使用 `long`。

自动材料中的水免费。自动岩浆先按上述来源顺序消耗岩浆桶。每个桶支付一格岩浆。剩余格数从网络扣取每格 1000 mB 岩浆。999 mB 不能支付一格，1000 mB 可以。

流体退款保留原资源键和组件。精妙背包储罐不参与扣取。手动 `items` 配置的水桶和岩浆桶仍按物品收费。

XP、ViScriptShop 原生货币和自定义费用继续使用现有协议。创造豁免和位置保护保持原规则。旧报价和旧事务保留原费用与恢复规则。升级不会对已有任务重新计价。

## 事务文件

扣费前重新检查资源，并固定分配方案。预留按以下顺序保存：

1. 原子保存玩家自身的物品、XP、货币扣费结果和外部来源意图。
2. 依次扣取外部来源，并保存各来源的预留结果。
3. 保存玩家的预留确认 `resourcesReady`。

全部预留成功后才开始施工。

| 来源 | 世界根目录下的文件 | 保存身份 |
| --- | --- | --- |
| 玩家库存 | `playerdata/<玩家UUID>.dat` | 事务 UUID |
| 普通背包 | `data/sophisticatedbackpacks.dat` | 事务 UUID + 内容 UUID |
| 链接背包 | `data/sophisticatedcore_linked_storage_groups.dat` | 事务 UUID + 共享组 UUID |
| 超越维度 | `data/BDNet_<网络ID>.dat` | 事务 UUID + 网络 ID |

外部文件的 `data.prefabdeploy_resource_receipts` 保存所有者、请求、状态和实际扣取资源。资源变化与凭据写入同一个压缩 NBT 文件。文件强制刷新后执行原子替换。可选 Mixin 同步保存这些 SavedData，防止异步保存的旧快照覆盖新扣费。普通背包内容改变后，同一内容 UUID 的现存包装器会刷新。

结算和退款使用事务 UUID 作为幂等键。失败时，资源退回凭据记录的原来源。移动背包、切换网络或撤销扣费权限不会改变退款目标。

原存储容量不足时，剩余资源保存在 `REFUND_PENDING` 凭据中。来源缺失、文件不一致、版本不支持或 API 结果不确定时，任务保留凭据并等待恢复。恢复流程不通过余额变化猜测扣费结果。管理员操作见[恢复说明](ADMIN.md#部署事务和恢复)。

## 验证与复现

验证记录见[资源兼容验证](TESTING-RESOURCES.md)。原始 JAR 的 SHA-256 保存在测试结果目录。准备一个仅包含上述四个原始 JAR 的目录，然后运行：

```powershell
.\Test-Resources.ps1 -CacheRoot C:/pdr -ResourceModsDir C:/mods/beloong-resources -Crash
.\Test.ps1 -CacheRoot C:/pdb -ReportDir docs/test-results/resources-base -Crash
.\Build.ps1 -CacheRoot C:/pdb -Task @('jar', 'sourcesJar')
```

测试使用独立世界。缓存路径须保持简短，以满足 Windows Java 本地套接字的路径长度限制。`Test-Resources.ps1` 检查 GameTest 成功标记。空测试运行不算成功。

崩溃测试在指定位置执行 JVM `halt(91)`。随后用同一世界冷启动。测试核对每个原来源的资源，并重复执行恢复。

本次测试使用服务器和 FakePlayer。整套 BeLoong 的客户端操作尚未验证。其他机器、升级和存储附属模组也须另行验证。
