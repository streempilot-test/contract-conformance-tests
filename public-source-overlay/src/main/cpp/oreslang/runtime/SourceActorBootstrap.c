#include "oreslang/runtime/SourceActorBootstrap.h"
#include "oreslang/runtime/SourceRecoveryLeaseV1.h"
#include "oreslang/runtime/NativeActorLogicalFramesV1.h"
#include "oreslang/runtime/NativeOresVM.h"
#include "oreslang/runtime/NativeAwaitTraceBridge.h"
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define SOURCE_ACTOR_MAGIC UINT64_C(0x4f52455353504157)
#define SOURCE_GROUP_SLOTS 64u
#define SOURCE_GROUP_ROOT_SLOT (SOURCE_GROUP_SLOTS - 1u)
#define SOURCE_GROUP_CARRIERS 4u
#define SOURCE_GROUP_MAILBOX 128u
#define SOURCE_TRACE_EXECUTIONS 128u
#define SOURCE_TRACE_CONTINUATIONS 128u
#define SOURCE_ROOT_CONTROL_SUSPEND UINT64_C(1)
#define SOURCE_ROOT_CONTROL_RESUME UINT64_C(2)
#define SOURCE_ROOT_CONTROL_DONE_SUSPEND UINT64_C(3)
#define SOURCE_ROOT_CONTROL_DONE_RESUME UINT64_C(4)

typedef struct SourceActor {
    uint64_t magic;
    uint64_t id;
    OresActorI64Heap* heap;
    OresNativeActorLogicalFramesV1* logical_frames;
    uint32_t frame_budget;
    uint32_t lifetime_frame_budget;
    uint32_t lifetime_frame_admissions;
    uint32_t restart_remaining;
    uint64_t fields_count;
    /* For a failed supervised incarnation, this metadata remains an invalid
     * tombstone until consuming recovery completes (or the root group closes).
     * Every backing heap/frame is
     * reclaimed before the root supervisor authorizes a fresh incarnation.
     * It preserves the consuming recovery decision and read-only diagnostics.
     * Public identity is a monotonic token, never this allocation address. */
    struct SourceActor* replacement;
    int restart_decided;
    int restart_settled;
    int terminal_policy_settled;
    int tombstone;
    /* Accepted guest DATA snapshot and waiters are guarded by group.mutex.
     * Physical callbacks signal this preinitialized condition after their
     * success/failure receipts become observable. No per-message allocation. */
    uint64_t admitted_sends;
    uint32_t flush_waiters;
    pthread_cond_t callback_completed;
    atomic_int logical_frame_failed;
    OresNativeActorRef ref;
    OresSourceActorInit constructor;
    OresSourceActorMessage on_message;
    atomic_int initialized;
    atomic_int startup_state;
    atomic_int constructor_started;
    atomic_int stop_requested;
    atomic_int join_started;
    atomic_uint_fast64_t processed;
    atomic_uint_fast64_t successful;
    atomic_uint_fast64_t failed;
    atomic_uint_fast64_t failure_code;
    atomic_int_fast64_t last_result;
} SourceActor;

typedef struct {
    pthread_cond_t completed;
    OresNativeActorFuture* ready_future;
    OresSourceActorContinuation continuation;
    SourceActor* actor;
    OresNativeSuspensionLink link;
    uint64_t source_token;
    int suspend_recorded;
    int done;
    int outcome;
    int64_t result;
} ReadyAwaitFrame;

typedef struct {
    pthread_cond_t completed;
    OresNativeActorFuture* done_future;
    OresSourceActorContinuation continuation;
    SourceActor* actor;
    OresNativeSuspensionLink link;
    uint64_t source_token;
    int suspend_recorded;
    int done;
    int64_t result;
} DoneAwaitFrame;

typedef struct {
    pthread_mutex_t mutex;
    OresNativeVM* vm;
    OresNativeActorRef root_ref;
    OresNativeExecutionContextStore* executions;
    OresNativeContinuationStore* continuations;
    ReadyAwaitFrame* pending_ready;
    DoneAwaitFrame* pending_done;
    SourceActor* actors[SOURCE_GROUP_SLOTS];
    SourceActor* retired[SOURCE_GROUP_SLOTS];
    /* A stopped leaf can outlive its source record in the VM while the
     * supervisor CONTROL turn finishes. The exact VM ref reserves the slot
     * until the VM acknowledges and accepts reclamation. */
    OresNativeActorRef pending_leaf_reclaim[SOURCE_GROUP_SLOTS];
    pthread_cond_t supervisor_progress;
    OresSourceRecoveryLeaseV1 recovery_lease;
    size_t next_slot;
    size_t live;
    size_t peak;
    atomic_uint_fast64_t terminal_notices;
    atomic_uint_fast64_t terminal_published;
    atomic_uint_fast64_t terminal_panic_notices;
    atomic_uint_fast64_t terminal_stopped_notices;
    atomic_uint_fast64_t last_panic_code;
} SourceActorGroup;

/* Process-local trusted-main group; the compiler only supports linear refs
 * in a zero-argument top-level main. There is exactly one worker pool per
 * active group, not one pool per actor.
 */
static SourceActorGroup group = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .supervisor_progress = PTHREAD_COND_INITIALIZER
};
/* Process-wide monotonic identity, allocated only under group.mutex. */
static uint64_t next_actor_id = 4100;
static _Thread_local OresNativeExecutionHandle current_source_execution;

/* Trusted compiler-generated, *synchronous* native actor-frame ABI.
 * The physical worker token is never sent to the guest. Carrier and actor
 * identity are checked by NativeActorLogicalFramesV1 on every operation.
 * This first scalar source profile does not suspend or migrate in a handler.
 */
static _Thread_local SourceActor* frame_actor;
static _Thread_local OresLogicalTurnV1 frame_turn;
static _Thread_local OresLogicalFrameV1 frame_handle;
static _Thread_local int frame_active;
static _Thread_local int frame_faulted;

static int reject_compiled_frame(void) {
    frame_faulted = 1;
    if (frame_actor) {
        atomic_store_explicit(&frame_actor->logical_frame_failed, 1, memory_order_release);
        atomic_store_explicit(&frame_actor->failure_code,
                              ORES_SOURCE_ACTOR_FAILURE_FRAME, memory_order_release);
    }
    return 0;
}

/* One compiled scalar receiver frame (64-byte preallocated slot) per turn.
 * These are not a guest FFI: they are emitted only by LLVM AOT actor handlers.
 * Ref32/ActorRef never carry the private frame store or physical turn token.
 */
int oreslang_source_actor_frame_push(void) {
    if (!frame_actor || frame_faulted || frame_active ||
        frame_actor->frame_budget == 0
        || (frame_actor->lifetime_frame_budget != 0
            && frame_actor->lifetime_frame_admissions
                >= frame_actor->lifetime_frame_budget))
        return reject_compiled_frame();
    if (!ores_logical_frames_v1_push(frame_actor->logical_frames,
                                    frame_turn, &frame_handle))
        return reject_compiled_frame();
    frame_actor->lifetime_frame_admissions++;
    frame_active = 1;
    return 1;
}
int oreslang_source_actor_frame_store_i64(int64_t value) {
    if (!frame_actor || !frame_active || frame_faulted ||
        !ores_logical_frames_v1_write(frame_actor->logical_frames,
                                     frame_handle, 0, &value, sizeof(value)))
        return reject_compiled_frame();
    return 1;
}
int oreslang_source_actor_frame_load_i64(int64_t* out) {
    if (!frame_actor || !frame_active || frame_faulted || !out ||
        !ores_logical_frames_v1_read(frame_actor->logical_frames,
                                    frame_handle, 0, out, sizeof(*out)))
        return reject_compiled_frame();
    return 1;
}
int oreslang_source_actor_frame_pop(void) {
    if (!frame_actor || !frame_active || frame_faulted ||
        !ores_logical_frames_v1_pop(frame_actor->logical_frames, frame_handle))
        return reject_compiled_frame();
    frame_active = 0;
    frame_handle = (OresLogicalFrameV1){0};
    return 1;
}

#ifdef ORESLANG_SOURCE_ACTOR_TESTING
static atomic_int test_flush_done_waiters;
int oreslang_source_actor_test_flush_done_waiters(void) {
    return atomic_load_explicit(&test_flush_done_waiters, memory_order_acquire);
}

static atomic_int test_hold_initialization;
static atomic_int test_initialization_entered;
static atomic_int test_hold_readiness_publication;
static atomic_int test_readiness_publication_entered;

