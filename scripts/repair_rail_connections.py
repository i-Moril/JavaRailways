#!/usr/bin/env python3
"""Repair MineRails world JSON rail endpoint connections.

This is a one-shot/offline repair tool for worlds that were saved after RailWand
created/selected the wrong active/default path at coincident rail endpoints.

It rebuilds each rail's nextRailAtA/nextRailAtB from endpoint geometry:
  * endpoints must coincide within --tolerance;
  * both transition directions must be no sharper than --max-angle;
  * activeTrackAtA/B is set to the smoothest candidate at that endpoint.

Run while the server/plugin is stopped, otherwise the server may overwrite the file
on world/plugin unload.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

DEFAULT_WORLD = Path("BukkitPlugin/run/plugins/MineRails/worlds/92b47942-f5b4-4230-9784-5b4c57d45fb9.json")
DEFAULT_TOLERANCE = 5e-3
DEFAULT_MAX_ANGLE_DEGREES = 30.0
DEFAULT_LOOKAHEAD_METERS = 2.0
EPSILON = 1e-9

Vec = tuple[float, float, float]


def vec(data: dict[str, Any]) -> Vec:
    return (float(data["x"]), float(data["y"]), float(data["z"]))


def neg(v: Vec) -> Vec:
    return (-v[0], -v[1], -v[2])


def dot(a: Vec, b: Vec) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def dist(a: Vec, b: Vec) -> float:
    return math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2)


def normalize(v: Vec) -> Vec | None:
    length = math.sqrt(dot(v, v))
    if length <= EPSILON:
        return None
    return (v[0] / length, v[1] / length, v[2] / length)


def angle_degrees(a: Vec, b: Vec) -> float | None:
    na = normalize(a)
    nb = normalize(b)
    if na is None or nb is None:
        return None
    value = max(-1.0, min(1.0, dot(na, nb)))
    return math.degrees(math.acos(value))


@dataclass(frozen=True)
class Endpoint:
    rail_index: int
    rail_id: str
    end: str
    position: Vec
    tangent_into_joint: Vec
    tangent_away_from_joint: Vec
    chord_away_from_joint: Vec


@dataclass
class Candidate:
    rail_id: str
    angle: float


@dataclass
class EndpointPlan:
    candidates: list[Candidate] = field(default_factory=list)

    def sorted_candidates(self) -> list[Candidate]:
        # De-duplicate by rail id and keep the smoothest angle if the same rail
        # somehow appears more than once for this endpoint.
        best_by_id: dict[str, float] = {}
        for candidate in self.candidates:
            best_by_id[candidate.rail_id] = min(candidate.angle, best_by_id.get(candidate.rail_id, math.inf))
        return [Candidate(rail_id, angle) for rail_id, angle in sorted(best_by_id.items(), key=lambda item: (item[1], item[0]))]


def point_at(points: list[dict[str, Any]], arc_length: float) -> Vec:
    if arc_length <= float(points[0].get("arcLength", 0.0)):
        return vec(points[0]["position"])
    for lo, hi in zip(points, points[1:]):
        lo_arc = float(lo.get("arcLength", 0.0))
        hi_arc = float(hi.get("arcLength", lo_arc))
        if arc_length <= hi_arc:
            if hi_arc - lo_arc <= EPSILON:
                return vec(lo["position"])
            ratio = (arc_length - lo_arc) / (hi_arc - lo_arc)
            a = vec(lo["position"])
            b = vec(hi["position"])
            return (
                a[0] + (b[0] - a[0]) * ratio,
                a[1] + (b[1] - a[1]) * ratio,
                a[2] + (b[2] - a[2]) * ratio,
            )
    return vec(points[-1]["position"])


def subtract(a: Vec, b: Vec) -> Vec:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def endpoints_for(rails: list[dict[str, Any]], lookahead_meters: float) -> list[Endpoint]:
    endpoints: list[Endpoint] = []
    for index, rail in enumerate(rails):
        points = rail.get("curveAB", {}).get("pointList") or []
        if len(points) < 2:
            continue
        rail_id = str(rail.get("id"))
        start = points[0]
        end = points[-1]
        total_arc = float(end.get("arcLength", 0.0))
        probe = min(max(0.0, lookahead_meters), total_arc)
        position_a = vec(start["position"])
        position_b = vec(end["position"])
        tangent_a = vec(start["tangent"])
        tangent_b = vec(end["tangent"])
        endpoints.append(Endpoint(
            rail_index=index,
            rail_id=rail_id,
            end="A",
            position=position_a,
            tangent_into_joint=neg(tangent_a),
            tangent_away_from_joint=tangent_a,
            chord_away_from_joint=subtract(point_at(points, probe), position_a),
        ))
        endpoints.append(Endpoint(
            rail_index=index,
            rail_id=rail_id,
            end="B",
            position=position_b,
            tangent_into_joint=tangent_b,
            tangent_away_from_joint=neg(tangent_b),
            chord_away_from_joint=subtract(point_at(points, total_arc - probe), position_b),
        ))
    return endpoints


def repair(data: dict[str, Any], tolerance: float, max_angle_degrees: float, lookahead_meters: float) -> tuple[int, int, int]:
    rails = data.get("rails") or []
    endpoints = endpoints_for(rails, lookahead_meters)
    plans: dict[tuple[int, str], EndpointPlan] = {
        (endpoint.rail_index, endpoint.end): EndpointPlan() for endpoint in endpoints
    }
    accepted_pairs = 0
    rejected_by_angle = 0

    for left_index, left in enumerate(endpoints):
        for right in endpoints[left_index + 1:]:
            if left.rail_index == right.rail_index:
                continue
            if dist(left.position, right.position) > tolerance:
                continue
            left_to_right = angle_degrees(left.tangent_into_joint, right.tangent_away_from_joint)
            right_to_left = angle_degrees(right.tangent_into_joint, left.tangent_away_from_joint)
            if left_to_right is None or right_to_left is None:
                rejected_by_angle += 1
                continue
            if left_to_right > max_angle_degrees + EPSILON or right_to_left > max_angle_degrees + EPSILON:
                rejected_by_angle += 1
                continue
            left_chord = angle_degrees(left.tangent_into_joint, right.chord_away_from_joint)
            right_chord = angle_degrees(right.tangent_into_joint, left.chord_away_from_joint)
            left_score = max(left_to_right, left_chord if left_chord is not None else left_to_right)
            right_score = max(right_to_left, right_chord if right_chord is not None else right_to_left)
            plans[(left.rail_index, left.end)].candidates.append(Candidate(right.rail_id, left_score))
            plans[(right.rail_index, right.end)].candidates.append(Candidate(left.rail_id, right_score))
            accepted_pairs += 1

    changed = 0
    for endpoint in endpoints:
        rail = rails[endpoint.rail_index]
        field = f"nextRailAt{endpoint.end}"
        active_field = f"activeTrackAt{endpoint.end}"
        candidates = plans[(endpoint.rail_index, endpoint.end)].sorted_candidates()
        next_ids = [candidate.rail_id for candidate in candidates]
        active_id = candidates[0].rail_id if candidates else None
        if rail.get(field) != next_ids or rail.get(active_field) != active_id:
            changed += 1
        rail[field] = next_ids
        rail[active_field] = active_id

    return changed, accepted_pairs, rejected_by_angle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", nargs="?", type=Path, default=DEFAULT_WORLD, help="world JSON to repair")
    parser.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE, help="endpoint coincidence tolerance in meters")
    parser.add_argument("--max-angle", type=float, default=DEFAULT_MAX_ANGLE_DEGREES, help="maximum tangent transition angle in degrees")
    parser.add_argument("--lookahead", type=float, default=DEFAULT_LOOKAHEAD_METERS, help="meters used to break same-tangent active-path ties by curve chord")
    parser.add_argument("--apply", action="store_true", help="write changes; without this, only prints a dry-run summary")
    parser.add_argument("--no-backup", action="store_true", help="do not create a .bak timestamp backup when applying")
    args = parser.parse_args()

    path = args.world
    before_text = path.read_text()
    data = json.loads(before_text)
    changed_endpoints, accepted_pairs, rejected_by_angle = repair(data, args.tolerance, args.max_angle, args.lookahead)
    after_text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    changed_file = before_text != after_text

    mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"[{mode}] {path}")
    print(f"rails={len(data.get('rails') or [])} changed_endpoints={changed_endpoints} accepted_pairs={accepted_pairs} rejected_by_angle={rejected_by_angle}")

    if not args.apply:
        if changed_file:
            print("changes pending; rerun with --apply while the server is stopped")
        else:
            print("no file changes needed")
        return 0

    if changed_file:
        if not args.no_backup:
            backup = path.with_suffix(path.suffix + f".bak-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
            shutil.copy2(path, backup)
            print(f"backup={backup}")
        path.write_text(after_text)
        print("written")
    else:
        print("no file changes needed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
