"""Launch isolated local servers and the development client connection smoke runner.

Run Build.ps1 prepareClientRun/prepareServerRun with -ConnectionSmoke first.
All processes, worlds, options and hotbars belong to the supplied local cache.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--vanilla-jar", type=Path, required=True)
    parser.add_argument("--base-port", type=int, default=25580)
    parser.add_argument("--run-name", default="connections")
    parser.add_argument("--report-name", default="reports")
    args = parser.parse_args()
    cache = args.cache.resolve()
    if str(cache).startswith("\\\\"):
        raise ValueError("The cache must be on a local disk")
    moddev = cache / "build/moddev"
    reports = cache / args.report_name
    reports.mkdir(parents=True, exist_ok=True)
    report_file = reports / "client-connections.json"
    if report_file.exists():
        raise ValueError("Use a fresh report directory or preserve the previous report before rerunning")
    for port in range(args.base_port, args.base_port + 3):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    mod_folders = os.pathsep.join(
        "prefabdeploy%%" + str(cache / part)
        for part in ("build/classes/java/main", "build/resources/main")
    )
    def runtime_classpath(side):
        entries = (moddev / (side + "LegacyClasspath.txt")).read_text(encoding="utf-8").splitlines()
        entries.append(str(moddev / "artifacts/neoforge-21.1.248.jar"))
        return os.pathsep.join(entries)
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java 21 is required")
    player_id = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:PrefabCompat").digest(), version=3))
    processes = []
    handles = []
    environment = os.environ.copy()
    environment.pop("MOD_CLASSES", None)
    temporary = cache / "tmp"
    temporary.mkdir(parents=True, exist_ok=True)
    environment["JAVA_TOOL_OPTIONS"] = "-Djdk.net.unixdomain.tmpdir=" + temporary.as_posix()
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

    def launch(name, command, run):
        log_path = reports / (name + ".log")
        handle = log_path.open("w", encoding="utf-8")
        handles.append(handle)
        process = subprocess.Popen(command, cwd=run, env=environment, stdin=subprocess.PIPE,
                                   stdout=handle, stderr=subprocess.STDOUT, text=True,
                                   creationflags=creationflags)
        processes.append((name, process))
        return process, log_path

    try:
        for offset, name in enumerate(("supported", "neoforge", "vanilla")):
            run = cache / args.run_name / name
            run.mkdir(parents=True, exist_ok=True)
            if (run / "world").exists():
                raise ValueError("Use fresh smoke worlds; existing world: " + str(run / "world"))
            (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            (run / "server.properties").write_text(
                "server-ip=127.0.0.1\nserver-port=" + str(args.base_port + offset) + "\n"
                "online-mode=false\ngamemode=creative\ndifficulty=peaceful\n"
                "level-type=minecraft:flat\nlevel-seed=1\nview-distance=5\nsimulation-distance=5\n"
                'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false,"structure_overrides":[]}\n'
                "spawn-protection=0\nallow-flight=true\nmax-players=4\n",
                encoding="utf-8")
            (run / "ops.json").write_text(json.dumps([
                {"uuid": player_id, "name": "PrefabCompat", "level": 4, "bypassesPlayerLimit": True}
            ]), encoding="utf-8")
            if name == "supported":
                shutil.copytree(args.project / "src/testFixtures/resources",
                                run / "world/datapacks/prefabdeploy-test-fixtures")
            if name == "vanilla":
                command = [java, "-Xmx2G", "-jar", str(args.vanilla_jar), "nogui"]
            else:
                command = [java, "-Xmx2G", "@" + str(moddev / "serverRunVmArgs.txt"),
                           "-cp", runtime_classpath("server")]
                if name == "supported":
                    command.append("-Dfml.modFolders=" + mod_folders)
                command.append("@" + str(moddev / "serverRunProgramArgs.txt"))
            process, log_path = launch(name, command, run)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(name + " exited early; see " + str(log_path))
                if "Done (" in log_path.read_text(encoding="utf-8", errors="replace"):
                    print(name + " server ready", flush=True)
                    break
                time.sleep(1)
            else:
                raise RuntimeError(name + " did not start; see " + str(log_path))

        run = cache / args.run_name / "client"
        run.mkdir(parents=True, exist_ok=True)
        (run / "options.txt").write_text(
            "lang:zh_cn\nguiScale:2\nrenderDistance:5\nsimulationDistance:5\n"
            "maxFps:120\nenableVsync:false\npauseOnLostFocus:false\n", encoding="utf-8")
        process, log_path = launch("client-connections", [java, "-Xmx4G",
            "@" + str(moddev / "clientRunVmArgs.txt"), "-cp", runtime_classpath("client"),
            "-Dfml.modFolders=" + mod_folders,
            "-Dprefabdeploy.reportDir=" + str(reports),
            "-Dprefabdeploy.connectionServers=" + ",".join(
                name + "@127.0.0.1:" + str(args.base_port + offset)
                for name, offset in (("supported", 0), ("neoforge", 1), ("vanilla", 2), ("neoforge", 1), ("supported", 0))),
            "@" + str(moddev / "clientRunProgramArgs.txt")], run)
        deadline = time.monotonic() + 1000
        while time.monotonic() < deadline:
            if process.poll() is not None:
                if process.returncode != 0 or not report_file.exists():
                    raise RuntimeError("Client failed; see " + str(log_path))
                report = json.loads(report_file.read_text(encoding="utf-8"))
                if not report["passed"]:
                    raise RuntimeError("Client assertion failed: " + report["error"])
                print("All " + str(len(report["connections"])) + " real connections passed", flush=True)
                break
            time.sleep(2)
        else:
            raise RuntimeError("Client timed out; see " + str(log_path))
    finally:
        for name, process in reversed(processes):
            if process.poll() is None:
                if name != "client-connections":
                    try:
                        process.stdin.write("stop\n")
                        process.stdin.flush()
                        process.wait(timeout=30)
                    except (OSError, subprocess.TimeoutExpired):
                        process.terminate()
                else:
                    process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        for handle in handles:
            handle.close()


if __name__ == "__main__":
    main()
