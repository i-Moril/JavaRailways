// @vitest-environment jsdom
import {act,StrictMode,useState} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {EditableIdSelect,EditableIdList,type IdOption} from './EditableIdSelect';
vi.mock('antd',()=>({
 AutoComplete:({value,onChange,onSelect,options,filterOption,defaultActiveFirstOption:_,allowClear:__,onClear:___,...props}:any)=><div><input {...props} value={value} onChange={()=>{}} onInput={event=>onChange(event.currentTarget.value)}/>{options.filter((option:any)=>filterOption(value,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange(option.value);onSelect(option.value);}}>{option.label}</button>)}</div>,
 Select:({value,onChange,onSelect,options,showSearch,mode:_,...props}:any)=><div>{value.map((id:string)=><span key={id}>{id}</span>)}<input {...props} value={showSearch.searchValue} onChange={()=>{}} onInput={event=>showSearch.onSearch(event.currentTarget.value)} onBlur={()=>{if(showSearch.searchValue&&!value.includes(showSearch.searchValue))onChange([...value,showSearch.searchValue]);}} onKeyDown={event=>{if(event.key==='Enter'&&showSearch.searchValue&&!value.includes(showSearch.searchValue))onChange([...value,showSearch.searchValue]);props.onKeyDown?.(event);}}/>{options.filter((option:any)=>showSearch.filterOption(showSearch.searchValue,option)).map((option:any)=><button key={option.value} onClick={()=>{onChange([...value,option.value]);onSelect?.(option.value);}}>{option.label}</button>)}</div>
}));
const choices:IdOption[]=[{value:'depot@1',label:'Depot track one'},{value:'station@1',label:'Central platform'}];
let container:HTMLDivElement,root:Root;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});container=document.createElement('div');document.body.append(container);root=createRoot(container);});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();});
const input=async(text:string)=>{const element=container.querySelector('input')!;await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(element,text);element.dispatchEvent(new Event('input',{bubbles:true}));});};
const key=async(value:string)=>{await act(async()=>container.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown',{key:value,bubbles:true})));};
const blur=async()=>{await act(async()=>container.querySelector('input')!.dispatchEvent(new FocusEvent('focusout',{bubbles:true})));};
describe('editable ID drafts',()=>{
 it('commits manually typed unknown IDs once on Enter plus blur and retains exact text',async()=>{
  const commit=vi.fn(),editing=vi.fn();await act(async()=>root.render(<StrictMode><EditableIdSelect fieldKey="owner/world/a" value="deleted@1" options={choices} onCommit={commit} onEditingChange={editing}/></StrictMode>));
  expect(container.querySelector('input')!.value).toBe('deleted@1');await input('custom@2');expect(commit).not.toHaveBeenCalled();expect(editing).toHaveBeenLastCalledWith(true);
  await key('Enter');await blur();expect(commit).toHaveBeenCalledExactlyOnceWith('custom@2');expect(editing).toHaveBeenLastCalledWith(false);
 });
 it('filters names and IDs, accepts an option with its ID, and does not duplicate selection on blur',async()=>{
  const commit=vi.fn();await act(async()=>root.render(<EditableIdSelect fieldKey="a" options={choices} onCommit={commit}/>));
  await input('Central');expect(container.querySelectorAll('button')).toHaveLength(1);expect(container.textContent).toContain('Central platform');
  await act(async()=>container.querySelector('button')!.click());await key('Enter');await blur();expect(commit).toHaveBeenCalledExactlyOnceWith('station@1');
  await input('DEPOT@');expect(container.querySelectorAll('button')).toHaveLength(1);expect(container.textContent).toContain('Depot track one');
 });
 it('cancels with Escape and isolates uncommitted text when owner or field changes',async()=>{
  const commit=vi.fn();const render=async(fieldKey:string)=>act(async()=>root.render(<EditableIdSelect fieldKey={fieldKey} value="origin" options={choices} onCommit={commit}/>));
  await render('world/a');await input('unfinished');await key('Escape');expect(container.querySelector('input')!.value).toBe('origin');await blur();expect(commit).not.toHaveBeenCalled();
  await input('old-world');const old=container.querySelector('input')!;await render('world/b');expect(container.querySelector('input')!.value).toBe('origin');await act(async()=>old.dispatchEvent(new FocusEvent('focusout',{bubbles:true})));expect(commit).not.toHaveBeenCalled();
 });
 it('keeps ordered multiple IDs, allows manual additions and cancels a pending candidate',async()=>{
  const change=vi.fn();function Harness(){const [value,setValue]=useState(['deleted']);return <EditableIdList fieldKey="world/list" options={choices} value={value} onChange={next=>{change(next);setValue(next);}}/>;}
  await act(async()=>root.render(<Harness/>));await input('new');await key('Enter');expect(change).toHaveBeenLastCalledWith(['deleted','new']);
  await input('cancelled');await key('Escape');expect(container.querySelector('input')!.value).toBe('');expect(change).toHaveBeenCalledTimes(1);
  await input('Central');expect(container.querySelectorAll('button')).toHaveLength(1);await act(async()=>container.querySelector('button')!.click());expect(change).toHaveBeenLastCalledWith(['deleted','new','station@1']);expect(container.querySelector('input')!.value).toBe('');await blur();expect(change).toHaveBeenCalledTimes(2);expect(container.textContent).not.toContain('cancelled');
 });
});
