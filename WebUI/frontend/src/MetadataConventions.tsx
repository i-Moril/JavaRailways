import { useState } from 'react';
import { Alert,Button,ColorPicker,Flex,Input,Modal,Space,Typography } from 'antd';
import { EditableIdList } from './EditableIdSelect';
import { useI18n } from './i18n';
import { localizedNameLabel } from './metadata';
import { isLocalizedMetadata,metadataConventionFields,updateMetadataField,updateMetadataTranslation,type MetadataScope } from './metadataConventions';
import type { LineV1,MetadataObject,MetadataValue } from './types';

export function MetadataConventionsButton({scope}:{scope:MetadataScope}) {
  const {t}=useI18n();
  const [open,setOpen]=useState(false);
  return <><Button onClick={()=>setOpen(true)}>{t('metadata.conventions')}</Button>
    <Modal title={t(`metadata.scope.${scope}`)} open={open} onCancel={()=>setOpen(false)} footer={<Button onClick={()=>setOpen(false)}>{t('metadata.close')}</Button>} width={720}>
      <Typography.Paragraph>{t('metadata.conventionsHelp')}</Typography.Paragraph>
      <Typography.Paragraph type="secondary">examples/models/display-styles/cabin-route/displays/cabin-route.xml</Typography.Paragraph>
      {metadataConventionFields[scope].map((key)=><section key={key} className="metadata-convention-description">
        <Typography.Text strong>{t(`metadata.field.${key}`)} · <Typography.Text code>{key}</Typography.Text></Typography.Text>
        <Typography.Paragraph>{t(`metadata.description.${key}`)}</Typography.Paragraph>
      </section>)}
      <Typography.Paragraph>{t(`metadata.rules.${scope}`)}</Typography.Paragraph>
      <Typography.Paragraph type="secondary">{t('metadata.languageRule')}</Typography.Paragraph>
    </Modal></>;
}

export function MetadataConventionFields({value,onChange,scope,lines=[],fieldKey='',disabled=false}:{
  value:MetadataObject;onChange:(value:MetadataObject)=>void;scope:MetadataScope;lines?:LineV1[];fieldKey?:string;disabled?:boolean;
}) {
  const {t,locale}=useI18n();
  return <Flex vertical gap={12} className="metadata-convention-fields">
    <Typography.Text strong>{t('metadata.knownFields')}</Typography.Text>
    {metadataConventionFields[scope].map((key)=>{
      const current=value[key];
      const label=t(`metadata.field.${key}`);
      const change=(next:MetadataValue|undefined)=>onChange(updateMetadataField(value,key,next));
      const unsupported=<Typography.Text type="secondary">{t('metadata.editInJson')}</Typography.Text>;
      let control;
      if(key==='transfers') {
        control=current===undefined||Array.isArray(current)&&current.every((id)=>typeof id==='string')
          ?<EditableIdList fieldKey={`${fieldKey}/transfers`} aria-label={label} value={(current??[]) as string[]} disabled={disabled}
            options={lines.map((line)=>({value:line.id,label:`${localizedNameLabel(line.id,line.metadata?.name,locale)} (${line.id})`}))} onChange={change}/>
          :unsupported;
      } else if(key==='themeColor') {
        control=current===undefined||typeof current==='string'
          ?<Space.Compact block><Input aria-label={label} value={current??''} placeholder="#126EAA" disabled={disabled} onChange={(event)=>change(event.target.value)}/>
            <ColorPicker aria-label={t('metadata.pickColor')} value={typeof current==='string'&&/^#[0-9a-fA-F]{6}$/.test(current)?current:undefined} disabled={disabled} onChangeComplete={(color)=>change(color.toHexString())}/></Space.Compact>
          :unsupported;
      } else if(typeof current==='string') {
        control=<Flex gap={8} wrap><Input aria-label={label} value={current} disabled={disabled} onChange={(event)=>change(event.target.value)} style={{flex:'1 1 180px'}}/>
          <Button disabled={disabled} onClick={()=>change({zh:current})}>{t('metadata.bilingual')}</Button></Flex>;
      } else if(current===undefined||isLocalizedMetadata(current)) {
        control=<div className="metadata-translations">{(['zh','en'] as const).map((language)=><label key={language}>
          <span>{t(`metadata.language.${language}`)}</span>
          <Input aria-label={`${label} · ${t(`metadata.language.${language}`)}`} value={current?.[language] as string??''} disabled={disabled}
            onChange={(event)=>onChange(updateMetadataTranslation(value,key,language,event.target.value))}/>
        </label>)}</div>;
      } else control=unsupported;
      return <div key={key} className="metadata-convention-field"><Flex align="center" justify="space-between" gap={8}>
        <Typography.Text>{label} <Typography.Text code>{key}</Typography.Text></Typography.Text>
        <Button size="small" type="text" disabled={disabled||current===undefined} onClick={()=>change(undefined)}>{t('metadata.removeField')}</Button>
      </Flex>{control}</div>;
    })}
    <Alert type="info" showIcon title={t('metadata.fieldsHelp')}/>
  </Flex>;
}
