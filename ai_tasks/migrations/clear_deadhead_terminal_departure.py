#!/usr/bin/env python3
"""Remove stale terminal departureTick fields from DEADHEAD runs.

Dry-run by default. Apply with --apply and a new backup directory.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def read_json(path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def clear(document):
    changed = []
    for run in document.get("runs", []):
        if run.get("type") != "DEADHEAD" or not run.get("calls"):
            continue
        terminal = run["calls"][-1]
        if terminal.get("departureTick") is None:
            continue
        changed.append({"runId": run.get("id"), "callId": terminal.get("id"),
                        "oldDepartureTick": terminal.get("departureTick")})
        terminal["departureTick"] = None
    return changed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--world", default="world")
    parser.add_argument("--document", default="new-timetable-9")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args()

    path = args.root / "drafts/editor-documents" / args.world / f"{args.document}.json"
    if not path.is_file():
        raise SystemExit(f"document not found: {path}")
    original = read_json(path)
    document = json.loads(json.dumps(original))
    changed = clear(document)
    result = {"mode": "APPLIED" if args.apply else "DRY-RUN", "document": str(path),
              "changed": changed}
    if not changed:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    if not args.apply:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    if args.backup_dir is None:
        raise SystemExit("--backup-dir is required with --apply")
    if args.backup_dir.exists():
        raise SystemExit(f"backup directory already exists: {args.backup_dir}")
    args.backup_dir.mkdir(parents=True)
    backup = args.backup_dir / path.name
    shutil.copy2(path, backup)
    manifest = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "source": str(path), "backup": str(backup),
        "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "backupSha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        "changed": changed,
    }
    (args.backup_dir / "backup-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_json_atomic(path, document)
    result["backupDirectory"] = str(args.backup_dir)
    result["sourceSha256Before"] = manifest["sourceSha256"]
    result["sourceSha256After"] = hashlib.sha256(path.read_bytes()).hexdigest()
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
