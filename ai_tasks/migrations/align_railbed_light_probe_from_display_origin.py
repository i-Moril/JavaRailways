#!/usr/bin/env python3
"""Align railbed probes while retaining display-origin-relative probe semantics.

RAIL probes stay at (0, 0.5, 0). Merged PAPER railbed probes change from
(0, 1, 0) to (0.505, 1.505, 0), cancelling the railbed visual offset in x/y
and placing the resulting probe at curvePosition + normal * 0.5.
Dry-run is the default; --apply creates a recovery point before atomic writes.
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
from raise_railbed_light_probe_offset import (
    RAIL_TARGET,
    RAILBED_CUSTOM_MODEL_DATA,
    probe_axes,
    yaml_element_ranges,
    yaml_identity,
)

OLD_RAILBED_TARGET = (0.0, 1.0, 0.0)
NEW_RAILBED_TARGET = (0.505, 1.505, 0.0)
EPSILON = 1.0e-9


def close_tuple(first: tuple[float, ...], second: tuple[float, ...]) -> bool:
    return all(math.isclose(a, b, abs_tol=EPSILON) for a, b in zip(first, second))


def is_yaml_railbed(block: list[str], subject: str) -> bool:
    element_type, material, custom_model_data = yaml_identity(block, subject)
    if element_type == "BLOCK" and material == "RAIL":
        return False
    if element_type == "ITEM" and material == "PAPER" and custom_model_data in RAILBED_CUSTOM_MODEL_DATA:
        return True
    raise ValueError(f"{subject}: unsupported rail element identity")


def is_saved_railbed(element: dict[str, Any], subject: str) -> bool:
    element_type = str(element.get("type", "")).upper()
    if element_type == "BLOCK" and str(element.get("blockMaterial", "")).upper() == "RAIL":
        return False
    item = element.get("itemStack") or {}
    if (
        element_type == "ITEM"
        and str(item.get("material", "")).upper() == "PAPER"
        and item.get("customModelData") in RAILBED_CUSTOM_MODEL_DATA
    ):
        return True
    raise ValueError(f"{subject}: unsupported rail element identity")


def probe_section_range(block: list[str], subject: str) -> tuple[int, int]:
    section = next((i for i, line in enumerate(block) if line.strip() == "light_probe_offset:"), None)
    if section is None:
        raise ValueError(f"{subject}: missing light_probe_offset")
    end = section + 1
    while end < len(block):
        line = block[end]
        indent = len(line) - len(line.lstrip(" "))
        if line.strip() and indent <= 4:
            break
        end += 1
    return section, end


def convert_yaml(content: bytes, path: Path) -> tuple[bytes, int]:
    lines = content.decode("utf-8").splitlines(keepends=True)
    changed = 0
    for element_index, (start, end) in reversed(list(enumerate(yaml_element_ranges(lines), start=1))):
        block = lines[start:end]
        subject = f"{path}: element #{element_index}"
        railbed = is_yaml_railbed(block, subject)
        values, _ = probe_axes(block, subject)
        current = (values["x"], values["y"], values["z"])
        target = NEW_RAILBED_TARGET if railbed else RAIL_TARGET
        if close_tuple(current, target):
            continue
        if not railbed or not close_tuple(current, OLD_RAILBED_TARGET):
            raise ValueError(f"{subject}: unexpected existing light_probe_offset {current}")
        section, section_end = probe_section_range(block, subject)
        newline = "\n"
        block[section:section_end] = [
            "    light_probe_offset:" + newline,
            "      x: 0.505" + newline,
            "      y: 1.505" + newline,
            "      z: 0.0" + newline,
        ]
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
            railbed = is_saved_railbed(element, subject)
            current = (
                float(element.get("lightProbeOffsetX", 0.0)),
                float(element.get("lightProbeOffsetY", 0.0)),
                float(element.get("lightProbeOffsetZ", 0.0)),
            )
            target = NEW_RAILBED_TARGET if railbed else RAIL_TARGET
            if close_tuple(current, target):
                continue
            if not railbed or not close_tuple(current, OLD_RAILBED_TARGET):
                raise ValueError(f"{subject}: unexpected existing light probe {current}")
            element["lightProbeOffsetX"] = NEW_RAILBED_TARGET[0]
            element["lightProbeOffsetY"] = NEW_RAILBED_TARGET[1]
            element["lightProbeOffsetZ"] = NEW_RAILBED_TARGET[2]
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
    temp = path.with_name(path.name + ".display-origin-probe-migration-tmp")
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
    recovery = root / ".javarailways-migrations" / f"align-railbed-light-probe-from-display-origin-{stamp}"
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
                "migration": "align-railbed-light-probe-from-display-origin",
                "railTarget": {"x": 0.0, "y": 0.5, "z": 0.0},
                "railbedTarget": {"x": 0.505, "y": 1.505, "z": 0.0},
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
