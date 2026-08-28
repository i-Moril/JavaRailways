#!/usr/bin/env python3
"""Add a +0.5 curve-normal light probe offset to rail configs and saved templates.

Dry-run is the default. --apply first creates a timestamped recovery point with
an SHA-256 manifest, then atomically replaces every validated target file.
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

TARGET = (0.0, 0.5, 0.0)
EPSILON = 1.0e-9


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def kotlin_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def template_hash(template: dict[str, Any], *, include_light_probe: bool) -> str:
    raw = kotlin_value(template["repeatLength"])
    for element in template["elements"]:
        item = element.get("itemStack") or {}
        fields = [
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
        if include_light_probe:
            fields.extend(
                [
                    kotlin_value(element.get("lightProbeOffsetX", 0.0)),
                    kotlin_value(element.get("lightProbeOffsetY", 0.0)),
                    kotlin_value(element.get("lightProbeOffsetZ", 0.0)),
                ]
            )
        raw += "|" + ",".join(fields)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def close_tuple(first: tuple[float, ...], second: tuple[float, ...]) -> bool:
    return all(math.isclose(a, b, abs_tol=EPSILON) for a, b in zip(first, second))


def yaml_element_ranges(lines: list[str]) -> list[tuple[int, int]]:
    starts = [index for index, line in enumerate(lines) if line.startswith("  - type:")]
    if not starts:
        raise ValueError("rail_model.yml contains no explicit element definitions")
    return list(zip(starts, starts[1:] + [len(lines)]))


def existing_yaml_probe(block: list[str], subject: str) -> tuple[float, float, float] | None:
    section = next((i for i, line in enumerate(block) if line.strip() == "light_probe_offset:"), None)
    if section is None:
        return None
    values = {"x": 0.0, "y": 0.0, "z": 0.0}
    for line in block[section + 1 :]:
        indent = len(line) - len(line.lstrip(" "))
        if line.strip() and indent <= 4:
            break
        stripped = line.strip()
        if ":" not in stripped:
            continue
        key, raw = (part.strip() for part in stripped.split(":", 1))
        if key in values:
            try:
                values[key] = float(raw)
            except ValueError as error:
                raise ValueError(f"{subject}: light_probe_offset.{key} must be numeric") from error
    return values["x"], values["y"], values["z"]


def convert_yaml(content: bytes, path: Path) -> tuple[bytes, int]:
    lines = content.decode("utf-8").splitlines(keepends=True)
    changed = 0
    # Process backwards so insertions cannot invalidate earlier ranges.
    for element_index, (start, end) in reversed(list(enumerate(yaml_element_ranges(lines), start=1))):
        block = lines[start:end]
        subject = f"{path}: element #{element_index}"
        existing = existing_yaml_probe(block, subject)
        if existing is not None:
            if not close_tuple(existing, TARGET):
                raise ValueError(f"{subject}: unexpected existing light_probe_offset {existing}")
            continue
        rotation = next((start + i for i, line in enumerate(block) if line.strip() == "rotation:"), None)
        if rotation is None:
            raise ValueError(f"{subject}: missing rotation section")
        lines[rotation:rotation] = [
            "    light_probe_offset:\n",
            "      y: 0.5\n",
        ]
        changed += 1
    return "".join(lines).encode("utf-8"), changed


def validate_template_hash(path: Path, template: dict[str, Any]) -> bool:
    old_id = template.get("templateId")
    if not isinstance(old_id, str):
        raise ValueError(f"{path}: template has invalid templateId {old_id!r}")
    # Current kotlinx.serialization output omits default-valued x/z fields. Detect
    # the hash generation rather than requiring all three persisted keys.
    current_hash = template_hash(template, include_light_probe=True)
    if current_hash == old_id:
        return True
    legacy_hash = template_hash(template, include_light_probe=False)
    if legacy_hash == old_id:
        return False
    raise ValueError(
        f"{path}: template hash mismatch: {old_id} != current {current_hash} or legacy {legacy_hash}"
    )


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
        validate_template_hash(path, template)
        seen.add(old_id)

    converted = copy.deepcopy(document)
    id_map: dict[str, str] = {}
    changed_elements = 0
    for template in converted["templates"]:
        old_id = template["templateId"]
        for index, element in enumerate(template["elements"], start=1):
            existing = (
                float(element.get("lightProbeOffsetX", 0.0)),
                float(element.get("lightProbeOffsetY", 0.0)),
                float(element.get("lightProbeOffsetZ", 0.0)),
            )
            has_all = all(
                key in element
                for key in ("lightProbeOffsetX", "lightProbeOffsetY", "lightProbeOffsetZ")
            )
            if has_all and close_tuple(existing, TARGET):
                continue
            if any(key in element for key in ("lightProbeOffsetX", "lightProbeOffsetY", "lightProbeOffsetZ")):
                raise ValueError(
                    f"{path}: template {old_id} element #{index} has unexpected light probe {existing}"
                )
            element["lightProbeOffsetX"] = TARGET[0]
            element["lightProbeOffsetY"] = TARGET[1]
            element["lightProbeOffsetZ"] = TARGET[2]
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
    temp = path.with_name(path.name + ".light-probe-migration-tmp")
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
        details.append(f"{path.relative_to(root)}: {changed} config element(s)")
    for path in render_paths:
        original = path.read_bytes()
        output, changed_elements, changed_references = convert_render_json(original, path)
        originals[path] = original
        outputs[path] = output
        element_changes += changed_elements
        reference_changes += changed_references
        details.append(
            f"{path.relative_to(root)}: {changed_elements} saved element(s), "
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
    recovery = root / ".javarailways-migrations" / f"add-rail-light-probe-offset-{stamp}"
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
                "migration": "add-rail-light-probe-offset",
                "target": {"x": TARGET[0], "y": TARGET[1], "z": TARGET[2]},
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
