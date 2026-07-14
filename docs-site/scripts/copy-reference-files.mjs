import {cpSync, mkdirSync, rmSync} from 'node:fs';
import {resolve} from 'node:path';

const siteDirectory = resolve(import.meta.dirname, '..');
const copies = [
  ['../schemas', 'static/schemas'],
  ['../types', 'static/types'],
  ['../build/dokka/html', 'static/api/current'],
];

for (const [sourcePath, targetPath] of copies) {
  const source = resolve(siteDirectory, sourcePath);
  const target = resolve(siteDirectory, targetPath);
  rmSync(target, {recursive: true, force: true});
  mkdirSync(target, {recursive: true});
  cpSync(source, target, {recursive: true});
  console.log(`Copied ${source} to ${target}`);
}
