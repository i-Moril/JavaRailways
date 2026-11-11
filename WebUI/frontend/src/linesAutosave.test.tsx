// @vitest-environment jsdom
import {act,StrictMode} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {LinesPage} from './LinesPage';
import {I18nProvider} from './i18n';
import {api} from './api';
import type {LineCollection,LineV1,SavedLineResult} from './types';

vi.mock('./api',()=>({api:{saveLine:vi.fn(),deleteLine:vi.fn()}}));
vi.mock('./CommittedNumberInput',()=>({CommittedNumberInput:()=>null}));
vi.mock('antd',()=>{
  const Wrapper=({children}:any)=><div>{children}</div>;
  const Input=Object.assign(({onChange,...props}:any)=><input {...props} onInput={onChange}/>,{TextArea:({onChange,autoSize:_,...props}:any)=><textarea {...props} onInput={onChange}/>});
  return {Alert:({title}:any)=><div role="alert">{title}</div>,Button:({children,icon:_,type:__,danger:___,...props}:any)=><button {...props}>{children}</button>,Flex:Wrapper,Space:Wrapper,
    Input,
    Select:({options,onChange,placeholder}:any)=><select aria-label={placeholder} defaultValue="" onChange={event=>onChange(event.target.value)}><option value=""/>{options.map((option:any)=><option value={option.value} key={option.value}>{option.label}</option>)}</select>,
    Typography:{Title:Wrapper,Text:({children,role,title}:any)=><span role={role} title={title}>{children}</span>}};
});

const line=(id:string):LineV1=>({id,displayName:id,upStationIds:['a','b'],downStationIds:['b','a']});
let root:Root,container:HTMLDivElement;
const initial:LineCollection={revision:4,lines:[line('alpha'),line('beta')]};
let collection:LineCollection;
const render=async(world='world',value=collection)=>{
  collection=value;
  await act(async()=>root.render(<StrictMode><I18nProvider><LinesPage world={world} metadata={{}} collection={collection} onCollection={next=>{collection=next;}} run={async operation=>operation()}/></I18nProvider></StrictMode>));
};
const nameInput=()=>container.querySelectorAll<HTMLInputElement>('.line-properties input')[0];
const typeName=async(value:string)=>{await act(async()=>{nameInput().value=value;nameInput().dispatchEvent(new Event('input',{bubbles:true}));});};
const click=async(text:string)=>{const element=[...container.querySelectorAll('button')].find(button=>button.textContent===text)!;await act(async()=>element.click());};
const tick=async(ms:number)=>{await act(async()=>{await vi.advanceTimersByTimeAsync(ms);});};
const deferred=()=>{let resolve!:(value:SavedLineResult)=>void;const promise=new Promise<SavedLineResult>(done=>resolve=done);return {promise,resolve};};

beforeEach(()=>{
  vi.useFakeTimers();vi.clearAllMocks();vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  collection=structuredClone(initial);container=document.createElement('div');document.body.append(container);root=createRoot(container);
  vi.mocked(api.saveLine).mockImplementation(async(_world,value,revision)=>({revision:revision+1,line:value}));
  vi.mocked(api.deleteLine).mockResolvedValue({revision:6,removed:true});
});
afterEach(async()=>{await act(async()=>root.unmount());container.remove();vi.useRealTimers();vi.unstubAllGlobals();});