void oreslang_source_actor_test_hold_initialization(int hold) {
    if (hold) {
        atomic_store_explicit(
            &test_initialization_entered, 0, memory_order_release);
        atomic_store_explicit(
            &test_hold_initialization, 1, memory_order_release);
    } else {
        atomic_store_explicit(
            &test_hold_initialization, 0, memory_order_release);
    }
}

int oreslang_source_actor_test_initialization_entered(void) {
    return atomic_load_explicit(
        &test_initialization_entered, memory_order_acquire);
}

void oreslang_source_actor_test_hold_readiness_publication(int hold) {
    if (hold) {
        atomic_store_explicit(
            &test_readiness_publication_entered, 0, memory_order_release);
        atomic_store_explicit(
            &test_hold_readiness_publication, 1, memory_order_release);
    } else {
        atomic_store_explicit(
            &test_hold_readiness_publication, 0, memory_order_release);
    }
}

int oreslang_source_actor_test_readiness_publication_entered(void) {
    return atomic_load_explicit(
        &test_readiness_publication_entered, memory_order_acquire);
}
uint64_t oreslang_source_actor_test_terminal_notices(void) {
    return atomic_load_explicit(&group.terminal_notices, memory_order_acquire);
}
uint64_t oreslang_source_actor_test_panic_notices(void) {
    return atomic_load_explicit(&group.terminal_panic_notices, memory_order_acquire);
}
uint64_t oreslang_source_actor_test_stopped_notices(void) {
    return atomic_load_explicit(&group.terminal_stopped_notices, memory_order_acquire);
}
uint64_t oreslang_source_actor_test_last_panic_code(void) {
    return atomic_load_explicit(&group.last_panic_code, memory_order_acquire);
}
#endif

static void ensure(int condition) { if (!condition) abort(); }
static void group_lock(void) { ensure(pthread_mutex_lock(&group.mutex) == 0); }
static void group_unlock(void) { ensure(pthread_mutex_unlock(&group.mutex) == 0); }

static int same_allocation(
        OresNativeAllocationDomainRef a,
        OresNativeAllocationDomainRef b) {
    return a.kind == b.kind
        && a.domain == b.domain
        && a.owner == b.owner
        && a.generation == b.generation;
}

/* group.mutex held. Only the native VM may authorize scheduler reuse.
 * No blocking or polling on a supervisor carrier. */
static void source_reclaim_settled_leaves_locked(void) {
    OresNativeExecutionContext current = {0};
    if (ores_native_vm_current_execution_context(
            group.vm, ORES_NATIVE_VM_MAIN, &current)) return;
    for (size_t i = 0; i < group.next_slot; ++i) {
        OresNativeActorRef old = group.pending_leaf_reclaim[i];
        if (!old.scheduler_domain) continue;
        ensure(!group.actors[i] && !group.retired[i] && old.actor_slot == i);
        if (ores_native_vm_reclaim_supervised_leaf_v1(
                group.vm, ORES_NATIVE_VM_MAIN, old))
            group.pending_leaf_reclaim[i] = (OresNativeActorRef){0};
    }
}

static size_t source_free_slot_locked(void) {
    for (size_t i = 0; i < SOURCE_GROUP_ROOT_SLOT; ++i)
        if (!group.actors[i] && !group.retired[i]
                && !group.pending_leaf_reclaim[i].scheduler_domain)
            return i;
    return SOURCE_GROUP_ROOT_SLOT;
}

static SourceActor* lookup(size_t index) {
    ensure(index < SOURCE_GROUP_ROOT_SLOT);
    SourceActor* actor = group.actors[index];
    ensure(actor && actor->magic == SOURCE_ACTOR_MAGIC);
    return actor;
}

/* The native ABI retains its opaque void* shape, but these values are
 * integer identities and are never dereferenced. Only trusted compiler
 * code may transport them. This is a lifetime fence, not FFI authorization. */
static void* source_handle(const SourceActor* instance) {
    return (void*)(uintptr_t)instance->id;
}

/* Caller holds group.mutex. Tombstones are available only to observations;
 * DATA, STOP and recovery resolve live records exclusively. */
static SourceActor* source_resolve_locked(void* opaque, int observe_retired) {
    const uintptr_t token = (uintptr_t)opaque;
    if (!token || !group.vm) return NULL;
    for (size_t i = 0; i < group.next_slot; ++i) {
        SourceActor* a = group.actors[i];
        if (a && a->id == (uint64_t)token) return a;
        a = observe_retired ? group.retired[i] : NULL;
        if (a && a->id == (uint64_t)token) return a;
    }
    return NULL;
}

int oreslang_source_actor_ready_status(void* opaque) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 1);
    const int result = instance ? atomic_load_explicit(
        &instance->startup_state, memory_order_acquire) : -1;
    group_unlock();
    return result;
}

int oreslang_source_actor_message_receipt(
        void* opaque, OresSourceActorMessageReceipt* out) {
    if (!out) return 0;
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 1);
    if (!instance) { group_unlock(); return 0; }
    *out = (OresSourceActorMessageReceipt){
        .processed_callbacks = atomic_load_explicit(&instance->processed, memory_order_acquire),
        .successful_messages = atomic_load_explicit(&instance->successful, memory_order_acquire),
        .failed_messages = atomic_load_explicit(&instance->failed, memory_order_acquire),
        .failure_code = atomic_load_explicit(&instance->failure_code, memory_order_acquire),
        .last_success_result = atomic_load_explicit(&instance->last_result, memory_order_acquire),
        .stop_requested = atomic_load_explicit(&instance->stop_requested, memory_order_acquire)
    };
    group_unlock();
    return 1;
}

typedef struct {
    OresNativeActorFuture* future;
    int status;
} SourceReadyFutureCommit;

static int commit_source_ready_future(void* raw) {
    SourceReadyFutureCommit* commit = (SourceReadyFutureCommit*)raw;
    return commit
        && commit->future
        && ores_native_actor_future_complete(commit->future, commit->status);
}

static void settle_ready_if_admitted_locked(ReadyAwaitFrame* frame) {
    ensure(frame && frame == group.pending_ready);
    if (!frame->suspend_recorded) return;

    const int startup_state = atomic_load_explicit(
        &frame->actor->startup_state, memory_order_acquire);
    int future_status = ORES_NATIVE_FUTURE_PENDING;
    if (startup_state == ORES_SOURCE_ACTOR_STARTUP_READY)
        future_status = ORES_NATIVE_FUTURE_READY;
    else if (startup_state
            == ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY)
        future_status = ORES_NATIVE_FUTURE_FAILED;
    else
        return;

    ensure(frame->ready_future != NULL);
    if (ores_native_actor_future_status(frame->ready_future)
            == ORES_NATIVE_FUTURE_PENDING) {
        /*
         * Serialize the fallible Future outcome commit and continuation READY
         * publication against cancellation under the continuation-store lock.
         * Both successful readiness and TERMINATED_BEFORE_READY must wake the
         * root CONTROL lane exactly once; only the Future outcome differs.
         */
        SourceReadyFutureCommit commit = {
            .future = frame->ready_future,
            .status = future_status
        };
        ensure(ores_native_continuation_store_commit_ready(
            group.continuations,
            frame->link.continuation,
            commit_source_ready_future,
            &commit));
    }
}

static void root_suspend_turn(void) {
    group_lock();
    ReadyAwaitFrame* frame = group.pending_ready;
    ensure(frame && !frame->done && !frame->suspend_recorded);

    OresNativeExecutionContext physical = {0};
    ensure(ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &physical));
    ensure(physical.allocation.kind == ORES_ALLOCATION_DOMAIN_ACTOR);
    ensure(physical.allocation.owner == SOURCE_GROUP_ROOT_SLOT);

    OresNativeExecutionHandle origin = {0, 0};
    ensure(ores_native_execution_context_store_insert(
        group.executions, &physical, &origin));
    OresNativeContinuationHandle no_parent = {0, 0};
    ensure(ores_native_await_trace_suspend(
        group.executions,
        group.continuations,
        origin,
        no_parent,
        ORES_CONTINUATION_BOUNDARY_AWAIT,
        1,
        frame->source_token,
        &frame->link));

    /*
     * The published continuation owns the durable pin. Drop this callback's
     * insertion reference before its native worker stack is torn down.
     */
    ensure(ores_native_execution_context_store_release(
        group.executions, origin));
    frame->suspend_recorded = 1;
    settle_ready_if_admitted_locked(frame);
    group_unlock();
}

