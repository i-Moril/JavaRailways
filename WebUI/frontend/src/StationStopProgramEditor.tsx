import {useRef} from 'react';
import {Tabs} from 'antd';
import {ProgramBlockEditor} from './ProgramBlockEditor';
import {useI18n} from './i18n';
import type {StationStopProgram,TimetableEventPoint,TimetableExtensionDescriptor} from './types';

export function StationStopProgramEditor({value,onChange,scope,onValidityChange,events=[],extensions=[]}:{value:StationStopProgram;onChange:(value:StationStopProgram)=>void;scope:string;onValidityChange?:(valid:boolean)=>void;extensions?:TimetableExtensionDescriptor[];events?:TimetableEventPoint[]}){
 const {t}=useI18n();
 const latest=useRef(value);latest.current=value;
 const validity=useRef({arrival:true,departurePreparation:true});
 const report=(phase:'arrival'|'departurePreparation',valid:boolean)=>{validity.current[phase]=valid;onValidityChange?.(validity.current.arrival&&validity.current.departurePreparation);};
 const update=(patch:Partial<StationStopProgram>)=>{latest.current={...latest.current,...patch};onChange(latest.current);};
 return <section className="station-stop-program-editor">
  <Tabs destroyOnHidden={false} items={(['arrival','departurePreparation'] as const).map((phase)=>({
   key:phase,label:t(phase==='arrival'?'stationPrograms.arrival':'stationPrograms.preparation'),forceRender:true,
   children:<ProgramBlockEditor events={events} context="arrival" extensions={extensions}
    entryLabel={t(phase==='arrival'?'stationPrograms.arrival':'stationPrograms.preparation')}
    preparationLead={phase==='departurePreparation'?value.preparationLead:undefined}
    onEntryChange={phase==='departurePreparation'?(definition,preparationLead)=>update({departurePreparation:definition,preparationLead}):undefined}
    draftScope={`${scope}/${phase}`} fieldScope={`${scope}/${phase}`} value={value[phase]}
    onValidityChange={(valid)=>report(phase,valid)} onChange={(definition)=>update({[phase]:definition})}/>,
  }))}/>
 </section>;
}
