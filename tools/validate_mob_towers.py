"""Read back the delivered ZIP and independently audit NBT and all material costs."""
from collections import Counter
from io import BytesIO
from pathlib import Path
import gzip
import hashlib
import json
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/mob_towers"


class NBT:
    def __init__(self, data):
        self.f = BytesIO(data)

    def unpack(self, fmt):
        return struct.unpack(fmt, self.f.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.f.read(self.unpack(">H")).decode("utf-8")

    def value(self, tag):
        if tag in (1, 2, 3):
            return self.unpack({1: ">b", 2: ">h", 3: ">i"}[tag])
        if tag == 8:
            return self.string()
        if tag == 9:
            kind, length = self.unpack(">B"), self.unpack(">i")
            assert length >= 0
            return [self.value(kind) for _ in range(length)]
        if tag == 10:
            result = {}
            while True:
                kind = self.unpack(">B")
                if kind == 0:
                    return result
                name = self.string()
                assert name not in result, name
                result[name] = self.value(kind)
        raise AssertionError(f"Unexpected NBT type {tag}")

    def root(self):
        assert self.unpack(">B") == 10
        self.string()
        result = self.value(10)
        assert self.f.read() == b""
        return result


def main():
    archive = OUT / "mob_towers-1.21.1-prefabdeploy-0.1.3.zip"
    report = {"sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "checks": []}
    resource = re.compile(r"^[a-z0-9_.-]+:[a-z0-9/._-]+$")
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        assert len(names) == len(set(names))
        assert z.testzip() is None
        assert all(not name.startswith("/") and ".." not in name.split("/") for name in names)
        assert json.loads(z.read("pack.mcmeta"))["pack"]["pack_format"] == 48
        definitions = [name for name in names if "/prefabs/" in name and name.endswith(".json")]
        assert len(definitions) == 2
        for name in definitions:
            meta = json.loads(z.read(name))
            assert resource.fullmatch(meta["source"])
            namespace, path = meta["source"].split(":")
            assert namespace != "prefabdeploy_local"
            assert meta["ignore_air"] is False and meta["visible"] is True and meta["unlock"] is True
            assert meta["conditions"] == {"type": "dimension", "id": "minecraft:overworld"}
            nbt = NBT(gzip.decompress(z.read(f"data/{namespace}/{path}"))).root()
            assert nbt["DataVersion"] == 3955
            size = nbt["size"]
            assert len(size) == 3 and all(isinstance(n, int) and n > 0 for n in size)
            assert meta["ground_y"] == 0 and meta["ground_y"] < size[1]
            assert len(nbt["blocks"]) == size[0]*size[1]*size[2] < 100_000
            assert nbt["entities"] == []
            palette = nbt["palette"]
            assert all(s["Name"].startswith("minecraft:") and resource.fullmatch(s["Name"]) for s in palette)
            voxels, materials, block_counts = {}, Counter(), Counter()
            for v in nbt["blocks"]:
                pos = tuple(v["pos"])
                assert len(pos) == 3 and all(0 <= a < b for a, b in zip(pos, size)) and pos not in voxels
                assert 0 <= v["state"] < len(palette)
                block = palette[v["state"]]
                voxels[pos] = block
                block_name = block["Name"]
                block_counts[block_name] += 1
                if "nbt" in v:
                    be = v["nbt"]
                    assert be["id"].startswith("minecraft:") and tuple(be[k] for k in ("x", "y", "z")) == pos
                    assert not be.get("Items", [])
                    if block_name == "minecraft:spawner":
                        assert be["id"] == "minecraft:mob_spawner"
                        assert be["SpawnData"] == {"entity": {"id": "minecraft:zombie"}}
                        assert {k: be[k] for k in ("MinSpawnDelay", "MaxSpawnDelay", "SpawnCount", "MaxNearbyEntities", "RequiredPlayerRange", "SpawnRange")} == {"MinSpawnDelay": 200, "MaxSpawnDelay": 800, "SpawnCount": 4, "MaxNearbyEntities": 6, "RequiredPlayerRange": 16, "SpawnRange": 4}
                if block_name not in ("minecraft:air", "minecraft:water"):
                    if block_name == "minecraft:spawner":
                        materials["minecraft:rotten_flesh"] += 50
                    else:
                        materials["minecraft:oak_sign" if block_name == "minecraft:oak_wall_sign" else block_name] += 1
            cost = meta["cost"]
            assert cost["xp"] == 0
            if path.endswith("zombie_spawner_tower.nbt"):
                assert block_counts["minecraft:spawner"] == 1 and cost["mode"] == "manual"
                items = cost["items"]
                assert all(resource.fullmatch(v["id"]) and isinstance(v["count"], int) and v["count"] > 0 for v in items)
                assert len({v["id"] for v in items}) == len(items)
                assert {v["id"]: v["count"] for v in items} == dict(materials)
                assert materials["minecraft:rotten_flesh"] == 50 and "minecraft:spawner" not in materials
            else:
                assert block_counts["minecraft:spawner"] == 0 and cost == {"mode": "auto", "xp": 0}
                assert all(voxels[(x,29,y)]["Name"] == "minecraft:cobblestone" for x in range(1,21) for y in range(1,21))
            report["checks"].append({"definition": name, "size_xyz": size, "positions": len(voxels), "blocks": dict(sorted(block_counts.items())), "materials": dict(sorted(materials.items())), "result": "PASS"})
    (OUT / "test-results").mkdir(exist_ok=True)
    (OUT / "test-results/static-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "sha256": report["sha256"], "buildings": len(report["checks"])}, indent=2))


if __name__ == "__main__":
    main()
