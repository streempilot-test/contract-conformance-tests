/* Real native source-bootstrap + registered NativeOresVM one-for-one policy.
 * A permits one successful frame admission per actor incarnation. A's
 * second DATA fails under that unchanged quota, root supervisor authorizes
 * exactly one replacement after DONE/heap+frame cleanup, A2 handles 9,
 * and sibling B handles 42. Stale old ActorRef rejects further sends.
 * This is native C ABI proof; compiled .atto guest is a separate CI gate. */
#define _POSIX_C_SOURCE 200809L
#include "oreslang/runtime/SourceActorBootstrap.h"
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <sched.h>

#define CHECK(x) do { if (!(x)) { \
  fprintf(stderr, "SOURCE_ACTOR_RESTART_FAIL line=%d condition=%s\n", __LINE__, #x); \
  abort(); } } while (0)
extern int oreslang_source_actor_frame_push(void);
extern int oreslang_source_actor_frame_store_i64(int64_t);
extern int oreslang_source_actor_frame_load_i64(int64_t*);
extern int oreslang_source_actor_frame_pop(void);

static atomic_int a_seven, a_nine, b_forty_two;
static void initialize(OresActorI64Heap* h, uint64_t d, uint64_t e) {
    (void)h; (void)d; (void)e;
}
static int64_t actor_a(OresActorI64Heap* h, uint64_t d, uint64_t e, int64_t msg) {
    (void)h; (void)d; (void)e;
    if (!oreslang_source_actor_frame_push()) return 0;
    CHECK(msg == 7 || msg == 9);
    CHECK(oreslang_source_actor_frame_store_i64(msg));
    int64_t value = 0;
    CHECK(oreslang_source_actor_frame_load_i64(&value));
    CHECK(oreslang_source_actor_frame_pop());
    CHECK(value == msg);
    if (msg == 7) atomic_fetch_add(&a_seven, 1);
    if (msg == 9) atomic_fetch_add(&a_nine, 1);
    return value;
}
static int64_t actor_b(OresActorI64Heap* h, uint64_t d, uint64_t e, int64_t msg) {
    (void)h; (void)d; (void)e;
    CHECK(msg == 42);
    CHECK(oreslang_source_actor_frame_push());
    CHECK(oreslang_source_actor_frame_store_i64(msg));
    int64_t value = 0;
    CHECK(oreslang_source_actor_frame_load_i64(&value));
    CHECK(oreslang_source_actor_frame_pop());
    CHECK(value == 42);
    atomic_fetch_add(&b_forty_two, 1);
    return value;
}
static void check_last_actor_capacity_denial(void) {
    /* Occupy every other child slot with a live sibling. Lifetime-spent
     * ordinary leaves are now reclaimable, so only genuine concurrent
     * capacity exhaustion may deny this replacement. */
    void* a = oreslang_source_actor_spawn_supervised_budgeted(
        initialize, actor_a, 1, 16, 1, 1);
    CHECK(a);
    void* fillers[62];
    for (unsigned i = 0; i < 62; ++i) {
        fillers[i] = oreslang_source_actor_spawn(initialize, actor_b, 1);
        CHECK(fillers[i] && fillers[i] != a);
    }
    CHECK(oreslang_source_actor_try_send(a, 7));
    CHECK(oreslang_source_actor_flush_blocking(a));
    CHECK(oreslang_source_actor_try_send(a, 8));
    CHECK(oreslang_source_actor_flush_blocking(a));
    CHECK(!oreslang_source_actor_recover_blocking(a));
    CHECK(!oreslang_source_actor_try_send(a, 99));
    CHECK(!oreslang_source_actor_recover_blocking(a));
    for (unsigned i = 0; i < 62; ++i) oreslang_source_actor_stop(fillers[i]);
    /* Recovery consumed A; old identities never resolve in a fresh group. */
    CHECK(!oreslang_source_actor_try_send(a, 99));
    CHECK(oreslang_source_actor_ready_status(a) == -1);
    OresSourceActorMessageReceipt stale = {0};
    CHECK(!oreslang_source_actor_message_receipt(a, &stale));
    CHECK(!oreslang_source_actor_flush_blocking(a));
    CHECK(oreslang_source_actor_test_terminal_notices() == 63);
    CHECK(oreslang_source_actor_test_panic_notices() == 1);
    CHECK(oreslang_source_actor_test_stopped_notices() == 62);
    CHECK(oreslang_source_actor_test_last_panic_code() == ORES_SOURCE_ACTOR_FAILURE_FRAME);
    /* A fresh group must start with fresh capacity after the denied decision. */
    void* b = oreslang_source_actor_spawn(initialize, actor_b, 1);
    CHECK(b && b != a);
    CHECK(!oreslang_source_actor_try_send(a, 99));
    CHECK(oreslang_source_actor_ready_status(a) == -1);
    CHECK(!oreslang_source_actor_message_receipt(a, &stale));
    CHECK(!oreslang_source_actor_flush_blocking(a));
    CHECK(oreslang_source_actor_try_send(b, 42));
    CHECK(oreslang_source_actor_flush_blocking(b));
    oreslang_source_actor_stop(b);
    CHECK(atomic_load(&b_forty_two) == 1);
    CHECK(oreslang_source_actor_test_terminal_notices() == 1);
    puts("SOURCE_ACTOR_LAST_CAPACITY_DENIAL_PASS consumed=1 fresh_group=1 B=42");
}

