#!/usr/bin/env python3
"""Fix BLOCK RAIL display rotation after ITEM-to-BLOCK migration.

Dry-run by default. --apply creates a timestamped recovery point and atomically
replaces rail package configs plus rail-render sidecars.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
from typing import Any


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def kotlin_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def template_hash(template: dict[str, Any]) -> str:
    raw = kotlin_value(template["repeatLength"])
    for element in template["elements"]:
        item = element.get("itemStack") or {}
        raw += "|" + ",".join(
            [
                element["type"],
                element.get("blockMaterial") or "",
                item.get("material") or "",
                kotlin_value(item.get("customModelData")),
                kotlin_value(element.get("brightnessBlock")),
                kotlin_value(element.get("brightnessSky")),
                kotlin_value(element["offsetX"]),
                kotlin_value(element["offsetY"]),
                kotlin_value(element["offsetZ"]),
                kotlin_value(element.get("rotationX", 0.0)),
                kotlin_value(element.get("rotationY", 0.0)),
                kotlin_value(element.get("rotationZ", 0.0)),
                kotlin_value(element["scaleX"]),
                kotlin_value(element["scaleY"]),
                kotlin_value(element["scaleZ"]),
            ]
        )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def convert_yaml(content: bytes, path: Path) -> tuple[bytes, int]:
    lines = content.decode("utf-8").splitlines(keepends=True)
    starts = [index for index, line in enumerate(lines) if line.startswith("  - type:")]
    starts.append(len(lines))
    changed = 0
    for block_index in range(len(starts) - 1):
        start, end = starts[block_index], starts[block_index + 1]
        block = lines[start:end]
        if not block[0].strip().lower() == "- type: block":
            continue
        if not any(line.strip().upper() == "MATERIAL: RAIL" for line in block):
            continue
        rotation = next((i for i, line in enumerate(block) if line.strip() == "rotation:"), None)
        if rotation is None:
            raise ValueError(f"{path}: BLOCK RAIL element has no rotation section")
        expected = {"x": "0.0", "y": "90.0", "z": "0.0"}
        current: dict[str, str] = {}
        positions: dict[str, int] = {}
        for relative in range(rotation + 1, min(rotation + 4, len(block))):
            stripped = block[relative].strip()
            if ":" not in stripped:
                continue
            key, value = (part.strip() for part in stripped.split(":", 1))
            if key in expected:
                current[key] = value
                positions[key] = relative
        if set(positions) != set(expected):
            raise ValueError(f"{path}: BLOCK RAIL rotation must explicitly contain x/y/z")
        if current == expected:
            continue
        for key, value in expected.items():
            relative = positions[key]
            newline = "\n" if block[relative].endswith("\n") else ""
            block[relative] = f"      {key}: {value}{newline}"
        lines[start:end] = block
        changed += 1
    return "".join(lines).encode("utf-8"), changed


def convert_render_json(content: bytes, path: Path) -> tuple[bytes, int, int]:
    document = json.loads(content)
    if document.get("version") != 2:
        raise ValueError(f"{path}: expected rail-render version 2")
    templates = document.get("templates")
    rails = document.get("rails")
    if not isinstance(templates, list) or not isinstance(rails, list):
        raise ValueError(f"{path}: templates and rails must be lists")

    seen: set[str] = set()
    for template in templates:
        old_id = template.get("templateId")
        if not isinstance(old_id, str) or old_id in seen:
            raise ValueError(f"{path}: invalid or duplicate templateId {old_id!r}")
        seen.add(old_id)
        calculated = template_hash(template)
        if calculated != old_id:
            raise ValueError(f"{path}: template hash mismatch: {old_id} != {calculated}")

    converted = copy.deepcopy(document)
    id_map: dict[str, str] = {}
    changed_templates = 0
    for template in converted["templates"]:
        old_id = template["templateId"]
        changed = False
        for element in template["elements"]:
            if element.get("type") != "BLOCK" or element.get("blockMaterial") != "RAIL":
                continue
            current = (
                float(element.get("rotationX", 0.0)),
                float(element.get("rotationY", 0.0)),
                float(element.get("rotationZ", 0.0)),
            )
            if current == (0.0, 90.0, 0.0):
                continue
            element.pop("rotationX", None)
            element["rotationY"] = 90.0
            element.pop("rotationZ", None)
            changed = True
        if changed:
            changed_templates += 1
        new_id = template_hash(template)
        template["templateId"] = new_id
        id_map[old_id] = new_id

    if len(set(id_map.values())) != len(id_map):
        raise ValueError(f"{path}: converted template IDs collide")
    updated_references = 0
    for rail in converted["rails"]:
        old_id = rail.get("templateId")
        if old_id not in id_map:
            raise ValueError(f"{path}: rail {rail.get('railId')} references missing template {old_id!r}")
        new_id = id_map[old_id]
        if new_id != old_id:
            rail["templateId"] = new_id
            updated_references += 1

    output = (json.dumps(converted, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return output, changed_templates, updated_references


def atomic_write(path: Path, content: bytes) -> None:
    temp = path.with_name(path.name + ".rotation-migration-tmp")
    with temp.open("wb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="Minecraft instance root")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    data = root / "plugins" / "JavaRailways"
    yaml_paths = sorted((data / "rails").glob("*/rail_model.yml"))
    render_paths = sorted((data / "worlds").glob("*-rail-render.json"))
    if not yaml_paths or not render_paths:
        raise ValueError("rail model configs or rail-render sidecars are missing")

    originals: dict[Path, bytes] = {}
    outputs: dict[Path, bytes] = {}
    details: list[str] = []
    config_changes = template_changes = reference_changes = 0
    for path in yaml_paths:
        original = path.read_bytes()
        output, changed = convert_yaml(original, path)
        originals[path] = original
        outputs[path] = output
        config_changes += changed
        details.append(f"{path.relative_to(root)}: {changed} BLOCK RAIL rotation(s)")
    for path in render_paths:
        original = path.read_bytes()
        output, changed_templates, changed_references = convert_render_json(original, path)
        originals[path] = original
        outputs[path] = output
        template_changes += changed_templates
        reference_changes += changed_references
        details.append(
            f"{path.relative_to(root)}: {changed_templates} template(s), "
            f"{changed_references} rail reference(s)"
        )

    print("mode:", "APPLY" if args.apply else "DRY-RUN")
    for detail in details:
        print(detail)
    print(
        f"total: {config_changes} config rotation(s), {template_changes} saved template(s), "
        f"{reference_changes} rail reference(s)"
    )
    if not args.apply:
        return 0

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    recovery = root / ".javarailways-migrations" / f"block-rail-rotation-{stamp}"
    recovery.mkdir(parents=True, exist_ok=False)
    manifest: list[dict[str, str]] = []
    for path, original in originals.items():
        relative = path.relative_to(root)
        backup = recovery / relative
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, backup)
        manifest.append(
            {
                "path": str(relative),
                "beforeSha256": sha256_bytes(original),
                "afterSha256": sha256_bytes(outputs[path]),
            }
        )
    (recovery / "manifest.json").write_text(
        json.dumps({"migration": "block-rail-rotation", "files": manifest}, indent=2) + "\n",
        encoding="utf-8",
    )
    for path, output in outputs.items():
        atomic_write(path, output)
    print(f"recovery point: {recovery}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
