import React, {useEffect} from 'react';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useBaseUrl from '@docusaurus/useBaseUrl';

type RootProps = Readonly<{children: React.ReactNode}>;

function PagefindSearch(): React.ReactNode {
  const baseUrl = useBaseUrl('/');
  const bundlePath = useBaseUrl('/pagefind/');

  useEffect(() => {
    const stylesheetId = 'pagefind-component-ui-stylesheet';
    if (!document.getElementById(stylesheetId)) {
      const stylesheet = document.createElement('link');
      stylesheet.id = stylesheetId;
      stylesheet.rel = 'stylesheet';
      stylesheet.href = `${bundlePath}pagefind-component-ui.css`;
      document.head.appendChild(stylesheet);
    }

    const scriptId = 'pagefind-component-ui-script';
    if (!document.getElementById(scriptId)) {
      const script = document.createElement('script');
      script.id = scriptId;
      script.type = 'module';
      script.src = `${bundlePath}pagefind-component-ui.js`;
      document.head.appendChild(script);
    }
  }, [bundlePath]);

  return (
    <div className="minerails-pagefind-search" data-pagefind-ignore="all">
      {React.createElement('pagefind-config', {
        'base-url': baseUrl,
        'bundle-path': bundlePath,
        lang: 'zh',
      })}
      {React.createElement('pagefind-modal-trigger')}
      {React.createElement('pagefind-modal')}
    </div>
  );
}

export default function Root({children}: RootProps): React.ReactNode {
  return (
    <>
      {children}
      <BrowserOnly>{() => <PagefindSearch />}</BrowserOnly>
    </>
  );
}
