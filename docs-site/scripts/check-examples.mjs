import {execFileSync} from 'node:child_process';
import {readdirSync, statSync} from 'node:fs';
import {resolve} from 'node:path';

const repositoryRoot = resolve(import.meta.dirname, '../..');
const roots = [resolve(repositoryRoot, 'examples'), resolve(repositoryRoot, 'models')];

function walk(directory) {
  try {
    return readdirSync(directory).flatMap((entry) => {
      const path = resolve(directory, entry);
      return statSync(path).isDirectory() ? walk(path) : [path];
    });
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
}

const scripts = roots.flatMap(walk).filter((path) => path.endsWith('.js'));
for (const script of scripts) {
  execFileSync(process.execPath, ['--check', script], {stdio: 'inherit'});
}
console.log(`Checked ${scripts.length} JavaScript example(s).`);
