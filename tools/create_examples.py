"""Deterministic, original sample buildings. Uses only the Python standard library."""
from pathlib import Path
import gzip
import json
import struct

ROOT = Path(__file__).resolve().parents[1] / "src/testFixtures/resources"


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def encode(tag, value):
    if tag == 1:
        return struct.pack(">b", value)
    if tag == 3:
        return struct.pack(">i", value)
    if tag == 4:
        return struct.pack(">q", value)
    if tag == 5:
        return struct.pack(">f", value)
    if tag == 6:
        return struct.pack(">d", value)
    if tag == 8:
        return string(value)
    if tag == 9:
        element, values = value
        return bytes([element]) + struct.pack(">i", len(values)) + b"".join(encode(element, v) for v in values)
    if tag == 10:
        return b"".join(bytes([kind]) + string(key) + encode(kind, v) for key, (kind, v) in value.items()) + b"\0"
    if tag in (11, 12):
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i" if tag == 11 else ">q", n) for n in value)
    raise ValueError(tag)


def nbt(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(b"\x0a\0\0" + encode(10, value), mtime=0))


def write_json(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def state(name, **properties):
    out = {"Name": (8, "minecraft:" + name)}
    if properties:
        out["Properties"] = (10, {k: (8, v) for k, v in properties.items()})
    return out


def structure(size, cells, block_entities=None, entities=None):
    block_entities = block_entities or {}
    palette, lookup, blocks = [], {}, []
    for pos, value in cells.items():
        key = repr(value)
        if key not in lookup:
            lookup[key] = len(palette)
            palette.append(value)
        entry = {"pos": (9, (3, pos)), "state": (3, lookup[key])}
        if pos in block_entities:
            entry["nbt"] = (10, block_entities[pos])
        blocks.append(entry)
    return {"DataVersion": (3, 3955), "size": (9, (3, size)), "palette": (9, (10, palette)), "blocks": (9, (10, blocks)), "entities": (9, (10, entities or []))}


def make_examples():
    cells = {}
    for y in range(7):
        for z in range(7):
            for x in range(9):
                value = state("air")
                if y == 0:
                    value = state("stone_bricks")
                elif y < 5 and (x in (0, 8) or z in (0, 6)):
                    value = state("oak_log", axis="y") if x in (0, 8) and z in (0, 6) else state("oak_planks")
                elif y == 5 or (y == 6 and x in range(2, 7)):
                    value = state("oak_planks")
                if z == 0 and x == 4 and y in (1, 2):
                    value = state("oak_door", facing="north", half="lower" if y == 1 else "upper", hinge="left", open="false", powered="false")
                if y == 3 and x in (2, 6) and z in (0, 6):
                    value = state("glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
                cells[x, y, z] = value
    nbt("data/prefabdeploy/blueprints/cottage.nbt", structure((9, 7, 7), cells))

    # Two regions with a deliberately untouched gap, and a negative X/Z size.
    palette = [state("air"), state("stone_bricks"), state("glass")]
    words = [0] * ((7 * 8 * 7 * 2 + 63) // 64)
    for y in range(8):
        for z in range(7):
            for x in range(7):
                index = (y * 7 + z) * 7 + x
                value = 1 if y in (0, 4, 7) or x in (0, 6) or z in (0, 6) else 0
                if y == 6 and z == 0 and x == 3:
                    value = 2
                bit = index * 2
                words[bit // 64] |= value << (bit % 64)
    words = [w if w < 2**63 else w - 2**64 for w in words]
    def vec(x, y, z):
        return {"x": (3, x), "y": (3, y), "z": (3, z)}
    region = {"Position": (10, vec(6, 0, 6)), "Size": (10, vec(-7, 8, -7)), "BlockStatePalette": (9, (10, palette)), "BlockStates": (12, words), "TileEntities": (9, (10, [])), "Entities": (9, (10, [])), "PendingBlockTicks": (9, (10, [])), "PendingFluidTicks": (9, (10, []))}
    pad = {"Position": (10, vec(10, 4, 0)), "Size": (10, vec(2, 1, 2)), "BlockStatePalette": (9, (10, palette)), "BlockStates": (12, [85]), "TileEntities": (9, (10, [])), "Entities": (9, (10, []))}
    nbt("data/prefabdeploy/blueprints/cellar.litematic", {"Version": (3, 7), "SubVersion": (3, 1), "MinecraftDataVersion": (3, 3955), "Regions": (10, {"Cellar": (10, region), "Landing": (10, pad)})})

    gallery = {(x, y, z): state("stone_bricks") if y == 0 else state("air") for y in range(4) for z in range(5) for x in range(7)}
    gallery[1, 1, 1] = state("chest", facing="north", type="single", waterlogged="false")
    gallery[3, 1, 1] = state("oak_sign", rotation="0", waterlogged="false")
    gallery[5, 1, 1] = state("white_banner", rotation="0")
    chest = {"id": (8, "minecraft:chest"), "x": (3, 1), "y": (3, 1), "z": (3, 1), "Items": (9, (10, [{"Slot": (1, 0), "id": (8, "minecraft:diamond"), "count": (3, 3)}, {"Slot": (1, 1), "id": (8, "minecraft:stone"), "count": (3, 32)}]))}
    text = {"messages": (9, (8, [json.dumps({"text": s}, ensure_ascii=False) for s in ("Prefab Deploy", "完整 NBT", "库存与实体", "1.21.1")])), "color": (8, "blue"), "has_glowing_text": (1, 1)}
    sign = {"id": (8, "minecraft:sign"), "x": (3, 3), "y": (3, 1), "z": (3, 1), "front_text": (10, text), "back_text": (10, text)}
    banner = {"id": (8, "minecraft:banner"), "x": (3, 5), "y": (3, 1), "z": (3, 1), "patterns": (9, (10, [{"pattern": (8, "minecraft:stripe_downleft"), "color": (8, "blue")}]))}
    armor = {"id": (8, "minecraft:armor_stand"), "Pos": (9, (6, [3.5, 1.0, 3.5])), "Rotation": (9, (5, [35.0, 0.0])), "UUID": (11, [16909060, 84281096, 151653132, 219025168]), "NoGravity": (1, 1), "ShowArms": (1, 1), "ArmorItems": (9, (10, [{"id": (8, "minecraft:" + item), "count": (3, 1)} for item in ("iron_boots", "iron_leggings", "iron_chestplate", "iron_helmet")]))}
    entities = [{"pos": (9, (6, [3.5, 1.0, 3.5])), "blockPos": (9, (3, [3, 1, 3])), "nbt": (10, armor)}]
    nbt("data/prefabdeploy/blueprints/nbt_gallery.nbt", structure((7, 4, 5), gallery, {(1, 1, 1): chest, (3, 1, 1): sign, (5, 1, 1): banner}, entities))
    for name, label, source, ground, cost in (
        ("cottage", "Oak Cottage / 橡木小屋", "cottage.nbt", 1, {"mode": "auto"}),
        ("cellar", "Cellar & Landing / 地下室与平台", "cellar.litematic", 4, {"mode": "combined", "xp": 5}),
        ("nbt_gallery", "NBT Gallery / NBT 展示台", "nbt_gallery.nbt", 1, {"mode": "manual", "items": [{"id": "minecraft:emerald", "count": 1}]}),
    ):
        write_json(f"data/prefabdeploy/prefabs/{name}.json", {"name": label, "category": "examples", "source": "prefabdeploy:blueprints/" + source, "ground_y": ground, "visible": True, "unlock": True, "cost": cost})
    # Empty test footprint: no expensive 36k-block setup, but ample test spacing.
    nbt("data/prefabdeploy/structure/empty.nbt", structure((48, 16, 48), {}))
    nbt("data/prefabdeploy/structure/large.nbt", structure((80, 16, 80), {}))
    nbt("data/prefabdeploy/structure/wide.nbt", structure((176, 16, 80), {}))
    nbt("data/prefabreload/structure/empty.nbt", structure((48, 16, 48), {}))



if __name__ == "__main__":
    make_examples()
    print("Generated development-only prefab fixtures")
