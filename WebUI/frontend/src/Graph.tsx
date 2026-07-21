import { useEffect, useRef, useState } from 'react';
import { layoutGraph, snapTick } from './graph';
import type { SavedTimetableV1 } from './types';

const COLORS = ['#56c5ff', '#ffb454', '#8bd17c', '#d28cff', '#ff718b', '#54d6c5'];

export function Graph({
  value,
  onTick,
  onSelect,
}: {
  value: SavedTimetableV1;
  onTick: (service: number, step: number, tick: number) => void;
  onSelect?: (service: number, step: number) => void;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [width, setWidth] = useState(900);
  const drag = useRef<{ service: number; step: number } | null>(null);
  const layout = layoutGraph(value, width);

  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    const resize = () => setWidth(element.parentElement?.clientWidth || 900);
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(element.parentElement!);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    const density = devicePixelRatio || 1;
    element.width = layout.width * density;
    element.height = layout.height * density;
    element.style.height = `${layout.height}px`;
    const context = element.getContext('2d')!;
    context.scale(density, density);
    context.clearRect(0, 0, layout.width, layout.height);
    context.font = '12px system-ui';

    const period = value.periodDays * 24_000;
    for (let tick = 0; tick <= period; tick += 2_000) {
      const x = layout.tickToX(tick);
      context.strokeStyle = '#24364d';
      context.beginPath();
      context.moveTo(x, 25);
      context.lineTo(x, layout.height - 25);
      context.stroke();
      context.fillStyle = '#8296ad';
      context.fillText(String(tick), x + 3, 18);
    }

    for (const lane of layout.lanes) {
      context.fillStyle = '#a9bad0';
      context.fillText(lane.id, 8, lane.y + 4);
      context.strokeStyle = '#30445f';
      context.beginPath();
      context.moveTo(layout.left, lane.y);
      context.lineTo(width - layout.right, lane.y);
      context.stroke();
    }

    for (const path of layout.paths) {
      const color = COLORS[path.serviceIndex % COLORS.length];
      if (path.points.length > 1) {
        context.strokeStyle = color;
        context.lineWidth = 2;
        context.beginPath();
        path.points.forEach((point, index) => {
          if (index === 0) context.moveTo(point.x, point.y);
          else context.lineTo(point.x, point.y);
        });
        context.stroke();
      }
      for (const point of path.points) {
        context.fillStyle = color;
        context.beginPath();
        context.arc(point.x, point.y, 7, 0, Math.PI * 2);
        context.fill();
        context.fillStyle = '#dbe8f7';
        context.fillText(`${path.serviceId} · ${point.tick}`, point.x + 9, point.y - 9);
      }
    }
  }, [layout, value, width]);

  const position = (event: React.PointerEvent) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  };

  return <canvas
    ref={canvas}
    className="graph"
    style={{ width: '100%' }}
    onPointerDown={(event) => {
      const pointer = position(event);
      const hit = layout.points.find((point) => Math.hypot(point.x - pointer.x, point.y - pointer.y) < 13);
      if (!hit) return;
      drag.current = { service: hit.serviceIndex, step: hit.stepIndex };
      onSelect?.(hit.serviceIndex, hit.stepIndex);
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={(event) => {
      if (!drag.current) return;
      const pointer = position(event);
      onTick(drag.current.service, drag.current.step, snapTick(layout.xToTick(pointer.x)));
    }}
    onPointerUp={() => { drag.current = null; }}
  />;
}
