"""Render the actual Building Tool assets for the CurseForge project avatar.

Requires Pillow and NumPy. No game files or model assets are modified.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets"
DEFAULT_OUTPUT = ROOT / "docs/curseforge"


def resource(identifier, kind, suffix):
    namespace, name = identifier.split(":", 1)
    return ASSETS / namespace / kind / (name + suffix)


def rotation_matrix(axis, degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def face_vertices(start, end, direction):
    x0, y0, z0 = start
    x1, y1, z1 = end
    # Vertices run from texture top-left clockwise as seen from outside.
    return np.array({
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[direction], dtype=float)


NORMALS = {
    "north": [0, 0, -1], "south": [0, 0, 1],
    "east": [1, 0, 0], "west": [-1, 0, 0],
    "up": [0, 1, 0], "down": [0, -1, 0],
}


def read_scene(frame):
    inputs = set()

    def read_model(identifier):
        path = resource(identifier, "models", ".json")
        inputs.add(path)
        return json.loads(path.read_text(encoding="utf-8-sig"))

    item = read_model("prefabdeploy:item/deployment_tool")
    composite = read_model(item["parent"])
    assert composite["loader"] == "neoforge:composite"
    faces, elements = [], 0
    for child in composite["item_render_order"]:
        model = read_model(composite["children"][child]["parent"])
        assert model["render_type"] in ("minecraft:cutout", "minecraft:translucent")
        translucent = model["render_type"] == "minecraft:translucent"
        textures = {}
        for name, identifier in model["textures"].items():
            path = resource(identifier, "textures", ".png")
            inputs.add(path)
            texture = Image.open(path).convert("RGBA")
            animation = path.with_suffix(".png.mcmeta")
            if animation.exists():
                inputs.add(animation)
                spec = json.loads(animation.read_text())["animation"]
                width, height = spec["width"], spec["height"]
                assert 0 <= frame < texture.height // height
                texture = texture.crop((0, frame * height, width, (frame + 1) * height))
            textures[name] = np.asarray(texture, dtype=np.float32) / 255
        elements += len(model["elements"])
        for element in model["elements"]:
            transform = np.eye(3)
            origin = np.zeros(3)
            if "rotation" in element:
                rotation = element["rotation"]
                assert not rotation.get("rescale", False), "Unsupported rescaled element"
                transform = rotation_matrix(rotation["axis"], rotation["angle"])
                origin = np.asarray(rotation["origin"])
            for direction, face in element["faces"].items():
                assert face.get("rotation", 0) == 0, "Unsupported UV rotation"
                vertices = face_vertices(element["from"], element["to"], direction)
                vertices = (vertices - origin) @ transform.T + origin
                normal = transform @ NORMALS[direction]
                u0, v0, u1, v1 = face["uv"]
                uv = np.array([(u0, v0), (u1, v0), (u1, v1), (u0, v1)]) / 16
                # Match Minecraft's directional face shading for solid parts.
                shade = 1.0 if not element.get("shade", True) else (
                    0.6 * normal[0] ** 2 + 0.8 * normal[2] ** 2
                    + (1.0 if normal[1] >= 0 else 0.5) * normal[1] ** 2
                )
                faces.append({"vertices": vertices, "normal": normal, "uv": uv,
                              "texture": textures[face["texture"].removeprefix("#")],
                              "translucent": translucent, "shade": shade})
    return faces, elements, inputs


def render(faces, size, azimuth, elevation):
    az, el = math.radians(azimuth), math.radians(elevation)
    eye = np.array([math.sin(az) * math.cos(el), math.sin(el), math.cos(az) * math.cos(el)])
    right = np.cross([0, 1, 0], eye)
    right /= np.linalg.norm(right)
    up = np.cross(eye, right)
    basis = np.array([right, up, eye]).T
    points = np.concatenate([f["vertices"] for f in faces]) @ basis
    low, high = points[:, :2].min(axis=0), points[:, :2].max(axis=0)
    center = (low + high) / 2
    scale = size * 0.84 / max(high - low)
    projected = []
    for face in faces:
        if np.dot(face["normal"], eye) <= 1e-8:
            continue
        v = face["vertices"] @ basis
        v[:, :2] = (v[:, :2] - center) * scale
        v[:, 0] += size / 2
        v[:, 1] = size / 2 - v[:, 1]
        projected.append(dict(face, screen=v))

    # Store premultiplied RGB and alpha, so transparent exports have clean edges.
    canvas = np.zeros((size, size, 4), dtype=np.float32)
    depth = np.full((size, size), -np.inf, dtype=np.float32)
    for translucent in (False, True):
        selected = [f for f in projected if f["translucent"] == translucent]
        if translucent:
            selected.sort(key=lambda f: f["screen"][:, 2].mean())
        for face in selected:
            # A projected cube face is a parallelogram. Sample it once to avoid
            # double alpha blending on a shared triangle diagonal.
            v = face["screen"]
            xmin = max(0, math.floor(v[:, 0].min()))
            xmax = min(size, math.ceil(v[:, 0].max()))
            ymin = max(0, math.floor(v[:, 1].min()))
            ymax = min(size, math.ceil(v[:, 1].max()))
            if xmin >= xmax or ymin >= ymax:
                continue
            matrix = np.array([v[1, :2] - v[0, :2], v[3, :2] - v[0, :2]]).T
            if abs(np.linalg.det(matrix)) < 1e-10:
                continue
            yy, xx = np.mgrid[ymin:ymax, xmin:xmax]
            delta = np.stack([xx + 0.5 - v[0, 0], yy + 0.5 - v[0, 1]], axis=-1)
            st = delta @ np.linalg.inv(matrix).T
            s, t = st[..., 0], st[..., 1]
            z = v[0, 2] + s * (v[1, 2] - v[0, 2]) + t * (v[3, 2] - v[0, 2])
            region_depth = depth[ymin:ymax, xmin:xmax]
            mask = (s >= 0) & (s < 1) & (t >= 0) & (t < 1) & (z >= region_depth - 1e-5)
            uv = face["uv"][0] + s[..., None] * (face["uv"][1] - face["uv"][0]) \
                + t[..., None] * (face["uv"][3] - face["uv"][0])
            texture = face["texture"]
            tex_y = np.clip((uv[..., 1] * texture.shape[0]).astype(int), 0, texture.shape[0] - 1)
            tex_x = np.clip((uv[..., 0] * texture.shape[1]).astype(int), 0, texture.shape[1] - 1)
            color = texture[tex_y, tex_x].copy()
            color[..., :3] *= face["shade"]
            if not translucent:
                color[..., 3] = (color[..., 3] >= 0.1).astype(np.float32)
            mask &= color[..., 3] > 0
            region = canvas[ymin:ymax, xmin:xmax]
            a = color[..., 3:4]
            color[..., :3] *= a
            blended = color + region * (1 - a)
            region[mask] = blended[mask]
            if not translucent:
                region_depth[mask] = z[mask]

    straight = canvas.copy()
    straight[..., :3] /= np.maximum(straight[..., 3:4], 1e-8)
    return Image.fromarray(np.uint8(np.clip(straight, 0, 1) * 255), "RGBA"), {
        "projection": "orthographic", "azimuth_degrees": azimuth,
        "elevation_degrees": elevation, "geometry_margin": 0.08,
        "rendered_faces": len(projected),
    }


def backdrop(size):
    yy, xx = np.mgrid[:size, :size] / size
    glow = np.exp(-((xx - 0.43) ** 2 / 0.22 + (yy - 0.38) ** 2 / 0.25) * 2)
    low = np.array([9, 18, 32])
    high = np.array([30, 57, 76])
    color = low + glow[..., None] * (high - low)
    return Image.fromarray(np.uint8(color), "RGB").convert("RGBA")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--azimuth", type=float, default=25)
    parser.add_argument("--elevation", type=float, default=35)
    parser.add_argument("--frame", type=int, default=0)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    faces, elements, inputs = read_scene(args.frame)
    subject, settings = render(faces, 2048, args.azimuth, args.elevation)
    background = backdrop(2048)
    shadow = Image.new("RGBA", subject.size, (0, 0, 0, 0))
    shadow.putalpha(subject.getchannel("A").point(lambda a: int(a * 0.32)).filter(ImageFilter.GaussianBlur(25)))
    background.alpha_composite(shadow, (0, 24))
    background.alpha_composite(subject)
    outputs = []
    for size in (400, 1024):
        path = args.output / f"prefabdeploy-icon-{size}.png"
        background.convert("RGB").resize((size, size), Image.Resampling.LANCZOS).save(path, optimize=True)
        outputs.append(path)
    path = args.output / "prefabdeploy-tool-transparent-1024.png"
    subject.resize((1024, 1024), Image.Resampling.LANCZOS).save(path, optimize=True)
    outputs.append(path)
    hashes = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(inputs)}
    metadata = {"source_item": "prefabdeploy:deployment_tool", "elements": elements,
                "hologram_texture_frame": args.frame, "settings": settings,
                "source_sha256": hashes,
                "outputs": {p.name: {"size": list(Image.open(p).size),
                                     "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                            for p in outputs}}
    (args.output / "icon-render.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"elements": elements, "rendered_faces": settings["rendered_faces"],
                      "outputs": [str(p) for p in outputs]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
