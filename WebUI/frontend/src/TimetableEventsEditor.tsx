import {Button,Flex,Typography} from 'antd';
import {useI18n} from './i18n';
import {formatTimetableTick} from './timetableTime';
import type {TimetableEventPoint} from './types';

/** A compact document-wide index; editing shares the graph's validated dialog. */
export function TimetableEventsEditor({events,onEdit,stationNames}:{events:TimetableEventPoint[];onEdit:(event:TimetableEventPoint)=>void;stationNames:ReadonlyMap<string,string>}){
  const {locale,t}=useI18n();
  return <section className="panel-surface timetable-events-editor">
    <Typography.Title level={5}>{t('events.title')}</Typography.Title>
    {!events.length&&<Typography.Text type="secondary">{t('events.empty')}</Typography.Text>}
    {events.map((event)=><Flex className="timetable-event-row" key={event.id} wrap gap={12} align="center">
      <Typography.Text code>{event.id}</Typography.Text>
      <Typography.Text className="event-station">{stationNames.get(event.stationId)||event.stationId}</Typography.Text>
      <Typography.Text>{event.tick} · {formatTimetableTick(event.tick,locale)}</Typography.Text>
      <Typography.Text className="event-label">{event.label}</Typography.Text>
      <Button size="small" onClick={()=>onEdit({...event})} aria-label={`${t('events.edit')} ${event.id}`}>{t('events.editAction')}</Button>
    </Flex>)}
  </section>;
}
