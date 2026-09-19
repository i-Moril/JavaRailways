import React, { useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  App as AntApp,
  Badge,
  Button,
  ConfigProvider,
  Dropdown,
  Flex,
  Form,
  Input,
  InputNumber,
  Layout,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  theme,
  Tooltip,
  Typography,
} from 'antd';
import type { MenuProps, TableColumnsType } from 'antd';
import {
  ApartmentOutlined,
  AuditOutlined,
  BlockOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  EnvironmentOutlined,
  GlobalOutlined,
  FullscreenOutlined,
  LeftOutlined,
  MinusOutlined,
  MoonOutlined,
  MoreOutlined,
  NodeIndexOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ProjectOutlined,
  SaveOutlined,
  SunOutlined,
  RightOutlined,
  SwapOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  ToolOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import './styles.css';

type Locale = 'en' | 'zh';
type ThemeMode = 'light' | 'dark';
type RibbonTab = 'timetable' | 'map' | 'lines' | 'duties' | 'templates' | 'validate';
type InspectorTab = 'calls' | 'options' | 'issues';
type LineId = 'airport' | 'coastal' | 'connector';

type Call = {
  key: string;
  station: string;
  stationZh: string;
  platform: string;
  arrival: number;
  departure: number;
  operation: 'Stop' | 'Pass' | 'Turn back';
};

const airportCalls: Call[] = [
  { key: 'harbour', station: 'Harbour Terminal', stationZh: '海港总站', platform: '2', arrival: 500, departure: 540, operation: 'Stop' },
  { key: 'oldtown', station: 'Old Town', stationZh: '旧城区', platform: '1', arrival: 750, departure: 780, operation: 'Stop' },
  { key: 'central', station: 'Central', stationZh: '中央车站', platform: '4', arrival: 1010, departure: 1050, operation: 'Stop' },
  { key: 'university', station: 'University', stationZh: '大学城', platform: '2', arrival: 1290, departure: 1290, operation: 'Pass' },
  { key: 'airport', station: 'Airport T2', stationZh: '机场二号航站楼', platform: '3', arrival: 1580, departure: 1580, operation: 'Turn back' },
];

const coastalCalls: Call[] = [
  { key: 'marina', station: 'Marina South', stationZh: '南湾码头', platform: '1', arrival: 620, departure: 660, operation: 'Stop' },
  { key: 'museum', station: 'Maritime Museum', stationZh: '海事博物馆', platform: '2', arrival: 820, departure: 850, operation: 'Stop' },
  { key: 'central-c', station: 'Central', stationZh: '中央车站', platform: '6', arrival: 1040, departure: 1080, operation: 'Stop' },
  { key: 'cliff', station: 'Cliff Gardens', stationZh: '崖岸公园', platform: '1', arrival: 1320, departure: 1320, operation: 'Pass' },
  { key: 'northport', station: 'North Port', stationZh: '北港', platform: '2', arrival: 1540, departure: 1580, operation: 'Stop' },
];

const connectorCalls: Call[] = [
  { key: 'oldtown-l', station: 'Old Town', stationZh: '旧城区', platform: '3', arrival: 700, departure: 740, operation: 'Stop' },
  { key: 'market', station: 'Market Square', stationZh: '市集广场', platform: '1', arrival: 900, departure: 930, operation: 'Stop' },
  { key: 'university-l', station: 'University', stationZh: '大学城', platform: '4', arrival: 1160, departure: 1200, operation: 'Turn back' },
];

const copy = {
  en: {
    appName: 'Timetable Studio', workspace: 'Coastal Network · Draft 12', save: 'Save draft', saved: 'Saved just now',
    ribbon: ['Timetable', 'Rail map', 'Lines', 'Duties', 'Templates', 'Validate'],
    runLibrary: 'Runs', search: 'Search runs', direction: 'Direction', inbound: 'Inbound', outbound: 'Outbound',
    diagram: 'Running graph', calls: 'Station calls', options: 'Run options', issues: 'Issues', tick: 'Tick', clock: 'Day · time', both: 'Both',
    selected: 'Selected run',
    stationDetails: 'Call details', operation: 'Operation', platform: 'Platform / berth', arrival: 'Arrival', departure: 'Departure', actions: 'Actions at station',
    addAction: 'Add action', actionHint: 'e.g. open doors, play arrival animation', serviceType: 'Service type', stoppingPattern: 'Stopping pattern',
    vehicle: 'Vehicle formation', priority: 'Dispatch priority', express: 'Airport Express', allStops: 'All stops', cars: '6-car EMU', normal: 'Normal',
    noIssues: 'No blocking issues', warning: '1 warning', warningText: 'Tight 30-tick dwell at University', selectCall: 'Select a station call to edit its details',
    activeRun: 'AE 104', line: 'Airport Line', status: 'Draft', zoom: '08:00–08:32 · 25% zoom',
  },
  zh: {
    appName: '时刻表工作台', workspace: '滨海路网 · 草稿 12', save: '保存草稿', saved: '刚刚已保存',
    ribbon: ['时刻表', '轨道地图', '线路', '交路', '模板', '校验'],
    runLibrary: '运行线', search: '搜索运行线', direction: '方向', inbound: '上行', outbound: '下行',
    diagram: '运行图', calls: '停站时刻', options: '运行选项', issues: '问题', tick: '游戏刻', clock: '日 · 时间', both: '同时显示',
    selected: '当前运行线',
    stationDetails: '停站详情', operation: '停站类型', platform: '站台 / 停车点', arrival: '到达', departure: '发车', actions: '到站动作',
    addAction: '添加动作', actionHint: '例如：开门、播放到站动画', serviceType: '服务类型', stoppingPattern: '停车模式',
    vehicle: '车辆编组', priority: '调度优先级', express: '机场快线', allStops: '每站停车', cars: '6 节编组动车组', normal: '普通',
    noIssues: '没有阻断性问题', warning: '1 个警告', warningText: '大学城停站时间仅 30 游戏刻', selectCall: '选择一个车站以编辑详情',
    activeRun: 'AE 104', line: '机场线', status: '草稿', zoom: '08:00–08:32 · 缩放 25%',
  },
};

const lineData = {
  airport: { name: 'Airport Line', nameZh: '机场线', color: '#1677ff', calls: airportCalls, runs: [
    { id: 'AE 104', start: '08:00', end: '08:26', selected: true }, { id: 'AE 106', start: '08:08', end: '08:34' }, { id: 'AE 108', start: '08:16', end: '08:42' },
  ] },
  coastal: { name: 'Coastal Line', nameZh: '滨海线', color: '#13a8a8', calls: coastalCalls, runs: [
    { id: 'CL 221', start: '08:04', end: '08:31', selected: true }, { id: 'CL 223', start: '08:12', end: '08:39' }, { id: 'CL 225', start: '08:20', end: '08:47' },
  ] },
  connector: { name: 'Local Connector', nameZh: '接驳线', color: '#722ed1', calls: connectorCalls, runs: [
    { id: 'L 018', start: '08:06', end: '08:22', selected: true }, { id: 'L 020', start: '08:18', end: '08:34' },
  ] },
};

function timeLabel(tick: number) {
  const minutes = 8 * 60 + Math.round((tick - 500) / 40);
  return `D1 ${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

function Command({ icon, label, danger, onClick }: { icon: React.ReactNode; label: string; danger?: boolean; onClick?: () => void }) {
  return <Button className="ribbon-command" type="text" danger={danger} icon={icon} onClick={onClick}>{label}</Button>;
}

function CommandGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="command-group"><div className="command-items">{children}</div><span className="group-label">{label}</span></div>;
}

function Ribbon({ active, onChange, locale }: { active: RibbonTab; onChange: (v: RibbonTab) => void; locale: Locale }) {
  const isZh = locale === 'zh';
  const labels = copy[locale].ribbon;
  const content: Record<RibbonTab, React.ReactNode> = {
    timetable: <>
      <CommandGroup label={isZh ? '创建' : 'Create'}><Command icon={<PlusOutlined />} label={isZh ? '新建运行线' : 'New run'} /><Command icon={<CopyOutlined />} label={isZh ? '复制' : 'Duplicate'} /></CommandGroup>
      <CommandGroup label={isZh ? '移动' : 'Move'}><Command icon={<SwapOutlined />} label={isZh ? '提前' : 'Earlier'} /><Command icon={<SwapOutlined rotate={180} />} label={isZh ? '推迟' : 'Later'} /><Command icon={<ClockCircleOutlined />} label={isZh ? '精确偏移' : 'Exact offset'} /></CommandGroup>
      <CommandGroup label={isZh ? '对齐' : 'Align'}><Command icon={<NodeIndexOutlined />} label={isZh ? '对齐发车' : 'Align departures'} /><Command icon={<ApartmentOutlined />} label={isZh ? '均匀间隔' : 'Even headway'} /></CommandGroup>
      <CommandGroup label={isZh ? '编辑' : 'Edit'}><Command icon={<EditOutlined />} label={isZh ? '编辑详情' : 'Edit details'} /><Command icon={<DeleteOutlined />} label={isZh ? '删除' : 'Delete'} danger /></CommandGroup>
    </>,
    map: <>
      <CommandGroup label={isZh ? '地图' : 'Map'}><Command icon={<EnvironmentOutlined />} label={isZh ? '定位选择' : 'Locate selection'} /><Command icon={<FullscreenOutlined />} label={isZh ? '适应路网' : 'Fit network'} /></CommandGroup>
      <CommandGroup label={isZh ? '图层' : 'Layers'}><Command icon={<NodeIndexOutlined />} label={isZh ? '车站' : 'Stations'} /><Command icon={<ProjectOutlined />} label={isZh ? '轨道区段' : 'Track sections'} /><Command icon={<WarningOutlined />} label={isZh ? '状态覆盖' : 'Status overlay'} /></CommandGroup>
      <CommandGroup label={isZh ? '编辑' : 'Edit'}><Command icon={<PlusOutlined />} label={isZh ? '新建节点' : 'New node'} /><Command icon={<EditOutlined />} label={isZh ? '编辑连接' : 'Edit links'} /></CommandGroup>
    </>,
    lines: <>
      <CommandGroup label={isZh ? '线路' : 'Line'}><Command icon={<PlusOutlined />} label={isZh ? '新建线路' : 'New line'} /><Command icon={<EditOutlined />} label={isZh ? '编辑车站' : 'Edit stations'} /></CommandGroup>
      <CommandGroup label={isZh ? '基础设施' : 'Infrastructure'}><Command icon={<NodeIndexOutlined />} label={isZh ? '停车点' : 'Berths'} /><Command icon={<ProjectOutlined />} label={isZh ? '路径' : 'Paths'} /></CommandGroup>
    </>,
    templates: <>
      <CommandGroup label={isZh ? '模板' : 'Template'}><Command icon={<ThunderboltOutlined />} label={isZh ? '应用模板' : 'Apply template'} /><Command icon={<SaveOutlined />} label={isZh ? '另存为模板' : 'Save as template'} /></CommandGroup>
      <CommandGroup label={isZh ? '生成' : 'Generate'}><Command icon={<CalendarOutlined />} label={isZh ? '生成运行模式' : 'Generate pattern'} /><Command icon={<ApartmentOutlined />} label={isZh ? '设置间隔' : 'Set headway'} /></CommandGroup>
    </>,
    duties: <>
      <CommandGroup label={isZh ? '分配' : 'Assign'}><Command icon={<TeamOutlined />} label={isZh ? '分配交路' : 'Assign duty'} /><Command icon={<NodeIndexOutlined />} label={isZh ? '自动接续' : 'Auto-connect'} /></CommandGroup>
      <CommandGroup label={isZh ? '车辆' : 'Vehicle'}><Command icon={<ToolOutlined />} label={isZh ? '车辆编组' : 'Formation'} /><Command icon={<SwapOutlined />} label={isZh ? '交换车辆' : 'Swap vehicle'} /></CommandGroup>
    </>,
    validate: <>
      <CommandGroup label={isZh ? '检查' : 'Check'}><Command icon={<CheckCircleOutlined />} label={isZh ? '检查所选' : 'Check selected'} /><Command icon={<AuditOutlined />} label={isZh ? '检查整条线路' : 'Check line'} /></CommandGroup>
      <CommandGroup label={isZh ? '模拟' : 'Simulate'}><Command icon={<PlayCircleOutlined />} label={isZh ? '运行模拟' : 'Run simulation'} /><Command icon={<WarningOutlined />} label={isZh ? '查看问题' : 'Open issues'} /></CommandGroup>
    </>,
  };
  return <section className="ribbon">
    <Tabs className="ribbon-tabs" activeKey={active} onChange={v => onChange(v as RibbonTab)} items={(Object.keys(content) as RibbonTab[]).map((key, i) => ({ key, label: labels[i] }))} />
    <div className="ribbon-commands">{content[active]}</div>
  </section>;
}

function RunningGraph({ dark, locale, calls, color, runId }: { dark: boolean; locale: Locale; calls: Call[]; color: string; runId: string }) {
  const [pan, setPan] = useState({ x: 34, y: 24 });
  const [zoom, setZoom] = useState(0.92);
  const drag = useRef<{ pointerId: number; x: number; y: number; originX: number; originY: number } | null>(null);
  const stations = calls.map(call => locale === 'zh' ? call.stationZh : call.station);
  const ys = calls.map((_, index) => calls.length === 1 ? 250 : 70 + (360 * index) / (calls.length - 1));
  const selectedPoints = ys.map((y, index) => `${142 + (528 * index) / Math.max(calls.length - 1, 1)},${y}`).join(' ');
  const secondaryPoints = ys.map((y, index) => `${302 + (500 * index) / Math.max(calls.length - 1, 1)},${y}`).join(' ');
  const reversePoints = [...ys].reverse().map((y, index) => `${210 + (532 * index) / Math.max(calls.length - 1, 1)},${y}`).join(' ');
  const resetView = () => { setPan({ x: 34, y: 24 }); setZoom(0.92); };
  return <div
    className={`graph-wrap ${drag.current ? 'is-panning' : ''}`}
    onPointerDown={event => {
      if ((event.target as HTMLElement).closest('.canvas-controls')) return;
      event.currentTarget.setPointerCapture(event.pointerId);
      drag.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, originX: pan.x, originY: pan.y };
      event.currentTarget.classList.add('is-panning');
    }}
    onPointerMove={event => {
      if (!drag.current || drag.current.pointerId !== event.pointerId) return;
      setPan({ x: drag.current.originX + event.clientX - drag.current.x, y: drag.current.originY + event.clientY - drag.current.y });
    }}
    onPointerUp={event => { drag.current = null; event.currentTarget.classList.remove('is-panning'); event.currentTarget.releasePointerCapture(event.pointerId); }}
    onPointerCancel={event => { drag.current = null; event.currentTarget.classList.remove('is-panning'); }}
  >
    <div className="canvas-controls"><Button.Group><Tooltip title={locale === 'zh' ? '缩小' : 'Zoom out'}><Button icon={<MinusOutlined />} onClick={() => setZoom(value => Math.max(.55, value - .1))} /></Tooltip><Button className="zoom-readout">{Math.round(zoom * 100)}%</Button><Tooltip title={locale === 'zh' ? '放大' : 'Zoom in'}><Button icon={<PlusOutlined />} onClick={() => setZoom(value => Math.min(1.6, value + .1))} /></Tooltip><Tooltip title={locale === 'zh' ? '重置视图' : 'Reset view'}><Button icon={<FullscreenOutlined />} onClick={resetView} /></Tooltip></Button.Group></div>
    <div className="canvas-sheet" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}>
    <svg className="graph" viewBox="0 0 820 500" role="img" aria-label="Railway running graph">
      <defs>
        <pattern id="minor-grid" width="32" height="24" patternUnits="userSpaceOnUse"><path d="M 32 0 L 0 0 0 24" fill="none" stroke={dark ? '#253349' : '#e7edf5'} strokeWidth="1" /></pattern>
      </defs>
      <rect x="116" width="704" height="500" fill="url(#minor-grid)" />
      {ys.map((y, i) => <g key={y}><line x1="116" y1={y} x2="820" y2={y} stroke={dark ? '#42536d' : '#c9d4e3'} /><text x="104" y={y + 4} textAnchor="end" className="station-label">{stations[i]}</text></g>)}
      {['08:00','08:08','08:16','08:24','08:32'].map((v, i) => <text key={v} x={132 + i * 160} y="24" className="time-axis">{v}</text>)}
      <polyline points={selectedPoints} fill="none" stroke={color} strokeWidth="5" className="selected-path" />
      <polyline points={secondaryPoints} fill="none" stroke={color} strokeWidth="3" opacity=".58" />
      <polyline points={reversePoints} fill="none" stroke={color} strokeWidth="3" opacity=".72" />
      {ys.map((y, index) => { const x = 142 + (528 * index) / Math.max(calls.length - 1, 1); return <circle key={`${x}-${y}`} cx={x} cy={y} r="6" fill="#fff" stroke={color} strokeWidth="4" />; })}
      <g transform="translate(152 82)"><rect width="68" height="25" rx="5" fill={color} /><text x="34" y="17" textAnchor="middle" fill="#fff" fontWeight="700" fontSize="12">{runId}</text></g>
    </svg></div>
  </div>;
}

function WorkspacePage({ tab, locale }: { tab: Exclude<RibbonTab, 'timetable'>; locale: Locale }) {
  const zh = locale === 'zh';
  if (tab === 'map') return <Layout className="work-layout"><Layout.Content className="module-page map-page">
    <aside className="module-sidebar panel-surface">
      <div className="module-panel-title"><b>{zh ? '地图图层' : 'Map layers'}</b><Button type="text" icon={<MoreOutlined />} /></div>
      <label className="layer-row"><Switch size="small" defaultChecked /><span><i className="layer-dot station" />{zh ? '车站与停车点' : 'Stations & berths'}</span></label>
      <label className="layer-row"><Switch size="small" defaultChecked /><span><i className="layer-dot track" />{zh ? '轨道区段' : 'Track sections'}</span></label>
      <label className="layer-row"><Switch size="small" defaultChecked /><span><i className="layer-dot route" />{zh ? '运营线路' : 'Service lines'}</span></label>
      <label className="layer-row"><Switch size="small" /><span><i className="layer-dot issue" />{zh ? '问题与占用' : 'Issues & occupancy'}</span></label>
      <div className="map-legend"><Typography.Text type="secondary">{zh ? '运营线路' : 'Service lines'}</Typography.Text>{(Object.keys(lineData) as LineId[]).map(id => <span key={id}><Badge color={lineData[id].color} />{zh ? lineData[id].nameZh : lineData[id].name}</span>)}</div>
    </aside>
    <main className="rail-map panel-surface">
      <div className="map-toolbar"><Typography.Text strong>{zh ? '滨海路网' : 'Coastal Network'}</Typography.Text><Space><Tag>{zh ? '编辑模式' : 'Edit mode'}</Tag><Button.Group><Button icon={<MinusOutlined />} /><Button>78%</Button><Button icon={<PlusOutlined />} /><Button icon={<FullscreenOutlined />} /></Button.Group></Space></div>
      <div className="map-viewport"><svg viewBox="0 0 1000 610" className="network-map" role="img" aria-label="Rail network map">
        <path d="M95 430 C210 430 220 300 350 300 S520 180 650 180 S790 260 915 120" className="track-shadow" /><path d="M95 430 C210 430 220 300 350 300 S520 180 650 180 S790 260 915 120" className="map-line airport" />
        <path d="M100 125 C245 125 240 280 350 300 S540 450 720 450 S850 390 930 410" className="track-shadow" /><path d="M100 125 C245 125 240 280 350 300 S540 450 720 450 S850 390 930 410" className="map-line coastal" />
        <path d="M350 300 L515 330 L650 180" className="track-shadow" /><path d="M350 300 L515 330 L650 180" className="map-line connector" />
        {[[100,125,'Marina South'],[95,430,'Harbour Terminal'],[350,300,'Central'],[515,330,'Market Square'],[650,180,'University'],[720,450,'North Port'],[915,120,'Airport T2']].map(([x,y,name]) => <g key={String(name)} transform={`translate(${x} ${y})`}><circle r="11" className="map-node" /><circle r="4" className="map-node-core" /><text y="-18" textAnchor="middle">{name}</text></g>)}
      </svg></div>
      <div className="map-hint">{zh ? '拖动画布平移 · 滚轮缩放 · 选择节点查看详情' : 'Drag to pan · Wheel to zoom · Select a node for details'}</div>
    </main>
    <aside className="map-inspector panel-surface"><div className="module-panel-title"><b>{zh ? '中央车站' : 'Central'}</b><Tag color="green">{zh ? '正常' : 'Operational'}</Tag></div><div className="property-list"><span><small>{zh ? '类型' : 'Type'}</small><b>{zh ? '换乘站' : 'Interchange'}</b></span><span><small>{zh ? '停车点' : 'Berths'}</small><b>6</b></span><span><small>{zh ? '线路' : 'Lines'}</small><Space><Badge color="#1677ff" /><Badge color="#13a8a8" /><Badge color="#722ed1" /></Space></span></div><div className="inspector-section"><Typography.Text type="secondary">{zh ? '连接区段' : 'Connected sections'}</Typography.Text>{['CENTRAL_WEST','CENTRAL_EAST','CENTRAL_DEPOT'].map(name => <button className="resource-row" key={name}><NodeIndexOutlined /><span>{name}</span><RightOutlined /></button>)}</div></aside>
  </Layout.Content></Layout>;

  if (tab === 'lines') return <Layout className="work-layout"><Layout.Content className="module-page"><div className="page-heading"><div><Typography.Title level={3}>{zh ? '线路' : 'Lines'}</Typography.Title><Typography.Text type="secondary">{zh ? '管理每条线路自己的车站顺序、方向和基础设施路径。' : 'Manage each line’s own station order, directions, and infrastructure path.'}</Typography.Text></div><Button type="primary" icon={<PlusOutlined />}>{zh ? '新建线路' : 'New line'}</Button></div><div className="line-admin-grid"><section className="entity-list panel-surface">{(Object.keys(lineData) as LineId[]).map((id, index) => <button className={`entity-card ${index === 0 ? 'active' : ''}`} key={id}><i style={{ background: lineData[id].color }} /><span><b>{zh ? lineData[id].nameZh : lineData[id].name}</b><small>{lineData[id].calls.length} {zh ? '座车站' : 'stations'} · {lineData[id].runs.length} {zh ? '条运行线' : 'runs'}</small></span><RightOutlined /></button>)}</section><section className="line-detail panel-surface"><div className="detail-heading"><Space><span className="large-line-dot" /><div><Typography.Title level={4}>{zh ? '机场线' : 'Airport Line'}</Typography.Title><Typography.Text type="secondary">AIRPORT_LINE</Typography.Text></div></Space><Button icon={<EditOutlined />}>{zh ? '编辑属性' : 'Edit properties'}</Button></div><div className="station-sequence">{airportCalls.map((call, i) => <div className="sequence-row" key={call.key}><span className="sequence-index">{i + 1}</span><span className="sequence-track" /><div><b>{zh ? call.stationZh : call.station}</b><small>{zh ? `默认停车点 ${call.platform}` : `Default berth ${call.platform}`}</small></div><Button type="text" icon={<MoreOutlined />} /></div>)}</div></section></div></Layout.Content></Layout>;

  if (tab === 'duties') return <Layout className="work-layout"><Layout.Content className="module-page"><div className="page-heading"><div><Typography.Title level={3}>{zh ? '交路' : 'Duties'}</Typography.Title><Typography.Text type="secondary">{zh ? '将同一车辆执行的运行线连接为可检查的时间链。' : 'Connect runs performed by the same vehicle into inspectable timelines.'}</Typography.Text></div><Button type="primary" icon={<PlusOutlined />}>{zh ? '新建交路' : 'New duty'}</Button></div><div className="duty-board">{[['D-EMU-01','#1677ff',['AE 104','AE 109','AE 116']],['D-EMU-02','#13a8a8',['CL 221','CL 228','CL 235']],['D-SHUTTLE-04','#722ed1',['L 018','L 020','L 022']]].map(([name,color,runs], index) => <section className="duty-card panel-surface" key={String(name)}><div className="duty-title"><Space><Badge color={String(color)} /><b>{name}</b><Tag>{index === 2 ? (zh ? '4 节编组' : '4-car') : (zh ? '6 节编组' : '6-car')}</Tag></Space><Button type="text" icon={<MoreOutlined />} /></div><div className="duty-chain">{(runs as string[]).map((run, i) => <React.Fragment key={run}><div className="duty-run"><b>{run}</b><small>{`0${8 + i}:0${i * 2} → 0${8 + i}:2${i * 2}`}</small></div>{i < runs.length - 1 && <div className="turnaround"><SwapOutlined /><small>{zh ? '折返 12 分钟' : '12 min turn'}</small></div>}</React.Fragment>)}</div></section>)}</div></Layout.Content></Layout>;

  if (tab === 'templates') return <Layout className="work-layout"><Layout.Content className="module-page"><div className="page-heading"><div><Typography.Title level={3}>{zh ? '模板' : 'Templates'}</Typography.Title><Typography.Text type="secondary">{zh ? '保存可重复使用的时刻、停站和到站动作模式。' : 'Reusable timing, stopping, and station-action patterns.'}</Typography.Text></div><Button type="primary" icon={<PlusOutlined />}>{zh ? '新建模板' : 'New template'}</Button></div><div className="template-grid">{[[CalendarOutlined,'10-minute peak service','高峰期 10 分钟班次','Headway pattern'],[ApartmentOutlined,'Airport express calls','机场快线停站模式','Stopping pattern'],[TeamOutlined,'Six-run vehicle cycle','六班次车辆循环','Duty pattern'],[ThunderboltOutlined,'Terminal arrival actions','终点站到达动作','Action blocks']].map(([Icon,en,cn,type], i) => { const TIcon = Icon as typeof CalendarOutlined; return <button className="template-card panel-surface" key={String(en)}><span className={`template-icon tint-${i}`}><TIcon /></span><Tag>{String(type)}</Tag><b>{zh ? String(cn) : String(en)}</b><small>{zh ? '上次修改：昨天' : 'Modified yesterday'}</small><span className="template-actions"><span>{zh ? '应用' : 'Apply'}</span><MoreOutlined /></span></button>; })}</div></Layout.Content></Layout>;

  return <Layout className="work-layout"><Layout.Content className="module-page"><div className="page-heading"><div><Typography.Title level={3}>{zh ? '校验' : 'Validation'}</Typography.Title><Typography.Text type="secondary">{zh ? '发布前检查运行线、交路、时间冲突和世界引用。' : 'Check runs, duties, timing conflicts, and world references before publishing.'}</Typography.Text></div><Button type="primary" icon={<PlayCircleOutlined />}>{zh ? '运行全部检查' : 'Run all checks'}</Button></div><div className="validation-summary"><div className="metric panel-surface success"><CheckCircleOutlined /><span><b>42</b><small>{zh ? '通过检查' : 'Checks passed'}</small></span></div><div className="metric panel-surface warning"><WarningOutlined /><span><b>3</b><small>{zh ? '警告' : 'Warnings'}</small></span></div><div className="metric panel-surface"><AuditOutlined /><span><b>0</b><small>{zh ? '阻断错误' : 'Blocking errors'}</small></span></div></div><section className="validation-list panel-surface"><div className="module-panel-title"><b>{zh ? '检查结果' : 'Results'}</b><Segmented options={[zh ? '全部' : 'All', zh ? '警告' : 'Warnings', zh ? '通过' : 'Passed']} /></div>{[['warning','Tight turnaround on D-EMU-01','交路 D-EMU-01 折返时间较短','Duty'],['warning','University dwell is only 30 ticks','大学城停站时间仅 30 游戏刻','Timetable'],['success','All station references resolve','所有车站引用均有效','World'],['success','No conflicting duty assignments','没有冲突的交路分配','Duty']].map(([kind,en,cn,scope]) => <div className="validation-row" key={en}>{kind === 'warning' ? <WarningOutlined className="warn" /> : <CheckCircleOutlined className="ok" />}<span><b>{zh ? cn : en}</b><small>{scope}</small></span><Button>{zh ? '查看' : 'View'}</Button></div>)}</section></Layout.Content></Layout>;
}

function App() {
  const [locale, setLocale] = useState<Locale>('en');
  const [mode, setMode] = useState<ThemeMode>('light');
  const [ribbonTab, setRibbonTab] = useState<RibbonTab>(() => {
    if (typeof window === 'undefined') return 'timetable';
    const requested = new URLSearchParams(window.location.search).get('page');
    return requested && ['timetable', 'map', 'lines', 'duties', 'templates', 'validate'].includes(requested) ? requested as RibbonTab : 'timetable';
  });
  const [inspectorTab, setInspectorTab] = useState<InspectorTab>('calls');
  const [timeMode, setTimeMode] = useState<'tick' | 'clock' | 'both'>('both');
  const [selectedLine, setSelectedLine] = useState<LineId>('airport');
  const [selectedCallKey, setSelectedCallKey] = useState<string | null>(null);
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(false);
  const [callData, setCallData] = useState<Record<LineId, Call[]>>({ airport: airportCalls, coastal: coastalCalls, connector: connectorCalls });
  const t = copy[locale];
  const dark = mode === 'dark';
  const line = lineData[selectedLine];
  const activeRun = line.runs[0];
  const selectedCall = callData[selectedLine].find(call => call.key === selectedCallKey) ?? null;

  const updateCall = (key: string, patch: Partial<Call>) => setCallData(current => ({
    ...current,
    [selectedLine]: current[selectedLine].map(call => call.key === key ? { ...call, ...patch } : call),
  }));

  const columns: TableColumnsType<Call> = useMemo(() => [
    { title: locale === 'zh' ? '车站' : 'Station', dataIndex: locale === 'zh' ? 'stationZh' : 'station', ellipsis: true, width: 128, render: (value: string, row) => <Button type="link" className="station-link" onClick={() => setSelectedCallKey(row.key)}>{value}</Button> },
    { title: locale === 'zh' ? '通过方式' : 'Call', dataIndex: 'operation', width: 100, render: (value: Call['operation'], row) => <Select variant="borderless" value={value} onChange={operation => updateCall(row.key, { operation })} options={['Stop','Pass','Turn back'].map(v => ({ value: v, label: locale === 'zh' ? ({ Stop: '停车', Pass: '通过', 'Turn back': '折返' } as Record<string,string>)[v] : v }))} /> },
    { title: locale === 'zh' ? '停车点' : 'Berth', dataIndex: 'platform', width: 68, render: (value: string, row) => <Input variant="borderless" value={value} onChange={event => updateCall(row.key, { platform: event.target.value })} /> },
    { title: t.arrival, dataIndex: 'arrival', width: 92, render: (value: number, row) => <div className="editable-time"><InputNumber variant="borderless" value={value} controls={false} onChange={arrival => updateCall(row.key, { arrival: arrival ?? value })} />{timeMode !== 'tick' && <small>{timeLabel(value)}</small>}</div> },
    { title: t.departure, dataIndex: 'departure', width: 92, render: (value: number, row) => <div className="editable-time"><InputNumber variant="borderless" value={value} controls={false} onChange={departure => updateCall(row.key, { departure: departure ?? value })} />{timeMode !== 'tick' && <small>{timeLabel(value)}</small>}</div> },
  ], [locale, timeMode, selectedLine, callData, t.arrival, t.departure]);

  const languageMenu: MenuProps = { items: [{ key: 'en', label: 'English' }, { key: 'zh', label: '简体中文' }], onClick: ({ key }) => setLocale(key as Locale) };
  const inspectorItems = [
    { key: 'calls', label: t.calls },
    { key: 'options', label: t.options },
    { key: 'issues', label: <span>{t.issues} <Badge count={1} size="small" /></span> },
  ];

  return <ConfigProvider locale={locale === 'zh' ? zhCN : enUS} theme={{
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: { colorPrimary: '#1677ff', borderRadius: 7, fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif', colorBgLayout: dark ? '#0b1220' : '#edf2f7' },
    components: { Layout: { siderBg: dark ? '#101827' : '#ffffff' }, Table: { cellPaddingBlockSM: 10, cellPaddingInlineSM: 10 } },
  }}>
    <AntApp><Layout className={`app-shell ${dark ? 'is-dark' : ''}`}>
      <Layout.Header className="topbar">
        <Flex align="center" justify="space-between" gap={16}>
          <Flex align="center" gap={12} className="brand"><span className="brand-mark"><NodeIndexOutlined /></span><div><Typography.Text strong>{t.appName}</Typography.Text><Typography.Text type="secondary" className="workspace-name">{t.workspace}</Typography.Text></div></Flex>
          <Flex align="center" gap={8}>
            <Typography.Text type="secondary" className="saved-state"><CheckCircleOutlined /> {t.saved}</Typography.Text>
            <Button type="primary" icon={<SaveOutlined />}>{t.save}</Button>
            <Tooltip title={dark ? 'Light mode' : 'Dark mode'}><Switch checked={dark} onChange={v => setMode(v ? 'dark' : 'light')} checkedChildren={<MoonOutlined />} unCheckedChildren={<SunOutlined />} /></Tooltip>
            <Dropdown menu={languageMenu}><Button icon={<GlobalOutlined />}>{locale === 'zh' ? '简体中文' : 'English'} <DownOutlined /></Button></Dropdown>
          </Flex>
        </Flex>
      </Layout.Header>

      <Ribbon active={ribbonTab} onChange={setRibbonTab} locale={locale} />

      {ribbonTab === 'timetable' ? <Layout className="work-layout"><Layout.Content className="workspace-grid" style={{ gridTemplateColumns: `${leftCollapsed ? 46 : 238}px minmax(420px, 1fr) ${rightCollapsed ? 46 : 520}px` }}>
          {leftCollapsed ? <aside className="collapsed-panel panel-surface"><Tooltip title={locale === 'zh' ? '展开运行线面板' : 'Expand runs panel'} placement="right"><Button type="text" icon={<RightOutlined />} onClick={() => setLeftCollapsed(false)} /></Tooltip><span>{t.runLibrary}</span></aside> : <aside className="run-panel panel-surface">
            <div className="line-scope"><Typography.Text type="secondary">{locale === 'zh' ? '当前线路' : 'Current line'}</Typography.Text><Select value={selectedLine} onChange={(value: LineId) => { setSelectedLine(value); setSelectedCallKey(null); }} options={(Object.keys(lineData) as LineId[]).map(id => ({ value: id, label: <Space><Badge color={lineData[id].color} />{locale === 'zh' ? lineData[id].nameZh : lineData[id].name}</Space> }))} /></div>
            <Flex align="center" justify="space-between" className="panel-heading"><Typography.Title level={5}>{t.runLibrary}</Typography.Title><Space size={0}><Button size="small" type="text" icon={<PlusOutlined />} /><Tooltip title={locale === 'zh' ? '收起面板' : 'Collapse panel'}><Button size="small" type="text" icon={<LeftOutlined />} onClick={() => setLeftCollapsed(true)} /></Tooltip></Space></Flex>
            <div className="run-filters"><Input.Search placeholder={t.search} allowClear /><Segmented block size="small" options={[t.inbound, t.outbound]} /></div>
            <div className="run-list">{line.runs.map(run => <button key={run.id} className={`run-card ${run.selected ? 'selected' : ''}`}><span className="line-stripe" style={{ background: line.color }} /><span className="run-main"><b>{run.id}</b><small>{t.outbound}</small></span><span className="run-time">{run.start}<small>{run.end}</small></span></button>)}</div>
          </aside>}

          <main className="graph-panel panel-surface">
            <Flex className="graph-header" justify="space-between" align="center"><div><Typography.Title level={5}>{t.diagram}</Typography.Title><Space size={6}><Tag color={line.color}>{activeRun.id}</Tag><Typography.Text type="secondary">{locale === 'zh' ? line.nameZh : line.name}</Typography.Text></Space></div><Typography.Text type="secondary">{locale === 'zh' ? '拖动画布以平移' : 'Drag canvas to pan'}</Typography.Text></Flex>
            <RunningGraph dark={dark} locale={locale} calls={callData[selectedLine]} color={line.color} runId={activeRun.id} />
            <Flex className="graph-status" justify="space-between"><Badge color={line.color} text={locale === 'zh' ? line.nameZh : line.name} /><Typography.Text type="secondary">{locale === 'zh' ? '拖动运行线以平移 · Shift 多选' : 'Drag a run to move · Shift for multi-select'}</Typography.Text></Flex>
          </main>

          {rightCollapsed ? <aside className="collapsed-panel right panel-surface"><Tooltip title={locale === 'zh' ? '展开停站面板' : 'Expand calls panel'} placement="left"><Button type="text" icon={<LeftOutlined />} onClick={() => setRightCollapsed(false)} /></Tooltip><span>{t.calls}</span></aside> : <aside className="inspector-panel panel-surface">
            {selectedCall ? <div className="call-subpage">
              <div className="subpage-head"><Button type="text" onClick={() => setSelectedCallKey(null)}>← {locale === 'zh' ? '返回停站时刻' : 'Back to calls'}</Button><Space size={0}><Tooltip title={locale === 'zh' ? '收起面板' : 'Collapse panel'}><Button type="text" icon={<RightOutlined />} onClick={() => setRightCollapsed(true)} /></Tooltip><Button type="text" icon={<MoreOutlined />} /></Space></div>
              <div className="subpage-title"><Typography.Text type="secondary">{t.stationDetails}</Typography.Text><Typography.Title level={4}>{locale === 'zh' ? selectedCall.stationZh : selectedCall.station}</Typography.Title><Space><Tag>{activeRun.id}</Tag><Tag color={line.color}>{locale === 'zh' ? line.nameZh : line.name}</Tag></Space></div>
              <div className="automation-page"><Flex justify="space-between" align="center"><div><Typography.Title level={5}>{locale === 'zh' ? '到站动作' : 'Station actions'}</Typography.Title><Typography.Text type="secondary">{locale === 'zh' ? '列车到达此停车点时按顺序执行' : 'Executed in order when the train reaches this berth'}</Typography.Text></div><Button icon={<PlusOutlined />}>{t.addAction}</Button></Flex>
                <div className="block-canvas"><div className="logic-block trigger"><ThunderboltOutlined /> {locale === 'zh' ? '当列车到达' : 'When train arrives'} <b>{locale === 'zh' ? selectedCall.stationZh : selectedCall.station}</b></div><div className="block-connector" /><div className="logic-block action"><PlayCircleOutlined /> {locale === 'zh' ? '播放动画' : 'Play animation'} <b>platform_arrival</b></div><div className="block-connector" /><div className="logic-block wait"><ClockCircleOutlined /> {locale === 'zh' ? '等待' : 'Wait'} <b>20 ticks</b></div><button className="block-dropzone">＋ {locale === 'zh' ? '拖放或添加动作' : 'Drop or add an action'}</button></div>
              </div>
            </div> : <>
              <div className="compact-inspector-head"><Space><Typography.Text strong>{activeRun.id}</Typography.Text><Tag>{t.status}</Tag></Space><Space size={0}><Tooltip title={locale === 'zh' ? '收起面板' : 'Collapse panel'}><Button type="text" icon={<RightOutlined />} onClick={() => setRightCollapsed(true)} /></Tooltip><Button type="text" icon={<MoreOutlined />} /></Space></div>
              <Tabs className="inspector-tabs" activeKey={inspectorTab} onChange={v => setInspectorTab(v as InspectorTab)} items={inspectorItems} />
              <div className="inspector-body">
              {inspectorTab === 'calls' && <div className="calls-page">
                <Flex justify="space-between" align="center" className="time-mode"><Typography.Text type="secondary">{locale === 'zh' ? '直接编辑停站数据；点击车站名称进入动作页面' : 'Edit calls directly; select a station name for actions'}</Typography.Text><Segmented size="small" value={timeMode} onChange={v => setTimeMode(v as typeof timeMode)} options={[{ label: t.tick, value: 'tick' }, { label: t.both, value: 'both' }]} /></Flex>
                <Table<Call> className="calls-table" size="small" pagination={false} tableLayout="fixed" columns={columns} dataSource={callData[selectedLine]} />
              </div>}
              {inspectorTab === 'options' && <Form layout="vertical"><Form.Item label={t.serviceType}><Select defaultValue="express" options={[{ value: 'express', label: t.express }, { value: 'local', label: locale === 'zh' ? '普通客运' : 'Local service' }]} /></Form.Item><Form.Item label={t.stoppingPattern}><Select defaultValue="all" options={[{ value: 'all', label: t.allStops }, { value: 'limited', label: locale === 'zh' ? '大站快车' : 'Limited stop' }]} /></Form.Item><Form.Item label={t.vehicle}><Select defaultValue="emu6" options={[{ value: 'emu6', label: t.cars }, { value: 'emu8', label: locale === 'zh' ? '8 节编组动车组' : '8-car EMU' }]} /></Form.Item><Form.Item label={t.priority}><Select defaultValue="normal" options={[{ value: 'normal', label: t.normal }, { value: 'high', label: locale === 'zh' ? '优先' : 'High' }]} /></Form.Item></Form>}
              {inspectorTab === 'issues' && <div className="issues-view"><div className="issue-success"><CheckCircleOutlined /> <span><b>{t.noIssues}</b><small>{locale === 'zh' ? '可以保存并运行模拟' : 'Ready to save and simulate'}</small></span></div><div className="issue-warning"><WarningOutlined /><span><b>{t.warning}</b><small>{t.warningText}</small></span></div></div>}
              </div>
            </>}
          </aside>}
        </Layout.Content></Layout> : <WorkspacePage tab={ribbonTab} locale={locale} />}
    </Layout></AntApp>
  </ConfigProvider>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
