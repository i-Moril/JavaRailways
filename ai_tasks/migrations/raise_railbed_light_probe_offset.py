#!/usr/bin/env python3
"""Raise saved railbed light probes from local y=0.5 to y=1.0.

RAIL BlockDisplay probes remain at y=0.5. The merged PAPER railbeds with custom
model data 730001/730002 move to y=1.0. Dry-run is the default; --apply creates
an SHA-256 recovery manifest before atomically replacing validated files.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import math
import os
from pathlib import Path
import shutil
import sys
from typing import Any

from add_rail_light_probe_offset import sha256_bytes, template_hash, validate_template_hash

RAIL_TARGET = (0.0, 0.5, 0.0)
RAILBED_TARGET = (0.0, 1.0, 0.0)
RAILBED_CUSTOM_MODEL_DATA = {730001, 730002}
EPSILON = 1.0e-9


def close_tuple(first: tuple[float, ...], second: tuple[float, ...]) -> bool:
    return all(math.isclose(a, b, abs_tol=EPSILON) for a, b in zip(first, second))


def target_for_element(element: dict[str, Any], subject: str) -> tuple[float, float, float]:
    element_type = str(element.get("type", "")).upper()
    if element_type == "BLOCK" and str(element.get("blockMaterial", "")).upper() == "RAIL":
        return RAIL_TARGET
    item = element.get("itemStack") or {}
    if (
        element_type == "ITEM"
        and str(item.get("material", "")).upper() == "PAPER"
        and item.get("customModelData") in RAILBED_CUSTOM_MODEL_DATA
    ):
        return RAILBED_TARGET
    raise ValueError(f"{subject}: unsupported rail element identity")


def yaml_element_ranges(lines: list[str]) -> list[tuple[int, int]]:
    starts = [index for index, line in enumerate(lines) if line.startswith("  - type:")]
    if not starts:
        raise ValueError("rail_model.yml contains no explicit element definitions")
    return list(zip(starts, starts[1:] + [len(lines)]))


def yaml_identity(block: list[str], subject: str) -> tuple[str, str, int | None]:
    element_type = block[0].split(":", 1)[1].strip().upper()
    material_line = next((line for line in block if line.startswith("    material:")), None)
    if material_line is None:
        raise ValueError(f"{subject}: missing material")
    material = material_line.split(":", 1)[1].strip().upper()
    custom_line = next((line for line in block if line.strip().startswith("custom_model_data:")), None)
    custom_model_data = int(custom_line.split(":", 1)[1].strip()) if custom_line else None
    return element_type, material, custom_model_data


def yaml_target(block: list[str], subject: str) -> tuple[float, float, float]:
    element_type, material, custom_model_data = yaml_identity(block, subject)
    if element_type == "BLOCK" and material == "RAIL":
        return RAIL_TARGET
    if element_type == "ITEM" and material == "PAPER" and custom_model_data in RAILBED_CUSTOM_MODEL_DATA:
        return RAILBED_TARGET
    raise ValueError(f"{subject}: unsupported rail element identity")


def probe_axes(block: list[str], subject: str) -> tuple[dict[str, float], dict[str, int]]:
    section = next((i for i, line in enumerate(block) if line.strip() == "light_probe_offset:"), None)
    if section is None:
        raise ValueError(f"{subject}: missing light_probe_offset")
    values = {"x": 0.0, "y": 0.0, "z": 0.0}
    positions: dict[str, int] = {}
    for index in range(section + 1, len(block)):
        line = block[index]
        indent = len(line) - len(line.lstrip(" "))
        if line.strip() and indent <= 4:
            break
        stripped = line.strip()
        if ":" not in stripped:
            continue
        key, raw = (part.strip() for part in stripped.split(":", 1))
        if key in values:
            values[key] = float(raw)
            positions[key] = index
    return values, positions


def convert_yaml(content: bytes, path: Path) -> tuple[bytes, int]:
    lines = content.decode("utf-8").splitlines(keepends=True)
    changed = 0
    for element_index, (start, end) in reversed(list(enumerate(yaml_element_ranges(lines), start=1))):
        block = lines[start:end]
        subject = f"{path}: element #{element_index}"
        target = yaml_target(block, subject)
        values, positions = probe_axes(block, subject)
        current = (values["x"], values["y"], values["z"])
        if close_tuple(current, target):
            continue
        if target != RAILBED_TARGET or not close_tuple(current, RAIL_TARGET):
            raise ValueError(f"{subject}: unexpected existing light_probe_offset {current}")
        y_position = positions.get("y")
        if y_position is None:
            raise ValueError(f"{subject}: light_probe_offset.y must be explicit")
        newline = "\n" if block[y_position].endswith("\n") else ""
        block[y_position] = "      y: 1.0" + newline
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
        if old_id in seen:
            raise ValueError(f"{path}: duplicate templateId {old_id!r}")
        if not validate_template_hash(path, template):
            raise ValueError(f"{path}: template {old_id} predates light-probe migration")
        seen.add(old_id)

    converted = copy.deepcopy(document)
    id_map: dict[str, str] = {}
    changed_elements = 0
    for template in converted["templates"]:
        old_id = template["templateId"]
        for index, element in enumerate(template["elements"], start=1):
            subject = f"{path}: template {old_id} element #{index}"
            target = target_for_element(element, subject)
            current = (
                float(element["lightProbeOffsetX"]),
                float(element["lightProbeOffsetY"]),
                float(element["lightProbeOffsetZ"]),
            )
            if close_tuple(current, target):
                continue
            if target != RAILBED_TARGET or not close_tuple(current, RAIL_TARGET):
                raise ValueError(f"{subject}: unexpected existing light probe {current}")
            element["lightProbeOffsetY"] = RAILBED_TARGET[1]
            changed_elements += 1
        new_id = template_hash(template, include_light_probe=True)
        template["templateId"] = new_id
        id_map[old_id] = new_id

    if len(set(id_map.values())) != len(id_map):
        raise ValueError(f"{path}: converted template IDs collide")
    changed_references = 0
    for index, rail in enumerate(converted["rails"], start=1):
        old_id = rail.get("templateId")
        if old_id not in id_map:
            raise ValueError(f"{path}: rail #{index} references missing template {old_id!r}")
        new_id = id_map[old_id]
        if new_id != old_id:
            rail["templateId"] = new_id
            changed_references += 1

    output = (json.dumps(converted, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return output, changed_elements, changed_references


def atomic_write(path: Path, content: bytes) -> None:
    temp = path.with_name(path.name + ".railbed-probe-migration-tmp")
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
        raise ValueError("rail model configs or current rail-render sidecars are missing")

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
        details.append(f"{path.relative_to(root)}: {changed} railbed config element(s)")
    for path in render_paths:
        original = path.read_bytes()
        output, changed_elements, changed_references = convert_render_json(original, path)
        originals[path] = original
        outputs[path] = output
        element_changes += changed_elements
        reference_changes += changed_references
        details.append(
            f"{path.relative_to(root)}: {changed_elements} saved railbed element(s), "
            f"{changed_references} rail reference(s)"
        )

    print("mode:", "APPLY" if args.apply else "DRY-RUN")
    for detail in details:
        print(detail)
    print(
        f"total: {config_changes} config railbed(s), {element_changes} saved railbed(s), "
        f"{reference_changes} rail reference(s)"
    )
    if not args.apply:
        return 0

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    recovery = root / ".javarailways-migrations" / f"raise-railbed-light-probe-offset-{stamp}"
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
        json.dumps(
            {
                "migration": "raise-railbed-light-probe-offset",
                "railTarget": {"x": 0.0, "y": 0.5, "z": 0.0},
                "railbedTarget": {"x": 0.0, "y": 1.0, "z": 0.0},
                "files": manifest,
            },
            indent=2,
        )
        + "\n",
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
