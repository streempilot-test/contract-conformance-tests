import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorkerControlLoop} from '../src/worker-control-loop.mjs';

function harness(options = {}) {
  const scheduled = [], errors = [];
  const loop = createWorkerControlLoop({
    postTurn: fn => {scheduled.push(fn);}, onTurnError: err => errors.push(err),
    ...options
  });
  function step() {assert.ok(scheduled.length, 'queued scheduler turn');scheduled.shift()();}
  function drain() {let budget=100;while(scheduled.length){assert.ok(--budget>0);step();}}
  return {loop, scheduled, errors, step, drain};
}

test('reactor completion only queues; CONTROL wins before completed data', () => {
  const h = harness();
  const seen = [];
  assert.equal(h.loop.attachOwner('actor',1), true);
  const ticket = h.loop.beginSuspension('actor',1, value => {seen.push(`resume:${value}`);});
  assert.equal(h.loop.complete(ticket, 42), true);
  assert.equal(h.loop.admitControl('actor',1, () => {seen.push('stop');}), true);
  assert.deepEqual(seen, [], 'no guest code runs inline on reactor completion');
  h.step();
  assert.deepEqual(seen, ['stop']);
  h.drain();
  assert.deepEqual(seen, ['stop','resume:42']);
});

test('DATA FIFO and one quantum per scheduled turn', () => {
  const h = harness(), seen = [];
  h.loop.attachOwner('a',1);
  for (let i=0;i<4;i++) assert.equal(h.loop.admitData('a',1, n => {seen.push(n);},i),true);
  h.step(); assert.deepEqual(seen,[0]);
  h.drain(); assert.deepEqual(seen,[0,1,2,3]);
  assert.equal(h.loop.stats.completed,4);
});

test('single completion, opaque ticket identity, and cancellation', () => {
  const h = harness(), seen=[];
  h.loop.attachOwner('a',2);
  const a=h.loop.beginSuspension('a',2, n=>seen.push(n));
  assert.equal(h.loop.complete({...a},3),false);
  assert.equal(h.loop.complete(a,3),true);
  assert.equal(h.loop.complete(a,4),false);
  const b=h.loop.beginSuspension('a',2, n=>seen.push(n));
  assert.equal(h.loop.cancel({...b}),false);
  assert.equal(h.loop.cancel(b),true);
  assert.equal(h.loop.complete(b,4),false);
  h.drain(); assert.deepEqual(seen,[3]);
});

test('retiring actor fences pending and queued work across same owner new epoch', () => {
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  const old=h.loop.beginSuspension('a',1,n=>seen.push(n));
  h.loop.admitData('a',1,()=>seen.push('old'));
  assert.equal(h.loop.retireOwner('a',1),true);
  assert.equal(h.loop.complete(old,1),false);
  assert.equal(h.loop.attachOwner('a',1),false, 'old epoch must not be reused');
  assert.equal(h.loop.attachOwner('a',2),true);
  h.loop.admitData('a',2,()=>seen.push('new'));
  h.drain();
  assert.deepEqual(seen,['new']);
  assert.equal(h.loop.stats.dropped,1);
});

test('CONTROL capacity reserved independently of ready queue; wait quota bounded',()=>{
  const h=harness({maxReady:1,maxControl:1,maxSuspended:1});
  h.loop.attachOwner('a',1);
  assert.equal(h.loop.admitData('a',1,()=>{}),true);
  assert.equal(h.loop.admitData('a',1,()=>{}),false);
  assert.equal(h.loop.admitControl('a',1,()=>{}),true);
  assert.equal(h.loop.admitControl('a',1,()=>{}),false);
  const resumed=[];
  const t=h.loop.beginSuspension('a',1,n=>resumed.push(n));
  assert.ok(t);
  assert.equal(h.loop.beginSuspension('a',1,()=>{}),null);
  assert.equal(h.loop.complete(t,1),true, 'completed ticket safely buffered');
  assert.equal(h.loop.complete(t,99),false, 'settlement is exactly once');
  assert.equal(h.loop.stats.settled,1);
  h.step(); // CONTROL
  assert.equal(h.loop.stats.settled,1);
  h.step(); // DATA frees one slot and promotes buffered completion
  assert.equal(h.loop.stats.settled,0);
  h.drain();
  assert.deepEqual(resumed,[1]);
  assert.equal(h.loop.stats.suspended,0);
});

