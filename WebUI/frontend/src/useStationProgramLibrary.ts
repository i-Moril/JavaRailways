import {useEffect,useRef,useState} from 'react';
import {api} from './api';
import type {StationProgramRecord} from './types';

/** Library resources are separate from the document's explicitly bound snapshots. */
export function useStationProgramLibrary(world:string,page:string,revision:number){
  const [resources,setResources]=useState<{world:string;records:StationProgramRecord[]}>({world:'',records:[]});
  const [error,setError]=useState(''),[loading,setLoading]=useState(false);
  const request=useRef(0);
  const refresh=async()=>{
    const token=++request.current;setLoading(true);setError('');
    try{const records=await api.stationPrograms(world);if(token===request.current)setResources({world,records});}
    catch(failure){if(token===request.current)setError(String(failure));}
    finally{if(token===request.current)setLoading(false);}
  };
  useEffect(()=>{setResources({world:'',records:[]});if(page!=='validation'&&world)void refresh();return()=>{request.current++;};},[world,page,revision]);
  return {records:resources.world===world?resources.records:[],error,loading,refresh};
}
