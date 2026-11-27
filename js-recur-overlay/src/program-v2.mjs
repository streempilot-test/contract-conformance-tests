import {identifierCharAt,isIdentifierStart,isIdentifierPart,isIdentifier} from './portable-unicode-identifier-v1.mjs';
import {lowerVerifiedQuantitySource} from './verified-quantity-source.mjs';
import {missingBangKeywordSeparator} from './bang-keyword-separator.mjs';
import {validLocalString,validateProgramIRV2,PROGRAM_IR_VERSION,PROGRAM_IR_DIALECT,PROGRAM_IR_INT_MODEL} from './program-ir-v2.mjs';
import {TESTING_SCALAR_SOURCE, TESTING_SCALAR_EXPORTS} from './compiler-owned-testing-scalar.mjs';
import {rejectExecutionDomains} from './execution-domain-admission.mjs';
import {rejectUnsupportedHttpActors} from './http-actor-admission.mjs';
import {isCoreConstructor,isProtectedPreludeIdentity} from './automatic-prelude.mjs';
import {coreBooleanIntrinsic,isCoreBooleanUnit} from './core-boolean-intrinsics.mjs';
/**
 * Experimental Oreslang program-to-ESM v2 slice:
 * flat runtime modules, private/public functions, qualified calls, integer matches.
 * Standalone parser until the shared typed frontend and IR become available.
 * DOES NOT implement classes, actors, imports, or the type-only namespace grammar.
 */
const MAX_BYTES = 262144, MAX_TOKENS = 30000, MAX_DEPTH = 192;
const MIN = -(1n << 63n), MAX = (1n << 63n) - 1n;
const CLASS_METHOD_RESERVED=new Set([
  'default','new','class','export','import','null','this','super','extends','static',
  'constructor','prototype','__proto__','define','pub','as','end','self','int',
  'void','return','mut','private','actor','fnc','routine','bool','Symbol','let',
  'val','const','if','ifnot','unless','fi','then','else','guard','await','task','spawn','module','trait',
  'interface','type','rt','gen','generator','async','suspend','suspends','yield',
  'Iterator','AsyncIterator'
]);
const CLASS_METHOD_CALLABLE_KEYWORDS=new Set([
  'mtd','method','stop','do','done','loop','block','select','readch','writech',
  'nb','true','false'
]);
const GENERATOR_RESERVED=new Set([
  'import','from','as','extends','implements','try','catch','throw','raise','panic',
  'recover','finally','end','fi','if','ifnot','unless','do','else','then','new','spawn','stop','done',
  'await','rt','async','suspend','suspends','critical','gen','generator','nlex','trap',
  'actor','isoactor','def','fnc','routine','while','for','of','loop','block','break',
  'continue','yield','guard','super','elseif','elif','switch','match','matches','is','default',
  'eq','neq','nb','select','readch','writech','cb','shared','untrusted','type','types',
  'typeof','interface','trait','struct','impl','abstract','void','static','pub','private',
  'return','defer','val','const','let','mut','borrow','self','true','false','null','obj',
  'arr','Iterator','AsyncIterator','int','define','class','enum','pattern','module',
  'contract','conforms','namespace','gpu','cpu','quantum','over','structural','bool','pure',
  'Some','None','Ok','Err','using','requires','serial','critical-read','critical-write','nb-select'
]);
export class ProgramCompileError extends Error {
  constructor(message, loc, code=null) {
    super('error: program v2: ' + message + (loc ? ' at ' + loc.line + ':' + loc.col : ''));
    this.name = 'ProgramCompileError';
    // Stable contract diagnostics are opt-in; preserve legacy messages.
    this.code=code;
  }
}
const fail = (message, loc, code=null) => {throw new ProgramCompileError(message, loc, code);};
const sourceLineEnd = ch => ch==='\n'||ch==='\r'||ch==='\u0085'||ch==='\u2028'||ch==='\u2029';

/**
 * Align source-profile negotiation with Java CheckedI64SourceHeader.
 * Provisional JS arithmetic stays checked for unmarked source too, but a
 * malformed or displaced explicit opt-in must never be silently ignored.
 * Only actual line-comment tokens count; block/quoted lookalikes do not.
 */
function requireWellFormedCheckedI64Header(source) {
  const directive='// oreslang:checked-i64-v1\n';
  const prefix='oreslang:checked-i64';
  const enabled=source.startsWith(directive);
  let depth=0,quote=null;
  for(let i=0;i<source.length;){
    const ch=source[i], next=source[i+1];
    if(quote!==null) {
      if(ch==='\\'&&i+1<source.length){i+=2;continue;}
      if(ch===quote)quote=null;
      i++;
      continue;
    }
    if(depth!==0) {
      if(ch==='/'&&next==='*'){depth++;i+=2;continue;}
      if(ch==='*'&&next==='/'){depth--;i+=2;continue;}
      i++;
      continue;
    }
    if(ch==='"'||ch==="'"){quote=ch;i++;continue;}
    if(ch==='/'&&next==='*'){depth=1;i+=2;continue;}
    if(ch==='/'&&next==='/'){
      let stop=i+2;
      while(stop<source.length&&!sourceLineEnd(source[stop]))stop++;
      let marker=i+2;
      while(marker<stop&&' \t\r\v\f'.includes(source[marker]))marker++;
      if(source.startsWith(prefix,marker)&&(!enabled||i!==0))
        fail('checked-i64-v1: malformed, duplicated, displaced or unsupported directive');
      i=stop;
      continue;
    }
    i++;
  }
  return enabled;
}
function validateSourceText(source) {
  // UTF-8 is never shorter than the source UTF-16 code-unit count. Reject
  // obviously oversized input before walking it or allocating an encoding.
  if(typeof source!=='string'||source.length>MAX_BYTES)
    fail('oversized or invalid source');
  // TextEncoder replaces lone UTF-16 surrogates. Reject before encoding so
  // comments and other lexically ignored regions cannot change byte identity.
  for(let i=0;i<source.length;i++){
    const unit=source.charCodeAt(i);
    if(unit===0)fail('NUL in source');
    if(unit===0x1b||unit===0x7f)
      fail('terminal control in source at UTF-16 offset '+i);
    if(unit===0x061c||unit===0x200e||unit===0x200f||
       (unit>=0x202a&&unit<=0x202e)||(unit>=0x2066&&unit<=0x2069))
      fail('bidirectional Unicode control in source at UTF-16 offset '+i);
    // All supported Unicode source line separators are handled consistently by the lexer and directive scanner.
    if(unit>=0xd800&&unit<=0xdbff){
      const lo=source.charCodeAt(i+1);
      if(!(lo>=0xdc00&&lo<=0xdfff))fail('invalid UTF-16 source');
      i++;
    }else if(unit>=0xdc00&&unit<=0xdfff)fail('invalid UTF-16 source');
  }
}
function normalizeProcessSymbolName(input) {
  if(typeof input!=='string'||input.length>1024)
    fail('Symbol source name exceeds normalization budget');
  for(let i=0;i<input.length;i++){
    const unit=input.charCodeAt(i);
    if(unit>=0xd800&&unit<=0xdbff){
      const lo=input.charCodeAt(i+1);
      if(!(lo>=0xdc00&&lo<=0xdfff))fail('unpaired UTF-16 surrogate in Symbol');
      i++;
    }else if(unit>=0xdc00&&unit<=0xdfff)fail('unpaired UTF-16 surrogate in Symbol');
  }
  const value=input.normalize('NFC');
  const bytes=new TextEncoder().encode(value).length;
  if(bytes<1||bytes>256)fail('Symbol name requires 1..256 UTF-8 bytes');
  for(const ch of value){
    const cp=ch.codePointAt(0);
    if(cp<0x20||(cp>=0x7f&&cp<=0x9f)||/[\p{Cf}\p{Zl}\p{Zp}]/u.test(ch)||
       cp===0x061c||cp===0x200e||cp===0x200f||
       (cp>=0x202a&&cp<=0x202e)||(cp>=0x2066&&cp<=0x2069))
      fail('control, line-separator, or bidi character in Symbol');
  }
  return value;
}
export function tokenizeCountedProfileSource(source) { return tokenize(source); }
function tokenize(source) {
  validateSourceText(source);
  if (new TextEncoder().encode(source).length > MAX_BYTES) fail('oversized or invalid source');
  requireWellFormedCheckedI64Header(source);
  const result = [];
  let pos = 0, line = 1, col = 1;
  function advance() {
    const ch=source[pos];
    if(sourceLineEnd(ch)){if(ch!=='\n'||source[pos-1]!=='\r')line++;col=1;}
    else col++;
    pos++;
  }
  function emit(kind, value, start) {
    if (result.length >= MAX_TOKENS) fail('token budget exceeded', start);
    result.push({kind, value, ...start});
  }
  while (pos < source.length) {
    const start = {line, col};
    const c = source[pos];
    if (/\s/.test(c)||c==='\u0085') {advance(); continue;}
    if (source.startsWith('//',pos)) {
      while (pos < source.length && !sourceLineEnd(source[pos])) advance();
      continue;
    }
    if (source.startsWith('/*',pos)) {
      advance(); advance(); let nesting = 1;
      while (nesting && pos < source.length) {
        if (source.startsWith('/*',pos)) {
          if (++nesting > 64) fail('comment nesting exceeded',start);
          advance(); advance();
        } else if (source.startsWith('*/',pos)) {
          nesting--; advance(); advance();
        } else advance();
      }
      if (nesting) fail('unterminated comment',start);
      continue;
    }
    if (c==='#') {
      advance();
      let value='';
      const quote=source[pos];
      if(quote==='"'||quote==="'"){
        advance();
        let closed=false;
        while(pos<source.length){
          if(source[pos]===quote){advance();closed=true;break;}
          let ch=source[pos];
          if(ch==='\n'||ch==='\r')fail('raw newline in quoted Symbol literal',start);
          advance();
          if(ch==='\\'){
            if(pos>=source.length)fail('unterminated quoted Symbol escape',start);
            const escaped=source[pos];advance();
            if(escaped==='u'){
              let code=0;
              for(let i=0;i<4;i++){
                if(pos>=source.length)fail('incomplete Unicode Symbol escape',start);
                const digit=source[pos];advance();
                const hex=digit>='0'&&digit<='9'?digit.charCodeAt(0)-48:
                  digit>='a'&&digit<='f'?digit.charCodeAt(0)-87:
                  digit>='A'&&digit<='F'?digit.charCodeAt(0)-55:-1;
                if(hex<0)fail('invalid Unicode Symbol escape',start);
                code=(code<<4)|hex;
              }
              ch=String.fromCharCode(code);
            }else if(escaped==='n')ch='\n';
            else if(escaped==='r')ch='\r';
            else if(escaped==='t')ch='\t';
            else if(escaped==='"'||escaped==="'"||escaped==='\\')ch=escaped;
            else fail('invalid quoted Symbol escape',start);
          }
          value+=ch;
        }
        if(!closed)fail('unterminated quoted Symbol literal',start);
        value=normalizeProcessSymbolName(value);
      }else{
        if(pos>=source.length||!/[A-Za-z_]/.test(source[pos]))
          fail("expected Symbol name immediately after '#'",start);
        while(pos<source.length&&/[A-Za-z0-9_]/.test(source[pos])){
          value+=source[pos];advance();
        }
        if(value.length>128)fail('unquoted Symbol literal exceeds 128 ASCII bytes',start);
        value=normalizeProcessSymbolName(value);
      }
      emit('symbol',value,start);
    } else if(c==='"'&&result.at(-1)?.value==='('&&result.at(-2)?.value==='Error'&&
              result.at(-3)?.value==='new'&&['throw','throw!','raise','raise!','panic','panic!'].includes(result.at(-4)?.value)){
      // Only a bounded literal core Error message, not a general string expression.
      advance();let message='';
      while(pos<source.length&&source[pos]!=='"'){
        const ch=source[pos];
        if(ch==='\\'||sourceLineEnd(ch)||ch.charCodeAt(0)<32||ch.charCodeAt(0)>126)
          fail('E_ERROR_MESSAGE: only unescaped printable ASCII Error literals are admitted',start);
        if(message.length>=256)fail('E_ERROR_MESSAGE: literal exceeds 256 bytes',start);
        message+=ch;advance();
      }
      if(pos>=source.length)fail('E_ERROR_MESSAGE: unterminated Error literal',start);
      advance();emit('errorMessage',message,start);
    } else if ((c==='"' || c==="'") && result.length && result[result.length-1].value==='from') {
      // Quoted strings are *only* admissible in the compiler-owned std import
      // declaration below. General string expressions remain unsupported.
      const quote=c;
      advance();
      let value='';
      while(pos < source.length && source[pos] !== quote) {
        const ch=source[pos];
        if(ch==='\\'||ch==='\n'||ch==='\r'||ch.charCodeAt(0)<32)
          fail('invalid or escaped module import string',start);
        value+=ch;advance();
        if(value.length>128)fail('module import path exceeds 128 bytes',start);
      }
      if(pos>=source.length)fail('unterminated module import string',start);
      advance();emit('str',value,start);
    } else if(c==='"'||c==="'"){
      const quote=c;
      advance();let value='';
      while(pos<source.length&&source[pos]!==quote){
        let ch=source[pos];advance();
        if(sourceLineEnd(ch)||ch.charCodeAt(0)<32)
          fail('E_STRING_PROFILE: raw control in string',start);
        if(ch==='\\'){
          if(pos>=source.length)fail('E_STRING_ESCAPE: truncated escape',start);
          ch=source[pos];advance();
          if(sourceLineEnd(ch))fail('E_STRING_ESCAPE: multiline continuation unsupported',start);
          if(ch==='u'){
            const hex=source.slice(pos,pos+4);
            if(!/^[0-9A-Fa-f]{4}$/.test(hex))fail('E_STRING_ESCAPE: invalid Unicode escape',start);
            ch=String.fromCharCode(parseInt(hex,16));
            for(let i=0;i<4;i++)advance();
          }else ch=ch==='n'?'\n':ch==='r'?'\r':ch==='t'?'\t':ch;
        }
        value+=ch;
        if(value.length>256)fail('E_STRING_PROFILE: local string exceeds 256 bytes',start);
      }
      if(pos>=source.length)fail('E_STRING_PROFILE: unterminated string',start);
      if(!validLocalString(value))fail('E_STRING_PROFILE: invalid Unicode or byte budget',start);
      advance();
      // Never let quoted payload text masquerade as a keyword or delimiter.
      emit('localString','<local-string>',start);
      result.at(-1).literal=value;
    } else if (c==='⧖'||c==='∂') {
      emit('id','suspends',start); result.at(-1).lexeme=c; advance();
    } else if (isIdentifierStart(identifierCharAt(source,pos))) {
      let word = '';
      while (pos < source.length && isIdentifierPart(identifierCharAt(source,pos))) {
        const ch=identifierCharAt(source,pos);word+=ch;for(let j=0;j<ch.length;j++)advance();
      }
      if (word.length > 128) fail('identifier limit',start);
      // Never parse a contextual ownership bang as a macro or ordinary call;
      // this ESM compiler lacks typechecked move/borrow/share lowering.
      if (missingBangKeywordSeparator(word, source.slice(pos)))
        fail('E_BANG_KEYWORD_WHITESPACE: ASCII whitespace required after '+word+'!',start);
      if (['copy','take','move','borrow','share'].includes(word) && source[pos]==='!' &&
          source[pos+1]!=='=' &&
          !(source.slice(pos+1,pos+3)==='eq' && !isIdentifierPart(identifierCharAt(source,pos+3))))
        fail('OWNERSHIP_BANG_BACKEND_UNSUPPORTED: typed ownership and worker transfer lowering required',start);
      // Canonical bang exceptional control requires typed Error/Result and
      // checked guest unwind; no fallback to an ordinary JS exception.
      if(['trap','throw','raise','panic'].includes(word) && source[pos]==='!' &&
         source[pos+1]!=='=' && !(source.startsWith('!eq',pos)&&!isIdentifierPart(identifierCharAt(source,pos+3))))
        {
          if(!/[ \t\r\n\v\f]/.test(source[pos+1]??''))
            fail('E_BANG_KEYWORD_WHITESPACE: ASCII whitespace required after '+word+'!',start);
          if(word==='trap'&&!['=','|>','return','('].includes(result.at(-1)?.value))
            fail('EXCEPTION_BANG_BACKEND_UNSUPPORTED: typed guest effects and Result lowering required',start);
          if(word!=='trap'&&!['{',';','then','else','do'].includes(result.at(-1)?.value))
            fail('EXCEPTION_BANG_BACKEND_UNSUPPORTED: exceptional control must be a statement',start);
          advance();word+='!';
        }
      emit('id',word==='susp'?'suspends':word,start);
      if(word==='susp')result.at(-1).lexeme=word;
    } else if (/[0-9]/.test(c)) {
      let number = '';
      while (pos < source.length && /[0-9_]/.test(source[pos])) {
        number += source[pos]; advance();
      }
      if (number.length > 64 || !/^[0-9]+(?:_[0-9]+)*$/.test(number)) fail('invalid int literal',start);
      if(source[pos]==='.' && /[0-9]/.test(source[pos+1]??'')){
        number+='.';advance();
        while(pos<source.length && /[0-9]/.test(source[pos])){number+=source[pos];advance();}
        if(number.length>128||!Number.isFinite(Number(number.replaceAll('_',''))))fail('invalid float literal',start);
        emit('float',number.replaceAll('_',''),start);
      }else emit('int',number,start);
    } else if (source.startsWith('&&',pos) || source.startsWith('||',pos)) {
      // Atto's symbolic boolean operators are aliases of the existing
      // lazy `and`/`or` IR. Never lower these to eager JS bitwise ops.
      emit('id',source.startsWith('&&',pos)?'and':'or',start);
      advance(); advance();
    } else if (['<=','>=','==','!='].some(op=>source.startsWith(op,pos))) {
      emit('sym',source.slice(pos,pos+2),start); advance(); advance();
    } else if (source.startsWith('->',pos) || source.startsWith('<-',pos) || source.startsWith('::',pos) || source.startsWith('|>',pos)) {
      emit('sym',source.slice(pos,pos+2),start); advance(); advance();
    } else if (c==='•') {
      emit('sym',c,start); advance();
    } else if (c==='%') {
      fail("'%' is reserved and is not AttoLang source syntax; use Integer.rem(a, b) or Integer.mod(a, b)",start);
    } else if (source.startsWith('^^',pos)) {
      emit('sym','^^',start); advance(); advance();
    } else if ('(){}[]:,;+-*/=.<>!¬?^&|'.includes(c)) {
      emit('sym',c,start); advance();
    } else fail('unsupported character',start);
  }
  emit('eof','<eof>',{line,col});
  return result;
}
/**
 * JS program-v2 supports a restricted tagged Option/Result value ABI,
 * but has no compiler-owned guest throw/raise/panic classification or trap
 * unwind boundary. Reject source trap before resolution/ESM publication.
 * Match lexer tokens, never raw text: comments containing 'trap' are inert.
 */
