#pragma once
#include "oreslang/runtime/ActorOwnedI64Heap.h"
#include "oreslang/runtime/NativeLogicalTrace.h"
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Restricted compiler-generated source-actor bootstrap: a pointer to the
 * generated constructor and fixed number of actor-owned i64 fields.
 * Returns an opaque monotonic native identity transported in the pointer ABI;
 * it is never a dereferenceable address or a guest scalar/FFI capability.
 * Source stop is only permitted from trusted zero-argument main.
 */
typedef void (*OresSourceActorInit)(OresActorI64Heap*, uint64_t, uint64_t);
typedef int64_t (*OresSourceActorMessage)(
    OresActorI64Heap*, uint64_t, uint64_t, int64_t);
typedef int64_t (*OresSourceActorContinuation)(void* actor);

enum {
    ORES_SOURCE_ACTOR_STARTUP_PENDING = 0,
    ORES_SOURCE_ACTOR_STARTUP_INITIALIZING = 1,
    ORES_SOURCE_ACTOR_STARTUP_READY = 2,
    ORES_SOURCE_ACTOR_STARTUP_TERMINATED_BEFORE_READY = 3
};

/* Read-only host lifecycle observation. TERMINATED_BEFORE_READY corresponds
 * to a failed get_ready observer while get_done still completes normally. */
int oreslang_source_actor_ready_status(void* actor);

/* Runtime-only observation: distinguished physical callbacks from successful
 * guest completions. A failed receiver must not be reported as a successful
 * zero result. The scheduler's processed count still counts any DATA callback
 * that physically returned (including a trapped failed receiver).
 */
#define ORES_SOURCE_ACTOR_FAILURE_FRAME UINT64_C(0xA7700001)
typedef struct {
    uint64_t processed_callbacks;
    uint64_t successful_messages;
    uint64_t failed_messages;
    uint64_t failure_code;
    int64_t last_success_result;
    int stop_requested;
} OresSourceActorMessageReceipt;

/* Observations resolve under the registry mutex, including retained failure
 * metadata. A stale identity with no record returns 0. Trusted diagnostics,
 */
int oreslang_source_actor_message_receipt(
    void* actor, OresSourceActorMessageReceipt* out);

/* Non-aborting status variant for trusted lowering/tests. A successful send
 * admits work, but does not guarantee processing before a competing STOP. */
int oreslang_source_actor_try_send(void* actor, int64_t payload);


#ifdef ORESLANG_SOURCE_ACTOR_TESTING
/* Deterministic startup-race proof hooks. They pause only the reserved
 * initialization CONTROL callback before it decides whether construction may
 * begin; they are not linked into ordinary source programs. */
void oreslang_source_actor_test_hold_initialization(int hold);
int oreslang_source_actor_test_initialization_entered(void);
void oreslang_source_actor_test_hold_readiness_publication(int hold);
int oreslang_source_actor_test_readiness_publication_entered(void);
/* Atomic count of priority supervisor terminal notices processed on the
 * root actor CONTROL lane; useful for exact once-delivery test receipts. */
uint64_t oreslang_source_actor_test_terminal_notices(void);
uint64_t oreslang_source_actor_test_panic_notices(void);
uint64_t oreslang_source_actor_test_stopped_notices(void);
uint64_t oreslang_source_actor_test_last_panic_code(void);
#endif

void* oreslang_source_actor_spawn(OresSourceActorInit initializer,
                                  OresSourceActorMessage on_message,
                                  uint64_t fields);
/* The compiler emits the bounded per-actor frame budget as an exact ABI
 * parameter, not a guest mutable pointer. Zero refuses all receiver
 * frames; 1..16 bound nested synchronous frames per physical turn. */
void* oreslang_source_actor_spawn_budgeted(OresSourceActorInit initializer,
                                           OresSourceActorMessage on_message,
                                           uint64_t fields,
                                           uint64_t max_frames);
/* Native AOT-only supervisor contract: the root CONTROL handler owns a
 * bounded one-for-one policy. Every replacement keeps original frame-depth
 * and per-incarnation cumulative admission quotas, with a fresh frame store
 * and scheduler identity. restart_limit=0 preserves ordinary stop semantics. */
void* oreslang_source_actor_spawn_supervised_budgeted(
    OresSourceActorInit initializer, OresSourceActorMessage on_message,
    uint64_t fields, uint64_t max_frames,
    uint64_t lifetime_frame_budget, uint64_t restart_limit);

