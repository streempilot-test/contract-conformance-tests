// Host-side logical continuation queue shared by browser and Node Worker hosts.
// Not a Wasm guest ABI, NIO implementation, or an untrusted TenantVM sandbox.
// Reactor/MessagePort callbacks may *admit* a completion, never run guest code.
import {SelectContinuationFence} from './select-continuation-fence.mjs';
const validLimit = (n, maximum) => Number.isSafeInteger(n) && n >= 1 && n <= maximum;
const validEpoch = n => Number.isSafeInteger(n) && n >= 1;
const validOwner = x => typeof x === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(x);

export function createWorkerControlLoop({
  maxOwners = 256, maxSuspended = 1024, maxReady = 1024, maxControl = 4,
  postTurn = callback => setTimeout(callback, 0),
  onTurnError = () => {}
} = {}) {
  if (![maxOwners,maxSuspended,maxReady,maxControl].every(n => validLimit(n, 65536)) ||
      typeof postTurn !== 'function' || typeof onTurnError !== 'function') {
    throw new TypeError('invalid control-loop configuration');
  }

  const owners = new Map(), epochs = new Map(), waiting = new Map(), ticketEntries = new WeakMap();
  const ready = [], control = [], settled = [];
  // Physical credits survive every park/resume until logical unwind quiesces.
  const ownerFences = new Map();
  let retainedOwners = 0;
  // A synchronous quantum is itself physical ownership, even without a parked lease.
  let runningOwner = null;
  // One coalesced scheduling hint per active owner. This is trusted host-only
  // authority, not a guest-exposed TaskId or forced native-thread preemption.
  const cooperatePending = new Map();
  const MAX_EPOCH_IDENTITIES = 65536;
  let nextTicket = 1, scheduled = false, executing = false, closed = false;
  let completed = 0, dropped = 0, errors = 0;
  let cooperateRequests = 0, cooperateDeferrals = 0, cooperateCoalesced = 0;
  const active = (owner, epoch) => owners.get(owner) === epoch;
  // An async function would continue executing off-scheduler after its first await.
  // Symbol.toStringTag can be forged on function objects, so checking only
  // Object.prototype.toString is insufficient even in the trusted host lane.
  const excludedCallables = new Set([
    Object.getPrototypeOf(async function () {}),
    Object.getPrototypeOf(function* () {}),
    Object.getPrototypeOf(async function* () {}),
  ]);
  const validRun = run => {
    if (typeof run !== 'function') return false;
    const tag = Object.prototype.toString.call(run);
    if (tag === '[object AsyncFunction]' ||
        tag === '[object AsyncGeneratorFunction]' ||
        tag === '[object GeneratorFunction]') return false;
    let proto = Object.getPrototypeOf(run);
    for (let depth = 0; proto !== null; depth++) {
      if (excludedCallables.has(proto) || depth >= 32) return false;
      proto = Object.getPrototypeOf(proto);
    }
    return true;
  };
  const backlog = () => ready.length + control.length;

  function report(error) {
    errors++;
    try {onTurnError(error);} catch { /* Error observers cannot break the loop. */ }
  }

  function failClosed(error) {
    if (closed) return;
    closed = true;
    owners.clear(); epochs.clear(); waiting.clear(); cooperatePending.clear(); ready.length = 0;
    control.length = 0; settled.length = 0;
    report(error);
  }

  // Completed I/O is retained within the already reserved waiter quota until
  // the bounded runnable queue has space. No reactor retry/lost wakeup required.
  function promoteSettled() {
    while (!closed && settled.length && ready.length < maxReady) {
      const entry = settled.shift();
      if (waiting.get(entry.id) !== entry || !active(entry.owner, entry.epoch)) continue;
      entry.phase = 'queued';
      ready.push({owner: entry.owner, epoch: entry.epoch, run: entry.resume,
        value: entry.value, entry});
      entry.value = undefined;
    }
  }

  function schedule() {
    if (scheduled || executing || closed || backlog() === 0) return;
    scheduled = true;
    let inPostTurn = true, invoked = false;
    try {postTurn(() => {
      if (invoked) {failClosed(new TypeError('postTurn invoked callback more than once')); return;}
      invoked = true;
      // An injected synchronous host callback must never execute a turn
      // within a reactor-completion or admission stack.
      if (inPostTurn) {
        failClosed(new TypeError('postTurn must schedule asynchronously'));
        return;
      }
      scheduled = false;
      if (!closed) dispatchOne();
    });} catch (error) {
      // A broken host scheduler is fatal; never run queued guest code inline.
      failClosed(error);
    } finally {inPostTurn = false;}
  }

  function dispatchOne() {
    if (executing || closed) return;
    executing = true;
    try {
      // CONTROL has reserved queue capacity independent of DATA readiness.
      const isControl = control.length > 0;
      const item = isControl ? control.shift() : ready.shift();
      if (!item) return;
      if (!active(item.owner, item.epoch) ||
          (item.entry && waiting.get(item.entry.id) !== item.entry)) {
        dropped++; return;
      }
      // A control message must never be delayed by a fairness hint. A DATA
      // item may move to the tail once, but no extra ticket or active turn is
      // fabricated; a stale or cancelled waiter remains fenced until dispatch.
      if (!isControl && cooperatePending.get(item.owner) === item.epoch) {
        cooperatePending.delete(item.owner);
        ready.push(item);
        cooperateDeferrals++;
        return;
      }
      // The queued completion remains cancellable until this exact dispatch.
      if (item.entry) waiting.delete(item.entry.id);
      runningOwner = item.owner;
      try {
        const result = item.run(item.value);
        // Quanta must not await a JS Promise while claiming an actor turn.
        // The compiler must snapshot a continuation and explicitly resume it.
        if (result != null && (typeof result === 'object' || typeof result === 'function')) {
          const then = result.then;
          if (typeof then === 'function') {
            // Observe a real rejected Promise so the host does not leak an
            // unhandled rejection; no settlement re-enters this turn.
            void Promise.resolve(result).catch(() => {});
            throw new TypeError('control-loop turns must be synchronous quanta');
          }
        }
        completed++;
      } catch (error) {report(error);}
    } finally {
      runningOwner = null;
      executing = false;
      promoteSettled();
      schedule();
    }
  }

  function enqueue(queue, limit, owner, epoch, run, value) {
    if (!validOwner(owner) || !validEpoch(epoch) || !validRun(run))
      throw new TypeError('invalid continuation');
    if (closed || !active(owner, epoch)) return false;
    if (queue.length >= limit) return false;
    queue.push({owner, epoch, run, value});
    schedule();
    return !closed;
  }

  return Object.freeze({
    attachOwner(owner, epoch) {
      if (!validOwner(owner) || !validEpoch(epoch)) throw new TypeError('invalid owner epoch');
      if (closed || owners.has(owner) || owners.size >= maxOwners ||
          (epochs.has(owner) && epoch <= epochs.get(owner)) ||
          (!epochs.has(owner) && epochs.size >= MAX_EPOCH_IDENTITIES)) return false;
      owners.set(owner, epoch);
      epochs.set(owner, epoch);
      return true;
    },
    // Trusted host only. Bounded independently of the ready/waiting queues.
    // A closed/faulted scheduler never revives a retained generation.
    retainOwner(owner, epoch) {
      if (!validOwner(owner) || !validEpoch(epoch)) throw new TypeError('invalid owner epoch');
      if (closed || !active(owner,epoch) || retainedOwners >= maxSuspended) return null;
      let fence=ownerFences.get(owner);
      if (!fence) {
        fence=new SelectContinuationFence({generation:epoch,maxTickets:Math.min(maxSuspended,4096)});
        ownerFences.set(owner,fence);
      }
      if (fence.executing >= Math.min(maxSuspended,4096)) return null;
      const lease=fence.tryClaim(fence.register(epoch));
      retainedOwners++;
      let held=true;
      return Object.freeze({generation:epoch,close(){
        if (!held) return false;
        held=false;
        lease.close();
        retainedOwners--;
        if (fence.executing===0) ownerFences.delete(owner);
        return true;
      }});
    },
    admitData(owner, epoch, run, value) {
      return enqueue(ready, maxReady, owner, epoch, run, value);
    },
    admitControl(owner, epoch, run, value) {
      return enqueue(control, maxControl, owner, epoch, run, value);
    },
    requestCooperate(owner, epoch) {
      if (!validOwner(owner) || !validEpoch(epoch))
        throw new TypeError('invalid cooperation identity');
      if (closed || !active(owner,epoch)) return false;
      // No wakeup, unbounded queue insertion, cross-owner scheduler ticket,
      // or forced yield in an active synchronous/critical guest quantum.
      if (cooperatePending.get(owner) === epoch) cooperateCoalesced++;
      else {cooperatePending.set(owner,epoch);cooperateRequests++;}
      return true;
    },
    beginSuspension(owner, epoch, resume) {
      if (!validOwner(owner) || !validEpoch(epoch) || !validRun(resume))
        throw new TypeError('invalid suspension');
      if (closed || !active(owner, epoch) || waiting.size >= maxSuspended ||
          !Number.isSafeInteger(nextTicket)) return null;
      const id = nextTicket++;
      const ticket = Object.freeze({owner, epoch, id});
      const entry = {id, owner, epoch, resume, phase: 'waiting', value: undefined};
      waiting.set(id, entry);
      ticketEntries.set(ticket, entry);
      return ticket;
    },
    // Host-only ticket *object identity* prevents accidental cross-owner
    // completion. Serialized worker frames need a separate authenticated ABI.
    complete(ticket, value) {
      if (closed || !ticket || typeof ticket !== 'object') return false;
      const entry = ticketEntries.get(ticket);
      if (!entry || waiting.get(entry.id) !== entry || entry.phase !== 'waiting' ||
          !active(entry.owner, entry.epoch)) return false;
      if (ready.length >= maxReady || settled.length) {
        entry.phase = 'staged';
        entry.value = value;
        settled.push(entry);
      } else {
        entry.phase = 'queued';
        ready.push({owner: entry.owner, epoch: entry.epoch, run: entry.resume, value, entry});
        schedule();
      }
      return !closed;
    },
    cancel(ticket) {
      if (!ticket || typeof ticket !== 'object') return false;
      const entry = ticketEntries.get(ticket);
      if (!entry || waiting.get(entry.id) !== entry) return false;
      waiting.delete(entry.id);
      if (entry.phase === 'staged') {
        const index = settled.indexOf(entry);
        if (index !== -1) settled.splice(index, 1);
      } else if (entry.phase === 'queued') {
        const index = ready.findIndex(item => item.entry === entry);
        if (index !== -1) {ready.splice(index, 1); dropped++;}
      }
      entry.value = undefined;
      promoteSettled();
      schedule();
      return true;
    },
    retireOwner(owner, epoch) {
      if (!active(owner, epoch) || runningOwner === owner || ownerFences.has(owner)) return false;
      owners.delete(owner);
      cooperatePending.delete(owner);
      for (const [id, entry] of waiting) {
        if (entry.owner === owner && entry.epoch === epoch) {
          waiting.delete(id);
          entry.value = undefined;
        }
      }
      for (const queue of [ready, control, settled]) {
        for (let i = queue.length - 1; i >= 0; i--) {
          if (queue[i].owner === owner && queue[i].epoch === epoch) {
            queue.splice(i, 1);
            if (queue !== settled) dropped++;
          }
        }
      }
      // Retirement refunds ready capacity immediately, not after stale turns run.
      // Pending active guard cleanup is NOT certified by this host-only queue.
      promoteSettled();
      schedule();
      return true;
    },
    close() {
      if (executing || retainedOwners!==0) return false;
      if (closed) return;
      closed = true;
      owners.clear(); epochs.clear(); waiting.clear(); cooperatePending.clear(); ready.length = 0; control.length = 0; settled.length = 0;
    },
    get stats() {
      return Object.freeze({closed, owners: owners.size, suspended: waiting.size,
        ready: ready.length, control: control.length, settled: settled.length, completed, dropped, errors,
        cooperatePending: cooperatePending.size, cooperateRequests, cooperateDeferrals,
        cooperateCoalesced, retainedOwners});
    }
  });
}
