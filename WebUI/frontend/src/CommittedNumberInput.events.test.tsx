import { renderToStaticMarkup } from 'react-dom/server';
import type { InputNumberProps } from 'antd';
import type { KeyboardEvent } from 'react';
import { beforeEach,describe,expect,it,vi } from 'vitest';
import { CommittedNumberInput } from './CommittedNumberInput';
import { I18nProvider } from './i18n';

const captured=vi.hoisted(()=>({props:undefined as InputNumberProps<number|string>|undefined}));
// Model Ant's public event boundary. Browser checks cover its mounted DOM behavior.
vi.mock('antd',()=>({InputNumber:(props:InputNumberProps<number|string>)=>{captured.props=props;return <input readOnly value={props.value??''}/>;}}));
function mount(onCommit:(value:number|null)=>void,allowEmpty=true) {
  renderToStaticMarkup(<I18nProvider><CommittedNumberInput fieldKey="world/doc/run/call/time" min={0} max={23999} precision={0} allowEmpty={allowEmpty} value={1200} onCommit={onCommit}/></I18nProvider>);
  return captured.props!;
}
function enter() {
  const event={key:'Enter',nativeEvent:{isComposing:false},defaultPrevented:false,preventDefault:vi.fn(),stopPropagation:vi.fn()};
  captured.props!.onKeyDownCapture!(event as unknown as KeyboardEvent<HTMLInputElement>);
  return event;
}
describe('numeric draft event boundary',()=>{
  beforeEach(()=>{captured.props=undefined;});
  it('commits once before Ant can normalize Enter, then deduplicates blur',()=>{
    const commit=vi.fn(),props=mount(commit);
    props.onInput!('1250');expect(commit).not.toHaveBeenCalled();
    const event=enter();expect(event.preventDefault).toHaveBeenCalled();expect(event.stopPropagation).toHaveBeenCalled();
    expect(commit).toHaveBeenCalledExactlyOnceWith(1250);
    props.onBlur!({} as never);expect(commit).toHaveBeenCalledTimes(1);
    expect(props.onPressEnter).toBeUndefined();
  });
  it('rejects raw out-of-range or fractional ticks instead of accepting Ant’s clamp or rounding',()=>{
    const commit=vi.fn(),props=mount(commit);
    for(const raw of ['24001','1250.5','-']){props.onInput!(raw);const event=enter();expect(event.stopPropagation).toHaveBeenCalled();expect(commit).not.toHaveBeenCalled();}
  });
  it('clearing an optional time commits null only on blur',()=>{
    const commit=vi.fn(),props=mount(commit);
    props.onInput!('');expect(commit).not.toHaveBeenCalled();props.onBlur!({} as never);expect(commit).toHaveBeenCalledExactlyOnceWith(null);
  });
  it('keeps a required time unchanged when cleared',()=>{
    const commit=vi.fn(),props=mount(commit,false);props.onInput!('');enter();props.onBlur!({} as never);expect(commit).not.toHaveBeenCalled();
  });
});
