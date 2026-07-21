import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'index',
    {
      type: 'category',
      label: '工程设计',
      collapsed: false,
      items: [
        'architecture/overview',
        'architecture/modules',
        'architecture/automatic-train-operation',
        'architecture/ato-roadmap',
        'architecture/timetable-operation-design',
        'architecture/timetable-operational-closure-checklist',
        'architecture/station-validation-checklist',
        'architecture/train-operation-validation-checklist',
        'architecture/control-transfer-validation-checklist',
        'architecture/threading-model',
        'architecture/persistence',
        'performance_assessment',
        {
          type: 'category',
          label: '架构决策记录',
          items: [
            'architecture/decisions/index',
            'architecture/decisions/documentation-system',
            'architecture/decisions/strict-model-config',
            'architecture/decisions/given-timetable-operation-scope',
          ],
        },
      ],
    },
    {
      type: 'category',
      label: '扩展插件开发',
      collapsed: false,
      items: ['extensions/overview', 'extensions/api-status', 'extensions/api-reference'],
    },
    {
      type: 'category',
      label: '列车模型包',
      collapsed: false,
      items: ['train_model_packages', 'train-model/schema-reference'],
    },
    {
      type: 'category',
      label: '时刻表',
      collapsed: false,
      items: ['in-game-timetable-validation'],
    },
  ],
};

export default sidebars;
