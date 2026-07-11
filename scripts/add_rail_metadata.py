#!/usr/bin/env python3
"""Add required rail metadata to MineRails test world snapshots."""

import argparse
import json
from pathlib import Path


def update(path: Path, design_speed_kmh: float) -> int:
    data = json.loads(path.read_text())
    metadata = {
        "designSpeedMetersPerSecond": design_speed_kmh / 3.6,
        "kind": "NORMAL",
        "geometry": {
            "curveKind": "UNKNOWN",
            "curveRadiusMeters": None,
            "straightUsesChordDirection": False,
            "automaticSuperelevation": False,
            "maxSuperelevationMeters": 0.15,
            "parallel": None,
        },
    }
    rails = data["rails"]
    for rail in rails:
        current = rail.setdefault("metadata", metadata.copy())
        current.setdefault("geometry", metadata["geometry"].copy())
    path.write_text(json.dumps(data, separators=(",", ":")))
    return len(rails)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--design-speed-kmh", type=float, default=80.0)
    args = parser.parse_args()
    if args.design_speed_kmh <= 0.0:
        parser.error("--design-speed-kmh must be > 0")
    for path in args.paths:
        print(f"{path}: updated {update(path, args.design_speed_kmh)} rails")


if __name__ == "__main__":
    main()
