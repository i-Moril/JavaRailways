import {renderToStaticMarkup} from 'react-dom/server';
import {describe,expect,it} from 'vitest';
import {I18nProvider,translate} from './i18n';
import {libraryHasChanges,newLibraryProgram,StationProgramLibrary} from './StationProgramLibrary';
import type {StationProgramDefinition,StationProgramRecord} from './types';
const definition:StationProgramDefinition={parameters:[],steps:[]};
const record=(id:string,name=id):StationProgramRecord=>({formatVersion:1,id,name,definition});

describe('program library file management',()=>{
 it('uses 128-bit numeric identities and retries collisions without depending on the wall clock',()=>{
  const entropy=[new Uint32Array([0,0,0,1]),new Uint32Array([1,2,3,4])];
  const first=newLibraryProgram([record('1')],()=>entropy.shift()!);
  expect(first.id).toBe(String((1n<<96n)|(2n<<64n)|(3n<<32n)|4n));
  expect(entropy).toHaveLength(0);expect(first.definition).toEqual({parameters:[],steps:[]});
 });
 it('uses compact numeric default names independently of the internal identity',()=>{
  const next=newLibraryProgram([record('long-internal-id','1'),record('other-id','3')]);
  expect(next.name).toBe('2');expect(next.id).not.toBe(next.name);
 });
 it('recognizes unaccepted invalid canvas changes as unsaved even with unchanged semantic definition',()=>{
  expect(libraryHasChanges(record('1'),definition,true)).toBe(false);
  expect(libraryHasChanges(record('1'),definition,false)).toBe(true);
  expect(libraryHasChanges(record('1'),{parameters:[],steps:[{id:'wait',type:'WAIT_DURATION',durationTicks:{value:1}}]},true)).toBe(true);
  expect(libraryHasChanges(undefined,definition,false)).toBe(false);
 });
 it('treats estimate edits, zero and clearing as unsaved metadata',()=>{
  const estimated={...record('1'),estimatedDurationTicks:20};
  expect(libraryHasChanges(estimated,definition,true,20)).toBe(false);
  expect(libraryHasChanges(estimated,definition,true,0)).toBe(true);
  expect(libraryHasChanges(estimated,definition,true,undefined)).toBe(true);
 });
 it('remounts all editor and request state for each owner/world pair',()=>{
  expect(StationProgramLibrary({world:'a',documentId:'doc',recoveryOwner:'player'}).key).not.toBe(StationProgramLibrary({world:'b',documentId:'doc',recoveryOwner:'player'}).key);
  expect(StationProgramLibrary({world:'a',documentId:'doc',recoveryOwner:'player'}).key).not.toBe(StationProgramLibrary({world:'a',documentId:'doc',recoveryOwner:'other'}).key);
  expect(StationProgramLibrary({world:'a',documentId:'doc',recoveryOwner:'player'}).key).not.toBe(StationProgramLibrary({world:'a',documentId:'other',recoveryOwner:'player'}).key);
 });
 it('renders a file list with no large manual ID/name creation form',()=>{
  const html=renderToStaticMarkup(<I18nProvider><StationProgramLibrary world="world" documentId="doc" recoveryOwner="player"/></I18nProvider>);
  expect(html).toContain('program-library-files');expect(html).toContain('role="list"');
  expect(html).toContain(translate('en-US','programs.new'));
  expect(html).not.toContain(`aria-label="${translate('en-US','programs.id')}"`);
  expect(html).not.toContain(`aria-label="${translate('en-US','programs.name')}"`);
 });
});
