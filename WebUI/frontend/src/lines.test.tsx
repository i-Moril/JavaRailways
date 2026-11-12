import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { LinesPage } from './App';
import { api } from './api';
import { I18nProvider } from './i18n';

const run = async <T,>(operation: () => Promise<T>): Promise<T | undefined> => operation();

describe('line editor', () => {
  it('edits station patterns without offering route validation', () => {
    const html = renderToStaticMarkup(
      <I18nProvider>
        <LinesPage
          world="world"
          metadata={{ stations: [
            { stationId: 'central', stationName: 'Central', berthId: '1', destinationId: 'central@1' },
            { stationId: 'central', stationName: 'Central', berthId: '2', destinationId: 'central@2' },
            { stationId: 'harbor', stationName: 'Harbor', berthId: '1', destinationId: 'harbor@1' },
          ] }}
          collection={{ revision: 0, lines: [{
            id:'line-1',metadata:{name:'Line 1'},color:'#1677ff',
            upStationIds:['central','harbor'],downStationIds:['harbor','central'],
          }] }}
          onCollection={() => undefined}
          run={run}
        />
      </I18nProvider>,
    );

    expect(html).toContain('Central');
    expect(html).not.toContain('Validate and preview route');
    expect(html).not.toContain('Line route is valid');
    expect(html).not.toContain('Line route is invalid');
  });

  it('does not expose a station-only route preview request', () => {
    expect('previewLine' in api).toBe(false);
  });
});
