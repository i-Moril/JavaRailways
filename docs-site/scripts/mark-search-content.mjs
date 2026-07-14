import {readdir, readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const buildRoot = resolve(import.meta.dirname, '..', 'build');
const files = await readdir(buildRoot, {recursive: true, withFileTypes: true});
let marked = 0;

for (const entry of files) {
  if (!entry.isFile() || !entry.name.endsWith('.html')) continue;
  const file = resolve(entry.parentPath, entry.name);
  const source = await readFile(file, 'utf8');
  let updated = source;

  if (source.includes('id=__docusaurus')) {
    updated = source.replace(/<main(?=[ >])/, '<main data-pagefind-body');
  } else {
    updated = source.replace(/<body(?=[ >])/, '<body data-pagefind-body');
  }

  if (updated !== source) {
    await writeFile(file, updated);
    marked += 1;
  }
}

if (marked === 0) {
  throw new Error(`No searchable HTML roots found under ${buildRoot}`);
}
console.log(`Marked the searchable root in ${marked} HTML file(s).`);
