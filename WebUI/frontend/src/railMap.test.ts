import {describe,expect,it} from 'vitest';
import {clampMapZoom,interpolateTrainPosition,railMapBounds,railSegmentsByVisualHeight,MAX_RAIL_MAP_ZOOM} from './railMap';
import type {RailMapFile,RailMapTrain} from './types';
const map:RailMapFile={formatVersion:1,world:'w',runtimeId:'r',generatedAtEpochMillis:0,markers:[],rails:[
  {id:'low',elevation:0,points:[{arc:0,x:0,y:0,z:0},{arc:10,x:10,y:0,z:0}]},
  {id:'high',elevation:10,points:[{arc:0,x:5,y:10,z:-5},{arc:10,x:5,y:10,z:5}]},
]};
const train:RailMapTrain={id:'t',revision:1,speedMetersPerSecond:1,derailed:false,reversed:false,railId:'low',arcLengthMeters:5,facing:'AB'};
describe('rail map projection',()=>{
  it('sorts lower segments first so higher segment casing erases crossings',()=>expect(railSegmentsByVisualHeight(map).map((segment)=>segment.railId)).toEqual(['low','high']));
  it('interpolates train positions on sampled arc lengths',()=>expect(interpolateTrainPosition(map,train)).toMatchObject({x:5,z:0}));
  it('caps wheel zoom',()=>{expect(clampMapZoom(100)).toBe(MAX_RAIL_MAP_ZOOM);expect(clampMapZoom(0)).toBe(.5);});
  it('computes world bounds',()=>expect(railMapBounds(map)).toEqual({minX:0,maxX:10,minZ:-5,maxZ:5}));
});
