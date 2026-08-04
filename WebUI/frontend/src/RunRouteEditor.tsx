import { useEffect,useMemo,useRef,useState } from 'react';
import { api } from './api';
import {
  copyRunShifted,createRunFromLine,lineSimulationRoute,resetRunTimings,resizeRunRoute,
  type AuthoringRunCallV1,type AuthoringRunV1,
} from './authoring';
import { useI18n } from './i18n';
import type { LineDirection,LineV1,SimulationProfileDescriptor,WorldMetadata } from './types';

const uid=(prefix:string)=>`${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,7)}`;
const nullableNumber=(value:string):number|null=>value===''?null:+value;
const stationIds=(line:LineV1,direction:LineDirection)=>direction==='UP'?line.upStationIds:line.downStationIds;

interface RunRouteEditorProps {
  world:string;
  run:AuthoringRunV1;
  lines:LineV1[];
  metadata:WorldMetadata;
  simulationProfiles:SimulationProfileDescriptor[];
  defaultProfileKey?:string;
  existingRunIds:Set<string>;
  periodTicks:number;
  busy:boolean;
  onError:(message:string)=>void;
  onChange:(run:AuthoringRunV1,clearArrivalOverrides?:boolean)=>void;
  onRename:(previousId:string,nextId:string)=>void;
  onCopy:(runs:AuthoringRunV1[])=>void;
}

