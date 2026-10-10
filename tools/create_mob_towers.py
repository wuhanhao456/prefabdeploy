"""Original vanilla mob towers for Prefab Deploy 0.1.3; deterministic NBT/ZIP."""
from collections import Counter
from pathlib import Path
import gzip
import json
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "examples/library/mob_towers"
OUT = ROOT / "artifacts/mob_towers"
NS = "mobtowers"


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def encode(tag, value):
    if tag in (1, 2, 3):
        return struct.pack({1: ">b", 2: ">h", 3: ">i"}[tag], value)
    if tag == 8:
        return string(value)
    if tag == 9:
        kind, values = value
        return bytes([kind]) + struct.pack(">i", len(values)) + b"".join(encode(kind, v) for v in values)
    if tag == 10:
        return b"".join(bytes([kind]) + string(key) + encode(kind, v) for key, (kind, v) in value.items()) + b"\0"
    raise ValueError(tag)


def state(name, **props):
    out = {"Name": (8, "minecraft:" + name)}
    if props:
        out["Properties"] = (10, {k: (8, str(v)) for k, v in props.items()})
    return out


AIR = state("air")
STONE = state("cobblestone")
SLAB = state("cobblestone_slab", type="bottom", waterlogged="false")
WATER = state("water", level="0")
GLASS = state("glass")


class Tower:
    def __init__(self, size):
        self.size = size
        self.cells = {(x, y, z): AIR for y in range(size[1]) for z in range(size[2]) for x in range(size[0])}
        self.nbt = {}

    def box(self, x0, y0, z0, x1, y1, z1, block=STONE):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                for x in range(x0, x1 + 1):
                    self.cells[x, y, z] = block

    def container(self, pos, block, **props):
        self.cells[pos] = state(block, **props)
        self.nbt[pos] = {"id": (8, "minecraft:" + block), "x": (3, pos[0]), "y": (3, pos[1]), "z": (3, pos[2]), "Items": (9, (10, []))}

    def sign(self, pos, facing):
        self.cells[pos] = state("oak_wall_sign", facing=facing, waterlogged="false")
        self.nbt[pos] = {"id": (8, "minecraft:sign"), "x": (3, pos[0]), "y": (3, pos[1]), "z": (3, pos[2])}

    def write(self, name):
        palette, indices, blocks = [], {}, []
        for pos, block in self.cells.items():
            key = repr(block)
            if key not in indices:
                indices[key] = len(palette)
                palette.append(block)
            entry = {"pos": (9, (3, pos)), "state": (3, indices[key])}
            if pos in self.nbt:
                entry["nbt"] = (10, self.nbt[pos])
            blocks.append(entry)
        data = {"DataVersion": (3, 3955), "size": (9, (3, self.size)), "palette": (9, (10, palette)), "blocks": (9, (10, blocks)), "entities": (9, (10, []))}
        path = PACK / f"data/{NS}/blueprints/{name}.nbt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(gzip.compress(b"\x0a\0\0" + encode(10, data), mtime=0))

    def materials(self, replace_spawner=False):
        counts = Counter()
        for block in self.cells.values():
            name = block["Name"][1]
            if name in ("minecraft:air", "minecraft:water"):
                continue
            if name == "minecraft:spawner" and replace_spawner:
                counts["minecraft:rotten_flesh"] += 50
            else:
                counts["minecraft:oak_sign" if name == "minecraft:oak_wall_sign" else name] += 1
        return dict(sorted(counts.items()))


