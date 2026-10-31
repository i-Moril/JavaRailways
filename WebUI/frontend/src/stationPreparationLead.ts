import {evaluateProgramExpression,operationTypes} from './programExpressions';
import type {StationPreparationLead,StationProgramOperation} from './types';

export const preparationOperations=['ADD','SUBTRACT','MULTIPLY','DIVIDE','MODULO','MIN','MAX','ROUND','FLOOR','CEIL'] as const;
/** Validate every static subtree without inventing a value for the actual train duration. */
export function validatePreparationLead(expression:StationPreparationLead):void {
 let nodes=0;
 const safe=(n:unknown):n is number=>typeof n==='number'&&Number.isFinite(n)&&(!Number.isInteger(n)||Number.isSafeInteger(n));
 const fail=():never=>{throw new Error('Invalid preparation lead');};
 const visit=(node:StationPreparationLead,depth:number):number|undefined=>{
  if(!node||typeof node!=='object'||Array.isArray(node)||Object.keys(node).length!==1||depth>16||++nodes>4096)fail();
  if('doorCloseTicks' in node){if(node.doorCloseTicks!==true)fail();return;}
  if('value' in node){if(!safe(node.value))fail();return node.value;}
  const op=node.operation;
  if(!op||typeof op!=='object'||Object.keys(op).length!==2||!preparationOperations.includes(op.type)||!Array.isArray(op.operands)||op.operands.length!==operationTypes[op.type]?.inputs.length)fail();
  const values=op.operands.map(child=>visit(child,depth+1));
  if((op.type==='DIVIDE'||op.type==='MODULO')&&values[1]===0)fail();
  if(values.every(value=>value!==undefined))return evaluateProgramExpression({operation:{type:op.type as StationProgramOperation,operands:values.map(value=>({value}))}}, {}) as number;
 };
 const value=visit(expression,0);
 if(value!==undefined&&(!Number.isSafeInteger(value)||value<0))fail();
}
export const samePreparationLead=(a:StationPreparationLead|undefined,b:StationPreparationLead|undefined)=>JSON.stringify(a)===JSON.stringify(b);
