#!/usr/bin/env python3
"""Add required rail metadata to MineRails test world snapshots."""

import argparse
import json
from pathlib import Path


def update(path: Path, design_speed_kmh: float) -> int:
    data = json.loads(path.read_text())
    metadata = {
        "designSpeedMetersPerSecond": design_speed_kmh / 3.6,
        "speedLimitMetersPerSecond": design_speed_kmh / 3.6,
        "derailSpeedMetersPerSecond": None,
        "allowedDirection": None,
        "geometry": {
            "curveKind": "UNKNOWN",
            "curveRadiusMeters": None,
            "automaticSuperelevation": False,
            "maxSuperelevationMeters": 0.15,
            "allowableCantDeficiencyMeters": 0.075,
            "parallel": None,
        },
    }
    rails = data["rails"]
    for rail in rails:
        current = rail.setdefault("metadata", metadata.copy())
        current.pop("kind", None)
        current.setdefault("speedLimitMetersPerSecond", current.get("designSpeedMetersPerSecond"))
        current.setdefault("derailSpeedMetersPerSecond", None)
        current.setdefault("allowedDirection", None)
        geometry = current.setdefault("geometry", metadata["geometry"].copy())
        geometry.setdefault("allowableCantDeficiencyMeters", 0.075)
        if geometry.get("curveKind") == "TURNOUT":
            geometry["curveKind"] = "FIVE_SEGMENT"
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
