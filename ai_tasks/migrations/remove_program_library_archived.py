#!/usr/bin/env python3
"""Remove obsolete library archive metadata, offline, without a runtime reader.

First: script LIBRARY_ROOT --plan /recovery/plan.json
Then, with the WebUI stopped: script LIBRARY_ROOT --apply --plan /recovery/plan.json
    --backup /recovery/program-library

The exact library root is draftDirectory/station-programs. Dry-run never changes
it. Apply requires the saved hash-pinned plan and a new backup directory outside
the library. Every source file is backed up, including already-current records.
Archived entries are removed; embedded document/runtime snapshots are untouched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}\Z")
IDENTITY = re.compile(r"sha256:[0-9a-f]{64}\Z")
FIELDS = {"formatVersion", "id", "name", "revision", "definition", "contentIdentity"}
MIGRATION = "remove-program-library-archived"


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def finite_float(value: str):
    result = float(value)
    if not math.isfinite(result):
        raise ValueError("non-finite JSON number")
    return result


def decode(content: bytes):
    return json.loads(content, object_pairs_hook=unique_object, parse_float=finite_float,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite JSON number")))


def output_json(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8")


def convert(content: bytes, record_id: str) -> tuple[str, bytes | None]:
    value = decode(content)
    if not isinstance(value, dict) or set(value) not in (FIELDS, FIELDS | {"archived"}):
        raise ValueError("unexpected program library record fields")
    if type(value["formatVersion"]) is not int or value["formatVersion"] != 1:
        raise ValueError("expected program library formatVersion 1")
    if value["id"] != record_id:
        raise ValueError("filename and record ID differ")
    if not isinstance(value["name"], str) or not value["name"].strip() or len(value["name"]) > 256:
        raise ValueError("invalid program name")
    if type(value["revision"]) is not int or not 1 <= value["revision"] <= 9007199254740991:
        raise ValueError("invalid program revision")
    definition = value["definition"]
    if (not isinstance(definition, dict) or set(definition) != {"parameters", "steps"}
            or not isinstance(definition["parameters"], list) or len(definition["parameters"]) > 64
            or not isinstance(definition["steps"], list) or len(definition["steps"]) > 256):
        raise ValueError("invalid program definition shape")
    if not isinstance(value["contentIdentity"], str) or not IDENTITY.fullmatch(value["contentIdentity"]):
        raise ValueError("invalid program content identity")
    # Definition semantics and the Jackson-specific canonical hash remain owned
    # by the Java validator. This metadata-only tool preserves both unchanged.
    if "archived" not in value:
        return "unchanged", content
    if type(value["archived"]) is not bool:
        raise ValueError("archived must be boolean")
    if value.pop("archived"):
        return "delete", None
    return "rewrite", output_json(value)


def inspect(root: Path) -> tuple[dict, dict[str, tuple[bytes, bytes | None]]]:
    if root.is_symlink() or not root.is_dir():
        raise ValueError("library root must be an existing non-symlink directory")
    if any(parent.is_symlink() for parent in root.absolute().parents):
        raise ValueError("library root ancestors must not be symlinks")
    root = root.resolve()
    files, payloads = [], {}
    for world in sorted(root.iterdir()):
        if world.is_symlink() or not world.is_dir() or not ID.fullmatch(world.name):
            raise ValueError("unexpected entry in library root")
        for path in sorted(world.iterdir()):
            if (path.is_symlink() or not path.is_file() or path.suffix != ".json"
                    or not ID.fullmatch(path.stem) or path.stat().st_nlink != 1):
                raise ValueError("unexpected or linked entry in library world directory")
            relative = path.relative_to(root).as_posix()
            original = path.read_bytes()
            try:
                action, converted = convert(original, path.stem)
            except (ValueError, TypeError, KeyError, UnicodeError) as error:
                raise ValueError(f"{relative}: invalid library record ({type(error).__name__})") from None
            files.append({"path": relative, "action": action, "beforeSha256": digest(original),
                          "afterSha256": digest(converted) if converted is not None else None})
            payloads[relative] = original, converted
    return {"migration": MIGRATION, "root": str(root), "files": files}, payloads


def outside(root: Path, path: Path):
    if path.resolve().is_relative_to(root.resolve()):
        raise ValueError("plan and backup must be outside the library root")


def atomic_replace(path: Path, content: bytes):
    descriptor, temporary = tempfile.mkstemp(prefix=".archive-migration-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        shutil.copystat(path, temporary)
        original_stat = path.stat()
        temporary_stat = os.stat(temporary)
        if (temporary_stat.st_uid, temporary_stat.st_gid) != (original_stat.st_uid, original_stat.st_gid):
            os.chown(temporary, original_stat.st_uid, original_stat.st_gid)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def apply(root: Path, expected: dict, backup: Path):
    outside(root, backup)
    current, payloads = inspect(root)
    if current != expected:
        raise ValueError("library differs from saved plan; create and review a new dry-run plan")
    backup.mkdir(parents=True, exist_ok=False)
    for relative, (original, _) in payloads.items():
        target = backup / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        source = root / relative
        shutil.copy2(source, target)
        with target.open("rb") as recovery_stream:
            os.fsync(recovery_stream.fileno())
        if target.read_bytes() != original:
            raise ValueError("source changed while backing up; no migration applied")
    with (backup / "manifest.json").open("xb") as manifest_stream:
        manifest_stream.write(output_json(current))
        manifest_stream.flush()
        os.fsync(manifest_stream.fileno())
    # Recheck the entire source tree after backup and before the first mutation.
    if inspect(root)[0] != current:
        raise ValueError("source changed during backup; no migration applied")
    for item in current["files"]:
        path = root / item["path"]
        if item["action"] == "delete":
            path.unlink()
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        elif item["action"] == "rewrite":
            atomic_replace(path, payloads[item["path"]][1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--plan", type=Path, help="write dry-run plan, or read exact plan during apply")
    parser.add_argument("--backup", type=Path, help="new recovery directory; required with --apply")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = args.root.absolute()
    plan, _ = inspect(root)
    if args.plan:
        outside(root, args.plan)
    if args.apply:
        if not args.plan or not args.backup:
            parser.error("--apply requires --plan and --backup; stop WebUI before applying")
        apply(root, decode(args.plan.read_bytes()), args.backup)
    elif args.backup:
        parser.error("--backup requires --apply")
    elif args.plan:
        with args.plan.open("xb") as stream:
            stream.write(output_json(plan))
    print("mode:", "APPLY" if args.apply else "DRY-RUN")
    print("root:", root)
    for action in ("unchanged", "rewrite", "delete"):
        print(action + ":", sum(item["action"] == action for item in plan["files"]))
    if args.apply:
        print("recovery:", args.backup)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
