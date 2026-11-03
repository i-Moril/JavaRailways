import {useEffect,useRef,useState} from 'react';
import {api} from './api';
import type {StationProgramRecord} from './types';

/** Programs are owned by the active timetable and loaded only while that document exists. */
export function useTimetablePrograms(world:string,documentId:string,revision:number){
  const [resources,setResources]=useState<{world:string;documentId:string;records:StationProgramRecord[]}>({world:'',documentId:'',records:[]});
  const [error,setError]=useState(''),[loading,setLoading]=useState(false);
  const request=useRef(0);
  const refresh=async()=>{
    if(!world||!documentId){setResources({world,documentId,records:[]});return;}
    const token=++request.current;setLoading(true);setError('');
    try{const records=await api.timetablePrograms(world,documentId);if(token===request.current)setResources({world,documentId,records});}
    catch(failure){if(token===request.current)setError(String(failure));}
    finally{if(token===request.current)setLoading(false);}
  };
  useEffect(()=>{
    setResources({world:'',documentId:'',records:[]});
    if(world&&documentId)void refresh();
    return()=>{request.current++;};
  },[world,documentId,revision]);
  return {records:resources.world===world&&resources.documentId===documentId?resources.records:[],error,loading,refresh};
}
