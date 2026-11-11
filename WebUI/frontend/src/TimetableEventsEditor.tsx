import {Button,Flex,Tag,Typography} from 'antd';
import {useI18n} from './i18n';
import {formatTimetableTick} from './timetableTime';
import type {TimetableEventPoint} from './types';
import type {ProjectedTimedOperation} from './timedOperations';

/** Both owners share a projection; editing remains at the source. */
export function TimetableEventsEditor({operations,onEdit,onCopy,onOpenDuty,stationNames}:{operations:ProjectedTimedOperation[];onEdit:(event:TimetableEventPoint)=>void;onCopy?:(event:TimetableEventPoint)=>void;onOpenDuty:(dutyId:string,elementId:string)=>void;stationNames:ReadonlyMap<string,string>}){
 const {locale,t}=useI18n();
 return <section className="panel-surface timetable-events-editor">
  <Typography.Title level={5}>{t('events.title')}</Typography.Title>
  {!operations.length&&<Typography.Text type="secondary">{t('events.empty')}</Typography.Text>}
  {operations.map(({operation:event,owner,dutyId,elementId,endTick})=><Flex className="timetable-event-row" key={`${owner}/${dutyId??''}/${event.id}`} wrap gap={12} align="center">
   {owner==='GRAPH'&&<Typography.Text code>{event.id}</Typography.Text>}
   {owner==='DUTY'&&<Tag>{t('timed.dutyOwned')} · {dutyId}</Tag>}
   {!event.program&&<Tag>{t('timed.marker')}</Tag>}
   <Typography.Text className="event-station">{event.stationId?stationNames.get(event.stationId)||event.stationId:'—'}</Typography.Text>
   <Typography.Text>{formatTimetableTick(event.tick,locale)}{endTick===undefined?` · ${t('timeline.unknown')}`:endTick!==event.tick?`–${formatTimetableTick(endTick,locale)}`:''}</Typography.Text>
   <Typography.Text className="event-label">{event.label||event.program?.programId}</Typography.Text>
   {owner==='GRAPH'?<Flex gap={6}><Button size="small" onClick={()=>onEdit(structuredClone(event))} aria-label={`${t('events.edit')} ${event.id}`}>{t('events.editAction')}</Button>{onCopy&&<Button size="small" onClick={()=>onCopy(structuredClone(event))} aria-label={`${t('events.copy')} ${event.id}`}>{t('events.copyAction')}</Button>}</Flex>:<Button size="small" onClick={()=>onOpenDuty(dutyId!,elementId!)}>{t('timed.openDuty')}</Button>}
  </Flex>)}
 </section>;
}