/* Keep a sibling alive for the entire run, so passing requires reusing
 * native leaf slots in one VM rather than repeatedly reopening the group. */
static void check_leaf_reuse(uint64_t cycles) {
    void* sibling = oreslang_source_actor_spawn(initialize, actor_b, 1);
    CHECK(sibling);
    void* first = NULL;
    void* previous = NULL;
    for (uint64_t i = 0; i < cycles; ++i) {
        void* leaf = oreslang_source_actor_spawn(initialize, actor_b, 1);
        CHECK(leaf && leaf != sibling && leaf != previous);
        if (!first) first = leaf;
        if (previous) {
            CHECK(!oreslang_source_actor_try_send(previous, 99));
            CHECK(!oreslang_source_actor_try_send(first, 99));
            OresSourceActorMessageReceipt stale = {0};
            CHECK(!oreslang_source_actor_message_receipt(previous, &stale));
        }
        CHECK(oreslang_source_actor_try_send(leaf, 42));
        CHECK(oreslang_source_actor_flush_blocking(leaf));
        OresSourceActorMessageReceipt receipt = {0};
        CHECK(oreslang_source_actor_message_receipt(leaf, &receipt));
        CHECK(receipt.successful_messages == 1 && receipt.last_success_result == 42);
        oreslang_source_actor_stop(leaf);
        CHECK(oreslang_source_actor_ready_status(leaf) == -1);
        CHECK(!oreslang_source_actor_try_send(leaf, 99));
        CHECK(oreslang_source_actor_test_terminal_notices() == i + 1);
        previous = leaf;
    }
    CHECK(oreslang_source_actor_try_send(sibling, 42));
    CHECK(oreslang_source_actor_flush_blocking(sibling));
    oreslang_source_actor_stop(sibling);
    CHECK(oreslang_source_actor_test_terminal_notices() == cycles + 1);
    CHECK(oreslang_source_actor_test_stopped_notices() == cycles + 1);
    CHECK(oreslang_source_actor_test_panic_notices() == 0);
    CHECK((uint64_t)atomic_load(&b_forty_two) == cycles + 1);
    printf("SOURCE_ACTOR_LEAF_REUSE_PASS cycles=%" PRIu64 " sibling=42\n", cycles);
}

/* Actual PANIC + supervisor-selected successor and ordinary STOP,
 * in a single persistent VM. More iterations than physical child slots
 * require reclamation of both failed and successful incarnations. */