static void root_resume_turn(void) {
    ReadyAwaitFrame* frame;
    OresSourceActorContinuation continuation;
    SourceActor* actor;
    OresNativeSuspensionLink link;
    int future_status;

    group_lock();
    frame = group.pending_ready;
    ensure(frame && frame->suspend_recorded && !frame->done);
    ensure(frame->ready_future != NULL);
    future_status = ores_native_actor_future_status(frame->ready_future);
    ensure(future_status == ORES_NATIVE_FUTURE_READY
        || future_status == ORES_NATIVE_FUTURE_FAILED);
    continuation = frame->continuation;
    actor = frame->actor;
    link = frame->link;
    group_unlock();

    OresNativeExecutionContext physical = {0};
    ensure(ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &physical));
    ensure(physical.allocation.kind == ORES_ALLOCATION_DOMAIN_ACTOR);
    ensure(physical.allocation.owner == SOURCE_GROUP_ROOT_SLOT);

    OresNativeExecutionHandle resumed = {0, 0};
    OresNativeExecutionContext logical = {0};
    ensure(ores_native_await_trace_ready_resume_begin(
        group.executions,
        group.continuations,
        physical.id,
        link,
        &resumed,
        &logical));

    /*
     * Even failed readiness resumes once on the root CONTROL lane so the
     * persisted logical suspension can be retired without leaking trace state.
     * Guest continuation code runs only for successful readiness.
     */
    ensure(same_allocation(logical.allocation, physical.allocation));
    int64_t result = 0;
    int outcome = ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY;
    if (future_status == ORES_NATIVE_FUTURE_READY) {
        ensure(!ores_native_store_handle_valid(current_source_execution));
        current_source_execution = resumed;
        result = continuation(source_handle(actor));
        current_source_execution = (OresNativeExecutionHandle){0, 0};
        outcome = ORES_SOURCE_ACTOR_STARTUP_READY;
    }

    ensure(ores_native_await_trace_resume_finish(group.continuations, link));
    ensure(ores_native_execution_context_store_release(
        group.executions, resumed));

    group_lock();
    ensure(frame == group.pending_ready && !frame->done);
    frame->outcome = outcome;
    frame->result = result;
    frame->done = 1;
    ensure(pthread_cond_signal(&frame->completed) == 0);
    group_unlock();
}

static void root_done_suspend_turn(void) {
    group_lock();
    DoneAwaitFrame* frame = group.pending_done;
    ensure(frame && !frame->done && !frame->suspend_recorded);

    OresNativeExecutionContext physical = {0};
    ensure(ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &physical));
    ensure(physical.allocation.kind == ORES_ALLOCATION_DOMAIN_ACTOR);
    ensure(physical.allocation.owner == SOURCE_GROUP_ROOT_SLOT);

    OresNativeExecutionHandle origin = {0, 0};
    ensure(ores_native_execution_context_store_insert(
        group.executions, &physical, &origin));
    OresNativeContinuationHandle no_parent = {0, 0};
    ensure(ores_native_await_trace_suspend(
        group.executions,
        group.continuations,
        origin,
        no_parent,
        ORES_CONTINUATION_BOUNDARY_AWAIT,
        1,
        frame->source_token,
        &frame->link));
    ensure(ores_native_execution_context_store_release(
        group.executions, origin));

    frame->suspend_recorded = 1;
    ensure(pthread_cond_signal(&frame->completed) == 0);
    group_unlock();
}

static void root_done_resume_turn(void) {
    DoneAwaitFrame* frame;
    OresSourceActorContinuation continuation;
    SourceActor* actor;
    OresNativeSuspensionLink link;

    group_lock();
    frame = group.pending_done;
    ensure(frame && frame->suspend_recorded && !frame->done);
    ensure(frame->done_future != NULL);
    ensure(ores_native_actor_future_status(frame->done_future)
        == ORES_NATIVE_FUTURE_READY);
    continuation = frame->continuation;
    actor = frame->actor;
    link = frame->link;
    group_unlock();

    OresNativeExecutionContext physical = {0};
    ensure(ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &physical));
    ensure(physical.allocation.kind == ORES_ALLOCATION_DOMAIN_ACTOR);
    ensure(physical.allocation.owner == SOURCE_GROUP_ROOT_SLOT);

    OresNativeExecutionHandle resumed = {0, 0};
    OresNativeExecutionContext logical = {0};
    ensure(ores_native_await_trace_resume_begin(
        group.executions,
        group.continuations,
        physical.id,
        link,
        &resumed,
        &logical));
    ensure(same_allocation(logical.allocation, physical.allocation));
    ensure(!ores_native_store_handle_valid(current_source_execution));
    current_source_execution = resumed;
    const int64_t result = continuation(source_handle(actor));
    current_source_execution = (OresNativeExecutionHandle){0, 0};

    ensure(ores_native_await_trace_resume_finish(group.continuations, link));
    ensure(ores_native_execution_context_store_release(
        group.executions, resumed));

    group_lock();
    ensure(frame == group.pending_done && !frame->done);
    frame->result = result;
    frame->done = 1;
    ensure(pthread_cond_signal(&frame->completed) == 0);
    group_unlock();
}

static void initialize(void* raw, size_t slot, uint64_t payload) {
    ensure(raw == &group);
    if (slot == SOURCE_GROUP_ROOT_SLOT) {
        if (payload == SOURCE_ROOT_CONTROL_SUSPEND) root_suspend_turn();
        else if (payload == SOURCE_ROOT_CONTROL_RESUME) root_resume_turn();
        else if (payload == SOURCE_ROOT_CONTROL_DONE_SUSPEND) root_done_suspend_turn();
        else if (payload == SOURCE_ROOT_CONTROL_DONE_RESUME) root_done_resume_turn();
        else abort();
        return;
    }

    ensure(payload == 0);
    SourceActor* instance = lookup(slot);

#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    if (atomic_load_explicit(
            &test_hold_initialization, memory_order_acquire)) {
        atomic_store_explicit(
            &test_initialization_entered, 1, memory_order_release);
        while (atomic_load_explicit(
                &test_hold_initialization, memory_order_acquire))
            sched_yield();
    }
#endif

    /*
     * Match Java ActorRef.stop(): startup admission and stop admission
     * linearize under the same group mutex. The reserved initialization
     * CONTROL turn survives mailbox sealing, but stop-before-start skips guest
     * construction completely.
     */
    group_lock();
    if (atomic_load_explicit(
            &instance->stop_requested, memory_order_acquire)) {
        atomic_store_explicit(
            &instance->startup_state,
            ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY,
            memory_order_release);
        ReadyAwaitFrame* frame = group.pending_ready;
        if (frame && frame->actor == instance)
            settle_ready_if_admitted_locked(frame);
        group_unlock();
        return;
    }

    int expected = ORES_SOURCE_ACTOR_STARTUP_PENDING;
    ensure(atomic_compare_exchange_strong_explicit(
        &instance->startup_state,
        &expected,
        ORES_SOURCE_ACTOR_STARTUP_INITIALIZING,
        memory_order_acq_rel,
        memory_order_acquire));
    atomic_store_explicit(
        &instance->constructor_started, 1, memory_order_release);
    group_unlock();

    OresActorTurn turn =
        ores_actor_i64_heap_enter(instance->heap, instance->id, 1);
    instance->constructor(instance->heap, turn.domain, turn.epoch);
    ores_actor_i64_heap_leave(instance->heap, turn);

#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    if (atomic_load_explicit(
            &test_hold_readiness_publication, memory_order_acquire)) {
        atomic_store_explicit(
            &test_readiness_publication_entered, 1, memory_order_release);
        while (atomic_load_explicit(
                &test_hold_readiness_publication, memory_order_acquire))
            sched_yield();
    }
#endif

    /*
     * Stop may race an already-running constructor. Re-enter the same group
     * mutex before publishing readiness: if stop acquired it first, readiness
     * fails; if this turn publishes READY first, a later stop cannot retroactively
     * change the already-settled readiness observer.
     */
    group_lock();
    if (atomic_load_explicit(
            &instance->stop_requested, memory_order_acquire)) {
        atomic_store_explicit(
            &instance->startup_state,
            ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY,
            memory_order_release);
    } else {
        atomic_store_explicit(
            &instance->initialized, 1, memory_order_release);
        atomic_store_explicit(
            &instance->startup_state,
            ORES_SOURCE_ACTOR_STARTUP_READY,
            memory_order_release);
    }

    ReadyAwaitFrame* frame = group.pending_ready;
    if (frame && frame->actor == instance)
        settle_ready_if_admitted_locked(frame);
    group_unlock();
}

