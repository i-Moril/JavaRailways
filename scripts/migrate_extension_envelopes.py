#!/usr/bin/env python3
"""One-shot CR-029 extension conversion; dry-run unless --output names a NEW directory.

Accept a raw Core runtime JSON file, an outer split-runtime file, or a directory
containing *-runtime.json[.bak] files (also gzip encoded). Only runtime files are
copied. This does NOT migrate outer/Basic ownership, curves or a complete world.
Keep the original archive/manifests as a durable recovery point before applying.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path


LEGACY_TYPES = {
    "work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState":
        ("javarailways:car_render_state", "car"),
    "work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState":
        ("javarailways:train_render_state", "train"),
}
CURRENT_TYPES = {type_id: owner for type_id, owner in LEGACY_TYPES.values()}
RUNTIME_SUFFIXES = ("-runtime.json", "-runtime.json.bak", "-runtime.json.gz", "-runtime.json.gz.bak")


def fail(path: str, message: str):
    raise ValueError(f"{path}: {message}")


def object_at(value, path: str) -> dict:
    if not isinstance(value, dict):
        fail(path, "expected object")
    return value


def parse_json(data: str, path: str):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail(path, f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    def invalid_number(token):
        fail(path, f"invalid JSON number {token}")

    try:
        return json.loads(data, object_pairs_hook=unique_object, parse_constant=invalid_number)
    except json.JSONDecodeError as error:
        fail(path, str(error))


def validate_payload(payload, owner: str, path: str):
    payload = object_at(payload, path)
    fields = ({"carModelName", "heldScenes", "modelSnapshotHash", "packageSnapshotHash"}
              if owner == "car" else {"version", "packageName", "packageSnapshotHash"})
    if payload.keys() - fields:
        fail(path, f"unknown payload fields: {sorted(payload.keys() - fields)}")
    if owner == "train" and "version" in payload:
        if type(payload["version"]) is not int or payload["version"] != 1:
            fail(path + ".version", "expected supported version 1")
    if owner == "car":
        if not isinstance(payload.get("carModelName"), str):
            fail(path + ".carModelName", "expected string")
        scenes = payload.get("heldScenes", {})
        if not isinstance(scenes, dict) or any(not isinstance(v, str) for v in scenes.values()):
            fail(path + ".heldScenes", "expected string-to-string object")
    for field in fields - {"version", "carModelName", "heldScenes"}:
        if field in payload and payload[field] is not None and not isinstance(payload[field], str):
            fail(path + "." + field, "expected nullable string")


def convert_map(value, owner: str, path: str, changes: list[str]):
    extensions = object_at(value, path)
    for key, raw in extensions.items():
        entry_path = path + "[" + json.dumps(key) + "]"
        entry = object_at(raw, entry_path)
        if "type" in entry:
            legacy_type = entry["type"]
            if not isinstance(legacy_type, str) or legacy_type not in LEGACY_TYPES:
                fail(entry_path + ".type", "unknown legacy extension discriminator")
            type_id, expected_owner = LEGACY_TYPES[legacy_type]
            payload = {k: v for k, v in entry.items() if k != "type"}
            converted = True
        else:
            if set(entry) != {"typeId", "schemaVersion", "payload"}:
                fail(entry_path, "expected exact typeId/schemaVersion/payload envelope")
            type_id = entry["typeId"]
            if not isinstance(type_id, str) or type_id not in CURRENT_TYPES:
                fail(entry_path + ".typeId", "unknown extension type ID")
            if type(entry["schemaVersion"]) is not int or entry["schemaVersion"] != 1:
                fail(entry_path + ".schemaVersion", "expected supported schema version 1")
            expected_owner = CURRENT_TYPES[type_id]
            payload = entry["payload"]
            converted = False
        if owner != expected_owner:
            fail(entry_path, f"{type_id} belongs to {expected_owner}, not {owner}")
        validate_payload(payload, owner, entry_path + ".payload")
        if converted:
            extensions[key] = {"typeId": type_id, "schemaVersion": 1, "payload": payload}
            changes.append(entry_path)


def convert_runtime(root, path: str, changes: list[str]):
    root = object_at(root, path)
    if type(root.get("formatVersion")) is not int or root["formatVersion"] != 1:
        fail(path + ".formatVersion", "expected Core runtime version 1")
    trains = root.get("trains")
    if not isinstance(trains, list):
        fail(path + ".trains", "expected array")
    for ti, train in enumerate(trains):
        tp = f"{path}.trains[{ti}]"
        train = object_at(train, tp)
        convert_map(train.get("extension"), "train", tp + ".extension", changes)
        cars = train.get("cars")
        if not isinstance(cars, list):
            fail(tp + ".cars", "expected array")
        for ci, car in enumerate(cars):
            cp = f"{tp}.cars[{ci}]"
            car = object_at(car, cp)
            config = object_at(car.get("config"), cp + ".config")
            convert_map(config.get("extension"), "car", cp + ".config.extension", changes)
            for bogie in ("bogieA", "bogieB"):
                data = object_at(car.get(bogie), cp + "." + bogie)
                convert_map(data.get("extension"), "bogie", cp + "." + bogie + ".extension", changes)


def convert_bytes(original: bytes, label: str) -> tuple[bytes, list[str]]:
    compressed = original.startswith(b"\x1f\x8b")
    text = (gzip.decompress(original) if compressed else original).decode("utf-8")
    root = object_at(parse_json(text, label), label)
    changes: list[str] = []
    if "coreSnapshotJson" in root:
        if type(root.get("formatVersion")) is not int or root["formatVersion"] not in (1, 2):
            fail(label + ".formatVersion", "expected supported outer runtime version 1 or 2")
        if not isinstance(root["coreSnapshotJson"], str):
            fail(label + ".coreSnapshotJson", "expected encoded JSON string")
        core_path = label + ".coreSnapshotJson"
        runtime = parse_json(root["coreSnapshotJson"], core_path)
        convert_runtime(runtime, core_path, changes)
        if changes:
            root["coreSnapshotJson"] = json.dumps(runtime, ensure_ascii=False, separators=(",", ":"))
    else:
        convert_runtime(root, label, changes)
    if not changes:
        return original, changes
    result = (json.dumps(root, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    return (gzip.compress(result, mtime=0) if compressed else result), changes


def migrate(source: Path, output: Path | None = None) -> dict:
    if source.is_symlink():
        raise ValueError("source must not be a symlink")
    source = source.resolve(strict=True)
    if output is not None:
        output = output.absolute()
        if output.exists() or output.is_symlink():
            raise ValueError("output must be a new directory; no overwrite or in-place migration")
        output = output.resolve()
        if source.is_dir() and output.is_relative_to(source):
            raise ValueError("output must not be inside source directory")
    files = ([source] if source.is_file() else
             sorted(p for p in source.iterdir() if p.name.endswith(RUNTIME_SUFFIXES)))
    if not files:
        raise ValueError("no runtime files found; pass the worlds directory or a specific runtime file")
    prepared = []
    report = {"dryRun": output is None, "source": str(source), "files": [], "conversions": 0}
    for file in files:
        if file.is_symlink() or not file.is_file():
            raise ValueError(f"{file}: expected regular non-symlink runtime file")
        original = file.read_bytes()
        converted, changes = convert_bytes(original, file.name)
        prepared.append((file.name, converted))
        report["files"].append({"file": file.name, "sourceSha256": hashlib.sha256(original).hexdigest(),
                                "outputSha256": hashlib.sha256(converted).hexdigest(),
                                "conversions": len(changes), "changedPaths": changes})
        report["conversions"] += len(changes)
    if output is not None:
        output.mkdir()  # refuse missing parent or concurrent creation; never overwrite an existing target
        for name, data in prepared:
            with (output / name).open("xb") as destination:
                destination.write(data)
        with (output / "migration-report.json").open("x", encoding="utf-8") as destination:
            json.dump(report, destination, ensure_ascii=False, indent=2)
            destination.write("\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="runtime file or worlds directory (no recursive scan)")
    parser.add_argument("--output", type=Path, help="explicit application to a new directory; source stays untouched")
    args = parser.parse_args()
    try:
        print(json.dumps(migrate(args.source, args.output), ensure_ascii=False, indent=2))
    except (ValueError, OSError, UnicodeError, EOFError) as error:
        parser.exit(1, f"Migration rejected: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
