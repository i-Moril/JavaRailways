import {describe,expect,it} from 'vitest';
import {matchesIdOption} from './EditableIdSelect';
import {waypointOptions,directedWaypointOptions} from './idOptions';
import type {WorldMetadata} from './types';
const metadata:WorldMetadata={wayPoints:['kasaidpt@1','中央@1','undirected'],
 programSimulationWayPoints:[{id:'kasaidpt@1',railId:'r',direction:'BA',directionSource:'WAYPOINT'}],
 stations:[{stationId:'中央',stationName:'中央站',berthId:'1',destinationId:'中央@1'}],
 depotDetails:[{id:'kasaidpt',displayName:'葛西车辆段',stablingPoints:[{id:'1',destinationId:'kasaidpt@1'}]}]};
describe('editable ID candidates keep their actual domain',()=>{
 it('displays and filters the full waypoint IDs',()=>{
  const choices=waypointOptions(metadata);expect(choices).toEqual(metadata.wayPoints!.map(id=>({value:id,label:id})));
  expect(choices.filter(choice=>matchesIdOption('KASAIDPT@1',choice)).map(choice=>choice.value)).toEqual(['kasaidpt@1']);
  expect(choices.filter(choice=>matchesIdOption('中央@',choice)).map(choice=>choice.value)).toEqual(['中央@1']);
  expect(choices.filter(choice=>matchesIdOption('missing',choice))).toEqual([]);
 });
 it('limits simulation candidates to eligible waypoint IDs, including depot points',()=>{
  const choices=directedWaypointOptions(metadata);expect(choices).toEqual([{value:'kasaidpt@1',label:'kasaidpt@1'}]);
  expect(choices.filter(choice=>matchesIdOption('KASAIDPT',choice))).toHaveLength(1);
  expect(choices.some(choice=>choice.value==='undirected')).toBe(false);
 });
});
