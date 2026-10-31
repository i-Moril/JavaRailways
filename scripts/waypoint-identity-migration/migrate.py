#!/usr/bin/env python3
"""Remove waypoint display names from extracted development infrastructure copies only."""

import argparse
import decimal
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


MIGRATION = "waypoint-identity-only"
FILE_NAME = re.compile(r".+-infrastructure\.json(?:\.bak)?\Z")


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
    """Locate members of an already parsed JSON object, retaining original value spelling."""
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


def string_boundaries(text, start):
    """Map each decoded character boundary to its original JSON-string source offset."""
    decoded, end = DECODER.raw_decode(text, start)
    if not isinstance(decoded, str):
        raise ValueError("Expected a JSON snapshot string")
    offset = start + 1
    boundaries = [offset]
    while offset < end - 1:
        if text[offset] != "\\":
            offset += 1
        elif text[offset + 1] != "u":
            offset += 2
        else:
            code = int(text[offset + 2:offset + 6], 16)
            offset += 6
            if (0xD800 <= code <= 0xDBFF and text[offset:offset + 2] == "\\u"
                    and 0xDC00 <= int(text[offset + 2:offset + 6], 16) <= 0xDFFF):
                offset += 6
        boundaries.append(offset)
    if len(boundaries) != len(decoded) + 1:
        raise ValueError("Snapshot string mapping failed")
    return decoded, boundaries


def convert_bytes(raw):
    compressed = raw.startswith(b"\x1f\x8b")
    text = (gzip.decompress(raw) if compressed else raw).decode("utf-8")
    root = DECODER.decode(text)
    if not isinstance(root, dict):
        raise ValueError("Infrastructure must be an object")
    root_members = {member[0]: member for member in members(text, skip_space(text, 0))}
    cuts, changes = [], []
    for field, collection in (("basicSnapshotJson", "wayPoints"), ("simpleAtoSnapshotJson", "destinations")):
        if field not in root_members:
            raise ValueError(f"Missing infrastructure snapshot: {field}")
        nested, boundaries = string_boundaries(text, root_members[field][2])
        data = DECODER.decode(nested)
        if not isinstance(data, dict) or not isinstance(data.get(collection, []), list):
            raise ValueError(f"Expected snapshot object with {collection} array")
        array = next((member for member in members(nested, skip_space(nested, 0)) if member[0] == collection), None)
        if array is None:
            continue
        offset = skip_space(nested, array[2] + 1)
        for index, point in enumerate(data[collection]):
            if not isinstance(point, dict):
                raise ValueError(f"Expected object at {field}.{collection}[{index}]")
            entries = members(nested, offset)
            for position, member in enumerate(entries):
                if member[0] != "displayName":
                    continue
                if len(entries) == 1:
                    begin, end = member[1], member[3]
                elif position + 1 < len(entries):
                    begin, end = member[1], entries[position + 1][1]
                else:
                    begin, end = entries[position - 1][3], member[3]
                cuts.append((boundaries[begin], boundaries[end]))
                changes.append({"path": f"{field}.{collection}[{index}].displayName", "id": point.get("id")})
                del point["displayName"]
            _, offset = DECODER.raw_decode(nested, offset)
            offset = skip_space(nested, offset)
            if nested[offset] == ",":
                offset = skip_space(nested, offset + 1)
        root[field] = data
    # Parse the untouched Core payload as well, including duplicate-key detection.
    if "coreSnapshotJson" in root:
        DECODER.decode(root["coreSnapshotJson"])
    if not cuts:
        return raw, changes
    for begin, end in sorted(cuts, reverse=True):
        text = text[:begin] + text[end:]
    actual = DECODER.decode(text)
    for field in ("basicSnapshotJson", "simpleAtoSnapshotJson"):
        actual[field] = DECODER.decode(actual[field])
        if isinstance(root[field], str):
            root[field] = DECODER.decode(root[field])
    if actual != root:
        raise ValueError("Conversion changed unrelated JSON values")
    converted = text.encode("utf-8")
    return (gzip.compress(converted, mtime=0) if compressed else converted), changes


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def plain_path(path):
    path = Path(path).absolute()
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError(f"Symlink paths are not supported: {path}")
    return path.resolve()