test('close revokes every waiter and drops scheduled turns',()=>{
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  const t=h.loop.beginSuspension('a',1,()=>seen.push('bad'));
  h.loop.admitData('a',1,()=>seen.push('bad'));
  h.loop.close();
  h.drain();
  assert.equal(h.loop.complete(t,2),false);
  assert.equal(h.loop.admitControl('a',1,()=>{}),false);
  assert.deepEqual(seen,[]);
  assert.equal(h.loop.stats.closed,true);
});

test('throw or a Promise-valued quantum never holds up the control loop',()=>{
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  h.loop.admitData('a',1,()=>{throw Error('boom');});
  h.loop.admitData('a',1,()=>Promise.resolve(1));
  h.loop.admitData('a',1,()=>seen.push('healthy'));
  h.drain();
  assert.deepEqual(seen,['healthy']);
  assert.match(h.errors[0].message,/boom/);
  assert.match(h.errors[1].message,/synchronous quanta/);
  assert.equal(h.loop.stats.completed,1);
});

test('invalid or stale controls fail closed',()=>{
  const h=harness();
  assert.throws(()=>h.loop.attachOwner('a',0),/owner epoch/);
  assert.equal(h.loop.attachOwner('a',1),true);
  assert.equal(h.loop.attachOwner('a',1),false);
  assert.equal(h.loop.admitData('a',2,()=>{}),false);
  assert.throws(()=>h.loop.beginSuspension('a',1,null),/invalid suspension/);
});

test('synchronous postTurn callbacks fail closed instead of running guest code inline',()=>{
  const seen=[],errors=[];
  const loop=createWorkerControlLoop({postTurn:fn=>fn(),onTurnError:e=>errors.push(e)});
  loop.attachOwner('a',1);
  loop.admitData('a',1,()=>seen.push('guest'));
  assert.deepEqual(seen,[]);
  assert.equal(loop.stats.closed,true);
  assert.match(errors[0].message,/must schedule asynchronously/);
});

test('opaque ticket lookup ignores forged getters and proxy aliases',()=>{
  const h=harness();h.loop.attachOwner('a',1);
  const ticket=h.loop.beginSuspension('a',1,()=>{});
  const evil=new Proxy(ticket,{get(){throw Error('getter must not run');}});
  assert.equal(h.loop.complete(evil,2),false);
  assert.equal(h.loop.cancel(evil),false);
  assert.equal(h.loop.cancel({get id(){throw Error('forged id');}}),false);
  assert.equal(h.loop.cancel(ticket),true);
});

test('queued and buffered completions are cancellable before dispatch',()=>{
  const h=harness({maxReady:1,maxSuspended:3}),seen=[];
  h.loop.attachOwner('a',1);
  const a=h.loop.beginSuspension('a',1,n=>seen.push('a'+n));
  const b=h.loop.beginSuspension('a',1,n=>seen.push('b'+n));
  assert.equal(h.loop.complete(a,1),true);
  assert.equal(h.loop.complete(b,2),true);
  assert.equal(h.loop.stats.ready,1);
  assert.equal(h.loop.stats.settled,1);
  assert.equal(h.loop.cancel(a),true);
  assert.equal(h.loop.stats.settled,0);
  assert.equal(h.loop.stats.ready,1);
  assert.equal(h.loop.cancel(b),true);
  h.drain();
  assert.deepEqual(seen,[]);
  assert.equal(h.loop.stats.suspended,0);
});

test('retirement refunds capacity and promotes another owner staged completion',()=>{
  const h=harness({maxReady:1,maxControl:1,maxSuspended:2}),seen=[];
  h.loop.attachOwner('old',1);h.loop.attachOwner('new',1);
  assert.equal(h.loop.admitData('old',1,()=>seen.push('stale')),true);
  assert.equal(h.loop.admitControl('old',1,()=>seen.push('stale-control')),true);
  const pending=h.loop.beginSuspension('new',1,n=>seen.push('resume:'+n));
  assert.equal(h.loop.complete(pending,42),true);
  assert.equal(h.loop.stats.settled,1);
  assert.equal(h.loop.retireOwner('old',1),true);
  assert.equal(h.loop.stats.ready,1);
  assert.equal(h.loop.stats.control,0);
  assert.equal(h.loop.stats.settled,0);
  h.drain();
  assert.deepEqual(seen,['resume:42']);
  assert.equal(h.loop.stats.dropped,2);
});