export function RunRouteEditor({
  world,run,lines,metadata,simulationProfiles,defaultProfileKey,existingRunIds,periodTicks,busy,
  onError,onChange,onRename,onCopy,
}:RunRouteEditorProps) {
  const {t}=useI18n();
  const [firstDepartureTick,setFirstDepartureTick]=useState(run.calls[0]?.departureTick??0);
  const [fixedDwellTicks,setFixedDwellTicks]=useState(600);
  const [profileKey,setProfileKey]=useState(defaultProfileKey??'');
  const [copyOffsetTicks,setCopyOffsetTicks]=useState(1_200);
  const [copyCount,setCopyCount]=useState(1);
  const [resetBusy,setResetBusy]=useState(false);
  const latestRun=useRef(run);
  latestRun.current=run;
  useEffect(()=>{
    setFirstDepartureTick(run.calls[0]?.departureTick??0);
    setProfileKey(defaultProfileKey??'');
  },[run.id,defaultProfileKey]);

  const line=lines.find((candidate)=>candidate.id===run.diagramLineId);
  const direction=run.lineDirection??'UP';
  const routeStations=line?stationIds(line,direction):[];
  const originIndex=line?routeStations.indexOf(run.calls[0]?.stationId??''):-1;
  const terminalIndex=line?routeStations.indexOf(run.calls.at(-1)?.stationId??''):-1;
  const stationNames=useMemo(()=>new Map((metadata.stations??[]).map((station)=>[
    station.stationId,station.stationDisplayName||station.stationId,
  ])),[metadata.stations]);
  const labelStation=(stationId:string)=>stationNames.get(stationId)||stationId;
  const berthsFor=(stationId:string)=>(metadata.stations??[]).filter((berth)=>berth.stationId===stationId);

  const changeLine=(lineId:string)=>{
    const selected=lines.find((candidate)=>candidate.id===lineId);
    if(!selected){onChange({...run,diagramLineId:null,calls:[]},true);return;}
    const next=createRunFromLine(run.id,selected,direction,metadata,()=>uid('call'));
    onChange({...next,type:run.type},true);
  };
  const changeDirection=(nextDirection:LineDirection)=>{
    if(!line){onChange({...run,lineDirection:nextDirection},true);return;}
    const next=createRunFromLine(run.id,line,nextDirection,metadata,()=>uid('call'));
    onChange({...next,type:run.type},true);
  };
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
    ?{status,departureTick:null,minimumDwellTicks:null}
    :{status});

  const reset=async()=>{
    if(!line||run.calls.length<2||!profileKey){onError(t('duties.resetMissing'));return;}
    const separator=profileKey.indexOf(':');
    if(separator<1){onError(t('duties.resetMissing'));return;}
    const sourceFingerprint=JSON.stringify(run);
    setResetBusy(true);onError('');
    try{
      const result=await api.simulateLine(world,line.id,{
        profileType:profileKey.slice(0,separator) as 'WORLD_TRAIN'|'TRAIN_TEMPLATE',
        profileId:profileKey.slice(separator+1),direction,
        ...lineSimulationRoute(run),
      });
      if(JSON.stringify(latestRun.current)!==sourceFingerprint)throw new Error(t('duties.resetChanged'));
      const next=resetRunTimings(run,result,firstDepartureTick,fixedDwellTicks);
      const exceedsPeriod=next.calls.some((call)=>(call.arrivalTick??0)>=periodTicks||(call.departureTick??0)>=periodTicks);
      if(exceedsPeriod)throw new Error(t('duties.outsidePeriod'));
      onChange(next,true);
    }catch(error){onError(error instanceof Error?error.message:String(error));}
    finally{setResetBusy(false);}
  };

  const copy=()=>{
    try{
      const generated:AuthoringRunV1[]=[];
      let serial=1;
      for(let copyIndex=1;copyIndex<=copyCount;copyIndex++){
        while(existingRunIds.has(`${run.id}-copy-${serial}`)||generated.some((candidate)=>candidate.id===`${run.id}-copy-${serial}`))serial++;
        const id=`${run.id}-copy-${serial++}`;
        const next=copyRunShifted(run,id,copyOffsetTicks*copyIndex,()=>uid('call'));
        const outside=next.calls.some((call)=>(call.arrivalTick!=null&&(call.arrivalTick<0||call.arrivalTick>=periodTicks))||(call.departureTick!=null&&(call.departureTick<0||call.departureTick>=periodTicks)));
        if(outside)throw new Error(t('duties.outsidePeriod'));
        generated.push(next);
      }
      onCopy(generated);
    }catch(error){onError(error instanceof Error?error.message:String(error));}
  };

  return <>
    <div className="section-title run-identity">
      <input value={run.id} onChange={(event)=>onRename(run.id,event.target.value)}/>
      <select value={run.diagramLineId||''} onChange={(event)=>changeLine(event.target.value)}>
        <option value="">{t('common.none')}</option>{lines.map((item)=><option key={item.id} value={item.id}>{item.displayName}</option>)}
      </select>
      <select value={direction} onChange={(event)=>changeDirection(event.target.value as LineDirection)}>
        <option value="UP">{t('direction.UP')}</option><option value="DOWN">{t('direction.DOWN')}</option>
      </select>
      <select value={run.type} onChange={(event)=>onChange({...run,type:event.target.value as AuthoringRunV1['type']})}>
        <option value="PASSENGER">PASSENGER</option><option value="DEADHEAD">DEADHEAD</option>
      </select>
    </div>
    {!line?<p className="empty">{t('editor.selectLineForSchedule')}</p>:<>
      <div className="run-endpoints">
        <label>{t('status.origin')}<select value={run.calls[0]?.stationId||''} onChange={(event)=>resize(event.target.value,run.calls.at(-1)!.stationId)}>{routeStations.map((station,index)=><option disabled={index>=terminalIndex} key={station} value={station}>{labelStation(station)}</option>)}</select></label>
        <label>{t('status.terminal')}<select value={run.calls.at(-1)?.stationId||''} onChange={(event)=>resize(run.calls[0].stationId,event.target.value)}>{routeStations.map((station,index)=><option disabled={index<=originIndex} key={station} value={station}>{labelStation(station)}</option>)}</select></label>
      </div>
      <div className="run-reset card-inset">
        <label>{t('editor.departureTime')}<input type="number" min="0" value={firstDepartureTick} onChange={(event)=>setFirstDepartureTick(Math.max(0,+event.target.value))}/></label>
        <label>{t('duties.fixedDwell')}<input type="number" min="0" value={fixedDwellTicks} onChange={(event)=>setFixedDwellTicks(Math.max(0,+event.target.value))}/></label>
        <label>{t('duties.originDepartureMode')}<select value={run.originDepartureMode??'AUTO'} onChange={(event)=>onChange({...run,originDepartureMode:event.target.value as AuthoringRunV1['originDepartureMode']})}><option value="AUTO">{t('duties.originDeparture.AUTO')}</option><option value="CONTINUE">{t('duties.originDeparture.CONTINUE')}</option><option value="REVERSE">{t('duties.originDeparture.REVERSE')}</option></select></label>
        <label>{t('simulation.profile')}<select value={profileKey} onChange={(event)=>setProfileKey(event.target.value)}><option value="">{t('common.select')}</option>{simulationProfiles.map((profile)=><option key={`${profile.type}:${profile.id}`} value={`${profile.type}:${profile.id}`}>{profile.displayName} · {profile.carCount}</option>)}</select></label>
        <button className="primary" onClick={()=>void reset()} disabled={busy||resetBusy||run.calls.length<2}>{resetBusy?t('duties.resetting'):t('duties.resetTimes')}</button>
      </div>
      <div className="route-table-scroll"><div className="route-table-header"><span>{t('editor.station')}</span><span>{t('editor.stopStatus')}</span><span>{t('editor.selectBerth')}</span><span>{t('editor.arrivalTime')}</span><span>{t('editor.departureTime')}</span><span>{t('editor.minimumDwell')}</span><span>{t('editor.doorSide')}</span><span/></div>
        <ol className="duty-calls route-table">{run.calls.map((call,index)=>{
          const endpoint=index===0||index===run.calls.length-1;
          const berths=berthsFor(call.stationId);
          return <li key={call.id}>
            <strong title={call.stationId}>{labelStation(call.stationId)}</strong>
            {endpoint?<span>{t(call.status==='ORIGIN'?'status.origin':'status.terminal')}</span>:<select value={call.status} onChange={(event)=>changeStatus(call,event.target.value as 'STOP'|'PASS')}><option value="STOP">{t('status.stop')}</option><option value="PASS">{t('status.pass')}</option></select>}
            <select value={call.berthId||''} onChange={(event)=>changeBerth(call,event.target.value)}><option value="">{t('common.select')}</option>{berths.map((berth)=><option key={berth.berthId} value={berth.berthId}>{berth.berthId}</option>)}</select>
            <input type="number" min="0" disabled={call.status==='ORIGIN'} value={call.arrivalTick??''} onChange={(event)=>patchCall(call.id,{arrivalTick:nullableNumber(event.target.value)})}/>
            <input type="number" min="0" disabled={call.status==='PASS'||call.status==='TERMINAL'} value={call.departureTick??''} onChange={(event)=>patchCall(call.id,{departureTick:nullableNumber(event.target.value)})}/>
            <input type="number" min="0" disabled={call.status!=='STOP'} value={call.minimumDwellTicks??''} onChange={(event)=>patchCall(call.id,{minimumDwellTicks:nullableNumber(event.target.value)})}/>
            <select value={call.doorSide||''} disabled={call.status==='PASS'||call.status==='ORIGIN'} onChange={(event)=>patchCall(call.id,{doorSide:(event.target.value||null) as typeof call.doorSide})}><option value="">—</option><option>NONE</option><option>LEFT</option><option>RIGHT</option><option>BOTH</option></select>
            <button className="danger" title={t('duties.trimEndpoint')} disabled={!endpoint||run.calls.length<=2} onClick={()=>index===0?resize(run.calls[1].stationId,run.calls.at(-1)!.stationId):resize(run.calls[0].stationId,run.calls.at(-2)!.stationId)}>×</button>
          </li>;
        })}</ol>
      </div>
      <div className="run-copy card-inset">
        <label>{t('duties.copyOffset')}<input type="number" value={copyOffsetTicks} onChange={(event)=>setCopyOffsetTicks(Math.trunc(+event.target.value))}/></label>
        <label>{t('duties.copyCount')}<input type="number" min="1" max="100" value={copyCount} onChange={(event)=>setCopyCount(Math.max(1,Math.min(100,Math.trunc(+event.target.value))))}/></label>
        <button onClick={copy} disabled={busy}>{t('duties.copyShift')}</button>
        <small>{t('duties.copyUnassigned')}</small>
      </div>
    </>}
  </>;
}
