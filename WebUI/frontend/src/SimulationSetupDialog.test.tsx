// @vitest-environment jsdom
import {act,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {SimulationSetupDialog} from './SimulationSetupDialog';
import {I18nProvider} from './i18n';
vi.mock('antd',()=>({
 Modal:({children,title,onOk,onCancel,okText,cancelText,okButtonProps}:any)=><section role="dialog"><h1>{title}</h1>{children}<button onClick={onCancel}>{cancelText}</button><button onClick={onOk} disabled={okButtonProps.disabled}>{okText}</button></section>,
 Typography:{Text:({children}:any)=><span>{children}</span>},
 InputNumber:({onInput,onChange:_,onKeyDownCapture,onKeyDown,onBlur,value,changeOnBlur:__,stringMode:___,status:____,precision:_____,...props}:any)=><input {...props} value={value??''} onInput={event=>onInput(event.currentTarget.value)} onChange={()=>{}} onKeyDownCapture={onKeyDownCapture} onKeyDown={onKeyDown} onBlur={onBlur}/>
}));
let container:HTMLDivElement,root:Root;
const confirm=vi.fn(),close=vi.fn();
function Harness({line=false}:{line?:boolean}){
 const [value,setValue]=useState(60000),[open,setOpen]=useState(true),[scope,setScope]=useState('owner-a'),[result,setResult]=useState('completed result');
 return <I18nProvider><span data-accepted>{value}</span><span data-result>{result}</span><button onClick={()=>setOpen(true)}>Open</button><button onClick={()=>{setScope('owner-b');setOpen(false);}}>Change owner</button>{open&&<SimulationSetupDialog key={scope} fieldKey={`${scope}/setup`} line={line} value={value} disabled={false} onClose={()=>{close();setOpen(false);}} onConfirm={millis=>{confirm(millis);setValue(millis);setResult('');setOpen(false);}}/>}</I18nProvider>;
}
const button=(text:string)=>[...container.querySelectorAll('button')].find(item=>item.textContent===text)!;
const click=async(text:string)=>act(async()=>button(text).click());
const input=async(value:string)=>act(async()=>{const field=container.querySelector('input')!;field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));});
const blur=async()=>act(async()=>container.querySelector('input')!.dispatchEvent(new FocusEvent('focusout',{bubbles:true})));
beforeEach(()=>{vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);localStorage.clear();localStorage.setItem('javarailways.locale','en-US');container=document.createElement('div');document.body.appendChild(container);root=createRoot(container);vi.clearAllMocks();});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.unstubAllGlobals();});
describe('simulation confirmation setup',()=>{
 it('does not start work when opened, commits exact milliseconds only on confirmation',async()=>{
  await act(async()=>root.render(<Harness/>));expect(confirm).not.toHaveBeenCalled();expect(container.textContent).toContain('Simulation setup');
  await input('1.001');await blur();expect(container.querySelector('[data-accepted]')!.textContent).toBe('60000');expect(container.querySelector('[data-result]')!.textContent).toBe('completed result');
  await click('Start simulation');expect(confirm).toHaveBeenCalledExactlyOnceWith(1001);expect(container.querySelector('[data-accepted]')!.textContent).toBe('1001');expect(container.querySelector('[data-result]')!.textContent).toBe('');
 });
 it('cancels edited and invalid drafts without clearing a result or changing accepted settings',async()=>{
  await act(async()=>root.render(<Harness line/>));expect(container.textContent).toContain('Line simulation setup');
  await input('1.001');await blur();await click('Cancel');
  expect(container.querySelector('[data-accepted]')!.textContent).toBe('60000');expect(container.querySelector('[data-result]')!.textContent).toBe('completed result');expect(confirm).not.toHaveBeenCalled();
  await click('Open');await input('0');expect(button('Start simulation').disabled).toBe(true);await click('Start simulation');expect(confirm).not.toHaveBeenCalled();await click('Cancel');
  await click('Open');expect(button('Start simulation').disabled).toBe(false);expect(container.querySelector('input')!.value).toBe('60');
 });
 it('discards invalid owner-scoped drafts so the next owner can start normally',async()=>{
  await act(async()=>root.render(<Harness/>));await input('0');expect(button('Start simulation').disabled).toBe(true);await click('Change owner');await click('Open');
  expect(button('Start simulation').disabled).toBe(false);await click('Start simulation');expect(confirm).toHaveBeenCalledExactlyOnceWith(60000);
 });
});