test('a malicious host invoking postTurn callback twice cannot run two turns',()=>{
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  h.loop.admitData('a',1,()=>seen.push(1));
  h.loop.admitData('a',1,()=>seen.push(2));
  const twice=h.scheduled[0];
  h.step();
  twice();
  h.drain();
  assert.deepEqual(seen,[1]);
  assert.equal(h.loop.stats.closed,true);
  assert.match(h.errors[0].message,/more than once/);
});

test('async and generator callables are rejected before executing',()=>{
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  assert.throws(()=>h.loop.admitData('a',1,async()=>seen.push('async')), /invalid continuation/);
  assert.throws(()=>h.loop.beginSuspension('a',1,async()=>seen.push('async')), /invalid suspension/);
  assert.throws(()=>h.loop.admitControl('a',1,function*(){seen.push('generator');}), /invalid continuation/);
  h.drain();
  assert.deepEqual(seen,[]);
});

test('bounded FIFO staged completion burst settles once without lost wakes',()=>{
  const h=harness({maxReady:2,maxSuspended:32}),seen=[];
  h.loop.attachOwner('a',1);
  for(let i=0;i<32;i++){
    const ticket=h.loop.beginSuspension('a',1,n=>seen.push(n));
    assert.ok(ticket);
    assert.equal(h.loop.complete(ticket,i),true);
    assert.equal(h.loop.complete(ticket,i+100),false);
  }
  assert.equal(h.loop.beginSuspension('a',1,()=>{}),null);
  assert.equal(h.loop.stats.ready,2);
  assert.equal(h.loop.stats.settled,30);
  h.drain();
  assert.deepEqual(seen,Array.from({length:32},(_,i)=>i));
  assert.equal(h.loop.stats.suspended,0);
});

test('async/generator quanta cannot hide behind a forged Symbol.toStringTag',()=>{
  const h=harness(),seen=[];
  h.loop.attachOwner('a',1);
  const asyncTurn=async()=>{seen.push('async body');await Promise.resolve();seen.push('escaped continuation');};
  const generatorTurn=function*(){seen.push('generator body');};
  const asyncGenerator=async function*(){seen.push('async generator body');};
  for(const run of [asyncTurn,generatorTurn,asyncGenerator]){
    Object.defineProperty(run,Symbol.toStringTag,{value:'Function'});
    assert.equal(Object.prototype.toString.call(run),'[object Function]',
      'test must demonstrate the forged tag bypasses the old validator');
    assert.throws(()=>h.loop.admitData('a',1,run),/invalid continuation/);
    assert.throws(()=>h.loop.admitControl('a',1,run),/invalid continuation/);
    assert.throws(()=>h.loop.beginSuspension('a',1,run),/invalid suspension/);
  }
  h.drain();
  assert.deepEqual(seen,[]);
  assert.equal(h.loop.stats.suspended,0);
  assert.equal(h.loop.stats.ready,0);
  assert.equal(h.loop.stats.control,0);
});


test('coalesced TaskCooperateRequest defers one DATA quantum, never a CONTROL turn',()=>{
  const h=harness(),seen=[];
  assert.equal(h.loop.attachOwner('cooperating',1),true);
  assert.equal(h.loop.attachOwner('other',1),true);
  assert.equal(h.loop.admitData('cooperating',1,()=>seen.push('cooperating')),true);
  assert.equal(h.loop.admitData('other',1,()=>seen.push('other')),true);
  assert.equal(h.loop.requestCooperate('cooperating',1),true);
  assert.equal(h.loop.requestCooperate('cooperating',1),true);
  assert.equal(h.loop.stats.cooperatePending,1);
  assert.equal(h.loop.stats.cooperateRequests,1);
  assert.equal(h.loop.stats.cooperateCoalesced,1);
  h.step();
  assert.deepEqual(seen,[], 'hint is observed only at legal scheduler boundary');
  assert.equal(h.loop.stats.cooperateDeferrals,1);
  assert.equal(h.loop.stats.ready,2,'no extra scheduler ticket minted');
  h.step();
  assert.deepEqual(seen,['other'],'different owner gets next fair DATA turn');
  h.drain();
  assert.deepEqual(seen,['other','cooperating']);
  assert.equal(h.loop.stats.cooperatePending,0);
  assert.equal(h.loop.stats.completed,2);
});

