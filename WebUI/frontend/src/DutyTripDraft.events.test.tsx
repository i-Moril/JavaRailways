import {renderToStaticMarkup} from 'react-dom/server';
import type {ChangeEvent,KeyboardEvent,ReactNode} from 'react';
import {describe,expect,it,vi} from 'vitest';
import {ProgramJsonInput} from './ProgramJsonInput';
import {updateDisplayPlanDefaults} from './displayPlans';
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
  return {Button:container,Flex:container,Select:container,Space:container,Tag:container,Collapse:({items}:{items:{key:string;children:ReactNode}[]})=><div>{items.map((item)=><div key={item.key}>{item.children}</div>)}</div>,
    Input:Object.assign(container,{TextArea:(props:DraftProps)=>{captured.draft=props;return <textarea readOnly value={props.value}/>;}}),
    Typography:{Title:container,Text:container,Paragraph:container}};
});

describe('continuous display defaults draft ownership',()=>{
  it('rejects invalid shared defaults without changing the plan and recovers on Escape',()=>{
    const change=vi.fn(),validity=vi.fn();
    const document={...emptyAuthoringDocument(),operationalTrips:[{id:'trip',defaults:{destination:'E'},segments:[]}]};
    renderToStaticMarkup(<I18nProvider><ProgramJsonInput fieldKey="world/document/plan/trip/defaults" value={document.operationalTrips[0].defaults} onValidityChange={validity} onChange={defaults=>change(updateDisplayPlanDefaults(document,'trip',defaults))}/></I18nProvider>);
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