static void check_failed_leaf_reuse(uint64_t cycles) {
    void* sibling = oreslang_source_actor_spawn(initialize, actor_b, 1);
    CHECK(sibling);
    void* first = NULL;
    for (uint64_t i = 0; i < cycles; ++i) {
        void* leaf = oreslang_source_actor_spawn_supervised_budgeted(
            initialize, actor_a, 1, 16, 1, 1);
        CHECK(leaf && leaf != sibling);
        if (!first) first = leaf;
        CHECK(oreslang_source_actor_try_send(leaf, 7));
        CHECK(oreslang_source_actor_flush_blocking(leaf));
        CHECK(oreslang_source_actor_try_send(leaf, 8));
        CHECK(oreslang_source_actor_flush_blocking(leaf));
        OresSourceActorMessageReceipt receipt = {0};
        CHECK(oreslang_source_actor_message_receipt(leaf, &receipt));
        CHECK(receipt.successful_messages == 1 && receipt.failed_messages == 1);
        void* next = oreslang_source_actor_recover_blocking(leaf);
        CHECK(next && next != leaf && next != sibling);
        CHECK(!oreslang_source_actor_try_send(first, 99));
        CHECK(!oreslang_source_actor_try_send(leaf, 99));
        CHECK(!oreslang_source_actor_recover_blocking(leaf));
        CHECK(!oreslang_source_actor_message_receipt(leaf, &receipt));
        CHECK(oreslang_source_actor_ready_status(leaf) == -1);
        CHECK(oreslang_source_actor_try_send(next, 9));
        CHECK(oreslang_source_actor_flush_blocking(next));
        CHECK(oreslang_source_actor_message_receipt(next, &receipt));
        CHECK(receipt.successful_messages == 1 && receipt.failed_messages == 0
            && receipt.last_success_result == 9);
        oreslang_source_actor_stop(next);
        CHECK(!oreslang_source_actor_try_send(next, 99));
        CHECK(oreslang_source_actor_test_panic_notices() == i + 1);
        CHECK(oreslang_source_actor_test_stopped_notices() == i + 1);
        CHECK(oreslang_source_actor_try_send(sibling, 42));
        CHECK(oreslang_source_actor_flush_blocking(sibling));
    }
    oreslang_source_actor_stop(sibling);
    CHECK(oreslang_source_actor_test_terminal_notices() == 2 * cycles + 1);
    CHECK(oreslang_source_actor_test_panic_notices() == cycles);
    CHECK(oreslang_source_actor_test_stopped_notices() == cycles + 1);
    CHECK((uint64_t)atomic_load(&a_seven) == cycles);
    CHECK((uint64_t)atomic_load(&a_nine) == cycles);
    CHECK((uint64_t)atomic_load(&b_forty_two) == cycles);
    printf("SOURCE_ACTOR_FAILED_LEAF_REUSE_PASS cycles=%" PRIu64 " sibling=42\n", cycles);
}

/* STOP consumes the failed reference without requesting recovery.
 * Keep another actor alive so leaked children/tombstones cannot be hidden
 * by group teardown. More iterations than physical child slots. */
static void check_failed_stop_reuse(void) {
    const uint64_t cycles = 256;
    void* sibling = oreslang_source_actor_spawn(initialize, actor_b, 1);
    CHECK(sibling);
    for (uint64_t i = 0; i < cycles; ++i) {
        void* leaf = oreslang_source_actor_spawn_supervised_budgeted(
            initialize, actor_a, 1, 16, 1, 1);
        CHECK(leaf && leaf != sibling);
        CHECK(oreslang_source_actor_try_send(leaf, 7));
        CHECK(oreslang_source_actor_flush_blocking(leaf));
        CHECK(oreslang_source_actor_try_send(leaf, 8));
        CHECK(oreslang_source_actor_flush_blocking(leaf));
        oreslang_source_actor_stop(leaf);
        OresSourceActorMessageReceipt stale = {0};
        CHECK(!oreslang_source_actor_message_receipt(leaf, &stale));
        CHECK(!oreslang_source_actor_try_send(leaf, 99));
        CHECK(!oreslang_source_actor_recover_blocking(leaf));
        CHECK(oreslang_source_actor_test_panic_notices() == i + 1);
        CHECK(oreslang_source_actor_test_stopped_notices() == 0);
        CHECK(oreslang_source_actor_try_send(sibling, 42));
        CHECK(oreslang_source_actor_flush_blocking(sibling));
    }
    oreslang_source_actor_stop(sibling);
    CHECK(oreslang_source_actor_test_terminal_notices() == cycles + 1);
    CHECK(oreslang_source_actor_test_panic_notices() == cycles);
    CHECK(oreslang_source_actor_test_stopped_notices() == 1);
    CHECK((uint64_t)atomic_load(&a_nine) == 0);
    CHECK((uint64_t)atomic_load(&b_forty_two) == cycles);
    puts("SOURCE_ACTOR_FAILED_STOP_REUSE_PASS cycles=256 orphan=0 sibling=42");
}

