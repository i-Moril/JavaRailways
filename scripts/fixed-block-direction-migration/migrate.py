#!/usr/bin/env python3
"""Delete only fixedBlocks[*].bidirectional from an audited development copy."""

import argparse
import decimal
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


MIGRATION = "remove-fixed-block-bidirectional"
FILE_NAME = re.compile(r"([0-9a-f-]{36})-definitions\.json(?:\.bak)?\Z")
ROOT_REQUIRED = {"schemaVersion", "worldId", "definitionRevision", "protectionRegions", "protectionRouteTables"}
ROOT_OPTIONAL = {"fixedBlocks", "fixedBlockSignals", "protectionSignals", "routeIndicators", "attachedSignals",
                 "pendingProtectionBoundaries", "boundaryNames"}
BLOCK_REQUIRED = {"blockId", "revision", "traversals"}


def reject_constant(value):
    raise ValueError(f"Invalid JSON number: {value}")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


DECODER = json.JSONDecoder(object_pairs_hook=unique_object, parse_float=decimal.Decimal,
                           parse_constant=reject_constant)


def skip_space(text, offset):
    while offset < len(text) and text[offset] in " \t\r\n":
        offset += 1
    return offset


def members(text, offset):
    """Locate members in an already strictly parsed JSON object, without rewriting values."""
    result = []
    offset = skip_space(text, offset + 1)
    while text[offset] != "}":
        start = offset
        key, offset = DECODER.raw_decode(text, offset)
        offset = skip_space(text, offset)
        assert text[offset] == ":"
        value_start = skip_space(text, offset + 1)
        _, end = DECODER.raw_decode(text, value_start)
        result.append((key, start, value_start, end))
        offset = skip_space(text, end)
        if text[offset] == "}":
            break
        assert text[offset] == ","
        offset = skip_space(text, offset + 1)
    return result


def convert_bytes(raw, expected_world):
    """Narrow structural conversion; CLI additionally requires the actual current Kotlin codec."""
    text = raw.decode("utf-8")
    root = DECODER.decode(text)
    if not isinstance(root, dict) or not ROOT_REQUIRED <= root.keys() or root.keys() - (ROOT_REQUIRED | ROOT_OPTIONAL):
        raise ValueError("Unfamiliar definitions root layout")
    if type(root["schemaVersion"]) is not int or root["schemaVersion"] != 1:
        raise ValueError("Expected schemaVersion 1")
    if root["worldId"] != str(uuid.UUID(expected_world)) or expected_world != str(uuid.UUID(expected_world)):
        raise ValueError("worldId does not match canonical filename UUID")
    if type(root["definitionRevision"]) is not int or root["definitionRevision"] < 0:
        raise ValueError("Expected non-negative definitionRevision")
    for key in ROOT_REQUIRED - {"schemaVersion", "worldId", "definitionRevision"} | ROOT_OPTIONAL:
        if key in root and not isinstance(root[key], list):
            raise ValueError(f"Expected array: {key}")
    blocks = root.get("fixedBlocks", [])
    for index, block in enumerate(blocks):
        if not isinstance(block, dict) or not BLOCK_REQUIRED <= block.keys() or block.keys() - (BLOCK_REQUIRED | {"bidirectional"}):
            raise ValueError(f"Unfamiliar fixedBlocks[{index}] layout")
        if not isinstance(block["blockId"], str) or type(block["revision"]) is not int or block["revision"] < 0:
            raise ValueError(f"Invalid fixedBlocks[{index}] identity/revision")
        if not isinstance(block["traversals"], list) or not block["traversals"]:
            raise ValueError(f"Expected nonempty fixedBlocks[{index}].traversals")
        if "bidirectional" in block and type(block["bidirectional"]) is not bool:
            raise ValueError(f"Expected Boolean fixedBlocks[{index}].bidirectional")

    cuts, changes = [], []
    block_member = next((member for member in members(text, skip_space(text, 0)) if member[0] == "fixedBlocks"), None)
    if block_member:
        offset = skip_space(text, block_member[2] + 1)
        for index, block in enumerate(blocks):
            block_members = members(text, offset)
            for position, member in enumerate(block_members):
                if member[0] != "bidirectional":
                    continue
                if position + 1 < len(block_members):
                    cuts.append((member[1], block_members[position + 1][1]))
                else:
                    cuts.append((block_members[position - 1][3], member[3]))
                changes.append({"path": f"fixedBlocks[{index}].bidirectional", "blockId": block["blockId"],
                                "removedValue": block["bidirectional"]})
                del block["bidirectional"]
            _, offset = DECODER.raw_decode(text, offset)
            offset = skip_space(text, offset)
            if text[offset] == ",":
                offset = skip_space(text, offset + 1)
    for start, end in reversed(cuts):
        text = text[:start] + text[end:]
    if DECODER.decode(text) != root:
        raise ValueError("Conversion changed unrelated JSON values")
    return text.encode("utf-8"), changes


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def plain_path(path):
    path = Path(path).absolute()
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError(f"Symlink paths are not supported: {path}")
    return path.resolve()


