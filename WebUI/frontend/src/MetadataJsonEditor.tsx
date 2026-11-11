import { Alert,Button,Flex,Input,Typography } from 'antd';
import { parseMetadata } from './metadata';
import { useI18n } from './i18n';

/** Raw text belongs to the calling document owner, so invalid drafts survive navigation. */
export function MetadataJsonEditor({draft,onChange,disabled=false}:{draft:string;onChange:(draft:string)=>void;disabled?:boolean}) {
  const {t}=useI18n();
  const parsed=parseMetadata(draft);
  return <Flex vertical gap={8} style={{minWidth:0,width:'100%'}}>
    <Typography.Text strong>{t('metadata.title')}</Typography.Text>
    <Typography.Text type="secondary">{t('metadata.help')}</Typography.Text>
    <Input.TextArea aria-label={t('metadata.title')} value={draft} onChange={(event)=>onChange(event.target.value)} disabled={disabled} spellCheck={false} autoSize={{minRows:7,maxRows:24}} style={{fontFamily:'monospace',minWidth:0}}/>
    {parsed.error&&<Alert type="error" showIcon title={t(`stations.${parsed.error}`)} description={parsed.detail}/>}
    <Button disabled={disabled||!!parsed.error} onClick={()=>{if(!parsed.error)onChange(JSON.stringify(parsed.value,null,2));}}>{t('stations.format')}</Button>
  </Flex>;
}
