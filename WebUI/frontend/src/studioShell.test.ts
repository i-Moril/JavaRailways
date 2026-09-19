import {describe,expect,it} from 'vitest';
import {theme as antTheme} from 'antd';
import {antDesignLocale,antDesignTheme} from './studioShell';

describe('Ant Design shell bridge',()=>{
  it('maps both supported locales to Ant Design locale packs',()=>{
    expect(antDesignLocale('zh-CN').locale).toBe('zh-cn');
    expect(antDesignLocale('en-US').locale).toBe('en');
  });

  it('maps the existing theme preference to the matching Ant Design algorithm',()=>{
    expect(antDesignTheme('dark').algorithm).toBe(antTheme.darkAlgorithm);
    expect(antDesignTheme('light').algorithm).toBe(antTheme.defaultAlgorithm);
  });
});
