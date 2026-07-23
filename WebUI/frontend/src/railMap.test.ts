import {describe,expect,it} from 'vitest';
import {CONNECTED_ELEVATION_TOLERANCE_METERS,DESTINATION_MARKER_RADIUS,RAIL_CASING_WIDTH,RAIL_STROKE_WIDTH,STATION_MARKER_RADIUS,TRAIN_MARKER_RADIUS,clampMapZoom,interpolateTrainPosition,layoutMapLabels,railMapBounds,railSegmentVisualLayers,railSegmentsByVisualHeight,zoomMapViewport,MAX_MAP_LABELS,MAX_RAIL_MAP_ZOOM} from './railMap';
import type {RailMapFile,RailMapTrain} from './types';
const map:RailMapFile={formatVersion:1,world:'w',runtimeId:'r',generatedAtEpochMillis:0,markers:[],rails:[
  {id:'low',elevation:0,points:[{arc:0,x:0,y:0,z:0},{arc:10,x:10,y:0,z:0}]},
  {id:'high',elevation:10,points:[{arc:0,x:5,y:10,z:-5},{arc:10,x:5,y:10,z:5}]},
]};
const train:RailMapTrain={id:'t',revision:1,speedMetersPerSecond:1,derailed:false,reversed:false,railId:'low',arcLengthMeters:5,facing:'AB'};
describe('rail map projection',()=>{
  it('sorts lower segments first so higher segment casing erases crossings',()=>expect(railSegmentsByVisualHeight(map).map((segment)=>segment.railId)).toEqual(['low','high']));
  it('renders close elevations as one connected layer and distinct elevations as overpasses',()=>{
    const close:RailMapFile={...map,rails:[map.rails[0],{...map.rails[1],points:map.rails[1].points.map((point)=>({...point,y:CONNECTED_ELEVATION_TOLERANCE_METERS-.1}))}]};
    expect(railSegmentVisualLayers(close)).toHaveLength(1);
    expect(railSegmentVisualLayers(map).map((layer)=>layer.map((segment)=>segment.railId))).toEqual([['low'],['high']]);
  });
  it('keeps rail, marker, and train sizes in screen pixels independent of zoom',()=>{
    expect([RAIL_CASING_WIDTH,RAIL_STROKE_WIDTH,STATION_MARKER_RADIUS,DESTINATION_MARKER_RADIUS,TRAIN_MARKER_RADIUS]).toEqual([9,3,5,3,6]);
    expect(zoomMapViewport({zoom:1,offsetX:0,offsetY:0},2,{x:100,y:100})).toEqual({zoom:2,offsetX:-60,offsetY:-60});
  });
  it('interpolates train positions on sampled arc lengths',()=>expect(interpolateTrainPosition(map,train)).toMatchObject({x:5,z:0}));
  it('caps wheel zoom',()=>{expect(clampMapZoom(100)).toBe(MAX_RAIL_MAP_ZOOM);expect(clampMapZoom(0)).toBe(.5);});
  it('computes world bounds',()=>expect(railMapBounds(map)).toEqual({minX:0,maxX:10,minZ:-5,maxZ:5}));
  it('caps label work on large live maps',()=>{
    const labels=Array.from({length:MAX_MAP_LABELS+20},(_,index)=>({id:String(index).padStart(5,'0'),text:`label-${index}`,x:20+index*240,y:100,priority:index===MAX_MAP_LABELS+19?-1:0,color:'#000'}));
    const placed=layoutMapLabels(labels,labels.length*240+100,300);
    expect(placed).toHaveLength(MAX_MAP_LABELS);
    expect(placed.some((label)=>label.id===String(MAX_MAP_LABELS+19).padStart(5,'0'))).toBe(true);
  });
  it('measures and truncates long wide labels within collision bounds',()=>{
    const placed=layoutMapLabels([
      {id:'wide-a',text:'非常长的目的地标签'.repeat(20),x:280,y:80,priority:0,color:'#000'},
      {id:'wide-b',text:'另一个非常长的目的地标签'.repeat(20),x:280,y:80,priority:0,color:'#000'},
    ],300,180,(text)=>[...text].length*20);
    expect(placed).toHaveLength(2);
    expect(placed.every((label)=>label.text.endsWith('…')&&label.labelX>=2&&label.labelX+label.width<=298)).toBe(true);
  });
  it('places collocated destination labels without overlap',()=>{
    const placed=layoutMapLabels([
      {id:'a',text:'alpha@one',x:100,y:100,priority:0,color:'#000'},
      {id:'b',text:'alpha@two',x:100,y:100,priority:0,color:'#000'},
      {id:'c',text:'waypoint',x:102,y:101,priority:2,color:'#000'},
    ],400,300);
    expect(placed).toHaveLength(3);
    for(let a=0;a<placed.length;a++)for(let b=a+1;b<placed.length;b++){
      expect(placed[a].labelX+placed[a].width<=placed[b].labelX||placed[b].labelX+placed[b].width<=placed[a].labelX||placed[a].labelY+placed[a].height<=placed[b].labelY||placed[b].labelY+placed[b].height<=placed[a].labelY).toBe(true);
    }
  });
});