describe('line editor autosave',()=>{
  it('does not write on initial load, selection, or an untouched new line',async()=>{
    await render();await tick(5000);
    await act(async()=>container.querySelectorAll<HTMLButtonElement>('.entity-card')[1].click());
    await click('New line');await tick(5000);
    expect(api.saveLine).not.toHaveBeenCalled();
  });

  it('debounces committed edits and retains edits typed during a write using the latest revision',async()=>{
    const request=deferred();vi.mocked(api.saveLine).mockReturnValueOnce(request.promise);
    await render();await typeName('first');await tick(999);expect(api.saveLine).not.toHaveBeenCalled();await tick(1);
    expect(api.saveLine).toHaveBeenLastCalledWith('world',expect.objectContaining({displayName:'first'}),4);
    await typeName('second');await act(async()=>request.resolve({revision:5,line:{...line('alpha'),displayName:'first'}}));
    expect(nameInput().value).toBe('second');await tick(1000);
    expect(api.saveLine).toHaveBeenLastCalledWith('world',expect.objectContaining({displayName:'second'}),5);
    expect(collection.revision).toBe(6);
  });

  it('flushes before immediate selection or new and leaves the new blank untouched',async()=>{
    await render();await typeName('changed');
    await act(async()=>container.querySelectorAll<HTMLButtonElement>('.entity-card')[1].click());
    expect(api.saveLine).toHaveBeenCalledOnce();expect(nameInput().value).toBe('beta');
    await typeName('changed beta');await click('New line');await tick(5000);
    expect(api.saveLine).toHaveBeenCalledTimes(2);expect(nameInput().value).toBe('Line 3');
  });

  it('keeps failed edits visible, blocks selection and offers manual retry without looping',async()=>{
    vi.mocked(api.saveLine).mockRejectedValueOnce(new Error('revision conflict'));
    await render();await typeName('unsaved');
    await act(async()=>container.querySelectorAll<HTMLButtonElement>('.entity-card')[1].click());
    expect(nameInput().value).toBe('unsaved');expect(container.textContent).toContain('revision conflict');
    await tick(10_000);expect(api.saveLine).toHaveBeenCalledOnce();
    await click('Retry save');expect(api.saveLine).toHaveBeenCalledTimes(2);expect(collection.revision).toBe(5);
  });

  it('waits for an in-flight save before deleting and cancels pending edits to prevent resurrection',async()=>{
    const request=deferred();vi.mocked(api.saveLine).mockReturnValueOnce(request.promise);
    await render();await typeName('first');await tick(1000);await typeName('second');
    await click('Delete');expect(api.deleteLine).not.toHaveBeenCalled();
    await act(async()=>request.resolve({revision:5,line:{...line('alpha'),displayName:'first'}}));
    expect(api.deleteLine).toHaveBeenCalledWith('world','alpha',5);await tick(10_000);
    expect(api.saveLine).toHaveBeenCalledOnce();expect(collection.lines.map(value=>value.id)).toEqual(['beta']);
  });

  it('cancels an unsent edit when deleting without recreating the deleted line',async()=>{
    await render();await typeName('pending');await click('Delete');await tick(10_000);
    expect(api.saveLine).not.toHaveBeenCalled();expect(api.deleteLine).toHaveBeenCalledWith('world','alpha',4);
    expect(nameInput().value).toBe('beta');
  });

  it('does not submit invalid committed edits or discard them on selection',async()=>{
    await render();await typeName('');await tick(5000);
    await act(async()=>container.querySelectorAll<HTMLButtonElement>('.entity-card')[1].click());
    expect(api.saveLine).not.toHaveBeenCalled();expect(nameInput().value).toBe('');
  });

  it('isolates late saves from a different world',async()=>{
    const request=deferred();vi.mocked(api.saveLine).mockReturnValueOnce(request.promise);
    await render();await typeName('old world');await tick(1000);
    await render('other',{revision:10,lines:[line('gamma')]});
    await act(async()=>request.resolve({revision:5,line:line('alpha')}));await tick(5000);
    expect(collection).toEqual({revision:10,lines:[line('gamma')]});expect(nameInput().value).toBe('gamma');
  });
});


it('retains malformed line metadata across blocked selection and saves custom JSON with the current revision',async()=>{
  await render();
  const text=()=>container.querySelector('textarea')!;
  const edit=async(value:string)=>{await act(async()=>{text().value=value;text().dispatchEvent(new Event('input',{bubbles:true}));});};
  await edit('{"transfers":');await tick(5000);
  await act(async()=>container.querySelectorAll<HTMLButtonElement>('.entity-card')[1].click());
  expect(api.saveLine).not.toHaveBeenCalled();expect(text().value).toBe('{"transfers":');expect(nameInput().value).toBe('alpha');
  await edit('{"transfers":[{"lineId":"beta"}],"custom":{"enabled":true}}');await tick(1000);
  expect(api.saveLine).toHaveBeenLastCalledWith('world',expect.objectContaining({metadata:{transfers:[{lineId:'beta'}],custom:{enabled:true}}}),4);
  await typeName('new name');await tick(1000);
  expect(api.saveLine).toHaveBeenLastCalledWith('world',expect.objectContaining({metadata:{transfers:[{lineId:'beta'}],custom:{enabled:true}}}),5);
});
