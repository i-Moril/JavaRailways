#!/usr/bin/env python3
"""One-time development train YAML conversion. Dry-run by default; PyYAML required."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

import yaml


class StrictLoader(yaml.SafeLoader):
    pass


def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if not isinstance(key, str):
            raise ValueError(f"mapping key must be a string at line {key_node.start_mark.line + 1}")
        if key in result:
            raise ValueError(f"duplicate key {key!r} at line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def convert(raw):
    text = raw.decode('utf-8')
    original = yaml.load(text, Loader=StrictLoader)
    if not isinstance(original, dict):
        return raw
    expected = copy.deepcopy(original)
    node = yaml.compose(text, Loader=StrictLoader)
    lines = text.splitlines(keepends=True)
    edits = []

    def fields(n):
        return {k.value: (k, v) for k, v in n.value}

    def walk_part(data, ast, inline):
        if not isinstance(data, dict) or 'type' not in data:
            return
        props = fields(ast)
        if inline and 'config-version' in data:
            version = data.pop('config-version')
            if type(version) is not int or version != 1:
                raise ValueError('inline config-version must be exactly 1 before removal')
            k, v = props['config-version']
            line = lines[k.start_mark.line]
            # A first mapping key may also carry the sequence dash; keep that dash.
            prefix = line[:k.start_mark.column]
            if line[v.end_mark.column:].strip() and not line[v.end_mark.column:].lstrip().startswith('#'):
                raise ValueError('flow-style inline version requires manual conversion')
            if not re.fullmatch(r'\s*(?:-\s*)?', prefix):
                raise ValueError('flow-style inline part requires manual conversion')
            replacement = prefix.rstrip() + '\n' if '-' in prefix else ''
            edits.append((sum(map(len, lines[:k.start_mark.line])), sum(map(len, lines[:k.start_mark.line + 1])), replacement))
        animations = data.get('animations')
        if isinstance(animations, list):
            animation_node = props['animations'][1]
            mapped = {}
            for item in animations:
                if not isinstance(item, dict) or not isinstance(item.get('name'), str) or not item['name'].strip():
                    raise ValueError('each old animation requires a nonblank string name')
                name = item['name']
                if name in mapped:
                    raise ValueError(f'duplicate animation name {name!r}')
                mapped[name] = {k: v for k, v in item.items() if k != 'name'}
            data['animations'] = mapped
            # Preserve comments and formatting when each animation starts with '- name:'.
            simple = bool(animations) and all(list(item)[0] == 'name' for item in animations)
            if simple:
                for item_node in animation_node.value:
                    k, v = fields(item_node)['name']
                    line = lines[k.start_mark.line]
                    prefix = line[:k.start_mark.column]
                    if not re.fullmatch(r'\s*-\s*', prefix) or v.end_mark.line != k.start_mark.line:
                        simple = False
                        break
            if simple:
                for item_node in animation_node.value:
                    k, v = fields(item_node)['name']
                    start = sum(map(len, lines[:k.start_mark.line]))
                    prefix = lines[k.start_mark.line][:k.start_mark.column]
                    indent = prefix.index('-')
                    scalar = text[v.start_mark.index:v.end_mark.index]
                    # Empty definitions need an explicit mapping value.
                    empty = len(item_node.value) == 1
                    edits.append((start, v.end_mark.index, ' ' * indent + scalar + ':' + (' {}' if empty else '')))
            else:
                # Less common flow/non-name-first input is normalized only within this field.
                k, _ = props['animations']
                start = sum(map(len, lines[:k.start_mark.line + 1]))
                end = animation_node.end_mark.index
                if animation_node.flow_style or animation_node.start_mark.line == k.start_mark.line:
                    start = animation_node.start_mark.index
                    replacement = yaml.safe_dump(mapped, allow_unicode=True, sort_keys=False, default_flow_style=True).strip()
                else:
                    indent = k.start_mark.column + 2
                    dumped = yaml.safe_dump(mapped, allow_unicode=True, sort_keys=False)
                    replacement = ''.join(' ' * indent + line if line.strip() else line for line in dumped.splitlines(keepends=True))
                edits.append((start, end, replacement))
        elif animations is not None and not isinstance(animations, dict):
            raise ValueError('animations must be a list to migrate or a current map')
        for key in ('parts',):
            walk_list(data.get(key), props.get(key, (None, None))[1])

    def walk_list(data, ast):
        if data is None:
            return
        if not isinstance(data, list) or not isinstance(ast, yaml.SequenceNode):
            raise ValueError('part installation tree must be a list')
        for item, item_node in zip(data, ast.value):
            if isinstance(item, dict) and 'type' in item:
                walk_part(item, item_node, True)

    if 'type' in expected and 'name' in expected:
        walk_part(expected, node, False)
    else:
        props = fields(node)
        for key in ('body-parts', 'wheel-parts', 'parts'):
            walk_list(expected.get(key), props.get(key, (None, None))[1])
    for start, end, replacement in sorted(edits, reverse=True):
        text = text[:start] + replacement + text[end:]
    actual = yaml.load(text, Loader=StrictLoader)
    if actual != expected:
        raise ValueError('converted YAML differs from the exact planned semantic transformation')
    return text.encode('utf-8')


def convert_zip(raw):
    source = io.BytesIO(raw)
    output = io.BytesIO()
    changed = False
    with zipfile.ZipFile(source) as old, zipfile.ZipFile(output, 'w') as new:
        if len(old.namelist()) != len(set(old.namelist())):
            raise ValueError('duplicate ZIP entry')
        new.comment = old.comment
        for info in old.infolist():
            before = old.read(info)
            after = convert(before) if info.filename.endswith(('.yml', '.yaml')) else before
            changed |= after != before
            new.writestr(info, after)
    return output.getvalue() if changed else raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path, help='new directory outside root; required with --apply')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.apply:
        if not args.backup or args.backup.exists() or args.backup.resolve().is_relative_to(root):
            parser.error('--apply requires a new backup directory outside the input root')
    plan = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'symlink rejected: {path}')
        if not path.is_file() or path.suffix not in ('.yml', '.yaml', '.zip'):
            continue
        before = path.read_bytes()
        after = convert_zip(before) if path.suffix == '.zip' else convert(before)
        if after != before:
            plan.append((path, before, after))
    report = {'root': str(root), 'applied': args.apply, 'files': [
        {'path': str(path.relative_to(root)), 'before': digest(before), 'after': digest(after)}
        for path, before, after in plan]}
    if args.apply:
        args.backup.mkdir(parents=True)
        # Back up every original before the first replacement; immutable manifest aids rollback.
        for path, before, _ in plan:
            target = args.backup / path.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(before)
        (args.backup / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
        for path, before, after in plan:
            if path.read_bytes() != before:
                raise ValueError(f'source changed after planning: {path}')
        for path, _, after in plan:
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
                stage = Path(stream.name)
                stream.write(after)
            try:
                shutil.copymode(path, stage)
                stage.replace(path)
            finally:
                stage.unlink(missing_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f"{'Applied' if args.apply else 'Dry-run'}: {len(plan)} changed files; report {args.report}")


if __name__ == '__main__':
    main()