function rejectUnsupportedTrapEffect(tokens) {
  const admitted = new Set();
  for (let i=0;i<tokens.length;i++) {
    const token=tokens[i];
    if (token.kind==='id' && (token.value==='trap'||token.value==='trap!')) {
      // Only the statically validated inferred local binding slice is admitted.
      // All other contexts retain the existing fail-closed admission.
      if(tokens[i-3]?.value==='val'&&tokens[i-2]?.kind==='id'&&tokens[i-1]?.value==='='){admitted.add(i);continue;}
      // Closed typed Result<int,Error> locals are the same validated trap IR.
      if(tokens.slice(i-9,i-2).map(t=>t.value).join(' ')==='val Result < int , Error >'&&tokens[i-2]?.kind==='id'&&tokens[i-1]?.value==='='){admitted.add(i);continue;}
      // A nested trap may only follow the opening parentheses of an already
      // admitted trap operand. The typed parser still validates the whole tree.
      let outer=i-1;
      while(tokens[outer]?.value==='(')outer--;
      if(outer<i-1&&admitted.has(outer)){admitted.add(i);continue;}
      if(token.value==='trap!'&&tokens[i-1]?.value==='|>')continue;
      if(tokens[i-1]?.value==='return'&&tokens[i+1]?.kind==='id'&&
         tokens.slice(i+2,i+10).map(t=>t.value).join(' ')==='. unwrap ( ) |> match < int'&&tokens[i+10]?.value==='>'&&tokens[i+11]?.value==='over')continue;
      fail('trap requires verified ordinary-throw effect and cleanup lowering; Option/Result values alone are not a trap boundary',token);
    }
  }
}
function rejectUnsupportedStaticSelect(tokens) {
  const startsStaticSelect = next =>
    next && ['{','over','first','fair','random'].includes(next.value);
  for (let i=0;i<tokens.length;i++) {
    const token=tokens[i];
    if (token.kind==='id' && token.value==='select' &&
        startsStaticSelect(tokens[i+1])) {
      fail(
        'static select requires checked Channel/Future arbitration, actor-lane continuation, ownership and cancellation lowering in the JS backend',
        token
      );
    }
  }
}
class Parser {
  constructor(tokens,coreBooleanModules=new Set()) {this.tokens = tokens;this.i = 0;this.depth = 0;this.expressions = 0;this.statementNodes = 0;this.coreBooleanModules=coreBooleanModules;}
  peek() {return this.tokens[this.i];}
  eat(s) {if(this.peek().value === s) {this.i++; return true;} return false;}
  expect(s) {if(!this.eat(s)) fail('expected ' + s + ', found ' + this.peek().value,this.peek());}
  name() {
    const t = this.peek();
    if (t.kind !== 'id' || !isIdentifier(t.value) || t.value==='ifnot'||t.value==='unless'||t.value==='suspends') fail('identifier expected',t);
    this.i++; return t.value;
  }
  generatorIdentifier() {
    const t=this.peek(),value=t.value;
    if(t.kind!=='id'||!isIdentifier(value)||GENERATOR_RESERVED.has(value))
      fail('invalid or reserved generator identifier '+value,t);
    this.i++;return value;
  }
  classIdentifier() {
    const t=this.peek(),value=t.value;
    if(t.kind!=='id'||!isIdentifier(value)||CLASS_METHOD_RESERVED.has(value)||
       value==='Option'||value==='Result'||value==='Integer')
      fail('invalid or reserved class/member identifier '+value,t);
    this.i++;return value;
  }
  callableName() {
    const t=this.peek(),value=t.value;
    if(t.kind!=='id'||!isIdentifier(value)||
       (CLASS_METHOD_RESERVED.has(value)&&!CLASS_METHOD_CALLABLE_KEYWORDS.has(value)))
      fail('invalid or reserved callable name '+value,t);
    this.i++;return value;
  }
  eatMethodMarker() {
    const marker=this.peek().value;
    if(marker!=='mtd'&&marker!=='method'&&marker!=='fnc')return false;
    const next=this.tokens[this.i+1],after=this.tokens[this.i+2];
    if(!next||next.kind!=='id'||after?.value!=='(')return false;
    const value=next.value;
    if(CLASS_METHOD_RESERVED.has(value)&&!CLASS_METHOD_CALLABLE_KEYWORDS.has(value))
      return false;
    if(marker==='method')fail('obsolete method introducer; use mtd',this.peek());
    this.i++;return true;
  }
  argumentsAfterOpen() {
    const args=[];let namedSeen=false;
    if(!this.eat(')')) {
      do {
        if(args.length>=128)fail('argument limit',this.peek());
        let name=null;
        if(this.peek().kind==='id'&&this.tokens[this.i+1]?.value===':'){
          name=this.name();this.expect(':');namedSeen=true;
        }else if(namedSeen)fail('positional argument cannot follow a named argument',this.peek());
        args.push({name,expr:this.expression()});
      } while(this.eat(','));
      this.expect(')');
    }
    return args;
  }
  // Accept both legacy `int value` and canonical AttoLang `value: int`.
  // The decision is token-based, not a text rewrite, so comments and typed
  // compound values cannot change parameter binding or evaluation order.
  typedParameter(readName) {
    if (this.peek().kind==='id' && this.tokens[this.i+1]?.value===':') {
      const name=readName();this.expect(':');
      if (['int','bool','Symbol','Option','Result'].includes(name))
        fail('type keyword cannot be a name-first parameter',this.peek());
      return {name,type:this.valueType()};
    }
    const type=this.valueType(),name=readName();
    return {name,type};
  }
  valueType() {
    if(this.eat('int'))return 'int';
    if(this.eat('bool'))return 'bool';
    if(this.peek().value==='boolean') {
      this.booleanAliasToken??=this.peek();this.i++;return 'bool';
    }
    if(this.eat('Symbol'))return 'symbol';
    if(this.eat('Error'))return 'error';
    if(this.eat('Option')){
      this.expect('<');this.expect('int');this.expect('>');
      return 'option<int>';
    }
    if(this.eat('Result')){
      this.expect('<');this.expect('int');this.expect(',');this.expect('int');this.expect('>');
      return 'result<int,int>';
    }
    fail('unsupported scalar type (int, bool, Symbol, Option<int>, Result<int,int>)',this.peek());
  }
  classDeclaration(pub, writtenPrivate=false) {
    const name=this.classIdentifier();
    if(isProtectedPreludeIdentity(name))
      fail('E_PRELUDE_SPOOF: cannot define compiler-owned core class '+name,this.peek());
    this.expect('as');
    const methods=[],slots=new Set();
    while(!this.eat('end')) {
      if(this.peek().kind==='eof')fail('unterminated class '+name,this.peek());
      if(methods.length>=256)fail('method count limit',this.peek());
      const methodPub=this.eat('pub');
      this.eatMethodMarker();
      const methodName=this.callableName();
      this.expect('(');
      if(this.eat('self')){
        if(this.eat(',')){
          if(this.peek().value===')')fail('trailing receiver comma',this.peek());
        }else if(this.peek().value!==')')fail('unsupported self parameter spelling',this.peek());
      }
      const params=[],paramTypes=[];
      if(!this.eat(')')){
        do {
          if(params.length>=32)fail('method arity budget',this.peek());
          const {type,name:param}=this.typedParameter(()=>this.classIdentifier());
          if(param==='self'||params.includes(param))fail('duplicate or reserved method parameter '+param,this.peek());
          params.push(param);paramTypes.push(type);
        } while(this.eat(','));
        this.expect(')');
      }
      const arrow=this.eat('->');
      if(!arrow)this.expect(':');
      const returnType=this.eat('void')?'void':this.valueType();
      const braced=this.eat('{');
      if(!braced){
        if(!arrow)fail('colon method bodies require braces',this.peek());
        this.expect('::');
      }
      const terminator=braced?'}':'end';
      let body=null;
      if(returnType==='void'){
        if((this.eat('return')||this.eat('^'))){
          if(this.peek().value!==';')fail('RETURN_VOID_VALUE: void method cannot return a value',this.peek());
          this.expect(';');
        }
        if(this.peek().value!==terminator)
          fail('void method body unsupported in convergence slice',this.peek());
      }else{
        const statements=this.statementSequence([terminator]);
        body=this.lowerStatements(statements,null);
        if(!body)fail('RETURN_EXPLICIT_REQUIRED: method requires a return on every path',this.peek());
        this.checkExpandedBudget(body);
      }
      this.expect(terminator);
      const slot=methodName+'/'+params.length;
      if(slots.has(slot))fail('duplicate method name/arity '+slot,this.peek());
      slots.add(slot);
      methods.push({name:methodName,pub:methodPub,params,paramTypes,returnType,body});
    }
    return {name,pub,methods,...(writtenPrivate?{writtenPrivate:true}:{})};
  }
  generatorStart() {
    let j=this.i;
    if(this.tokens[j]?.value==='pub')j++;
    if(['async','suspend','suspends'].includes(this.tokens[j]?.value))j++;
    return (this.tokens[j]?.value==='gen'&&this.tokens[j+1]?.value==='*')||
      this.tokens[j]?.value==='generator';
  }
  generator(moduleName) {
    const pub=this.eat('pub'),start=this.peek();
    let mode='sync';
    if(this.eat('async'))mode='async';
    else if(this.eat('suspend')||this.eat('suspends'))mode='suspend';
    if(this.eat('gen'))this.expect('*');
    else if(this.eat('generator'))this.expect('*');
    else fail('generator marker expected',start);
    if(mode!=='sync')
      fail(mode+' gen* requires the JS async continuation/cleanup ABI and is not implemented yet',start);
    const name=this.generatorIdentifier();
    if(isProtectedPreludeIdentity(name))
      fail('E_PRELUDE_SPOOF: cannot define compiler-owned core generator '+name,start);
    this.expect('(');
    const params=[],paramTypes=[];
    if(!this.eat(')')){
      do{
        if(params.length>=128)fail('generator parameter limit',this.peek());
        const {type,name:param}=this.typedParameter(()=>this.generatorIdentifier());
        if(type!=='int')fail('sync generator v1 currently supports int parameters only',this.peek());
        if(params.includes(param))fail('duplicate generator parameter '+param);
        params.push(param);paramTypes.push(type);
      }while(this.eat(','));
      this.expect(')');
    }
    const arrow=this.eat('->');
    if(!arrow)this.expect(':');
    this.expect('Iterator');this.expect('<');
    const elementType=this.valueType();
    if(elementType!=='int')fail('sync generator v1 currently supports Iterator<int> only',this.peek());
    this.expect('>');
    const braced=this.eat('{');
    if(!braced&&!arrow)fail('expected { for colon generator body',this.peek());
    if(!braced){if(!this.eat('as'))this.eat('::');}
    const terminator=braced?'}':'end',yields=[];
    let returned=false;
    while(this.peek().kind!=='eof'&&this.peek().value!==terminator){
      if(returned)fail('unreachable statement after generator return',this.peek());
      if(this.eat('yield')){
        if(this.eat('*'))fail('yield* is not implemented in JS sync generator v1',this.peek());
        if(yields.length>=MAX_TOKENS)fail('generator yield budget exceeded',this.peek());
        yields.push(this.expression());this.expect(';');
      }else if((this.eat('return')||this.eat('^'))){
        if(this.peek().value!==';')
          fail('generator return cannot carry a value; use yield',this.peek());
        this.expect(';');returned=true;
      }else fail('sync generator v1 supports only yield expressions and bare return',this.peek());
    }
    if(this.peek().kind==='eof')fail('unterminated generator '+name,this.peek());
    this.expect(terminator);
    return {module:moduleName,name,pub,params,paramTypes,elementType,yields};
  }