def inventory(source):
    if not source.is_dir():
        raise ValueError("Source must be an extracted SimpleSignals worlds directory")
    result = {}
    for path in sorted(source.iterdir()):
        match = FILE_NAME.fullmatch(path.name)
        if path.is_symlink() or not path.is_file() or not match or str(uuid.UUID(match[1])) != match[1]:
            raise ValueError(f"Unfamiliar or non-regular sidecar input: {path.name}")
        result[path.name] = digest(path.read_bytes())
    return result


def plan(source):
    source = plain_path(source)
    hashes = inventory(source)
    if not hashes:
        raise ValueError("Source contains no definition sidecars; verify the extracted directory")
    files, converted = [], {}
    for name, before in hashes.items():
        raw = (source / name).read_bytes()
        if digest(raw) != before:
            raise ValueError("Source changed during planning")
        target, changes = convert_bytes(raw, name[:36])
        converted[name] = target
        files.append({"name": name, "sourceSha256": before, "candidateSha256": digest(target), "removedFields": changes})
    if inventory(source) != hashes:
        raise ValueError("Source inventory changed during planning")
    report = {"migration": MIGRATION, "source": str(source), "sourceSha256": hashes, "files": files,
              "removedFields": sum(len(entry["removedFields"]) for entry in files)}
    return report, converted


def validate_current(converted, classpath, java):
    entries = [plain_path(entry) for entry in classpath.split(os.pathsep)]
    if not entries or any(not entry.is_file() or entry.suffix != ".jar" for entry in entries):
        raise ValueError("Classpath must be an explicit list of current JAR files (no wildcards/directories)")
    hashes = {str(entry): digest(entry.read_bytes()) for entry in entries}
    helper = Path(__file__).with_name("ValidateDefinitions.java")
    helper_hash = digest(helper.read_bytes())
    with tempfile.TemporaryDirectory(prefix="fixed-block-current-codec-") as directory:
        candidate = Path(directory)
        for name, raw in converted.items():
            (candidate / name).write_bytes(raw)
        result = subprocess.run([java, "-cp", os.pathsep.join(map(str, entries)), str(helper), str(candidate)],
                                capture_output=True, text=True)
        if result.returncode:
            raise ValueError("Current codec rejected candidate:\n" + result.stderr.strip())
    if hashes != {str(entry): digest(entry.read_bytes()) for entry in entries}:
        raise ValueError("Validator JARs changed during validation")
    if helper_hash != digest(helper.read_bytes()):
        raise ValueError("Validator helper changed during validation")
    return {"result": result.stdout.strip(), "classpathSha256": hashes, "helperSha256": helper_hash}


def write_candidate(source, output, report, converted, expected):
    source, output = plain_path(source), plain_path(output)
    if source == output or output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError("Output must be a new directory independent of the source")
    if expected.get("migration") != MIGRATION or expected.get("sourceSha256") != report["sourceSha256"] or expected.get("files") != report["files"]:
        raise ValueError("Dry-run report does not match source/candidate hashes")
    if {entry["name"]: entry["candidateSha256"] for entry in report["files"]} != {name: digest(raw) for name, raw in converted.items()}:
        raise ValueError("Candidate bytes do not match reviewed hashes")
    if inventory(source) != report["sourceSha256"]:
        raise ValueError("Source changed before candidate output")
    output.mkdir()  # Exclusive directory reservation: never overwrite an earlier candidate/recovery point.
    for name, raw in converted.items():
        atomic_write(output / name, raw)
    if inventory(source) != report["sourceSha256"]:
        raise ValueError("Source changed while writing candidate; candidate is incomplete")
    report = {**report, "dryRun": False, "candidate": str(output), "complete": True}
    atomic_write(output / "migration-report.json", (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode())
    return report


def atomic_write(path, raw):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("xb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--classpath", required=True, help="Explicit current SimpleSignals and host JARs, separated by OS path separator")
    parser.add_argument("--java", default="java", help="Java 25 executable")
    parser.add_argument("--output", type=Path, help="Create a new independent candidate directory")
    parser.add_argument("--expected-report", type=Path, help="Previously reviewed dry-run JSON; mandatory with --output")
    args = parser.parse_args()
    if bool(args.output) != bool(args.expected_report):
        parser.error("--output and --expected-report must be supplied together")
    report, converted = plan(args.source)
    report["currentCodec"] = validate_current(converted, args.classpath, args.java)
    if inventory(plain_path(args.source)) != report["sourceSha256"]:
        raise ValueError("Source changed during current-codec validation")
    report["dryRun"] = True
    if args.output:
        expected = json.loads(args.expected_report.read_text(encoding="utf-8"))
        report = write_candidate(args.source, args.output, report, converted, expected)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
