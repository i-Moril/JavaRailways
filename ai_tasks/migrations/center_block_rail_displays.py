#!/usr/bin/env python3
"""Center migrated BLOCK rail visuals while preserving their intended layout.

ITEM display models were center-anchored. BLOCK displays use a corner origin.
This one-time migration adjusts RAIL and full-block ballast offsets accordingly.
Dry-run is the default; --apply writes only after validation and a recovery point.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sys
from typing import Any

BALLAST_MATERIALS = {"GRAVEL", "STONE"}
BALLAST_CENTER_Z = (-1.0, 0.0, 1.0)
EPSILON = 1.0e-9


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


def close_tuple(first: tuple[float, ...], second: tuple[float, ...]) -> bool:
    return all(math.isclose(a, b, abs_tol=EPSILON) for a, b in zip(first, second))


def target_offset(
    material: str,
    current: tuple[float, float, float],
    scale: tuple[float, float, float],
    subject: str,
) -> tuple[float, float, float] | None:
    if material == "RAIL":
        target = (-scale[2] / 2.0, 0.0, scale[0] / 2.0)
        if close_tuple(current, target):
            return None
        if not close_tuple(current, (0.0, 0.0, 0.0)):
            raise ValueError(f"{subject}: unexpected pre-migration RAIL offset {current}")
        return target

    if material not in BALLAST_MATERIALS:
        raise ValueError(f"{subject}: unsupported BLOCK material {material!r}")
    targets = [
        (-scale[0] / 2.0, -0.5 - scale[1] / 2.0, center_z - scale[2] / 2.0)
        for center_z in BALLAST_CENTER_Z
    ]
    if any(close_tuple(current, target) for target in targets):
        return None
    for center_z, target in zip(BALLAST_CENTER_Z, targets):
        if close_tuple(current, (0.0, -0.5, center_z)):
            return target
    raise ValueError(f"{subject}: unexpected pre-migration ballast offset {current}")


def section_axes(block: list[str], section: str, subject: str) -> tuple[dict[str, float], dict[str, int]]:
    section_index = next((i for i, line in enumerate(block) if line.strip() == f"{section}:"), None)
    if section_index is None:
        raise ValueError(f"{subject}: missing {section} section")
    values: dict[str, float] = {}
    positions: dict[str, int] = {}
    for relative in range(section_index + 1, min(section_index + 4, len(block))):
        stripped = block[relative].strip()
        if ":" not in stripped:
            continue
        key, raw = (part.strip() for part in stripped.split(":", 1))
        if key in {"x", "y", "z"}:
            values[key] = float(raw)
            positions[key] = relative
    if set(values) != {"x", "y", "z"}:
        raise ValueError(f"{subject}: {section} must explicitly contain x/y/z")
    return values, positions


def format_number(value: float) -> str:
    text = f"{value:.12f}".rstrip("0").rstrip(".")
    return text + ".0" if "." not in text else text


def convert_yaml(content: bytes, path: Path) -> tuple[bytes, int]:
    lines = content.decode("utf-8").splitlines(keepends=True)
    starts = [index for index, line in enumerate(lines) if line.startswith("  - type:")]
    starts.append(len(lines))
    changed = 0
    for block_index in range(len(starts) - 1):
        start, end = starts[block_index], starts[block_index + 1]
        block = lines[start:end]
        if block[0].strip().lower() != "- type: block":
            continue
        material_line = next((line for line in block if line.strip().lower().startswith("material:")), None)
        if material_line is None:
            raise ValueError(f"{path}: BLOCK element has no material")
        material = material_line.split(":", 1)[1].strip().upper()
        subject = f"{path}: BLOCK {material} element #{block_index + 1}"
        offset, positions = section_axes(block, "offset", subject)
        scale, _ = section_axes(block, "scale", subject)
        current = (offset["x"], offset["y"], offset["z"])
        scale_tuple = (scale["x"], scale["y"], scale["z"])
        target = target_offset(material, current, scale_tuple, subject)
        if target is None:
            continue
        for key, value in zip(("x", "y", "z"), target):
            relative = positions[key]
            newline = "\n" if block[relative].endswith("\n") else ""
            block[relative] = f"      {key}: {format_number(value)}{newline}"
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
    changed_elements = 0
    for template in converted["templates"]:
        old_id = template["templateId"]
        for index, element in enumerate(template["elements"]):
            if element.get("type") != "BLOCK":
                raise ValueError(f"{path}: template {old_id} still contains non-BLOCK element")
            material = element.get("blockMaterial")
            current = (
                float(element["offsetX"]),
                float(element["offsetY"]),
                float(element["offsetZ"]),
            )
            scale = (
                float(element["scaleX"]),
                float(element["scaleY"]),
                float(element["scaleZ"]),
            )
            target = target_offset(material, current, scale, f"{path}: {old_id} element #{index + 1}")
            if target is None:
                continue
            element["offsetX"], element["offsetY"], element["offsetZ"] = target
            changed_elements += 1
        new_id = template_hash(template)
        template["templateId"] = new_id
        id_map[old_id] = new_id

    if len(set(id_map.values())) != len(id_map):
        raise ValueError(f"{path}: converted template IDs collide")
    changed_references = 0
    for rail in converted["rails"]:
        old_id = rail.get("templateId")
        if old_id not in id_map:
            raise ValueError(f"{path}: rail {rail.get('railId')} references missing template {old_id!r}")
        new_id = id_map[old_id]
        if new_id != old_id:
            rail["templateId"] = new_id
            changed_references += 1

    output = (json.dumps(converted, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return output, changed_elements, changed_references


def atomic_write(path: Path, content: bytes) -> None:
    temp = path.with_name(path.name + ".centering-migration-tmp")
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
    config_changes = element_changes = reference_changes = 0
    for path in yaml_paths:
        original = path.read_bytes()
        output, changed = convert_yaml(original, path)
        originals[path] = original
        outputs[path] = output
        config_changes += changed
        details.append(f"{path.relative_to(root)}: {changed} centered config element(s)")
    for path in render_paths:
        original = path.read_bytes()
        output, changed_elements, changed_references = convert_render_json(original, path)
        originals[path] = original
        outputs[path] = output
        element_changes += changed_elements
        reference_changes += changed_references
        details.append(
            f"{path.relative_to(root)}: {changed_elements} centered template element(s), "
            f"{changed_references} rail reference(s)"
        )

    print("mode:", "APPLY" if args.apply else "DRY-RUN")
    for detail in details:
        print(detail)
    print(
        f"total: {config_changes} config element(s), {element_changes} saved element(s), "
        f"{reference_changes} rail reference(s)"
    )
    if not args.apply:
        return 0

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    recovery = root / ".javarailways-migrations" / f"center-block-rail-displays-{stamp}"
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
        json.dumps({"migration": "center-block-rail-displays", "files": manifest}, indent=2) + "\n",
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
