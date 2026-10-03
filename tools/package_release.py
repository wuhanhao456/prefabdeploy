"""Package compiled artifacts and optionally verify explicitly supplied current runtime evidence."""

import argparse
import hashlib
import json
import re
import shutil
import tomllib
import xml.etree.ElementTree as ET
from datetime import datetime
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
REPORTS = ROOT / "docs" / "test-results"
PROPERTIES = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
MIN_NEOFORGE = PROPERTIES["neo_min_version"]
VERSION = PROPERTIES["mod_version"]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def text(path):
    return path.read_text(encoding="utf-8-sig")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_zip(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in sorted(files, key=lambda p: p.relative_to(ROOT).as_posix()):
            relative = file.relative_to(ROOT).as_posix()
            info = zipfile.ZipInfo(f"prefabdeploy-{VERSION}/{relative}", (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if file.name == "gradlew" else 0o100644) << 16
            archive.writestr(info, file.read_bytes())


def runtime_evidence(directory):
    results = sorted((directory / "junit").glob("TEST-*.xml"))
    require(bool(results), "Current JUnit XML is missing")
    count = 0
    for path in results:
        suite = ET.parse(path).getroot()
        require(int(suite.attrib["failures"]) == 0 and int(suite.attrib["errors"]) == 0,
                f"Failed unit tests: {path.name}")
        require(int(suite.attrib.get("skipped", 0)) == 0, f"Skipped unit tests: {path.name}")
        count += int(suite.attrib["tests"])
    profiles = {}
    for profile in ("base", "compat"):
        log = text(directory / f"gametest-{profile}.log")
        match = re.search(r"All (\d+) required tests passed", log)
        require(match is not None and int(match[1]) >= 35, f"Incomplete current {profile} regressions")
        require(f"Prefab Deploy {VERSION} (prefabdeploy)" in log
                and f"NeoForge mod loading, version {MIN_NEOFORGE}, for MC 1.21.1" in log,
                f"Wrong mod/runtime in {profile} evidence")
        profiles[profile] = int(match[1])
    compat = text(directory / "gametest-compat.log")
    for marker in ("PREFAB VSS MIXED TRANSACTION VERIFIED", "PREFAB FTB TEAM AND QUEST VERIFIED",
                   "PREFAB FOREIGN CLAIM VERIFIED"):
        require(marker in compat, f"Missing optional integration check: {marker}")
    for stage in ("BLOCKS", "TICKS", "COMMIT"):
        writer = text(directory / f"crash-{stage}-write.log")
        reader = text(directory / f"crash-{stage}-read.log")
        require("PREFAB HARD CRASH READY:" in writer and "exit value 91" in writer,
                f"Unverified hard exit: {stage}")
        require("All 1 required tests passed" in reader and f"Prefab Deploy {VERSION} (prefabdeploy)" in reader,
                f"Unverified current cold recovery: {stage}")
    require("CLIENT SMOKE COMPLETE" in text(directory / "client-smoke.log"), "Client smoke incomplete")
    client = json.loads(text(directory / "client-benchmark.json"))
    require(client["mod_version"] == VERSION and client["neoforge"] == MIN_NEOFORGE,
            "Client evidence belongs to a different release")
    for field in ("r_four_direction_cycle_verified", "shift_right_click_follow_cancel_verified",
                  "shift_right_click_validation_cancel_verified", "three_beacon_floating_flow_verified",
                  "survival_material_charge_verified", "local_host_import_verified",
                  "folder_button_capability_verified", "lan_host_and_guest_access_verified",
                  "outside_right_click_air_block_entity_verified", "normal_right_click_confirm_deploy_verified",
                  "beacon_renderer_particles_verified", "integrated_model_scenarios_captured"):
        require(client.get(field), f"Client regression incomplete: {field}")
    reload_log = text(directory / "reload" / "reload-command.log")
    require("PREFAB HOT RELOAD DISCOVERY UPDATE DELETE SNAPSHOT VERIFIED" in reload_log
            and "All 1 required tests passed" in reload_log, "Server hot reload incomplete")
    models = json.loads(text(directory / "model-reload.json"))
    require(models["mod_version"] == VERSION and models["saved_beacon_reloaded"]
            and models["client_renderer_available"], "Saved beacon reload incomplete")
    shaders = []
    for profile, pack in (("off", "(off)"), ("complementary", "ComplementaryReimagined_r5.9.zip"),
                          ("bsl", "BSL_v10.1.1.zip")):
        folder = directory / f"shader-{profile}"
        require("SHADER SMOKE COMPLETE" in text(folder / "client-smoke.log"), f"Shader flow incomplete: {profile}")
        data = json.loads(text(folder / "shader-validation.json"))
        require(data["mod_version"] == VERSION and data["neoforge"] == MIN_NEOFORGE
                and data["shader_pack"] == pack and data["shader_active"] == (profile != "off"),
                f"Wrong shader/runtime: {profile}")
        require(data["beacon_renderer_particles_verified"] and data["normal_right_click_confirm_deploy_verified"],
                f"Shader model/deployment check incomplete: {profile}")
        shaders.append({"pack": pack, "iris": data["iris"], "sodium": data["sodium"]})
    require(data.get("loading_preview_blocks_confirmation_verified"), "Loading preview confirmation guard unverified")
    require([r["positions"] for r in client["runs"]] == [10000, 50000, 100000], "Incomplete performance samples")
    targets = True
    for run in client["runs"]:
        require(run["native_server"]["deployment_success"] and run["native_server"]["final_updates_drained"],
                "Native benchmark deployment incomplete")
        targets &= run["native_server"]["p95_ms"] <= 5
        targets &= run["cpu_p95_ms"] <= 3 and run["gpu_p95_ms"] <= 3
        targets &= run["world_preview"]["cpu_p95_ms"] <= 3 and run["world_preview"]["gpu_p95_ms"] <= 3
    return {"unit_tests_passed": count,
            "gametest_base": {"entries_passed": profiles["base"], "core_executed": profiles["base"] - 4, "optional_skipped": 4},
            "gametest_compat": {"entries_passed": profiles["compat"], "optional_skipped": 0},
            "hard_crash_restarts_passed": ["BLOCKS", "TICKS", "COMMIT"],
            "native_client_flow_passed": True,
            "server_hot_reload_passed": True,
            "saved_beacon_reload_passed": True,
            "shader_profiles_passed": shaders,
            "preview_and_deployment_p95_targets_passed": bool(targets),
            "runtime_checks_status": "passed" if targets else "functional_pass_performance_target_missed",
            "neoforge_tested_versions": [MIN_NEOFORGE],
            "manual_acceptance_status": "captured_model_gui_and_shader_scenarios_reviewed_see_limits",
            "gui_screenshot_dimensions_reviewed": [[640, 400], [427, 267]],
            "runtime_reports": directory.relative_to(REPORTS).as_posix(),
            "benchmark": (directory / "client-benchmark.json").relative_to(REPORTS).as_posix()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, required=True)
    evidence = parser.add_mutually_exclusive_group(required=True)
    evidence.add_argument("--build-only", action="store_true",
                        help="Record compilation only; all current runtime checks remain unverified")
    evidence.add_argument("--runtime-reports", type=Path,
                          help="Verify fresh unit, GameTest, integration, crash and client reports")
    parser.add_argument("--build-log", type=Path,
                        help="Successful jar/sourcesJar Gradle log to include in the delivery record")
    args = parser.parse_args()
    runtime = runtime_evidence(args.runtime_reports.resolve()) if args.runtime_reports else None
    DIST.mkdir(exist_ok=True)
    REPORTS.mkdir(exist_ok=True)
    build_log = None
    if args.build_log:
        log = text(args.build_log)
        require("BUILD SUCCESSFUL" in log and "> Task :jar" in log and "> Task :sourcesJar" in log,
                "Successful jar/sourcesJar compilation log is required")
        require(not any(line.startswith("> Task :" + task) for line in log.splitlines()
                        for task in ("test", "runClient", "runGameTestServer")),
                "Build-only delivery log unexpectedly contains test/runtime tasks")
        build_log = f"build-{VERSION}.log"
        shutil.copy2(args.build_log, REPORTS / build_log)

    jar = DIST / f"prefabdeploy-{VERSION}.jar"
    sources_jar = DIST / f"prefabdeploy-{VERSION}-sources.jar"
    for artifact in (jar, sources_jar):
        compiled = args.build_dir / "libs" / artifact.name
        require(compiled.is_file() and digest(compiled) == digest(artifact),
                f"Delivery differs from the build artifact: {artifact.name}")
    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip() is None, "Damaged JAR")
        names = set(archive.namelist())
        require(not any("/testing/" in n or "ClientSmoke" in n or n.startswith("data/prefabcrash/")
                        for n in names), "Development test entry was packaged")
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
        require(metadata["mods"][0]["modId"] == "prefabdeploy", "Wrong mod ID")
        require(metadata["mods"][0]["version"] == VERSION, "Wrong mod version")
        require(metadata["license"] == "MPL-2.0" and "META-INF/LICENSE" in names, "Missing MPL-2.0 license")
        require(not any(n.startswith("data/prefabdeploy/prefabs/") or n.startswith("data/prefabdeploy/blueprints/")
                        for n in names), "Development prefab fixtures were packaged")
        neo_dependency = next(d for d in metadata["dependencies"]["prefabdeploy"] if d["modId"] == "neoforge")
        require(neo_dependency["versionRange"] == f"[{MIN_NEOFORGE},)", "Wrong NeoForge minimum")
        for relative in ("kubejs.plugins.txt", "data/prefabdeploy/recipe/deployment_tool.json",
                         "data/prefabdeploy/recipe/positioning_beacon.json",
                         "assets/prefabdeploy/lang/zh_cn.json", "assets/prefabdeploy/lang/en_us.json",
                         "assets/prefabdeploy/messages.json",
                         "assets/blueprintitem/models/item/blueprint.json",
                         "assets/blueprintitem/textures/item/castle_hologram.png.mcmeta",
                         "assets/beaconvisual/models/block/frame.json",
                         "assets/beaconvisual/models/block/core.json"):
            require(relative in names, f"Missing JAR resource: {relative}")
        mixins = json.loads(archive.read("prefabdeploy.mixins.json"))
        for cls in mixins["mixins"]:
            require(f'io/github/prefabdeploy/mixin/{cls}.class' in names, f"Missing mixin: {cls}")

    # Check authored definitions, translations, models and configuration syntax.
    for directory in (ROOT / "src" / "main" / "resources", ROOT / "examples"):
        for path in directory.rglob("*.json"):
            json.loads(text(path))
    tomllib.loads(text(ROOT / "examples" / "prefabdeploy-server.toml"))
    for path in (DIST / "prefabdeploy-performance.zip",):
        with zipfile.ZipFile(path) as archive:
            require(archive.testzip() is None and "pack.mcmeta" in archive.namelist(),
                    f"Invalid datapack: {path.name}")

    code = sorted((ROOT / "src").rglob("*.java"))
    fingerprint = hashlib.sha256()
    for path in code:
        fingerprint.update(path.relative_to(ROOT).as_posix().encode())
        fingerprint.update(path.read_bytes())
    previous_path = REPORTS / "acceptance.json"
    historical = []
    if previous_path.is_file():
        previous = json.loads(text(previous_path))
        if previous.get("version") != VERSION:
            archive_path = REPORTS / f'acceptance-{previous["version"]}.json'
            if not archive_path.exists():
                shutil.copy2(previous_path, archive_path)
        elif runtime and previous.get("runtime_checks_status") == "not_run_by_user_request":
            archive_path = REPORTS / f"acceptance-{VERSION}-build-only.json"
            if not archive_path.exists():
                shutil.copy2(previous_path, archive_path)
    historical = sorted(p.name for p in REPORTS.glob("acceptance-*.json"))
    acceptance = {
        "version": VERSION, "minecraft": "1.21.1", "neoforge": MIN_NEOFORGE,
        "neoforge_minimum": MIN_NEOFORGE, "neoforge_compiled_version": PROPERTIES["neo_version"],
        "neoforge_tested_versions": [],
        "date": datetime.now().astimezone().isoformat(timespec="seconds"),
        "source_java_sha256": fingerprint.hexdigest(),
        "jar_sha256": digest(jar), "build_log": build_log,
        "build_artifacts_match": True, "archive_and_metadata_integrity": True,
        "unit_tests_passed": None, "gametest_base": None, "gametest_compat": None,
        "hard_crash_restarts_passed": [],
        "native_client_flow_passed": None,
        "preview_and_deployment_p95_targets_passed": None,
        "runtime_checks_status": "not_run_by_user_request",
        "manual_acceptance_status": "not_run",
        "manual_acceptance_checklist": f"../RELEASE-{VERSION}.md",
        "historical_acceptance_records": historical,
        "benchmark": None,
        "measurement_limits": f"../TESTING-{VERSION}.md" if runtime else "../TESTING.md",
        "published": False,
    }
    if runtime:
        acceptance.update(runtime)
    (REPORTS / "acceptance.json").write_text(json.dumps(acceptance, ensure_ascii=False, indent=2) + "\n",
                                             encoding="utf-8")
    source_files = [ROOT / name for name in (".gitignore", "LICENSE", "AGENTS.md", "Build.ps1", "Test.ps1", "README.md",
                   "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat")]
    for directory in ("src", "gradle", "docs", "examples", "tools"):
        source_files.extend(p for p in (ROOT / directory).rglob("*")
                            if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"
                            and not p.is_relative_to(ROOT / "examples" / "library"))
    source = DIST / f"prefabdeploy-{VERSION}-source.zip"
    make_zip(source, source_files)
    artifacts = [jar, DIST / f"prefabdeploy-{VERSION}-sources.jar", source, DIST / "prefabdeploy-performance.zip"]
    for path in artifacts:
        with zipfile.ZipFile(path) as archive:
            require(archive.testzip() is None, f"Damaged artifact: {path.name}")
    (DIST / "SHA256SUMS.txt").write_text("".join(f"{digest(p)}  {p.name}\n" for p in artifacts), encoding="ascii")
    for path in artifacts:
        print(f"{path.name}: {path.stat().st_size:,} bytes")
    print("Packaged delivery and checksum manifest. " +
          ("Supplied current runtime evidence verified." if runtime else "No runtime/test results claimed."))


if __name__ == "__main__":
    main()