  functions(moduleName, declaredPub=false, declaredPrivate=false) {
    let pub=declaredPub, writtenPrivate=declaredPrivate;
    while(['pub','private'].includes(this.peek().value)) {
      if(pub||writtenPrivate)fail('conflicting visibility modifier',this.peek());
      pub=this.eat('pub');writtenPrivate=!pub;if(writtenPrivate)this.expect('private');
    }
    const start=this.peek();
    if(start.value==='suspends')fail('E_SUSPENDS_UNSUPPORTED: JS callable continuation lowering is unavailable',start);
    const fnc=this.eat('fnc'),routine=!fnc&&this.eat('routine');
    const requestedReturnType=this.peek().value;
    const returnFirst=['int','void','infer'].includes(requestedReturnType) &&
      ['<-','•'].includes(this.tokens[this.i+1]?.value);
    if(!fnc&&!routine&&!returnFirst)fail('unsupported declaration',start,'E_TOPLEVEL');
    if(returnFirst){this.i+=2;}
    const kind=routine?'routine':'fnc',name=this.name();
    // The program-v2 ESM slice has no once-only, reachability-scoped
    // startup state machine. Never emit init as a normal callable/export.
    if(name==='init')fail('init lifecycle requires once-only reachable initialization lowering',start);
    if(isProtectedPreludeIdentity(name))
      fail('E_PRELUDE_SPOOF: cannot define compiler-owned core name '+name,start);
    // This bounded profile reserves unqualified intrinsic names. Qualified
    // user And/Or declarations keep ordinary call dispatch.
    if(name==='Xor'||(moduleName===null&&['And','Or'].includes(name)))
      fail("'"+name+"' is a reserved builtin name",start);
    this.expect('(');
    const params = [], paramTypes = [];
    if (!this.eat(')')) {
      do {
        if (params.length >= 128) fail('parameter limit',this.peek());
        const {type,name:p}=this.typedParameter(()=>this.name());
        if (params.includes(p)) fail('duplicate parameter ' + p);
        params.push(p); paramTypes.push(type);
      } while(this.eat(','));
      this.expect(')');
    }
    // Bound source-level control flow to explicitly returning value functions;
    // pure void functions may fall through. No general effect lowering yet.
    let arrow=returnFirst?false:this.eat('->');
    if(!returnFirst&&!arrow)this.expect(':');
    let returnType=returnFirst?requestedReturnType:this.eat('void')?'void':this.eat('infer')?'infer':this.valueType();
    // Post-type arrow is part of the canonical name-first header.
    if(!returnFirst&&!arrow)arrow=this.eat('->');
    if(returnFirst && ['->',':'].includes(this.peek().value))
      fail('return-first callable has a duplicate return annotation',this.peek(),'E_RETURN_ANNOTATION');
    let throws;
    if(this.eat('throws')){
      this.expect('(');
      throws=this.eat(')')?[]:['Error'];
      if(throws.length){this.expect('Error');this.expect(')');}
    }
    const braced=this.eat('{');
    const began=!braced&&returnFirst&&this.eat('begin');
    if(!braced&&!began&&!arrow&&!returnFirst)
      fail('expected { or begin for return-first function body',this.peek());
    if(!braced&&!began&&!returnFirst){if(!this.eat('as'))this.eat('::');}
    const terminator=braced?'}':'end';
    let body=null;
    if(returnType==='void'){
      // Program-v2 has no general statement/effect lowering yet. Match the
      // existing pure-void class-method subset without fabricating a value.
      if((this.eat('return')||this.eat('^'))){
        if(this.peek().value!==';')fail('RETURN_VOID_VALUE: void function cannot return a value',this.peek());
        this.expect(';');
      }
      if(this.peek().value!==terminator)
        fail('void function effects require checked statement lowering',this.peek());
    }else{
      const statements=this.statementSequence([terminator]);
      body=this.lowerStatements(statements,null);
      if(!body)fail('RETURN_EXPLICIT_REQUIRED: int function requires a return on every path',this.peek(),'E_RETURN');
      this.checkExpandedBudget(body);
      if(returnType==='infer'){
        // Infer only from statically evidenced int scalar operations here.
        // Value-producing calls, effects and conditional alternatives are not
        // sufficiently typed in this slice; reject them instead of assuming
        // int. Checked pure-void sequencing may precede a proven int value.
        function provenInt(node){
          if(node.kind==='int')return true;
          if(node.kind==='variable')
            return params.some((name,index)=>name===node.name&&paramTypes[index]==='int');
          if(node.kind==='neg')return provenInt(node.inner);
          if(node.kind==='binary'&&['+','*','-'].includes(node.op))
            return provenInt(node.left)&&provenInt(node.right);
          // A direct pure-void call cannot determine the return type.
          // Resolve/validate must separately prove it returns void.
          if(node.kind==='seqVoidCall')
            return provenInt(node.value);
          return false;
        }
        if(!provenInt(body))
          fail('INFER_UNSUPPORTED: return-first infer requires provably int pure expression',this.peek());
        returnType='int';
      }
    }
    this.expect(terminator);
    // Match native restricted return-first `end;` and final `end.` spellings.
    // A dot is a terminal marker, not permission to concatenate declarations.
    if(returnFirst&&!braced){
      if(this.peek().value===';')this.i++;
      else if(this.peek().value==='.'&&this.tokens[this.i+1]?.kind==='eof')this.i++;
    }
    // Exporting a routine as a module object property would make a
    // direct-call-only routine first class. Leave this for runtime/ABI design.
    if (pub && kind === 'routine') fail('public routine cannot be exported');
    const fn={module:moduleName, name, kind, pub, params, body,...(writtenPrivate?{writtenPrivate:true}:{}),...(throws===undefined?{}:{throws})};
    // Keep the historical integer-only IR exactly stable for old consumers.
    // New typed fields are an all-or-nothing additive extension for bool.
    if(returnType!=='int'||paramTypes.some(type=>type!=='int')){
      fn.paramTypes=paramTypes;fn.returnType=returnType;
    }
    return fn;
  }
  statementSequence(terminators,depth=0) {
    if(depth>64)fail('conditional nesting budget exceeded',this.peek());
    const statements=[];
    while(this.peek().kind!=='eof'&&!terminators.includes(this.peek().value)) {
      if(++this.statementNodes>MAX_TOKENS)fail('statement budget exceeded',this.peek());
      // Recognize declaration heads without granting implicit initialization.
      const bindingTokens=this.tokens.slice(this.i,this.i+5);
      const qualifier=['let','val','const'].includes(bindingTokens[0]?.value);
      const localHead=qualifier?1:0;
      const builtin=['int','i64','i32','float','bool','string','Symbol'].includes(bindingTokens[localHead]?.value);
      if(builtin&&bindingTokens[localHead+1]?.kind==='id'&&bindingTokens[localHead+2]?.value===';')
        fail('E_MISSING_INITIALIZER: explicit initializer required',bindingTokens[localHead+2]);
      if(qualifier&&bindingTokens[1]?.kind==='id'&&bindingTokens[2]?.value===':'&&
          ['int','i64','i32','float','bool','string','Symbol'].includes(bindingTokens[3]?.value)&&bindingTokens[4]?.value===';')
        fail('E_MISSING_INITIALIZER: explicit initializer required',bindingTokens[4]);
      if((this.eat('return')||this.eat('^'))) {
        if(this.peek().value===';')
          fail('RETURN_VALUE_REQUIRED: nonvoid return requires a value',this.peek());
        const body=this.expression();
        this.expect(';');
        statements.push({kind:'return',body});
      } else if(['throw','throw!','raise','raise!','panic','panic!'].includes(this.peek().value)){
        const effect=this.tokens[this.i++].value.replace(/!$/,'');
        if(!this.eat('new')){
          if(effect!=='throw')fail('E_EFFECT_PROFILE: only ordinary caught Error can be rethrown',this.peek());
          const name=this.name();this.expect(';');
          statements.push({kind:'effect',body:{kind:'rethrow',name}});
        }else{
          this.expect('Error');this.expect('(');
          const literal=this.peek();
          if(literal.kind!=='errorMessage')fail('E_ERROR_MESSAGE: expected core Error literal',literal);
          this.i++;this.expect(')');this.expect(';');
          statements.push({kind:'effect',body:{kind:'guestEffect',effect,message:literal.value}});
        }
      } else if(this.eat('try')){
        this.expect('{');const body=this.statementSequence(['}'],depth+1);this.expect('}');
        this.expect('catch');this.expect('(');const name=this.name();this.expect(')');
        if(GENERATOR_RESERVED.has(name)||isProtectedPreludeIdentity(name)||name==='Error')
          fail('E_CATCH_BINDING: invalid catch binding',this.peek());
        this.expect('{');const handler=this.statementSequence(['}'],depth+1);this.expect('}');
        // The expression IR has no explicit fallthrough continuation yet. Never
        // execute a subsequent statement inside the protected catch region.
        if(!this.returnsOnAllPaths(body)||!this.returnsOnAllPaths(handler))
          fail('E_TRY_FALLTHROUGH_UNSUPPORTED: both protected and handler paths must terminate',this.peek());
        statements.push({kind:'tryCatch',name,body,handler});
      } else if(this.eat('guard')) {
        // Guard's predicate must be a bool. Reuse the typed IR's conditional
        // checker and evaluate each comma-separated predicate at most once,
        // short-circuiting from left to right on the first failure.
        let condition=this.expression();
        while(this.eat(',')) {
          const next=this.expression();
          condition={kind:'if',condition,yes:next,no:{kind:'bool',value:false}};
        }
        let failure;
        if(this.eat('{')){
          failure=this.statementSequence(['}'],depth+1);
          this.expect('}');
          // A braced guard inside an outer if may be followed by the outer
          // else; do not consume it here. A duplicate standalone else will
          // remain an ordinary enclosing syntax error.
        }else if(this.eat('else')){
          failure=this.statementSequence(['end'],depth+1);
          this.expect('end');
        }else fail("E_GUARD: expected '{ ... }' or 'else ... end'",this.peek());
        if(!this.returnsOnAllPaths(failure))
          fail('E_GUARD: every failure path must explicitly return from the function',this.peek());
        statements.push({kind:'branch',condition:{kind:'not',inner:condition},
          yes:failure,no:[]});
      } else if(this.peek().value==='val'||this.peek().value==='const'||
                 (this.peek().value==='let'&&['int','bool','string'].includes(this.tokens[this.i+1]?.value))) {
        const isConst=this.eat('const');
        const readOnlyLet=!isConst&&this.eat('let');
        if(!isConst&&!readOnlyLet)this.expect('val');
        // Function-scope scalar locals; only typed string let may rebind.
        if(depth!==0)fail('LOCAL_BRANCH_SCOPE_UNSUPPORTED',this.peek());
        // Keep existing Option, Result, string, bool, and directional bindings.
        const typedTrapResult=this.tokens.slice(this.i,this.i+6).map(t=>t.value).join(' ')==='Result < int , Error >';
        if(typedTrapResult)this.i+=6;
        let localType=typedTrapResult?'trapResult':(this.peek().value==='boolean'&&this.tokens[this.i+1]?.kind==='id')?this.valueType():['Option','Result'].includes(this.peek().value)?this.valueType():this.eat('float')?'float':this.eat('int')?'int':this.eat('bool')?'bool':this.eat('string')?'string':'inferredTrap';
        const name=this.name();
        if(!isConst&&!readOnlyLet&&localType==='inferredTrap'&&this.eat(':')){
          localType=this.valueType();
          if(!['int','float','bool','string'].includes(localType))fail('name-first binding requires primitive type in this profile',this.peek());
        }
        if(this.peek().value===';')fail('E_MISSING_INITIALIZER: explicit initializer required',this.peek());
        const directional=this.takeDirection('<');
        if(!directional)this.expect('=');
        let init;
        if(localType==='inferredTrap'&&!directional&&!isConst){
          if(this.eat('trap!')||this.eat('trap'))init={kind:'trap',inner:this.expression()};
          else {localType='inferredInt';init=this.expression();}
        }else init=this.expression();
        this.expect(';');
        if(isConst&&(!directional||localType!=='int'))fail('only directional const int is admitted here',this.peek());
        statements.push({kind:'local',name,init,localType:isConst?'constInt':directional&&localType==='inferredTrap'?'directionalInferred':localType,writable:readOnlyLet&&localType==='string'});
      } else if(this.peek().kind==='id'&&this.tokens[this.i+1]?.value==='='){
        if(depth!==0)fail('E_STRING_VIEW_SCOPE: rebinding requires root function scope',this.peek());
        const name=this.name();this.expect('=');
        const init=this.expression();this.expect(';');
        statements.push({kind:'local',name,init,localType:'string',writable:true,rebind:true});
      } else if(['if','ifnot','unless'].includes(this.peek().value)) {
        const head=this.tokens[this.i++].value;
        const predicate=this.expression();
        // Negate the predicate once using the existing typed Boolean IR.
        const condition=head==='if'?predicate:{kind:'not',inner:predicate};
        if(this.eat('{')){
          const yes=this.statementSequence(['}'],depth+1);this.expect('}');
          let no=[];
          if(this.eat('else')){this.expect('{');no=this.statementSequence(['}'],depth+1);this.expect('}');}
          statements.push({kind:'branch',condition,yes,no});
        }else{
          // Keyword-delimited conditionals alone own their fi.
          if(head!=='if')this.expect('do');
          else if(!this.eat('do')&&!this.eat('then')){this.expect(';');this.expect('do');}
          const yes=this.statementSequence(['else','fi'],depth+1);
          const no=this.eat('else')?this.statementSequence(['fi'],depth+1):[];
          this.expect('fi');
          statements.push({kind:'branch',condition,yes,no});
        }
       } else if(this.peek().kind==='id'||this.peek().kind==='int'||this.peek().value==='('){
        const expr=this.expression();
        if(this.takeDirection('>')){
          if(depth!==0)fail('LOCAL_BRANCH_SCOPE_UNSUPPORTED',this.peek());
          const isConst=this.eat('const');
          if(!isConst)this.expect('val');
          const localType=this.eat('int')?'int':this.eat('float')?'float':'directionalInferred';
          const name=this.name();
          this.expect(';');
          if(isConst&&localType!=='int')fail('directional const requires int',this.peek());
          statements.push({kind:'local',name,init:expr,localType:isConst?'constInt':localType});
          continue;
        }
        // Only statically resolved pure-void direct calls are allowed.
        if(expr.kind!=='call'||expr.qualifier!==null)
          fail('VOID_STATEMENT_DIRECT_CALL_REQUIRED',this.peek());
        this.expect(';');
        statements.push({kind:'voidCall',expr});
      } else fail('unsupported statement in int function',this.peek());
    }
    if(this.peek().kind==='eof')fail('unterminated function or conditional',this.peek(),'E_EOF');
    return statements;
  }
  returnsOnAllPaths(stmts) {
    if(!stmts.length)return false;
    const last=stmts[stmts.length-1];
    return last.kind==='return'||last.kind==='effect'||last.kind==='tryCatch'||
      (last.kind==='branch'&&this.returnsOnAllPaths(last.yes)&&this.returnsOnAllPaths(last.no));
  }
  lowerStatements(stmts,fallback) {
    let result=fallback;
    for(let i=stmts.length-1;i>=0;i--) {
      const stmt=stmts[i];
      if(i<stmts.length-1 &&
         (stmt.kind==='return'||stmt.kind==='effect'||stmt.kind==='tryCatch' ||
          (this.returnsOnAllPaths(stmt.yes||[])&&this.returnsOnAllPaths(stmt.no||[]))))
        fail('unreachable statement after terminating return/if');
      if(stmt.kind==='return'||stmt.kind==='effect')result=stmt.body;
      else if(stmt.kind==='tryCatch')result={kind:'tryCatch',name:stmt.name,body:this.lowerStatements(stmt.body,null),handler:this.lowerStatements(stmt.handler,null)};
      else if(stmt.kind==='local'){
        if(result===null)fail('RETURN_EXPLICIT_REQUIRED: local must precede a value return');
        result={kind:'local',name:stmt.name,init:stmt.init,localType:stmt.localType,writable:stmt.writable===true,rebind:stmt.rebind===true,body:result};
      }
      else if(stmt.kind==='voidCall'){
        if(result===null)fail('RETURN_EXPLICIT_REQUIRED: void call must precede a value return');
        result={kind:'seqVoidCall',call:stmt.expr,value:result};
      }else {
        const yes=this.lowerStatements(stmt.yes,result);
        const no=this.lowerStatements(stmt.no,result);
        if(!yes||!no)fail('RETURN_EXPLICIT_REQUIRED: int function requires return on every if branch');
        result={kind:'if',condition:stmt.condition,yes,no};
      }
    }
    return result;
  }
  // Fall-through after sequential if statements can share a continuation
  // between both branches. The AST is a DAG in memory, but resolving or
  // serializing it expands every occurrence into an IR tree. Detect exponential
  // amplification *before* resolution, JSON, or codegen work begins.
  checkExpandedBudget(root) {
    const memo=new WeakMap();
    function size(expr,depth=0) {
      if(depth>MAX_DEPTH)fail('expanded expression depth exceeded');
      if(memo.has(expr))return memo.get(expr);
      let total=1;
      function add(next) {
        if(next===null||typeof next!=='object')fail('invalid expression during lowering');
        total+=size(next,depth+1);
        if(total>MAX_TOKENS)fail('expanded conditional IR node budget exceeded');
      }
      switch(expr.kind){
        case 'int':case 'float':case 'bool':case 'string':case 'symbol':case 'variable':case 'guestEffect':case 'rethrow':break;
        case 'tryCatch':add(expr.body);add(expr.handler);break;
        case 'neg':case 'not':case 'trap':case 'immediateAwait':case 'intTypeTest':case 'inlineScalarTask':add(expr.inner);break;
        case 'binary':case 'compare':add(expr.left);add(expr.right);break;
        case 'if':add(expr.condition);add(expr.yes);add(expr.no);break;
        case 'seqVoidCall':add(expr.call);add(expr.value);break;
        case 'scalarRecur':
          for(const s of expr.states)add(s.init);
          for(const g of expr.guards){add(g.condition);add(g.value);}
          for(const a of expr.args)add(a);break;
        case 'local':add(expr.init);add(expr.body);break;
        case 'newClass':for(const arg of expr.args)add(arg.expr??arg);break;
        case 'methodCall':
          add(expr.receiver);for(const arg of expr.args)add(arg.expr??arg);break;
        case 'call':case 'intrinsic':for(const arg of expr.args)add(arg.expr??arg);break;
        case 'match':case 'adtMatch':
          add(expr.subject);
          for(const arm of expr.arms)add(arm.body);
          break;
        default:fail('unrecognized lowered expression');
      }
      memo.set(expr,total);
      return total;
    }
    size(root);
  }
  exportDeclaration() {
    this.expect('export');this.expect('pub');
    let visibility='public';
    if(this.eat('[')) {
      this.expect('pkg');this.expect(']');visibility='package';
    }
    let kind=null;
    if(this.eat('class'))kind='class';
    else if(this.eat('fnc'))kind='fnc';
    const name=this.name();this.expect(';');
    return {name,kind,visibility};
  }
  parse() {
    // Oreslang's flat source namespace is compile-time package metadata,
    // not a runtime module/object. It must occur only once at file start.
    let namespace = null;
    // Source-file namespace metadata is NOT a runtime module nor an import.
    // The canonical spelling `use namespace types;` is accepted only at
    // position zero, before any function or import. Preserve the older
    // `namespace types;` spelling for existing program-v2 consumers.
    if (this.eat('use')) {
      this.expect('namespace');
      namespace = this.name();
      this.expect(';');
    } else if (this.eat('namespace')) {
      namespace = this.name();
      this.expect(';');
    }
    const modules=[],functions=[],generators=[],classes=[],usedModules=new Set(),usedClasses=new Set();
    const exportDeclarations=[];
    while(this.peek().kind!=='eof'){
      if(this.peek().value==='export') {
        if(exportDeclarations.length>=1024)fail('file export budget exceeded',this.peek());
        exportDeclarations.push(this.exportDeclaration());
        continue;
      }
      if(this.peek().value==='import'){
        const next=this.tokens[this.i+1]?.value;
        fail(next==='namespace'
          ? 'import namespace requires verified manifest package index and per-provider linker'
          : 'cross-file import requires verified source graph lowering',this.peek());
      }
      let prefixVisibility=null;
      if(['pub','private'].includes(this.peek().value)&&this.tokens[this.i+1]?.value==='define'){
        prefixVisibility=this.peek().value;this.i++;
      }
      if(this.eat('define')){
        const afterVisibility=['pub','private'].includes(this.peek().value)?this.tokens[this.i++].value:null;
        if(prefixVisibility&&afterVisibility)fail(prefixVisibility==='pub'&&afterVisibility==='pub'?'duplicate pub modifier':'duplicate/conflicting visibility modifier',this.peek());
        const declarationPub=(prefixVisibility||afterVisibility)==='pub';
        const declarationPrivate=(prefixVisibility||afterVisibility)==='private';
        if(this.peek().value==='symbol')
          fail('Symbol declaration/export manifest not implemented',this.peek());
        if(this.eat('class')){
          if(classes.length>=512)fail('class declaration budget exceeded',this.peek());
          const cls=this.classDeclaration(declarationPub,declarationPrivate);
          if(usedClasses.has(cls.name)||usedModules.has(cls.name))
            fail('duplicate class/module name '+cls.name,this.peek());
          usedClasses.add(cls.name);classes.push(cls);
          continue;
        }
        if(this.peek().value==='fnc'||this.peek().value==='routine'||
           (['int','void','infer'].includes(this.peek().value)&&['<-','•'].includes(this.tokens[this.i+1]?.value))){
          if(functions.length+generators.length>=512)fail('declaration budget exceeded',this.peek());
          functions.push(this.functions(null,declarationPub,declarationPrivate));
          continue;
        }
        if(declarationPub||declarationPrivate)fail('explicit visibility define is only supported for classes/functions in program-v2',this.peek());
        this.expect('module');const name=this.name();
        if(isProtectedPreludeIdentity(name)||name==='Symbol'||name==='Integer')
          fail('E_PRELUDE_SPOOF: reserved portable intrinsic module name '+name);
        if(usedModules.has(name)||usedClasses.has(name))fail('duplicate module/class name '+name);
        usedModules.add(name);this.eat('as');modules.push(name);
        let count=0;
        while(!this.eat('end')){
          if(this.peek().kind==='eof')fail('unterminated module '+name,this.peek());
          if(++count>512||functions.length+generators.length>=512)
            fail('declaration budget exceeded',this.peek());
          if(this.generatorStart())generators.push(this.generator(name));
          else functions.push(this.functions(name));
        }
      }else{
        if(functions.length+generators.length>=512)fail('declaration budget exceeded',this.peek());
        if(this.generatorStart())generators.push(this.generator(null));
        else functions.push(this.functions(null));
      }
    }
    if(this.booleanAliasToken&&(usedClasses.has('boolean')||usedModules.has('boolean')))
      fail('E_TYPE_ALIAS_SHADOWED: boolean requires nominal type resolution',this.booleanAliasToken);
    if(!functions.length&&!generators.length&&!modules.length&&!classes.length)fail('empty program');
    return {namespace,modules,functions,generators,classes,exportDeclarations};
  }
  integer(negative = false) {
    const token=this.peek();
    if (token.kind !== 'int') fail('integer literal expected',token);
    this.i++;
    const n=BigInt(token.value.replaceAll('_','')) * (negative ? -1n : 1n);
    if (n < MIN || n > MAX) fail('int literal outside provisional signed i64',token);
    return n.toString();
  }
  atDirection(op){
    const a=this.peek(),b=this.tokens[this.i+1],c=this.tokens[this.i+2];
    const adjacent=(x,y)=>x?.line===y?.line&&x?.col+1===y?.col;
    return a.value===op&&b?.value===op&&adjacent(a,b)&&!(c?.value===op&&adjacent(b,c));
  }
  takeDirection(op){
    if(!this.atDirection(op))return false;
    this.i+=2;return true;
  }
  expression(min=0) {
    if (++this.depth > MAX_DEPTH || ++this.expressions > MAX_TOKENS) fail('expression budget exceeded',this.peek());
    try {
      let left = this.primary();
      let stages=0;
      while (true) {
        const token=this.peek();
        let op=token.value, width=1;
        if(op==='|>' && min===0){
          this.i++;
          if(++stages>128)fail('pipeline exceeds 128 stages',token);
          if(this.eat('match')){
            // Subject injection uses the normal checked match IR, not a lambda.
            if(++this.depth>MAX_DEPTH)fail('expression budget exceeded',token);
            try{left=this.matchExpression(left);}finally{this.depth--;}
          }else if(this.eat('trap!')){
            // A synthetic immutable local establishes the upstream value
            // outside the trap. The internal name cannot be source-spelled.
            const name='@receiver:'+this.expressions+':'+this.i;
            const target=this.name();
            this.expect('(');
            const args=this.argumentsAfterOpen();
            left={kind:'local',name,init:left,localType:'int',body:{
              kind:'trap',inner:{kind:'call',qualifier:null,name:target,args:[
                {name:null,expr:{kind:'variable',name}},...args
              ]}
            }};
          }else if(this.eat('await')){
            if(['(','<'].includes(this.peek().value))fail('pipeline await takes only the left-hand value',this.peek());
            left={kind:'immediateAwait',inner:left};
          }else if(['if','ifnot','unless'].includes(this.peek().value)){
            const invert=this.tokens[this.i++].value!=='if';
            let condition=this.receiverPredicate(left);
            if(invert)condition={kind:'not',inner:condition};
            this.expect('then');
            const yes=this.expression();
            this.expect('else');
            const no=this.expression();
            this.expect('fi');
            left={kind:'if',condition,yes,no};
          }else if(this.peek().value==='?'||this.receiverPredicateStarts()){
            left=this.receiverPredicate(left);
            if(this.eat('?')){
              const yes=this.ternaryArm(0);
              this.expect(':');
              const no=this.ternaryArm(1);
              left={kind:'if',condition:left,yes,no};
            }
          }else{
            // Program-v2 admits named root/module calls; no computed callees.
            const at=this.peek();
            if(at.kind!=='id'||GENERATOR_RESERVED.has(at.value))fail('pipeline requires a named function or match',at);
            let name=this.name(),qualifier=null;
            if(this.eat('.')){qualifier=name;name=this.name();}
            const args=this.eat('(')?this.argumentsAfterOpen():[];
            left={kind:'call',qualifier,name,args:[{name:null,expr:left},...args]};
          }
          continue;
        }
        // Recognize contiguous triple shifts only at expression boundaries.
        // Keep individual > tokens for nested nominal generic closers.
        if(op==='<'||op==='>'){
          const adjacent=n=>{const t=this.tokens[this.i+n];return t?.kind==='sym'&&t.value===op&&t.line===token.line&&t.col===token.col+n;};
          if(adjacent(1)&&adjacent(2)){op=op.repeat(3);width=3;}
          else if(adjacent(1)){
            if(op==='>'&&['val','const','let'].includes(this.tokens[this.i+2]?.value))break;
            fail('typed I/O requires capability and suspension lowering; not a numeric shift',token);
          }
        }
        if (op==='?' && min<=1) {
          this.i++;
          const yes=this.ternaryArm(0);
          this.expect(':');
          const no=this.ternaryArm(1); // right associative, branches remain lazy
          left={kind:'if',condition:left,yes,no};
          continue;
        }
        // Word operators lower to already-validated typed comparisons and
        // lazy conditionals, never JS truthiness or eager bitwise operators.
        const compare=['<','>','<=','>=','==','!=','eq','neq'].includes(op);
        const logical=op==='and'||op==='or';
        if(op==='^')fail("E_OPERATOR_CARET: single infix '^' is invalid; use '^^' for integer XOR",token);
        const prec=op==='*'||op==='/'?20:op==='+'||op==='-'?15:
          op==='<<<'||op==='>>>'||op==='ashr'?12:
          ['<','>','<=','>='].includes(op)?9:compare?8:
          op==='&'?7:op==='^^'?6:op==='|'?5:op==='and'?4:op==='or'?3:-1;
        if (prec < min) break;
        this.i+=width;
        const right=this.expression(prec+1);
        if(logical){
          left=op==='and'
            ? {kind:'if',condition:left,yes:right,no:{kind:'bool',value:false}}
            : {kind:'if',condition:left,yes:{kind:'bool',value:true},no:right};
        }else{
          left={kind:compare?'compare':'binary',
            op:op==='eq'?'==':op==='neq'?'!=':op==='^^'?'^':op,left,right};
        }
      }
      return left;
    } finally {this.depth--;}
  }
  receiverPredicateStarts(){
    return ['is','<','>','<=','>=','eq','neq','==','!='].includes(this.peek().value);
  }
  receiverPredicate(left){
    if(this.eat('is')){
      this.eat('type');
      // The portable program-v2 profile has no general nominal type-test ABI.
      this.expect('int');
      return {kind:'intTypeTest',inner:left};
    }
    if(!this.receiverPredicateStarts())return left;
    const op=this.tokens[this.i++].value;
    const right=this.expression(['<','>','<=','>='].includes(op)?10:9);
    return {kind:'compare',op:op==='eq'?'==':op==='neq'?'!=':op,left,right};
  }
  ternaryArm(min) {
    if(!this.eat('{'))return this.expression(min);
    if(!this.eat('return')&&!this.eat('^'))
      fail('ternary block requires an explicit result; lexical prefixes are unsupported',this.peek(),'E_VALUE_ARM');
    const value=this.expression();
    this.eat(';');
    if(!this.eat('}'))fail("single-result ternary block requires closing '}'",this.peek(),'E_VALUE_ARM');
    return value;
  }
  primary() {
    if(this.peek().kind==='id' && this.peek().value==='spawn' && this.tokens[this.i+1]?.kind==='id')
      fail('actor spawn requires typed initialization, resource admission and runtime lowering',this.peek(),'E_SPAWN_OPTIONS_UNSUPPORTED');
    // Closed int state loop with guarded valued breaks and a final recur.
    if(this.peek().value==='loop'&&this.tokens[this.i+1]?.value==='(')
      return this.scalarRecurExpression();
    // Identical narrow single-break scalar profile to Rust #727.
    // Keep the computed expression boundary; do not admit bare named
    // callable references or a general state-loop result join.
    if(this.peek().value==='loop'&&this.tokens[this.i+1]?.value==='{'){
      const at=this.peek();this.i+=2;this.expect('break');
      const value=this.expression();
      if(!['int','float','bool','string','neg','not','binary','compare','if'].includes(value.kind))
        fail('value-loop operand requires typed CFG lowering',at);
      this.expect(';');this.expect('}');
      if(this.peek().value==='(')fail('calling a computed value loop',this.peek());
      return value;
    }
    if(this.eat('sync')){
      this.expect('task');
      if(this.eat('<')){this.expect('int');this.expect('>');}
      this.expect('{');
      const locals=[], declarations=new Map();
      while(true){
        let name,rebind=false;
        if(this.peek().value==='val'||this.peek().value==='let'){
          const mutable=this.eat('let');
          if(!mutable)this.expect('val');
          this.expect('int');
          name=this.name();
          if(GENERATOR_RESERVED.has(name)||name==='task'||name==='sync'||declarations.has(name))
            fail('E_TASK_LOCAL: reserved or duplicate task-local name',this.peek());
          declarations.set(name,mutable);
        }else if(this.peek().kind==='id'&&this.tokens[this.i+1]?.value==='='){
          name=this.name();
          if(declarations.get(name)!==true)
            fail('E_TASK_LOCAL_WRITE: only this task\'s mutable local may be assigned',this.peek());
          rebind=true;
        }else break;
        this.expect('=');
        const init=this.expression();
        this.expect(';');
        locals.push({name,init,rebind});
        if(locals.length>64)fail('E_TASK_LOCAL: local and write budget exceeded',this.peek());
      }
      if(!this.eat('return')&&!this.eat('^'))
        fail('E_TASK_RETURN_TYPE: scalar inline task requires a terminal int return',this.peek());
      let inner=this.expression();
      this.expect(';');this.expect('}');
      for(let i=locals.length-1;i>=0;i--)
        inner={kind:'local',name:locals[i].name,init:locals[i].init,localType:'int',taskRebind:locals[i].rebind,body:inner};
      return {kind:'inlineScalarTask',inner};
    }
    if(this.eat('trap!')||this.eat('trap'))return {kind:'trap',inner:this.expression(30)};
    if(this.eat('await'))return {kind:'immediateAwait',inner:this.expression(30)};
    if(this.eat('!')||this.eat('¬')||this.eat('not'))return {kind:'not',inner:this.expression(30)};
    if(this.eat('new')){
      const className=this.name();
      this.expect('(');
      let receiver={kind:'newClass',name:className,args:this.argumentsAfterOpen()};
      while(this.eat('.')){
        const name=this.callableName();
        this.expect('(');
        receiver={kind:'methodCall',receiver,name,args:this.argumentsAfterOpen()};
      }
      return receiver;
    }
    if(this.peek().kind==='localString')return {kind:'string',value:this.tokens[this.i++].literal};
    if(this.eat('true'))return {kind:'bool',value:true};
    if(this.eat('false'))return {kind:'bool',value:false};
    if (this.eat('-')) {
      if(this.peek().kind === 'int') return {kind:'int',value:this.integer(true)};
      return {kind:'neg',inner:this.expression(30)};
    }
    if(this.eat('+')) return this.expression(30);
    if(this.eat('(')) {
      const e=this.expression();this.expect(')'); return e;
    }
    if(this.eat('match')) return this.matchExpression();
    if(this.peek().kind==='int') return {kind:'int',value:this.integer()};
    if(this.peek().kind==='float')return {kind:'float',value:this.tokens[this.i++].value};
    if(this.peek().kind==='symbol'){
      const literal=this.tokens[this.i++];
      return {kind:'symbol',value:literal.value};
    }
    const token=this.peek();
    if (token.kind !== 'id') fail('expression expected',token);
    const first=this.name();
    let qualifier=null, symbol=first;
    if(this.eat('.')) {qualifier=first; symbol=this.name();}
    if (!this.eat('(')) {
      if (qualifier) fail('bare module properties not yet supported',token);
      // Existing Oreslang Option constructor spelling: bare None.
      if(first==='None')return {kind:'call',qualifier:'Option',name:'none',args:[]};
      return {kind:'variable', name:first};
    }
    if(qualifier===null&&coreBooleanIntrinsic(symbol))
      return this.namedBooleanReduction(symbol,token);
    if(this.coreBooleanModules.has(qualifier)){
      if(!coreBooleanIntrinsic(symbol))fail('E_CORE_BOOL_IMPORT: unknown core Boolean export '+symbol,token);
      return this.namedBooleanReduction(symbol,token);
    }
    const args=this.argumentsAfterOpen();
    // Named integer operations retain their semantics; raw % remains reserved.
    // The binary ops below are internal IR names, never source syntax.
    if(qualifier==='Integer'){
      const ops={rem:'%',mod:'mod',bitXor:'^'};
      if(!Object.hasOwn(ops,symbol))fail('Integer.'+symbol+' is unsupported; use Integer.rem, Integer.mod or Integer.bitXor',token);
      if(args.length!==2)fail('Integer.'+symbol+' requires two arguments',token);
      if(args.some(a=>a.name!==null))fail('Integer.'+symbol+' takes positional arguments',token);
      return {kind:'binary',op:ops[symbol],left:args[0].expr,right:args[1].expr};
    }
    // Canonical Oreslang constructors also work as pure aliases for the
    // same statically checked intrinsics; don't introduce any new host API.
    if(qualifier===null){
      const constructors={Some:['Option','some'],Ok:['Result','ok'],Err:['Result','err']};
      if(Object.hasOwn(constructors,symbol) && isCoreConstructor(symbol)){
        const [scope,name]=constructors[symbol];
        return {kind:'call',qualifier:scope,name,args};
      }
    }
    return {kind:'call',qualifier,name:symbol,args};
  }
  scalarRecurExpression(){
    const at=this.peek(),states=[],names=new Set(),guards=[];
    this.expect('loop');this.expect('(');
    do {
      if(states.length>=32)fail('E_RECUR_ARITY: state limit',at);
      this.expect('val');const name=this.name();this.expect(':');this.expect('int');
      if(names.has(name)||GENERATOR_RESERVED.has(name))fail('duplicate or reserved state binding',at);
      this.expect('=');const init=this.expression();
      states.push({name,init});names.add(name);
    }while(this.eat(','));
    this.expect(')');this.expect('{');
    while(this.eat('if')){
      if(guards.length>=32)fail('value loop guard limit',at);
      const condition=this.expression();this.expect('{');this.expect('break');
      const value=this.expression();this.expect(';');this.expect('}');this.eat('fi');
      guards.push({condition,value});
    }
    if(!guards.length)fail('E_LOOP_VALUE: value loop requires guarded valued break',at);
    this.expect('recur');this.expect('(');
    const args=[];
    if(!this.eat(')')){
      do {if(args.length>=32)fail('E_RECUR_ARITY: operand limit',at);args.push(this.expression());}while(this.eat(','));
      this.expect(')');
    }
    if(args.length!==states.length)fail('E_RECUR_ARITY: operand count mismatch',at);
    this.expect(';');this.expect('}');
    return {kind:'scalarRecur',states,guards,args};
  }
  namedBooleanReduction(name, token) {
    // Literal packs are private scalar temporaries, never first-class arrays.
    const pack=this.eat('['), identity=name==='And';
    let values=[];
    if(pack){
      if(!this.eat(']')){
        do {
          if(values.length>=32)fail('Boolean reduction operand limit',token);
          values.push(this.expression());
        } while(this.eat(','));
        this.expect(']');
      }
      this.expect(')');
    }else{
      const args=this.argumentsAfterOpen();
      if(args.length<2||args.length>32)fail(name+' requires 2..32 scalar operands',token);
      if(args.some(arg=>arg.name!==null))fail(name+' takes positional arguments',token);
      values=args.map(arg=>arg.expr);
    }
    const bool=value=>({kind:'bool',value});
    const strict=inner=>({kind:'not',inner:{kind:'not',inner}});
    // The existing typed conditional and comparison IR checks both operands,
    // including unreachable runtime branches. XOR uses strict bool operands,
    // not the integer comparison overload.
    const fold=items=>{
      if(!items.length)return bool(identity);
      return items.slice(1).reduce((left,right)=>{
        if(name==='And')return {kind:'if',condition:left,yes:right,no:bool(false)};
        if(name==='Or')return {kind:'if',condition:left,yes:bool(true),no:right};
        return {kind:'compare',op:'!=',left,right:strict(right)};
      },strict(items[0]));
    };
    if(!pack)return fold(values);
    const prefix='@bool-pack:'+this.i+':';
    const names=values.map((_,i)=>prefix+i);
    // Evaluate each input exactly once, in order, even when the reduction
    // result is already determined. Internal names cannot be source-spelled.
    let body=fold(names.map(name=>({kind:'variable',name})));
    for(let i=values.length-1;i>=0;i--)
      body={kind:'local',name:names[i],localType:'bool',init:values[i],body};
    return body;
  }
  matchExpression(injectedSubject=null) {
    // A typed scalar match is a canonical source form, not a new
    // dynamically-dispatched container or unchecked JS pattern primitive.
    const typed=this.eat('<');
    if(typed){this.expect('int');this.expect('>');}
    const subject=injectedSubject??this.expression();
    const hasOver=this.eat('over');
    if(injectedSubject!==null&&!hasOver)fail('pipeline match requires over and takes its subject from the left-hand value',this.peek());
    if(typed&&!hasOver)fail('typed scalar match requires over',this.peek());
    const braced=this.eat('{');
    const arms=[],seen=new Set();
    let family=null,wildcard=false;
    const ended=()=>braced?this.peek().value==='}':this.peek().value==='end';
    while(!ended()&&this.peek().kind!=='eof') {
      let tag=null, bind=null, value=null;
      const canonicalDefault=this.eat('default');
      const legacyElse=!canonicalDefault&&this.eat('else');
      if(canonicalDefault||legacyElse) {
        if(family==='adt')fail('ADT match requires exhaustive named variants, not default',this.peek());
        family='int';tag='wildcard';
      } else {
        this.expect('on');
        const at=this.peek();
        if(this.eat('default'))
          fail("match fallback must use 'default ->' without 'on'",at);
        if(this.eat('_')){
          if(family==='adt')fail('ADT match forbids wildcard');
          family='int';tag='wildcard';
        } else if(['Some','None','Ok','Err'].includes(at.value)){
          if(family==='int')fail('cannot mix integer and ADT patterns',at);
          family='adt';tag=this.name();
          if(tag==='None') {
            if(this.peek().value==='(')fail('None has no payload',this.peek());
          } else {
            this.expect('(');
            if(tag==='Err'&&this.eat(')'))bind=null;
            else {bind=this.name();this.expect(')');}
            if(bind==='_'||['None','Some','Ok','Err'].includes(bind))
              fail('unsupported ADT payload binding name',at);
          }
        } else {
          if(family==='adt')fail('cannot mix ADT and integer patterns',at);
          family='int';tag='integer';
          value=this.integer(this.eat('-'));
        }
      }
      if(tag==='wildcard'&&(this.peek().value==='when'||this.peek().value==='as'))
        fail('match default/wildcard arm cannot have a guard or binding',this.peek());
      if(wildcard)fail('unreachable arm after wildcard',this.peek());
      if(tag==='wildcard')wildcard=true;
      const key=tag==='integer'?'int:'+value:tag;
      if(seen.has(key))fail('duplicate match pattern '+key);
      seen.add(key);
      if(typed&&this.eat(':'))this.expect('int');
      const arrow=this.eat('->');
      if(!arrow&&(canonicalDefault||this.peek().value!=='{'))
        fail("match expression arms require '->'",this.peek());
      const block=this.eat('{');
      // Keep the existing single-expression block subset. No arbitrary
      // statement blocks or early-return control flow are admitted here.
      if(block&&this.peek().value==='return') {
        if(family!=='adt')fail('return in scalar expression match is unsupported');
        this.i++;
      }
      const body=this.expression();
      if(block){this.expect(';');this.expect('}');}
      const separated=this.eat(',');
      if(!block&&!separated&&!ended()&&!['on','default','else'].includes(this.peek().value))
        fail('expected match arm boundary',this.peek());
      arms.push({tag,bind,value,body});
      if(arms.length>256)fail('match arm limit');
    }
    this.expect(braced?'}':'end');
    if(family==='adt') {
      const tags=new Set(arms.map(arm=>arm.tag));
      const option=tags.has('Some')||tags.has('None');
      const result=tags.has('Ok')||tags.has('Err');
      if(option&&result)fail('cannot mix Option and Result variant patterns');
      const required=option?['Some','None']:['Ok','Err'];
      if(arms.length!==2||required.some(tag=>!tags.has(tag)))
        fail('non-exhaustive ADT match; expected '+required.join('/'));
      return {kind:'adtMatch',adt:option?'option<int>':'result<int,int>',
        typed,subject,arms:arms.map(arm=>({tag:arm.tag,bind:arm.bind,body:arm.body}))};
    }
    if(!wildcard)fail('scalar int match requires exhaustive wildcard/else');
    return {kind:'match',subject,arms:arms.map(arm=>({value:arm.value,body:arm.body}))};
  }
}
function resolveProgram(parsed) {
  const {modules,functions,generators=[],classes=[],exportDeclarations=[]}=parsed;
  const resolvedExports=new Map();
  for(const entry of exportDeclarations) {
    const targetClasses=classes.filter(c=>c.name===entry.name);
    const targetFns=functions.filter(f=>f.module===null&&f.name===entry.name);
    if(!targetClasses.length&&!targetFns.length)fail('unknown export '+entry.name);
    if(targetClasses.length&&targetFns.length)fail('ambiguous export '+entry.name);
    const kind=targetClasses.length?'class':'fnc';
    if(entry.kind!==null&&entry.kind!==kind)fail('export kind mismatch '+entry.name);
    const targets=targetClasses.length?targetClasses:targetFns;
    if(targets.some(t=>t.writtenPrivate))fail('explicit private declaration cannot be exported: '+entry.name);
    if(entry.visibility==='package'&&targets.some(t=>t.pub))
      fail('package export conflicts with public declaration '+entry.name);
    if(kind==='fnc'&&targets.some(t=>t.kind==='routine')&&entry.visibility==='public')
      fail('routine is not a public exported fnc '+entry.name);
    const prior=resolvedExports.get(entry.name);
    if(prior&&prior.visibility!==entry.visibility)fail('conflicting export visibility '+entry.name);
    if(!prior)resolvedExports.set(entry.name,{name:entry.name,kind,visibility:entry.visibility,targets});
  }
  for(const entry of resolvedExports.values())if(entry.visibility==='public')entry.targets.forEach(t=>{t.pub=true;});
  // Written visibility is compiler-only provenance; the checked IR schema
  // must not acquire a new field or a serialized authority marker.
  for(const target of [...functions,...classes])delete target.writtenPrivate;
  const slots=new Map(),exports=new Set(),symbolKinds=new Map(),classByName=new Map();
  const classTables=classes.map((cls,classIndex)=>{
    if(classByName.has(cls.name))fail('duplicate class '+cls.name);
    classByName.set(cls.name,classIndex);
    const methodSlots=new Map(),methodFamilies=new Set();
    cls.methods.forEach((method,methodIndex)=>{
      const key=method.name+'/'+method.params.length;
      if(methodSlots.has(key))fail('duplicate method name/arity '+cls.name+'.'+key);
      methodSlots.set(key,methodIndex);methodFamilies.add(method.name);
    });
    return {methodSlots,methodFamilies};
  });
  functions.forEach((fn,i)=>{
    const qualified=(fn.module===null?'@file/':'@module/'+fn.module+'/')+fn.name;
    const slot=qualified+'/'+fn.params.length;
    if(slots.has(slot)) fail('duplicate caller-visible arity '+slot,null,'E_DUPLICATE');
    if(symbolKinds.has(qualified) && symbolKinds.get(qualified)!==fn.kind)
      fail('fnc and direct-call-only routine cannot share an exported name '+qualified);
    symbolKinds.set(qualified,fn.kind);
    slots.set(slot,i);
    if(fn.pub && fn.module===null) exports.add(fn.name);
  });
  generators.forEach((gen,i)=>{
    const qualified=(gen.module===null?'@file/':'@module/'+gen.module+'/')+gen.name;
    const slot=qualified+'/'+gen.params.length;
    if(slots.has(slot))fail('duplicate caller-visible arity '+slot);
    // Callable identity is name + value arity. A generator and ordinary
    // function may therefore share a family when their arities differ.
    // Preserve an existing function-family marker so wrong-arity diagnostics
    // remain available to ordinary source calls.
    if(!symbolKinds.has(qualified))symbolKinds.set(qualified,'generator');
    // Generator targets are not source-callable in this bounded slice, so use
    // a distinct non-function slot marker rather than a function target index.
    slots.set(slot,'generator:'+i);
    if(gen.pub&&gen.module===null)exports.add(gen.name);
  });
  modules.forEach(m=>{
    if(exports.has(m)) fail('module/function export conflict '+m);
    exports.add(m);
  });
  classes.forEach(cls=>{
    if(exports.has(cls.name))fail('class export conflicts with module/function '+cls.name);
    if(cls.pub)exports.add(cls.name);
  });
  let nodeCount=0,nextCaptureSlot=0;
  function resolvedType(expr,owner) {
    switch(expr.kind){
      case 'scalarRecur':case 'int':case 'neg':case 'binary':case 'capture':case 'match':return 'int';
      case 'float':case 'floatNeg':case 'floatBin':case 'floatCapture':return 'float';
      case 'trap':return ['option<int>','trapOptionResult'].includes(expr.operandType)?'trapOptionResult':'trapResult';
      case 'errorCapture':return 'error';
      case 'optionCapture':return 'option<int>';
      case 'resultCapture':return 'result<int,int>';
      case 'trapCapture':return 'trapResult';
      case 'trapOptionCapture':return 'trapOptionResult';
      case 'trapMethod':return expr.name==='unwrap'?'int':'bool';
      case 'trapOptionMethod':return expr.name==='unwrap'?'option<int>':'bool';
      case 'bool':case 'not':case 'compare':case 'boolCompare':case 'stringCompare':case 'numericCompare':case 'symbolCompare':return 'bool';
      case 'symbol':return 'symbol';
      case 'string':case 'stringCapture':return 'string';
      case 'boolCapture':return 'bool';
      case 'param':return owner.paramTypes?.[expr.index]??'int';
      case 'newClass':return 'class:'+expr.classIndex;
      case 'methodCall':case 'namedMethodCall':
        return classes[expr.classIndex].methods[expr.methodIndex].returnType;
      case 'call':case 'namedCall':return functions[expr.target].returnType??'int';
      case 'intrinsic': {
        const results={
          'Option.some':'option<int>','Option.none':'option<int>',
          'Option.is_some':'bool','Option.unwrap_or':'int','Option.unwrap':'int','Result.unwrap':'int','Option.expect':'int','Result.expect':'int',
          'Result.ok':'result<int,int>','Result.err':'result<int,int>',
          'Result.is_ok':'bool','Result.unwrap_or':'int','Result.unwrap_err_or':'int'
        };
        return results[expr.name];
      }
      case 'if':return resolvedType(expr.yes,owner);
      case 'seqVoidCall':return resolvedType(expr.value,owner);
      case 'local':case 'floatLocal':case 'boolLocal':case 'stringLocal':case 'trapLocal':case 'trapOptionLocal':case 'optionLocal':case 'resultLocal':return resolvedType(expr.body,owner);
      case 'adtMatch':return resolvedType(expr.arms[0].body,owner);
      default:fail('cannot infer resolved expression type '+expr.kind);
    }
  }
  function bindArguments(formals,sourceArgs,label,owner,depth,bindings){
    const byName=new Map(formals.map((name,index)=>[name,index]));
    const paramOrder=[],seen=new Set();let nextPositional=0,hasNamed=false;
    for(const arg of sourceArgs){
      let formal;
      if(arg.name===null)formal=nextPositional++;
      else{
        hasNamed=true;formal=byName.get(arg.name);
        if(formal===undefined)fail('unknown named argument '+arg.name+' for '+label);
      }
      if(formal>=formals.length)fail('call arity mismatch for '+label);
      if(seen.has(formal))fail('duplicate argument binding for '+formals[formal]);
      seen.add(formal);paramOrder.push(formal);
    }
    if(seen.size!==formals.length)fail('missing argument for '+label);
    return {
      args:sourceArgs.map(a=>resolve(a.expr,owner,depth+1,bindings)),
      paramOrder,hasNamed
    };
  }
  function resolve(expr,owner,depth=0,bindings=[]) {
    if(++nodeCount>MAX_TOKENS || depth>MAX_DEPTH) fail('IR node/depth budget exceeded');
    switch(expr.kind) {
      case 'tryCatch':{
        if(owner.ownerKind==='method'||owner.returnType==='void'||owner.returnType==='infer'||owner.params.includes(expr.name)||bindings.some(b=>b.name===expr.name))fail('E_CATCH_BINDING: explicit return type and nonshadowing binding required');
        if(nextCaptureSlot>=MAX_TOKENS)fail('catch slot budget exceeded');
        const slot=nextCaptureSlot++;
        return {kind:'tryCatch',slot,body:resolve(expr.body,owner,depth+1,bindings),handler:resolve(expr.handler,owner,depth+1,[...bindings,{name:expr.name,slot,type:'caughtError'}])};
      }
      case 'rethrow':{
        const binding=bindings.findLast(b=>b.name===expr.name);
        if(binding?.type==='caughtError')return {kind:'rethrow',slot:binding.slot};
        if(binding?.type==='error')return {kind:'throwError',value:{kind:'errorCapture',slot:binding.slot}};
        const index=owner.params.indexOf(expr.name);
        if(!binding&&index>=0&&owner.paramTypes?.[index]==='error'&&owner.ownerKind!=='method')
          return {kind:'throwError',value:{kind:'param',index}};
        fail('E_EFFECT_PROFILE: rethrow requires a scoped caught or typed Error');
      }
      case 'guestEffect':
        if(owner.ownerKind==='method'||owner.returnType==='void'||owner.returnType==='infer'||owner.params.includes('Error')||bindings.some(b=>b.name==='Error')||classByName.has('Error')||modules.includes('Error')||[...functions,...generators].some(f=>f.name==='Error'))
          fail('E_EFFECT_PROFILE: explicit result type and unshadowed core Error required');
        return expr;
      case 'int':case 'float':case 'bool':case 'string':case 'symbol':return expr;
      case 'intTypeTest': {
        const inner=resolve(expr.inner,owner,depth+1,bindings);
        if(resolvedType(inner,owner)!=='int')fail('int type test requires statically proven int in program-v2');
        // All admitted integers are signed i64. Comparison with MIN retains
        // receiver evaluation (including errors) exactly once, no fake constant.
        return {kind:'compare',op:'>=',left:inner,right:{kind:'int',value:MIN.toString()}};
      }
      case 'scalarRecur': {
        if(owner.ownerKind==='method')fail('state loop method profile unsupported');
        const scope=[],states=[];
        // Closed source subset: no calls, captures outside this lexical loop,
        // ownership, suspension or guest effects.
        const admit=(e,d=0)=>{
          if(d>MAX_DEPTH)fail('state loop expression depth');
          switch(e.kind){
            case 'int':case 'bool':return;
            case 'variable':if(scope.some(s=>s.name===e.name))return;break;
            case 'neg':case 'not':admit(e.inner,d+1);return;
            case 'binary':case 'compare':admit(e.left,d+1);admit(e.right,d+1);return;
            case 'if':admit(e.condition,d+1);admit(e.yes,d+1);admit(e.no,d+1);return;
          }
          fail('state loop requires closed scalar operands');
        };
        const typed=(e,t)=>{admit(e);const v=resolve(e,owner,depth+1,scope);
          if(resolvedType(v,owner)!==t)fail('state loop operand type mismatch');return v;};
        for(const s of expr.states){
          const init=typed(s.init,'int');
          if(nextCaptureSlot>=MAX_TOKENS)fail('state loop slot budget exceeded');
          const slot=nextCaptureSlot++;
          states.push({slot,init});scope.push({name:s.name,slot,type:'int'});
        }
        return {kind:'scalarRecur',states,
          guards:expr.guards.map(g=>({condition:typed(g.condition,'bool'),value:typed(g.value,'int')})),
          args:expr.args.map(a=>typed(a,'int'))};
      }
      case 'inlineScalarTask': {
        // No arbitrary calls, suspension, ownership or external effects in
        // this scalar profile. Check the source AST before erasing the wrapper.
        const admit=(e,d=0)=>{
          if(d>MAX_DEPTH)fail('E_TASK_UNSUPPORTED: task expression depth');
          switch(e.kind){
            case 'int':case 'variable':return;
            case 'inlineScalarTask':case 'neg':admit(e.inner,d+1);return;
            case 'binary':admit(e.left,d+1);admit(e.right,d+1);return;
            case 'local':admit(e.init,d+1);admit(e.body,d+1);return;
            default:fail('E_TASK_UNSUPPORTED: inline scalar task cannot admit '+e.kind);
          }
        };
        admit(expr.inner);
        const inner=resolve(expr.inner,owner,depth+1,bindings);
        if(resolvedType(inner,owner)!=='int')fail('E_TASK_RETURN_TYPE: expected int');
        return inner;
      }
      case 'immediateAwait': {
        const inner=resolve(expr.inner,owner,depth+1,bindings);
        // No Future/actor readiness lowering exists in this frontend. Admit
        // only statically typed i64 values, and erase the identity operator.
        if(resolvedType(inner,owner)!=='int')fail('await requires immediate int in program-v2; asynchronous await unsupported');
        return inner;
      }
      case 'local': {
        const previous=bindings.findLast(b=>b.name===expr.name);
        if(owner.ownerKind==='method'||owner.params.includes(expr.name))
          fail('LOCAL_DUPLICATE_OR_SHADOW: '+expr.name);
        if(expr.taskRebind){
          // Parser admits writes only to a mutable int declared in this inline task.
          if(expr.rebind||!previous||previous.type!=='int')
            fail('E_TASK_LOCAL_WRITE: requires previously declared int local');
        }else if(expr.rebind){
          if(!previous||previous.type!=='string'||!previous.writable)
            fail('E_STRING_REBIND: requires writable typed string local');
        }else if(previous)fail('LOCAL_DUPLICATE_OR_SHADOW: '+expr.name);
        if(nextCaptureSlot>=MAX_TOKENS)fail('local slot budget exceeded');
        const slot=nextCaptureSlot++;
        const init=resolve(expr.init,owner,depth+1,bindings);
        const type=['inferredTrap','directionalInferred','inferredInt'].includes(expr.localType)?resolvedType(init,owner):expr.localType==='constInt'?'int':expr.localType;
        if(expr.localType==='trapResult'&&(init.kind!=='trap'||resolvedType(init,owner)!=='trapResult'))fail('E_TRAP_TYPE: typed Result<int,Error> local requires checked trap initializer');
        if(expr.taskRebind&&resolvedType(init,owner)!=='int')fail('E_TASK_LOCAL_WRITE: int assignment required');
        if(expr.localType==='inferredTrap'&&!['trapResult','trapOptionResult'].includes(type))fail('inferred trap local requires Result');
        if(expr.localType==='inferredInt'&&type!=='int')fail('inferred val requires int in program-v2; use an explicit supported type');
        if(expr.localType==='directionalInferred'&&!['int','float','trapResult','trapOptionResult'].includes(type))fail('directional binding requires supported scalar or trapped Result');
        if(expr.localType==='constInt'){
          // Narrow, fail-closed constant-expression subset: evaluate only
          // literal scalar expressions; no calls, parameters or captures.
          const checked=n=>{if(n<MIN||n>MAX)fail('constant int overflow');return n;};
          const fold=e=>{
            if(e.kind==='int')return BigInt(e.value);
            if(e.kind==='bool')return e.value;
            if(e.kind==='neg')return checked(-fold(e.inner));
            if(e.kind==='not'){const v=fold(e.inner);if(typeof v!=='boolean')fail('constant bool required');return !v;}
            if(e.kind==='if'){const p=fold(e.condition),y=fold(e.yes),n=fold(e.no);if(typeof p!=='boolean'||typeof y!==typeof n)fail('constant conditional types');return p?y:n;}
            if(e.kind==='compare'||e.kind==='boolCompare'||e.kind==='binary'){
              const a=fold(e.left),b=fold(e.right);
              if(typeof a!==typeof b)fail('constant operand type mismatch');
              switch(e.op){
                case '==':return a===b;case '!=':return a!==b;
                case '<':return a<b;case '>':return a>b;case '<=':return a<=b;case '>=':return a>=b;
                case '+':if(typeof a==='bigint')return checked(a+b);break;
                case '-':if(typeof a==='bigint')return checked(a-b);break;
                case '*':if(typeof a==='bigint')return checked(a*b);break;
              }
            }
            fail('directional const initializer is not a proven constant expression');
          };
          if(typeof fold(init)!=='bigint')fail('directional const int requires int');
        }
        return {kind:type==='option<int>'?'optionLocal':type==='result<int,int>'?'resultLocal':type==='trapOptionResult'?'trapOptionLocal':type==='trapResult'?'trapLocal':type==='float'?'floatLocal':type==='bool'?'boolLocal':type==='string'?'stringLocal':'local',slot,init,
          body:resolve(expr.body,owner,depth+1,[...bindings,{name:expr.name,slot,type,writable:expr.writable===true&&type==='string'}])};
      }
      case 'seqVoidCall': {
        if(expr.call.kind!=='call'||expr.call.qualifier!==null)
          fail('VOID_STATEMENT_DIRECT_CALL_REQUIRED');
        const call=resolve(expr.call,owner,depth+1,bindings);
        if(call.kind!=='call'&&call.kind!=='namedCall')
          fail('VOID_STATEMENT_DIRECT_CALL_REQUIRED');
        if((functions[call.target].returnType??'int')!=='void')
          fail('VOID_STATEMENT_REQUIRES_VOID_RETURN');
        return {kind:'seqVoidCall',call,value:resolve(expr.value,owner,depth+1,bindings)};
      }
      case 'newClass': {
        if(owner.ownerKind==='method')
          fail('class construction inside method body unsupported in convergence slice');
        const classIndex=classByName.get(expr.name);
        if(classIndex===undefined)fail('unknown class '+expr.name);
        if(expr.args.length!==0)fail('fieldless constructor arity mismatch for '+expr.name);
        return {kind:'newClass',classIndex};
      }
      case 'methodCall': {
        if(owner.ownerKind==='method')
          fail('nested method calls inside method body unsupported in convergence slice');
        const receiver=resolve(expr.receiver,owner,depth+1,bindings);
        const receiverType=resolvedType(receiver,owner);
        if(!receiverType.startsWith('class:'))fail('method receiver is not a class value');
        const classIndex=Number(receiverType.slice(6));
        const table=classTables[classIndex],cls=classes[classIndex];
        const methodIndex=table.methodSlots.get(expr.name+'/'+expr.args.length);
        if(methodIndex===undefined){
          if(table.methodFamilies.has(expr.name))
            fail('method arity mismatch for '+cls.name+'.'+expr.name);
          fail('unknown method '+cls.name+'.'+expr.name);
        }
        const method=cls.methods[methodIndex];
        if(!method.pub)fail('private method call '+cls.name+'.'+expr.name);
        const bound=bindArguments(method.params,expr.args,cls.name+'.'+expr.name,
          owner,depth,bindings);
        const base={classIndex,methodIndex,receiver,args:bound.args};
        return bound.hasNamed?{kind:'namedMethodCall',...base,paramOrder:bound.paramOrder}:
          {kind:'methodCall',...base};
      }
      case 'variable': {
        const captured=bindings.findLast(b=>b.name===expr.name);
        if(captured?.type==='caughtError')fail('E_CATCH_BINDING: caught Error is only available for typed rethrow');
        if(captured)return {kind:captured.type==='error'?'errorCapture':captured.type==='option<int>'?'optionCapture':captured.type==='result<int,int>'?'resultCapture':captured.type==='trapOptionResult'?'trapOptionCapture':captured.type==='trapResult'?'trapCapture':captured.type==='float'?'floatCapture':captured.type==='bool'?'boolCapture':captured.type==='string'?'stringCapture':'capture',slot:captured.slot};
        const index=owner.params.indexOf(expr.name);
        if(index<0) fail('unresolved identifier '+expr.name);
        return {kind:'param',index};
      }
      case 'trap':{
        if(owner.ownerKind==='method')fail('trap in methods unsupported');
        const inner=resolve(expr.inner,owner,depth+1,bindings);
        const operandType=resolvedType(inner,owner);
        if(!['int','option<int>','result<int,int>','trapResult','trapOptionResult'].includes(operandType))fail('trap requires int, Option<int> or closed Result operand');
        return {kind:'trap',operandType,inner};
      }
      case 'neg':case 'not':{
        const inner=resolve(expr.inner,owner,depth+1,bindings);
        return {kind:expr.kind==='neg'&&resolvedType(inner,owner)==='float'?'floatNeg':expr.kind,inner};
      }
      case 'binary':{
        const left=resolve(expr.left,owner,depth+1,bindings),right=resolve(expr.right,owner,depth+1,bindings);
        const lt=resolvedType(left,owner),rt=resolvedType(right,owner);
        if(lt==='float'||rt==='float'){
          if(lt!=='float'||rt!=='float'||!['+','-','*','/'].includes(expr.op))fail('explicit float operands required');
          return {kind:'floatBin',op:expr.op,left,right};
        }
        return {kind:'binary',op:expr.op,left,right};
      }
      case 'compare': {
        const left=resolve(expr.left,owner,depth+1,bindings);
        const right=resolve(expr.right,owner,depth+1,bindings);
        const leftType=resolvedType(left,owner),rightType=resolvedType(right,owner);
        if(leftType==='string'||rightType==='string'){
          if(leftType!=='string'||rightType!=='string'||!['==','!='].includes(expr.op))
            fail('E_STRING_TYPE: string equality requires two typed string operands');
          return {kind:'stringCompare',op:expr.op,left,right};
        }
        if(leftType==='bool'||rightType==='bool'){
          if(leftType!=='bool'||rightType!=='bool'||!['==','!='].includes(expr.op))
            fail('Boolean comparison requires bool ==/!= bool');
          return {kind:'boolCompare',op:expr.op,left,right};
        }
        if(leftType==='symbol'||rightType==='symbol'){
          if(!['==','!='].includes(expr.op)||leftType!=='symbol'||rightType!=='symbol')
            fail('Symbol comparison requires Symbol ==/!= Symbol');
          return {kind:'symbolCompare',op:expr.op,left,right};
        }
        if(leftType==='float'||rightType==='float'){
          if(!['int','float'].includes(leftType)||!['int','float'].includes(rightType))fail('numeric comparison operands required');
          return {kind:'numericCompare',op:expr.op,leftType,rightType,left,right};
        }
        return {kind:'compare',op:expr.op,left,right};
      }
      case 'if': return {kind:'if',condition:resolve(expr.condition,owner,depth+1,bindings),
        yes:resolve(expr.yes,owner,depth+1,bindings),no:resolve(expr.no,owner,depth+1,bindings)};
      case 'call': {
        const local=bindings.findLast(b=>b.name===expr.qualifier);
        if(local){
          if(['option<int>','result<int,int>'].includes(local.type)){
            const option=local.type==='option<int>';
            const receiver={kind:option?'optionCapture':'resultCapture',slot:local.slot};
            if(expr.name==='expect'){
              if(owner.ownerKind==='method'||expr.args.length!==1||expr.args[0].name!==null)fail('E_EXPECT: one positional string message required');
              const message=resolve(expr.args[0].expr,owner,depth+1,bindings);
              if(resolvedType(message,owner)!=='string')fail('E_EXPECT: string message required');
              return {kind:'intrinsic',name:option?'Option.expect':'Result.expect',args:[receiver,message]};
            }
            if(expr.name!=='unwrap'||expr.args.length!==0)fail('unsupported typed local method/arity');
            return {kind:'intrinsic',name:option?'Option.unwrap':'Result.unwrap',args:[receiver]};
          }
          if(!['trapResult','trapOptionResult'].includes(local.type)||!['is_ok','is_err','unwrap'].includes(expr.name)||expr.args.length!==0)fail('unsupported local Result method/arity');
          const option=local.type==='trapOptionResult';
          return {kind:option?'trapOptionMethod':'trapMethod',name:expr.name,receiver:{kind:option?'trapOptionCapture':'trapCapture',slot:local.slot}};
        }
        if(expr.qualifier!==null&&owner.params.includes(expr.qualifier)&&['unwrap','expect'].includes(expr.name)){
          const index=owner.params.indexOf(expr.qualifier), type=owner.paramTypes?.[index];
          if(!['option<int>','result<int,int>'].includes(type)||owner.ownerKind==='method')
            fail('unsupported typed parameter method/arity');
          const receiver={kind:'param',index};
          if(expr.name==='expect'){
            if(expr.args.length!==1||expr.args[0].name!==null)fail('E_EXPECT: one positional string message required');
            const message=resolve(expr.args[0].expr,owner,depth+1,bindings);
            if(resolvedType(message,owner)!=='string')fail('E_EXPECT: string message required');
            return {kind:'intrinsic',name:type==='option<int>'?'Option.expect':'Result.expect',args:[receiver,message]};
          }
          if(expr.args.length!==0)fail('unsupported typed parameter method/arity');
          return {kind:'intrinsic',name:type==='option<int>'?'Option.unwrap':'Result.unwrap',args:[receiver]};
        }
        if(expr.qualifier==='Option'||expr.qualifier==='Result'){
          const key=expr.qualifier+'.'+expr.name;
          const signatures={
            'Option.some':[1,'option<int>'],
            'Option.none':[0,'option<int>'],
            'Option.is_some':[1,'bool'],
            'Option.unwrap_or':[2,'int'],
            'Result.ok':[1,'result<int,int>'],
            'Result.err':[1,'result<int,int>'],
            'Result.is_ok':[1,'bool'],
            'Result.unwrap_or':[2,'int'],
            'Result.unwrap_err_or':[2,'int']
          };
          const sig=signatures[key];
          if(!sig)fail('unknown portable constructor/intrinsic '+key);
          if(expr.args.some(a=>a.name!==null))
            fail('named arguments are unsupported for portable intrinsic '+key);
          if(expr.args.length!==sig[0])fail('intrinsic arity mismatch '+key);
          return {kind:'intrinsic',name:key,args:expr.args.map(a=>resolve(a.expr,owner,depth+1,bindings))};
        }
        if(owner.ownerKind==='method')
          fail('function calls inside method body unsupported in convergence slice');
        const mod=expr.qualifier===null?owner.module:expr.qualifier;
        if(mod!==null && !modules.includes(mod)) fail('unknown module '+mod);
        const key=(mod===null?'@file/':'@module/'+mod+'/')+expr.name;
        const target=slots.get(key+'/'+expr.args.length);
        if(target===undefined) {
          if(symbolKinds.has(key)) fail('call arity mismatch for '+key);
          fail('unresolved callable '+key);
        }
        if(typeof target!=='number')
          fail('generator values are not source-callable in JS sync generator v1; consume them through the iterator ABI');
        if(expr.qualifier!==null && mod!==owner.module && !functions[target].pub) fail('private module member '+key);
        const bound=bindArguments(functions[target].params,expr.args,key,
          owner,depth,bindings);
        return bound.hasNamed?{kind:'namedCall',target,args:bound.args,paramOrder:bound.paramOrder}:
          {kind:'call',target,args:bound.args};
      }
      case 'match':
        if(owner.ownerKind==='method')
          fail('match inside method body unsupported in convergence slice');
        return {kind:'match',subject:resolve(expr.subject,owner,depth+1,bindings),
        arms:expr.arms.map(arm=>({value:arm.value,body:resolve(arm.body,owner,depth+1,bindings)}))};
      case 'adtMatch': {
        if(owner.ownerKind==='method')
          fail('ADT match inside method body unsupported in convergence slice');
        const subject=resolve(expr.subject,owner,depth+1,bindings);
        const subjectType=resolvedType(subject,owner);
        const adt=expr.adt==='result<int,int>'&&subjectType==='trapResult'?'trapResult':expr.adt;
        if(adt==='trapResult'&&expr.arms.some(arm=>arm.tag==='Err'&&arm.bind!==null)&&
           !(subject.kind==='trap'&&subject.operandType==='int'))
          fail('E_ERROR_BINDING: typed Error binding requires a proven scalar trap; mixed returned Err payload is not Error');
        const arms=expr.arms.map(arm=>{
          let slot=null,scope=bindings;
          if(arm.bind!==null){
            if(owner.params.includes(arm.bind)||bindings.some(b=>b.name===arm.bind))
              fail('ADT payload binding shadows an existing name '+arm.bind);
            if(nextCaptureSlot>=MAX_TOKENS)fail('ADT capture slot budget exceeded');
            slot=nextCaptureSlot++;
            scope=[...bindings,{name:arm.bind,slot,type:adt==='trapResult'&&arm.tag==='Err'?'error':'int'}];
          }
          return {tag:arm.tag,slot,body:resolve(arm.body,owner,depth+1,scope)};
        });
        if(expr.typed&&arms.some(arm=>resolvedType(arm.body,owner)!=='int'))
          fail('explicit match<int> requires integer arm results');
        return {kind:'adtMatch',adt,subject,arms};
      }
      default: fail('unsupported expression node');
    }
  }
  const resolved=functions.map(fn=>({...fn,body:fn.body===null?null:resolve(fn.body,fn)}));
  const resolvedGenerators=generators.map(gen=>({
    ...gen,yields:gen.yields.map(expr=>resolve(expr,gen))
  }));
  const resolvedClasses=classes.map((cls,classIndex)=>({
    ...cls,
    methods:cls.methods.map(method=>({
      ...method,
      body:method.body===null?null:resolve(method.body,{
        ...method,module:null,ownerKind:'method',classIndex
      })
    }))
  }));
  const graph=resolved.map(()=>new Set());
  function inspect(expr,owner) {
    if(expr===null)return; // Pure void bodies contain no calls or effects.
    if(expr.kind==='call'||expr.kind==='namedCall') {
      graph[owner].add(expr.target);expr.args.forEach(a=>inspect(a,owner));
    } else if(expr.kind==='methodCall'||expr.kind==='namedMethodCall') {
      inspect(expr.receiver,owner);expr.args.forEach(a=>inspect(a,owner));
    } else if(expr.kind==='newClass') {
      // fieldless construction has no call-graph edge in this slice
    } else if(expr.kind==='intrinsic')expr.args.forEach(a=>inspect(a,owner));
    else if(['binary','floatBin','compare','boolCompare','stringCompare','numericCompare','symbolCompare'].includes(expr.kind)) {inspect(expr.left,owner);inspect(expr.right,owner);}
    else if(expr.kind==='scalarRecur'){
      expr.states.forEach(s=>inspect(s.init,owner));
      expr.guards.forEach(g=>{inspect(g.condition,owner);inspect(g.value,owner);});
      expr.args.forEach(a=>inspect(a,owner));
    }
    else if(expr.kind==='if') {inspect(expr.condition,owner);inspect(expr.yes,owner);inspect(expr.no,owner);}
    else if(expr.kind==='tryCatch'){inspect(expr.body,owner);inspect(expr.handler,owner);}
    else if(expr.kind==='seqVoidCall'){inspect(expr.call,owner);inspect(expr.value,owner);}
    else if(['local','floatLocal','boolLocal','stringLocal','trapLocal','trapOptionLocal','optionLocal','resultLocal'].includes(expr.kind)){inspect(expr.init,owner);inspect(expr.body,owner);}
    else if(['trapMethod','trapOptionMethod'].includes(expr.kind))inspect(expr.receiver,owner);
    else if(['neg','floatNeg','not','trap'].includes(expr.kind)) inspect(expr.inner,owner);
    else if(expr.kind==='match'||expr.kind==='adtMatch') {
      inspect(expr.subject,owner);expr.arms.forEach(a=>inspect(a.body,owner));
    }
  }
  resolved.forEach((f,i)=>inspect(f.body,i));
  // Conservative until there is a fuel/call-depth budget: matches may contain
  // conditional recursion, but rejecting cycles is safer than permitting an
  // unbounded JS stack explosion. This is NOT an Oreslang language restriction.
  const colors=new Uint8Array(resolved.length);
  function visit(i) {
    if(colors[i]===1) fail('recursive call graph unsupported pending fuel budget');
    if(colors[i]===2) return;
    colors[i]=1;for(const j of graph[i])visit(j); colors[i]=2;
  }
  resolved.forEach((_,i)=>visit(i));
  return {
    sourceNamespace:parsed.namespace,modules,functions:resolved,
    ...(resolvedExports.size?{fileExports:[...resolvedExports.values()].map(e=>({
      name:e.name,kind:e.kind,
      visibility:e.visibility
    }))}:{}),
    ...(resolvedGenerators.length?{generators:resolvedGenerators}:{}),
    ...(resolvedClasses.length?{classes:resolvedClasses}:{})
  };
}
function emitProgram(program) {
  const out=[
    '// experimental Oreslang program-v2, provisional signed-64 integer model',
    'const _arithmeticBrand=new WeakSet();',
    'function _arith(code,message){const e=new RangeError(message);Object.defineProperties(e,{name:{value:code==="integer_overflow"?"ArithmeticOverflowError":"DivisionByZeroError"},code:{value:code,enumerable:true},abiCode:{value:code==="integer_overflow"?65537:65538,enumerable:true}});_arithmeticBrand.add(e);return Object.freeze(e);}',
    'function _check(v){if(typeof v!=="bigint")throw new TypeError("Oreslang signed i64 BigInt required");if(v<-(1n<<63n)||v>(1n<<63n)-1n)throw _arith("integer_overflow","ORES_I64_OVERFLOW: checked signed 64-bit overflow");return v;}',
    // Do not expose JS engine-specific "Division by zero" RangeError text as
    // an unclassified Oreslang guest failure. This is a provisional host-side
    // code, NOT the portable guest Error wire ABI or actor CONTROL signal.
    'function _rem(a,b){_check(a);_check(b);if(b===0n)throw _arith("division_by_zero","ORES_I64_DIVISION_BY_ZERO: checked signed 64-bit remainder by zero");return _check(a%b);}'
  ];
  function emitExpr(expr,depth=0) {
    if(depth > MAX_DEPTH) fail('codegen depth exceeded');
    switch(expr.kind) {
      case 'tryCatch':return '(()=>{try{return '+emitExpr(expr.body,depth+1)+';}catch(_v'+expr.slot+'){if(!_isOrdinaryGuest(_v'+expr.slot+'))throw _v'+expr.slot+';return '+emitExpr(expr.handler,depth+1)+';}})()';
      case 'rethrow':return '(()=>{throw _v'+expr.slot+';})()';
      case 'throwError':return '(()=>{throw _checkGuestError('+emitExpr(expr.value,depth+1)+');})()';
      case 'guestEffect':return '_guestEffect('+JSON.stringify(expr.effect)+','+JSON.stringify(expr.message)+')';
      case 'int': return expr.value+'n';
      case 'float': return '('+String(Number(expr.value))+')';
      case 'symbol':return '_symbol('+JSON.stringify(expr.value)+')';
      case 'bool': return expr.value?'true':'false';
      case 'string': return JSON.stringify(expr.value);
      case 'param':return '_p'+expr.index;
      case 'scalarRecur': {
        const states=expr.states;
        const init=states.map(s=>'let _v'+s.slot+'='+emitExpr(s.init,depth+1)+';').join('');
        const guards=expr.guards.map(g=>'if('+emitExpr(g.condition,depth+1)+')return '+emitExpr(g.value,depth+1)+';').join('');
        // Stage every operand before mutating any slot. Lexical temporaries
        // are per iteration and never form recursive JS calls.
        const staged=expr.args.map((a,i)=>'const _n'+states[i].slot+'='+emitExpr(a,depth+1)+';').join('');
        const commit=states.map(s=>'_v'+s.slot+'=_n'+s.slot+';').join('');
        return '(()=>{'+init+'while(true){'+guards+staged+commit+'}})()';
      }
      case 'scalarWhileMany': {
        const emitSteps=(steps,d)=>steps.map(step=>{
          if(d>MAX_DEPTH)fail('loop codegen depth exceeded');
          if(step.kind==='bind')return (step.mutable?'let ':'const ')+'_v'+step.slot+'='+emitExpr(step.init,d+1)+';';
          if(step.kind==='set')return '_v'+step.slot+'='+emitExpr(step.value,d+1)+';';
          if(step.kind==='loopExit')return 'if('+emitExpr(step.condition,d+1)+'){'+step.exit+';}';
          return 'while('+emitExpr(step.condition,d+1)+'){'+emitSteps(step.steps,d+1)+'}';
        }).join('');
        return '(()=>{'+expr.states.map(s=>'let _v'+s.slot+'='+emitExpr(s.init,depth+1)+';').join('')+
          'while('+emitExpr(expr.condition,depth+1)+'){'+emitSteps(expr.steps,depth+1)+'}return '+emitExpr(expr.body,depth+1)+';})()';
      }
      case 'scalarWhile': {
        const v='_v'+expr.slot;
        return '(('+v+')=>{while('+emitExpr(expr.condition,depth+1)+'){'+v+'='+emitExpr(expr.update,depth+1)+';}return '+emitExpr(expr.body,depth+1)+';})('+emitExpr(expr.init,depth+1)+')';
      }
      case 'capture':case 'floatCapture':case 'boolCapture':case 'stringCapture':case 'trapCapture':case 'trapOptionCapture':case 'optionCapture':case 'resultCapture':case 'errorCapture':return '_v'+expr.slot;
      case 'local':case 'floatLocal':case 'boolLocal':case 'stringLocal':case 'trapLocal':case 'trapOptionLocal':case 'optionLocal':case 'resultLocal':return '((_v'+expr.slot+')=>('+emitExpr(expr.body,depth+1)+'))('+emitExpr(expr.init,depth+1)+')';
      // Oreslang checked signed-i64 arithmetic is not JS BigInt.asIntN
      // wrapping arithmetic. Check the computed result before returning.
      case 'i32Check':
        return '((v)=>{_check(v);if(v<-2147483648n||v>2147483647n){const e=new RangeError("E_I32_OVERFLOW: checked signed 32-bit overflow");Object.defineProperty(e,"code",{value:"i32_overflow",enumerable:true});throw Object.freeze(e);}return v;})('+emitExpr(expr.inner,depth+1)+')';
      case 'refinementCheck':
        return '((v)=>{_check(v);if(v<'+expr.minimum+'n||v>'+expr.maximum+'n){const e=new RangeError("E_REFINEMENT_CHECK_FAILED: checked refinement violated");Object.defineProperty(e,"code",{value:"refinement_violation",enumerable:true});throw Object.freeze(e);}return v;})('+emitExpr(expr.inner,depth+1)+')';
      case 'neg':return '_check(-('+emitExpr(expr.inner,depth+1)+'))';
      case 'trap':return '_trap(()=>('+emitExpr(expr.inner,depth+1)+'),'+JSON.stringify(expr.operandType)+')';
      case 'trapMethod':case 'trapOptionMethod':return '_trapMethod('+JSON.stringify(expr.name)+','+emitExpr(expr.receiver,depth+1)+','+(expr.kind==='trapOptionMethod')+')';
      case 'floatNeg':return '(-('+emitExpr(expr.inner,depth+1)+'))';
      case 'floatBin':return '(('+emitExpr(expr.left,depth+1)+')'+expr.op+'('+emitExpr(expr.right,depth+1)+'))';
      case 'numericCompare':{
        // Abstract relational comparison of BigInt and Number is exact. Equality
        // uses both ordered comparisons, not Number(bigint) or JS loose equality.
        const op=expr.op==='=='?'a<=b&&a>=b':expr.op==='!='?'!(a<=b&&a>=b)':'a'+expr.op+'b';
        return '((a,b)=>('+op+'))('+emitExpr(expr.left,depth+1)+','+emitExpr(expr.right,depth+1)+')';
      }
      case 'not':return '!('+emitExpr(expr.inner,depth+1)+')';
      case 'binary':
        if(expr.op==='<<<'||expr.op==='>>>'||expr.op==='ashr'){
          const staticCount=expr.right.kind==='int'?BigInt(expr.right.value):
            expr.right.kind==='neg'&&expr.right.inner.kind==='int'?-BigInt(expr.right.inner.value):null;
          if(staticCount!==null&&(staticCount<0n||staticCount>63n))fail('E_SHIFT_DISTANCE: expected 0..63');
          const left=emitExpr(expr.left,depth+1),right=emitExpr(expr.right,depth+1);
          // Evaluate both operands once; validate before shifting, never mask counts.
          const shift=expr.op==='<<<'?'a<<b':expr.op==='ashr'?'a>>b':'BigInt.asUintN(64,a)>>b';
          return '((a,b)=>{_check(a);_check(b);if(b<0n||b>63n)throw new RangeError("E_GUEST_SHIFT_DISTANCE: expected 0..63");return BigInt.asIntN(64,'+shift+');})('+left+','+right+')';
        }
        // Evaluate operands once, left-to-right; do not expose engine-specific zero-divisor errors.
        if(expr.op==='/')return '((a,b)=>{_check(a);_check(b);if(b===0n)throw _arith("division_by_zero","ORES_I64_DIVISION_BY_ZERO: checked signed 64-bit division by zero");return _check(a/b);})(('+emitExpr(expr.left,depth+1)+'),('+emitExpr(expr.right,depth+1)+'))';
        if(expr.op==='%')return '_rem(('+emitExpr(expr.left,depth+1)+'),('+emitExpr(expr.right,depth+1)+'))';
        // Inline so programs without Integer.mod keep byte-identical output.
        if(expr.op==='mod')return '((a,b)=>{const r=_rem(a,b);return r<0n?_check(b>0n?r+b:r-b):r;})(('+emitExpr(expr.left,depth+1)+'),('+emitExpr(expr.right,depth+1)+'))';
        return '_check(('+emitExpr(expr.left,depth+1)+')'+expr.op+'('+emitExpr(expr.right,depth+1)+'))';
      case 'compare':return '(('+emitExpr(expr.left,depth+1)+')'+expr.op+'('+emitExpr(expr.right,depth+1)+'))';
      case 'stringCompare':case 'boolCompare':return '(('+emitExpr(expr.left,depth+1)+')'+
        (expr.op==='=='?'===':'!==')+'('+emitExpr(expr.right,depth+1)+'))';
      case 'symbolCompare': {
        const equal='_symboleq('+emitExpr(expr.left,depth+1)+','+
          emitExpr(expr.right,depth+1)+')';
        return expr.op==='=='?equal:'!('+equal+')';
      }
      case 'if':return '(('+emitExpr(expr.condition,depth+1)+')?('+
        emitExpr(expr.yes,depth+1)+'):('+emitExpr(expr.no,depth+1)+'))';
      case 'seqVoidCall':
        return '(()=>{'+emitExpr(expr.call,depth+1)+';return '+
          emitExpr(expr.value,depth+1)+';})()';
      case 'newClass':return 'new _class'+expr.classIndex+'()';
      case 'methodCall': {
        const method=program.classes[expr.classIndex].methods[expr.methodIndex];
        return '('+emitExpr(expr.receiver,depth+1)+')['+JSON.stringify(method.name)+']('+
          expr.args.map(x=>emitExpr(x,depth+1)).join(',')+')';
      }
      case 'namedMethodCall': {
        const method=program.classes[expr.classIndex].methods[expr.methodIndex];
        const declarations=['const _receiver='+emitExpr(expr.receiver,depth+1)+';'];
        expr.args.forEach((arg,i)=>declarations.push(
          'const _namedMethod'+i+'='+emitExpr(arg,depth+1)+';'));
        const formal=new Array(expr.args.length);
        expr.paramOrder.forEach((slot,i)=>{formal[slot]='_namedMethod'+i;});
        return '(()=>{'+declarations.join('')+'return _receiver['+
          JSON.stringify(method.name)+']('+formal.join(',')+');})()';
      }
      case 'call':return '_f'+expr.target+'('+expr.args.map(x=>emitExpr(x,depth+1)).join(',')+')';
      case 'namedCall': {
        const declarations=expr.args.map((arg,i)=>
          'const _named'+i+'='+emitExpr(arg,depth+1)+';');
        const formal=new Array(expr.args.length);
        expr.paramOrder.forEach((slot,i)=>{formal[slot]='_named'+i;});
        return '(()=>{'+declarations.join('')+'return _f'+expr.target+'('+
          formal.join(',')+');})()';
      }
      case 'intrinsic': {
        const funcs={
          'Option.some':'_some','Option.none':'_none',
          'Option.is_some':'_is_some','Option.unwrap_or':'_option_or','Option.unwrap':'_optionUnwrap','Result.unwrap':'_resultUnwrap','Option.expect':'_optionExpect','Result.expect':'_resultExpect',
          'Result.ok':'_ok','Result.err':'_err',
          'Result.is_ok':'_is_ok','Result.unwrap_or':'_result_or',
          'Result.unwrap_err_or':'_error_or'
        };
        const target=funcs[expr.name];
        if(!target)fail('unsupported portable intrinsic');
        return target+'('+expr.args.map(x=>emitExpr(x,depth+1)).join(',')+')';
      }
      case 'adtMatch': {
        const check=expr.adt==='trapResult'?'_checkTrapResult':expr.adt==='option<int>'?'_checkoption':'_checkresult';
        const lines=['(()=>{const _subject='+emitExpr(expr.subject,depth+1)+';'+check+'(_subject);switch(_subject.kind){'];
        for(const arm of expr.arms){
          lines.push('case '+JSON.stringify(arm.tag)+':{');
          if(arm.slot!==null)lines.push('const _v'+arm.slot+'=_subject.value;');
          lines.push('return '+emitExpr(arm.body,depth+1)+';}');
        }
        lines.push('default:throw new TypeError("Oreslang ADT match illegal tag");}})()');
        return lines.join('');
      }
      case 'match': {
        // _subject is evaluated exactly once. A branch returns immediately,
        // so no fallthrough and unmatched arm expressions stay lazy.
        const lines=['(()=>{const _subject='+emitExpr(expr.subject,depth+1)+';switch(_subject){'];
        expr.arms.forEach(arm=>{
          lines.push(arm.value===null?'default:':'case '+arm.value+'n:');
          lines.push('return '+emitExpr(arm.body,depth+1)+';');
        });
        lines.push('}})()');
        return lines.join('');
      }
      default: fail('unsupported codegen expression');
    }
  }
  const classMethods=(program.classes??[]).flatMap(cls=>cls.methods);
  if(program.functions.some(fn=>fn.returnType==='bool'||fn.paramTypes?.includes('bool'))||
     classMethods.some(method=>method.returnType==='bool'||method.paramTypes.includes('bool')))
    out.push('function _checkbool(v){if(typeof v!=="boolean")throw new TypeError("Oreslang bool required");return v;}');
  function usesSymbol(expr) {
    if(expr===null)return false;
    switch(expr.kind) {
      case 'symbol':return true;
      case 'newClass':return false;
      case 'methodCall':case 'namedMethodCall':
        return usesSymbol(expr.receiver)||expr.args.some(usesSymbol);
      case 'call':case 'namedCall':case 'intrinsic':return expr.args.some(usesSymbol);
      case 'binary':case 'floatBin':case 'compare':case 'boolCompare':case 'stringCompare':case 'numericCompare':case 'symbolCompare':
        return usesSymbol(expr.left)||usesSymbol(expr.right);
      case 'scalarRecur':return expr.states.some(s=>usesSymbol(s.init))||expr.guards.some(g=>usesSymbol(g.condition)||usesSymbol(g.value))||expr.args.some(usesSymbol);
      case 'scalarWhileMany': {
        const visit=steps=>steps.some(s=>s.kind==='loop'?usesSymbol(s.condition)||visit(s.steps):s.kind==='loopExit'?usesSymbol(s.condition):usesSymbol(s.init??s.value));
        return expr.states.some(s=>usesSymbol(s.init))||usesSymbol(expr.condition)||visit(expr.steps)||usesSymbol(expr.body);
      }
      case 'scalarWhile':return [expr.init,expr.condition,expr.update,expr.body].some(usesSymbol);
      case 'if':return usesSymbol(expr.condition)||usesSymbol(expr.yes)||usesSymbol(expr.no);
      case 'tryCatch':return usesSymbol(expr.body)||usesSymbol(expr.handler);
      case 'seqVoidCall':return usesSymbol(expr.call)||usesSymbol(expr.value);
      case 'local':case 'floatLocal':case 'boolLocal':case 'stringLocal':case 'trapLocal':case 'trapOptionLocal':case 'optionLocal':case 'resultLocal':return usesSymbol(expr.init)||usesSymbol(expr.body);
      case 'trap':return usesSymbol(expr.inner);
      case 'trapMethod':case 'trapOptionMethod':return usesSymbol(expr.receiver);
      case 'i32Check':case 'refinementCheck':case 'neg':case 'floatNeg':case 'not':return usesSymbol(expr.inner);
      case 'match':case 'adtMatch':return usesSymbol(expr.subject)||
        expr.arms.some(arm=>usesSymbol(arm.body));
      default:return false;
    }
  }
  const hasSymbol=program.functions.some(fn=>fn.returnType==='symbol'||
    fn.paramTypes?.includes('symbol')||usesSymbol(fn.body))||
    classMethods.some(method=>method.returnType==='symbol'||
      method.paramTypes.includes('symbol')||(method.body!==null&&usesSymbol(method.body)));
  if(hasSymbol) {
    out.push(
      'const _symbolBrand=new WeakSet();',
      'function _symbolKind(k,n){const x=Object.create(null);Object.defineProperty(x,"kind",{value:k,enumerable:true});Object.defineProperty(x,"name",{value:n,enumerable:true});Object.freeze(x);_symbolBrand.add(x);return x;}',
      'function _symbol(n){return _symbolKind("PROCESS",n);}',
      'function _checksymbol(v){if(!v||typeof v!=="object"||!_symbolBrand.has(v)||Object.getPrototypeOf(v)!==null||!Object.isFrozen(v))throw new TypeError("Oreslang Symbol required");return v;}',
      'function _symboleq(a,b){_checksymbol(a);_checksymbol(b);return a.kind===b.kind&&a.name===b.name;}',
      'function _wireName(n,stable){if(typeof n!=="string"||n.length>1024)throw new TypeError("Oreslang Symbol wire name budget");for(let i=0;i<n.length;i++){const u=n.charCodeAt(i);if(u>=55296&&u<=56319){const lo=n.charCodeAt(i+1);if(!(lo>=56320&&lo<=57343))throw new TypeError("Oreslang Symbol wire UTF-16");i++;}else if(u>=56320&&u<=57343)throw new TypeError("Oreslang Symbol wire UTF-16");}if(n.normalize("NFC")!==n)throw new TypeError("Oreslang Symbol wire requires NFC");const bytes=new TextEncoder().encode(n).length;if(bytes<1||bytes>256)throw new TypeError("Oreslang Symbol wire byte budget");for(const ch of n){const cp=ch.codePointAt(0);if(cp<32||(cp>=127&&cp<=159)||/[\\p{Cf}\\p{Zl}\\p{Zp}]/u.test(ch)||cp===1564||cp===8206||cp===8207||(cp>=8234&&cp<=8238)||(cp>=8294&&cp<=8297))throw new TypeError("Oreslang Symbol wire forbidden Unicode");}if(stable&&!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(n))throw new TypeError("Oreslang Symbol wire stable UUID");return n;}',
      'function _wireDecode(v,policy="trusted"){if(policy!=="trusted"&&policy!=="untrusted-core")throw new TypeError("Oreslang Symbol wire policy");if(!v||typeof v!=="object"||Array.isArray(v)||(Object.getPrototypeOf(v)!==Object.prototype&&Object.getPrototypeOf(v)!==null))throw new TypeError("Oreslang Symbol wire object");const keys=Reflect.ownKeys(v);if(keys.length!==2||!keys.includes("$symbol")||!keys.includes("name")||keys.some(k=>typeof k!=="string"))throw new TypeError("Oreslang Symbol wire shape");for(const k of ["$symbol","name"]){const d=Object.getOwnPropertyDescriptor(v,k);if(!d||!Object.hasOwn(d,"value")||!d.enumerable)throw new TypeError("Oreslang Symbol wire accessor/non-enumerable");}const kind=v.$symbol;if(kind!=="process"&&kind!=="stable")throw new TypeError("Oreslang Symbol wire kind");const name=_wireName(v.name,kind==="stable");if(policy==="untrusted-core"&&(kind!=="process"||!["ready","error","Symbol.iterator"].includes(name)))throw new TypeError("Oreslang Symbol wire denied");return _symbolKind(kind==="process"?"PROCESS":"STABLE",name);}',
      'function _wireEncode(v){const s=_checksymbol(v);const x=Object.create(null);Object.defineProperty(x,"$symbol",{value:s.kind==="PROCESS"?"process":"stable",enumerable:true});Object.defineProperty(x,"name",{value:s.name,enumerable:true});return Object.freeze(x);}',
      'const _symbolWireV1=Object.freeze({encode:_wireEncode,decode:_wireDecode});'
    );
  }
  // Intrinsics may be nested entirely within an int-returning function
  // without appearing in its public signature. Include helpers based on
  // both typed signatures and the entire already-validated expression DAG.
  function usesPortableRuntime(expr) {
    if(expr===null)return false;
    switch(expr.kind) {
      case 'intrinsic':case 'trap':case 'trapMethod':case 'trapOptionMethod':return true;
      case 'newClass':return false;
      case 'methodCall':case 'namedMethodCall':
        return usesPortableRuntime(expr.receiver)||expr.args.some(usesPortableRuntime);
      case 'call':case 'namedCall':return expr.args.some(usesPortableRuntime);
      case 'binary':case 'floatBin':case 'compare':case 'boolCompare':case 'stringCompare':case 'numericCompare':case 'symbolCompare':
        return usesPortableRuntime(expr.left)||usesPortableRuntime(expr.right);
      case 'scalarRecur':return expr.states.some(s=>usesPortableRuntime(s.init))||expr.guards.some(g=>usesPortableRuntime(g.condition)||usesPortableRuntime(g.value))||expr.args.some(usesPortableRuntime);
      case 'scalarWhileMany': {
        const visit=steps=>steps.some(s=>s.kind==='loop'?usesPortableRuntime(s.condition)||visit(s.steps):s.kind==='loopExit'?usesPortableRuntime(s.condition):usesPortableRuntime(s.init??s.value));
        return expr.states.some(s=>usesPortableRuntime(s.init))||usesPortableRuntime(expr.condition)||visit(expr.steps)||usesPortableRuntime(expr.body);
      }
      case 'scalarWhile':return [expr.init,expr.condition,expr.update,expr.body].some(usesPortableRuntime);
      case 'if':return usesPortableRuntime(expr.condition)||
        usesPortableRuntime(expr.yes)||usesPortableRuntime(expr.no);
      case 'tryCatch':return usesPortableRuntime(expr.body)||usesPortableRuntime(expr.handler);
      case 'seqVoidCall':return usesPortableRuntime(expr.call)||usesPortableRuntime(expr.value);
      case 'local':case 'floatLocal':case 'boolLocal':case 'stringLocal':case 'trapLocal':case 'trapOptionLocal':case 'optionLocal':case 'resultLocal':return usesPortableRuntime(expr.init)||usesPortableRuntime(expr.body);
      case 'i32Check':case 'refinementCheck':case 'neg':case 'floatNeg':case 'not':return usesPortableRuntime(expr.inner);
      case 'match':return usesPortableRuntime(expr.subject)||
        expr.arms.some(arm=>usesPortableRuntime(arm.body));
      case 'adtMatch':return true;
      default:return false;
    }
  }
  function containsCheckedUnwrap(value){
    return !!value&&typeof value==='object'&&((value.kind==='intrinsic'&&['Option.unwrap','Result.unwrap','Option.expect','Result.expect'].includes(value.name))||Object.values(value).some(containsCheckedUnwrap));
  }
  const hasCheckedUnwrap=containsCheckedUnwrap(program);
  if(program.functions.some(fn=>['option<int>','result<int,int>'].includes(fn.returnType)||
      fn.paramTypes?.some(t=>['option<int>','result<int,int>'].includes(t))||
      usesPortableRuntime(fn.body))||
     classMethods.some(method=>['option<int>','result<int,int>'].includes(method.returnType)||
      method.paramTypes.some(t=>['option<int>','result<int,int>'].includes(t))||
      (method.body!==null&&usesPortableRuntime(method.body)))) {
    out.push(
      ...(hasCheckedUnwrap?[
        'const _tagBrand=new WeakSet();',
        'function _tag(k,v){const x=Object.create(null);Object.defineProperty(x,"kind",{value:k,enumerable:true});Object.defineProperty(x,"value",{value:v,enumerable:true});_tagBrand.add(x);return Object.freeze(x);}'
      ]:['function _tag(k,v){const x=Object.create(null);Object.defineProperty(x,"kind",{value:k,enumerable:true});Object.defineProperty(x,"value",{value:v,enumerable:true});return Object.freeze(x);}']),
      'function _checktag(x,types){if(!x||typeof x!=="object"||Object.getPrototypeOf(x)!==null||!Object.isFrozen(x))throw new TypeError("Oreslang tagged value required");const keys=Reflect.ownKeys(x);if(keys.length!==2||!keys.includes("kind")||!keys.includes("value"))throw new TypeError("Oreslang tagged value shape required");const kd=Object.getOwnPropertyDescriptor(x,"kind"),vd=Object.getOwnPropertyDescriptor(x,"value");if(!kd||!vd||!Object.hasOwn(kd,"value")||!Object.hasOwn(vd,"value")||!kd.enumerable||!vd.enumerable||!types.includes(kd.value))throw new TypeError("Oreslang tagged value type required");if(kd.value==="None"){if(vd.value!==null)throw new TypeError("Oreslang None payload must be null");}else _check(vd.value);return x;}',
      'function _checkoption(v){return _checktag(v,["Some","None"]);}',
      'function _checkresult(v){return _checktag(v,["Ok","Err"]);}',
      'function _some(v){return _tag("Some",_check(v));}',
      'function _none(){return _tag("None",null);}',
      'function _ok(v){return _tag("Ok",_check(v));}',
      'function _err(v){return _tag("Err",_check(v));}',
      'function _is_some(v){return _checkoption(v).kind==="Some";}',
      'function _is_ok(v){return _checkresult(v).kind==="Ok";}',
      'function _option_or(v,d){_check(d);const x=_checkoption(v);return x.kind==="Some"?x.value:d;}',
      'function _result_or(v,d){_check(d);const x=_checkresult(v);return x.kind==="Ok"?x.value:d;}',
      'function _error_or(v,d){_check(d);const x=_checkresult(v);return x.kind==="Err"?x.value:d;}',

    );
  }
  // Validated IR is bounded plain data. Emit private trap helpers only for
  // programs that actually use trap; keep ordinary ADT artifact overhead low.
  function containsTrap(value){
    if(!value||typeof value!=='object')return false;
    if(['trap','trapMethod','trapOptionMethod'].includes(value.kind)||(value.kind==='intrinsic'&&['Option.unwrap','Result.unwrap','Option.expect','Result.expect'].includes(value.name))||(value.kind==='adtMatch'&&value.adt==='trapResult'))return true;
    return Object.values(value).some(containsTrap);
  }
  function containsGuestEffect(value){
    if(!value||typeof value!=='object')return false;
    return value.kind==='guestEffect'||Object.values(value).some(containsGuestEffect);
  }
  const hasGuestEffect=containsGuestEffect(program);
  if(hasGuestEffect)out.push(
    'const _ordinaryGuestBrand=new WeakSet();',
    'function _guestEffect(kind,message){const e=new Error(message);Object.defineProperty(e,"effect",{value:kind,enumerable:true});if(kind==="throw")_ordinaryGuestBrand.add(e);throw Object.freeze(e);}'
  );
  function containsCatch(value){return !!value&&typeof value==='object'&&(value.kind==='tryCatch'||Object.values(value).some(containsCatch));}
  function containsTypedThrow(value){return !!value&&typeof value==='object'&&(value.kind==='throwError'||Object.values(value).some(containsTypedThrow));}
  const hasErrorParameter=program.functions.some(fn=>fn.paramTypes?.includes('error'))||containsTypedThrow(program);
  if(containsTrap(program)||containsCatch(program)||hasErrorParameter)out.push('function _isOrdinaryGuest(e){return _arithmeticBrand.has(e)||(typeof _ordinaryGuestBrand!=="undefined"&&_ordinaryGuestBrand.has(e))||(typeof _checkedUnwrapBrand!=="undefined"&&_checkedUnwrapBrand.has(e));}');
  if(hasErrorParameter)out.push('function _checkGuestError(e){if(!_isOrdinaryGuest(e))throw new TypeError("untrusted guest Error");return e;}');
  if(containsTrap(program))out.push(
      'const _trapBrand=new WeakSet(),_checkedUnwrapBrand=new WeakSet();',
      ...(hasCheckedUnwrap?[
      'function _unwrapFailure(name,message,original){const e=new Error(message,{cause:original});Object.defineProperty(e,"name",{value:name});_checkedUnwrapBrand.add(e);throw Object.freeze(e);}',
      'function _optionUnwrap(v){if(!_tagBrand.has(v))throw new TypeError("untrusted Option unwrap carrier");const x=_checkoption(v);if(x.kind==="Some")return x.value;return _unwrapFailure("OptionUnwrapError","cannot unwrap None",undefined);}',
      'function _resultUnwrap(v){if(!_tagBrand.has(v))throw new TypeError("untrusted Result unwrap carrier");const x=_checkresult(v);if(x.kind==="Ok")return x.value;return _unwrapFailure("ResultUnwrapError","cannot unwrap Err",x.value);}',
      'function _expectPanic(message,cause){if(typeof message!=="string")throw new TypeError("expect message must be string");const e=new Error(message,{cause});Object.defineProperty(e,"effect",{value:"panic",enumerable:true});throw Object.freeze(e);}',
      'function _optionExpect(v,message){if(!_tagBrand.has(v))throw new TypeError("untrusted Option expect carrier");if(typeof message!=="string")throw new TypeError("expect message must be string");const x=_checkoption(v);if(x.kind==="Some")return x.value;return _expectPanic(message,undefined);}',
      'function _resultExpect(v,message){if(!_tagBrand.has(v))throw new TypeError("untrusted Result expect carrier");if(typeof message!=="string")throw new TypeError("expect message must be string");const x=_checkresult(v);if(x.kind==="Ok")return x.value;return _expectPanic(message,x.value);}'
      ]:[]),
      'function _checkTrapResult(v){if(!_trapBrand.has(v))throw new TypeError("invalid trap Result");return v;}',
      'function _trap(thunk,t){try{const v=thunk();if(t==="trapResult"||t==="trapOptionResult"){if(!_trapBrand.has(v))throw new TypeError("invalid trap Result");return v;}const result=t==="option<int>"?_tag("Ok",_checkoption(v)):t==="int"?_ok(v):_checkresult(v);_trapBrand.add(result);return result;}catch(e){if(!_isOrdinaryGuest(e))throw e;const result=_tag("Err",e);_trapBrand.add(result);return result;}}',
      'function _trapMethod(m,result,option){if(!_trapBrand.has(result))throw new TypeError("invalid trap Result");if(m==="is_ok")return result.kind==="Ok";if(m==="is_err")return result.kind==="Err";if(m==="unwrap"&&result.kind==="Ok")return option?_checkoption(result.value):_check(result.value);if(m==="unwrap"){const e=new Error("cannot unwrap Err",{cause:result.value});Object.defineProperty(e,"name",{value:"ResultUnwrapError"});_checkedUnwrapBrand.add(e);throw Object.freeze(e);}throw new TypeError("invalid trap Result method");}'
  );
  (program.classes??[]).forEach((cls,classIndex)=>{
    const local='_class'+classIndex;
    out.push('class '+local+' {','#oresBrand=1;',
      'constructor(..._args){if(new.target!=='+local+')throw new TypeError("unsupported class derivation");if(_args.length!==0)throw new TypeError("constructor arity mismatch");Object.preventExtensions(this);}');
    const families=new Map();
    cls.methods.forEach((method,methodIndex)=>{
      if(!method.pub)return;
      if(!families.has(method.name))families.set(method.name,[]);
      families.get(method.name).push(methodIndex);
    });
    for(const [name,indices] of families){
      out.push(JSON.stringify(name)+'(..._args){',
        'if(this===null||(typeof this!=="object"&&typeof this!=="function")||!(#oresBrand in this))throw new TypeError("invalid Oreslang receiver");',
        'switch(_args.length){');
      for(const methodIndex of indices){
        const method=cls.methods[methodIndex];
        out.push('case '+method.params.length+':{');
        method.params.forEach((_,i)=>{
          const type=method.paramTypes[i];
          const check={int:'_check',bool:'_checkbool',symbol:'_checksymbol',
            'option<int>':'_checkoption','result<int,int>':'_checkresult'}[type];
          if(!check)fail('unsupported method parameter type');
          out.push('const _p'+i+'=_args['+i+'];'+check+'(_p'+i+');');
        });
        out.push(method.returnType==='void'?'return;':'return '+emitExpr(method.body)+';','}');
      }
      out.push('default:throw new TypeError("method arity mismatch");','}','}');
    }
    out.push('}','Object.freeze('+local+'.prototype);','Object.freeze('+local+');');
  });
  (program.generators??[]).forEach((gen,i)=>{
    const params=gen.params.map((_,j)=>'_p'+j);
    out.push('function* _gbody'+i+'('+params.join(',')+'){');
    params.forEach(p=>out.push('_check('+p+');'));
    for(const yielded of gen.yields)
      out.push('yield _check('+emitExpr(yielded)+');');
    out.push('}');
    out.push('function _g'+i+'('+params.join(',')+'){');
    out.push('const _inner=_gbody'+i+'('+params.join(',')+');');
    out.push('let _closed=false,_failure=null;');
    out.push('const _iter=Object.create(null);');
    out.push('Object.defineProperty(_iter,"next",{value:function(..._args){if(_args.length!==0)throw new TypeError("Oreslang Iterator.next takes no arguments");if(_failure!==null)throw _failure;if(_closed)return Object.freeze({value:undefined,done:true});try{const _step=_inner.next();if(_step.done)_closed=true;return Object.freeze({value:_step.value,done:_step.done});}catch(_error){_closed=true;_failure=_error;throw _error;}},enumerable:true});');
    out.push('Object.defineProperty(_iter,"close",{value:function(..._args){if(_args.length!==0)throw new TypeError("Oreslang Iterator.close takes no arguments");if(_closed&&_failure===null)return;_closed=true;_failure=null;try{_inner.return();}catch(_error){_failure=_error;throw _error;}},enumerable:true});');
    out.push('Object.defineProperty(_iter,Symbol.iterator,{value:function(){return this;}});');
    out.push('return Object.freeze(_iter);');
    out.push('}');
  });
  program.functions.forEach((fn,i)=>{
    const params=fn.params.map((_,j)=>'_p'+j);
    out.push('function _f'+i+'('+params.join(',')+'){');
    params.forEach((p,j)=>{
      const type=fn.paramTypes?.[j]??'int';
      const check={int:'_check',bool:'_checkbool',symbol:'_checksymbol',error:'_checkGuestError',
        'option<int>':'_checkoption','result<int,int>':'_checkresult'}[type];
      if(!check)fail('unsupported portable parameter type');
      out.push(check+'('+p+');');
    });
    out.push(fn.returnType==='void'?'return;':'return '+emitExpr(fn.body)+';','}');
  });
  let dispatcherNumber=0;
  function publicCallableGroups(module) {
    const result=new Map();
    const group=name=>{
      if(!result.has(name))result.set(name,{functions:[],generators:[]});
      return result.get(name);
    };
    program.functions.forEach((fn,i)=>{
      if(fn.module===module&&fn.pub)group(fn.name).functions.push(i);
    });
    (program.generators??[]).forEach((gen,i)=>{
      if(gen.module===module&&gen.pub)group(gen.name).generators.push(i);
    });
    return result;
  }
  function exportCallableGroup(group) {
    // One dispatcher owns the exported name across ordinary functions and
    // generators. Same-arity collisions were rejected during resolution.
    const name='_dispatch'+(dispatcherNumber++);
    out.push('function '+name+'(..._args){switch(_args.length){');
    for(const i of group.functions)
      out.push('case '+program.functions[i].params.length+':return _f'+i+'(..._args);');
    for(const i of group.generators)
      out.push('case '+program.generators[i].params.length+':return _g'+i+'(..._args);');
    out.push('default:throw new TypeError("Oreslang overload arity mismatch");}}');
    return name;
  }
  program.modules.forEach((module,i)=>{
    out.push('const _module'+i+'=Object.create(null);');
    for(const [name,group] of publicCallableGroups(module)) {
      const callable=exportCallableGroup(group);
      out.push('Object.defineProperty(_module'+i+','+JSON.stringify(name)+',{value:'+callable+',enumerable:true,configurable:false,writable:false});');
    }
    out.push('Object.freeze(_module'+i+');');
    out.push('export { _module'+i+' as '+JSON.stringify(module)+' };');
  });
  for(const [name,group] of publicCallableGroups(null)) {
    out.push('export { '+exportCallableGroup(group)+' as '+JSON.stringify(name)+' };');
  }
  (program.classes??[]).forEach((cls,i)=>{
    if(cls.pub)out.push('export { _class'+i+' as '+JSON.stringify(cls.name)+' };');
  });
  if(hasSymbol)out.push('export { _symbolWireV1 as "@oreslang/symbol-wire-v1" };');
  return out.join('\n')+'\n';
}
// This is not ambient file importing. The browser compiler embeds only the
// exact .atto testing module owned by THIS backend. No runtime IO, dynamic
// source resolution, graph traversal or host-owned mocks enter the guest.
const TESTING_SCALAR_IMPORT_IDS = new Set([
  'std/testing_scalar', 'std/testing_scalar.atto',
  'atto.std/testing_scalar', 'atto.std/testing_scalar.atto'
]);
// Closed compiler-owned module import. No file lookup, source splice, host
// import or ordinary eager forwarding function. Until lexical alias scoping
// is expanded, all uses of a core module alias must be qualified calls.
function lowerCompilerOwnedBooleanImports(tokens){
  let i=0;
  const aliases=new Set();
  while(tokens[i]?.value==='import'&&tokens[i+1]?.value==='module'&&
        tokens[i+3]?.value==='from'&&tokens[i+4]?.kind==='str'&&
        isCoreBooleanUnit(tokens[i+4].value)){
    const alias=tokens[i+2],end=tokens[i+5];
    if(alias?.kind!=='id'||!isIdentifier(alias.value)||end?.value!==';'||
       aliases.has(alias.value)||aliases.size>=8||
       CLASS_METHOD_RESERVED.has(alias.value)||GENERATOR_RESERVED.has(alias.value)||
       isProtectedPreludeIdentity(alias.value)||coreBooleanIntrinsic(alias.value)||
       ['Integer','Symbol','main'].includes(alias.value)||alias.value.startsWith('__atto_'))
      fail('E_CORE_BOOL_IMPORT: invalid, reserved or duplicate module alias',alias);
    aliases.add(alias.value);i+=6;
  }
  if(!aliases.size)return {rootTokens:tokens,aliases};
  const rootTokens=tokens.slice(i);
  for(let j=0;j<rootTokens.length;j++){
    const t=rootTokens[j];
    if(t.kind==='id'&&aliases.has(t.value)){
      if(rootTokens[j-1]?.value==='.'||rootTokens[j+1]?.value!=='.'||
         rootTokens[j+2]?.kind!=='id'||!coreBooleanIntrinsic(rootTokens[j+2].value)||
         rootTokens[j+3]?.value!=='(')
        fail('E_CORE_BOOL_IMPORT: alias is reserved for qualified core Boolean calls',t);
    }
  }
  return {rootTokens,aliases};
}

