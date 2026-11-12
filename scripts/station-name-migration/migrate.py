#!/usr/bin/env python3
"""Convert one extracted JavaRailways infrastructure snapshot into a new file."""

import argparse
import gzip
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "waypoint-identity-migration"))
from migrate import DECODER, members, skip_space  # noqa: E402


def by_key(text, offset):
    return {entry[0]: entry for entry in members(text, offset)}


def has_station_name(name):
    if isinstance(name, str):
        return bool(name.strip())
    return isinstance(name, dict) and any(
        isinstance(name.get(language), str) and name[language].strip() for language in ("zh", "en")
    )


def convert(raw):
    compressed = raw.startswith(b"\x1f\x8b")
    text = (gzip.decompress(raw) if compressed else raw).decode("utf-8")
    outer = DECODER.decode(text)
    nested = outer["simpleAtoSnapshotJson"]
    snapshot = DECODER.decode(nested)
    if outer["formatVersion"] != 2 or snapshot["formatVersion"] != 1:
        raise ValueError("Expected infrastructure format 2 with SimpleATO infrastructure format 1")

    metadata = snapshot.get("stationMetadata", {})
    old_names = {}
    edits = []
    fields = by_key(nested, skip_space(nested, 0))
    offset = skip_space(nested, fields["destinations"][2] + 1)
    for destination in snapshot["destinations"]:
        entries = members(nested, offset)
        for i, (key, begin, _, end) in enumerate(entries):
            if key != "stationDisplayName":
                continue
            if i + 1 < len(entries):
                end = entries[i + 1][1]
            elif i:
                begin = entries[i - 1][3]
            edits.append((begin, end, ""))
            station = destination.get("stationId")
            old_name = destination[key]
            if destination["roleType"] == "STATION_BERTH" and old_name and old_name != station:
                old_names.setdefault(station, set()).add(old_name)
        _, offset = DECODER.raw_decode(nested, offset)
        offset = skip_space(nested, offset)
        if nested[offset] == ",":
            offset = skip_space(nested, offset + 1)

    if not edits:
        return raw, 0, 0
    removed = len(edits)
    promoted = {}
    for station, names in old_names.items():
        if has_station_name(metadata.get(station, {}).get("name")):
            continue
        if len(names) != 1:
            raise ValueError(f"Conflicting legacy names for {station!r}: {sorted(names)!r}; set metadata.name first")
        promoted[station] = next(iter(names))

    if promoted and "stationMetadata" not in fields:
        position = max(field[3] for field in fields.values())
        addition = {station: {"name": name} for station, name in promoted.items()}
        edits.append((position, position, ', "stationMetadata": ' + json.dumps(addition, ensure_ascii=False)))
    elif promoted:
        metadata_offset = fields["stationMetadata"][2]
        records = by_key(nested, metadata_offset)
        new_records = {}
        for station, name in promoted.items():
            if station not in records:
                new_records[station] = {"name": name}
                continue
            object_offset = records[station][2]
            station_fields = by_key(nested, object_offset)
            serialized_name = json.dumps(name, ensure_ascii=False)
            if "name" in station_fields:
                _, _, begin, end = station_fields["name"]
                edits.append((begin, end, serialized_name))
            else:
                position = object_offset + 1
                separator = ", " if station_fields else ""
                edits.append((position, position, '"name": ' + serialized_name + separator))
        if new_records:
            position = metadata_offset + 1
            separator = ", " if records else ""
            serialized = json.dumps(new_records, ensure_ascii=False)
            edits.append((position, position, serialized[1:-1] + separator))

    for begin, end, replacement in sorted(edits, reverse=True):
        nested = nested[:begin] + replacement + nested[end:]
    converted = DECODER.decode(nested)
    for destination in snapshot["destinations"]:
        destination.pop("stationDisplayName", None)
    for station, name in promoted.items():
        snapshot.setdefault("stationMetadata", {}).setdefault(station, {})["name"] = name
    if converted != snapshot:
        raise ValueError("Conversion changed unrelated SimpleATO data")
    _, _, begin, end = by_key(text, skip_space(text, 0))["simpleAtoSnapshotJson"]
    result = (text[:begin] + json.dumps(nested, ensure_ascii=False) + text[end:]).encode("utf-8")
    return (gzip.compress(result, mtime=0) if compressed else result), removed, len(promoted)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, help="New candidate file. Omit to report changes only.")
    args = parser.parse_args()
    candidate, removed, promoted = convert(args.source.read_bytes())
    if args.output:
        with args.output.open("xb") as stream:
            stream.write(candidate)
    print(f"{args.source.name}: {removed} snapshot fields removed, {promoted} names moved to station metadata")


if __name__ == "__main__":
    main()
