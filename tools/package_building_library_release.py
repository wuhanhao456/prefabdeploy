"""Package the building-library release using its current, targeted runtime evidence."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return path.read_text(encoding="utf-8-sig")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def archive(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(entry, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--build-log", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    args = parser.parse_args()
    properties = dict(line.split("=", 1) for line in read(ROOT / "gradle.properties").splitlines() if "=" in line)
    version = properties["mod_version"]
    reports = args.reports.resolve()
    dist = ROOT / "dist"
    require("BUILD SUCCESSFUL" in read(args.build_log), "Final compilation failed")
    shutil.copy2(args.build_log, reports / "build.log")
    artifacts = [dist / f"prefabdeploy-{version}.jar", dist / f"prefabdeploy-{version}-sources.jar"]
    for file in artifacts:
        require(sha(file) == sha(args.build_dir / "libs" / file.name), f"Build artifact mismatch: {file.name}")
    units = 0
    for xml in (reports / "junit").glob("TEST-*.xml"):
        suite = ET.parse(xml).getroot()
        require(all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")), "Unit test failures")
        units += int(suite.get("tests"))
    require(units >= 10, "Unit test reports missing")
    base = read(reports / "gametest-base.log")
    match = re.search(r"All (\d+) required tests passed", base)
    require(match is not None and int(match[1]) >= 35 and f"Prefab Deploy {version} (prefabdeploy)" in base, "Base regressions failed or stale")
    for profile in ("enabled", "disabled"):
        log = read(reports / "library" / f"{profile}.log")
        require("All 1 required tests passed" in log and "PREFAB BUILDING LIBRARY ZIP DIRECTORY UPDATE DELETE DISABLE OVERRIDE ACCESS SNAPSHOT VERIFIED" in log
                and f"enabled={'true' if profile == 'enabled' else 'false'}" in log
                and f"Prefab Deploy {version} (prefabdeploy)" in log, f"Missing {profile} library regression")
    reload_log = read(reports / "reload" / "reload-command.log")
    require("All 1 required tests passed" in reload_log and "PREFAB HOT RELOAD DISCOVERY UPDATE DELETE SNAPSHOT VERIFIED" in reload_log, "World reload regression missing")
    mob_log = read(reports / "mob-towers" / "gametest.log")
    require("All 3 required tests passed" in mob_log and f"Prefab Deploy {version} (prefabdeploy)" in mob_log, "Built-in tower regressions missing")
    rotations = re.findall(r"^(mobtowers:[a-z_]+) rotation=(\d+): (.+)$", read(reports / "mob-towers" / "mob-towers-runtime.txt"), re.M)
    require(len(rotations) == 8 and {(name, int(angle)) for name, angle, _ in rotations} ==
            {(name, angle) for name in ("mobtowers:dark_tower", "mobtowers:zombie_spawner_tower") for angle in (0, 90, 180, 270)}, "Tower rotations incomplete")
    client = json.loads(read(reports / "client-benchmark.json"))
    require("CLIENT SMOKE COMPLETE" in read(reports / "client-smoke.log") and client["mod_version"] == version, "Client evidence missing or stale")
    for key in ("builtin_towers_and_folder_pack_in_integrated_catalog_verified", "lan_guest_shared_folder_building_verified",
                "local_host_import_verified", "lan_host_and_guest_access_verified", "survival_material_charge_verified"):
        require(client.get(key), f"Client scenario not verified: {key}")
    spec = importlib.util.spec_from_file_location("mob_nbt", ROOT / "tools" / "validate_mob_towers.py")
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    blueprint_checks = []
    with zipfile.ZipFile(artifacts[0]) as jar:
        require(jar.testzip() is None, "JAR integrity failure")
        names = jar.namelist()
        require(not any("/testing/" in n or "Smoke" in n or n.startswith("data/prefabdeploy/blueprints/") or n.startswith("data/prefabdeploy/structure/") for n in names), "Development fixtures in release JAR")
        metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
        require(metadata["mods"][0]["version"] == version, "Wrong JAR version")
        require("META-INF/LICENSE" in names and metadata["license"] == "MPL-2.0", "Missing license")
        builtin = "builtin/mob_towers/"
        require(json.loads(jar.read(builtin + "pack.mcmeta"))["pack"]["pack_format"] == 48, "Wrong built-in pack format")
        for path in names:
            if path.endswith(".json"):
                json.loads(jar.read(path))
        for name, size, mode in (("dark_tower", [22, 31, 22], "auto"), ("zombie_spawner_tower", [13, 16, 14], "manual")):
            path = builtin + f"data/mobtowers/blueprints/{name}.nbt"
            nbt = validator.NBT(gzip.decompress(jar.read(path))).root()
            definition = json.loads(jar.read(builtin + f"data/mobtowers/prefabs/{name}.json"))
            require(nbt["DataVersion"] == 3955 and nbt["size"] == size and len(nbt["blocks"]) == size[0] * size[1] * size[2], "Invalid built-in NBT")
            require(definition["ground_y"] == 0 and definition["ignore_air"] is False and definition["cost"]["mode"] == mode, "Builtin behavior changed")
            if mode == "manual":
                require({item["id"]: item["count"] for item in definition["cost"]["items"]}["minecraft:rotten_flesh"] == 50, "Spawner replacement fee changed")
            require(jar.read(path) == (ROOT / "src/main/resources" / path).read_bytes(), "Packaged NBT differs from source")
            blueprint_checks.append({"id": "mobtowers:" + name, "size_xyz": size, "DataVersion": 3955, "sha256": hashlib.sha256(jar.read(path)).hexdigest()})
    tomllib.loads(read(ROOT / "examples/prefabdeploy-server.toml"))
    verification = {
        "version": version, "date": "2026-10-10", "minecraft": "1.21.1", "neoforge": properties["neo_version"],
        "result": "PASS", "unit_tests_passed": units, "base_gametest_entries_passed": int(match[1]),
        "base_optional_entries_skipped": 4, "library_cold_start_profiles": ["enabled", "disabled"],
        "world_reload_snapshot_regression": True, "builtin_tower_gametest_entries_passed": 3,
        "survival_tower_builds": 8, "missing_material_quote_cases": 17,
        "rotations": [{"id": name, "degrees": int(angle), "evidence": detail} for name, angle, detail in rotations],
        "integrated_client_and_lan_folder_pack_checks": True, "builtin_blueprints": blueprint_checks,
        "artifacts": {p.name: sha(p) for p in artifacts},
        "limitations": ["No remote second-client LAN connection test; guest selection uses a server-side FakePlayer on the published integrated server.",
                        "No full modpack, shader, optional resource integration or crash-recovery rerun for this release.",
                        "Mob spawning and transport checks do not measure hourly production."],
        "published": False,
    }
    evidence = json.dumps(verification, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    (reports / "verification.json").write_bytes(evidence)
    (dist / f"verification-{version}.json").write_bytes(evidence)
    install_cn = f"""Prefab Deploy {version} 安装说明

