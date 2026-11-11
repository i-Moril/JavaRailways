export type AutosaveState={status:'idle'|'pending'|'saving'|'saved'|'failed';error?:string};
interface Input<T>{scope:string;id:string;value:T;baseline:string;enabled:boolean}
/** Serial debounced persistence. The caller owns document identity and saved baselines. */
export class DocumentAutosave<T>{
  private input?:Input<T>;
  private timer?:ReturnType<typeof setTimeout>;
  private pending?:Promise<void>;
  private generation=0;
  private paused=0;
  private disposed=false;
  private failedSnapshot?:string;
  private assignedId='';
  constructor(private readonly options:{write:(id:string,value:T)=>Promise<{id:string}>;onSaved:(id:string,value:T)=>void;onState:(state:AutosaveState)=>void;delay?:number}){}
  update(input:Input<T>){
    if(this.disposed)return;
    if(this.input?.scope!==input.scope){this.generation++;this.assignedId=input.id;this.failedSnapshot=undefined;this.cancelTimer();}
    else if(input.id)this.assignedId=input.id;
    this.input=input;
    this.schedule();
  }
  private cancelTimer(){if(this.timer!==undefined)clearTimeout(this.timer);this.timer=undefined;}
  private schedule(){
    this.cancelTimer();
    const input=this.input;if(!input||this.disposed||this.paused||this.pending)return;
    const snapshot=JSON.stringify(input.value);
    if(snapshot===input.baseline){this.options.onState({status:'saved'});return;}
    if(!input.enabled){this.options.onState({status:'idle'});return;}
    if(snapshot===this.failedSnapshot)return;
    this.options.onState({status:'pending'});
    this.timer=setTimeout(()=>{this.timer=undefined;void this.write();},this.options.delay??1000);
  }
  private async write(){
    const input=this.input;if(!input||this.disposed||this.pending)return;
    const generation=this.generation,snapshot=JSON.stringify(input.value);
    this.options.onState({status:'saving'});
    const pending=(async()=>{
      try{
        const result=await this.options.write(this.assignedId,input.value);
        if(this.disposed||generation!==this.generation)return;
        this.assignedId=result.id;this.failedSnapshot=undefined;
        // Advance only the actual saved snapshot; edits made during the request stay dirty.
        if(this.input)this.input={...this.input,id:result.id,baseline:snapshot};
        this.options.onSaved(result.id,input.value);
        this.options.onState({status:JSON.stringify(this.input?.value)===snapshot?'saved':'pending'});
      }catch(error){
        if(this.disposed||generation!==this.generation)return;
        this.failedSnapshot=snapshot;
        this.options.onState({status:'failed',error:error instanceof Error?error.message:String(error)});
      }
    })();
    this.pending=pending;
    await pending;
    this.pending=undefined;this.schedule();
  }
  /** Mutating/manual commands must wait for any earlier write before acting. */
  async pause(){this.paused++;this.cancelTimer();await this.pending;}
  /** Save the latest committed snapshot before navigation. False keeps the editor open. */
  async flush():Promise<boolean>{
    this.cancelTimer();
    await this.pending;
    if(!this.input||this.disposed)return false;
    if(JSON.stringify(this.input.value)===this.input.baseline)return true;
    if(!this.input.enabled)return false;
    this.failedSnapshot=undefined;
    await this.write();
    return !this.disposed&&!!this.input&&JSON.stringify(this.input.value)===this.input.baseline;
  }
  resume(){this.paused=Math.max(0,this.paused-1);this.schedule();}
  /** Retry through the same queue; does not bypass a pending command. */
  retry(){this.failedSnapshot=undefined;this.schedule();}
  /** React StrictMode may replay an effect setup after its cleanup. */
  activate(){if(this.disposed){this.disposed=false;this.generation++;}}
  dispose(){this.disposed=true;this.generation++;this.cancelTimer();}
}
