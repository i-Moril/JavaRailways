import {createContext,useContext,useLayoutEffect,useMemo,useState,type ReactNode} from 'react';
export type Theme='dark'|'light';
const ThemeContext=createContext<{theme:Theme;setTheme:(theme:Theme)=>void}|null>(null);
export function initialTheme(storage?:string|null,prefersDark=true):Theme{return storage==='light'||storage==='dark'?storage:prefersDark?'dark':'light';}
export function ThemeProvider({children}:{children:ReactNode}){
  const [theme,setThemeState]=useState<Theme>(()=>initialTheme(typeof window==='undefined'?null:localStorage.getItem('javarailways.theme'),typeof window==='undefined'||window.matchMedia('(prefers-color-scheme: dark)').matches));
  useLayoutEffect(()=>{document.documentElement.dataset.theme=theme;document.documentElement.style.colorScheme=theme;localStorage.setItem('javarailways.theme',theme);},[theme]);
  const value=useMemo(()=>({theme,setTheme:setThemeState}),[theme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
export function useTheme(){const value=useContext(ThemeContext);if(!value)throw new Error('useTheme must be used inside ThemeProvider');return value;}