extern int oreslang_source_actor_test_flush_done_waiters(void);
static atomic_int flush_turn_entered, flush_turn_release;
static int64_t held_turn(OresActorI64Heap* h, uint64_t d, uint64_t e, int64_t msg) {
    (void)h; (void)d; (void)e;
    atomic_store(&flush_turn_entered, 1);
    while (!atomic_load(&flush_turn_release)) sched_yield();
    return msg;
}
static void wait_for_flag(int (*read_flag)(void)) {
    const struct timespec interval = {0, 1000000};
    for (int i = 0; i < 10000; ++i) {
        if (read_flag()) return;
        nanosleep(&interval, NULL);
    }
    CHECK(0 && "timed out waiting for deterministic test boundary");
}
static int turn_entered(void) { return atomic_load(&flush_turn_entered); }
typedef struct { void* actor; int result; } FlushProbe;
static void* run_flush(void* raw) {
    FlushProbe* probe = raw;
    probe->result = oreslang_source_actor_flush_blocking(probe->actor);
    return NULL;
}
static void check_sealed_flush(void) {
    for (int abandon = 0; abandon <= 1; ++abandon) {
        atomic_store(&flush_turn_entered, 0);
        atomic_store(&flush_turn_release, 0);
        void* a = oreslang_source_actor_spawn(initialize, held_turn, 1);
        CHECK(a && oreslang_source_actor_try_send(a, 42));
        wait_for_flag(turn_entered);
        if (abandon) CHECK(oreslang_source_actor_try_send(a, 99));
        oreslang_source_actor_request_stop(a);
        FlushProbe probe = {a, -1};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, run_flush, &probe) == 0);
        wait_for_flag(oreslang_source_actor_test_flush_done_waiters);
        atomic_store(&flush_turn_release, 1);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(probe.result == !abandon);
        OresSourceActorMessageReceipt receipt = {0};
        CHECK(oreslang_source_actor_message_receipt(a, &receipt));
        CHECK(receipt.processed_callbacks == 1 && receipt.successful_messages == 1);
        CHECK(receipt.last_success_result == 42);
        oreslang_source_actor_join_done(a);
        CHECK(!oreslang_source_actor_flush_blocking(a));
    }
    puts("SOURCE_ACTOR_SEALED_FLUSH_PASS active_completed=1 queued_abandoned=1");
}

