import Ajv2020 from 'ajv/dist/2020.js';
import {readFileSync, readdirSync, statSync} from 'node:fs';
import {basename, extname, resolve} from 'node:path';
import YAML from 'yaml';

const repositoryRoot = resolve(import.meta.dirname, '../..');
const schemaDirectory = resolve(repositoryRoot, 'schemas/train-model');
const modelsDirectory = resolve(repositoryRoot, 'models');

function readJson(name) {
  return JSON.parse(readFileSync(resolve(schemaDirectory, name), 'utf8'));
}

function readYaml(path) {
  const source = readFileSync(path, 'utf8');
  if (/^\s*scene:\s*(?:on|off|yes|no)\s*(?:#.*)?$/im.test(source)) {
    throw new Error(`${path}: scene names on/off/yes/no must be quoted for SnakeYAML compatibility`);
  }
  const document = YAML.parseDocument(source, {
    version: '1.2',
    strict: true,
    uniqueKeys: true,
  });
  if (document.errors.length > 0) {
    throw new Error(`${path}: ${document.errors.map((error) => error.message).join('; ')}`);
  }
  return document.toJS({maxAliasCount: 100});
}

const commonSchema = readJson('model-common.schema.json');
const schemas = {
  manifest: readJson('manifest-v2.schema.json'),
  part: readJson('part-v1.schema.json'),
  car: readJson('car-v1.schema.json'),
  control: readJson('control-v1.schema.json'),
  actions: readJson('actions-v1.schema.json'),
};

const ajv = new Ajv2020({allErrors: true, strict: true});
ajv.addSchema(commonSchema);
const validators = Object.fromEntries(
  Object.entries(schemas).map(([name, schema]) => [name, ajv.compile(schema)]),
);

const failures = [];
let validatedFiles = 0;

function validateFile(kind, path) {
  let value;
  try {
    value = readYaml(path);
  } catch (error) {
    failures.push(error.message);
    return null;
  }
  const validate = validators[kind];
  if (!validate(value)) {
    const details = validate.errors
      .map((error) => `${error.instancePath || '/'} ${error.message}`)
      .join('; ');
    failures.push(`${path}: ${details}`);
    return null;
  }
  validatedFiles += 1;
  return value;
}

function yamlFiles(directory) {
  if (!statSync(directory, {throwIfNoEntry: false})?.isDirectory()) return [];
  return readdirSync(directory).flatMap((entry) => {
    const path = resolve(directory, entry);
    if (statSync(path).isDirectory()) return yamlFiles(path);
    return ['.yml', '.yaml'].includes(extname(path).toLowerCase()) ? [path] : [];
  });
}

for (const packageEntry of readdirSync(modelsDirectory)) {
  const packageDirectory = resolve(modelsDirectory, packageEntry);
  if (!statSync(packageDirectory).isDirectory()) continue;

  const manifestPath = resolve(packageDirectory, 'manifest.yml');
  const manifest = validateFile('manifest', manifestPath);
  if (!manifest || manifest.type !== 'train') continue;

  validateFile('control', resolve(packageDirectory, 'control.yml'));
  const actionsPath = resolve(packageDirectory, 'actions.yml');
  if (statSync(actionsPath, {throwIfNoEntry: false})?.isFile()) {
    validateFile('actions', actionsPath);
  }
  for (const path of yamlFiles(resolve(packageDirectory, 'cars'))) validateFile('car', path);
  for (const path of yamlFiles(resolve(packageDirectory, 'parts'))) validateFile('part', path);
}

if (failures.length > 0) {
  console.error('Train model schema validation failed:');
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`Validated ${validatedFiles} train model YAML file(s).`);
