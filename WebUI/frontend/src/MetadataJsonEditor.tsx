import { Alert,Button,Flex,Input,Typography } from 'antd';
import { MetadataConventionFields,MetadataConventionsButton } from './MetadataConventions';
import type { MetadataScope } from './metadataConventions';
import type { LineV1 } from './types';
import { parseMetadata } from './metadata';
import { useI18n } from './i18n';

/** Raw text belongs to the calling document owner, so invalid drafts survive navigation. */
export function MetadataJsonEditor({draft,onChange,disabled=false,scope,lines,fieldKey,label}:{draft:string;onChange:(draft:string)=>void;disabled?:boolean;scope?:MetadataScope;lines?:LineV1[];fieldKey?:string;label?:string}) {
  const {t}=useI18n();
  const parsed=parseMetadata(draft);
  return <Flex vertical gap={8} style={{minWidth:0,width:'100%'}}>
    <Flex justify="space-between" align="center" gap={8} wrap><Typography.Text strong>{t('metadata.title')}</Typography.Text>{scope&&<MetadataConventionsButton scope={scope}/>}</Flex>
    <Typography.Text type="secondary">{t('metadata.help')}</Typography.Text>
    {scope&&!parsed.error&&<MetadataConventionFields value={parsed.value} scope={scope} lines={lines} fieldKey={fieldKey} disabled={disabled} onChange={(value)=>onChange(JSON.stringify(value,null,2))}/>}
    <Input.TextArea aria-label={label??t('metadata.title')} value={draft} onChange={(event)=>onChange(event.target.value)} disabled={disabled} spellCheck={false} autoSize={{minRows:7,maxRows:24}} style={{fontFamily:'monospace',minWidth:0}}/>
    {parsed.error&&<Alert type="error" showIcon title={t(`stations.${parsed.error}`)} description={parsed.detail}/>}
    <Button disabled={disabled||!!parsed.error} onClick={()=>{if(!parsed.error)onChange(JSON.stringify(parsed.value,null,2));}}>{t('stations.format')}</Button>
  </Flex>;
}