static void deliver(void* raw, size_t slot, uint64_t payload) {
    ensure(raw == &group && slot != SOURCE_GROUP_ROOT_SLOT);
    SourceActor* instance = lookup(slot);
    ensure(instance->on_message != NULL);
    ensure(atomic_load_explicit(
        &instance->initialized, memory_order_acquire) == 1);
    OresActorTurn turn =
        ores_actor_i64_heap_enter(instance->heap, instance->id, 1);
    OresLogicalTurnV1 logical = {0};
    int64_t result = 0;
    int succeeded = 0;

    /* This scalar ABI serializes source receiver turns on the scheduler lane.
     * A successful native callback can return zero; zero is never our
     * failure sentinel. Admission and frame drop status decide success. */
    if (!atomic_load_explicit(&instance->logical_frame_failed, memory_order_acquire)
        && ores_logical_frames_v1_begin(instance->logical_frames, instance->id, 1, &logical)) {
        frame_actor = instance;
        frame_turn = logical;
        frame_handle = (OresLogicalFrameV1){0};
        frame_active = 0;
        frame_faulted = 0;

        result = instance->on_message(
            instance->heap, turn.domain, turn.epoch, (int64_t)payload);

        if (!frame_faulted && !frame_active
            && ores_logical_frames_v1_end(instance->logical_frames, logical)) {
            succeeded = 1;
        } else {
            /* Cancel owns scrubbing even when the generated receiver forgot
             * to pop or a frame read/write failed. No output from this
             * callback is treated as a successful guest result. */
            atomic_store_explicit(&instance->logical_frame_failed, 1, memory_order_release);
            atomic_store_explicit(&instance->failure_code,
                                  ORES_SOURCE_ACTOR_FAILURE_FRAME, memory_order_release);
            (void)ores_logical_frames_v1_cancel(instance->logical_frames, logical);
        }
        frame_actor = NULL;
        frame_turn = (OresLogicalTurnV1){0};
        frame_handle = (OresLogicalFrameV1){0};
        frame_active = 0;
        frame_faulted = 0;
    } else {
        atomic_store_explicit(&instance->logical_frame_failed, 1, memory_order_release);
        atomic_store_explicit(&instance->failure_code,
                              ORES_SOURCE_ACTOR_FAILURE_FRAME, memory_order_release);
    }
    ores_actor_i64_heap_leave(instance->heap, turn);

    if (succeeded) {
        atomic_store_explicit(&instance->last_result, result, memory_order_relaxed);
        atomic_fetch_add_explicit(&instance->successful, 1, memory_order_release);
    } else {
        atomic_fetch_add_explicit(&instance->failed, 1, memory_order_release);
    }
    if (!succeeded) {
        /* Seal future DATA immediately on a frame failure while allowing this
         * callback to finish its physical carrier turn. Stop is idempotent
         * against a trusted outer-host request racing this callback. */
        group_lock();
        int expected = 0;
        if (atomic_compare_exchange_strong_explicit(
                &instance->stop_requested, &expected, 1,
                memory_order_acq_rel, memory_order_acquire)) {
            ensure(ores_native_vm_stop_sealed(
                group.vm, ORES_NATIVE_VM_MAIN, instance->ref));
        }
        group_unlock();
    }
    /* A DATA callback is only completed after failure admission is sealed.
     * Publishing 'processed' before STOP sealing let flush_blocking return
     * with a failed-count receipt but a still-live mailbox. The ordered
     * release also covers last_success_result/success/failed/failure_code. */
    atomic_fetch_add_explicit(&instance->processed, 1, memory_order_release);
    /* An explicit trusted-main flush_blocking barrier observes physical
     * callback completion, regardless of whether the guest succeeded. */
    group_lock();
    ensure(pthread_cond_broadcast(&instance->callback_completed) == 0);
    group_unlock();
}

/* Typed priority CONTROL belongs to the root supervisor's actor lane.
 * Do not look up freed child pointers: only immutable scheduler identities
 * and the typed, runtime-issued terminal envelope cross this boundary.
 * Policy-driven automatic restart is a separate compiler/runtime milestone.
 */
/* A root supervisor CONTROL turn authorizes a bounded one-for-one restart.
 * It never publishes a synthetic guest success and never restarts a STOPPED
 * child. The replacement is a fresh physical slot, actor ID, private heap,
 * logical-frame store and mailbox. Selection and admission share group.mutex;
 * initialization is queued asynchronously, never executed by this callback.
 *
 * No root callback blocks waiting for a carrier or for guest execution.
 * The old actor must already have physical DONE and host finalization before
 * NativeOresVM can publish this authenticated terminal envelope. */
static void* source_spawn_locked(OresSourceActorInit constructor,
        OresSourceActorMessage on_message, uint64_t fields,
        uint64_t max_frames, uint64_t lifetime_frame_budget,
        uint64_t restart_limit, size_t slot);

/* This restricted linear ActorRef source profile only has one consuming
 * recovery bridge. A plain STOP/join must not create an unreachable child.
 * The registered root still decides policy after physical finalization. */
static int source_recovery_owns_locked(const SourceActor* actor) {
    return ores_source_recovery_matches(&group.recovery_lease,
        (size_t)actor->ref.actor_slot, actor->ref.generation, actor->id);
}

static void source_supervisor_notice(
        void* raw, size_t parent_slot, OresNativeActorTerminalFailure failure) {
    ensure(raw == &group && parent_slot == SOURCE_GROUP_ROOT_SLOT);
    ensure(failure.child.scheduler_domain == group.root_ref.scheduler_domain
        && failure.child.actor_slot < SOURCE_GROUP_ROOT_SLOT
        && failure.child.generation != 0 && failure.correlation != 0);

    SourceActor* original = NULL;
    size_t successor_slot = SOURCE_GROUP_ROOT_SLOT;
    group_lock();
    const size_t slot = (size_t)failure.child.actor_slot;
    original = group.actors[slot] ? group.actors[slot] : group.retired[slot];
    ensure(original && original->magic == SOURCE_ACTOR_MAGIC
        && original->ref.scheduler_domain == failure.child.scheduler_domain
        && original->ref.actor_slot == failure.child.actor_slot
        && original->ref.generation == failure.child.generation
        && original->id == failure.correlation
        && !original->terminal_policy_settled);
    if (failure.kind == ORES_NATIVE_CONTROL_PANIC
        && failure.failure_code == ORES_SOURCE_ACTOR_FAILURE_FRAME
        && original->restart_remaining != 0
        && source_recovery_owns_locked(original)
        && !original->restart_decided) {
        original->restart_decided = 1;
        successor_slot = source_free_slot_locked();
    }
    if (original->restart_decided && successor_slot == SOURCE_GROUP_ROOT_SLOT)
        original->restart_settled = 1;
    if (successor_slot < SOURCE_GROUP_ROOT_SLOT) {
        const uint64_t remaining = original->restart_remaining - 1;
        void* fresh_handle = source_spawn_locked(
                original->constructor, original->on_message, original->fields_count,
                original->frame_budget, original->lifetime_frame_budget,
                remaining, successor_slot);
        /* Do not migrate state or preserve an old borrow or pointer.
         * Keep the selection and registration in the same critical section. */
        SourceActor* fresh = source_resolve_locked(fresh_handle, 0);
        ensure(fresh != NULL);
        ensure(original && original->magic == SOURCE_ACTOR_MAGIC
            && original->ref.actor_slot == failure.child.actor_slot
            && original->replacement == NULL);
        original->replacement = fresh;
        original->restart_settled = 1;
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
        fprintf(stderr,
            "SOURCE_ACTOR_ONE_FOR_ONE_RESTART_VERIFIED old_slot=%llu new_slot=%llu old_generation=%llu new_generation=%llu remaining=%llu\n",
            (unsigned long long)failure.child.actor_slot,
            (unsigned long long)fresh->ref.actor_slot,
            (unsigned long long)failure.child.generation,
            (unsigned long long)fresh->ref.generation,
            (unsigned long long)remaining);
#endif
    }
    if (failure.kind == ORES_NATIVE_CONTROL_PANIC) {
        ensure(failure.failure_code != 0);
        atomic_store_explicit(&group.last_panic_code,
                              failure.failure_code, memory_order_release);
        atomic_fetch_add_explicit(
            &group.terminal_panic_notices, 1, memory_order_release);
    } else {
        ensure(failure.kind == ORES_NATIVE_CONTROL_STOPPED
            && failure.failure_code == 0);
        atomic_fetch_add_explicit(
            &group.terminal_stopped_notices, 1, memory_order_release);
    }
    original->terminal_policy_settled = 1;
    atomic_fetch_add_explicit(&group.terminal_notices, 1, memory_order_release);
    ensure(pthread_cond_broadcast(&group.supervisor_progress) == 0);
    group_unlock();
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    fprintf(stderr,
        "SOURCE_ACTOR_TERMINAL_CONTROL_VERIFIED kind=%s slot=%llu generation=%llu code=%llu correlation=%llu\n",
        failure.kind == ORES_NATIVE_CONTROL_PANIC ? "PANIC" : "STOPPED",
        (unsigned long long)failure.child.actor_slot,
        (unsigned long long)failure.child.generation,
        (unsigned long long)failure.failure_code,
        (unsigned long long)failure.correlation);
#endif
}

