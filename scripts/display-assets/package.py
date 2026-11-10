#!/usr/bin/env python3
"""Build S100, independent cabin style and client display resources without deployment."""
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
import generate

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / 'models'


def archive(path, entries):
    with ZipFile(path, 'w', compression=ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)


def tree(directory, prefix):
    return {prefix + p.relative_to(directory).as_posix(): p.read_bytes()
            for p in directory.rglob('*') if p.is_file()}


def main():
    generate.main()
    archive(MODELS / 's100.zip', tree(MODELS / 's100', 's100/'))
    archive(MODELS / 'cabin-route-style.zip', tree(MODELS / 'display-styles/cabin-route', 'cabin-route/'))
    # Retain the existing client-version overlays, item definitions and transparency
    # fixes. Patch only display texture references in each applicable model variant.
    with ZipFile(MODELS / 'resourcepack-s100-transparency-1.21.11-26.2.zip') as z:
        pack = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
    model_dir = 'assets/minecraft/models/item/art1st/tc/s100/'
    for name, raw in list(pack.items()):
        relative = name.removeprefix('overlay_26_2/')
        if relative not in (model_dir + 'body_head.json', model_dir + 'door_display.json'):
            continue
        model = json.loads(raw)
        source = json.loads((MODELS / 'resourcepack' / relative).read_text())
        if relative.endswith('/body_head.json'):
            model['textures']['display_background'] = source['textures']['display_background']
            for element in model.get('elements', []):
                if element.get('name') == 'head_display_back':
                    element['faces']['north']['texture'] = '#display_background'
        else:
            model['textures'].update(source['textures'])
        pack[name] = (json.dumps(model, ensure_ascii=False, indent=2) + '\n').encode()
    pack.update(tree(MODELS / 'resourcepack/assets/javarailways/font', 'assets/javarailways/font/'))
    pack.update(tree(MODELS / 'resourcepack/assets/javarailways/textures/display', 'assets/javarailways/textures/display/'))
    background = 'assets/minecraft/textures/item/art1st/tc/s100/display_background.png'
    pack[background] = (MODELS / 'resourcepack' / background).read_bytes()
    archive(MODELS / 'resourcepack-s100-displays-1.21.11-26.2.zip', pack)


if __name__ == '__main__':
    main()