def inventory(source):
    if not source.is_dir():
        raise ValueError("Source must be an extracted worlds directory")
    result = {}
    for path in sorted(source.iterdir()):
        if not FILE_NAME.fullmatch(path.name):
            continue
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Non-regular infrastructure input: {path.name}")
        result[path.name] = digest(path.read_bytes())
    return result


def plan(source):
    source = plain_path(source)
    hashes = inventory(source)
    if not hashes:
        raise ValueError("Source contains no infrastructure files")
    files, converted = [], {}
    for name, before in hashes.items():
        raw = (source / name).read_bytes()
        if digest(raw) != before:
            raise ValueError("Source changed during planning")
        target, changes = convert_bytes(raw)
        converted[name] = target
        files.append({"name": name, "sourceSha256": before, "candidateSha256": digest(target), "removedFields": changes})
    if inventory(source) != hashes:
        raise ValueError("Source inventory changed during planning")
    return {"migration": MIGRATION, "source": str(source), "sourceSha256": hashes, "files": files,
            "removedFields": sum(len(entry["removedFields"]) for entry in files)}, converted


def validate_current(converted, classpath, java):
    entries = [plain_path(entry) for entry in classpath.split(os.pathsep)]
    if not entries or any(not entry.is_file() or entry.suffix != ".jar" for entry in entries):
        raise ValueError("Classpath must be explicit current JAR files (no wildcards/directories)")
    hashes = {str(entry): digest(entry.read_bytes()) for entry in entries}
    helper = Path(__file__).with_name("ValidateInfrastructure.java")
    helper_hash = digest(helper.read_bytes())
    with tempfile.TemporaryDirectory(prefix="waypoint-current-codec-") as directory:
        for name, raw in converted.items():
            (Path(directory) / name).write_bytes(raw)
        result = subprocess.run([java, "-cp", os.pathsep.join(map(str, entries)), str(helper), directory],
                                capture_output=True, text=True)
        if result.returncode:
            raise ValueError("Current codec rejected candidate:\n" + result.stderr.strip())
    if hashes != {str(entry): digest(entry.read_bytes()) for entry in entries}:
        raise ValueError("Validator JARs changed during validation")
    if helper_hash != digest(helper.read_bytes()):
        raise ValueError("Validator helper changed during validation")
    return {"result": result.stdout.strip(), "classpathSha256": hashes, "helperSha256": helper_hash}


def atomic_write(path, raw):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("xb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def write_candidate(source, output, report, converted, expected):
    source, output = plain_path(source), plain_path(output)
    if source == output or output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError("Output must be a new directory independent of the source")
    if (expected.get("migration") != MIGRATION or expected.get("source") != report["source"]
            or expected.get("sourceSha256") != report["sourceSha256"] or expected.get("files") != report["files"]
            or expected.get("dryRun") is not True or expected.get("currentCodec") != report.get("currentCodec")):
        raise ValueError("Dry-run report does not match source/candidate/validator hashes")
    if {entry["name"]: entry["candidateSha256"] for entry in report["files"]} != {name: digest(raw) for name, raw in converted.items()}:
        raise ValueError("Candidate bytes do not match reviewed hashes")
    if inventory(source) != report["sourceSha256"]:
        raise ValueError("Source changed before candidate output")
    output.mkdir()
    for name, raw in converted.items():
        atomic_write(output / name, raw)
    if inventory(source) != report["sourceSha256"]:
        raise ValueError("Source changed while writing candidate; candidate is incomplete")
    report = {**report, "dryRun": False, "candidate": str(output), "complete": True}
    atomic_write(output / "migration-report.json", (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--classpath", required=True, help="Explicit current JavaRailways shadow JAR path(s)")
    parser.add_argument("--java", default="java", help="Java 25 executable")
    parser.add_argument("--output", type=Path, help="Create a new independent candidate directory")
    parser.add_argument("--expected-report", type=Path, help="Reviewed dry-run JSON; mandatory with --output")
    args = parser.parse_args()
    if bool(args.output) != bool(args.expected_report):
        parser.error("--output and --expected-report must be supplied together")
    report, converted = plan(args.source)
    report["currentCodec"] = validate_current(converted, args.classpath, args.java)
    if inventory(plain_path(args.source)) != report["sourceSha256"]:
        raise ValueError("Source changed during current-codec validation")
    report["dryRun"] = True
    if args.output:
        expected = DECODER.decode(args.expected_report.read_text(encoding="utf-8"))
        report = write_candidate(args.source, args.output, report, converted, expected)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
