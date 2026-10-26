import {useRef} from 'react';
import {Alert,Tabs,Typography} from 'antd';
import {ProgramBlockEditor} from './ProgramBlockEditor';
import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';
import type {StationStopProgram,TimetableEventPoint,TimetableExtensionDescriptor} from './types';
export function StationStopProgramEditor({value,onChange,scope,onValidityChange,events=[],extensions=[]}:{value:StationStopProgram;onChange:(value:StationStopProgram)=>void;scope:string;onValidityChange?:(valid:boolean)=>void;extensions?:TimetableExtensionDescriptor[];events?:TimetableEventPoint[]}){
 const {t}=useI18n();const validity=useRef({arrival:true,departurePreparation:true});const report=(phase:'arrival'|'departurePreparation',valid:boolean)=>{validity.current[phase]=valid;onValidityChange?.(validity.current.arrival&&validity.current.departurePreparation);};return <section><Alert type="info" title={t('stationPrograms.hint')}/>
 <Typography.Paragraph><label>{t('stationPrograms.lead')}<CommittedNumberInput aria-label={t('stationPrograms.lead')} fieldKey={`${scope}/lead`} min={0} precision={0} allowEmpty={false} value={value.preparationLeadTicks} onCommit={(ticks)=>onChange({...value,preparationLeadTicks:ticks??0})}/></label></Typography.Paragraph>
 <Tabs destroyOnHidden={false} items={(['arrival','departurePreparation'] as const).map((phase)=>({key:phase,label:t(phase==='arrival'?'stationPrograms.arrival':'stationPrograms.preparation'),forceRender:true,children:<ProgramBlockEditor events={events} context="arrival" extensions={extensions} draftScope={`${scope}/${phase}`} fieldScope={`${scope}/${phase}`} value={value[phase]} onValidityChange={(valid)=>report(phase,valid)} onChange={(definition)=>onChange({...value,[phase]:definition})}/>}))}/>
 </section>;
}