function lowerCompilerOwnedTestingImports(tokens) {
  let i=0,declarations=0;
  const locals=new Map(),seenExports=new Set();
  const next=()=>tokens[i];
  const eat=v=>{if(next()?.value===v){i++;return true;}return false;};
  const requireToken=v=>{
    if(!eat(v))fail('E_STD_IMPORT: expected '+v,next());
  };
  const identifier=()=>{
    const t=next();
    if(t?.kind!=='id'||!isIdentifier(t.value))
      fail('E_STD_IMPORT: expected import function identifier',t);
    i++;
    return t.value;
  };
  // Only `import fnc ... from "<compiler-owned testing id>";` is lowered
  // here; every other import form keeps its existing fail-closed diagnostics.
  const isTestingImport=j=>{
    if(tokens[j]?.value!=='import'||tokens[j+1]?.value!=='fnc')return false;
    for(let k=j+2;k<tokens.length&&k<j+64;k++){
      if(tokens[k].value===';')return false;
      if(tokens[k].value==='from')return tokens[k+1]?.kind==='str'&&TESTING_SCALAR_IMPORT_IDS.has(tokens[k+1].value);
    }
    return false;
  };
  while(next()?.value==='import' && isTestingImport(i)) {
    const pos=next();
    if(++declarations>8)fail('E_STD_IMPORT: too many library import declarations',pos);
    i++;
    requireToken('fnc');
    const names=[];
    do {
      const exported=identifier();
      const local=eat('as')?identifier():exported;
      if(!TESTING_SCALAR_EXPORTS.includes(exported))
        fail('E_STD_IMPORT: unknown compiler-owned assertion '+exported,pos);
      if(locals.has(local)||seenExports.has(exported))
        fail('E_STD_IMPORT: duplicated or shadowed assertion import',pos);
      if(local.startsWith('__atto_'))
        fail('E_STD_IMPORT: reserved import alias',pos);
      locals.set(local,exported);
      seenExports.add(exported);
      names.push(local);
      if(names.length>TESTING_SCALAR_EXPORTS.length)
        fail('E_STD_IMPORT: import binding budget exceeded',pos);
    } while(eat(','));
    requireToken('from');
    const path=next();
    if(path?.kind!=='str'||!TESTING_SCALAR_IMPORT_IDS.has(path.value))
      fail('E_STD_IMPORT: unsupported compiler-owned library path',path);
    i++;requireToken(';');
  }
  return {rootTokens:declarations?tokens.slice(i):tokens,locals};
}
function bindCompilerOwnedTestingImports(parsed,locals) {
  if(!locals.size)return parsed;
  // Parse the embedded .atto source through the same parser as user code.
  // Its six public declarations remain separately owned in this compiler.
  const library=new Parser(tokenize(TESTING_SCALAR_SOURCE)).parse();
  if(library.modules.length||library.classes.length||library.generators.length||
      library.functions.length!==TESTING_SCALAR_EXPORTS.length||
      library.functions.some(f=>!f.pub||f.module!==null)||
      TESTING_SCALAR_EXPORTS.some(n=>!library.functions.some(f=>f.name===n)))
    fail('E_STD_IMPORT: compiler-owned library source contract mismatch');
  for(const [local,exported] of locals) {
    if(parsed.functions.some(f=>f.name===local)||
       parsed.generators.some(g=>g.name===local)||
       parsed.classes.some(c=>c.name===local)||parsed.modules.includes(local))
      fail('E_STD_IMPORT: imported name collides with local declaration '+local);
    const fn=library.functions.find(f=>f.name===exported);
    parsed.functions.push({...fn,name:local,pub:false});
  }
  if(parsed.functions.length>512)
    fail('E_STD_IMPORT: linked function budget exceeded');
  return parsed;
}

