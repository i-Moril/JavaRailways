import { describe, expect, it } from 'vitest';
import { emptyAction, emptyTimetable, reducer } from './editor';

describe('editor reducer', () => {
  it('adds and edits services and actions immutably', () => {
    const initial = emptyTimetable();
    const withService = reducer(initial, { type:'ADD_SERVICE' });
    const withStep = reducer(withService, { type:'ADD_STEP', service:0, actionType:'STOP' });
    const edited = reducer(withStep, { type:'PATCH_STEP', service:0, step:2, tick:120, action:{ stopTicks:40 } });
    expect(initial.services).toHaveLength(0);
    expect(edited.services[0].steps[2]).toMatchObject({ tick:120, action:{ type:'STOP', stopTicks:40 } });
  });

  it('inserts a post-arrival operation without replacing neighboring route steps', () => {
    let state = reducer(emptyTimetable(), { type:'ADD_SERVICE' });
    state = reducer(state, {
      type:'INSERT_STEP', service:0, step:2,
      value:{ type:'AT', tick:100, action:emptyAction('REVERSE') },
    });
    expect(state.services[0].steps.map((step) => step.action?.type || step.type))
      .toEqual(['CHECK', 'DESTINATION', 'REVERSE']);
  });

  it('creates structurally valid defaults for action-specific required fields', () => {
    expect(emptyAction('STOP').stopTicks).toBe(20);
    expect(emptyAction('DESTINATION_OPTIONS').wayPointIds).toEqual(['']);
    expect(emptyAction('STATION_DESTINATION_OPTIONS')).toMatchObject({ stationId:'', berthId:'' });
  });

  it('deletes only the selected service', () => {
    let state = reducer(emptyTimetable(), { type:'ADD_SERVICE' });
    state = reducer(state, { type:'ADD_SERVICE' });
    state = reducer(state, { type:'DELETE_SERVICE', service:0 });
    expect(state.services.map((service) => service.id)).toEqual(['service-2']);
  });
});
