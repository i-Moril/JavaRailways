#!/usr/bin/env python3
"""Build S100 and independent cabin style example packages without deployment."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo

ROOT = Path(__file__).resolve().parents[2]
MODELS = ROOT / 'examples/models'


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
    archive(MODELS / 's100.zip', tree(MODELS / 's100', 's100/'))
    archive(MODELS / 'cabin-route-style.zip', tree(MODELS / 'display-styles/cabin-route', 'cabin-route/'))


if __name__ == '__main__':
    main()
