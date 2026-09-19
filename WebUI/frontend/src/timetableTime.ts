import type { Locale } from './i18n';

const TICKS_PER_DAY=24_000;
const MINUTES_PER_DAY=1_440;
const MINECRAFT_CLOCK_OFFSET_MINUTES=360;

export function formatTimetableTick(tick:number,locale:Locale):string {
  if(!Number.isFinite(tick)||tick<0)return '—';
  const totalMinutes=Math.floor(tick*MINUTES_PER_DAY/TICKS_PER_DAY)+MINECRAFT_CLOCK_OFFSET_MINUTES;
  const day=Math.floor(totalMinutes/MINUTES_PER_DAY)+1;
  const minuteOfDay=totalMinutes%MINUTES_PER_DAY;
  const hours=Math.floor(minuteOfDay/60).toString().padStart(2,'0');
  const minutes=(minuteOfDay%60).toString().padStart(2,'0');
  return locale==='zh-CN'?`第${day}天 ${hours}:${minutes}`:`D${day} ${hours}:${minutes}`;
}
