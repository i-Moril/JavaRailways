import {useState} from 'react';
import {Modal,Typography} from 'antd';
import {SimulationTimeoutInput,validSimulationTimeout} from './SimulationTimeoutInput';
import {useI18n} from './i18n';

export function SimulationSetupDialog(p:{fieldKey:string;line:boolean;value:number;disabled:boolean;onClose:()=>void;onConfirm:(millis:number)=>void}) {
 const {t}=useI18n();
 // Editing and cancelling are local to this invocation, never changes to an accepted scenario.
 const [value,setValue]=useState(p.value),[valid,setValid]=useState(true);
 const disabled=p.disabled||!valid||!validSimulationTimeout(value);
 return <Modal open title={t(p.line?'simulation.lineSetup':'simulation.setup')} okText={t('programSimulation.run')} cancelText={t('programSimulation.cancel')} onCancel={p.onClose} onOk={()=>{if(!disabled)p.onConfirm(value);}} okButtonProps={{disabled}} destroyOnHidden>
  <div className="move-dialog"><SimulationTimeoutInput fieldKey={p.fieldKey} value={value} onChange={setValue} onValidityChange={setValid}/><Typography.Text type="secondary">{t('simulation.timeoutHint')}</Typography.Text></div>
 </Modal>;
}
