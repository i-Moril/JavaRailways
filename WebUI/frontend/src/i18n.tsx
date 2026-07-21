import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

export type Locale = 'zh-CN' | 'en-US';

const zh = {
  'app.title': 'MineRails 时刻表编辑器',
  'app.subtitle': '创建、验证并发布列车运行图',
  'nav.primary': '主导航',
  'nav.graph': '运行图',
  'nav.lines': '线路',
  'nav.services': '车次与动作',
  'nav.simulation': '模拟',
  'common.world': '世界',
  'common.language': '语言',
  'common.draft': '草稿',
  'common.newDraft': '新建本地草稿',
  'common.save': '保存草稿',
  'common.delete': '删除',
  'common.validate': '验证',
  'common.publish': '发布',
  'common.add': '添加',
  'common.none': '无',
  'common.select': '请选择',
  'editor.timetableId': '时刻表 ID',
  'editor.periodDays': '周期（游戏日）',
  'editor.graph': '运行图',
  'editor.graphHint': '时间从左到右，车站和路径点从上到下；拖动节点可修改 Tick。',
  'editor.sidebarShow': '显示编辑栏',
  'editor.sidebarHide': '隐藏编辑栏',
  'editor.services': '车次与动作',
  'editor.addService': '新增车次',
  'editor.noServices': '尚未创建车次。',
  'editor.serviceId': '车次 ID',
  'editor.dynamicTrain': '车辆段动态配车',
  'editor.fixedTrain': '固定列车',
  'editor.trainId': '列车 ID',
  'editor.scheduled': '按时刻运行',
  'editor.manual': '手动启动',
  'editor.addAction': '新增动作',
  'editor.tick': 'Tick',
  'editor.actionType': '动作类型',
  'editor.target': '位置 / 目标',
  'editor.value': '参数',
  'editor.selectBerth': '选择站台',
  'editor.noIssues': '没有发现验证问题',
  'editor.afterArrival': '到站、关门后 / 下次发车前执行',
  'editor.stablingIds': '停车线 ID',
  'editor.arrival': '到站设置',
  'editor.beforeDeparture': '发车前设置',
  'editor.stoppingOffset': '停车偏移（米）',
  'editor.minimumDwell': '最短停站（Tick）',
  'editor.doorSide': '开门侧',
  'editor.defaultValue': '默认',
  'editor.viaPoints': '途经点',
  'lines.title': '线路管理',
  'lines.pending': '线路模型、车站选择和自动寻路接口正在此页面接入。',
  'lines.all': '全部线路',
  'lines.id': '线路 ID',
  'lines.name': '线路名称',
  'lines.color': '线路颜色',
  'lines.orderedStations': '有序车站',
  'lines.addStation': '添加车站',
  'lines.preview': '验证并预览路径',
  'lines.routeValid': '线路路径有效',
  'lines.routeInvalid': '线路路径无效',
  'simulation.title': '隔离列车模拟',
  'simulation.pending': '选择线路和列车参数后，将在隔离快照中计算区间运行耗时。',
  'simulation.line': '模拟线路',
  'simulation.maxSpeed': '最高速度（km/h）',
  'simulation.cars': '编组辆数',
  'simulation.poweredCars': '动力车数量',
  'simulation.carMass': '每辆质量（kg）',
  'simulation.tractionForce': '每动力车牵引力（N）',
  'simulation.tractionPower': '每动力车功率（W）',
  'simulation.brakeForce': '每辆制动力（N）',
  'simulation.run': '运行隔离模拟',
  'simulation.results': '模拟结果',
  'simulation.total': '总运行时间',
  'simulation.segment': '区间',
  'simulation.distance': '距离',
  'simulation.time': '耗时',
  'simulation.peakSpeed': '最高速度',
  'actions.CHECK': '确认当前位置',
  'actions.TAKE_FROM_DEPOT': '从车辆段取车',
  'actions.STORE_AT_DEPOT': '存入车辆段',
  'actions.DESTINATION': '运行至目标',
  'actions.DESTINATION_OPTIONS': '目标停车设置',
  'actions.STOP': '停车等待',
  'actions.HALT': '关闭列车',
  'actions.REVERSE': '换向',
  'actions.STATION_DESTINATION_OPTIONS': '车站停靠设置',
} as const;