// Mixed profile: validate unused leading refinement declarations through the
// existing quantity checker. No alias use is erased; active refinement values
// require integrated nominal ADT/quantity typing and remain unsupported here.
function admitUnusedRefinementsBeforeCheckedUnwrap(tokens){
  if(!['type','declare'].includes(tokens[0]?.value)||
     !tokens.some((t,i)=>t.value==='|>'&&tokens[i+1]?.value==='match')||
     !tokens.some(t=>t.value==='trap!'||t.value==='trap'))return null;
  let end=0;const aliases=new Set();
  while(['type','declare'].includes(tokens[end]?.value)){
    const start=end;
    if(tokens[end].value==='declare')end++;
    if(tokens[end]?.value!=='type')fail('expected type after declare',tokens[end]);
    const name=tokens[end+1];
    if(name?.kind!=='id'||isProtectedPreludeIdentity(name.value)||
       GENERATOR_RESERVED.has(name.value)||name.value==='main'||aliases.has(name.value))
      fail('invalid or duplicate mixed-profile refinement',name);
    aliases.add(name.value);
    while(end<tokens.length&&tokens[end].value!==';'&&tokens[end].kind!=='eof')end++;
    if(tokens[end]?.value!==';'||end===start)fail('unterminated refinement declaration',tokens[end]);
    end++;
  }
  const remainder=tokens.slice(end);
  if(remainder.some(t=>t.kind==='id'&&aliases.has(t.value)))
    fail('active refinement use requires integrated ADT proof lowering',remainder[0]);
  const proof=lowerVerifiedQuantitySource([...tokens.slice(0,end),...tokenize('pub fnc main() -> int { return 0; }')],fail);
  if(proof===null)fail('unsupported refinement declaration',tokens[0]);
  validateProgramIRV2({schemaVersion:PROGRAM_IR_VERSION,dialect:PROGRAM_IR_DIALECT,integerModel:PROGRAM_IR_INT_MODEL,...proof});
  return remainder;
}

