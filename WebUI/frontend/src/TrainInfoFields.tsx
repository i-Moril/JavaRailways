import {Alert,Button,Input,Select,Typography} from 'antd';
import {useI18n} from './i18n';
import {trainInfoFields,trainInfoRows,type TrainInfoRow} from './trainInfo';
/** Raw rows are editor drafts; conversion validates identities before accepting the program. */
export function TrainInfoFields({raw,onChange}:{raw:string;onChange:(raw:string)=>void}) {
 const {t}=useI18n();let rows:TrainInfoRow[];
 try{rows=trainInfoRows(raw);}catch{return <><Alert type="warning" title={t('trainInfo.invalidRows')}/><Input.TextArea aria-label={t('trainInfo.rawRows')} value={raw} onChange={event=>onChange(event.target.value)}/></>;}
 const write=(next:TrainInfoRow[])=>onChange(JSON.stringify(next));
 return <div className="train-info-fields"><Typography.Text strong>{t('trainInfo.title')}</Typography.Text><Typography.Paragraph type="secondary">{t('trainInfo.help')}</Typography.Paragraph>{rows.map((row,index)=><div className="train-info-row" key={index}><Input aria-label={`${t('trainInfo.key')} ${index+1}`} value={row.key} onChange={event=>write(rows.map((item,i)=>i===index?{...item,key:event.target.value}:item))}/><Select aria-label={`${t('trainInfo.field')} ${index+1}`} value={row.field} options={trainInfoFields.map(value=>({value,label:t(`trainInfo.${value}`)}))} onChange={field=>write(rows.map((item,i)=>i===index?{...item,field}:item))}/><Button danger aria-label={`${t('trainInfo.remove')} ${index+1}`} onClick={()=>write(rows.filter((_,i)=>i!==index))}>×</Button></div>)}<Button disabled={rows.length>=64} onClick={()=>write([...rows,{key:'',field:'NAME'}])}>{t('trainInfo.add')}</Button></div>;
}
