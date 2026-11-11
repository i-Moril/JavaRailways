import {DeleteOutlined,PlusOutlined} from '@ant-design/icons';
import {Button,Flex,Typography} from 'antd';
import {EditableIdSelect} from './EditableIdSelect';
import {directedWaypointOptions} from './idOptions';
import {useI18n} from './i18n';
import type {AuthoringInitialPositionV1} from './authoring';
import type {WorldMetadata} from './types';

interface Props {
  positions:AuthoringInitialPositionV1[];
  metadata:WorldMetadata;
  fieldScope:string;
  onChange:(positions:AuthoringInitialPositionV1[])=>void;
}

export function InitialPositionsPage({positions,metadata,fieldScope,onChange}:Props) {
  const {t}=useI18n();
  const trainOptions=[...new Set((metadata.simulationProfiles??[])
    .filter((profile)=>profile.type==='WORLD_TRAIN')
    .map((profile)=>profile.displayName))]
    .sort((left,right)=>left.localeCompare(right))
    .map((value)=>({value,label:value}));
  const patch=(index:number,value:Partial<AuthoringInitialPositionV1>)=>onChange(positions.map((position,current)=>current===index?{...position,...value}:position));
  return <div className="module-page initial-positions-module">
    <section className="panel-surface initial-positions-panel">
      <Flex justify="space-between" align="start" gap={16} wrap>
        <div><Typography.Title level={3}>{t('positions.title')}</Typography.Title><Typography.Paragraph type="secondary">{t('positions.description')}</Typography.Paragraph></div>
        <Button icon={<PlusOutlined/>} onClick={()=>onChange([...positions,{trainName:'',wayPointId:''}])}>{t('positions.add')}</Button>
      </Flex>
      {positions.length===0?<p className="empty">{t('positions.empty')}</p>:<div className="initial-position-list">
        <div className="initial-position-head"><Typography.Text strong>{t('ui.trainName')}</Typography.Text><Typography.Text strong>{t('duties.initialWaypoint')}</Typography.Text><span/></div>
        {positions.map((position,index)=><div className="initial-position-row" key={index}>
          <EditableIdSelect fieldKey={`${fieldScope}/initial-positions/${index}/train`} aria-label={t('ui.trainName')} value={position.trainName} options={trainOptions} onCommit={(trainName)=>patch(index,{trainName})}/>
          <EditableIdSelect fieldKey={`${fieldScope}/initial-positions/${index}/waypoint`} aria-label={t('duties.initialWaypoint')} value={position.wayPointId} options={directedWaypointOptions(metadata)} onCommit={(wayPointId)=>patch(index,{wayPointId})}/>
          <Button danger type="text" icon={<DeleteOutlined/>} aria-label={t('positions.remove')} onClick={()=>onChange(positions.filter((_,current)=>current!==index))}/>
        </div>)}
      </div>}
      <Typography.Paragraph className="initial-positions-hint" type="secondary">{t('positions.hint')}</Typography.Paragraph>
    </section>
  </div>;
}
