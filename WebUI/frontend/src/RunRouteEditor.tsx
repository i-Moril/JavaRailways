import {EditableIdSelect,EditableIdList} from './EditableIdSelect';
import {berthOptions,waypointOptions} from './idOptions';
import { useEffect,useMemo,useState,type ReactNode } from 'react';
import { Button,Flex,Modal,Segmented,Select,Space,Table,Tabs,Tag,Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { ArrowLeftOutlined,CheckCircleOutlined,DeleteOutlined } from '@ant-design/icons';
import { resizeRunRoute,type AuthoringIssue,type AuthoringRunCallV1,type AuthoringRunV1 } from './authoring';
import {StationStopProgramEditor} from './StationStopProgramEditor';
import {defaultStationStopProgram} from './stationPlans';
import { RunTitleEditor } from './RunTitleEditor';
import { CommittedNumberInput } from './CommittedNumberInput';
import { useI18n } from './i18n';
import { formatTimetableTick } from './timetableTime';
import type { StationStopProgram,TimetableEventPoint,LineDirection,LineV1,WorldMetadata } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const stationIds=(line:LineV1,direction:LineDirection)=>direction==='UP'?line.upStationIds:line.downStationIds;

interface RunRouteEditorProps {
  tools?:ReactNode;
  stationPrograms?:StationStopProgram[];
  events?:TimetableEventPoint[];
  onStationProgramChange?:(program:StationStopProgram)=>void;
  onRename?:(id:string)=>void;
  fieldScope?:string;
  programDraftScope?:string;
  issues?:AuthoringIssue[];
  focusedCallId?:string;
  focusedArrivalStep?:string;
  onArrivalValidityChange?:(runId:string,callId:string,valid:boolean)=>void;
  onUndo?:()=>void;
  onRedo?:()=>void;
  run:AuthoringRunV1;
  lines:LineV1[];
  metadata:WorldMetadata;
  busy:boolean;
  onError:(message:string)=>void;
  onChange:(run:AuthoringRunV1,clearArrivalOverrides?:boolean)=>void;
  onDelete:()=>void;
}

export function RunRouteEditor({events=[],stationPrograms=[],onStationProgramChange,run,lines,metadata,busy,onError,onChange,onDelete,tools,issues=[],focusedCallId,fieldScope='',onRename,onArrivalValidityChange,focusedArrivalStep,onUndo,onRedo,programDraftScope=fieldScope}:RunRouteEditorProps) {
  const {locale,t}=useI18n();
  const [inspectorTab,setInspectorTab]=useState<'calls'|'options'|'issues'>('calls');
  const [selectedCallId,setSelectedCallId]=useState(focusedCallId??'');
  useEffect(()=>{setSelectedCallId(focusedCallId??'');},[run.id,focusedCallId]);
  const [arrivalOpen,setArrivalOpen]=useState(false);
  useEffect(()=>{setArrivalOpen(Boolean(focusedArrivalStep));},[run.id,selectedCallId,focusedArrivalStep]);
  const [timeDisplay,setTimeDisplay]=useState<'tick'|'both'>('both');
  const line=lines.find((candidate)=>candidate.id===run.diagramLineId);
  const direction=run.lineDirection??'UP';
  const routeStations=line?stationIds(line,direction):[];
  const originIndex=routeStations.indexOf(run.calls[0]?.stationId??'');
  const terminalIndex=routeStations.indexOf(run.calls.at(-1)?.stationId??'');
  const stationNames=useMemo(()=>new Map((metadata.stations??[]).map((station)=>[
    station.stationId,station.stationDisplayName||station.stationId,
  ])),[metadata.stations]);
  const labelStation=(stationId:string)=>stationNames.get(stationId)||stationId;
  const berthsFor=(stationId:string)=>(metadata.stations??[]).filter((berth)=>berth.stationId===stationId);

  const resize=(originStationId:string,terminalStationId:string)=>{
    if(!line)return;
    try{onChange(resizeRunRoute(run,line,direction,originStationId,terminalStationId,metadata,()=>uid('call')),true);}
    catch(error){onError(error instanceof Error?error.message:String(error));}
  };
  const patchCall=(callId:string,patch:Partial<AuthoringRunCallV1>)=>onChange({
    ...run,calls:run.calls.map((call)=>call.id===callId?{...call,...patch}:call),
  },true);
  const changeBerth=(call:AuthoringRunCallV1,berthId:string)=>{
    const berth=berthsFor(call.stationId).find((candidate)=>candidate.berthId===berthId);
    patchCall(call.id,{berthId:berthId||null,targetId:berth?.destinationId??''});
  };
  const changeStatus=(call:AuthoringRunCallV1,status:'STOP'|'PASS')=>patchCall(call.id,status==='PASS'
    ?{status,departureTick:null,minimumDwellTicks:null}:{status});

  const selectedCall=run.calls.find((call)=>call.id===selectedCallId);
  const callColumns:TableColumnsType<AuthoringRunCallV1>=[
    {title:t('editor.station'),key:'station',width:132,ellipsis:true,render:(_,call)=><Button type="link" className="station-link" onClick={()=>setSelectedCallId(call.id)}>{labelStation(call.stationId)}</Button>},
    {title:t('editor.callType'),key:'status',width:92,render:(_,call)=>call.status==='ORIGIN'||call.status==='TERMINAL'?<Typography.Text>{t(call.status==='ORIGIN'?'status.origin':'status.terminal')}</Typography.Text>:<Select variant="borderless" value={call.status} onChange={(status)=>changeStatus(call,status as 'STOP'|'PASS')} options={[{value:'STOP',label:t('status.stop')},{value:'PASS',label:t('status.pass')}]}/>},
    {title:t('editor.berth'),key:'berth',width:88,render:(_,call)=><EditableIdSelect fieldKey={`${fieldScope}/${run.id}/${call.id}/berth`} aria-label={`${labelStation(call.stationId)} · ${t('editor.berth')}`} value={call.berthId??''} placeholder="—" onCommit={(berthId)=>changeBerth(call,berthId)} options={berthOptions(metadata,call.stationId)}/>},
    {title:t('editor.arrivalTime'),key:'arrival',width:96,render:(_,call)=>call.status==='ORIGIN'?<Typography.Text type="secondary">—</Typography.Text>:<span className="editable-tick"><CommittedNumberInput aria-label={`${labelStation(call.stationId)} · ${t('editor.arrivalTime')}`} fieldKey={`${fieldScope}/${run.id}/${call.id}/arrival`} variant="borderless" controls={false} min={0} precision={0} value={call.arrivalTick} onCommit={(arrivalTick)=>patchCall(call.id,{arrivalTick})}/>{timeDisplay==='both'&&<small>{call.arrivalTick==null?'—':formatTimetableTick(call.arrivalTick,locale)}</small>}</span>},
    {title:t('editor.departureTime'),key:'departure',width:96,render:(_,call)=>call.status==='PASS'?<Typography.Text type="secondary">—</Typography.Text>:<span className="editable-tick"><CommittedNumberInput aria-label={`${labelStation(call.stationId)} · ${t('editor.departureTime')}`} fieldKey={`${fieldScope}/${run.id}/${call.id}/departure`} variant="borderless" controls={false} min={0} precision={0} value={call.departureTick} onCommit={(departureTick)=>patchCall(call.id,{departureTick})}/>{timeDisplay==='both'&&<small>{call.departureTick==null?'—':formatTimetableTick(call.departureTick,locale)}</small>}</span>},
  ];

  if(!line)return <p className="empty">{t('editor.selectLineForSchedule')}</p>;
  if(selectedCall)return <section className="call-subpage">
    <Flex className="subpage-head" align="center" justify="space-between"><Button type="text" icon={<ArrowLeftOutlined/>} onClick={()=>setSelectedCallId('')}>{t('editor.backToCalls')}</Button><Tag>{run.id}</Tag></Flex>
    <div className="subpage-title"><Typography.Text type="secondary">{t('editor.stationDetails')}</Typography.Text><Typography.Title level={4}>{labelStation(selectedCall.stationId)}</Typography.Title></div>
    <div className="call-detail-form"><label>{t('editor.minimumDwell')}<CommittedNumberInput fieldKey={`${fieldScope}/${run.id}/${selectedCall.id}/dwell`} min={0} precision={0} disabled={selectedCall.status==='PASS'||selectedCall.status==='ORIGIN'} value={selectedCall.minimumDwellTicks} onCommit={(minimumDwellTicks)=>patchCall(selectedCall.id,{minimumDwellTicks})}/></label><label>{t('editor.doorSide')}<Select value={selectedCall.doorSide||'NONE'} disabled={selectedCall.status==='PASS'||selectedCall.status==='ORIGIN'} onChange={(doorSide)=>patchCall(selectedCall.id,{doorSide:doorSide as typeof selectedCall.doorSide})} options={['NONE','LEFT','RIGHT','BOTH'].map((value)=>({value,label:value}))}/></label></div>
    <label>{t('editor.via')}<EditableIdList fieldKey={`${fieldScope}/${run.id}/${selectedCall.id}/via`} aria-label={t('editor.via')} disabled={selectedCall.status==='ORIGIN'} value={selectedCall.viaIds??[]} options={waypointOptions(metadata)} onChange={(viaIds)=>patchCall(selectedCall.id,{viaIds})}/></label>
    {selectedCall.status!=='PASS'&&<section className="station-arrival-actions"><Typography.Title level={5}>{t('stationPrograms.title')}</Typography.Title><Typography.Paragraph type="secondary">{t('stationPrograms.hint')}</Typography.Paragraph><Button disabled={busy||!onStationProgramChange} onClick={()=>setArrivalOpen(true)}>{t('programs.editArrival')}</Button><Modal title={`${t('stationPrograms.title')} · ${labelStation(selectedCall.stationId)}`} width={1120} open={arrivalOpen} onCancel={()=>setArrivalOpen(false)} footer={null} destroyOnHidden><StationStopProgramEditor events={events} value={stationPrograms.find((program)=>program.stationId===selectedCall.stationId)??defaultStationStopProgram(selectedCall.stationId)} scope={`${programDraftScope}/station/${selectedCall.stationId}`} onValidityChange={(valid)=>onArrivalValidityChange?.(selectedCall.stationId,selectedCall.id,valid)} extensions={metadata.extensions?.actions} onChange={(program)=>onStationProgramChange?.(program)}/></Modal></section>}

  </section>;

  const calls=<div className="calls-page"><Flex className="time-mode" align="center" justify="space-between"><Typography.Text type="secondary">{t('editor.stationCallsHint')}</Typography.Text><Segmented size="small" value={timeDisplay} onChange={(value)=>setTimeDisplay(value as 'tick'|'both')} options={[{value:'tick',label:t('editor.tick')},{value:'both',label:t('editor.bothTimes')}]}/></Flex><Table<AuthoringRunCallV1> className="calls-table" size="small" pagination={false} tableLayout="fixed" rowKey="id" columns={callColumns} dataSource={run.calls}/></div>;
  const options=<div className="run-options-page">

    {tools}
    <section className="option-section"><Typography.Title level={5}>{t('editor.runEndpoints')}</Typography.Title><Typography.Text type="secondary">{t('editor.runEndpointsHint')}</Typography.Text><div className="endpoint-options"><label>{t('status.origin')}<Select value={run.calls[0]?.stationId} onChange={(station)=>resize(station,run.calls.at(-1)!.stationId)} options={routeStations.map((station,index)=>({value:station,label:labelStation(station),disabled:index>=terminalIndex}))}/></label><label>{t('status.terminal')}<Select value={run.calls.at(-1)?.stationId} onChange={(station)=>resize(run.calls[0].stationId,station)} options={routeStations.map((station,index)=>({value:station,label:labelStation(station),disabled:index<=originIndex}))}/></label></div></section>
    <section className="option-section danger-zone"><div><Typography.Title level={5}>{t('editor.deleteRun')}</Typography.Title><Typography.Text type="secondary">{t('editor.deleteRunHint')}</Typography.Text></div><Button danger type="primary" icon={<DeleteOutlined/>} disabled={busy} onClick={onDelete}>{t('editor.deleteRun')}</Button></section>
  </div>;
  const issuesView=<div className="issues-view">{issues.map((issue,index)=><div className="validation-row" key={index}><span><b>{issue.message}</b><small>{issue.path}</small></span></div>)}{issues.length===0&&<div className="issue-success"><CheckCircleOutlined/><span><b>{t('editor.noLocalIssues')}</b><small>{t('editor.serverValidationStillRequired')}</small></span></div>}</div>;
  return <section className="run-inspector"><Flex className="compact-inspector-head" align="center" justify="space-between"><Space>{onRename?<RunTitleEditor fieldKey={`${fieldScope}/${run.id}`} value={run.id} disabled={busy} onCommit={onRename}/>:<Typography.Text strong>{run.id}</Typography.Text>}<Tag>{t('editor.draft')}</Tag></Space></Flex><Tabs className="inspector-tabs" activeKey={inspectorTab} onChange={(key)=>setInspectorTab(key as typeof inspectorTab)} items={[{key:'calls',label:t('editor.stationCalls')},{key:'options',label:t('editor.runOptions')},{key:'issues',label:t('duties.issues')}]}/><div className="inspector-body">{inspectorTab==='calls'?calls:inspectorTab==='options'?options:issuesView}</div></section>;
}
