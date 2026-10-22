import {Button,Flex,Input,Select,Typography} from 'antd';
import {DeleteOutlined} from '@ant-design/icons';
import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';
import {type AuthoringFormationEvent,type FormationOutput} from './formationAuthoring';

export function FormationEventsEditor({events,dutyIds,destinations,onChange,fieldScope}:{events:AuthoringFormationEvent[];dutyIds:string[];destinations:string[];onChange:(events:AuthoringFormationEvent[])=>void;fieldScope:string}){
  const {t}=useI18n();
  const options=(ids:string[])=>ids.map((value)=>({value,label:value}));
  const patch=(index:number,event:AuthoringFormationEvent)=>onChange(events.map((e,i)=>i===index?event:e));
  const outputFields=(output:FormationOutput,onPatch:(next:FormationOutput)=>void)=><Flex wrap gap={8} align="center">
    <label>{t('formation.outputId')}<Input value={output.id} onChange={(e)=>onPatch({...output,id:e.target.value})}/></label>
    <label>{t('formation.disposition')}<Select value={output.disposition.type} options={[{value:'HOLD',label:t('formation.hold')},{value:'DUTY',label:t('formation.duty')}]} onChange={(type)=>onPatch({...output,disposition:type==='HOLD'?{type}:{type:'DUTY',dutyId:''}})}/></label>
    {output.disposition.type==='DUTY'&&<label>{t('formation.outputDuty')}<Select value={output.disposition.dutyId||undefined} options={options(dutyIds)} onChange={(dutyId)=>onPatch({...output,disposition:{type:'DUTY',dutyId}})}/></label>}
  </Flex>;
  return <section className="panel-surface">
    <Typography.Title level={4}>{t('formation.title')}</Typography.Title><Typography.Paragraph type="secondary">{t('formation.help')}</Typography.Paragraph>
    {events.map((event,index)=><section className="program-step" key={index}>
      <Flex wrap gap={8} align="center"><Typography.Text strong>{t(`programs.step.${event.kind}`)}</Typography.Text><label>{t('programs.eventId')}<Input value={event.id} onChange={(e)=>patch(index,{...event,id:e.target.value})}/></label><label>{t('formation.coordinator')}<Select value={event.coordinatorDutyId||undefined} options={options(dutyIds)} onChange={(coordinatorDutyId)=>patch(index,{...event,coordinatorDutyId})}/></label><Button danger icon={<DeleteOutlined/>} aria-label={t('common.delete')} onClick={()=>onChange(events.filter((_,i)=>i!==index))}/></Flex>
      {event.kind==='SPLIT'?<>
        <label>{t('formation.firstUnitCount')}<CommittedNumberInput fieldKey={`${fieldScope}/${index}/${event.id}/count`} value={event.partition.firstUnitCount} min={1} precision={0} allowEmpty={false} onCommit={(count)=>patch(index,{...event,partition:{from:'HEAD',firstUnitCount:count??1}})}/></label>
        {event.outputs.map((output,oi)=><div key={oi}><Typography.Text>{t(output.group==='FIRST'?'formation.first':'formation.rest')}</Typography.Text>{outputFields(output,(next)=>patch(index,{...event,outputs:event.outputs.map((o,j)=>j===oi?{...next,group:output.group}:o)}))}</div>)}
      </>:<>
        <Flex wrap gap={8}>
          <label>{t('formation.approaching')}<Select value={event.approachingDutyId||undefined} options={options(dutyIds)} onChange={(approachingDutyId)=>patch(index,{...event,approachingDutyId})}/></label>
          <label>{t('formation.stationary')}<Select value={event.stationaryDutyId||undefined} options={options(dutyIds)} onChange={(stationaryDutyId)=>patch(index,{...event,stationaryDutyId})}/></label>
          <label>{t('formation.stationaryEnd')}<Select value={event.stationaryEnd} options={[{value:'HEAD',label:t('formation.head')},{value:'TAIL',label:t('formation.tail')}]} onChange={(stationaryEnd)=>patch(index,{...event,stationaryEnd})}/></label>
          <label>{t('formation.approachTarget')}<Select value={event.rendezvous.approachDestinationId||undefined} options={options(destinations)} onChange={(approachDestinationId)=>patch(index,{...event,rendezvous:{...event.rendezvous,approachDestinationId}})}/></label>
          <label>{t('formation.stationaryTarget')}<Select value={event.rendezvous.stationaryDestinationId||undefined} options={options(destinations)} onChange={(stationaryDestinationId)=>patch(index,{...event,rendezvous:{...event.rendezvous,stationaryDestinationId}})}/></label>
          <label>{t('formation.approachSpeed')}<CommittedNumberInput fieldKey={`${fieldScope}/${index}/${event.id}/speed`} value={event.maximumApproachSpeedMetersPerSecond*3.6} min={0.01} allowEmpty={false} onCommit={(kmh)=>patch(index,{...event,maximumApproachSpeedMetersPerSecond:(kmh??0)/3.6})}/></label>
        </Flex>{outputFields(event.output,(output)=>patch(index,{...event,output}))}
      </>}
    </section>)}
    <Flex wrap gap={8}>{(['SPLIT','COUPLE'] as const).map((kind)=><Button key={kind} onClick={()=>{
      const id=`event-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
      const event:AuthoringFormationEvent=kind==='SPLIT'?{id,kind,coordinatorDutyId:dutyIds[0]??'',partition:{from:'HEAD',firstUnitCount:1},outputs:[{id:'first',group:'FIRST',disposition:{type:'HOLD'}},{id:'rest',group:'REST',disposition:{type:'HOLD'}}]}:{id,kind,coordinatorDutyId:dutyIds[0]??'',approachingDutyId:dutyIds[0]??'',stationaryDutyId:dutyIds[1]??'',stationaryEnd:'TAIL',rendezvous:{approachDestinationId:'',stationaryDestinationId:''},maximumApproachSpeedMetersPerSecond:0.5,output:{id:'combined',disposition:{type:'HOLD'}}};
      onChange([...events,event]);
    }}>{t(`programs.step.${kind}`)}</Button>)}</Flex>
  </section>;
}