def natural_tower():
    t = Tower((22, 31, 22))
    # Small accessible base; the full selected volume explicitly clears air.
    t.box(7, 0, 7, 14, 0, 15)
    for y in range(1, 25):
        for x in range(9, 13):
            for z in range(9, 13):
                if x in (9, 12) or z in (9, 12):
                    t.cells[x, y, z] = GLASS if y <= 4 else STONE
    # Four campfires above four hoppers; the rear pair drains into the front.
    for x in (10, 11):
        t.container((x, 1, 10), "hopper", facing="south", enabled="true")
        t.container((x, 1, 11), "hopper", facing="south", enabled="true")
        t.container((x, 1, 12), "barrel", facing="south", open="false")
        for z in (10, 11):
            t.cells[x, 2, z] = state("campfire", facing="north", lit="true", signal_fire="false", waterlogged="false")
        for z in (10, 11):
            t.sign((x, 24, z), "east" if x == 10 else "west")
    # Four 8x8 dry platforms; one-block-lower 2-wide channels flow 8 blocks.
    for x in range(2, 20):
        for z in range(2, 20):
            if x not in (10, 11) and z not in (10, 11):
                t.cells[x, 25, z] = STONE
                t.cells[x, 26, z] = STONE
                if x % 3 == 0 and z % 3 == 0:
                    t.cells[x, 27, z] = state("white_carpet")
            elif not (x in (10, 11) and z in (10, 11)):
                t.cells[x, 24, z] = STONE
    for a in (10, 11):
        for end in (2, 19):
            t.cells[a, 25, end] = WATER
            t.cells[end, 25, a] = WATER
    # Vertical open trapdoors make platform edges navigable to mobs.
    for a in list(range(2, 10)) + list(range(12, 20)):
        t.cells[10, 26, a] = state("oak_trapdoor", facing="east", half="bottom", open="true", powered="false", waterlogged="false")
        t.cells[11, 26, a] = state("oak_trapdoor", facing="west", half="bottom", open="true", powered="false", waterlogged="false")
        t.cells[a, 26, 10] = state("oak_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
        t.cells[a, 26, 11] = state("oak_trapdoor", facing="north", half="bottom", open="true", powered="false", waterlogged="false")
    for y in range(24, 30):
        t.box(1, y, 1, 20, y, 1)
        t.box(1, y, 20, 20, y, 20)
        t.box(1, y, 2, 1, y, 19)
        t.box(20, y, 2, 20, y, 19)
    t.box(1, 29, 1, 20, 29, 20)
    t.box(1, 30, 1, 20, 30, 20, SLAB)
    # Spawn-proof collection platform; central slabs are not inside the shaft.
    for x in range(7, 15):
        for z in range(7, 16):
            if not (9 <= x <= 12 and 9 <= z <= 12):
                t.cells[x, 1, z] = SLAB
    return t


def zombie_tower():
    t = Tower((13, 16, 14))
    t.box(7, 0, 8, 12, 0, 13)
    for y in range(1, 7):
        for x in range(9, 12):
            for z in range(9, 12):
                if x != 10 or z != 10:
                    t.cells[x, y, z] = GLASS if y <= 4 else STONE
    t.container((10, 1, 10), "hopper", facing="south", enabled="true")
    t.container((10, 1, 11), "hopper", facing="south", enabled="true")
    t.container((10, 1, 12), "barrel", facing="south", open="false")
    t.cells[10, 2, 10] = state("campfire", facing="north", lit="true", signal_fire="false", waterlogged="false")
    t.sign((10, 6, 10), "west")
    # 9x9 room, water from the north into a lowered eastward trench.
    t.box(1, 8, 1, 11, 8, 11)
    for x in range(2, 11):
        t.cells[x, 8, 10] = AIR
        t.cells[x, 9, 2] = WATER
        if x < 10:
            t.cells[x, 6, 10] = STONE
    t.cells[2, 7, 10] = WATER
    t.box(1, 6, 9, 1, 8, 11)
    t.box(2, 6, 9, 9, 7, 9)
    t.box(2, 6, 11, 11, 8, 11)
    t.box(11, 6, 9, 11, 8, 10)
    for y in range(9, 15):
        t.box(1, y, 1, 11, y, 1)
        t.box(1, y, 11, 11, y, 11)
        t.box(1, y, 2, 1, y, 10)
        t.box(11, y, 2, 11, y, 10)
    t.box(1, 14, 1, 11, 14, 11)
    t.box(1, 15, 1, 11, 15, 11, SLAB)
    pos = (6, 11, 6)
    t.cells[pos] = state("spawner")
    t.nbt[pos] = {"id": (8, "minecraft:mob_spawner"), "x": (3, 6), "y": (3, 11), "z": (3, 6),
                  "Delay": (2, 20), "MinSpawnDelay": (2, 200), "MaxSpawnDelay": (2, 800),
                  "SpawnCount": (2, 4), "MaxNearbyEntities": (2, 6), "RequiredPlayerRange": (2, 16), "SpawnRange": (2, 4),
                  "SpawnData": (10, {"entity": (10, {"id": (8, "minecraft:zombie")})})}
    # Prevent zombies from standing on top of the spawner.
    t.cells[6, 12, 6] = SLAB
    for x in range(7, 13):
        for z in range(8, 14):
            if not (9 <= x <= 11 and 9 <= z <= 12):
                t.cells[x, 1, z] = SLAB
    return t


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    write_json(PACK / "pack.mcmeta", {"pack": {"pack_format": 48, "description": "刷怪塔组合 · Prefab Deploy 0.1.3 / Minecraft 1.21.1"}})
    manifest = {}
    for name, label, t, manual in (("dark_tower", "普通暗室刷怪塔", natural_tower(), False), ("zombie_spawner_tower", "僵尸刷怪笼塔", zombie_tower(), True)):
        materials = t.materials(manual)
        cost = {"mode": "manual", "items": [{"id": i, "count": n} for i, n in materials.items()], "xp": 0} if manual else {"mode": "auto", "xp": 0}
        definition = {"name": label, "category": "刷怪塔组合", "source": f"{NS}:blueprints/{name}.nbt", "ground_y": 0,
                      "ignore_air": False, "visible": True, "unlock": True,
                      "conditions": {"type": "dimension", "id": "minecraft:overworld"},
                      "requirements_text": "直接解锁。仅限主世界；使用原版刷怪规则。" + ("建材中的刷怪笼替换为 50 块腐肉。" if manual else "消耗蓝图所需建材。"), "cost": cost}
        write_json(PACK / f"data/{NS}/prefabs/{name}.json", definition)
        t.write(name)
        manifest[name] = {"id": f"{NS}:{name}", "name": label, "size_xyz": t.size, "ground_y": 0, "DataVersion": 3955,
                          "affected_positions": len(t.cells), "materials": materials,
                          "blocks": dict(sorted(Counter(b["Name"][1] for b in t.cells.values()).items()))}
    write_json(OUT / "materials.json", manifest)
    labels = {"barrel": "木桶", "campfire": "营火", "cobblestone": "圆石", "cobblestone_slab": "圆石台阶", "glass": "玻璃", "hopper": "漏斗", "oak_sign": "橡木告示牌", "oak_trapdoor": "橡木活板门", "white_carpet": "白色地毯", "rotten_flesh": "腐肉"}
    instructions = [
        "刷怪塔组合 — Prefab Deploy 建筑数据包", "", "适用：Minecraft Java 1.21.1，Prefab Deploy 0.1.3，NeoForge 21.1.248 或更高的 1.21.1 版本。", "建筑只使用原版方块，不需要 KubeJS 或其他联动模组。", "",
        "安装", "1. 将 mob_towers-1.21.1-prefabdeploy-0.1.3.zip 原样放入世界的 datapacks/。不要放入 resourcepacks/。",
        "   单人世界：实例目录/saves/世界名/datapacks/；专用服务器：世界目录/datapacks/。局域网由主机安装。",
        "2. 管理员执行 /prefab reload（权限等级 2）；服务器控制台执行 prefab reload。被禁用的数据包需先启用。",
        "3. 用建筑建造工具打开建筑库，在“刷怪塔组合”分类选择建筑。预览位置后确认建造。R 可旋转 90°。",
        "4. 材料较多，可把全部材料放入双箱；空闲时持建筑建造工具 Shift+右键绑定箱子，再建造。箱子放在蓝图范围之外，并保持来源区块已加载。", "",
        "建筑与费用", "两座塔直接解锁，建造条件为主世界；不收费经验、货币。创造模式按本 Mod 规则免除材料。",
        "普通暗室刷怪塔：mobtowers:dark_tower；22×31×22（宽×高×深）。自动收取所需建材。",
        "僵尸刷怪笼塔：mobtowers:zombie_spawner_tower；13×16×14。按蓝图完整材料清单收费，其中一个刷怪笼替换为 50 块腐肉。",
        "蓝图内的水按本 Mod 自动材料规则免费；僵尸塔同样不额外收水桶。容器为空，没有预装战利品或实体。", "",
        "放置与使用", "参考层为最低层 0，不含地下部分。两座塔均保留空气覆盖，请在空间足够的空地预览完整边界后建造。",
        "普通塔：四个暗室平台靠原版自然刷怪，活板门引导怪物进入水道，落差和营火处理怪物，掉落物经漏斗进入底部两个木桶。",
        "在底部木桶前的台阶平台等待；这里与刷怪平台超过 24 格且小于 32 格。使用非和平难度并开启 doMobSpawning。",
        "暗室需要保持无光。周边地面和洞穴的怪物会占用自然刷怪上限；塔的产量取决于附近其他刷怪空间、模拟距离和服务器规则。",
        "僵尸塔：9×9 刷怪室，北侧水流进入降低的横向水沟，再进入营火收集井；底部一个木桶接收掉落物。",
        "在僵尸塔底部木桶前的台阶平台等待即可保持距刷怪笼小于 16 格。使用非和平难度；不要离开刷怪笼激活范围。",
        "刷怪笼使用原版参数：每批最多 4 只，附近最多 6 只，范围 4，玩家激活范围 16，间隔 200–800 Tick。",
        "这两座塔自动收集物品；营火和落差击杀不会稳定提供玩家击杀经验。", "",
        "材料清单（生存模式，每座一次）",
    ]
    for entry in manifest.values():
        instructions.append(entry["name"] + "：")
        instructions.extend(f"  {labels[item.split(':')[1]]} × {count}" for item, count in entry["materials"].items())
        instructions.append("")
    instructions += ["验证证据", "同目录 test-results/ 保存静态校验、服务端 GameTest 日志和逐次运行记录；验证范围见“验证说明.txt”。",
                     "源蓝图为原版结构 NBT，DataVersion 3955，数据包格式 48。"]
    text = "\n".join(instructions) + "\n"
    (OUT / "安装说明.txt").write_text(text, encoding="utf-8")
    readme = """# Mob Tower Sample Pack

This is a sample building datapack for Prefab Deploy 0.1.3 and Minecraft Java 1.21.1. It includes a dark chamber tower for natural mob spawning and a zombie spawner tower. Both use vanilla blocks.

## Installation

1. Place the ZIP in your world's `datapacks/` folder. For single-player worlds, use `saves/<world>/datapacks/`; for servers, use `<world>/datapacks/`.
2. As an operator, run `/prefab reload`.
3. Open the Building Tool, select a tower, and build it.

## 中文

这是适用于 Prefab Deploy 0.1.3 和 Minecraft Java 1.21.1 的建筑样板数据包，内置普通暗室刷怪塔和僵尸刷怪笼塔，全部使用原版方块。

### 安装

1. 将 ZIP 放入世界的 `datapacks/` 文件夹。单人世界使用 `saves/<世界名>/datapacks/`，服务器使用 `<世界目录>/datapacks/`。
2. 管理员执行 `/prefab reload`。
3. 打开建筑建造工具，选择刷怪塔并建造。
"""
    (PACK / "README.md").write_text(readme, encoding="utf-8")
    (OUT / "README.md").write_text(readme, encoding="utf-8")
    # The ZIP uses the short bilingual README as its installation document.
    (PACK / "安装说明.txt").unlink(missing_ok=True)
    archive = OUT / "mob_towers-1.21.1-prefabdeploy-0.1.3.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(PACK.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), (2026, 10, 9, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, path.read_bytes())
    print(json.dumps({"zip": str(archive), "buildings": manifest}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
