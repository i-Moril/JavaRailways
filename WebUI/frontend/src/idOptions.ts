import type {IdOption} from './EditableIdSelect';
import type {WorldMetadata} from './types';

export const idOptions=(ids:readonly string[]):IdOption[]=>ids.map(value=>({value,label:value}));
export const waypointOptions=(metadata:WorldMetadata):IdOption[]=>idOptions(metadata.wayPoints??[]);
export const directedWaypointOptions=(metadata:WorldMetadata):IdOption[]=>idOptions((metadata.programSimulationWayPoints??[]).map(point=>point.id));