static void open_group_locked(void) {
    ensure(!group.vm && !group.executions && !group.continuations);
    ensure(group.live == 0 && group.next_slot == 0 && group.peak == 0);
    ensure(!group.recovery_lease.held);
    ensure(group.pending_ready == NULL && group.pending_done == NULL);
    atomic_store_explicit(&group.terminal_notices, 0, memory_order_release);
    atomic_store_explicit(&group.terminal_published, 0, memory_order_release);
    atomic_store_explicit(&group.terminal_panic_notices, 0, memory_order_release);
    atomic_store_explicit(&group.terminal_stopped_notices, 0, memory_order_release);
    atomic_store_explicit(&group.last_panic_code, 0, memory_order_release);

    /*
     * Live trace stores are process-local runtime metadata. They hold logical
     * values/handles only and outlive individual worker stacks.
     */
    group.executions =
        ores_native_execution_context_store_open(SOURCE_TRACE_EXECUTIONS);
    ensure(group.executions != NULL);
    group.continuations = ores_native_continuation_store_open(
        SOURCE_TRACE_CONTINUATIONS, group.executions);
    ensure(group.continuations != NULL);

    OresNativeVMConfig cfg = {0};
    for (int i = 0; i < ORES_NATIVE_VM_DOMAIN_COUNT; ++i) {
        cfg.domains[i].actor_slots =
            i == ORES_NATIVE_VM_MAIN ? SOURCE_GROUP_SLOTS : 1;
        cfg.domains[i].mailbox_capacity =
            i == ORES_NATIVE_VM_MAIN ? SOURCE_GROUP_MAILBOX : 1;
        cfg.domains[i].carrier_workers =
            i == ORES_NATIVE_VM_MAIN ? SOURCE_GROUP_CARRIERS : 1;
        cfg.domains[i].on_message = deliver;
        cfg.domains[i].on_control = initialize;
        cfg.domains[i].on_failure = source_supervisor_notice;
        cfg.domains[i].context = &group;
    }
    group.vm = ores_native_vm_open(&cfg);
    ensure(group.vm != NULL);
    /* Match the Java VM CONTROL bootstrap barrier: one native master loop is
     * running before the root actor can admit work. It only signals scheduler
     * conditions; constructor/guest execution stays on bounded carriers. */
    ensure(ores_native_vm_start_main_control_loop(group.vm));

    /*
     * The compiler-generated top-level continuation owns one reserved logical
     * actor lane. It is metadata-only until an await schedules CONTROL.
     */
    group.root_ref = ores_native_vm_spawn(
        group.vm, ORES_NATIVE_VM_MAIN, SOURCE_GROUP_ROOT_SLOT);
    ensure(group.root_ref.scheduler_domain
        && group.root_ref.actor_slot == SOURCE_GROUP_ROOT_SLOT);

#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    for (int i = 0; i < ORES_NATIVE_VM_DOMAIN_COUNT; ++i)
        ensure(ores_native_vm_started_carriers(
            group.vm, (enum OresNativeVMDomain)i) == 0);
    fprintf(stderr,
        "SOURCE_ACTOR_VM_ROUTING_VERIFIED domains=4 main=1 cold_carriers=0 root_slot=%u\n",
        SOURCE_GROUP_ROOT_SLOT);
#endif
}

/* Caller holds group.mutex. No guest constructor runs inline. */
static void* source_spawn_locked(
        OresSourceActorInit constructor,
        OresSourceActorMessage on_message,
        uint64_t fields,
        uint64_t max_frames,
        uint64_t lifetime_frame_budget,
        uint64_t restart_limit, size_t slot) {
    ensure(constructor != NULL && fields >= 1 && fields <= 4096
        && max_frames <= 16 && lifetime_frame_budget <= 64
        && restart_limit <= 4);
    ensure(group.vm && slot < SOURCE_GROUP_ROOT_SLOT
        && !group.actors[slot] && !group.retired[slot]
        && !group.pending_leaf_reclaim[slot].scheduler_domain);
    if (group.next_slot <= slot) group.next_slot = slot + 1;
    SourceActor* instance = (SourceActor*)calloc(1, sizeof(*instance));
    ensure(instance != NULL);
    instance->magic = SOURCE_ACTOR_MAGIC;
    /* Deny before increment, including narrow native pointer ABIs. Never wrap
     * or recycle the process-wide identity at group close. */
    ensure(next_actor_id != 0 && next_actor_id < UINTPTR_MAX && next_actor_id < UINT64_MAX);
    instance->id = next_actor_id++;
    instance->constructor = constructor;
    instance->on_message = on_message;
    instance->frame_budget = (uint32_t)max_frames;
    instance->lifetime_frame_budget = (uint32_t)lifetime_frame_budget;
    instance->lifetime_frame_admissions = 0;
    instance->restart_remaining = (uint32_t)restart_limit;
    instance->fields_count = fields;
    instance->replacement = NULL;
    instance->restart_decided = 0;
    instance->restart_settled = 0;
    instance->terminal_policy_settled = 0;
    instance->tombstone = 0;
    instance->admitted_sends = 0;
    instance->flush_waiters = 0;
    ensure(pthread_cond_init(&instance->callback_completed, NULL) == 0);

    /*
     * SourceActor is dynamically allocated. Initialize every C11 atomic
     * explicitly instead of relying on calloc byte patterns to stand in for
     * atomic object initialization.
     */
    atomic_init(&instance->initialized, 0);
    atomic_init(
        &instance->startup_state, ORES_SOURCE_ACTOR_STARTUP_PENDING);
    atomic_init(&instance->constructor_started, 0);
    atomic_init(&instance->stop_requested, 0);
    atomic_init(&instance->join_started, 0);
    atomic_init(&instance->processed, 0);
    atomic_init(&instance->successful, 0);
    atomic_init(&instance->failed, 0);
    atomic_init(&instance->failure_code, 0);
    atomic_init(&instance->last_result, 0);
    atomic_init(&instance->logical_frame_failed, 0);

    instance->heap = ores_actor_i64_heap_open(instance->id, 1, fields);
    /* Current native i64 heap is serial. One preallocated turn lane, with
     * reserved depth for future compiler-generated nested synchronous calls. */
    instance->logical_frames = ores_logical_frames_v1_open(
        instance->id, 1, 1, max_frames == 0 ? 1 : max_frames, 64);
    ensure(instance->logical_frames != NULL);
    group.actors[slot] = instance;
    /* Every compiler-generated source actor has a registered root supervisor.
     * This is host lifecycle metadata; it grants no guest supervisor pointer. */
    instance->ref = ores_native_vm_spawn_supervised(
        group.vm, ORES_NATIVE_VM_MAIN, slot, group.root_ref);
    ensure(instance->ref.scheduler_domain
        && instance->ref.actor_slot == slot);
    group.live++;
    if (group.live > group.peak) group.peak = group.live;

    /* CONTROL initializes the compiler-emitted actor constructor on a worker. */
    ensure(ores_native_vm_publish_control(
        group.vm, ORES_NATIVE_VM_MAIN, instance->ref, 0));
    return source_handle(instance);
}

void* oreslang_source_actor_spawn_supervised_budgeted(
        OresSourceActorInit constructor,
        OresSourceActorMessage on_message,
        uint64_t fields,
        uint64_t max_frames,
        uint64_t lifetime_frame_budget,
        uint64_t restart_limit) {
    group_lock();
    if (!group.vm) open_group_locked();
    source_reclaim_settled_leaves_locked();
    void* handle = source_spawn_locked(constructor, on_message, fields,
        max_frames, lifetime_frame_budget, restart_limit, source_free_slot_locked());
    group_unlock();
    return handle;
}

int oreslang_source_actor_try_send(void* opaque, int64_t payload) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    int admitted = 0;
    if (instance
        && instance->on_message
        && !atomic_load_explicit(&instance->logical_frame_failed, memory_order_acquire)
        && !atomic_load_explicit(&instance->stop_requested, memory_order_acquire)) {
        admitted = ores_native_vm_try_send(
            group.vm, ORES_NATIVE_VM_MAIN, instance->ref, (uint64_t)payload)
            == ORES_NATIVE_SEND_ACCEPTED;
        if (admitted) instance->admitted_sends++;
    }
    group_unlock();
    return admitted;
}