type TranslationKey = keyof typeof zh;
const en: Record<TranslationKey, string> = {
  'app.title': 'MineRails Timetable Editor',
  'app.subtitle': 'Build, validate and publish a train running graph',
  'nav.primary': 'Primary navigation',
  'nav.graph': 'Running graph',
  'nav.lines': 'Lines',
  'nav.services': 'Services & actions',
  'nav.simulation': 'Simulation',
  'common.world': 'World',
  'common.language': 'Language',
  'common.draft': 'Draft',
  'common.newDraft': 'New local draft',
  'common.save': 'Save draft',
  'common.delete': 'Delete',
  'common.validate': 'Validate',
  'common.publish': 'Publish',
  'common.add': 'Add',
  'common.none': 'None',
  'common.select': 'Select',
  'editor.timetableId': 'Timetable ID',
  'editor.periodDays': 'Period (game days)',
  'editor.graph': 'Running graph',
  'editor.graphHint': 'Time runs left to right; stations and waypoints run top to bottom. Drag a point to change its Tick.',
  'editor.sidebarShow': 'Show editor',
  'editor.sidebarHide': 'Hide editor',
  'editor.services': 'Services & actions',
  'editor.addService': 'Add service',
  'editor.noServices': 'No services yet.',
  'editor.serviceId': 'Service ID',
  'editor.dynamicTrain': 'Dynamic depot train',
  'editor.fixedTrain': 'Fixed train',
  'editor.trainId': 'Train ID',
  'editor.scheduled': 'Scheduled',
  'editor.manual': 'Manual',
  'editor.addAction': 'Add action',
  'editor.tick': 'Tick',
  'editor.actionType': 'Action type',
  'editor.target': 'Location / target',
  'editor.value': 'Value',
  'editor.selectBerth': 'Select berth',
  'editor.noIssues': 'No validation issues',
  'editor.afterArrival': 'After arrival and door closing / before next departure',
  'editor.stablingIds': 'Stabling IDs',
  'editor.arrival': 'Arrival settings',
  'editor.beforeDeparture': 'Before departure',
  'editor.stoppingOffset': 'Stopping offset (m)',
  'editor.minimumDwell': 'Minimum dwell (ticks)',
  'editor.doorSide': 'Door side',
  'editor.defaultValue': 'Default',
  'editor.viaPoints': 'Via points',
  'lines.title': 'Line management',
  'lines.pending': 'Line persistence, station selection and automatic routing are being connected here.',
  'lines.all': 'All lines',
  'lines.id': 'Line ID',
  'lines.name': 'Line name',
  'lines.color': 'Line color',
  'lines.orderedStations': 'Ordered stations',
  'lines.addStation': 'Add station',
  'lines.preview': 'Validate and preview route',
  'lines.routeValid': 'Line route is valid',
  'lines.routeInvalid': 'Line route is invalid',
  'simulation.title': 'Isolated train simulation',
  'simulation.pending': 'Select a line and train profile to calculate segment travel times from an isolated snapshot.',
  'simulation.line': 'Simulation line',
  'simulation.maxSpeed': 'Maximum speed (km/h)',
  'simulation.cars': 'Car count',
  'simulation.poweredCars': 'Powered cars',
  'simulation.carMass': 'Mass per car (kg)',
  'simulation.tractionForce': 'Traction per powered car (N)',
  'simulation.tractionPower': 'Power per powered car (W)',
  'simulation.brakeForce': 'Brake force per car (N)',
  'simulation.run': 'Run isolated simulation',
  'simulation.results': 'Simulation results',
  'simulation.total': 'Total travel time',
  'simulation.segment': 'Segment',
  'simulation.distance': 'Distance',
  'simulation.time': 'Travel time',
  'simulation.peakSpeed': 'Peak speed',
  'actions.CHECK': 'Check current location',
  'actions.TAKE_FROM_DEPOT': 'Take from depot',
  'actions.STORE_AT_DEPOT': 'Store at depot',
  'actions.DESTINATION': 'Run to destination',
  'actions.DESTINATION_OPTIONS': 'Destination stop options',
  'actions.STOP': 'Wait stopped',
  'actions.HALT': 'Power off train',
  'actions.REVERSE': 'Reverse',
  'actions.STATION_DESTINATION_OPTIONS': 'Station call options',
};

export function normalizeLocale(value?: string | null): Locale {
  return value?.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US';
}

export function translate(locale: Locale, key: TranslationKey): string {
  return (locale === 'zh-CN' ? zh : en)[key];
}

interface I18nValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: TranslationKey) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

function initialLocale(): Locale {
  if (typeof window === 'undefined') return 'en-US';
  return normalizeLocale(window.localStorage.getItem('minerails.locale') || window.navigator.language);
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, updateLocale] = useState<Locale>(initialLocale);
  const value = useMemo<I18nValue>(() => ({
    locale,
    setLocale(next) {
      updateLocale(next);
      if (typeof window !== 'undefined') window.localStorage.setItem('minerails.locale', next);
    },
    t: (key) => translate(locale, key),
  }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
