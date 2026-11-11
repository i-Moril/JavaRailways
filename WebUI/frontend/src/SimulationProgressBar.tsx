import {useEffect,useState} from 'react';
import {useI18n} from './i18n';

export function SimulationProgressBar({kind,startedAt}:{kind:'TIMETABLE'|'LINE';startedAt:number}) {
  const {t}=useI18n();
  const [now,setNow]=useState(()=>Date.now());
  useEffect(()=>{
    setNow(Date.now());
    const timer=window.setInterval(()=>setNow(Date.now()),100);
    return()=>window.clearInterval(timer);
  },[startedAt]);
  const elapsed=Math.max(0,(now-startedAt)/1000).toFixed(1);
  const label=t(kind==='TIMETABLE'?'simulation.timetableRunning':'simulation.lineRunning');
  return <div className="simulation-progress" role="progressbar" aria-label={label} aria-valuetext={`${t('simulation.waited')} ${elapsed} s`}>
    <div className="simulation-progress-label"><b>{label}</b><span>{t('simulation.waited')} {elapsed} s</span></div>
    <div className="simulation-progress-track"><span/></div>
  </div>;
}