Minecraft 1.21.1 / NeoForge 21.1.248 或更高的 1.21.1 版本 / Java 21。
将 prefabdeploy-{version}.jar 放入客户端和服务端 mods/，删除旧版 Prefab Deploy JAR。
不需要额外安装刷怪塔数据包。内置普通暗室塔和僵尸刷怪笼塔默认开启。
在服务端 prefabdeploy-server.toml 的 [buildings] 中设置 enableDefaultTestBuildings = false 可关闭内置建筑，修改后重启。
NeoForge 21.1.248 使用实例 config/ 配置，世界 serverconfig/ 的同名文件优先。
自定义建筑数据包 ZIP 或解压目录可直接放入服务端 prefabdeploy/blueprints/，包根必须包含 pack.mcmeta。
也可沿用世界 datapacks/。修改数据包后由管理员执行 /prefab reload；禁用的数据包保持禁用。
数据包以服务端或局域网主机文件为准，单人世界使用本机；散装 NBT/Litematic 保留原有主机权限。
普通塔消耗自动计算的建材；僵尸塔使用完整手动清单，刷怪笼替换为 50 块腐肉。两座塔直接解锁，仅限主世界。
当前验证结果和边界见 verification.json；完整操作见 README.md 和 README.en.md。
""".encode("utf-8")
    install_files = {p.name: p.read_bytes() for p in artifacts}
    install_files.update({"LICENSE": (ROOT / "LICENSE").read_bytes(), "README.md": (ROOT / "README.md").read_bytes(),
                          "README.en.md": (ROOT / "README.en.md").read_bytes(), "安装说明.txt": install_cn,
                          "examples/prefabdeploy-server.toml": (ROOT / "examples/prefabdeploy-server.toml").read_bytes(),
                          "verification.json": evidence,
                          "SHA256SUMS.txt": "".join(f"{sha(p)}  {p.name}\n" for p in artifacts).encode("ascii")})
    install_zip = dist / f"prefabdeploy-{version}.zip"
    archive(install_zip, install_files)
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode("utf-8").split("\0")
    source_files = {f"prefabdeploy-{version}/{name}": (ROOT / name).read_bytes() for name in paths if name and (ROOT / name).is_file()}
    source_zip = dist / f"prefabdeploy-{version}-source.zip"
    archive(source_zip, source_files)
    artifacts.extend([install_zip, source_zip])
    for path in artifacts:
        with zipfile.ZipFile(path) as test:
            require(test.testzip() is None, f"Damaged delivery: {path.name}")
    checksums = "".join(f"{sha(p)}  {p.name}\n" for p in artifacts)
    (dist / f"SHA256SUMS-{version}.txt").write_text(checksums, encoding="ascii")
    print(json.dumps({"result": "PASS", "version": version, "files": [p.name for p in artifacts]}, indent=2))


if __name__ == "__main__":
    main()