/* This API only blocks trusted outer-main execution. The captured count
 * linearizes with try_send under group.mutex, so a concurrent send cannot
 * sneak into an earlier flush snapshot. The carrier signals after recording
 * either a successful or failed physical callback. No DATA is sealed here. */
int oreslang_source_actor_flush_blocking(void* opaque) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    if (!instance || atomic_load_explicit(&instance->join_started, memory_order_acquire)
        || !instance->on_message) {
        group_unlock();
        return 0;
    }
    OresNativeExecutionContext current = {0};
    ensure(!ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &current));
    const uint64_t target = instance->admitted_sends;
    instance->flush_waiters++;
    while (atomic_load_explicit(&instance->processed, memory_order_acquire) < target
           && !atomic_load_explicit(&instance->stop_requested, memory_order_acquire))
        ensure(pthread_cond_wait(&instance->callback_completed, &group.mutex) == 0);
    if (atomic_load_explicit(&instance->processed, memory_order_acquire) < target) {
        /* STOP seals admission, not the physical callback. A failed or
         * externally stopped active callback can still publish its receipt.
         * The existing flush waiter pins the record, VM and exact lane until
         * we return; join waits for all flush waiters before detaching it.
         * Drop group.mutex so the carrier can publish and reach DONE. */
        ensure(atomic_load_explicit(&instance->stop_requested, memory_order_acquire));
        OresNativeVM* vm = group.vm;
        const OresNativeActorRef ref = instance->ref;
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
        atomic_fetch_add_explicit(&test_flush_done_waiters, 1, memory_order_release);
#endif
        group_unlock();
        ensure(ores_native_vm_wait_done(vm, ORES_NATIVE_VM_MAIN, ref));
        group_lock();
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
        atomic_fetch_sub_explicit(&test_flush_done_waiters, 1, memory_order_release);
#endif
        ensure(source_resolve_locked(opaque, 0) == instance);
    }
    const int completed = atomic_load_explicit(
        &instance->processed, memory_order_acquire) >= target;
    ensure(instance->flush_waiters > 0);
    instance->flush_waiters--;
    ensure(pthread_cond_broadcast(&instance->callback_completed) == 0);
    group_unlock();
    return completed;
}
void* oreslang_source_actor_spawn_budgeted(
        OresSourceActorInit constructor,
        OresSourceActorMessage on_message,
        uint64_t fields,
        uint64_t max_frames) {
    return oreslang_source_actor_spawn_supervised_budgeted(
        constructor, on_message, fields, max_frames, 0, 0);
}
void* oreslang_source_actor_spawn(
        OresSourceActorInit constructor,
        OresSourceActorMessage on_message,
        uint64_t fields) {
    return oreslang_source_actor_spawn_budgeted(
        constructor, on_message, fields, 16);
}

void oreslang_source_actor_send(void* opaque, int64_t payload) {
    ensure(oreslang_source_actor_try_send(opaque, payload));
}

int oreslang_source_actor_await_ready_checked(
        void* opaque,
        OresSourceActorContinuation continuation,
        uint64_t source_token,
        int64_t* result_out) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    if (!instance || atomic_load_explicit(&instance->join_started, memory_order_acquire)) {
        group_unlock();
        return -1;
    }
    ensure(continuation != NULL && result_out != NULL);

    /*
     * This outer wait is trusted top-level ABI glue, never guest-carrier
     * blocking. A carrier call would be a deadlock/semantic violation.
     */
    OresNativeExecutionContext carrier = {0};
    ensure(!ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &carrier));

    ReadyAwaitFrame frame = {
        .continuation = continuation,
        .actor = instance,
        .source_token = source_token
    };
    ensure(pthread_cond_init(&frame.completed, NULL) == 0);

    ensure(group.vm && group.root_ref.scheduler_domain
        && group.pending_ready == NULL && group.pending_done == NULL);
    frame.ready_future = ores_native_vm_actor_future_open(
        group.vm,
        ORES_NATIVE_VM_MAIN,
        group.root_ref,
        SOURCE_ROOT_CONTROL_RESUME);
    ensure(frame.ready_future != NULL);
    group.pending_ready = &frame;

    /*
     * First root turn captures/suspends its scheduler-minted ExecutionId.
     * Future settlement cannot resume until this CONTROL turn has published
     * the OresNativeSuspensionLink.
     */
    ensure(ores_native_vm_publish_control(
        group.vm,
        ORES_NATIVE_VM_MAIN,
        group.root_ref,
        SOURCE_ROOT_CONTROL_SUSPEND));

    while (!frame.done)
        ensure(pthread_cond_wait(&frame.completed, &group.mutex) == 0);

    ensure(group.pending_ready == &frame);
    group.pending_ready = NULL;
    ensure(pthread_cond_broadcast(&group.supervisor_progress) == 0);
    const int outcome = frame.outcome;
    if (outcome == ORES_SOURCE_ACTOR_STARTUP_READY)
        *result_out = frame.result;

    ores_native_actor_future_destroy(frame.ready_future);
    ensure(ores_native_continuation_store_release(
        group.continuations, frame.link.continuation));
    ensure(ores_native_execution_context_store_live(group.executions) == 0);
    ensure(ores_native_continuation_store_live(group.continuations) == 0);
    group_unlock();

    ensure(pthread_cond_destroy(&frame.completed) == 0);
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    fprintf(stderr,
        "SOURCE_AWAIT_READY_TRACE_PASS outcome=%d execution_id_persisted=1 fresh_resume=1 root_slot=%u source_token=%llu\n",
        outcome,
        SOURCE_GROUP_ROOT_SLOT,
        (unsigned long long)source_token);
#endif
    return outcome;
}

int64_t oreslang_source_actor_await_ready(
        void* opaque,
        OresSourceActorContinuation continuation,
        uint64_t source_token) {
    int64_t result = 0;
    const int outcome = oreslang_source_actor_await_ready_checked(
        opaque, continuation, source_token, &result);
    /*
     * Existing source lowering has no typed ActorTerminated projection yet.
     * Fail closed instead of hanging or running the continuation after failed
     * readiness; checked host/runtime callers can observe the failure exactly.
     */
    ensure(outcome == ORES_SOURCE_ACTOR_STARTUP_READY);
    return result;
}

int64_t oreslang_source_actor_await_done(
        void* opaque,
        OresSourceActorContinuation continuation,
        uint64_t source_token) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    ensure(instance != NULL);
    ensure(continuation != NULL);
    ensure(atomic_load_explicit(
        &instance->stop_requested, memory_order_acquire) == 1);
    ensure(atomic_load_explicit(
        &instance->join_started, memory_order_acquire) == 0);

    /*
     * The physical wait is trusted top-level glue. It must never park an
     * actor carrier: the target actor may need that carrier to drain accepted
     * work and publish its terminal acknowledgement.
     */
    OresNativeExecutionContext carrier = {0};
    ensure(!ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &carrier));

    DoneAwaitFrame frame = {
        .continuation = continuation,
        .actor = instance,
        .source_token = source_token
    };
    ensure(pthread_cond_init(&frame.completed, NULL) == 0);

    ensure(group.vm && group.root_ref.scheduler_domain
        && group.pending_ready == NULL && group.pending_done == NULL);
    OresNativeVM* vm = group.vm;
    const OresNativeActorRef target = instance->ref;
    frame.done_future = ores_native_vm_actor_future_open(
        vm,
        ORES_NATIVE_VM_MAIN,
        group.root_ref,
        SOURCE_ROOT_CONTROL_DONE_RESUME);
    ensure(frame.done_future != NULL);
    group.pending_done = &frame;

    /*
     * Persist logical ancestry on a root CONTROL turn before waiting on the
     * target. The host waits for this acknowledgement so no continuation is
     * completed before its durable suspension link exists.
     */
    ensure(ores_native_vm_publish_control(
        vm,
        ORES_NATIVE_VM_MAIN,
        group.root_ref,
        SOURCE_ROOT_CONTROL_DONE_SUSPEND));
    while (!frame.suspend_recorded)
        ensure(pthread_cond_wait(&frame.completed, &group.mutex) == 0);
    group_unlock();

    ensure(ores_native_vm_wait_done(
        vm, ORES_NATIVE_VM_MAIN, target));

    group_lock();
    ensure(group.vm == vm && group.pending_done == &frame);
    ensure(ores_native_vm_poll_done(
        vm, ORES_NATIVE_VM_MAIN, target));
    ensure(ores_native_actor_future_status(frame.done_future)
        == ORES_NATIVE_FUTURE_PENDING);
    ensure(ores_native_actor_future_complete(
        frame.done_future, ORES_NATIVE_FUTURE_READY));

    while (!frame.done)
        ensure(pthread_cond_wait(&frame.completed, &group.mutex) == 0);

    ensure(group.pending_done == &frame);
    group.pending_done = NULL;
    const int64_t result = frame.result;

    ores_native_actor_future_destroy(frame.done_future);
    ensure(ores_native_continuation_store_release(
        group.continuations, frame.link.continuation));
    ensure(ores_native_execution_context_store_live(group.executions) == 0);
    ensure(ores_native_continuation_store_live(group.continuations) == 0);
    group_unlock();

    ensure(pthread_cond_destroy(&frame.completed) == 0);
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    fprintf(stderr,
        "SOURCE_AWAIT_DONE_TRACE_PASS terminal_ack=1 execution_id_persisted=1 fresh_resume=1 root_slot=%u source_token=%llu\n",
        SOURCE_GROUP_ROOT_SLOT,
        (unsigned long long)source_token);