/* Consumes a logically failed ActorRef and awaits the root supervisor's
 * CONTROL-selected replacement. The old ActorRef remains an invalid,
 * generation-fenced tombstone until consuming recovery reads the decision;
 * after recovery the old token cannot resolve even for diagnostics. In this
 * restricted linear source profile plain STOP/join discards the failed ref
 * without requesting a replacement. No scheduler carrier
 * may call this blocking trusted-main bridge. Returns NULL on escalation. */
void* oreslang_source_actor_recover_blocking(void* failed_actor);

/* Nonblocking bounded one-way send. Overload currently fails closed with a
 * host error; a typed guest overload result still needs compiler lowering.
 */
void oreslang_source_actor_send(void* actor, int64_t payload);
/* Trusted-main bounded-mailbox completion barrier. Wait for the accepted
 * snapshot captured at entry to finish physical DATA callbacks (a failed
 * callback is still completed, never a successful result). It does not seal
 * the mailbox or convert actor-local failures into process aborts.
 * A terminal seal waits for physical DONE if the snapshot is still pending;
 * returns 0 only if the sealed snapshot cannot complete (abandoned DATA),
 * or if the reference is invalid or already being consumed.
 * Must not be invoked on an actor carrier; this blocking sync API is not
 * actor-method suspension or a general async Promise/Task.
 */
int oreslang_source_actor_flush_blocking(void* actor);

/*
 * Compiler/runtime stop phases. request_stop is nonblocking and only asks the
 * scheduler to terminate the actor. join_done is trusted host glue: it waits
 * for physical VM completion, retires/destroys the actor heap, releases the
 * opaque SourceActor allocation, and closes an idle source-actor VM.
 *
 * Source lowering without get_done continues to call oreslang_source_actor_stop(),
 * which keeps its existing synchronous request+join behavior. get_done lowering
 * uses request_stop plus oreslang_source_actor_await_done(); the split APIs remain
 * trusted compiler/runtime capabilities, not guest-callable functions.
 */
void oreslang_source_actor_request_stop(void* actor);
void oreslang_source_actor_join_done(void* actor);
void oreslang_source_actor_stop(void* actor);

/*
 * Restricted compiler-generated await slice:
 *   await actor.get_ready();
 *
 * The trusted host arranges one root CONTROL suspension turn, tears down that
 * worker stack, then a NativeActorFuture admits exactly one root CONTROL resume
 * turn. The persisted OresNativeSuspensionLink carries logical ancestry between
 * those turns; no native stack pointer is retained.
 *
 * The continuation runs on the root actor lane and must not synchronously call
 * source_actor_stop. The outer trusted main performs stop after this function
 * returns. source_token is a compiler-provided source offset/site identifier.
 */
int64_t oreslang_source_actor_await_ready(
    void* actor,
    OresSourceActorContinuation continuation,
    uint64_t source_token);

/* Host/runtime projection of the same readiness observer. Returns READY or
 * TERMINATED_BEFORE_READY, or -1 for an unknown/retiring identity;
 * the continuation runs only on READY. The compiler
 * wrapper above remains fail-closed until typed ActorTerminated propagation is
 * lowered for source await. */
int oreslang_source_actor_await_ready_checked(
    void* actor,
    OresSourceActorContinuation continuation,
    uint64_t source_token,
    int64_t* result);

/*
 * Restricted compiler-generated terminal await slice:
 *   stop actor;
 *   await actor.get_done();
 *
 * The stop request has already been admitted before this call. The trusted
 * outer host, never an actor carrier, waits for the target's physical terminal
 * ACK. A root CONTROL suspension/resume pair preserves logical ancestry; the
 * target actor heap and opaque SourceActor are reclaimed only after the resumed
 * continuation has completed.
 */
int64_t oreslang_source_actor_await_done(
    void* actor,
    OresSourceActorContinuation continuation,
    uint64_t source_token);

/*
 * Active only while a compiler-generated source continuation is executing.
 * These hooks let throw/raise/panic rendering attach the live logical ancestry
 * without exposing a native stack pointer or runtime store object.
 */
int oreslang_source_actor_current_execution_handle(
    OresNativeExecutionHandle* out);
int oreslang_source_actor_snapshot_current_trace(
    OresNativeExecutionTraceFrame* execution_frames,
    size_t execution_capacity,
    OresNativeContinuationTraceFrame* continuation_frames,
    size_t continuation_capacity,
    OresNativeLogicalTraceResult* out);

#ifdef __cplusplus
}
#endif