export function compileToProgramIRV2(source) {
  const tokens=tokenize(source);
  rejectExecutionDomains(tokens,fail,{inlineScalarTasks:true});
  rejectUnsupportedHttpActors(tokens,fail);
  rejectUnsupportedStaticSelect(tokens);
  rejectUnsupportedTrapEffect(tokens);
  const mixed=admitUnusedRefinementsBeforeCheckedUnwrap(tokens);
  const quantity=mixed===null?lowerVerifiedQuantitySource(tokens,fail):null;
  if(quantity!==null)return validateProgramIRV2({
    schemaVersion:PROGRAM_IR_VERSION,dialect:PROGRAM_IR_DIALECT,integerModel:PROGRAM_IR_INT_MODEL,...quantity
  });
  const core=lowerCompilerOwnedBooleanImports(mixed??tokens);
  const linked=lowerCompilerOwnedTestingImports(core.rootTokens);
  const parsed=bindCompilerOwnedTestingImports(new Parser(linked.rootTokens,core.aliases).parse(),linked.locals);
  const program=resolveProgram(parsed);
  return validateProgramIRV2({
    schemaVersion:PROGRAM_IR_VERSION,
    dialect:PROGRAM_IR_DIALECT,
    integerModel:PROGRAM_IR_INT_MODEL,
    ...program
  });
}
// Strict verification admits only the explicitly checked quantity frontend.
 // An unverified ordinary source path must never be used as a fallback.
