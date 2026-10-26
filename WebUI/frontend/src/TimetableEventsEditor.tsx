import {Button,Flex,Input,Select,Typography} from 'antd';
import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';
import {nextTimetableEventId} from './timetableEvents';
import type {TimetableEventPoint} from './types';

export function TimetableEventsEditor({events,onChange,stationIds,scope}:{events:TimetableEventPoint[];onChange:(events:TimetableEventPoint[])=>void;stationIds:string[];scope:string}){
  const {t}=useI18n();
  const patch=(id:string,values:Partial<TimetableEventPoint>)=>onChange(events.map((event)=>event.id===id?{...event,...values}:event));
  return <section className="panel-surface timetable-events-editor">
    <Flex justify="space-between" align="center" gap={12} wrap>
      <Typography.Title level={5}>{t('events.title')}</Typography.Title>
      <Button disabled={!stationIds.length} onClick={()=>onChange([...events,{id:nextTimetableEventId(events),stationId:stationIds[0],tick:0}])}>{t('events.add')}</Button>
    </Flex>
    {events.map((event)=><Flex className="timetable-event-row" key={event.id} wrap gap={12} align="center">
      <Typography.Text code>{event.id}</Typography.Text>
      <Select aria-label={t('editor.station')} value={event.stationId||undefined} options={stationIds.map((id)=>({value:id,label:id}))} onChange={(stationId)=>patch(event.id,{stationId})}/>
      <CommittedNumberInput aria-label={t('duties.executeTick')} fieldKey={`${scope}/${event.id}/tick`} min={0} precision={0} allowEmpty={false} value={event.tick} onCommit={(tick)=>patch(event.id,{tick:tick??0})}/>
      <Input aria-label={t('events.label')} placeholder={t('events.label')} value={event.label??''} onChange={(change)=>patch(event.id,{label:change.target.value})}/>
      <Button danger onClick={()=>onChange(events.filter((item)=>item.id!==event.id))}>{t('common.delete')}</Button>
    </Flex>)}
  </section>;
}
