import { useEffect, useRef, useState } from 'react';
import { graphScreenXToTick, graphServiceColors, layoutGraph, snapTick, zoomGraphViewport, type GraphViewport } from './graph';
import { useI18n } from './i18n';
import { useTheme } from './theme';
import type { LineV1, SavedTimetableV1, WorldMetadata } from './types';

type DragState =
  | { kind:'point'; service:number; step:number; pointKind:'DEPARTURE'|'ARRIVAL' }
  | { kind:'pan'; axis:'x'|'y'|'both'; x:number; y:number; offsetX:number; offsetY:number };

export function Graph({
  value,
  onTick,
  onSelect,
  metadata,
  line,
  graphLineId,
  readOnly = false,
}: {
  value: SavedTimetableV1;
  metadata?: WorldMetadata;
  line?: LineV1;
  graphLineId?: string;
  readOnly?: boolean;
  onTick: (service: number, step: number, tick: number, kind:'DEPARTURE'|'ARRIVAL') => void;
  onSelect?: (service: number, step: number) => void;
}) {
  const { t } = useI18n();
  const { theme } = useTheme();
  const surface = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const [size, setSize] = useState({ width: 900, height: 500 });
  const [viewport, setViewport] = useState<GraphViewport>({ scaleX:1, scaleY:1, offsetX:0, offsetY:0 });
  const [hoveredPoint,setHoveredPoint] = useState<string|null>(null);
  const drag = useRef<DragState | null>(null);
  const layout = layoutGraph(value, size.width, 58, size.height, metadata, line, graphLineId);
  const top = 54;
  const transformX = (x:number) => layout.left + (x - layout.left) * viewport.scaleX + viewport.offsetX;
  const transformY = (y:number) => top + (y - top) * viewport.scaleY + viewport.offsetY;
  const transformedPoints = layout.points.map((point) => ({ ...point, x:transformX(point.x), y:transformY(point.y) }));
  const pointKey=(point:{serviceIndex:number;stepIndex:number;kind:'DEPARTURE'|'ARRIVAL';tick:number;location:string})=>
    `${point.serviceIndex}:${point.stepIndex}:${point.kind}:${point.tick}:${point.location}`;

  useEffect(() => {
    const element = surface.current;
    if (!element) return;
    const resize = () => {
      const density=devicePixelRatio||1;
      const maximumCssSize=Math.floor(8192/density);
      setSize({
        width:Math.max(1,Math.min(maximumCssSize,element.clientWidth||900)),
        height:Math.max(1,Math.min(maximumCssSize,element.clientHeight||500)),
      });
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    const density = devicePixelRatio || 1;
    element.width = size.width * density;
    element.height = size.height * density;
    element.style.height = `${size.height}px`;
    const context = element.getContext('2d')!;
    context.scale(density, density);
    context.clearRect(0, 0, size.width, size.height);
    context.font = '12px system-ui';
    const styles=getComputedStyle(document.documentElement);
    const color=(name:string,fallback:string)=>styles.getPropertyValue(name).trim()||fallback;
    const grid=color('--canvas-grid','#30445f'),minorGrid=color('--canvas-grid-minor','#24364d');
    const label=color('--canvas-label','#a9bad0'),text=color('--canvas-text','#dbe8f7'),serviceColors=graphServiceColors(theme);
    context.save();
    context.beginPath();
    context.rect(0, 0, size.width, size.height);
    context.clip();

    const period = value.periodDays * 24_000;
    const tickInterval = viewport.scaleX >= 4 ? 500 : viewport.scaleX >= 2 ? 1_000 : viewport.scaleX < 0.75 ? 4_000 : 2_000;
    for (let tick = 0; tick <= period; tick += tickInterval) {
      const x = transformX(layout.tickToX(tick));
      if (x < layout.left || x > size.width) continue;
      context.strokeStyle = minorGrid;
      context.beginPath();
      context.moveTo(x, 25);
      context.lineTo(x, size.height);
      context.stroke();
      context.fillStyle = label;
      context.fillText(String(tick), x + 3, 18);
    }

    for (const lane of layout.lanes) {
      const y = transformY(lane.y);
      if (y < 30 || y > size.height) continue;
      context.fillStyle = label;
      context.fillText(lane.id, 8, y + 4);
      context.strokeStyle = grid;
      context.beginPath();
      context.moveTo(layout.left, y);
      context.lineTo(size.width, y);
      context.stroke();
    }

    for (const path of layout.paths) {
      const color = serviceColors[path.serviceIndex % serviceColors.length];
      const points = path.points.map((point) => ({ ...point, x:transformX(point.x), y:transformY(point.y) }));
      if (points.length > 1) {
        context.strokeStyle = color;
        context.lineWidth = 2;
        context.beginPath();
        points.forEach((point, index) => index === 0 ? context.moveTo(point.x, point.y) : context.lineTo(point.x, point.y));
        context.stroke();
      }
      for (const point of points) {
        if (point.x < layout.left - 8 || point.x > size.width + 8 || point.y < 22 || point.y > size.height + 8) continue;
        context.fillStyle = color;
        context.beginPath();
        context.arc(point.x, point.y, 7, 0, Math.PI * 2);
        context.fill();
        if(hoveredPoint===pointKey(point)){
          context.fillStyle = text;
          context.fillText(`${path.serviceId} · ${point.location} · ${point.tick}`, point.x + 9, point.y - 9);
        }
      }
    }
    context.restore();
  }, [layout, size.width, size.height, value, viewport, theme, hoveredPoint]);

  const position = (event: React.PointerEvent | React.WheelEvent) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    return { x:event.clientX - bounds.left, y:event.clientY - bounds.top };
  };
  const zoom = (axis:'x'|'y', factor:number, anchor?:{x:number;y:number}) => setViewport((current) =>
    zoomGraphViewport(current,axis,factor,axis==='x' ? anchor?.x ?? (layout.left+size.width)/2 : anchor?.y ?? (top+size.height)/2,axis==='x' ? layout.left : top));

  return <div ref={surface} className="graph-surface">
    <div className="graph-viewport-controls" aria-label={t('editor.graphViewport')}>
      <button title={t('editor.zoomXOut')} onClick={() => zoom('x', 0.8)}>X−</button>
      <button title={t('editor.zoomXIn')} onClick={() => zoom('x', 1.25)}>X+</button>
      <button title={t('editor.zoomYOut')} onClick={() => zoom('y', 0.8)}>Y−</button>
      <button title={t('editor.zoomYIn')} onClick={() => zoom('y', 1.25)}>Y+</button>
      <button title={t('editor.graphReset')} onClick={() => setViewport({ scaleX:1, scaleY:1, offsetX:0, offsetY:0 })}>1:1</button>
    </div>
    <canvas
      ref={canvas}
      className="graph"
      style={{ width:'100%' }}
      onWheel={(event) => {
        event.preventDefault();
        const pointer = position(event);
        if (pointer.x < layout.left) zoom('y', event.deltaY < 0 ? 1.12 : 0.89, pointer);
        else if (pointer.y < top) zoom('x', event.deltaY < 0 ? 1.12 : 0.89, pointer);
        else setViewport((current) => ({ ...current, offsetX:current.offsetX - event.deltaX, offsetY:current.offsetY - event.deltaY }));
      }}
      onPointerDown={(event) => {
        const pointer = position(event);
        if (!readOnly) {
          const hit = transformedPoints.find((point) => Math.hypot(point.x - pointer.x, point.y - pointer.y) < 13);
          if (hit) {
            drag.current = { kind:'point', service:hit.serviceIndex, step:hit.stepIndex, pointKind:hit.kind };
            onSelect?.(hit.serviceIndex, hit.stepIndex);
            event.currentTarget.setPointerCapture(event.pointerId);
            return;
          }
        }
        drag.current = {
          kind:'pan', axis:pointer.x < layout.left ? 'y' : pointer.y < top ? 'x' : 'both',
          x:pointer.x, y:pointer.y, offsetX:viewport.offsetX, offsetY:viewport.offsetY,
        };
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      onPointerMove={(event) => {
        const pointer = position(event);
        if(!drag.current){
          const hit=transformedPoints.find((point)=>Math.hypot(point.x-pointer.x,point.y-pointer.y)<13);
          setHoveredPoint(hit?pointKey(hit):null);
          return;
        }
        if (drag.current.kind === 'point') {
          onTick(drag.current.service, drag.current.step, snapTick(graphScreenXToTick(layout,viewport,pointer.x)), drag.current.pointKind);
          return;
        }
        const active = drag.current;
        setViewport((current) => ({
          ...current,
          offsetX:active.axis === 'y' ? current.offsetX : active.offsetX + pointer.x - active.x,
          offsetY:active.axis === 'x' ? current.offsetY : active.offsetY + pointer.y - active.y,
        }));
      }}
      onPointerUp={() => { drag.current = null; }}
      onPointerCancel={() => { drag.current = null; setHoveredPoint(null); }}
      onPointerLeave={() => { if(!drag.current)setHoveredPoint(null); }}
    />
  </div>;
}