test('TaskCooperateRequest never postpones cancel CONTROL or revives retired owner',()=>{
  const h=harness(),seen=[];
  assert.equal(h.loop.attachOwner('child',10),true);
  assert.equal(h.loop.admitData('child',10,()=>seen.push('guest-data')),true);
  assert.equal(h.loop.requestCooperate('child',10),true);
  assert.equal(h.loop.admitControl('child',10,()=>seen.push('cancel-control')),true);
  h.step();
  assert.deepEqual(seen,['cancel-control']);
  assert.equal(h.loop.stats.cooperatePending,1);
  assert.equal(h.loop.retireOwner('child',10),true);
  assert.equal(h.loop.stats.cooperatePending,0);
  assert.equal(h.loop.requestCooperate('child',10),false);
  assert.equal(h.loop.attachOwner('child',11),true);
  assert.equal(h.loop.requestCooperate('child',10),false);
  assert.equal(h.loop.admitData('child',11,()=>seen.push('fresh')),true);
  h.drain();
  assert.deepEqual(seen,['cancel-control','fresh']);
  assert.equal(h.loop.stats.cooperateDeferrals,0,'stale hints never delay new incarnation');
});

test('cooperation hint keeps a queued completion cancellable and cannot wake a suspended child',()=>{
  const h=harness(),seen=[];
  assert.equal(h.loop.attachOwner('child',1),true);
  const ticket=h.loop.beginSuspension('child',1,value=>seen.push(value));
  assert.equal(h.loop.requestCooperate('child',1),true);
  assert.equal(h.scheduled.length,0,'request does not synthesize a wake');
  assert.equal(h.loop.complete(ticket,42),true);
  h.step();
  assert.equal(h.loop.stats.cooperateDeferrals,1);
  assert.equal(h.loop.cancel(ticket),true,'completion still cancellable after deferral');
  h.drain();
  assert.deepEqual(seen,[]);
  assert.equal(h.loop.stats.suspended,0);
  assert.equal(h.loop.stats.ready,0);
});

test('closed loop rejects new TaskCooperateRequest and clears bounded pending hints',()=>{
  const h=harness();
  assert.equal(h.loop.attachOwner('a',1),true);
  assert.equal(h.loop.requestCooperate('a',1),true);
  assert.throws(()=>h.loop.requestCooperate({},1),/invalid cooperation identity/);
  assert.throws(()=>h.loop.requestCooperate('a',0),/invalid cooperation identity/);
  assert.equal(h.loop.stats.cooperatePending,1);
  h.loop.close();
  assert.equal(h.loop.stats.cooperatePending,0);
  assert.equal(h.loop.requestCooperate('a',1),false);
});

test('active DATA and CONTROL quanta pin their owner through return or throw',()=>{
  for (const lane of ['admitData','admitControl']) {
    for (const fails of [false,true]) {
      const h=harness(), seen=[];
      assert.equal(h.loop.attachOwner('a',1),true);
      assert.equal(h.loop.attachOwner('b',1),true);
      assert.equal(h.loop[lane]('a',1,()=>{
        seen.push(h.loop.retireOwner('a',1));
        seen.push(h.loop.close());
        seen.push(h.loop.attachOwner('a',2));
        // The unrelated idle owner is not pinned by this turn.
        seen.push(h.loop.retireOwner('b',1));
        if(fails) throw Error('expected callback failure');
      }),true);
      h.step();
      // Assert outside the callback: onTurnError intentionally catches throws.
      assert.deepEqual(seen,[false,false,false,true]);
      assert.equal(h.loop.stats.closed,false);
      assert.equal(h.loop.stats.owners,1);
      assert.equal(h.loop.stats.errors,fails?1:0);
      assert.equal(h.loop.retireOwner('a',1),true);
      assert.equal(h.loop.attachOwner('a',2),true);
      assert.equal(h.loop.admitData('a',1,()=>{}),false);
      assert.equal(h.loop.admitData('a',2,()=>seen.push(42)),true);
      h.drain();
      assert.equal(seen.at(-1),42);
      assert.equal(h.loop.retireOwner('a',2),true);
      h.loop.close();
      assert.equal(h.loop.stats.closed,true);
    }
  }
});
