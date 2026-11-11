#!/usr/bin/env python3
"""One-time physics config migration. Dry-run by default; --apply writes .bak restore points."""

from __future__ import annotations

import argparse
import difflib
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

SCALARS = {
    "physics-worker-threads": "worker-threads",
    "overspeed-derail-enabled": "derail-enabled",
    "derail-lateral-acceleration-mps2": "derail-lateral-acceleration-mps2",
    "overspeed-tolerance-kmh": "tolerance-kmh",
    "overspeed-grace-ticks": "grace-ticks",
}
TURNOUT_KEYS = {
    "default-protection-distance-meters",
    "default-locking-distance-meters",
    "decision-lead-ticks",
    "allocation-retry-ticks",
}


def scalar_value(text: str, key: str, indent: str = "") -> str | None:
    match = re.search(rf"(?m)^{re.escape(indent)}{re.escape(key)}:\s*([^#\n]+?)\s*$", text)
    return match.group(1).strip() if match else None


def replace_physics_value(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf"(?m)^(\s*{re.escape(key)}:\s*)[^#\n]+?(\s*(?:#.*)?)$")
    updated, count = pattern.subn(rf"\g<1>{value}\g<2>", text, count=1)
    if count != 1:
        raise ValueError(f"physics.yml target key '{key}' missing or ambiguous")
    return updated


def upgrade_physics_schema(text: str) -> str:
    version = scalar_value(text, "schema-version")
    gravity = scalar_value(text, "gravity", "  ")
    legacy_gravity = scalar_value(text, "gravity-mps2", "  ")
    if version == "2":
        if gravity is None or legacy_gravity is not None:
            raise ValueError("physics.yml schema 2 requires 'track.gravity' and forbids 'track.gravity-mps2'")
        return text
    if version != "1":
        raise ValueError(f"unsupported physics.yml schema-version '{version}'")
    if legacy_gravity is None or gravity is not None:
        raise ValueError("physics.yml schema 1 must contain only 'track.gravity-mps2'")
    upgraded = replace_physics_value(text, "schema-version", "2")
    updated, count = re.subn(
        r"(?m)^(\s*)gravity-mps2:(\s*)",
        r"\1gravity:\2",
        upgraded,
        count=1,
    )
    if count != 1:
        raise ValueError("physics.yml legacy gravity key missing or ambiguous")
    return updated


def migrate(config_text: str, physics_text: str) -> tuple[str, str]:
    values: dict[str, str] = {}
    for old_key, new_key in SCALARS.items():
        value = scalar_value(config_text, old_key)
        if value is not None:
            values[new_key] = value

    turnout_match = re.search(r"(?m)^turnout:[ \t]*\n(?P<body>(?:^[ \t]+[^\n]*(?:\n|$))*)", config_text)
    if turnout_match:
        body = turnout_match.group("body")
        for key in TURNOUT_KEYS:
            value = scalar_value(body, key, "  ")
            if value is not None:
                values[key] = value

    migrated_physics = upgrade_physics_schema(physics_text)
    for key, value in values.items():
        migrated_physics = replace_physics_value(migrated_physics, key, value)

    migrated_config = config_text
    for key in SCALARS:
        migrated_config = re.sub(rf"(?m)^{re.escape(key)}:.*\n?", "", migrated_config)
    migrated_config = re.sub(r"(?m)^turnout:[ \t]*\n(?:^[ \t]+[^\n]*(?:\n|$))*", "", migrated_config)
    if migrated_config == config_text and migrated_physics == physics_text:
        raise ValueError("no retired physics config or schema found")
    return migrated_config, migrated_physics


def show_diff(path: Path, old: str, new: str) -> None:
    print("".join(difflib.unified_diff(
        old.splitlines(True), new.splitlines(True),
        fromfile=str(path), tofile=str(path) + " (migrated)"
    )))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("data_folder", type=Path, help="plugins/JavaRailways directory")
    parser.add_argument("--apply", action="store_true", help="write changes after creating backups")
    parser.add_argument(
        "--template", type=Path,
        default=Path("BukkitPlugin/src/main/resources/physics.yml"),
        help="physics.yml template used when the data folder has no physics.yml",
    )
    args = parser.parse_args()

    config_path = args.data_folder / "config.yml"
    physics_path = args.data_folder / "physics.yml"
    config_text = config_path.read_text(encoding="utf-8")
    physics_text = (physics_path if physics_path.exists() else args.template).read_text(encoding="utf-8")
    new_config, new_physics = migrate(config_text, physics_text)
    show_diff(config_path, config_text, new_config)
    show_diff(physics_path, physics_text, new_physics)

    if not args.apply:
        print("Dry-run only; rerun with --apply to write files and .bak restore points.")
        return 0

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    shutil.copy2(config_path, config_path.with_name(f"config.yml.{stamp}.bak"))
    if physics_path.exists():
        shutil.copy2(physics_path, physics_path.with_name(f"physics.yml.{stamp}.bak"))
    config_path.write_text(new_config, encoding="utf-8")
    physics_path.write_text(new_physics, encoding="utf-8")
    print(f"Applied migration; restore points use suffix {stamp}.bak")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
