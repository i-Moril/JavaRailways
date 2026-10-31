import type {StationProgramLiteral,StationProgramOperation,StationProgramParameterType,StationProgramValue} from './types';
export const operationTypes:Record<StationProgramOperation,{inputs:StationProgramParameterType[];output:'NUMBER'|'STRING'}>={
 ADD:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},SUBTRACT:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},MULTIPLY:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},DIVIDE:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},MODULO:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},MIN:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},MAX:{inputs:['NUMBER','NUMBER'],output:'NUMBER'},ROUND:{inputs:['NUMBER'],output:'NUMBER'},FLOOR:{inputs:['NUMBER'],output:'NUMBER'},CEIL:{inputs:['NUMBER'],output:'NUMBER'},CONCAT:{inputs:['STRING','STRING'],output:'STRING'},SUBSTRING:{inputs:['STRING','NUMBER','NUMBER'],output:'STRING'},REPLACE:{inputs:['STRING','STRING','STRING'],output:'STRING'},TEXT_LENGTH:{inputs:['STRING'],output:'NUMBER'},TO_STRING:{inputs:['NUMBER'],output:'STRING'},TO_NUMBER:{inputs:['STRING'],output:'NUMBER'},
};
export const numericTypes=['NUMBER','TICK','DURATION'];
export const compatibleProgramType=(actual:StationProgramParameterType,expected:StationProgramParameterType)=>actual===expected||numericTypes.includes(actual)&&numericTypes.includes(expected);
export const programOutputChecks=(type:StationProgramParameterType)=>numericTypes.includes(type)?numericTypes:type;
const fail=():never=>{throw new Error('Invalid program expression');};
const safeNumber=(value:unknown):value is number=>typeof value==='number'&&Number.isFinite(value)&&(!Number.isInteger(value)||Number.isSafeInteger(value));
const plainNumber=(n:number)=>{if(Object.is(n,-0))return '0';const text=String(n);if(!/[eE]/.test(text))return text;const [coefficient,power]=text.split('e');const negative=coefficient.startsWith('-');const [whole,fraction='']=coefficient.replace('-','').split('.');const digits=whole+fraction;const decimal=whole.length+Number(power);return (negative?'-':'')+(decimal<=0?'0.'+'0'.repeat(-decimal)+digits:decimal>=digits.length?digits+'0'.repeat(decimal-digits.length):digits.slice(0,decimal)+'.'+digits.slice(decimal));};
export function evaluateProgramExpression(value:StationProgramValue<StationProgramLiteral>,args:Record<string,StationProgramLiteral>):StationProgramLiteral {
 let nodes=0;
 const visit=(expression:StationProgramValue<StationProgramLiteral>,depth:number):StationProgramLiteral=>{
  if(depth>16||++nodes>4096||!expression||typeof expression!=='object'||Array.isArray(expression)||Object.keys(expression).length!==1)fail();
  if('value' in expression)return expression.value;
  if('parameterId' in expression){if(!Object.hasOwn(args,expression.parameterId))fail();return args[expression.parameterId];}
  if('object' in expression){if(!expression.object||typeof expression.object!=='object'||Array.isArray(expression.object))fail();const entries=Object.entries(expression.object);if(entries.length>128||entries.some(([key])=>!key.trim()||key.length>256))fail();return Object.fromEntries(entries.map(([key,item])=>[key,visit(item,depth+1)]));}
  const operation=expression.operation;const specification=Object.hasOwn(operationTypes,operation?.type)?operationTypes[operation.type]:undefined;if(!specification||Object.keys(operation).length!==2||!Array.isArray(operation.operands)||operation.operands.length!==specification.inputs.length)fail();
  const values=operation.operands.map((item,index)=>{const v=visit(item,depth+1);if(specification!.inputs[index]==='NUMBER'?!safeNumber(v):typeof v!=='string'||v.length>8192)fail();return v;});
  const a=values[0] as number,b=values[1] as number;const text=values[0] as string;let result:number|string;
  switch(operation.type){
   case 'ADD':result=a+b;break;case 'SUBTRACT':result=a-b;break;case 'MULTIPLY':result=a*b;break;case 'DIVIDE':if(b===0)fail();result=a/b;break;case 'MODULO':if(b===0)fail();result=a%b;break;case 'MIN':result=Math.min(a,b);break;case 'MAX':result=Math.max(a,b);break;case 'ROUND':result=Math.round(a);break;case 'FLOOR':result=Math.floor(a);break;case 'CEIL':result=Math.ceil(a);break;
   case 'CONCAT':result=text+(values[1] as string);break;case 'TEXT_LENGTH':result=text.length;break;
   case 'SUBSTRING':{const start=values[1] as number,end=values[2] as number;if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||end<start||end>text.length)fail();result=text.slice(start,end);break;}
   case 'REPLACE':{const search=values[1] as string,replacement=values[2] as string;if(!search)fail();let matches=0,position=0;while((position=text.indexOf(search,position))!==-1){matches++;position+=search.length;}if(text.length+matches*(replacement.length-search.length)>8192)fail();result=text.split(search).join(replacement);break;}
   case 'TO_STRING':result=plainNumber(a);break;case 'TO_NUMBER':{const input=text.trim();if(!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(input))fail();result=Number(input);break;}
  }
  if(typeof result==='number'?!safeNumber(result):result.length>8192)fail();return result;
 };
 return visit(value,0);
}
export function validateProgramExpression(value:StationProgramValue<StationProgramLiteral>,expected:StationProgramParameterType,parameters:Map<string,StationProgramParameterType>,literalValid:(value:unknown,type:StationProgramParameterType)=>boolean,args?:Record<string,StationProgramLiteral>):void {
 let nodes=0;
 const visit=(expression:StationProgramValue<StationProgramLiteral>,type:StationProgramParameterType,depth:number):boolean=>{
  if(depth>16||++nodes>4096||!expression||typeof expression!=='object'||Array.isArray(expression)||Object.keys(expression).length!==1)fail();
  if('value' in expression){if(!literalValid(expression.value,type))fail();return true;}
  if('parameterId' in expression){const actual=parameters.get(expression.parameterId);if(!actual||!compatibleProgramType(actual,type))fail();if(args&&!literalValid(args[expression.parameterId],type))fail();return !!args;}
  if('object' in expression){if(type!=='JSON_OBJECT'||!expression.object||typeof expression.object!=='object'||Array.isArray(expression.object))fail();const entries=Object.entries(expression.object);if(entries.length>128||entries.some(([key])=>!key.trim()||key.length>256))fail();let bound=true;for(const [,child] of entries){if('value' in child){if(depth+1>16||++nodes>4096||Object.keys(child).length!==1||!literalValid({data:child.value},'JSON_OBJECT'))fail();}else {const childBound=visit(child,infer(child),depth+1);bound=childBound&&bound;}}if(bound&&!literalValid(evaluateProgramExpression(expression,args??{}),type))fail();return bound;}
  const op=expression.operation;if(!op||typeof op!=='object'||Object.keys(op).length!==2||!Array.isArray(op.operands))fail();const spec=Object.hasOwn(operationTypes,op.type)?operationTypes[op.type]:undefined;if(!spec||!compatibleProgramType(spec.output,type)||op.operands.length!==spec.inputs.length)fail();let bound=true;op.operands.forEach((item,index)=>{const childBound=visit(item,spec!.inputs[index],depth+1);bound=childBound&&bound;});if(bound&&!literalValid(evaluateProgramExpression(expression,args??{}),type))fail();return bound;
 };
 const infer=(expression:StationProgramValue<StationProgramLiteral>):StationProgramParameterType=>'operation' in expression?(Object.hasOwn(operationTypes,expression.operation.type)?operationTypes[expression.operation.type].output:fail()):'object' in expression?'JSON_OBJECT':'parameterId' in expression?parameters.get(expression.parameterId)??fail():typeof expression.value==='number'?'NUMBER':typeof expression.value==='string'?'STRING':Array.isArray(expression.value)?'ID_LIST':'JSON_OBJECT';
 visit(value,expected,0);
}
