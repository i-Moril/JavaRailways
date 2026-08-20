#!/usr/bin/env python3
"""One-time migration of rail display templates from ITEM to BLOCK.

Dry-run is the default. --apply writes only after validating every input and
creates a timestamped recovery point containing every original file.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
from typing import Any

ITEM_TYPE_LINE = re.compile(r"^(\s*-\s*type:\s*)item(\s*(?:#.*)?)$", re.IGNORECASE | re.MULTILINE)


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
    text = content.decode("utf-8")
    converted, count = ITEM_TYPE_LINE.subn(r"\1block\2", text)
    if re.search(r"^\s*-\s*type:\s*item(?:\s|$)", converted, re.IGNORECASE | re.MULTILINE):
        raise ValueError(f"{path}: ITEM rail display type remains after conversion")
    return converted.encode("utf-8"), count


def convert_render_json(content: bytes, path: Path) -> tuple[bytes, int, int]:
    document = json.loads(content)
    if document.get("version") != 2:
        raise ValueError(f"{path}: expected rail-render version 2, got {document.get('version')!r}")
    templates = document.get("templates")
    rails = document.get("rails")
    if not isinstance(templates, list) or not isinstance(rails, list):
        raise ValueError(f"{path}: templates and rails must be lists")

    old_ids: set[str] = set()
    for template in templates:
        old_id = template.get("templateId")
        if not isinstance(old_id, str) or old_id in old_ids:
            raise ValueError(f"{path}: invalid or duplicate templateId {old_id!r}")
        old_ids.add(old_id)
        calculated = template_hash(template)
        if calculated != old_id:
            raise ValueError(
                f"{path}: template hash mismatch before migration: {old_id} != {calculated}"
            )

    converted = copy.deepcopy(document)
    id_map: dict[str, str] = {}
    item_count = 0
    for template in converted["templates"]:
        old_id = template["templateId"]
        for index, element in enumerate(template["elements"]):
            element_type = element.get("type")
            if element_type == "BLOCK":
                continue
            if element_type != "ITEM":
                raise ValueError(
                    f"{path}: template {old_id} element #{index + 1} has unsupported type {element_type!r}"
                )
            item_stack = element.get("itemStack")
            if not isinstance(item_stack, dict) or not isinstance(item_stack.get("material"), str):
                raise ValueError(f"{path}: template {old_id} element #{index + 1} has no item material")
            unsupported = set(item_stack) - {"material"}
            if unsupported:
                raise ValueError(
                    f"{path}: template {old_id} element #{index + 1} would lose item data: "
                    f"{sorted(unsupported)}"
                )
            element["type"] = "BLOCK"
            element["blockMaterial"] = item_stack["material"]
            element.pop("itemStack", None)
            item_count += 1
        new_id = template_hash(template)
        template["templateId"] = new_id
        id_map[old_id] = new_id

    if len(set(id_map.values())) != len(id_map):
        raise ValueError(f"{path}: converted templates collide; refusing ambiguous reference rewrite")
    for rail in converted["rails"]:
        old_id = rail.get("templateId")
        if old_id not in id_map:
            raise ValueError(f"{path}: rail {rail.get('railId')} references missing template {old_id!r}")
        rail["templateId"] = id_map[old_id]

    def contains_item(value: Any) -> bool:
        if value == "ITEM":
            return True
        if isinstance(value, dict):
            return any(contains_item(v) for v in value.values())
        if isinstance(value, list):
            return any(contains_item(v) for v in value)
        return False

    if contains_item(converted):
        raise ValueError(f"{path}: ITEM value remains after conversion")
    output = (json.dumps(converted, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return output, item_count, len(rails)


def atomic_write(path: Path, content: bytes) -> None:
    temp = path.with_name(path.name + ".migration-tmp")
    with temp.open("wb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="Minecraft instance root")
    parser.add_argument("--apply", action="store_true", help="create recovery point and replace files")
    args = parser.parse_args()
    root = args.root.resolve()
    data = root / "plugins" / "JavaRailways"
    yaml_paths = sorted((data / "rails").glob("*/rail_model.yml"))
    render_paths = sorted((data / "worlds").glob("*-rail-render.json"))
    if not yaml_paths:
        raise ValueError(f"no rail_model.yml files found under {data / 'rails'}")
    if not render_paths:
        raise ValueError(f"no rail-render.json files found under {data / 'worlds'}")

    originals: dict[Path, bytes] = {}
    outputs: dict[Path, bytes] = {}
    details: list[str] = []
    total_yaml_items = 0
    total_saved_items = 0
    total_saved_rails = 0
    for path in yaml_paths:
        original = path.read_bytes()
        output, count = convert_yaml(original, path)
        originals[path] = original
        outputs[path] = output
        total_yaml_items += count
        details.append(f"{path.relative_to(root)}: {count} config element(s) ITEM -> BLOCK")
    for path in render_paths:
        original = path.read_bytes()
        output, item_count, rail_count = convert_render_json(original, path)
        originals[path] = original
        outputs[path] = output
        total_saved_items += item_count
        total_saved_rails += rail_count
        details.append(
            f"{path.relative_to(root)}: {item_count} saved template element(s), "
            f"{rail_count} saved rail reference(s)"
        )

    print("mode:", "APPLY" if args.apply else "DRY-RUN")
    for detail in details:
        print(detail)
    print(
        f"total: {total_yaml_items} config element(s), {total_saved_items} saved template element(s), "
        f"{total_saved_rails} saved rail reference(s)"
    )
    if not args.apply:
        return 0

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    recovery = root / ".javarailways-migrations" / f"rail-displays-item-to-block-{stamp}"
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
        json.dumps({"migration": "rail-displays-item-to-block", "files": manifest}, indent=2) + "\n",
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
