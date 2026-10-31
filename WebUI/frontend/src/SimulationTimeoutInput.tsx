import {CommittedNumberInput} from './CommittedNumberInput';
import {useI18n} from './i18n';

export const DEFAULT_SIMULATION_TIMEOUT_MILLIS=60_000;
export const MAX_SIMULATION_TIMEOUT_MILLIS=3_600_000;
export function validSimulationTimeout(value:number):boolean {
 return Number.isSafeInteger(value)&&value>=1&&value<=MAX_SIMULATION_TIMEOUT_MILLIS;
}
/** Seconds are presentation only; every simulation request carries an integer millisecond budget. */
export function SimulationTimeoutInput(p:{fieldKey:string;value:number;onChange:(millis:number)=>void;onValidityChange:(valid:boolean)=>void}) {
 const {t}=useI18n();
 return <label>{t('simulation.timeout')}<CommittedNumberInput aria-label={t('simulation.timeout')} fieldKey={p.fieldKey} value={p.value/1000} allowEmpty={false} min={0.001} max={MAX_SIMULATION_TIMEOUT_MILLIS/1000} step={1} precision={3} onValidityChange={p.onValidityChange} onCommit={seconds=>{if(seconds!==null)p.onChange(Math.round(seconds*1000));}}/></label>;
}
