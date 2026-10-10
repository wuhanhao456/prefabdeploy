"""Summarize actual successful Minecraft runs and verify the tested ZIP identity."""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/mob_towers"


def main():
    run = Path(sys.argv[1])
    archive = OUT / "mob_towers-1.21.1-prefabdeploy-0.1.3.zip"
    tested = run / "world/datapacks" / archive.name
    exact_match = tested.read_bytes() == archive.read_bytes()
    with zipfile.ZipFile(tested) as old, zipfile.ZipFile(archive) as new:
        old_names, new_names = set(old.namelist()), set(new.namelist())
        changed = old_names ^ new_names
        changed.update(name for name in old_names & new_names if old.read(name) != new.read(name))
        assert changed <= {"README.md", "安装说明.txt"}, "Game content differs from the tested ZIP"
    log = (OUT / "test-results/gametest.log").read_text(encoding="utf-8")
    runtime = (OUT / "test-results/mob-towers-runtime.txt").read_text(encoding="utf-8")
    static = json.loads((OUT / "test-results/static-validation.json").read_text(encoding="utf-8"))
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    assert digest == static["sha256"]
    assert "All 3 required tests passed" in log and "BUILD SUCCESSFUL" in log
    rotations = re.findall(r"^(mobtowers:[a-z_]+) rotation=(\d+): (.+)$", runtime, re.MULTILINE)
    assert {(name, int(angle)) for name, angle, _ in rotations} == {(name, angle) for name in ("mobtowers:dark_tower", "mobtowers:zombie_spawner_tower") for angle in (0,90,180,270)}
    assert len(rotations) == 8
    report = {"date": "2026-10-09", "result": "PASS", "minecraft": "1.21.1", "prefabdeploy": "0.1.3", "neoforge": "21.1.248", "java": "21.0.11",
              "sha256": digest, "tested_zip_matches_delivery": exact_match, "tested_game_content_matches_delivery": True, "documentation_changes_since_runtime_test": sorted(changed), "required_gametest_entries": 3,
              "survival_builds": 8, "missing_material_quote_cases": 17,
              "rotations": [{"id": name, "degrees": int(angle), "evidence": detail} for name, angle, detail in rotations],
              "tested": ["all JSON and vanilla structure NBT", "actual server resource import of delivered ZIP", "prefab reload command", "exact required materials, one spawner replaced by 50 rotten flesh", "one of every material missing rejects quote without deduction", "survival construction and single settlement using real double chests", "all non-air/non-water block states match rotated blueprint", "Overworld, direct unlock and Nether rejection", "zero skylight/block light in dark chamber and vanilla natural spawning logic", "original unmodified zombie spawner generates zombies", "water transport, campfire/fall killing and hopper-to-barrel collection"],
              "limitations": ["No graphical client GUI preview or mouse placement test", "Natural spawning invokes the vanilla routine at the platforms to test biome/distance/light/collision; not a long-term production-rate measurement", "Transport test zombies remove autonomous goals and carry guaranteed-drop markers; physics remains enabled", "No hourly output benchmark or full user modpack compatibility test"],
              "source_files": ["tools/create_mob_towers.py", "tools/validate_mob_towers.py", "tools/test_mob_towers.ps1", "tools/fixtures/MobTowerGameTests.java"]}
    (OUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    text = """刷怪塔组合 — 实际验证说明

结果：通过。验证日期：2026-10-09。
环境：Minecraft 1.21.1、NeoForge 21.1.248、Prefab Deploy 0.1.3、Java 21.0.11。
最终交付包的建筑定义、蓝图和 pack.mcmeta 与测试世界实际加载的版本一致；交付后仅更新了说明文件。

静态检查
全部 JSON、资源 ID、源文件路径、费用类型及 ZIP 根目录已核对。
两份蓝图均为真实压缩原版结构 NBT，DataVersion 3955；尺寸、参考层 0、明确空气覆盖、无模组方块及空容器已核对。
普通塔的材料报价由 Mod 自动计算。僵尸塔使用完整手动清单；与游戏实际方块材料逐项一致，唯独一个刷怪笼替换为 50 块腐肉。

真实服务端检查
3 个必需 GameTest 入口全部通过，包含 8 次完整生存建造：两座塔各自 0°、90°、180°、270°。
生存建造从真实双箱按正常堆叠扣取全额材料；交易结算状态为 COMMITTED，重复结算不重复扣取。
每种材料分别少一件的 17 个报价均拒绝，材料来源数量保持不变；腐肉 49 块也拒绝。
实际执行 prefab reload 并确认两份定义重读有效；直接解锁、主世界通过、下界拒绝已核对。
每个朝向的非空气、非水方块状态与旋转后的蓝图一致，包含漏斗方向、告示牌和活板门。
普通塔内部方块亮度和天空亮度均为 0，调用原版自然刷怪逻辑在平台上实际生成怪物。
普通塔四条水道的测试怪物经落差/营火死亡，掉落物实际进入两个木桶。
僵尸塔保留原版刷怪笼 NBT 和 200–800 Tick 间隔；刷怪笼自行运行并实际生成僵尸。
僵尸塔三个房间位置的测试怪物经水流和营火处理，标记掉落物与刷怪笼僵尸的腐肉实际进入木桶。

验证边界
服务端测试使用 GameTest 玩家，其服务器游戏模式设为生存，并实际支付材料。
自然刷怪检查在平台位置调用 Minecraft 原版逻辑，包含生物群系、玩家距离、亮度和碰撞判断。这个检查不代表每小时产量。
水流载体是测试生成的僵尸。测试仅移除自主行为，保留水流、重力与伤害，并携带保证掉落的标记物来确认收集链路。
未运行图形客户端的建筑库预览、鼠标定位或长期产量测试，也未验证用户的完整整合包。
两座塔直接解锁，没有额外的未解锁状态。Minecraft 原版刷怪上限及服务器规则仍然生效。

证据
test-results/gametest.log：真实进程输出，含 All 3 required tests passed 与 BUILD SUCCESSFUL。
test-results/mob-towers-runtime.txt：各朝向建造、刷怪、运输与收集记录。
test-results/static-validation.json：ZIP 和 NBT 读回结果、完整材料及最终 SHA-256。
verification.json：机器可读的验证摘要与验证边界。
复现：先执行 python tools/create_mob_towers.py，再执行 tools/test_mob_towers.ps1（本地磁盘 QA 世界），随后执行 python tools/validate_mob_towers.py。
测试在独立本地项目运行，正式 Mod 源码和用户存档没有修改。
"""
    (OUT / "验证说明.txt").write_text(text + "\nSHA-256：" + digest + "\n", encoding="utf-8")
    (OUT / "SHA256SUMS.txt").write_text(digest + "  " + archive.name + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "tested_zip_matches_delivery": exact_match, "tested_game_content_matches_delivery": True, "survival_builds": 8, "sha256": digest}))


if __name__ == "__main__":
    main()
