import { theme as antTheme, type ThemeConfig } from 'antd';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import type { Locale } from './i18n';
import type { Theme } from './theme';

export function antDesignLocale(locale:Locale) {
  return locale==='zh-CN'?zhCN:enUS;
}

export function antDesignTheme(theme:Theme):ThemeConfig {
  return {
    algorithm:theme==='dark'?antTheme.darkAlgorithm:antTheme.defaultAlgorithm,
    token:{
      colorPrimary:'#1677ff',
      borderRadius:7,
      fontFamily:'Inter, ui-sans-serif, system-ui, sans-serif',
    },
  };
}
