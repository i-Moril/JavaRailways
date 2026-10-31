import {describe,expect,it} from 'vitest';
import {matchesIdOption} from './EditableIdSelect';
import {waypointOptions,directedWaypointOptions,berthOptions,stablingPointOptions} from './idOptions';
import type {WorldMetadata} from './types';
const metadata:WorldMetadata={wayPoints:['kasaidpt@1','中央@1','undirected'],
 destinations:[{id:'undirected',displayName:'进库检查点'}],
 programSimulationWayPoints:[{id:'kasaidpt@1',displayName:'第一存车线',railId:'r',direction:'BA',directionSource:'WAYPOINT'}],
 stations:[{stationId:'中央',stationDisplayName:'中央站',berthId:'1',destinationId:'中央@1'}],
 depotDetails:[{id:'kasaidpt',displayName:'葛西车辆段',stablingPoints:[{id:'1',destinationId:'kasaidpt@1'}]}]};
describe('editable ID candidates keep their actual domain',()=>{
 it('filters waypoint suggestions by both canonical IDs and displayed names',()=>{
  const choices=waypointOptions(metadata);expect(choices.map(choice=>choice.value)).toEqual(metadata.wayPoints);
  expect(choices.filter(choice=>matchesIdOption('KASAIDPT@1',choice)).map(choice=>choice.value)).toEqual(['kasaidpt@1']);
  expect(choices.filter(choice=>matchesIdOption('第一存车线',choice)).map(choice=>choice.value)).toEqual(['kasaidpt@1']);
  expect(choices.filter(choice=>matchesIdOption('中央站',choice)).map(choice=>choice.value)).toEqual(['中央@1']);
  expect(choices.filter(choice=>matchesIdOption('进库检查点',choice)).map(choice=>choice.value)).toEqual(['undirected']);
  expect(choices.filter(choice=>matchesIdOption('missing',choice))).toEqual([]);
 });
 it('includes eligible depot destinations and their depot context without admitting undirected points',()=>{
  const choices=directedWaypointOptions(metadata);expect(choices).toEqual([{value:'kasaidpt@1',label:'第一存车线 · 葛西车辆段 · 1 · kasaidpt@1'}]);
  for(const search of ['kasaidpt@1','葛西车辆段','第一存车线'])expect(choices.filter(choice=>matchesIdOption(search,choice))).toHaveLength(1);
  expect(choices.some(choice=>choice.value==='undirected')).toBe(false);
 });
 it('keeps local berth and stabling point values scoped and never submits a destination ID instead',()=>{
  expect(berthOptions(metadata,'中央')).toEqual([{value:'1',label:'中央站 · 1'}]);expect(berthOptions(metadata,'other')).toEqual([]);
  expect(stablingPointOptions(metadata,'kasaidpt')).toEqual([{value:'1',label:'1 · kasaidpt@1'}]);expect(stablingPointOptions(metadata,'other')).toEqual([]);
 });
});
