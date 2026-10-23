import {renderToStaticMarkup} from 'react-dom/server';
import type {ChangeEvent,KeyboardEvent,ReactNode} from 'react';
import {describe,expect,it,vi} from 'vitest';
import {DutyManagement} from './DutyManagement';
import {I18nProvider} from './i18n';
import {emptyAuthoringDocument} from './authoring';

interface DraftProps {
  value:string;
  onChange:(event:ChangeEvent<HTMLTextAreaElement>)=>void;
  onBlur:()=>void;
  onKeyDown:(event:KeyboardEvent<HTMLTextAreaElement>)=>void;
}
const captured=vi.hoisted(()=>({draft:undefined as DraftProps|undefined}));
vi.mock('antd',()=>{
  const container=({children}:{children?:ReactNode})=><div>{children}</div>;
  return {Button:container,Flex:container,Select:container,Space:container,Tag:container,
    Input:Object.assign(container,{TextArea:(props:DraftProps)=>{captured.draft=props;return <textarea readOnly value={props.value}/>;}}),
    Typography:{Title:container,Text:container,Paragraph:container}};
});

describe('operational trip defaults draft ownership',()=>{
  it('propagates invalid JSON through the duty view without writing stale defaults and recovers on Escape',()=>{
    const noop=()=>undefined,change=vi.fn(),validity=vi.fn();
    const document={...emptyAuthoringDocument(),operationalTrips:[{id:'trip',defaults:{destination:'E'},segments:[]}]};
    renderToStaticMarkup(<I18nProvider><DutyManagement document={document} onDocumentChange={change}
      onValidityChange={validity} duties={[]} runs={[]} selectedId="" metadata={{}} fieldScope="world/document"
      onSelect={noop} onAdd={noop} onDelete={noop} onPatch={noop} onAssign={noop}
      onAddFunction={noop} onPatchFunction={noop} onMove={noop} onRemove={noop} onViewRun={noop}/></I18nProvider>);
    const draft=captured.draft!;expect(draft).toBeDefined();
    draft.onChange({target:{value:'{"destination":'}} as ChangeEvent<HTMLTextAreaElement>);
    draft.onBlur();
    expect(validity).toHaveBeenLastCalledWith(false);
    expect(change).not.toHaveBeenCalled();
    const preventDefault=vi.fn();
    draft.onKeyDown({key:'Escape',preventDefault} as unknown as KeyboardEvent<HTMLTextAreaElement>);
    expect(preventDefault).toHaveBeenCalledOnce();
    expect(validity).toHaveBeenLastCalledWith(true);
    draft.onBlur();expect(change).not.toHaveBeenCalled();
    draft.onChange({target:{value:'{"destination":"D"}'}} as ChangeEvent<HTMLTextAreaElement>);
    draft.onBlur();
    expect(change).toHaveBeenCalledExactlyOnceWith({...document,operationalTrips:[{...document.operationalTrips[0],defaults:{destination:'D'}}]});
  });
});
