import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {compileProgramV2,compileToProgramIRV2,compileProgramIRV2} from '../src/program-v2.mjs';
const cases=[["220-recur-sum.atto","adf984b7e7c7e1dd864460e89db50e47f0ee50dd","45"],["221-recur-parallel-fibonacci.atto","5ea7a41aa0052b55ff6d10eebce7561dbe78fe8b","13"],["227-recur-source-order-init.atto","4ec369653a9b333c61449a02b4c06fc8e51f4d07","6"],["229-recur-constant-stack.atto","33d71fc35d920b086cda844577228cb6bdb3d45e","100000"]];
let seq=0;
async function run(src){
 const js=compileProgramV2(src);
 return (await import('data:text/javascript;base64,'+Buffer.from(js+'\n// '+seq++).toString('base64'))).main();
}
for(const [name,hash,expected] of cases)test(name,async()=>{
 const b=readFileSync(new URL('fixtures/'+name,import.meta.url));
 assert.equal(createHash('sha1').update('blob '+b.length+'\0').update(b).digest('hex'),hash);
 assert.equal(await run(b.toString('utf8')),BigInt(expected));
});
test('ten million iterations execute without recursive stack growth',async()=>{
 assert.equal(await run('pub fnc main(): int { return loop (val n: int = 0) { if n >= 10000000 { break n; } recur(n+1); }; }'),10000000n);
});
test('reject invalid loop source',()=>{
 for(const body of [
  'loop (let n:int=0) { if n eq 0 { break n; } recur(n+1); }',
  'loop (val n:int=0) { if n eq 0 { break n; } recur(true); }',
  'loop (val n:int=0) { if n eq 0 { break n; } recur(); }',
  'loop (val n:int=0) { recur(n+1); }',
  'loop (val n:int=0) { if n eq 0 { break n; } recur(n+1); break n; }',
  'loop (val n:int=0) { if n eq 0 { break f(); } recur(n+1); }',
  'loop (val n:int=0) { if n eq 0 { break true; } recur(n+1); }'
 ])assert.throws(()=>compileProgramV2('fnc f():int{return 42;} pub fnc main():int{return '+body+';}'),undefined,body);
});
test('supplied IR rejects forged slots, arity, missing break and external parameter',()=>{
 const src='pub fnc main(): int { return loop (val n:int=0) { if n eq 0 { break n; } recur(n+1); }; }';
 const original=compileToProgramIRV2(src);
 for(const mutate of [e=>e.states[0].slot=-1,e=>e.args=[],e=>e.guards=[],e=>e.guards[0].value={kind:'param',index:0},e=>e.states[0].init={kind:'capture',slot:e.states[0].slot}]){
  const d=structuredClone(original);mutate(d.functions[0].body);
  assert.throws(()=>compileProgramIRV2(d));
 }
});