#endif

    /*
     * Reclamation is deliberately after the resumed continuation. This keeps
     * the opaque ActorRef allocation valid while the continuation is active,
     * but no guest message/stop capability is admitted after get_done.
     */
    oreslang_source_actor_join_done(opaque);
    return result;
}

int oreslang_source_actor_current_execution_handle(
        OresNativeExecutionHandle* out) {
    if (!out || !ores_native_store_handle_valid(current_source_execution))
        return 0;
    *out = current_source_execution;
    return 1;
}

int oreslang_source_actor_snapshot_current_trace(
        OresNativeExecutionTraceFrame* execution_frames,
        size_t execution_capacity,
        OresNativeContinuationTraceFrame* continuation_frames,
        size_t continuation_capacity,
        OresNativeLogicalTraceResult* out) {
    if (!out || !ores_native_store_handle_valid(current_source_execution)
            || !group.executions || !group.continuations)
        return 0;
    return ores_native_logical_trace_snapshot(
        group.executions,
        group.continuations,
        current_source_execution,
        execution_frames,
        execution_capacity,
        continuation_frames,
        continuation_capacity,
        out);
}

void oreslang_source_actor_request_stop(void* opaque) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    ensure(instance != NULL);

    /*
     * Stop admission shares the group mutex with startup/readiness publication
     * so exactly one side wins the stop-vs-ready linearization point.
     * This phase still never waits for physical completion.
     */
    int expected = 0;
    const int first_request = atomic_compare_exchange_strong_explicit(
        &instance->stop_requested,
        &expected,
        1,
        memory_order_acq_rel,
        memory_order_acquire);
    /* A frame error may already have sealed the mailbox on the carrier.
     * An outer host may still issue its ordinary stop/get_done sequence. */
    ensure(first_request ||
        atomic_load_explicit(&instance->logical_frame_failed, memory_order_acquire));

    const size_t slot = (size_t)instance->ref.actor_slot;
    ensure(group.vm && slot < group.next_slot
        && group.actors[slot] == instance);
    /*
     * Source stop matches Java ActorRef.stop(), not gracefulStop(): reject
     * future DATA and abandon queued DATA, while an already-active turn is
     * allowed to cross its physical execution boundary.
     */
    if (first_request)
        ensure(ores_native_vm_stop_sealed(
            group.vm, ORES_NATIVE_VM_MAIN, instance->ref));
    ensure(pthread_cond_broadcast(&instance->callback_completed) == 0);
    group_unlock();
}

/* Caller holds group.mutex. A recovery lease keeps the group and its
 * bounded tombstones alive until the outer-main consumer reads the decision.
 * This does not authorize concurrent consuming lifecycle calls or slot reuse. */
static void close_idle_group_locked(void) {
    if (ores_source_recovery_can_close(&group.recovery_lease, group.live)) {
        /* An outstanding final CONTROL callback may authorize and create
         * a new actor. Release group.mutex while waiting: the callback must
         * acquire it to spawn and publish its decision. A finite spin while
         * holding this mutex would deadlock the supervisor. */
        uint64_t published = atomic_load_explicit(
            &group.terminal_published, memory_order_acquire);
        while (ores_source_recovery_can_close(&group.recovery_lease, group.live)
               && atomic_load_explicit(
                   &group.terminal_notices, memory_order_acquire) < published)
            ensure(pthread_cond_wait(
                &group.supervisor_progress, &group.mutex) == 0);
    }
    if (ores_source_recovery_can_close(&group.recovery_lease, group.live)) {
        ensure(group.pending_ready == NULL && group.pending_done == NULL);
        ensure(ores_native_execution_context_store_live(group.executions) == 0);
        ensure(ores_native_continuation_store_live(group.continuations) == 0);

        ensure(ores_native_vm_close(group.vm));
        group.vm = NULL;
        group.root_ref = (OresNativeActorRef){0, 0, 0};

        ensure(ores_native_continuation_store_close(group.continuations));
        group.continuations = NULL;
        ensure(ores_native_execution_context_store_close(group.executions));
        group.executions = NULL;
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
        fprintf(stderr,
            "SOURCE_ACTOR_GROUP_VERIFIED active=0 slots=%zu peak=%zu carriers=%u\n",
            group.next_slot, group.peak, SOURCE_GROUP_CARRIERS);
#endif
        /* Decision metadata remains bounded by the current group. Public
         * identities never recycle, even after these records are freed. */
        for (size_t i = 0; i < group.next_slot; ++i) {
            group.pending_leaf_reclaim[i] = (OresNativeActorRef){0};
            SourceActor* retired = group.retired[i];
            if (!retired) continue;
            ensure(retired->tombstone && retired->heap == NULL
                && retired->logical_frames == NULL);
            retired->magic = 0;
            free(retired);
            group.retired[i] = NULL;
        }
        group.next_slot = 0;
        group.peak = 0;
    }
}

