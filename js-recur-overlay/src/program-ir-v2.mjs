import {isIdentifier} from './portable-unicode-identifier-v1.mjs';
/**
 * Strict JSON-safe resolved IR contract for the experimental program-v2 backend.
 * This is a backend-local migration seam, NOT yet the shared canonical Oreslang IR.
 */
export const PROGRAM_IR_VERSION=2;
export const PROGRAM_IR_DIALECT='oreslang.program';
export const PROGRAM_IR_INT_MODEL='signed-i64-checked-provisional';
const DEC=/^(?:0|[1-9][0-9]*|-[1-9][0-9]*)$/;
const MIN=-(1n<<63n), MAX=(1n<<63n)-1n;
const LIMIT_NODES=30000,LIMIT_DEPTH=192;
// Both source and supplied IR must preserve exact Unicode scalar sequences.
// Strict JS equality is equivalent to exact UTF-8 byte equality on this domain.
export function validLocalString(value){
  if(typeof value!=='string'||value.length>256)return false;
  for(let i=0;i<value.length;i++){
    const u=value.charCodeAt(i);
    if(u>=0xd800&&u<=0xdbff){
      const low=value.charCodeAt(++i);
      if(!(low>=0xdc00&&low<=0xdfff))return false;
    }else if(u>=0xdc00&&u<=0xdfff)return false;
  }
  return new TextEncoder().encode(value).length<=256;
}
const CLASS_RESERVED=new Set([
  'default','new','class','export','import','null','true','false','this','super',
  'extends','static','constructor','prototype','__proto__','define','pub','as',
  'end','self','int','void','return','mut','private','actor','fnc','routine',
  'bool','Symbol','let','val','const','if','fi','then','else','await','task',
  'spawn','module','trait','interface','type','rt','gen','generator','async',
  'suspend','suspends','yield','Iterator','AsyncIterator'
]);
const METHOD_CALLABLE_KEYWORDS=new Set([
  'mtd','method','stop','do','done','loop','block','select','readch','writech',
  'nb','true','false'
]);
const GENERATOR_RESERVED=new Set([
  'import','from','as','extends','implements','try','catch','throw','raise','panic',
  'recover','finally','end','fi','if','do','else','then','new','spawn','stop','done',
  'await','rt','async','suspend','suspends','critical','gen','generator','nlex','trap',
  'actor','isoactor','def','fnc','routine','while','for','of','loop','block','break',
  'continue','yield','super','elseif','elif','switch','match','matches','is','default',
  'eq','neq','nb','select','readch','writech','cb','shared','untrusted','type','types',
  'typeof','interface','trait','struct','impl','abstract','void','static','pub','private',
  'return','defer','val','const','let','mut','borrow','self','true','false','null','obj',
  'arr','Iterator','AsyncIterator','int','define','class','enum','pattern','module',
  'contract','conforms','namespace','gpu','cpu','quantum','over','structural','bool','pure',
  'Some','None','Ok','Err','using','requires','serial','critical-read','critical-write','nb-select'
]);
export class ProgramIRError extends Error {
  constructor(detail, code=null) {
    super('error: program IR: '+detail);
    this.name='ProgramIRError';
    this.code=code;
  }
}
const fail=(s,code=null)=>{throw new ProgramIRError(s,code);};
function record(v,keys,context) {
  if(v===null || typeof v!=='object' || Array.isArray(v))fail(context+': expected object');
  const proto=Object.getPrototypeOf(v);
  if(proto!==null && proto!==Object.prototype)fail(context+': non-plain object');
  const own=Reflect.ownKeys(v);
  if(own.length!==keys.length || own.some(k=>typeof k!=='string'||!keys.includes(k)))
    fail(context+': missing/extra fields');
  for(const key of keys){
    const desc=Object.getOwnPropertyDescriptor(v,key);
    if(!desc || !Object.hasOwn(desc,'value'))fail(context+': accessor/missing '+key);
  }
  return v;
}
function sequence(v,max,context){
  if(!Array.isArray(v)||Object.getPrototypeOf(v)!==Array.prototype||v.length>max)
    fail(context+': invalid array');
  const own=Reflect.ownKeys(v);
  if(own.length!==v.length+1 || own.some(k=>k!=='length'&&(!/^(?:0|[1-9][0-9]*)$/.test(k)||Number(k)>=v.length)))
    fail(context+': unexpected array properties');
  for(let i=0;i<v.length;i++) {
    const desc=Object.getOwnPropertyDescriptor(v,i);
    if(!desc||!Object.hasOwn(desc,'value'))fail(context+': accessor/sparse array');
  }
  return v;
}
function ident(s,where) {
  if(typeof s!=='string'||!isIdentifier(s))fail(where+': invalid identifier');
  return s;
}
function classIdent(s,where){
  ident(s,where);
  if((CLASS_RESERVED.has(s)&&s!=='mtd'&&s!=='method')||
     s==='Option'||s==='Result')fail(where+': reserved identifier');
  return s;
}
function generatorIdent(s,where){
  ident(s,where);
  if(GENERATOR_RESERVED.has(s))fail(where+': reserved generator identifier');
  return s;
}
function callableIdent(s,where){
  ident(s,where);
  if(CLASS_RESERVED.has(s)&&!METHOD_CALLABLE_KEYWORDS.has(s))
    fail(where+': reserved callable name');
  return s;
}
function int(s){
  if(typeof s!=='string'||s.length>20||!DEC.test(s))fail('invalid canonical integer');
  const n=BigInt(s);
  if(n<MIN||n>MAX)fail('integer outside provisional signed i64');
  return s;
}
function symbolName(s){
  if(typeof s!=='string'||s.length>1024)fail('Symbol source name exceeds normalization budget');
  for(let i=0;i<s.length;i++){
    const unit=s.charCodeAt(i);
    if(unit>=0xd800&&unit<=0xdbff){
      const lo=s.charCodeAt(i+1);
      if(!(lo>=0xdc00&&lo<=0xdfff))fail('unpaired UTF-16 surrogate in Symbol');
      i++;
    }else if(unit>=0xdc00&&unit<=0xdfff)fail('unpaired UTF-16 surrogate in Symbol');
  }
  const normalized=s.normalize('NFC');
  if(normalized!==s)fail('non-canonical NFC Symbol name');
  const bytes=new TextEncoder().encode(s).length;
  if(bytes<1||bytes>256)fail('Symbol name requires 1..256 UTF-8 bytes');
  for(const ch of s){
    const cp=ch.codePointAt(0);
    if(cp<0x20||(cp>=0x7f&&cp<=0x9f)||/[\p{Cf}\p{Zl}\p{Zp}]/u.test(ch)||
       cp===0x061c||cp===0x200e||cp===0x200f||
       (cp>=0x202a&&cp<=0x202e)||(cp>=0x2066&&cp<=0x2069))
      fail('control, line-separator, or bidi character in Symbol');
  }
  return s;
}
/**
 * Copy after validation, never return caller-owned IR references.
 * Direct object inputs are trusted data only, not a JavaScript sandbox.
 */
