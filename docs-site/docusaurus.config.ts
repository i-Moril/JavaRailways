import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const siteUrl = process.env.DOCS_URL ?? 'http://localhost:32000';
const baseUrl = process.env.DOCS_BASE_URL ?? '/';

const config: Config = {
  title: 'MineRails 文档',
  tagline: '铁路模拟、扩展开发、列车模型包与时刻表',
  url: siteUrl,
  baseUrl,
  trailingSlash: false,
  onBrokenLinks: 'throw',
  future: {
    v4: true,
  },
  i18n: {
    defaultLocale: 'zh-Hans',
    locales: ['zh-Hans'],
  },
  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],
  presets: [
    [
      'classic',
      {
        docs: {
          path: '../docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          showLastUpdateTime: true,
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],
  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'MineRails',
      items: [
        {type: 'docSidebar', sidebarId: 'docsSidebar', label: '文档', position: 'left'},
        {to: '/architecture/overview', label: '工程设计', position: 'left'},
        {to: '/extensions/overview', label: '扩展开发', position: 'left'},
        {to: '/train-model', label: '列车模型包', position: 'left'},
        {to: '/timetable', label: '时刻表', position: 'left'},
        {href: `${siteUrl}${baseUrl}api/current/index.html`, label: 'API Reference', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: '开发者',
          items: [
            {label: '工程设计', to: '/architecture/overview'},
            {label: '扩展插件开发', to: '/extensions/overview'},
          ],
        },
        {
          title: '内容创作',
          items: [
            {label: '列车模型包', to: '/train-model'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} MineRails contributors.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'kotlin', 'groovy'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