static void source_join_done(void* opaque, int recovery_claimed) {
    group_lock();
    SourceActor* instance = source_resolve_locked(opaque, 0);
    ensure(instance != NULL);
    ensure(!group.pending_done || group.pending_done->actor != instance);
    ensure(atomic_load_explicit(
        &instance->stop_requested, memory_order_acquire) == 1);

    if (recovery_claimed) {
        ensure(source_recovery_owns_locked(instance)
            && atomic_load_explicit(&instance->join_started, memory_order_acquire));
    } else {
        int expected = 0;
        ensure(!source_recovery_owns_locked(instance)
            && atomic_compare_exchange_strong_explicit(
                &instance->join_started, &expected, 1,
                memory_order_acq_rel, memory_order_acquire));
    }

    /*
     * This is trusted outer-host glue, never a carrier turn. Do not block a
     * source actor carrier while waiting for the actor's physical terminal ACK.
     */
    OresNativeExecutionContext carrier = {0};
    ensure(!ores_native_vm_current_execution_context(
        group.vm, ORES_NATIVE_VM_MAIN, &carrier));

    const size_t slot = (size_t)instance->ref.actor_slot;
    OresNativeVM* vm = group.vm;
    const OresNativeActorRef ref = instance->ref;
    group_unlock();
    ensure(ores_native_vm_wait_done(vm, ORES_NATIVE_VM_MAIN, ref));

    group_lock();
    /* A pending readiness continuation retains this record until it settles. */
    while (group.pending_ready && group.pending_ready->actor == instance)
        ensure(pthread_cond_wait(&group.supervisor_progress, &group.mutex) == 0);
    ensure(group.vm && slot < group.next_slot
        && group.actors[slot] == instance);
    const int startup_state = atomic_load_explicit(
        &instance->startup_state, memory_order_acquire);
    ensure(startup_state == ORES_SOURCE_ACTOR_STARTUP_READY
        || startup_state
            == ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY);
    ensure(ores_native_vm_poll_done(
        group.vm, ORES_NATIVE_VM_MAIN, instance->ref));

    OresNativeActorTerminalState terminal = {0};
    ensure(ores_native_vm_actor_terminal_state(
        group.vm, ORES_NATIVE_VM_MAIN, instance->ref, &terminal));
    ensure(terminal.kind == ORES_NATIVE_TERMINATION_STOPPED);
    ensure(terminal.processed_user_messages
        + terminal.abandoned_user_messages
        == terminal.accepted_user_messages);
    ensure(terminal.processed_user_messages
        == atomic_load_explicit(&instance->processed, memory_order_acquire));
    /* Actor-origin failures still count as physically returned DATA callbacks
     * at the scheduler layer, but NEVER as successful guest messages. */
    const uint64_t successful = atomic_load_explicit(
        &instance->successful, memory_order_acquire);
    const uint64_t failed = atomic_load_explicit(
        &instance->failed, memory_order_acquire);
    const uint64_t failure_code = atomic_load_explicit(
        &instance->failure_code, memory_order_acquire);
    ensure(successful <= terminal.processed_user_messages
        && failed == terminal.processed_user_messages - successful);
    ensure(failure_code == 0 || failed > 0);
    ensure(terminal.abandoned_control_messages == 0);

    /*
     * get_done parity requires physical quiescence, not a logical stop bit.
     * Reclaim the actor-owned heap only after the VM terminal acknowledgement.
     */
    ores_actor_i64_heap_retire(instance->heap);
    ensure(ores_actor_i64_heap_try_destroy(instance->heap));
    instance->heap = NULL;
    /* Physical STOP acknowledgement gates both heap and logical-frame free. */
    ensure(ores_logical_frames_v1_close(instance->logical_frames));
    instance->logical_frames = NULL;

    /* The VM requires a trusted resource-finalization receipt *after* the
     * physical actor DONE and both actor-owned allocations are gone. Only
     * the registered parent can consume this typed failure/STOPPED notice.
     * Once acknowledged, a duplicate is rejected by the VM. */
    ensure(ores_native_vm_confirm_supervised_finalized(
        group.vm, ORES_NATIVE_VM_MAIN, instance->ref));
    OresNativeActorTerminalFailure notice = {
        .kind = failure_code ? ORES_NATIVE_CONTROL_PANIC : ORES_NATIVE_CONTROL_STOPPED,
        .child = instance->ref,
        .failure_code = failure_code,
        .correlation = instance->id
    };
    /* The reserved parent CONTROL slot is bounded. Publication can fail
     * transiently if occupied; do not lose the notice. We are the external
     * host, never a carrier turn. Release group mutex while waiting, because
     * root CONTROL callbacks may need it to finish. No per-retry allocation. */
    group_unlock();
    while (!ores_native_vm_publish_supervised_failure(
            group.vm, ORES_NATIVE_VM_MAIN, notice))
        sched_yield();
    atomic_fetch_add_explicit(
        &group.terminal_published, 1, memory_order_release);
    group_lock();
    ensure(group.vm && slot < group.next_slot
        && group.actors[slot] == instance);
#ifdef ORESLANG_SOURCE_ACTOR_TESTING
    fprintf(stderr,
        "SOURCE_ACTOR_SPAWN_STOP_VERIFIED constructor_on_worker=%d released=1 ready=%d accepted=%llu processed=%llu abandoned=%llu last=%lld successful=%llu failed=%llu failure_code=%llu\n",
        atomic_load_explicit(
            &instance->constructor_started, memory_order_acquire),
        startup_state == ORES_SOURCE_ACTOR_STARTUP_READY,
        (unsigned long long)terminal.accepted_user_messages,
        (unsigned long long)terminal.processed_user_messages,
        (unsigned long long)terminal.abandoned_user_messages,
        (long long)atomic_load_explicit(
            &instance->last_result, memory_order_relaxed),
        (unsigned long long)successful,
        (unsigned long long)failed,
        (unsigned long long)failure_code);
#endif
    /* The exact root policy callback must settle before the record can be
     * detached. The VM's independent acknowledgement gate still decides
     * when the scheduler slot becomes reusable. */
    while (!instance->terminal_policy_settled)
        ensure(pthread_cond_wait(&group.supervisor_progress, &group.mutex) == 0);
    /* Quiesce trusted external flush waiters before dropping their condition
     * and the actor pointer. This wait releases group.mutex until the final
     * reader exits, preventing a use-after-free during STOP/join. */
    while (instance->flush_waiters > 0)
        ensure(pthread_cond_wait(&instance->callback_completed, &group.mutex) == 0);
    ensure(pthread_cond_destroy(&instance->callback_completed) == 0);
    group.actors[slot] = NULL;
    if (recovery_claimed) {
        ensure(failure_code != 0 && instance->restart_remaining != 0
            && source_recovery_owns_locked(instance) && instance->restart_settled);
        /* Preserve only the exact consuming recovery decision. Plain STOP
         * never retains an unconsumed replacement or tombstone. */
        instance->tombstone = 1;
        group.retired[slot] = instance;
    } else {
        ensure(!group.pending_leaf_reclaim[slot].scheduler_domain);
        group.pending_leaf_reclaim[slot] = instance->ref;
        instance->magic = 0;
        free(instance);
        source_reclaim_settled_leaves_locked();
    }
    ensure(group.live > 0);
    group.live--;

    close_idle_group_locked();
    group_unlock();
}

void oreslang_source_actor_join_done(void* opaque) {
    source_join_done(opaque, 0);
}

void* oreslang_source_actor_recover_blocking(void* opaque) {
    if (!opaque) return NULL;
    group_lock();
    SourceActor* previous = source_resolve_locked(opaque, 0);
    /* This is a consuming trusted-main bridge. A nonfailed actor, forged
     * reference, duplicate recovery request, or retired incarnation cannot
     * be restarted by simply calling a runtime symbol. */
    int eligible = group.vm && previous && previous->magic == SOURCE_ACTOR_MAGIC
        && !previous->tombstone
        && previous->restart_remaining != 0
        && atomic_load_explicit(&previous->logical_frame_failed,
                                memory_order_acquire)
        && atomic_load_explicit(&previous->stop_requested,
                                memory_order_acquire)
        && !atomic_load_explicit(&previous->join_started,
                                 memory_order_acquire)
        && previous->ref.actor_slot < group.next_slot
        && group.actors[previous->ref.actor_slot] == previous;
    if (eligible) {
        OresNativeExecutionContext carrier = {0};
        eligible = !ores_native_vm_current_execution_context(
            group.vm, ORES_NATIVE_VM_MAIN, &carrier);
    }
    if (eligible) eligible = ores_source_recovery_claim(&group.recovery_lease,
        (size_t)previous->ref.actor_slot, previous->ref.generation, previous->id);
    if (eligible) {
        /* Both consuming authorities linearize under the same mutex. */
        int expected = 0;
        ensure(atomic_compare_exchange_strong_explicit(
            &previous->join_started, &expected, 1,
            memory_order_acq_rel, memory_order_acquire));
    }
    group_unlock();
    if (!eligible) return NULL;

    /* Physical DONE, heap retirement, frame scrubbing, host finalization,
     * registered root CONTROL publication. No guest carrier parks here.
     * Root's CONTROL callback decides policy and creates a fresh child,
     * which cannot see any of the previous incarnation's owned storage. */
    source_join_done(opaque, 1);
    group_lock();
    const size_t old_slot = (size_t)previous->ref.actor_slot;
    ensure(old_slot < group.next_slot && group.retired[old_slot] == previous);
    while (!previous->restart_settled)
        ensure(pthread_cond_wait(
            &group.supervisor_progress, &group.mutex) == 0);
    SourceActor* fresh = previous->replacement;
    if (fresh) {
        ensure(fresh != previous && fresh->id != previous->id
            && fresh->ref.scheduler_domain == previous->ref.scheduler_domain
            && fresh->ref.actor_slot != previous->ref.actor_slot
            && group.actors[fresh->ref.actor_slot] == fresh
            && fresh->lifetime_frame_admissions == 0
            && fresh->frame_budget == previous->frame_budget
            && fresh->lifetime_frame_budget == previous->lifetime_frame_budget);
    }
    ensure(ores_source_recovery_release(&group.recovery_lease, old_slot,
        previous->ref.generation, previous->id));
    void* replacement_handle = fresh ? source_handle(fresh) : NULL;
    /* Recovery consumed this old identity. No external reader can still
     * hold the record: join_done drained flush/readiness and root policy.
     * The VM independently waits for physical CONTROL acknowledgement. */
    ensure(previous->terminal_policy_settled && previous->tombstone
        && !previous->heap && !previous->logical_frames
        && !group.pending_leaf_reclaim[old_slot].scheduler_domain);
    group.pending_leaf_reclaim[old_slot] = previous->ref;
    group.retired[old_slot] = NULL;
    previous->magic = 0;
    free(previous);
    source_reclaim_settled_leaves_locked();
    close_idle_group_locked();
    group_unlock();
    return replacement_handle;
}

void oreslang_source_actor_stop(void* opaque) {
    /*
     * Preserve synchronous stop for source paths that do not await get_done.
     * get_done lowering instead emits request_stop followed by await_done.
     */
    oreslang_source_actor_request_stop(opaque);
    oreslang_source_actor_join_done(opaque);
}
