#!/usr/bin/env python3
"""Offline editor-document conversion. Dry-run by default; never overwrite source or output."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

PLANNED = {f"javarailways:plan_{field}" for field in ("run_type", "line_direction", "station_id", "run_id", "line_id", "call_status", "duty_id")}


def walk(steps):
    for step in steps:
        yield step
        if step.get("type") == "BRANCH":
            yield from walk(step["thenSteps"])
            yield from walk(step["elseSteps"])
        if step.get("type") == "ASYNC":
            yield from walk(step["steps"])


def convert(document):
    if document.get("formatVersion") != 1:
        raise ValueError("Expected development editor document formatVersion 1")
    result = copy.deepcopy(document)
    changes, blocked = [], []
    groups = {}
    for run in result.get("runs", []):
        for call in run.get("calls", []):
            if call.get("status") == "PASS":
                if "arrivalProgram" in call:
                    blocked.append(f"{run['id']}/{call['id']}: passing call has an arrival program")
                continue
            if call.get("status") == "ORIGIN":
                if "arrivalProgram" in call:
                    blocked.append(f"{run['id']}/{call['id']}: origin has an old arrival override")
                continue
            groups.setdefault(call["stationId"], []).append((run["id"], call))
    records = {record["stationId"]: record for record in result.get("stationPrograms", [])}
    for station_id, calls in groups.items():
        custom = [(run_id, call) for run_id, call in calls if "arrivalProgram" in call]
        if not custom:
            continue
        variants = {json.dumps(call.get("arrivalProgram"), sort_keys=True) for _, call in calls}
        if len(variants) != 1 or station_id in records:
            blocked.append(f"{station_id}: conflicting per-call or existing shared definitions; choose one shared program manually")
            continue
        definition = custom[0][1]["arrivalProgram"]
        if definition.get("parameters"):
            blocked.append(f"{station_id}: unexpected declared parameters")
            continue
        ids = {step["id"] for step in walk(definition["steps"])}
        def fresh(stem):
            name = stem
            while name in ids:
                name += "-copy"
            ids.add(name)
            return name
        arrival = {"parameters": [], "steps": [{"id": fresh("migration-open"), "type": "OPEN_DOORS"}, *copy.deepcopy(definition["steps"]), {"id": fresh("migration-end"), "type": "END"}]}
        records[station_id] = {"stationId": station_id, "preparationLeadTicks": 20, "arrival": arrival,
                               "departurePreparation": {"parameters": [], "steps": [{"id": "close", "type": "CLOSE_DOORS"}, {"id": "end", "type": "END"}]}}
        for run_id, call in calls:
            del call["arrivalProgram"]
            changes.append(f"{run_id}/{call['id']}: moved arrival program to shared station {station_id}")
    result["stationPrograms"] = list(records.values())
    definitions = [entry[phase] for entry in records.values() for phase in ("arrival", "departurePreparation")]
    definitions += [entry["definition"] for entry in result.get("boundPrograms", [])]
    definitions += [element["definition"] for duty in result.get("duties", []) for element in duty.get("elements", []) if element.get("type") == "INLINE"]
    for definition in definitions:
        for step in walk(definition["steps"]):
            if step["type"] == "BRANCH" and step.get("typeId") not in PLANNED:
                blocked.append(f"step {step['id']}: runtime condition must be rewritten using planned context")
            if step["type"] == "SELECT_STOP":
                blocked.append(f"step {step['id']}: dynamic stop selection belongs to another plugin")
    return result, changes, blocked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, help="Write candidate and audit to new paths; source remains the recovery copy")
    args = parser.parse_args()
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    original = args.source.read_bytes()
    result, changes, blocked = convert(json.loads(original, object_pairs_hook=unique))
    candidate = (json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()
    audit = {"source": str(args.source.resolve()), "sourceSha256": hashlib.sha256(original).hexdigest(),
             "candidateSha256": hashlib.sha256(candidate).hexdigest(), "changes": changes, "blocked": blocked, "written": False}
    if args.output and not blocked:
        audit_path = args.output.with_name(args.output.name + ".audit.json")
        if args.output.exists() or audit_path.exists() or args.output.resolve() == args.source.resolve():
            parser.error("Output and audit must be new paths, distinct from source")
        with args.output.open("xb") as stream:
            stream.write(candidate)
        audit["written"] = True
        with audit_path.open("x") as stream:
            json.dump(audit, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(json.dumps(audit, ensure_ascii=False, indent=2))
    return 2 if blocked else 0


if __name__ == "__main__":
    raise SystemExit(main())
