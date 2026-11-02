#!/usr/bin/env python3
"""Remove authored destination tolerances from development infrastructure JSON.

Dry-run: script infrastructure.json
Apply, only while the owner is stopped: script infrastructure.json --apply
    --backup-dir /independent/recovery/new-directory
No runtime snapshots, gzip, directory discovery or automatic server operations.
The field removal leaves format versions, revisions and unrelated values intact;
this is not a substitute for validation by the current JVM reader.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import stat
import sys
import tempfile


FIELD = "positionToleranceMeters"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key: " + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("non-finite JSON number: " + value)


def finite_float(value):
    result = float(value)
    if not math.isfinite(result):
        reject_constant(value)
    return result


def decode(data):
    return json.loads(data, object_pairs_hook=unique_object,
                      parse_constant=reject_constant, parse_float=finite_float)


def encode(value):
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode()


def header(value, version):
    if (not isinstance(value, dict) or type(value.get("formatVersion")) is not int
            or value["formatVersion"] != version or "physicsEpoch" in value
            or type(value.get("infrastructureRevision")) is not int
            or not 0 <= value["infrastructureRevision"] <= 9223372036854775807):
        raise ValueError("expected infrastructure format " + str(version) + " with valid revision")


def convert(original):
    root = decode(original)
    outer = isinstance(root, dict) and "simpleAtoSnapshotJson" in root
    if outer:
        header(root, 2)
        for key in ("coreSnapshotJson", "basicSnapshotJson", "simpleAtoSnapshotJson"):
            if not isinstance(root.get(key), str):
                raise ValueError("expected embedded infrastructure JSON string: " + key)
            embedded = decode(root[key])
            header(embedded, 1 if key == "simpleAtoSnapshotJson" else 2)
            if embedded["infrastructureRevision"] != root["infrastructureRevision"]:
                raise ValueError("infrastructure revision mismatch: " + key)
        snapshot = decode(root["simpleAtoSnapshotJson"])
    else:
        snapshot = root
    header(snapshot, 1)
    if (not isinstance(snapshot.get("destinations"), list)
            or not isinstance(snapshot.get("lines"), list)
            or type(snapshot.get("lineRevision")) is not int or snapshot["lineRevision"] < 0):
        raise ValueError("expected SimpleATO infrastructure destinations, lines and lineRevision")
    removed = []
    for index, destination in enumerate(snapshot["destinations"]):
        if not isinstance(destination, dict):
            raise ValueError("destination must be an object")
        if FIELD in destination:
            value = destination[FIELD]
            if type(value) not in (int, float) or value < 0:
                raise ValueError("destination tolerance must be a finite nonnegative number")
            removed.append({"index": index, "id": destination.get("id"), "oldValue": value})
            del destination[FIELD]
    if not removed:
        return original, removed
    if outer:
        root["simpleAtoSnapshotJson"] = encode(snapshot).decode()
    return encode(root), removed


def read_source(path):
    if any(parent.is_symlink() for parent in path.absolute().parents):
        raise ValueError("source ancestors must not be symlinks")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError("source must be a regular non-symlink file")
        return stream.read(), metadata


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def durable_create(path, data):
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def migrate(path, apply=False, backup_dir=None):
    path = path.absolute()
    original, metadata = read_source(path)
    converted, removed = convert(original)
    report = {"source": str(path), "mode": "APPLY" if apply else "DRY-RUN",
              "removedCount": len(removed), "removed": removed,
              "beforeSha256": digest(original), "afterSha256": digest(converted)}
    if not apply:
        if backup_dir is not None:
            raise ValueError("--backup-dir requires --apply")
        return report
    if backup_dir is None:
        raise ValueError("--apply requires --backup-dir outside the source directory")
    backup_dir = backup_dir.absolute()
    if backup_dir.resolve().is_relative_to(path.parent.resolve()):
        raise ValueError("backup directory must be outside the source directory")
    if not removed:
        return report
    # A fresh directory protects the recovery point from collision or rotation.
    backup_dir.mkdir(parents=True, exist_ok=False)
    durable_create(backup_dir / "source.json", original)
    durable_create(backup_dir / "manifest.json", encode(report))
    fsync_directory(backup_dir)
    fsync_directory(backup_dir.parent)
    descriptor, temporary = tempfile.mkstemp(prefix=".stop-tolerance-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(converted)
            os.fchmod(stream.fileno(), stat.S_IMODE(metadata.st_mode))
            os.fsync(stream.fileno())
        current, current_metadata = read_source(path)
        if (digest(current) != report["beforeSha256"]
                or (current_metadata.st_dev, current_metadata.st_ino) != (metadata.st_dev, metadata.st_ino)):
            raise ValueError("source changed before replacement; recovery retained")
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args()
    print(encode(migrate(args.source, args.apply, args.backup_dir)).decode(), end="")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(1)
