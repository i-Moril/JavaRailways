#!/usr/bin/env python3
"""Remote-local-only audit; outputs schema metadata/counts, never document contents.

Run on the deployment host with both exact embedded WebUI JARs. This is a
read-only audit of source stores; Java helpers compile in temporary directories.
Any source-valid -> candidate-invalid document or any nonempty compiled
schedule store needs separate review before deployment; no silent migration.
"""
from __future__ import annotations
import argparse
import collections
import json
from pathlib import Path
from migrate_equipment_snapshots import scan
from migrate_duty_timeline import scan as scan_documents
from remove_program_library_archived import decode
from unify_program_string_types import JavaBridge


def accepted(bridge, value):
    try:
        bridge('validate-document', value)
        return True
    except ValueError:
        return False


def audit(documents, worlds, timetables, javascript, source, candidate):
    comparison = collections.Counter()
    fields = collections.Counter()
    leads = collections.Counter()
    for raw in scan_documents(documents).values():
        value = decode(raw)
        if not isinstance(value, dict):
            raise ValueError('author document must be an object')
        fields.update(value.keys())
        comparison[f'source_{accepted(source, value)}_candidate_{accepted(candidate, value)}'] += 1
        for station in value.get('stationPrograms', []):
            leads['numeric'] += 'preparationLeadTicks' in station
            leads['expression'] += 'preparationLead' in station
    runtime_count = 0
    compiled_count = 0
    for name, raw in scan(worlds).items():
        if not name.endswith(('-runtime.json', '-runtime.json.bak')):
            continue
        outer = decode(raw)
        if outer.get('formatVersion') != 2:
            raise ValueError('unexpected runtime format')
        inner = decode(outer['simpleAtoSnapshotJson'].encode())
        schedules = inner['timetables']
        if not isinstance(schedules, list):
            raise ValueError('compiled timetable collection must be a list')
        compiled_count += len(schedules)
        runtime_count += 1
    standalone = len(scan(timetables))
    js = len(scan(javascript))
    source.verify(); candidate.verify()
    return dict(documentValidation=dict(comparison), documentTopLevelFields=dict(fields),
                authorPreparationFields=dict(leads), runtimeFiles=runtime_count,
                compiledTimetables=compiled_count, standaloneTimetableFiles=standalone,
                generatedJavascriptFiles=js,
                zeroLeadMigrationTargets=(leads['numeric'] == 0 and compiled_count == 0 and standalone == 0 and js == 0),
                validatorRegressions=comparison['source_True_candidate_False'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('documents', 'worlds', 'timetables', 'javascript', 'source-webui-jar', 'candidate-webui-jar', 'java-dir'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    source = JavaBridge(args.source_webui_jar, args.java_dir)
    candidate = JavaBridge(args.candidate_webui_jar, args.java_dir)
    try:
        result = audit(args.documents, args.worlds, args.timetables, args.javascript, source, candidate)
        print(json.dumps(result, sort_keys=True))
        if result['validatorRegressions'] or not result['zeroLeadMigrationTargets']:
            raise SystemExit('review required: nonempty migration boundary or validator regression')
    finally:
        if source.temporary is not None:
            source.temporary.cleanup()
        if candidate.temporary is not None:
            candidate.temporary.cleanup()


if __name__ == '__main__':
    main()
