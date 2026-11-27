import assert from 'node:assert/strict';
import {readFileSync,existsSync,mkdtempSync,rmSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
const cases=JSON.parse(readFileSync('fixtures/manifest.json','utf8')).cases.filter(c=>["delimited-single-subject","delimited-unresolved","delimited-empty"].includes(c.id));
assert.equal(cases.length,3);
for(const c of cases){const b=readFileSync(join('fixtures',c.file));assert.equal(createHash('sha1').update(Buffer.from('blob '+b.length+'\0')).update(b).digest('hex'),c.sourceGitBlobSha1);}
console.log('3/3 exact fixture hashes');
if(process.argv[2]==='--integrity-only')process.exit(0);
const compiler=resolve(process.argv[2]),tmp=mkdtempSync(join(tmpdir(),'atto-match-'));
const compile=(name,source)=>{const input=join(tmp,name+'.atto'),out=join(tmp,name+'.wasm');writeFileSync(input,source);rmSync(out,{force:true});const p=spawnSync(compiler,[input,'-o',out],{cwd:tmp,encoding:'utf8',timeout:30000});assert.ifError(p.error);assert.equal(p.signal,null);return {p,out};};
function guest(name,source,expected,expectedPrint=''){const {p,out}=compile(name,source);assert.equal(p.status,0,p.stderr);assert.ok(existsSync(out));let printed='';const module=new WebAssembly.Module(readFileSync(out));let instance;const host={print_i64:v=>{printed+=String(v)},print_newline:()=>{printed+='\n'},print_str:(ptr,len)=>{printed+=new TextDecoder().decode(new Uint8Array(instance.exports.memory.buffer,ptr,len))}};for(const imp of WebAssembly.Module.imports(module)){assert.equal(imp.module,'atto');assert.ok(Object.hasOwn(host,imp.name),imp.name)}instance=new WebAssembly.Instance(module,{atto:host});assert.equal(instance.exports.main(),BigInt(expected));assert.equal(instance.exports.panic_code.value,0);assert.equal(printed,expectedPrint);console.log('PASS guest '+name);}
function reject(name,source){const {p,out}=compile(name,source);assert.equal(p.status,1,p.stderr);assert.match(p.stderr,/E_[A-Z_]+/);assert.equal(existsSync(out),false);console.log('PASS rejection '+name+' '+p.stderr.trim().slice(0,250));}
try{
guest('baseline','pub fnc main(): int { return 42; }',42);
for(const c of cases){const b=readFileSync(join('fixtures',c.file));if(c.expect==='exit')guest(c.id,b,c.exit);else reject(c.id,b);}
guest('grouped-or','pub fnc main(): int { return match |(2 | 4)| over on 6 -> 42, on _ -> 0 end; }',42);
guest('logical-or','pub fnc main(): int { return match |true || false| over on true -> 42, on false -> 0 end; }',42);
guest('once-only','fnc subject(): int { stdio.println("subject"); return 2; } pub fnc main(): int { return match |subject()| over on 0 -> 0, on 2 -> 42, on _ -> 0 end; }',42,'subject\n');
guest('default-only','fnc subject(): int { stdio.println("subject"); return 2; } pub fnc main(): int { return match |subject()| over on _ -> 42 end; }',42,'subject\n');
guest('statement','pub fnc main(): int { match |7| over on 7 -> { return 42; } on _ -> { return 0; } end }',42);
guest('legacy','pub fnc main(): int { return match 7 over on 7 -> 42, on _ -> 0 end; }',42);
reject('unsupported-product','pub fnc main(): int { return match |2, 4| over on _ -> 42 end; }');
reject('ungrouped-or','pub fnc main(): int { return match |2 | 4| over on _ -> 42 end; }');
reject('trailing-comma','pub fnc main(): int { return match |2,| over on _ -> 42 end; }');
}finally{rmSync(tmp,{recursive:true,force:true})}