export function compileVerifiedStrict(source) {
  const tokens=tokenize(source);
  rejectExecutionDomains(tokens,fail);
  rejectUnsupportedHttpActors(tokens,fail);
  rejectUnsupportedStaticSelect(tokens);
  rejectUnsupportedTrapEffect(tokens);
  const quantity=lowerVerifiedQuantitySource(tokens,fail,true);
  if(quantity===null)fail('E_VERIFY_UNSUPPORTED: strict profile requires typed quantity source',tokens[0]);
  return emitProgram(validateProgramIRV2({
    schemaVersion:PROGRAM_IR_VERSION,dialect:PROGRAM_IR_DIALECT,integerModel:PROGRAM_IR_INT_MODEL,...quantity
  },true));
}
export function compileProgramIRV2(ir) {
  return emitProgram(validateProgramIRV2(ir));
}
export function compileProgramV2(source) {
  return emitProgram(compileToProgramIRV2(source));
}
export function checkProgramV2(source) {
  compileProgramV2(source);
  return true;
}

// The current AttoLang scalar contract admits checked truncating division and
// named Integer.rem/mod, not raw percent syntax. Other program-v2 forms remain restricted.
// Keep this an explicit profile rather than silently changing legacy .ores
// syntax or treating a broader JS-only feature as verified Java/LLVM parity.
export function compileAttoScalarV1(source) {
  const ir=compileToProgramIRV2(source);
  if(ir.sourceNamespace!==null||ir.modules.length||
     (ir.classes?.length??0)||(ir.generators?.length??0))
    fail('AttoLang scalar-v1 admits only root integer functions');
  const visit=expr=>{
    switch(expr.kind){
      case 'int':case 'param':case 'capture':return;
      case 'local':visit(expr.init);visit(expr.body);return;
      case 'i32Check':case 'refinementCheck':case 'neg':return visit(expr.inner);
      case 'binary':
        if(!['+','-','*','/','%','mod','^','&','|','<<<','>>>','ashr'].includes(expr.op))
          fail('AttoLang scalar-v1 removed or unsupported operator '+expr.op);
        visit(expr.left);visit(expr.right);return;
      case 'call':
        for(const arg of expr.args)visit(arg);
        return;
      default:fail('AttoLang scalar-v1 expression not yet supported: '+expr.kind);
    }
  };
  for(const fn of ir.functions){
    if(fn.kind!=='fnc'||(fn.returnType??'int')!=='int'||
       (fn.paramTypes??fn.params.map(()=>'int')).some(t=>t!=='int'))
      fail('AttoLang scalar-v1 admits only int fnc signatures');
    visit(fn.body);
  }
  return compileProgramIRV2(ir);
}
export function checkAttoScalarV1(source) {
  compileAttoScalarV1(source);
  return true;
}