export function validateProgramIRV2(input, allowScalarWhile = false) {
  if(input===null||typeof input!=='object'||Array.isArray(input))
    fail('program: expected object');
  const rawKeys=Reflect.ownKeys(input);
  const hasClasses=rawKeys.includes('classes');
  const hasGenerators=rawKeys.includes('generators');
  const hasFileExports=rawKeys.includes('fileExports');
  const programKeys=['schemaVersion','dialect','integerModel','sourceNamespace','modules','functions'];
  if(hasGenerators)programKeys.push('generators');
  if(hasClasses)programKeys.push('classes');
  if(hasFileExports)programKeys.push('fileExports');
  record(input,programKeys,'program');
  if(input.schemaVersion!==PROGRAM_IR_VERSION||input.dialect!==PROGRAM_IR_DIALECT||
     input.integerModel!==PROGRAM_IR_INT_MODEL)fail('unsupported IR version/dialect/model');
  const namespace=input.sourceNamespace===null?null:ident(input.sourceNamespace,'file namespace');
  const seenModules=new Set(),moduleNames=sequence(input.modules,512,'modules').map(m=>{
    const name=ident(m,'module name');
    if(seenModules.has(name))fail('duplicate module '+name);
    seenModules.add(name);
    return name;
  });
  const sourceFunctions=sequence(input.functions,512,'functions');
  const sourceGenerators=hasGenerators?sequence(input.generators,512,'generators'):[];
  const sourceClasses=hasClasses?sequence(input.classes,512,'classes'):[];
  // Source parsing admits at most 512 caller-visible function/generator
  // declarations in aggregate. Forged JSON IR must not double that budget by
  // filling both independently bounded arrays.
  if(sourceFunctions.length+sourceGenerators.length>512)
    fail('caller-visible function/generator declaration budget exceeded');
  if(hasGenerators&&!sourceGenerators.length)fail('redundant empty generators field');
  if(hasClasses&&!sourceClasses.length)fail('redundant empty classes field');
  if(!moduleNames.length&&!sourceFunctions.length&&!sourceGenerators.length&&!sourceClasses.length)
    fail('empty program');
  const classNames=new Set(),classHeaders=sourceClasses.map(cls=>{
    record(cls,['name','pub','methods'],'class');
    const name=classIdent(cls.name,'class name');
    if(classNames.has(name)||seenModules.has(name))fail('duplicate class/module '+name);
    classNames.add(name);
    if(typeof cls.pub!=='boolean')fail('invalid class visibility');
    const slots=new Set(),methods=sequence(cls.methods,256,'class methods').map(method=>{
      record(method,['name','pub','params','paramTypes','returnType','body'],'class method');
      const methodName=callableIdent(method.name,'method name');
      if(typeof method.pub!=='boolean')fail('invalid method visibility');
      const params=sequence(method.params,32,'method parameters').map(p=>classIdent(p,'method parameter'));
      if(new Set(params).size!==params.length||params.includes('self'))
        fail('duplicate/reserved method parameter');
      const types=['int','bool','symbol','option<int>','result<int,int>'];
      const paramTypes=sequence(method.paramTypes,32,'method paramTypes');
      if(paramTypes.length!==params.length||paramTypes.some(t=>!types.includes(t)))
        fail('unsupported method parameter type');
      const returnType=method.returnType;
      if(returnType!=='void'&&!types.includes(returnType))fail('unsupported method return type');
      if(returnType==='void'&&method.body!==null)fail('void method body must be null');
      if(returnType!=='void'&&(method.body===null||typeof method.body!=='object'))
        fail('value method body required');
      const slot=methodName+'/'+params.length;
      if(slots.has(slot))fail('duplicate caller-visible method arity '+name+'.'+slot);
      slots.add(slot);
      return {name:methodName,pub:method.pub,params:[...params],
        paramTypes:[...paramTypes],returnType,body:method.body};
    });
    return {name,pub:cls.pub,methods};
  });
  const slots=new Set(),symbolKinds=new Map(),exportNames=new Set(moduleNames);
  const headers=sourceFunctions.map(f=>{
    // Reject nulls and non-records before any optional-key introspection.
    // The full record(...) admission below checks plain prototypes and every
    // exact own-data field before reading function metadata.
    if(f===null||typeof f!=='object'||Array.isArray(f))
      fail('function: expected plain data object');
    const keys=Reflect.ownKeys(f);
    const typed=keys.includes('paramTypes')||keys.includes('returnType');
    const explicitThrows=keys.includes('throws');
    const fields=typed?['module','name','kind','pub','params','body','paramTypes','returnType']:
      ['module','name','kind','pub','params','body'];
    if(explicitThrows)fields.push('throws');
    record(f,fields,'function');
    const throws=explicitThrows?sequence(f.throws,1,'ordinary throws row'):null;
    if(throws!==null&&throws.some(t=>t!=='Error'))fail('E_EFFECT: only Error is admitted in this throws profile');
    const module=f.module===null?null:ident(f.module,'owner module');
    if(module!==null && !seenModules.has(module))fail('missing owner module '+module);
    const name=ident(f.name,'function name');
    if(f.kind!=='fnc'&&f.kind!=='routine')fail('unknown function kind');
    if(typeof f.pub!=='boolean')fail('invalid visibility');
    if(f.kind==='routine'&&f.pub)fail('public routine cannot be exported');
    const params=sequence(f.params,128,'parameters').map(p=>ident(p,'parameter'));
    if(new Set(params).size!==params.length)fail('duplicate parameters');
    const paramTypes=typed?sequence(f.paramTypes,128,'paramTypes'):params.map(()=>'int');
    const types=['int','bool','symbol','option<int>','result<int,int>'];
    if(paramTypes.length!==params.length||
       paramTypes.some(t=>!types.includes(t)&&t!=='error'))fail('unsupported function parameter type');
    if(paramTypes.includes('error')&&(f.pub||params.includes('Error')||seenModules.has('Error')))
      fail('E_ERROR_BINDING: Error parameters require private callable and unshadowed core Error');
    const returnType=typed?f.returnType:'int';
    if(returnType!=='void'&&!types.includes(returnType))fail('unsupported function return type');
    if(returnType==='void'&&f.body!==null)fail('void function body must be null');
    if(returnType!=='void'&&(f.body===null||typeof f.body!=='object'))
      fail('nonvoid function requires an expression body');
    if(typed&&returnType==='int'&&paramTypes.every(t=>t==='int'))
      fail('redundant typed metadata on int-only function');
    const slot=(module===null?'@file/':'@module/'+module+'/')+name+'/'+params.length;
    if(slots.has(slot))fail('duplicate caller-visible method arity '+slot,'E_DUPLICATE');
    const family=slot.slice(0,slot.lastIndexOf('/'));
    if(symbolKinds.has(family)&&symbolKinds.get(family)!==f.kind)
      fail('fnc/routine overload kind conflict '+family);
    symbolKinds.set(family,f.kind);
    slots.add(slot);
    if(module===null&&f.pub)exportNames.add(name);
    return {module,name,kind:f.kind,pub:f.pub,params,
      ...(typed?{paramTypes:[...paramTypes],returnType}: {}),...(explicitThrows?{throws:[...throws]}:{})};
  });
  const generatorHeaders=sourceGenerators.map(gen=>{
    record(gen,['module','name','pub','params','paramTypes','elementType','yields'],'generator');
    const module=gen.module===null?null:ident(gen.module,'generator owner module');
    if(module!==null&&!seenModules.has(module))fail('missing generator owner module '+module);
    const name=generatorIdent(gen.name,'generator name');
    if(typeof gen.pub!=='boolean')fail('invalid generator visibility');
    const params=sequence(gen.params,128,'generator parameters').map(p=>generatorIdent(p,'generator parameter'));
    if(new Set(params).size!==params.length)fail('duplicate generator parameters');
    const paramTypes=sequence(gen.paramTypes,128,'generator paramTypes');
    if(paramTypes.length!==params.length||paramTypes.some(t=>t!=='int'))
      fail('sync generator v1 supports int parameters only');
    if(gen.elementType!=='int')fail('sync generator v1 supports Iterator<int> only');
    const yields=sequence(gen.yields,LIMIT_NODES,'generator yields');
    const slot=(module===null?'@file/':'@module/'+module+'/')+name+'/'+params.length;
    if(slots.has(slot))fail('duplicate caller-visible generator/function arity '+slot);
    const family=slot.slice(0,slot.lastIndexOf('/'));
    // Callable identity is name + value arity; preserve the existing family
    // marker when a differently-ary ordinary function already owns the name.
    if(!symbolKinds.has(family))symbolKinds.set(family,'generator');
    slots.add(slot);
    if(module===null&&gen.pub)exportNames.add(name);
    return {module,name,pub:gen.pub,params,paramTypes:[...paramTypes],elementType:'int',yields};
  });
  // Export directives are compile-time capability metadata, not permissive
  // ESM exports. Revalidate untrusted direct IR independently of the parser.
  const validatedFileExports=[];
  const seenFileExports=new Set();
  if(hasFileExports) {
    for(const entry of sequence(input.fileExports,1024,'fileExports')) {
      record(entry,['name','kind','visibility'],'file export');
      const name=ident(entry.name,'export name');
      if(seenFileExports.has(name))fail('duplicate export '+name);
      seenFileExports.add(name);
      if(!['class','fnc'].includes(entry.kind))fail('invalid export kind '+entry.kind);
      if(!['public','package'].includes(entry.visibility))fail('invalid export visibility');
      const targets=entry.kind==='class'
        ? classHeaders.filter(c=>c.name===name)
        : headers.filter(f=>f.module===null&&f.name===name&&f.kind==='fnc');
      if(!targets.length)fail('unbound export '+name);
      if(targets.some(t=>t.pub!==(entry.visibility==='public')))
        fail('export visibility does not match symbol table '+name);
      validatedFileExports.push({name,kind:entry.kind,visibility:entry.visibility});
    }
    if(!validatedFileExports.length)fail('redundant empty fileExports field');
  }
  // Export-name collisions are legal only for overloads of the same root
  // function, and NEVER between a runtime module object and a root function.
  for(const header of [...headers,...generatorHeaders])
    if(header.module===null&&header.pub&&seenModules.has(header.name))
      fail('file export conflicts with runtime module '+header.name);
  for(const cls of classHeaders){
    if(cls.pub&&seenModules.has(cls.name))fail('class export conflicts with runtime module '+cls.name);
    if(cls.pub&&[...headers,...generatorHeaders].some(h=>h.module===null&&h.pub&&h.name===cls.name))
      fail('class export conflicts with root callable '+cls.name);
  }
  const owners=[...headers,...generatorHeaders];
  const graph=owners.map(()=>new Set());
  let nodes=0;
  const captureSlots=owners.map(()=>new Set());
  const floatSlots=owners.map(()=>new Set());
  const boolSlots=owners.map(()=>new Set());
  const stringSlots=owners.map(()=>new Set());
  const optionSlots=owners.map(()=>new Set());
  const resultSlots=owners.map(()=>new Set());
  const trapSlots=owners.map(()=>new Set());
  const trapOptionSlots=owners.map(()=>new Set());
  const caughtSlots=owners.map(()=>new Set());
  const errorSlots=owners.map(()=>new Set());
  function node(expr,owner,depth=0,expected='int',active=[]){
    if(++nodes>LIMIT_NODES||depth>LIMIT_DEPTH)fail('expression complexity exceeded');
    if(!expr||typeof expr!=='object'||Array.isArray(expr))fail('invalid expression');
    const desc=Object.getOwnPropertyDescriptor(expr,'kind');
    if(!desc||!Object.hasOwn(desc,'value'))fail('invalid expression kind');
    // No implicit truthiness or numeric coercions, including in supplied IR.
    if(expected==='bool'&&['int','neg','binary'].includes(desc.value))
      fail('boolean expression required');
    if(expected!=='bool'&&['bool','compare','boolCompare','stringCompare','symbolCompare','not'].includes(desc.value))
      fail('boolean value cannot be used as non-bool');
    if(expected!=='int'&&['int','neg','binary'].includes(desc.value))
      fail('integer expression cannot be used as non-int');
    if(expected!=='symbol'&&desc.value==='symbol')
      fail('Symbol value cannot be used as non-Symbol');
    switch(desc.value) {
      case 'tryCatch':{
        record(expr,['kind','slot','body','handler'],'ordinary catch');
        if(owner>=headers.length||expected!==(headers[owner].returnType??'int')||
           !Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||expr.slot<0||
           expr.slot>=LIMIT_NODES||captureSlots[owner].has(expr.slot)||active.includes(expr.slot))
          fail('E_CATCH_BINDING: invalid catch scope');
        captureSlots[owner].add(expr.slot);
        const body=node(expr.body,owner,depth+1,expected,active);
        caughtSlots[owner].add(expr.slot);
        return {kind:'tryCatch',slot:expr.slot,body,handler:node(expr.handler,owner,depth+1,expected,[...active,expr.slot])};
      }
      case 'rethrow':
        record(expr,['kind','slot'],'caught Error rethrow');
        if(owner>=headers.length||expected!==(headers[owner].returnType??'int')||
           !Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||!active.includes(expr.slot)||!caughtSlots[owner].has(expr.slot))
          fail('E_CATCH_BINDING: rethrow outside caught Error scope');
        return {kind:'rethrow',slot:expr.slot};
      case 'throwError':
        record(expr,['kind','value'],'typed Error throw');
        if(owner>=headers.length||expected!==(headers[owner].returnType??'int'))fail('E_EFFECT_PROFILE: invalid typed Error throw');
        return {kind:'throwError',value:node(expr.value,owner,depth+1,'error',active)};
      case 'errorCapture':
        record(expr,['kind','slot'],'typed Error capture');
        if(expected!=='error'||!Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||!active.includes(expr.slot)||!errorSlots[owner].has(expr.slot))
          fail('E_ERROR_BINDING: invalid Error capture');
        return {kind:'errorCapture',slot:expr.slot};
      case 'guestEffect':
        record(expr,['kind','effect','message'],'core guest effect');
        if(owner>=headers.length||expected!==(headers[owner].returnType??'int')||
           [...headers,...generatorHeaders,...classHeaders].some(h=>h.name==='Error')||seenModules.has('Error')||headers[owner].params.includes('Error')||
           !['throw','raise','panic'].includes(expr.effect)||typeof expr.message!=='string'||
           expr.message.length>256||!(/^[\x20-\x7e]*$/).test(expr.message))
          fail('E_EFFECT_PROFILE: invalid core guest effect');
        return {kind:'guestEffect',effect:expr.effect,message:expr.message};
      case 'float':
        record(expr,['kind','value'],'float');
        if(expected!=='float'||typeof expr.value!=='string'||expr.value.length>128||
           !/^[0-9]+\.[0-9]+$/.test(expr.value)||!Number.isFinite(Number(expr.value)))fail('invalid float literal/type');
        return {kind:'float',value:expr.value};
      case 'trap':
        record(expr,['kind','operandType','inner'],'typed arithmetic trap');
        const option=['option<int>','trapOptionResult'].includes(expr.operandType);
        if(expected!==(option?'trapOptionResult':'trapResult')||
           !['int','option<int>','result<int,int>','trapResult','trapOptionResult'].includes(expr.operandType))
          fail('trap operand/result type mismatch');
        return {kind:'trap',operandType:expr.operandType,inner:node(expr.inner,owner,depth+1,expr.operandType,active)};
      case 'trapMethod':case 'trapOptionMethod':{
        record(expr,['kind','name','receiver'],'trap Result method');
        const option=desc.value==='trapOptionMethod';
        if(!['is_ok','is_err','unwrap'].includes(expr.name)||expected!==(expr.name==='unwrap'?(option?'option<int>':'int'):'bool'))
          fail('trap Result method type mismatch');
        return {kind:desc.value,name:expr.name,
          receiver:node(expr.receiver,owner,depth+1,option?'trapOptionResult':'trapResult',active)};
      }
      case 'floatNeg':
        record(expr,['kind','inner'],'float negation');
        if(expected!=='float')fail('float negation type mismatch');
        return {kind:'floatNeg',inner:node(expr.inner,owner,depth+1,'float',active)};
      case 'floatBin':
        record(expr,['kind','op','left','right'],'float arithmetic');
        if(expected!=='float'||!['+','-','*','/'].includes(expr.op))fail('float arithmetic type mismatch');
        return {kind:'floatBin',op:expr.op,left:node(expr.left,owner,depth+1,'float',active),right:node(expr.right,owner,depth+1,'float',active)};
      case 'numericCompare':
        record(expr,['kind','op','leftType','rightType','left','right'],'numeric comparison');
        if(expected!=='bool'||!['<','>','<=','>=','==','!='].includes(expr.op)||
           !['int','float'].includes(expr.leftType)||!['int','float'].includes(expr.rightType))fail('numeric comparison type mismatch');
        return {kind:'numericCompare',op:expr.op,leftType:expr.leftType,rightType:expr.rightType,
          left:node(expr.left,owner,depth+1,expr.leftType,active),right:node(expr.right,owner,depth+1,expr.rightType,active)};
      case 'int':
        record(expr,['kind','value'],'int');
        if(expected!=='int')fail('boolean expected, got int');
        return {kind:'int',value:int(expr.value)};
      case 'bool':
        record(expr,['kind','value'],'bool');
        if(expected!=='bool'||typeof expr.value!=='boolean')fail('invalid bool literal/type');
        return {kind:'bool',value:expr.value};
      case 'string':
        record(expr,['kind','value'],'local string');
        if(allowScalarWhile===true||expected!=='string'||!validLocalString(expr.value))fail('invalid local string literal/type');
        return {kind:'string',value:expr.value};
      case 'symbol':
        record(expr,['kind','value'],'symbol');
        if(expected!=='symbol')fail('Symbol literal type mismatch');
        return {kind:'symbol',value:symbolName(expr.value)};
      case 'newClass': {
        record(expr,['kind','classIndex'],'class construction');
        if(!Number.isSafeInteger(expr.classIndex)||Object.is(expr.classIndex,-0)||
           expr.classIndex<0||expr.classIndex>=classHeaders.length)
          fail('invalid class construction target');
        if(expected!=='class:'+expr.classIndex)fail('class construction type mismatch');
        return {kind:'newClass',classIndex:expr.classIndex};
      }
      case 'methodCall':
      case 'namedMethodCall': {
        const named=desc.value==='namedMethodCall';
        record(expr,named?
          ['kind','classIndex','methodIndex','receiver','args','paramOrder']:
          ['kind','classIndex','methodIndex','receiver','args'],
          named?'named method call':'method call');
        if(!Number.isSafeInteger(expr.classIndex)||Object.is(expr.classIndex,-0)||
           expr.classIndex<0||expr.classIndex>=classHeaders.length)
          fail('invalid method class target');
        const cls=classHeaders[expr.classIndex];
        if(!Number.isSafeInteger(expr.methodIndex)||Object.is(expr.methodIndex,-0)||
           expr.methodIndex<0||expr.methodIndex>=cls.methods.length)
          fail('invalid method target');
        const method=cls.methods[expr.methodIndex];
        if(!method.pub)fail('private method cannot be called externally');
        if(method.returnType==='void')fail('void method cannot be used as expression');
        if(method.returnType!==expected)fail('method return type mismatch');
        const receiver=node(expr.receiver,owner,depth+1,'class:'+expr.classIndex,active);
        const args=sequence(expr.args,32,'method call args');
        let order=method.params.map((_,i)=>i);
        if(named){
          const supplied=sequence(expr.paramOrder,32,'method parameter order');
          if(supplied.length!==args.length||args.length!==method.params.length)
            fail('named method arity mismatch');
          const seen=new Set();
          order=supplied.map(slot=>{
            if(!Number.isSafeInteger(slot)||Object.is(slot,-0)||slot<0||
               slot>=method.params.length||seen.has(slot))
              fail('invalid named method parameter order');
            seen.add(slot);return slot;
          });
          if(seen.size!==method.params.length)fail('incomplete named method parameter order');
        }else if(args.length!==method.params.length)fail('method arity mismatch');
        return {
          kind:desc.value,classIndex:expr.classIndex,methodIndex:expr.methodIndex,
          receiver,args:args.map((arg,i)=>node(arg,owner,depth+1,method.paramTypes[order[i]],active)),
          ...(named?{paramOrder:order}:{})
        };
      }
      case 'scalarRecur': {
        record(expr,['kind','states','guards','args'],'scalar recur');
        if(expected!=='int'||owner>=headers.length||allowScalarWhile===true)fail('scalar recur requires ordinary integer expression');
        const scope=[];
        // Check the normalized tree, not arbitrary unvalidated object fields.
        const closed=e=>{
          switch(e.kind){
            case 'int':case 'bool':return true;
            case 'capture':return scope.includes(e.slot);
            case 'neg':case 'not':return closed(e.inner);
            case 'binary':case 'compare':case 'boolCompare':return closed(e.left)&&closed(e.right);
            case 'if':return closed(e.condition)&&closed(e.yes)&&closed(e.no);
            default:return false;
          }
        };
        const typed=(e,t)=>{
          const v=node(e,owner,depth+1,t,scope);
          if(!closed(v))fail('scalar recur requires closed integer operands');
          return v;
        };
        const states=sequence(expr.states,32,'recur states').map(s=>{
          record(s,['slot','init'],'recur state');
          const init=typed(s.init,'int'),slot=s.slot;
          if(!Number.isSafeInteger(slot)||Object.is(slot,-0)||slot<0||slot>=LIMIT_NODES||captureSlots[owner].has(slot)||active.includes(slot))
            fail('invalid recur slot');
          captureSlots[owner].add(slot);scope.push(slot);return {slot,init};
        });
        if(!states.length)fail('empty recur state');
        const guards=sequence(expr.guards,32,'recur guards').map(g=>{
          record(g,['condition','value'],'recur guard');
          return {condition:typed(g.condition,'bool'),value:typed(g.value,'int')};
        });
        if(!guards.length)fail('recur loop needs valued break');
        const args=sequence(expr.args,32,'recur args').map(a=>typed(a,'int'));
        if(args.length!==states.length)fail('recur arity mismatch');
        return {kind:'scalarRecur',states,guards,args};
      }
      case 'scalarWhileMany': {
        if(allowScalarWhile!==true||owner>=headers.length)fail('scalar loop requires strict source admission');
        record(expr,['kind','states','condition','steps','body'],'scalar loop');
        const scope=[...active],writable=new Set();
        const slot=id=>{
          if(!Number.isSafeInteger(id)||Object.is(id,-0)||id<0||id>=LIMIT_NODES||captureSlots[owner].has(id))
            fail('invalid or repeated loop slot');
          captureSlots[owner].add(id);return id;
        };
        const states=sequence(expr.states,64,'loop states').map(state=>{
          record(state,['slot','init'],'loop state');
          const init=node(state.init,owner,depth+1,'int',active);
          const id=slot(state.slot);scope.push(id);writable.add(id);return {slot:id,init};
        });
        const steps=(items,outer,allowed,d)=>{
          if(d>16)fail('loop statement depth');
          const local=[...outer],mut=new Set(allowed);
          return sequence(items,128,'loop statements').map(step=>{
            if(++nodes>LIMIT_NODES)fail('expression complexity exceeded');
            const kind=Object.getOwnPropertyDescriptor(step??{},'kind');
            if(!kind||!Object.hasOwn(kind,'value'))fail('invalid loop statement');
            if(kind.value==='set'){
              record(step,['kind','slot','value'],'loop assignment');
              if(!mut.has(step.slot)||!local.includes(step.slot))fail('assignment outside mutable loop scope');
              return {kind:'set',slot:step.slot,value:node(step.value,owner,depth+d+1,'int',local)};
            }
            if(kind.value==='bind'){
              record(step,['kind','slot','mutable','init'],'loop binding');
              if(typeof step.mutable!=='boolean')fail('invalid loop binding mutability');
              const init=node(step.init,owner,depth+d+1,'int',local),id=slot(step.slot);
              local.push(id);if(step.mutable)mut.add(id);
              return {kind:'bind',slot:id,mutable:step.mutable,init};
            }
            if(kind.value==='loopExit'){
              record(step,['kind','exit','condition'],'guarded loop exit');
              if(step.exit!=='break'&&step.exit!=='continue')fail('invalid loop exit');
              return {kind:'loopExit',exit:step.exit,condition:node(step.condition,owner,depth+d+1,'bool',local)};
            }
            if(kind.value==='loop'){
              record(step,['kind','condition','steps'],'nested loop');
              return {kind:'loop',condition:node(step.condition,owner,depth+d+1,'bool',local),
                steps:steps(step.steps,local,mut,d+1)};
            }
            fail('invalid loop statement kind');
          });
        };
        return {kind:'scalarWhileMany',states,condition:node(expr.condition,owner,depth+1,'bool',scope),
          steps:steps(expr.steps,scope,writable,1),body:node(expr.body,owner,depth+1,expected,scope)};
      }
      case 'scalarWhile': {
        if(allowScalarWhile!==true)fail('scalar while requires strict source admission');
        // Checked i64 state only. This validates structural/type safety,
        // not a source-level proof or termination certificate.
        record(expr,['kind','slot','init','condition','update','body'],'scalar while');
        if(owner>=headers.length||!Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||expr.slot<0||
           expr.slot>=LIMIT_NODES||captureSlots[owner].has(expr.slot)||active.includes(expr.slot))
          fail('invalid or repeated loop slot');
        captureSlots[owner].add(expr.slot);
        const init=node(expr.init,owner,depth+1,'int',active);
        const scoped=[...active,expr.slot];
        return {kind:'scalarWhile',slot:expr.slot,init,
          condition:node(expr.condition,owner,depth+1,'bool',scoped),
          update:node(expr.update,owner,depth+1,'int',scoped),
          body:node(expr.body,owner,depth+1,expected,scoped)};
      }
      case 'local':case 'floatLocal':case 'boolLocal':case 'stringLocal':case 'trapLocal':case 'trapOptionLocal':case 'optionLocal':case 'resultLocal':{
        record(expr,['kind','slot','init','body'],'immutable local');
        if(owner>=headers.length||!Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||expr.slot<0||
           expr.slot>=LIMIT_NODES||captureSlots[owner].has(expr.slot)||active.includes(expr.slot))
          fail('invalid or repeated local slot');
        captureSlots[owner].add(expr.slot);
        const t=desc.value==='optionLocal'?'option<int>':desc.value==='resultLocal'?'result<int,int>':desc.value==='trapOptionLocal'?'trapOptionResult':desc.value==='trapLocal'?'trapResult':desc.value==='floatLocal'?'float':desc.value==='boolLocal'?'bool':desc.value==='stringLocal'?'string':'int';
        if(allowScalarWhile===true&&(t==='bool'||t==='string'))fail('ordinary-only local type');
        const init=node(expr.init,owner,depth+1,t,active);
        if(t==='trapResult')trapSlots[owner].add(expr.slot);
        if(t==='trapOptionResult')trapOptionSlots[owner].add(expr.slot);
        if(t==='float')floatSlots[owner].add(expr.slot);
        if(t==='bool')boolSlots[owner].add(expr.slot);
        if(t==='string')stringSlots[owner].add(expr.slot);
        if(t==='option<int>')optionSlots[owner].add(expr.slot);
        if(t==='result<int,int>')resultSlots[owner].add(expr.slot);
        return {kind:desc.value,slot:expr.slot,init,body:node(expr.body,owner,depth+1,expected,[...active,expr.slot])};
      }
      case 'capture':case 'floatCapture':case 'boolCapture':case 'stringCapture':case 'trapCapture':case 'trapOptionCapture':case 'optionCapture':case 'resultCapture':
        record(expr,['kind','slot'],'capture');
        if(!Number.isSafeInteger(expr.slot)||Object.is(expr.slot,-0)||expr.slot<0||
           (!active.includes(expr.slot)||caughtSlots[owner].has(expr.slot)||errorSlots[owner].has(expr.slot)))fail('ADT capture outside its typed payload scope');
        const captureType=optionSlots[owner].has(expr.slot)?'option<int>':resultSlots[owner].has(expr.slot)?'result<int,int>':trapOptionSlots[owner].has(expr.slot)?'trapOptionResult':trapSlots[owner].has(expr.slot)?'trapResult':floatSlots[owner].has(expr.slot)?'float':boolSlots[owner].has(expr.slot)?'bool':stringSlots[owner].has(expr.slot)?'string':'int';
        if(captureType==='int'&&expected!=='int')fail('ADT int payload capture type mismatch');
        if(expected!==captureType||desc.value!==(captureType==='option<int>'?'optionCapture':captureType==='result<int,int>'?'resultCapture':captureType==='trapOptionResult'?'trapOptionCapture':captureType==='trapResult'?'trapCapture':captureType==='float'?'floatCapture':captureType==='bool'?'boolCapture':captureType==='string'?'stringCapture':'capture'))
          fail('capture type mismatch');
        return {kind:desc.value,slot:expr.slot};
      case 'param':
        record(expr,['kind','index'],'param');
        if(!Number.isSafeInteger(expr.index)||Object.is(expr.index,-0)||expr.index<0||expr.index>=owners[owner].params.length)
          fail('out-of-bounds parameter');
        if((owners[owner].paramTypes?.[expr.index]??'int')!==expected)
          fail('parameter type mismatch');
        return {kind:'param',index:expr.index};
      case 'i32Check': {
        record(expr,['kind','inner'],'checked i32');
        if(expected!=='int')fail('checked i32 requires integer');
        return {kind:'i32Check',inner:node(expr.inner,owner,depth+1,'int',active)};
      }
      case 'refinementCheck': {
        record(expr,['kind','inner','minimum','maximum'],'checked refinement');
        if(expected!=='int')fail('checked refinement requires signed-i64 value');
        const minimum=int(expr.minimum),maximum=int(expr.maximum);
        if(BigInt(minimum)>BigInt(maximum))fail('empty refinement range');
        return {kind:'refinementCheck',inner:node(expr.inner,owner,depth+1,'int',active),minimum,maximum};
      }
      case 'neg':
        record(expr,['kind','inner'],'neg');
        if(expected!=='int')fail('int negation in boolean context');
        return {kind:'neg',inner:node(expr.inner,owner,depth+1,'int',active)};
      case 'not':
        record(expr,['kind','inner'],'not');
        if(expected!=='bool')fail('boolean negation in integer context');
        return {kind:'not',inner:node(expr.inner,owner,depth+1,'bool',active)};
      case 'binary':
        record(expr,['kind','op','left','right'],'binary');
        if(expected!=='int')fail('arithmetic in boolean context');
        if(!['+','-','*','/','%','mod','^','&','|','<<<','>>>','ashr'].includes(expr.op))fail('unsupported binary operator');
        return {kind:'binary',op:expr.op,
          left:node(expr.left,owner,depth+1,'int',active),right:node(expr.right,owner,depth+1,'int',active)};
      case 'compare':
        record(expr,['kind','op','left','right'],'compare');
        if(expected!=='bool')fail('comparison in integer context');
        if(!['<','>','<=','>=','==','!='].includes(expr.op))fail('unsupported comparator');
        return {kind:'compare',op:expr.op,left:node(expr.left,owner,depth+1,'int',active),
          right:node(expr.right,owner,depth+1,'int',active)};
      case 'stringCompare':
        record(expr,['kind','op','left','right'],'local string compare');
        if(allowScalarWhile===true||expected!=='bool'||!['==','!='].includes(expr.op))
          fail('invalid local string comparison');
        return {kind:'stringCompare',op:expr.op,
          left:node(expr.left,owner,depth+1,'string',active),
          right:node(expr.right,owner,depth+1,'string',active)};
      case 'boolCompare':
        record(expr,['kind','op','left','right'],'Boolean compare');
        if(expected!=='bool'||!['==','!='].includes(expr.op))
          fail('unsupported Boolean comparison');
        return {kind:'boolCompare',op:expr.op,
          left:node(expr.left,owner,depth+1,'bool',active),
          right:node(expr.right,owner,depth+1,'bool',active)};
      case 'symbolCompare':
        record(expr,['kind','op','left','right'],'Symbol compare');
        if(expected!=='bool')fail('Symbol comparison outside bool context');
        if(expr.op!=='=='&&expr.op!=='!=')fail('unsupported Symbol comparator');
        return {kind:'symbolCompare',op:expr.op,
          left:node(expr.left,owner,depth+1,'symbol',active),
          right:node(expr.right,owner,depth+1,'symbol',active)};
      case 'if':
        record(expr,['kind','condition','yes','no'],'if');
        return {kind:'if',condition:node(expr.condition,owner,depth+1,'bool',active),
          yes:node(expr.yes,owner,depth+1,expected,active),
          no:node(expr.no,owner,depth+1,expected,active)};
      case 'seqVoidCall': {
        record(expr,['kind','call','value'],'void-call sequence');
        const effectKind=expr.call&&typeof expr.call==='object'&&
          Object.getOwnPropertyDescriptor(expr.call,'kind')?.value;
        if(effectKind!=='call'&&effectKind!=='namedCall')
          fail('void-call sequence requires a direct call');
        return {kind:'seqVoidCall',
          call:node(expr.call,owner,depth+1,'void',active),
          value:node(expr.value,owner,depth+1,expected,active)};
      }
      case 'call': {
        record(expr,['kind','target','args'],'call');
        if(!Number.isSafeInteger(expr.target)||Object.is(expr.target,-0)||expr.target<0||expr.target>=headers.length)
          fail('invalid call target');
        const target=headers[expr.target];
        if((target.returnType??'int')!==expected)fail('call return type mismatch');
        const args=sequence(expr.args,128,'call args');
        if(args.length!==target.params.length)fail('call arity mismatch');
        if(target.module!==owners[owner].module && !target.pub)fail('cross-module private call');
        graph[owner].add(expr.target);
        return {kind:'call',target:expr.target,args:args.map((a,i)=>
          node(a,owner,depth+1,target.paramTypes?.[i]??'int',active))};
      }
      case 'namedCall': {
        record(expr,['kind','target','args','paramOrder'],'named call');
        if(!Number.isSafeInteger(expr.target)||Object.is(expr.target,-0)||
           expr.target<0||expr.target>=headers.length)fail('invalid named call target');
        const target=headers[expr.target];
        if((target.returnType??'int')!==expected)fail('named call return type mismatch');
        const args=sequence(expr.args,128,'named call args');
        const order=sequence(expr.paramOrder,128,'named call parameter order');
        if(args.length!==target.params.length||order.length!==args.length)
          fail('named call arity mismatch');
        const seen=new Set();
        const normalizedOrder=order.map(slot=>{
          if(!Number.isSafeInteger(slot)||Object.is(slot,-0)||slot<0||
             slot>=target.params.length||seen.has(slot))
            fail('invalid named call parameter order');
          seen.add(slot);return slot;
        });
        if(seen.size!==target.params.length)fail('incomplete named call parameter order');
        if(target.module!==owners[owner].module&&!target.pub)
          fail('cross-module private named call');
        graph[owner].add(expr.target);
        return {kind:'namedCall',target:expr.target,
          args:args.map((argument,i)=>node(argument,owner,depth+1,
            target.paramTypes?.[normalizedOrder[i]]??'int',active)),
          paramOrder:normalizedOrder};
      }
      case 'intrinsic': {
        record(expr,['kind','name','args'],'intrinsic');
        const signatures={
          'Option.some':[['int'],'option<int>'],
          'Option.none':[[],'option<int>'],
          'Option.is_some':[['option<int>'],'bool'],
          'Option.unwrap_or':[['option<int>','int'],'int'],
          'Option.unwrap':[['option<int>'],'int'],
          'Result.unwrap':[['result<int,int>'],'int'],
          'Option.expect':[['option<int>','string'],'int'],
          'Result.expect':[['result<int,int>','string'],'int'],
          'Result.ok':[['int'],'result<int,int>'],
          'Result.err':[['int'],'result<int,int>'],
          'Result.is_ok':[['result<int,int>'],'bool'],
          'Result.unwrap_or':[['result<int,int>','int'],'int'],
          'Result.unwrap_err_or':[['result<int,int>','int'],'int']
        };
        if(typeof expr.name!=='string'||!Object.hasOwn(signatures,expr.name))
          fail('unknown portable intrinsic');
        const [types,result]=signatures[expr.name];
        if(expected!==result)fail('intrinsic return type mismatch');
        const args=sequence(expr.args,2,'intrinsic args');
        if(args.length!==types.length)fail('intrinsic arity mismatch');
        return {kind:'intrinsic',name:expr.name,
          args:args.map((argument,i)=>node(argument,owner,depth+1,types[i],active))};
      }
      case 'adtMatch': {
        record(expr,['kind','adt','subject','arms'],'ADT match');
        if(!['option<int>','result<int,int>','trapResult'].includes(expr.adt))
          fail('unknown ADT match type');
        const subject=node(expr.subject,owner,depth+1,expr.adt,active);
        const variants=expr.adt==='option<int>'?['Some','None']:['Ok','Err'];
        const arms=sequence(expr.arms,2,'ADT match arms');
        if(arms.length!==2)fail('non-exhaustive ADT match');
        const seen=new Set(),checked=[];
        for(const arm of arms){
          record(arm,['tag','slot','body'],'ADT match arm');
          if(!variants.includes(arm.tag)||seen.has(arm.tag))
            fail('ADT match has duplicate or inappropriate variant');
          seen.add(arm.tag);
          const errorArm=expr.adt==='trapResult'&&arm.tag==='Err';
          if(errorArm&&arm.slot!==null&&!(subject.kind==='trap'&&subject.operandType==='int'))
            fail('E_ERROR_BINDING: mixed returned Err payload cannot be bound as Error');
          const payload=arm.tag!=='None'&&(!errorArm||arm.slot!==null);
          if(payload) {
            if(!Number.isSafeInteger(arm.slot)||Object.is(arm.slot,-0)||arm.slot<0||
               arm.slot>=LIMIT_NODES||captureSlots[owner].has(arm.slot)||active.includes(arm.slot))
              fail('invalid or repeated ADT payload slot');
            captureSlots[owner].add(arm.slot);
            if(errorArm)errorSlots[owner].add(arm.slot);
          } else if(arm.slot!==null)fail('discarded variant cannot bind a payload');
          const scopes=payload?[...active,arm.slot]:active;
          checked.push({tag:arm.tag,slot:arm.slot,
            body:node(arm.body,owner,depth+1,expected,scopes)});
        }
        if(seen.size!==2)fail('non-exhaustive ADT match');
        return {kind:'adtMatch',adt:expr.adt,subject,arms:checked};
      }
      case 'match': {
        record(expr,['kind','subject','arms'],'match');
        const subject=node(expr.subject,owner,depth+1,'int',active);
        const arms=sequence(expr.arms,256,'match arms');
        if(!arms.length)fail('empty match');
        let wildcard=false;
        const literals=new Set();
        const checked=arms.map(arm=>{
          record(arm,['value','body'],'match arm');
          let value;
          if(arm.value===null) {
            if(wildcard)fail('duplicate wildcard');
            wildcard=true;value=null;
          } else {
            if(wildcard)fail('unreachable arm after wildcard');
            value=int(arm.value);
            if(literals.has(value))fail('duplicate scalar pattern');
            literals.add(value);
          }
          return {value,body:node(arm.body,owner,depth+1,expected,active)};
        });
        if(!wildcard)fail('non-exhaustive int match');
        return {kind:'match',subject,arms:checked};
      }
      default:fail('unsupported expression kind');
    }
  }
  function methodNode(expr,method,depth=0,expected=method.returnType){
    if(++nodes>LIMIT_NODES||depth>LIMIT_DEPTH)fail('method expression complexity exceeded');
    if(!expr||typeof expr!=='object'||Array.isArray(expr))fail('invalid method expression');
    const desc=Object.getOwnPropertyDescriptor(expr,'kind');
    if(!desc||!Object.hasOwn(desc,'value'))fail('invalid method expression kind');
    switch(desc.value){
      case 'int':
        record(expr,['kind','value'],'method int');
        if(expected!=='int')fail('method int type mismatch');
        return {kind:'int',value:int(expr.value)};
      case 'bool':
        record(expr,['kind','value'],'method bool');
        if(expected!=='bool'||typeof expr.value!=='boolean')fail('method bool type mismatch');
        return {kind:'bool',value:expr.value};
      case 'symbol':
        record(expr,['kind','value'],'method Symbol');
        if(expected!=='symbol')fail('method Symbol type mismatch');
        return {kind:'symbol',value:symbolName(expr.value)};
      case 'param':
        record(expr,['kind','index'],'method param');
        if(!Number.isSafeInteger(expr.index)||Object.is(expr.index,-0)||expr.index<0||
           expr.index>=method.params.length)fail('out-of-bounds method parameter');
        if(method.paramTypes[expr.index]!==expected)fail('method parameter type mismatch');
        return {kind:'param',index:expr.index};
      case 'neg':
        record(expr,['kind','inner'],'method neg');
        if(expected!=='int')fail('method negation type mismatch');
        return {kind:'neg',inner:methodNode(expr.inner,method,depth+1,'int')};
      case 'not':
        record(expr,['kind','inner'],'method not');
        if(expected!=='bool')fail('method boolean negation type mismatch');
        return {kind:'not',inner:methodNode(expr.inner,method,depth+1,'bool')};
      case 'binary':
        record(expr,['kind','op','left','right'],'method binary');
        if(expected!=='int'||!['+','-','*','/','%','mod','^','&','|','<<<','>>>','ashr'].includes(expr.op))
          fail('unsupported method arithmetic');
        return {kind:'binary',op:expr.op,
          left:methodNode(expr.left,method,depth+1,'int'),
          right:methodNode(expr.right,method,depth+1,'int')};
      case 'compare':
        record(expr,['kind','op','left','right'],'method compare');
        if(expected!=='bool'||!['<','>','<=','>=','==','!='].includes(expr.op))
          fail('unsupported method comparison');
        return {kind:'compare',op:expr.op,
          left:methodNode(expr.left,method,depth+1,'int'),
          right:methodNode(expr.right,method,depth+1,'int')};
      case 'boolCompare':
        record(expr,['kind','op','left','right'],'method Boolean compare');
        if(expected!=='bool'||!['==','!='].includes(expr.op))
          fail('unsupported method Boolean comparison');
        return {kind:'boolCompare',op:expr.op,
          left:methodNode(expr.left,method,depth+1,'bool'),
          right:methodNode(expr.right,method,depth+1,'bool')};
      case 'symbolCompare':
        record(expr,['kind','op','left','right'],'method Symbol compare');
        if(expected!=='bool'||(expr.op!=='=='&&expr.op!=='!='))
          fail('unsupported method Symbol comparison');
        return {kind:'symbolCompare',op:expr.op,
          left:methodNode(expr.left,method,depth+1,'symbol'),
          right:methodNode(expr.right,method,depth+1,'symbol')};
      case 'if':
        record(expr,['kind','condition','yes','no'],'method if');
        return {kind:'if',
          condition:methodNode(expr.condition,method,depth+1,'bool'),
          yes:methodNode(expr.yes,method,depth+1,expected),
          no:methodNode(expr.no,method,depth+1,expected)};
      case 'intrinsic': {
        record(expr,['kind','name','args'],'method intrinsic');
        const signatures={
          'Option.some':[['int'],'option<int>'],'Option.none':[[],'option<int>'],
          'Option.is_some':[['option<int>'],'bool'],'Option.unwrap_or':[['option<int>','int'],'int'],
          'Result.ok':[['int'],'result<int,int>'],'Result.err':[['int'],'result<int,int>'],
          'Result.is_ok':[['result<int,int>'],'bool'],
          'Result.unwrap_or':[['result<int,int>','int'],'int'],
          'Result.unwrap_err_or':[['result<int,int>','int'],'int']
        };
        if(typeof expr.name!=='string'||!Object.hasOwn(signatures,expr.name))
          fail('unknown method intrinsic');
        const [types,result]=signatures[expr.name];
        if(result!==expected)fail('method intrinsic return type mismatch');
        const args=sequence(expr.args,2,'method intrinsic args');
        if(args.length!==types.length)fail('method intrinsic arity mismatch');
        return {kind:'intrinsic',name:expr.name,
          args:args.map((arg,i)=>methodNode(arg,method,depth+1,types[i]))};
      }
      default:fail('unsupported method expression kind '+desc.value);
    }
  }
  const functions=headers.map((f,i)=>({...f,
    body:f.returnType==='void'?null:node(sourceFunctions[i].body,i,0,f.returnType??'int')}));
  const generators=generatorHeaders.map((gen,i)=>({
    ...gen,
    yields:gen.yields.map(expr=>node(expr,headers.length+i,0,gen.elementType))
  }));
  const classes=classHeaders.map(cls=>({
    name:cls.name,pub:cls.pub,
    methods:cls.methods.map(method=>({
      ...method,
      body:method.returnType==='void'?null:methodNode(method.body,method,0,method.returnType)
    }))
  }));
  const color=new Uint8Array(headers.length);
  function visit(i) {
    if(color[i]===1)fail('recursive call graph unsupported pending fuel');
    if(color[i]===2)return;
    color[i]=1;for(const j of graph[i])visit(j);color[i]=2;
  }
  functions.forEach((_,i)=>visit(i));
  // Conservative ordinary guest effect analysis, independent of source AST.
  // The existing closed IR has no recursion. Memoization bounds transitive
  // analysis; legacy declarations are inferred, not treated as pure.
  const effects=new Map(),visiting=new Set();
  function escapes(i){
    if(effects.has(i))return effects.get(i);
    if(visiting.has(i))fail('E_EFFECT: recursive effect graph unsupported');
    visiting.add(i);
    const fn=functions[i];
    // Checked integer parameter admission can signal arithmetic overflow.
    // For well-typed guest arguments this check cannot fail; do not treat
    // malformed host arguments as a guest source effect.
    const result=ordinary(fn.body);
    visiting.delete(i);effects.set(i,result);return result;
  }
  function ordinary(n){
    if(n===null||typeof n!=='object')return false;
    switch(n.kind){
      case 'trap':return false;
      case 'tryCatch':return ordinary(n.handler);
      case 'guestEffect':return n.effect==='throw';
      case 'rethrow':case 'throwError':return true;
      case 'call':case 'namedCall':return n.args.some(ordinary)||escapes(n.target);
      case 'trapMethod':case 'trapOptionMethod':return n.name==='unwrap'||ordinary(n.receiver);
      case 'intrinsic':return ['Option.unwrap','Result.unwrap'].includes(n.name)||n.args.some(ordinary);
      case 'methodCall':case 'namedMethodCall':return true; // no method effect summaries yet
      case 'binary':return ['+','-','*','/','%','mod','<<','>>','<<<','>>>','ashr'].includes(n.op)||ordinary(n.left)||ordinary(n.right);
      case 'neg':case 'i32Check':case 'refinementCheck':return true;
      default:return Object.values(n).some(v=>Array.isArray(v)?v.some(ordinary):ordinary(v));
    }
  }
  for(let i=0;i<functions.length;i++){
    const fn=functions[i];
    if(fn.throws?.length===0&&escapes(i))
      fail('E_EFFECT: undeclared escaping ordinary throw in '+fn.name);
    // A declared Error must not resolve to a shadowed nominal type.
    if((fn.throws?.length||fn.paramTypes?.includes('error'))&&([...headers,...generatorHeaders,...classHeaders].some(h=>h.name==='Error')||seenModules.has('Error')||fn.params.includes('Error')))
      fail('E_EFFECT: throws(Error) requires unshadowed core Error');
  }
  return {
    schemaVersion:PROGRAM_IR_VERSION,dialect:PROGRAM_IR_DIALECT,
    integerModel:PROGRAM_IR_INT_MODEL,sourceNamespace:namespace,
    modules:moduleNames,functions,
    ...(hasGenerators?{generators}:{}),
    ...(hasClasses?{classes}:{}),
    ...(hasFileExports?{fileExports:validatedFileExports}:{})
  };
}
export function stringifyProgramIRV2(ir){
  return JSON.stringify(validateProgramIRV2(ir));
}
