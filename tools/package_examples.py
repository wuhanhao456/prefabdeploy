"""Create installable data packs from deterministic originals; no third-party assets."""
from pathlib import Path
import zipfile
import create_examples as examples

ROOT = Path(__file__).resolve().parents[1]


def zip_pack(directory, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED) as output:
        for file in sorted(directory.rglob('*')):
            if file.is_file():
                entry = zipfile.ZipInfo(file.relative_to(directory).as_posix(), (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(entry, file.read_bytes())


def main():
    performance = ROOT / 'examples/performance'
    examples.ROOT = performance
    examples.write_json('pack.mcmeta', {'pack': {'pack_format': 48, 'description': 'Prefab Deploy 10k / 50k / 100k test buildings'}})
    for count in (10000, 50000, 100000):
        width, depth = 50, 25 if count == 10000 else 50
        height = count // (width * depth)
        cells = {}
        for y in range(height):
            for z in range(depth):
                for x in range(width):
                    i = (y * depth + z) * width + x
                    cells[x, y, z] = examples.state('air' if i % 17 == 0 else 'glass' if i % 2 == 0 else 'stone')
        examples.nbt(f'data/prefabbench/blueprints/{count}.nbt', examples.structure((width, height, depth), cells))
        examples.write_json(f'data/prefabbench/prefabs/{count}.json', {
            'name': f'Performance {count}', 'category': 'performance',
            'source': f'prefabbench:blueprints/{count}.nbt', 'ground_y': 0,
            'ignore_air': False, 'visible': True, 'unlock': True, 'cost': {'mode': 'free'},
            'requirements_text': 'Free performance fixture; use a dedicated test world'
        })
    zip_pack(performance, ROOT / 'dist/prefabdeploy-performance.zip')
    print('Created three development performance fixtures in examples/ and dist/')


if __name__ == '__main__':
    main()
