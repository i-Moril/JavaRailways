"""Compile receipts for the isolated production actuator experiment."""
import hashlib
import json
import os
from pathlib import Path

SOURCE_ROOTS = ('Core/src/main', 'Core/src/testFixtures', 'Basic/src/main', 'Basic/src/test', 'Basic/src/testFixtures',
                'ai_tasks/experiments/production-emu-performance')
BUILD_FILES = ('build.gradle', 'settings.gradle', 'gradle.properties', 'Core/build.gradle.kts',
               'Basic/build.gradle.kts', 'gradle/libs.versions.toml')
OUTPUTS = {
    ':Core:compileKotlin': 'Core/build/classes/kotlin/main',
    ':Core:compileTestFixturesKotlin': 'Core/build/classes/kotlin/testFixtures',
    ':Basic:compileKotlin': 'Basic/build/classes/kotlin/main',
    ':Basic:compileTestFixturesKotlin': 'Basic/build/classes/kotlin/testFixtures',
    ':Basic:compileTestKotlin': 'Basic/build/classes/kotlin/test',
}
RECEIPT = Path('/tmp/jr-production-emu-compile/receipt.json')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_manifest(repo):
    paths = {p for directory in SOURCE_ROOTS for p in (repo / directory).rglob('*')
             if p.is_file() and p.suffix in ('.kt', '.java', '.py', '.gradle')}
    paths.update(repo / name for name in BUILD_FILES if (repo / name).is_file())
    return {str(p.relative_to(repo)): sha(p) for p in sorted(paths)}


def class_manifest(repo, directory):
    return {str(p.relative_to(repo)): sha(p) for p in sorted((repo / directory).rglob('*.class'))}


def runtime_manifest(classpath):
    paths = set()
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_file():
            paths.add(path)
        elif path.is_dir():
            paths.update(p for p in path.rglob('*') if p.is_file())
        # Gradle includes absent Java/resources output directories in Kotlin-only modules.
    return {str(p.resolve()): sha(p) for p in sorted(paths)}


def verify_receipt(repo, receipt, classpath):
    if receipt['repo'] != str(repo.resolve()):
        raise ValueError('Compilation receipt belongs to another checkout')
    if receipt['source_sha256'] != source_manifest(repo):
        raise ValueError('Sources differ from compilation receipt; recompile the benchmark')
    if receipt['classpath'] != classpath:
        raise ValueError('Classpath differs from compilation receipt')
    if receipt['runtime_sha256'] != runtime_manifest(classpath):
        raise ValueError('Runtime classpath contents differ from compilation receipt')
    if set(receipt['tasks']) != set(OUTPUTS):
        raise ValueError('Incomplete compilation receipt')
    for task, directory in OUTPUTS.items():
        recorded = receipt['tasks'][task]
        if not recorded or recorded != class_manifest(repo, directory):
            raise ValueError('Compiled classes differ from compilation receipt: ' + task)
    return receipt


if __name__ == '__main__':
    import sys
    repo = Path(sys.argv[1]).resolve()
    action = sys.argv[2]
    if action == 'sources':
        print(json.dumps(source_manifest(repo), sort_keys=True))
    elif action == 'runtime':
        print(json.dumps(runtime_manifest(sys.argv[3]), sort_keys=True))
    elif action == 'classes':
        print(json.dumps(class_manifest(repo, sys.argv[3]), sort_keys=True))
    else:
        raise ValueError(action)
