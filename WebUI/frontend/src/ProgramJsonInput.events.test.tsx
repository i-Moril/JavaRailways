import {renderToStaticMarkup} from 'react-dom/server';
import type {ChangeEvent,KeyboardEvent} from 'react';
import {beforeEach,describe,expect,it,vi} from 'vitest';
import {ProgramJsonInput} from './ProgramJsonInput';
import {I18nProvider} from './i18n';
import type {ProgramJsonObject} from './types';
interface CapturedProps {onChange:(event:ChangeEvent<HTMLTextAreaElement>)=>void;onBlur:()=>void;onKeyDown:(event:KeyboardEvent<HTMLTextAreaElement>)=>void;value:string}
const captured=vi.hoisted(()=>({props:undefined as CapturedProps|undefined}));
// Models the public Ant event boundary; browser acceptance covers mounted DOM behavior.
vi.mock('antd',()=>({Input:{TextArea:(props:CapturedProps)=>{captured.props=props;return <textarea readOnly value={props.value}/>;}},Typography:{Text:({children}:{children:React.ReactNode})=><span>{children}</span>,Paragraph:({children}:{children:React.ReactNode})=><p>{children}</p>}}));
function mount(value:ProgramJsonObject|undefined,onChange:(value:ProgramJsonObject)=>void,onValidityChange?:(valid:boolean)=>void){renderToStaticMarkup(<I18nProvider><ProgramJsonInput fieldKey="world/doc/call/payload" value={value} onChange={onChange} onValidityChange={onValidityChange}/></I18nProvider>);return captured.props!;}
const type=(props:CapturedProps,value:string)=>props.onChange({target:{value}} as ChangeEvent<HTMLTextAreaElement>);
function key(props:CapturedProps,key:string,ctrlKey=false){const event={key,ctrlKey,metaKey:false,preventDefault:vi.fn()};props.onKeyDown(event as unknown as KeyboardEvent<HTMLTextAreaElement>);return event;}
describe('whole-object JSON draft event boundary',()=>{
  beforeEach(()=>{captured.props=undefined;});
  it('keeps JSON typing local and commits once across CtrlEnter plus blur',()=>{
    const change=vi.fn(),props=mount({},change);type(props,'{"nested":[true,null,0.25]}');expect(change).not.toHaveBeenCalled();key(props,'Enter',true);props.onBlur();expect(change).toHaveBeenCalledExactlyOnceWith({nested:[true,null,0.25]});
  });
  it('rejects empty and nonobject drafts without replacing accepted data',()=>{
    const change=vi.fn(),valid=vi.fn(),props=mount({accepted:true},change,valid);for(const draft of ['', '[1]','{"n":-','{"n":9007199254740992}']){type(props,draft);props.onBlur();}expect(change).not.toHaveBeenCalled();expect(valid).toHaveBeenLastCalledWith(false);
  });
  it('Escape discards an invalid intermediate value before blur',()=>{
    const change=vi.fn(),valid=vi.fn(),props=mount({accepted:true},change,valid);type(props,'{"n":-');key(props,'Escape');props.onBlur();expect(change).not.toHaveBeenCalled();expect(valid).toHaveBeenLastCalledWith(true);
  });
  it('applies an empty object to a missing newly adopted parameter',()=>{
    const change=vi.fn(),props=mount(undefined,change);props.onBlur();props.onBlur();expect(change).toHaveBeenCalledExactlyOnceWith({});
  });
});
