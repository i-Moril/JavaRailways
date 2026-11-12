import { localizedNameLabel } from './metadata';
import { useState,type ReactNode } from 'react';
import { Badge,Button,Flex,Input,Segmented,Select,Space,Tag,Tooltip,Typography } from 'antd';
import { LeftOutlined,PlusOutlined,RightOutlined } from '@ant-design/icons';
import type { AuthoringRunV1 } from './authoring';
import { useI18n } from './i18n';
import { graphServiceColor } from './graph';
import { useTheme } from './theme';
import { formatTimetableTick } from './timetableTime';
import type { LineDirection,LineV1 } from './types';

interface TimetableWorkspaceProps {
  lines:LineV1[];
  activeLineId:string;
  onLineChange:(lineId:string)=>void;
  runs:AuthoringRunV1[];
  selectedRunId:string;
  serviceColorIds?:readonly string[];
  onSelectRun:(runId:string)=>void;
  onAddRun:()=>void;
  search:string;
  onSearchChange:(value:string)=>void;
  direction:LineDirection;
  onDirectionChange:(direction:LineDirection)=>void;
  assignmentFor:(runId:string)=>string|undefined;
  graph:ReactNode;
  inspector:ReactNode;
  graphActions?:ReactNode;
  graphPreviewActive:boolean;
  saved:boolean;
  saveStatus?:ReactNode;
}

const runStart=(run:AuthoringRunV1)=>run.calls.find((call)=>call.departureTick!=null)?.departureTick;
const runEnd=(run:AuthoringRunV1)=>[...run.calls].reverse().find((call)=>call.arrivalTick!=null)?.arrivalTick;

export function TimetableWorkspace({
  lines,activeLineId,onLineChange,runs,selectedRunId,serviceColorIds,onSelectRun,onAddRun,search,onSearchChange,direction,onDirectionChange,
  assignmentFor,graph,inspector,graphActions,graphPreviewActive,saved,saveStatus,
}:TimetableWorkspaceProps) {
  const {locale,t}=useI18n();
  const {theme}=useTheme();
  const [leftCollapsed,setLeftCollapsed]=useState(false);
  const [rightCollapsed,setRightCollapsed]=useState(false);
  const line=lines.find((candidate)=>candidate.id===activeLineId);
  const selected=runs.find((run)=>run.id===selectedRunId);
  const color=line?.color??'#1677ff';
  const colorIds=serviceColorIds??runs.map((run)=>run.id);
  const runColor=(id:string)=>graphServiceColor(theme,id,colorIds,line?.color,runs.find(run=>run.id===id)?.color);
  return <div className="workspace-grid" style={{gridTemplateColumns:`${leftCollapsed?46:238}px minmax(420px,1fr) ${rightCollapsed?46:520}px`}}>
    {leftCollapsed?<aside className="collapsed-panel panel-surface">
      <Tooltip title={t('editor.runLibraryShow')} placement="right"><Button type="text" icon={<RightOutlined/>} onClick={()=>setLeftCollapsed(false)}/></Tooltip>
      <span>{t('duties.runs')}</span>
    </aside>:<aside className="run-panel panel-surface">
      <div className="line-scope"><Typography.Text type="secondary">{t('lines.current')}</Typography.Text><Select value={activeLineId||undefined} onChange={onLineChange} options={lines.map((item)=>({value:item.id,label:<Space><Badge color={item.color??'#1677ff'}/>{localizedNameLabel(item.id,item.metadata?.name,locale)}</Space>}))}/></div>
      <Flex align="center" justify="space-between" className="panel-heading"><Typography.Title level={5}>{t('duties.runs')}</Typography.Title><Space size={0}><Button size="small" type="text" icon={<PlusOutlined/>} onClick={onAddRun}/><Tooltip title={t('editor.runLibraryHide')}><Button size="small" type="text" icon={<LeftOutlined/>} onClick={()=>setLeftCollapsed(true)}/></Tooltip></Space></Flex>
      <div className="run-filters"><Input.Search allowClear value={search} onChange={(event)=>onSearchChange(event.target.value)} placeholder={t('duties.searchRuns')}/><Segmented block size="small" value={direction} onChange={(value)=>onDirectionChange(value as LineDirection)} options={[{value:'UP',label:t('direction.UP')},{value:'DOWN',label:t('direction.DOWN')}]}/></div>
      <div className="run-list">{runs.map((run)=>{
        const start=runStart(run),end=runEnd(run);
        return <button key={run.id} className={`run-card ${selectedRunId===run.id?'selected':''}`} onClick={()=>onSelectRun(run.id)}><span className="line-stripe" style={{background:runColor(run.id)}}/><span className="run-main"><b>{run.id}</b><small>{assignmentFor(run.id)??t('duties.unassigned')}</small></span><span className="run-time">{start==null?'—':start}<small>{end==null?'—':formatTimetableTick(end,locale)}</small></span></button>;
      })}</div>
      {!runs.length&&<p className="empty run-library-empty">{t('duties.noRunsOnLine')}</p>}
    </aside>}

    <main className="graph-panel panel-surface">
      <Flex className="graph-header" justify="space-between" align="center"><div><Typography.Title level={5}>{t('editor.graph')}</Typography.Title><Space size={6}><Tag color={selected?runColor(selected.id):color}>{selected?.id??t('common.none')}</Tag><Typography.Text type="secondary">{line&&localizedNameLabel(line.id,line.metadata?.name,locale)}</Typography.Text></Space></div><Space><Typography.Text type="secondary">{graphPreviewActive?t('duties.shiftPreviewActive'):t('editor.graphPanHint')}</Typography.Text>{saveStatus??<Tag color={saved?'green':'gold'}>{saved?t('common.saved'):t('common.unsaved')}</Tag>}{graphActions}</Space></Flex>
      <div className="studio-graph-canvas">{graph}</div>
      <Flex className="graph-status" justify="space-between"><Badge color={color} text={line?localizedNameLabel(line.id,line.metadata?.name,locale):t('common.none')}/><Typography.Text type="secondary">{t('editor.graphAxisHint')}</Typography.Text></Flex>
    </main>

    {rightCollapsed?<aside className="collapsed-panel right panel-surface">
      <Tooltip title={t('editor.sidebarShow')} placement="left"><Button type="text" icon={<LeftOutlined/>} onClick={()=>setRightCollapsed(false)}/></Tooltip><span>{t('editor.stationCalls')}</span>
    </aside>:<aside className="inspector-panel panel-surface"><Tooltip title={t('editor.sidebarHide')}><Button className="inspector-collapse" type="text" icon={<RightOutlined/>} onClick={()=>setRightCollapsed(true)}/></Tooltip>{inspector}</aside>}
  </div>;
}
