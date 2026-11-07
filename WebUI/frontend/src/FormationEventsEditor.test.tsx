import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {I18nProvider} from './i18n';
import {FormationSourceFields} from './FormationEventsEditor';
import type {TimetableAuthoringDocumentV1} from './authoring';
const noop=()=>{};
const document:TimetableAuthoringDocumentV1={formatVersion:1,id:'doc',periodDays:1,diagramLines:[],runs:[],arrivalOverrides:[],initialPositions:[],duties:[{id:'1',selector:{type:'TRAIN_NAME',name:'train'},elements:[]},{id:'2',selector:{type:'TAKE_FROM_FORMATION_OUTPUT',eventId:'split',outputId:'rest'},elements:[]}],formationEvents:[{id:'split',kind:'SPLIT',tick:300,duration:{type:'MANUAL',ticks:20},coordinatorDutyId:'1',partition:{from:'HEAD',firstUnitCount:2},outputs:[{id:'first',group:'FIRST',disposition:{type:'DUTY',dutyId:'1'}},{id:'rest',group:'REST',disposition:{type:'DUTY',dutyId:'2'}}]}]};
describe('formation vehicle provenance',()=>{
 it('shows the operation, output and handoff instead of editing raw IDs or acquisition tick',()=>{
 const html=renderToStaticMarkup(<I18nProvider><FormationSourceFields document={document} dutyId="2" eventId="split" outputId="rest" onSelectDuty={noop} onChange={noop}/></I18nProvider>);
 expect(html).toContain('1 · Separate complete EMU units · 300 · Remaining units');expect(html).toContain('300–320');expect(html).toContain('Actual availability depends on completed handoff');expect(html).not.toContain('Output ID');expect(html).not.toContain('Acquisition tick');
 });
 it('keeps deleted source provenance visibly unresolved without offering independent vehicles',()=>{
 const html=renderToStaticMarkup(<I18nProvider><FormationSourceFields document={{...document,formationEvents:[]}} dutyId="2" eventId="split" outputId="rest" onSelectDuty={noop} onChange={noop}/></I18nProvider>);
 expect(html).toContain('No valid source operation and vehicles selected. Assign a source again.');expect(html).not.toContain('300–320');
 });
});

it('does not claim availability after the output was held or reassigned',()=>{
 const changed={...document,formationEvents:document.formationEvents!.map(e=>e.kind==='SPLIT'?{...e,outputs:e.outputs.map(o=>o.group==='REST'?{...o,disposition:{type:'HOLD' as const}}:o)}:e)};
 const html=renderToStaticMarkup(<I18nProvider><FormationSourceFields document={changed} dutyId="2" eventId="split" outputId="rest" onSelectDuty={noop} onChange={noop}/></I18nProvider>);
 expect(html).toContain('No valid source operation and vehicles selected.');expect(html).not.toContain('Actual availability depends on completed handoff');expect(html).toContain('View source operation 1');
});