int main(int argc, char** argv) {
    if (argc == 2 && strcmp(argv[1], "--sealed-flush") == 0) {
        check_sealed_flush();
        return 0;
    }
    const int exhaust_restart = argc == 2 && strcmp(argv[1], "--exhaust") == 0;
    const int capacity_denial = argc == 2 && strcmp(argv[1], "--capacity") == 0;
    const int reuse = argc == 2 && strcmp(argv[1], "--reuse") == 0;
    const int million = argc == 2 && strcmp(argv[1], "--reuse-million") == 0;
    const int failed_reuse = argc == 2 && strcmp(argv[1], "--failed-reuse") == 0;
    const int failed_million = argc == 2 && strcmp(argv[1], "--failed-reuse-million") == 0;
    const int failed_stop_reuse = argc == 2 && strcmp(argv[1], "--failed-stop-reuse") == 0;
    CHECK(argc == 1 || exhaust_restart || capacity_denial || reuse || million || failed_reuse || failed_million || failed_stop_reuse);
    atomic_init(&a_seven, 0); atomic_init(&a_nine, 0);
    atomic_init(&b_forty_two, 0);
    if (capacity_denial) { check_last_actor_capacity_denial(); return 0; }
    if (reuse || million) { check_leaf_reuse(million ? UINT64_C(1000000) : UINT64_C(256)); return 0; }
    if (failed_stop_reuse) { check_failed_stop_reuse(); return 0; }
    if (failed_reuse || failed_million) { check_failed_leaf_reuse(failed_million ? UINT64_C(1000000) : UINT64_C(256)); return 0; }
    void* a = oreslang_source_actor_spawn_supervised_budgeted(
        initialize, actor_a, 1, 16, 1, 1);
    void* b = oreslang_source_actor_spawn(initialize, actor_b, 1);
    CHECK(a && b && a != b);
    CHECK(oreslang_source_actor_try_send(a, 7));
    CHECK(oreslang_source_actor_flush_blocking(a));
    CHECK(oreslang_source_actor_try_send(a, 8));
    CHECK(oreslang_source_actor_flush_blocking(a));
    OresSourceActorMessageReceipt old = {0};
    CHECK(oreslang_source_actor_message_receipt(a, &old));
    CHECK(old.processed_callbacks == 2);
    CHECK(old.successful_messages == 1 && old.failed_messages == 1);
    CHECK(old.last_success_result == 7);
    CHECK(old.failure_code == ORES_SOURCE_ACTOR_FAILURE_FRAME);
    CHECK(old.stop_requested);
    void* replacement = oreslang_source_actor_recover_blocking(a);
    CHECK(replacement && replacement != a && replacement != b);
    /* Consuming recovery retires even the old diagnostic identity. */
    CHECK(!oreslang_source_actor_try_send(a, 123));
    CHECK(!oreslang_source_actor_recover_blocking(a));
    CHECK(!oreslang_source_actor_message_receipt(a, &old));
    CHECK(oreslang_source_actor_ready_status(a) == -1);
    CHECK(oreslang_source_actor_try_send(replacement, 9));
    CHECK(oreslang_source_actor_flush_blocking(replacement));
    OresSourceActorMessageReceipt fresh = {0};
    CHECK(oreslang_source_actor_message_receipt(replacement, &fresh));
    CHECK(fresh.processed_callbacks == 1 && fresh.successful_messages == 1);
    CHECK(fresh.failed_messages == 0 && fresh.last_success_result == 9);
    CHECK(fresh.failure_code == 0);
    CHECK(oreslang_source_actor_try_send(b, 42));
    CHECK(oreslang_source_actor_flush_blocking(b));
    CHECK(atomic_load(&a_seven) == 1);
    CHECK(atomic_load(&a_nine) == 1);
    CHECK(atomic_load(&b_forty_two) == 1);
    if (exhaust_restart) {
        /* A2 received a fresh per-incarnation quota of one frame and
         * processed 9. Its next turn must fail *without* creating A3:
         * the supervisor's total one_for_one restart budget was spent. */
        CHECK(oreslang_source_actor_try_send(replacement, 10));
        CHECK(oreslang_source_actor_flush_blocking(replacement));
        CHECK(oreslang_source_actor_message_receipt(replacement, &fresh));
        CHECK(fresh.processed_callbacks == 2);
        CHECK(fresh.successful_messages == 1 && fresh.failed_messages == 1);
        CHECK(fresh.last_success_result == 9);
        CHECK(fresh.failure_code == ORES_SOURCE_ACTOR_FAILURE_FRAME);
        CHECK(fresh.stop_requested);
        CHECK(!oreslang_source_actor_recover_blocking(replacement));
        CHECK(!oreslang_source_actor_try_send(replacement, 11));
        /* Prove B progresses AFTER the second terminal fence and exhausted
         * restart decision, not only before A2 fails. */
        CHECK(oreslang_source_actor_try_send(b, 42));
        CHECK(oreslang_source_actor_flush_blocking(b));
        CHECK(atomic_load(&b_forty_two) == 2);
        OresSourceActorMessageReceipt sibling = {0};
        CHECK(oreslang_source_actor_message_receipt(b, &sibling));
        CHECK(sibling.successful_messages == 2 && sibling.failed_messages == 0);
        CHECK(sibling.last_success_result == 42 && !sibling.stop_requested);
    }
    oreslang_source_actor_stop(replacement);
    oreslang_source_actor_stop(b);
    CHECK(oreslang_source_actor_test_terminal_notices() == 3);
    CHECK(oreslang_source_actor_test_panic_notices() == (exhaust_restart ? 2u : 1u));
    CHECK(oreslang_source_actor_test_stopped_notices() == (exhaust_restart ? 1u : 2u));
    CHECK(oreslang_source_actor_test_last_panic_code()
          == ORES_SOURCE_ACTOR_FAILURE_FRAME);
    if (exhaust_restart)
        puts("SOURCE_ACTOR_RESTART_BUDGET_EXHAUSTED_PASS A=PANIC A2=PANIC B=42 A3=NONE");
    else
        puts("SOURCE_ACTOR_ONE_FOR_ONE_PASS A=1_failed_1 A2=9 B=42 PANIC=1 STOPPED=2");
    return 0;
}
