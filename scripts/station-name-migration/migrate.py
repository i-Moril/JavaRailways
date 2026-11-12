#!/usr/bin/env python3
"""Move station and line names to metadata in an extracted infrastructure snapshot."""

import argparse
import gzip
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "waypoint-identity-migration"))
from migrate import DECODER, members, skip_space  # noqa: E402


def by_key(text, offset):
    return {entry[0]: entry for entry in members(text, offset)}


def has_name(name):
    if isinstance(name, str):
        return bool(name.strip())
    return isinstance(name, dict) and any(
        isinstance(name.get(language), str) and name[language].strip() for language in ("zh", "en")
    )



def array_entries(text, array_start, records):
    offset = skip_space(text, array_start + 1)
    for record in records:
        yield record, members(text, offset)
        _, offset = DECODER.raw_decode(text, offset)
        offset = skip_space(text, offset)
        if text[offset] == ",":
            offset = skip_space(text, offset + 1)


def delete_member(entries, index):
    _, begin, _, end = entries[index]
    if index + 1 < len(entries):
        end = entries[index + 1][1]
    elif index:
        begin = entries[index - 1][3]
    return begin, end, ""


def set_name(text, offset, name):
    fields = by_key(text, offset)
    serialized = json.dumps(name, ensure_ascii=False)
    if "name" in fields:
        _, _, begin, end = fields["name"]
        return begin, end, serialized
    position = offset + 1
    separator = ", " if fields else ""
    return position, position, '"name": ' + serialized + separator


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
    destinations = snapshot["destinations"]
    for destination, entries in array_entries(nested, fields["destinations"][2], destinations):
        for index, (key, _, _, _) in enumerate(entries):
            if key != "stationDisplayName":
                continue
            edits.append(delete_member(entries, index))
            station = destination.get("stationId")
            old_name = destination.pop(key)
            if destination["roleType"] == "STATION_BERTH" and old_name and old_name != station:
                old_names.setdefault(station, set()).add(old_name)

    removed = len(edits)
    line_names_moved = 0
    for line, entries in array_entries(nested, fields["lines"][2], snapshot["lines"]):
        line_fields = {entry[0]: entry for entry in entries}
        for index, (key, _, _, _) in enumerate(entries):
            if key != "displayName":
                continue
            edits.append(delete_member(entries, index))
            removed += 1
            old_name = line.pop(key)
            line_metadata = line.setdefault("metadata", {})
            if has_name(line_metadata.get("name")):
                break
            if "metadata" in line_fields:
                edits.append(set_name(nested, line_fields["metadata"][2], old_name))
            else:
                # Insert before the closing brace, after the deletion range.
                position = skip_space(nested, entries[-1][3])
                addition = json.dumps({"name": old_name}, ensure_ascii=False)
                edits.append((position, position, ', "metadata": ' + addition))
            line_metadata["name"] = old_name
            line_names_moved += 1
            break
    if not edits:
        return raw, 0, 0
    promoted = {}
    for station, names in old_names.items():
        if has_name(metadata.get(station, {}).get("name")):
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
            edits.append(set_name(nested, records[station][2], name))
        if new_records:
            position = metadata_offset + 1
            separator = ", " if records else ""
            serialized = json.dumps(new_records, ensure_ascii=False)
            edits.append((position, position, serialized[1:-1] + separator))

    for begin, end, replacement in sorted(edits, reverse=True):
        nested = nested[:begin] + replacement + nested[end:]
    converted = DECODER.decode(nested)
    for station, name in promoted.items():
        snapshot.setdefault("stationMetadata", {}).setdefault(station, {})["name"] = name
    if converted != snapshot:
        raise ValueError("Conversion changed unrelated SimpleATO data")
    _, _, begin, end = by_key(text, skip_space(text, 0))["simpleAtoSnapshotJson"]
    result = (text[:begin] + json.dumps(nested, ensure_ascii=False) + text[end:]).encode("utf-8")
    return (gzip.compress(result, mtime=0) if compressed else result), removed, len(promoted) + line_names_moved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, help="New candidate file. Omit to report changes only.")
    args = parser.parse_args()
    candidate, removed, promoted = convert(args.source.read_bytes())
    if args.output:
        with args.output.open("xb") as stream:
            stream.write(candidate)
    print(f"{args.source.name}: {removed} snapshot fields removed, {promoted} names moved to metadata")


if __name__ == "__main__":
    main()
