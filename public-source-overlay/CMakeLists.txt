cmake_minimum_required(VERSION 3.20)
project(oreslang_llvm VERSION 0.2.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Standalone native IR ABI test: hand-authored source-free LLVM IR links with
# the real C telemetry runtime and returns 42. This is NOT Atto compiler IR.
# Limit the fixture to its pinned x86_64 Linux triple and a local Clang driver.
function(atto_add_static_source_log_ir_abi_test target test_name)
  if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux" OR
     NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
    return()
  endif()
  find_program(ATTO_SOURCE_LOG_IR_CLANG NAMES clang clang-20 clang-19 clang-18 clang-17)
  if(NOT ATTO_SOURCE_LOG_IR_CLANG)
    return()
  endif()
  set(atto_ir_object "${CMAKE_CURRENT_BINARY_DIR}/${target}.o")
  add_custom_command(OUTPUT "${atto_ir_object}"
    COMMAND "${ATTO_SOURCE_LOG_IR_CLANG}" -x ir -O1 -c
      "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_static_source_log_ir_abi_v1.ll"
      -o "${atto_ir_object}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_static_source_log_ir_abi_v1.ll"
    VERBATIM)
  set_source_files_properties("${atto_ir_object}" PROPERTIES
    GENERATED TRUE EXTERNAL_OBJECT TRUE)
  add_executable(${target}
    tests/native_static_source_log_ir_abi_harness_v1.c
    src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c
    "${atto_ir_object}")
  target_include_directories(${target} PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  set_target_properties(${target} PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
  target_compile_options(${target} PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  add_test(NAME ${test_name} COMMAND ${target})
  set_tests_properties(${test_name} PROPERTIES
    PASS_REGULAR_EXPRESSION "LLVM_STATIC_SOURCE_LOG_IR_ABI_LINK_HOST_PASS"
    TIMEOUT 20)
endfunction()

# Source identity cold-path admission can be tested without an LLVM SDK.
# This is HOST-ONLY, not compiler-inserted Atto guest instrumentation.
option(ATTO_SOURCE_IDS_HOST_ONLY
  "Build only the standalone native source-ID descriptor verification" OFF)
if(ATTO_SOURCE_IDS_HOST_ONLY)
  include(CTest)
  add_executable(atto_source_id_v1_host_test
    tests/native_source_identity_v1_test.cpp)
  target_include_directories(atto_source_id_v1_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(atto_source_id_v1_host_test PRIVATE cxx_std_17)
  if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(atto_source_id_v1_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME source_id_v1_host_metadata_only
    COMMAND atto_source_id_v1_host_test)

  add_executable(atto_source_id_v1_fuzz_host_test
    tests/native_source_identity_fuzz_v1_test.cpp)
  target_include_directories(atto_source_id_v1_fuzz_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(atto_source_id_v1_fuzz_host_test PRIVATE cxx_std_17)
  if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(atto_source_id_v1_fuzz_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME source_id_v1_fuzz_host_metadata_only
    COMMAND atto_source_id_v1_fuzz_host_test)
  add_executable(atto_source_log_manifest_host_test
    tests/native_source_log_manifest_v1_test.cpp)
  target_include_directories(atto_source_log_manifest_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_definitions(atto_source_log_manifest_host_test PRIVATE
    ATTO_SOURCE_LOG_MANIFEST_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/examples/telemetry/log-info-ids.json")
  target_compile_features(atto_source_log_manifest_host_test PRIVATE cxx_std_17)
  if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(atto_source_log_manifest_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME source_log_manifest_host_only
    COMMAND atto_source_log_manifest_host_test)
  add_executable(atto_native_telemetry_literal_host_test
    tests/native_telemetry_literal_v1_test.cpp)
  target_include_directories(atto_native_telemetry_literal_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(atto_native_telemetry_literal_host_test PRIVATE cxx_std_17)
  target_compile_options(atto_native_telemetry_literal_host_test PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  add_test(NAME native_telemetry_literal_10k_host_only
    COMMAND atto_native_telemetry_literal_host_test)
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    add_executable(atto_source_log_paths_host_test
      tests/native_source_log_paths_v1_test.cpp)
    target_include_directories(atto_source_log_paths_host_test PRIVATE
      "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(atto_source_log_paths_host_test PRIVATE cxx_std_17)
    target_compile_options(atto_source_log_paths_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME source_log_paths_host_only
      COMMAND atto_source_log_paths_host_test)
  endif()
  atto_add_static_source_log_ir_abi_test(
    atto_static_source_log_ir_link_host_test
    source_free_static_log_ir_link_host_only)

  # C11 host-native ring verification; no LLVM SDK or guest compiler needed.
  add_executable(atto_static_log_ring_v1_host_test
    tests/native_static_log_ring_v1_test.c
    src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
  target_include_directories(atto_static_log_ring_v1_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  set_target_properties(atto_static_log_ring_v1_host_test PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
  if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(atto_static_log_ring_v1_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME static_guest_log_ring_host_only
    COMMAND atto_static_log_ring_v1_host_test)
  add_executable(atto_static_log_model_v1_host_test
    tests/native_static_log_model_v1_test.c
    src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
  target_include_directories(atto_static_log_model_v1_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  set_target_properties(atto_static_log_model_v1_host_test PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
  target_compile_options(atto_static_log_model_v1_host_test PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  add_test(NAME static_log_50k_model_host_only COMMAND atto_static_log_model_v1_host_test)
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(atto_static_log_carriers_v1_host_test
      tests/native_static_log_carriers_v1_test.c
      src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
    target_include_directories(atto_static_log_carriers_v1_host_test PRIVATE
      "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(atto_static_log_carriers_v1_host_test PRIVATE Threads::Threads)
    set_target_properties(atto_static_log_carriers_v1_host_test PROPERTIES
      C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto_static_log_carriers_v1_host_test PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME static_log_eight_carriers_host_only
      COMMAND atto_static_log_carriers_v1_host_test)
  endif()
  return()
endif()

# ActorRegionV1 verification can be built against the REAL ActorArena.c
# without LLVM headers or the compiler. This is host-runtime evidence ONLY,
# never proof that an AttoLang .atto guest was compiled or ran.
option(ORESLANG_ACTOR_REGION_HOST_ONLY
    "Build the isolated ActorRegionV1 native checks without LLVM" OFF)
option(ORESLANG_ACTOR_REGION_SANITIZERS
    "Enable ASan + UBSan for the isolated ActorRegionV1 native checks" OFF)
if(ORESLANG_ACTOR_REGION_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "ActorRegionV1 host verification requires BUILD_TESTING=ON")
    endif()
    if(NOT UNIX OR NOT CMAKE_C_COMPILER_ID MATCHES "GNU|Clang|AppleClang")
        message(FATAL_ERROR "ActorRegionV1 subprocess test requires POSIX + GCC/Clang")
    endif()
    find_package(Threads REQUIRED)
    foreach(level IN ITEMS 0 2)
        foreach(kind IN ITEMS adapter supervisor)
            set(target "oreslang-actor-region-v1-${kind}-O${level}")
            if(kind STREQUAL "adapter")
                set(test_file tests/actor_region_v1_test.c)
            else()
                set(test_file tests/actor_region_v1_fault_supervisor_test.c)
            endif()
            add_executable(${target}
                ${test_file}
                src/main/cpp/oreslang/runtime/ActorRegionV1.c
                src/main/cpp/oreslang/runtime/ActorArena.c)
            target_include_directories(${target} PRIVATE
                "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
            target_compile_features(${target} PRIVATE c_std_11)
            target_compile_options(${target} PRIVATE
                -Wall -Wextra -Wpedantic -Werror -UNDEBUG "-O${level}")
            target_link_libraries(${target} PRIVATE Threads::Threads)
            if(ORESLANG_ACTOR_REGION_SANITIZERS)
                target_compile_options(${target} PRIVATE
                    -fsanitize=address,undefined -fno-omit-frame-pointer)
                target_link_options(${target} PRIVATE
                    -fsanitize=address,undefined)
            endif()
            set(test_name "native_actor_region_v1_${kind}_O${level}_host_only")
            add_test(NAME ${test_name} COMMAND ${target})
            set_tests_properties(${test_name} PROPERTIES TIMEOUT 45)
        endforeach()
    endforeach()
    return()
endif()

# GPU actor host-only verification. Opt-in native CTest with real actor/OpenCL
# sources but no LLVM developer packages or compiled AttoLang guest.
option(ORESLANG_GPU_ACTOR_HOST_ONLY "Build native GPU actor security tests only" OFF)
if(ORESLANG_GPU_ACTOR_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "GPU actor host-only verification needs BUILD_TESTING=ON")
    endif()
    find_package(Threads REQUIRED)
    function(oreslang_gpu_actor_host_test target test_name source_file)
        add_executable(${target} ${source_file})
        target_include_directories(${target} PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(${target} PRIVATE cxx_std_17)
        target_compile_options(${target} PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
        target_link_libraries(${target} PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
        add_test(NAME ${test_name} COMMAND ${target})
        set_tests_properties(${test_name} PROPERTIES TIMEOUT 45)
    endfunction()
    oreslang_gpu_actor_host_test(oreslang-gpu-host-v0-registry
        native_gpu_actor_v0_registry_migration
        tests/gpu_actor_host_turn_bridge_v0_test.cpp)
    oreslang_gpu_actor_host_test(oreslang-gpu-host-v1-quarantine
        native_gpu_actor_v1_quarantine
        tests/gpu_actor_provider_bridge_v1_test.cpp)
    oreslang_gpu_actor_host_test(oreslang-gpu-host-v2-opencl
        native_gpu_actor_v2_opencl_mock
        tests/gpu_actor_opencl_provider_v2_test.cpp)
    oreslang_gpu_actor_host_test(oreslang-gpu-host-opencl-g3
        native_gpu_opencl_g3_legacy_regression
        tests/opencl_provider_g3_test.cpp)
    add_test(NAME native_gpu_actor_v0_completion_authority_private
        COMMAND "${CMAKE_COMMAND}"
            "-DGPU_ACTOR_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
            "-DGPU_ACTOR_INCLUDE_ROOT=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
            "-DGPU_ACTOR_NEGATIVE_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_actor_v0_completion_authority_negative.cpp"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/GpuActorV0CompletionAuthorityGate.cmake")
    set_tests_properties(native_gpu_actor_v0_completion_authority_private
        PROPERTIES TIMEOUT 30)
    # Worker-only quiescence/cleanup authority: ordinary C++ callers MUST
    # fail compilation when invoking these potentially blocking methods.
    add_test(NAME native_gpu_actor_recovery_worker_authority_private
        COMMAND "${CMAKE_COMMAND}"
            "-DGPU_ACTOR_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
            "-DGPU_ACTOR_INCLUDE_ROOT=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
            "-DGPU_ACTOR_NEGATIVE_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_actor_recovery_authority_negative.cpp"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/GpuActorRecoveryAuthorityGate.cmake")
    set_tests_properties(native_gpu_actor_recovery_worker_authority_private
        PROPERTIES TIMEOUT 30)

    # Build the physical probe, but never count 77 as a passing GPU test.
    add_executable(oreslang-gpu-physical-prerequisite
        tests/gpu_actor_opencl_v2_physical_probe.cpp)
    target_include_directories(oreslang-gpu-physical-prerequisite PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-gpu-physical-prerequisite PRIVATE cxx_std_17)
    target_compile_options(oreslang-gpu-physical-prerequisite PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-gpu-physical-prerequisite PRIVATE
        Threads::Threads ${CMAKE_DL_LIBS})
    return()
endif()


# Host-only native failure observer regression; no LLVM SDK or guest compiler.
option(ATTO_FAILURE_OBSERVER_HOST_ONLY
  "Build the actor failure observer preallocated-ring host test only" OFF)
if(ATTO_FAILURE_OBSERVER_HOST_ONLY)
  include(CTest)
  if(NOT BUILD_TESTING)
    message(FATAL_ERROR "Native failure observer and supervisor CONTROL host proofs require BUILD_TESTING=ON")
  endif()
  find_package(Threads REQUIRED)
  add_executable(oreslang-native-actor-failure-observer-host-test
    tests/actor_reliability_fault_observer_test.cpp)
  target_include_directories(oreslang-native-actor-failure-observer-host-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(oreslang-native-actor-failure-observer-host-test PRIVATE cxx_std_17)
  target_compile_options(oreslang-native-actor-failure-observer-host-test PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  target_link_libraries(oreslang-native-actor-failure-observer-host-test PRIVATE Threads::Threads)
  add_test(NAME native_actor_failure_observer_ring_host_only
    COMMAND oreslang-native-actor-failure-observer-host-test)
  set_tests_properties(native_actor_failure_observer_ring_host_only PROPERTIES TIMEOUT 20)
  # Run the supervisor CONTROL restart/STOP test on toolchain-only hosts too.
  # This test exercises native runtime headers, not compiled Atto guest code.
  add_executable(oreslang-native-supervisor-control-host-test
    tests/supervisor_control_turn_test.cpp)
  target_include_directories(oreslang-native-supervisor-control-host-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(oreslang-native-supervisor-control-host-test PRIVATE cxx_std_17)
  target_compile_options(oreslang-native-supervisor-control-host-test PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  target_link_libraries(oreslang-native-supervisor-control-host-test PRIVATE Threads::Threads)
  add_test(NAME native_supervisor_control_host_only
    COMMAND oreslang-native-supervisor-control-host-test)
  set_tests_properties(native_supervisor_control_host_only PROPERTIES TIMEOUT 20)
  return()
endif()

# Standalone host access-grant regression (no LLVM toolchain or guest proof).
option(ATTO_ACTOR_ACCESS_HOST_ONLY
  "Build only the checked actor allocation authority host test" OFF)
if(ATTO_ACTOR_ACCESS_HOST_ONLY)
  include(CTest)
  add_executable(atto_actor_access_v1_host_test
    tests/actor_isolation_access_v1_test.cpp)
  target_include_directories(atto_actor_access_v1_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_options(atto_actor_access_v1_host_test PRIVATE
    -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  add_test(NAME actor_access_v1_host_gate
    COMMAND atto_actor_access_v1_host_test)
  find_package(Threads REQUIRED)
  add_executable(atto_actor_access_v1_scoped_host_test
    tests/actor_isolation_access_scoped_test.cpp)
  target_include_directories(atto_actor_access_v1_scoped_host_test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(atto_actor_access_v1_scoped_host_test PRIVATE cxx_std_17)
  target_compile_options(atto_actor_access_v1_scoped_host_test PRIVATE
    -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  target_link_libraries(atto_actor_access_v1_scoped_host_test PRIVATE Threads::Threads)
  add_test(NAME actor_access_v1_scoped_release_retire_quiescence
    COMMAND atto_actor_access_v1_scoped_host_test)
  set_tests_properties(actor_access_v1_scoped_release_retire_quiescence
    PROPERTIES TIMEOUT 20)
  return()
endif()

# Isolated allocation-free binary-pattern compiler primitive verification.
# Deliberately HOST-ONLY: a compiled C++ plan does not prove .atto LLVM guest
# parser/typecheck, generated guest AOT IR, or borrow-checker authority.
option(ORESLANG_AOT_BINARY_PATTERN_HOST_ONLY
    "Build only C++17 static binary/bit pattern plan regression tests" OFF)
if(ORESLANG_AOT_BINARY_PATTERN_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "Binary-pattern host proof requires BUILD_TESTING=ON")
    endif()
    add_executable(oreslang-atto-byte-bit-pattern-plan-host-test
        tests/atto_byte_bit_pattern_plan_test.cpp)
    target_include_directories(oreslang-atto-byte-bit-pattern-plan-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-atto-byte-bit-pattern-plan-host-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-atto-byte-bit-pattern-plan-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_byte_bit_plan_host_only
        COMMAND oreslang-atto-byte-bit-pattern-plan-host-test)
    set_tests_properties(native_atto_byte_bit_plan_host_only PROPERTIES TIMEOUT 15)
    return()
endif()

# Host-only C++17 .oresi v1 preflight with no LLVM SDK dependency.
# Tests metadata admission, not the compiler, guest or native linker.
option(ORESLANG_ORESI_HOST_ONLY
    "Build only pinned .oresi public-interface metadata tests" OFF)
if(ORESLANG_ORESI_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "Native .oresi preflight requires BUILD_TESTING=ON")
    endif()
    add_executable(oreslang-native-oresi-v1-admission-test
        tests/native_oresi_v1_admission_test.cpp)
    target_include_directories(oreslang-native-oresi-v1-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-oresi-v1-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-oresi-v1-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_oresi_v1_import_preflight_host_only
        COMMAND oreslang-native-oresi-v1-admission-test)
    set_tests_properties(native_oresi_v1_import_preflight_host_only
        PROPERTIES TIMEOUT 20)
    return()
endif()

# Isolated native host test entrypoint: no LLVM development package, Rust
# harness, guest frontend or network access is required to verify the real
# C ActorArena + C++20 proxy slab + actor-owned scheduler contract.
# This is explicitly NOT a complete LLVM/compiler or guest-runtime build.
option(ORESLANG_PROXY_SLAB_HOST_ONLY
    "Build only the three native proxy slab host proofs without LLVM" OFF)
if(ORESLANG_PROXY_SLAB_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "Proxy slab host proof requires BUILD_TESTING=ON")
    endif()
    if(NOT CMAKE_C_COMPILER_ID MATCHES "GNU|Clang"
            OR NOT CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        message(FATAL_ERROR "Proxy slab host proof requires GCC or Clang")
    endif()
    find_package(Threads REQUIRED)

    function(oreslang_proxy_host_ctest target test_name timeout_seconds)
        add_executable(${target} ${ARGN})
        target_include_directories(${target} PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(${target} PRIVATE c_std_11 cxx_std_20)
        target_compile_options(${target} PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        target_link_libraries(${target} PRIVATE Threads::Threads)
        add_test(NAME ${test_name} COMMAND ${target})
        set_tests_properties(${test_name} PROPERTIES TIMEOUT ${timeout_seconds})
    endfunction()

    # AOT tracing policy is host-only, never an instrumented Atto guest.
    oreslang_proxy_host_ctest(oreslang-native-trace-probe-policy-host-test
        native_trace_probe_policy_host_only 20
        tests/native_trace_probe_policy_test.cpp)
    oreslang_proxy_host_ctest(oreslang-native-traceparent-v00-host-test
        native_traceparent_v00_host_only 20
        tests/native_traceparent_v00_test.cpp)
    oreslang_proxy_host_ctest(oreslang-native-semantic-span-host-test
        native_semantic_span_buffer_host_only 20
        tests/native_semantic_span_buffer_test.cpp)

    oreslang_proxy_host_ctest(oreslang-native-proxy-request-slab-test
        native_proxy_request_slab_host_only 20
        tests/native_proxy_request_slab_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    oreslang_proxy_host_ctest(oreslang-native-proxy-arena-pages-test
        native_proxy_slab_arena_pages_and_zeroization 30
        tests/native_proxy_request_slab_arena_pages_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    oreslang_proxy_host_ctest(oreslang-native-actor-proxy-slab-owner-test
        native_actor_proxy_slab_generation_and_quota 30
        tests/native_actor_proxy_request_slab_owner_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/ActorArena.c)
    oreslang_proxy_host_ctest(oreslang-native-proxy-transport-gate-test
        native_proxy_transport_callback_gate_races 20
        tests/native_proxy_transport_gate_test.cpp)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        oreslang_proxy_host_ctest(oreslang-native-epoll-transport-bridge-test
            native_epoll_transport_callback_retirement 20
            tests/native_epoll_transport_bridge_test.cpp)
        oreslang_proxy_host_ctest(oreslang-native-epoll-transport-stress-test
            native_epoll_transport_capacity_stale_shutdown_stress 30
            tests/native_epoll_transport_capacity_stress_test.cpp)
        oreslang_proxy_host_ctest(oreslang-native-epoll-bridge-lifetime-test
            native_epoll_bridge_external_handoff_failstop 20
            tests/native_epoll_bridge_lifetime_test.cpp)
        oreslang_proxy_host_ctest(oreslang-native-actor-nio-fd-lease-test
            native_actor_nio_fd_lease_exclusive_rearm 20
            tests/native_actor_nio_fd_lease_test.cpp)
        oreslang_proxy_host_ctest(oreslang-native-vm-actor-nio-owned-test
            native_vm_actor_nio_owned_callbacks 40
            tests/native_vm_actor_nio_owned_test.cpp
            src/main/cpp/oreslang/runtime/NativeOresVM.c
            src/main/cpp/oreslang/runtime/NativeActorFuture.c
            src/main/cpp/oreslang/runtime/NativeActorScheduler.c
            src/main/cpp/oreslang/runtime/NativeOresVMNio.cpp
            src/main/cpp/oreslang/runtime/NativeEpollReactorABI.cpp)
    endif()
    return()
endif()

# Narrow actual C++ host-runtime test, runnable without LLVM developer headers.
# This is NOT compiler-lowered .atto Map<Bytes,Bytes> or typed ActorMail.
option(ORESLANG_ACTOR_BYTES_MAP_HOST_ONLY
    "Build only the bounded actor-owned Bytes map native host regression" OFF)
if(ORESLANG_ACTOR_BYTES_MAP_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "Actor Bytes map host proof requires BUILD_TESTING=ON")
    endif()
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-bytes-map-test
        tests/native_actor_bytes_map_test.cpp)
    target_include_directories(oreslang-native-actor-bytes-map-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-bytes-map-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-bytes-map-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-actor-bytes-map-test PRIVATE
        Threads::Threads)
    add_test(NAME native_actor_owned_bytes_map_host_only
        COMMAND oreslang-native-actor-bytes-map-test)
    set_tests_properties(native_actor_owned_bytes_map_host_only
        PROPERTIES TIMEOUT 60)
    return()
endif()

# Managed Oreslang references are a language/runtime storage policy, separate
# from the native target ABI. Keep compact actor-local references as the
# default on 64-bit hosts while preserving an explicit full-width mode.
set(ORES_MANAGED_REF_MODE "compressed32" CACHE STRING
    "Default native managed reference representation: compressed32 or native64")
set_property(CACHE ORES_MANAGED_REF_MODE PROPERTY STRINGS compressed32 native64)
if(ORES_MANAGED_REF_MODE STREQUAL "native64")
    add_compile_definitions(ORES_MANAGED_REF_DEFAULT_NATIVE64=1)
elseif(NOT ORES_MANAGED_REF_MODE STREQUAL "compressed32")
    message(FATAL_ERROR
        "ORES_MANAGED_REF_MODE must be compressed32 or native64; got '${ORES_MANAGED_REF_MODE}'")
endif()
message(STATUS "Oreslang managed reference mode: ${ORES_MANAGED_REF_MODE}")

# Opt-in host-only receiver selector verification for environments without
# LLVM C++ headers or llvm-config. Never claim native guest compiler evidence
# from this bounded CTest target. Normal builds remain LLVM-backed.
option(ORES_RECEIVER_SLOT_HOST_ONLY "Build only the host receiver slot CTest" OFF)
if(ORES_RECEIVER_SLOT_HOST_ONLY)
    include(CTest)
    if(NOT BUILD_TESTING)
        message(FATAL_ERROR "ORES_RECEIVER_SLOT_HOST_ONLY requires BUILD_TESTING=ON")
    endif()
    add_executable(oreslang-native-receiver-slot-test
        tests/native_receiver_slot_test.cpp)
    target_include_directories(oreslang-native-receiver-slot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-receiver-slot-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-receiver-slot-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_receiver_erased_slot_host_contract
        COMMAND oreslang-native-receiver-slot-test)
    set_tests_properties(native_receiver_erased_slot_host_contract PROPERTIES TIMEOUT 10)
    return()
endif()

# Tiny dependency-free C11 actor logical-frame ABI test entrypoint.
# This runs the *real native host primitive*, not guest .atto/AOT lowering.
option(ATTO_ACTOR_LOGICAL_FRAMES_HOST_ONLY
    "Build actor-owned logical-frame native host regression without LLVM" OFF)
if(ATTO_ACTOR_LOGICAL_FRAMES_HOST_ONLY)
    include(CTest)
    find_package(Threads REQUIRED)
    add_executable(atto-native-actor-logical-frames-host-test
    tests/native_actor_logical_frames_v1_test.c
    src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c)
    target_include_directories(atto-native-actor-logical-frames-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-actor-logical-frames-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto-native-actor-logical-frames-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-actor-logical-frames-host-test PRIVATE Threads::Threads)
    add_test(NAME actor_logical_frames_v1_native_host
        COMMAND atto-native-actor-logical-frames-host-test)
    set_tests_properties(actor_logical_frames_v1_native_host PROPERTIES TIMEOUT 20)
    # Actual native frame allocator: Actor A fails its quota, B still returns
    # 42, and A restart cannot reuse prior actor-incarnation handles.
    add_executable(atto-native-actor-a-b-quota-host-test
        tests/native_actor_a_b_quota_isolation_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c)
    target_include_directories(atto-native-actor-a-b-quota-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-actor-a-b-quota-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto-native-actor-a-b-quota-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-actor-a-b-quota-host-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_a_b_frame_quota_isolation
        COMMAND atto-native-actor-a-b-quota-host-test)
    set_tests_properties(native_actor_a_b_frame_quota_isolation PROPERTIES TIMEOUT 20)

    # Uses the real typed supervisor CONTROL scheduler and actor-owned frames.
    # This is native host evidence, not a compiled .atto failure path.
    add_executable(atto-native-actor-frame-control-host-test
        tests/native_actor_frame_quota_supervisor_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(atto-native-actor-frame-control-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-actor-frame-control-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto-native-actor-frame-control-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-actor-frame-control-host-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_frame_quota_typed_supervisor_control
        COMMAND atto-native-actor-frame-control-host-test)
    set_tests_properties(native_actor_frame_quota_typed_supervisor_control PROPERTIES TIMEOUT 30)
    # Stronger host path: registered NativeOresVM child + owner finalization
    # receipt must precede priority CONTROL, with rejected forgery/replay.
    add_executable(atto-native-vm-supervised-quota-host-test
        tests/native_actor_frame_quota_vm_supervised_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c)
    target_include_directories(atto-native-vm-supervised-quota-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-vm-supervised-quota-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto-native-vm-supervised-quota-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-vm-supervised-quota-host-test PRIVATE Threads::Threads)
    add_test(NAME native_vm_registered_actor_frame_quota_supervisor
        COMMAND atto-native-vm-supervised-quota-host-test)
    set_tests_properties(native_vm_registered_actor_frame_quota_supervisor PROPERTIES TIMEOUT 30)
    add_test(NAME native_vm_supervised_leaf_million_reuse
        COMMAND atto-native-vm-supervised-quota-host-test --reuse)
    set_tests_properties(native_vm_supervised_leaf_million_reuse PROPERTIES TIMEOUT 900)

    # Compiled native callback test at the actual source-actor bootstrap ABI.
    # This is source-runtime proof, not LLVM-produced .atto guest proof.
    add_executable(atto-native-source-actor-failure-host-test
        tests/source_actor_failure_supervisor_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(atto-native-source-actor-failure-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-source-actor-failure-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_definitions(atto-native-source-actor-failure-host-test PRIVATE ORESLANG_SOURCE_ACTOR_TESTING)
    target_compile_options(atto-native-source-actor-failure-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-source-actor-failure-host-test PRIVATE Threads::Threads)
    add_test(NAME native_source_actor_failure_supervisor_host
        COMMAND atto-native-source-actor-failure-host-test)
    set_tests_properties(native_source_actor_failure_supervisor_host PROPERTIES TIMEOUT 30)

    # Root supervisor policy admits one fresh replacement only after physical
    # DONE, per-incarnation frame cleanup, and registered CONTROL publication.
    add_executable(atto-native-source-actor-restart-host-test
        tests/source_actor_one_for_one_restart_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(atto-native-source-actor-restart-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-source-actor-restart-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_definitions(atto-native-source-actor-restart-host-test PRIVATE ORESLANG_SOURCE_ACTOR_TESTING)
    target_compile_options(atto-native-source-actor-restart-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(atto-native-source-actor-restart-host-test PRIVATE Threads::Threads)
    add_test(NAME native_source_actor_one_for_one_restart_host
        COMMAND atto-native-source-actor-restart-host-test)
    set_tests_properties(native_source_actor_one_for_one_restart_host PROPERTIES TIMEOUT 30)
    add_test(NAME native_source_actor_restart_budget_exhausted_host
        COMMAND atto-native-source-actor-restart-host-test --exhaust)
    set_tests_properties(native_source_actor_restart_budget_exhausted_host PROPERTIES TIMEOUT 30)
    add_test(NAME native_source_actor_last_capacity_denial_host
        COMMAND atto-native-source-actor-restart-host-test --capacity)
    set_tests_properties(native_source_actor_last_capacity_denial_host PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_leaf_reuse_host
        COMMAND atto-native-source-actor-restart-host-test --reuse)
    set_tests_properties(native_source_actor_leaf_reuse_host PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_failed_leaf_reuse_host
        COMMAND atto-native-source-actor-restart-host-test --failed-reuse)
    set_tests_properties(native_source_actor_failed_leaf_reuse_host PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_failed_stop_reuse_host
        COMMAND atto-native-source-actor-restart-host-test --failed-stop-reuse)
    set_tests_properties(native_source_actor_failed_stop_reuse_host PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_sealed_flush_host
        COMMAND atto-native-source-actor-restart-host-test --sealed-flush)
    set_tests_properties(native_source_actor_sealed_flush_host PROPERTIES TIMEOUT 30)
    add_executable(atto-native-source-recovery-lease-test
        tests/source_recovery_lease_v1_test.c)
    target_include_directories(atto-native-source-recovery-lease-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(atto-native-source-recovery-lease-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(atto-native-source-recovery-lease-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_source_recovery_lease_host
        COMMAND atto-native-source-recovery-lease-test)
    set_tests_properties(native_source_recovery_lease_host PROPERTIES TIMEOUT 30)
    return()
endif()

find_package(LLVM CONFIG REQUIRED)
if(LLVM_VERSION_MAJOR LESS 14)
    message(FATAL_ERROR "Oreslang requires LLVM 14 or newer; found ${LLVM_PACKAGE_VERSION}")
endif()
message(STATUS "Building Oreslang LLVM against LLVM ${LLVM_PACKAGE_VERSION}")

add_executable(oreslang-llvmc
    src/main/cpp/oreslang/main.cpp
    src/main/cpp/oreslang/frontend/Parser.cpp
    src/main/cpp/oreslang/frontend/ModuleLoader.cpp
    src/main/cpp/oreslang/frontend/MethodResolver.cpp
    src/main/cpp/oreslang/types/NativeScalarTypes.cpp
    src/main/cpp/oreslang/types/OwnershipChecker.cpp
    src/main/cpp/oreslang/backend/LlvmCodegen.cpp
    src/main/cpp/oreslang/backend/ScalarLoopLowering.cpp
)

target_include_directories(oreslang-llvmc PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
    "${LLVM_INCLUDE_DIRS}"
)
add_definitions(${LLVM_DEFINITIONS})
llvm_map_components_to_libnames(ORE_LLVM_LIBS core support)
target_link_libraries(oreslang-llvmc PRIVATE ${ORE_LLVM_LIBS})

include(CTest)
if(BUILD_TESTING)

    # The same production C11 preallocated logical-frame ABI is tested in the
    # full LLVM build, but this is host-runtime proof, not guest compiler output.
    add_executable(oreslang-native-actor-logical-frames-v1-test
    tests/native_actor_logical_frames_v1_test.c
    src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c)
    target_include_directories(oreslang-native-actor-logical-frames-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-actor-logical-frames-v1-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-actor-logical-frames-v1-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-actor-logical-frames-v1-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_logical_frames_v1
        COMMAND oreslang-native-actor-logical-frames-v1-test)
    set_tests_properties(native_actor_logical_frames_v1 PROPERTIES TIMEOUT 20)
    add_executable(oreslang-native-actor-a-b-quota-test
        tests/native_actor_a_b_quota_isolation_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c)
    target_include_directories(oreslang-native-actor-a-b-quota-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-actor-a-b-quota-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-actor-a-b-quota-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-actor-a-b-quota-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_a_b_quota_sibling_42
        COMMAND oreslang-native-actor-a-b-quota-test)
    set_tests_properties(native_actor_a_b_quota_sibling_42 PROPERTIES TIMEOUT 20)
    # This runs the actual native Atto scalar parser + LLVM function IR
    # emitter. It proves checked routine metadata only, NOT an inserted
    # guest runtime site, linked AOT binary, JIT, or OTel export.
    add_executable(oreslang-native-source-log-manifest-test
        tests/native_source_log_manifest_v1_test.cpp)
    target_include_directories(oreslang-native-source-log-manifest-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-source-log-manifest-test PRIVATE
        ATTO_SOURCE_LOG_MANIFEST_FIXTURE="${CMAKE_CURRENT_SOURCE_DIR}/examples/telemetry/log-info-ids.json")
    target_compile_features(oreslang-native-source-log-manifest-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-source-log-manifest-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_source_log_manifest_admission
        COMMAND oreslang-native-source-log-manifest-test)
    add_executable(oreslang-native-telemetry-literal-test
        tests/native_telemetry_literal_v1_test.cpp)
    target_include_directories(oreslang-native-telemetry-literal-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-telemetry-literal-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-telemetry-literal-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_telemetry_literal_10k_sdk
        COMMAND oreslang-native-telemetry-literal-test)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-native-source-log-paths-test
            tests/native_source_log_paths_v1_test.cpp)
        target_include_directories(oreslang-native-source-log-paths-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-native-source-log-paths-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-native-source-log-paths-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_source_log_physical_paths_admission
            COMMAND oreslang-native-source-log-paths-test)
    endif()
    atto_add_static_source_log_ir_abi_test(
        oreslang-native-static-source-log-ir-link-test
        source_free_native_static_log_ir_link_host_only)

    add_test(NAME native_source_routine_id_compiler_ir
        COMMAND oreslang-llvmc --selftest-source-routine-ir)
    set_tests_properties(native_source_routine_id_compiler_ir PROPERTIES
        PASS_REGULAR_EXPRESSION "LLVM_SOURCE_ID_COMPILER_IR_METADATA_PASS"
        TIMEOUT 30)

    add_test(NAME native_guest_log_info_verified_llvm_ir
        COMMAND oreslang-llvmc --selftest-native-log-info-ir)
    set_tests_properties(native_guest_log_info_verified_llvm_ir PROPERTIES
        PASS_REGULAR_EXPRESSION "LLVM_NATIVE_LOG_INFO_GUEST_IR_PASS"
        TIMEOUT 30)

    # Full compiler proof: this target invokes oreslang-llvmc on the actual
    # reviewed .atto source + JSON manifest, compiles the emitted LLVM IR,
    # links the production C runtime and checks exit 42 + both real receipts.
    # It is NOT the standalone source-free IR ABI regression above.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND
       CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
        find_program(ATTO_NATIVE_LOG_GUEST_CLANG
            NAMES clang clang-20 clang-19 clang-18 clang-17)
        if(ATTO_NATIVE_LOG_GUEST_CLANG)
            add_test(NAME native_log_info_compiler_linked_guest_full_aot
                COMMAND "${CMAKE_COMMAND}"
                    "-DATTO_ROOT=${CMAKE_CURRENT_SOURCE_DIR}"
                    "-DATTO_COMPILER=$<TARGET_FILE:oreslang-llvmc>"
                    "-DATTO_CLANG=${ATTO_NATIVE_LOG_GUEST_CLANG}"
                    "-DATTO_WORK=${CMAKE_CURRENT_BINARY_DIR}/native-log-guest-check"
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_guest_log_info_full_aot.cmake")
            set_tests_properties(native_log_info_compiler_linked_guest_full_aot PROPERTIES
                PASS_REGULAR_EXPRESSION "LLVM_NATIVE_LOG_INFO_LINKED_ATTO_GUEST_PASS:42"
                TIMEOUT 45)
        endif()
    endif()

    add_executable(oreslang-native-actor-frame-control-test
        tests/native_actor_frame_quota_supervisor_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-frame-control-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-actor-frame-control-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-actor-frame-control-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-actor-frame-control-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_frame_quota_typed_supervisor_e2e
        COMMAND oreslang-native-actor-frame-control-test)
    set_tests_properties(native_actor_frame_quota_typed_supervisor_e2e PROPERTIES TIMEOUT 30)
    add_executable(oreslang-native-vm-supervised-quota-test
        tests/native_actor_frame_quota_vm_supervised_test.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c)
    target_include_directories(oreslang-native-vm-supervised-quota-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-vm-supervised-quota-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-vm-supervised-quota-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-vm-supervised-quota-test PRIVATE Threads::Threads)
    add_test(NAME native_vm_registered_actor_quota_failure_control
        COMMAND oreslang-native-vm-supervised-quota-test)
    set_tests_properties(native_vm_registered_actor_quota_failure_control PROPERTIES TIMEOUT 30)
    add_test(NAME native_vm_supervised_leaf_million_reuse
        COMMAND oreslang-native-vm-supervised-quota-test --reuse)
    set_tests_properties(native_vm_supervised_leaf_million_reuse PROPERTIES TIMEOUT 900)


    # Compiled native callback test at the actual source-actor bootstrap ABI.
    # This is source-runtime proof, not LLVM-produced .atto guest proof.
    add_executable(oreslang-native-source-actor-failure-test
        tests/source_actor_failure_supervisor_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-native-source-actor-failure-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-source-actor-failure-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_definitions(oreslang-native-source-actor-failure-test PRIVATE ORESLANG_SOURCE_ACTOR_TESTING)
    target_compile_options(oreslang-native-source-actor-failure-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-source-actor-failure-test PRIVATE Threads::Threads)
    add_test(NAME native_source_actor_failure_supervisor_bridge
        COMMAND oreslang-native-source-actor-failure-test)
    set_tests_properties(native_source_actor_failure_supervisor_bridge PROPERTIES TIMEOUT 30)

    # Root supervisor policy admits one fresh replacement only after physical
    # DONE, per-incarnation frame cleanup, and registered CONTROL publication.
    add_executable(oreslang-native-source-actor-restart-test
        tests/source_actor_one_for_one_restart_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-native-source-actor-restart-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-source-actor-restart-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_definitions(oreslang-native-source-actor-restart-test PRIVATE ORESLANG_SOURCE_ACTOR_TESTING)
    target_compile_options(oreslang-native-source-actor-restart-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-source-actor-restart-test PRIVATE Threads::Threads)
    add_test(NAME native_source_actor_one_for_one_restart_e2e
        COMMAND oreslang-native-source-actor-restart-test)
    set_tests_properties(native_source_actor_one_for_one_restart_e2e PROPERTIES TIMEOUT 30)
    add_test(NAME native_source_actor_restart_budget_exhausted_e2e
        COMMAND oreslang-native-source-actor-restart-test --exhaust)
    set_tests_properties(native_source_actor_restart_budget_exhausted_e2e PROPERTIES TIMEOUT 30)
    add_test(NAME native_source_actor_leaf_reuse_e2e
        COMMAND oreslang-native-source-actor-restart-test --reuse)
    set_tests_properties(native_source_actor_leaf_reuse_e2e PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_failed_leaf_reuse_e2e
        COMMAND oreslang-native-source-actor-restart-test --failed-reuse)
    set_tests_properties(native_source_actor_failed_leaf_reuse_e2e PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_failed_stop_reuse_e2e
        COMMAND oreslang-native-source-actor-restart-test --failed-stop-reuse)
    set_tests_properties(native_source_actor_failed_stop_reuse_e2e PROPERTIES TIMEOUT 60)
    add_test(NAME native_source_actor_sealed_flush_e2e
        COMMAND oreslang-native-source-actor-restart-test --sealed-flush)
    set_tests_properties(native_source_actor_sealed_flush_e2e PROPERTIES TIMEOUT 30)
    # Static byte/bit/packet match plan host proof, NOT compiled .atto guest AOT.
    add_executable(oreslang-atto-byte-bit-pattern-plan-test
        tests/atto_byte_bit_pattern_plan_test.cpp)
    target_include_directories(oreslang-atto-byte-bit-pattern-plan-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-atto-byte-bit-pattern-plan-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-atto-byte-bit-pattern-plan-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_byte_bit_plan
        COMMAND oreslang-atto-byte-bit-pattern-plan-test)
    set_tests_properties(native_atto_byte_bit_plan PROPERTIES TIMEOUT 15)

    # Compiler import-string boundary; host-only tests must not be reported as
    # guest linking parity. Native source execution is separately gated by
    # the native_module_imports compiler + clang + guest executable harness.
    add_executable(oreslang-native-import-path-variables-test
        tests/import_path_variables_test.cpp)
    target_include_directories(oreslang-native-import-path-variables-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-import-path-variables-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_import_path_variables_host_only
        COMMAND oreslang-native-import-path-variables-test)
    set_tests_properties(native_import_path_variables_host_only PROPERTIES TIMEOUT 15)

    # Real binary-safe native String.contains_literal implementation with
    # embedded NUL, bounds, empty needle, and fail-closed invalid handles.
    # This C runtime ABI test is distinct from source-to-native Atto execution.
    add_executable(oreslang-native-string-contains-literal-test
        tests/native_string_contains_literal_test.c
        src/main/cpp/oreslang/runtime/NativeStringRegion.c)
    target_include_directories(oreslang-native-string-contains-literal-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-string-contains-literal-test PRIVATE pthread)
    target_compile_options(oreslang-native-string-contains-literal-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_string_contains_literal_binary_unit
        COMMAND oreslang-native-string-contains-literal-test)
    set_tests_properties(native_string_contains_literal_binary_unit PROPERTIES TIMEOUT 20)
    # Real native String-region + checked owned Array<String> cross-boundary
    # runtime. This C ABI test is in addition to source/LLVM/Clang guests.
    add_executable(oreslang-native-string-split-test
        tests/native_string_split_test.c
        src/main/cpp/oreslang/runtime/NativeStringSplit.c
        src/main/cpp/oreslang/runtime/NativeStringRegion.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-string-split-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-string-split-test PRIVATE pthread)
    target_compile_options(oreslang-native-string-split-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_string_split_owned_array_region
        COMMAND oreslang-native-string-split-test)
    set_tests_properties(native_string_split_owned_array_region PROPERTIES TIMEOUT 30)
    # Host-only receiver-erased method selector. No LLVM guest execution is
    # implied by this standalone C++ contract test.
    add_executable(oreslang-native-receiver-slot-test
        tests/native_receiver_slot_test.cpp)
    target_include_directories(oreslang-native-receiver-slot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-receiver-slot-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_receiver_erased_slot_host_contract
        COMMAND oreslang-native-receiver-slot-test)
    set_tests_properties(native_receiver_erased_slot_host_contract PROPERTIES TIMEOUT 10)
    # Test-only Linux two-process local socket and EOF crash ambiguity.
    # The test fixture does NOT authenticate a remote actor/peer.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-native-ipc-two-process-test
            tests/native_ipc_receipt_two_process_test.cpp)
        target_include_directories(oreslang-native-ipc-two-process-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_options(oreslang-native-ipc-two-process-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        add_test(NAME native_ipc_two_process_eof_and_session_retirement
            COMMAND oreslang-native-ipc-two-process-test)
        set_tests_properties(native_ipc_two_process_eof_and_session_retirement
            PROPERTIES TIMEOUT 25)
    endif()
    # Native host-only IPC receipt ledger (not source compiler parity or
    # remote-peer authentication). Execute strict transitions and carrier races.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-ipc-receipts-test
        tests/native_ipc_receipt_table_test.cpp)
    target_include_directories(oreslang-native-ipc-receipts-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-ipc-receipts-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-ipc-receipts-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_ipc_receipt_bounded_correlation_and_races
        COMMAND oreslang-native-ipc-receipts-test)
    set_tests_properties(native_ipc_receipt_bounded_correlation_and_races
        PROPERTIES TIMEOUT 25)
    # Deliberate std::bad_alloc injection during the retirement snapshot:
    # either all correlations remain, or every retired identity is returned.
    add_executable(oreslang-native-ipc-loss-failpoint-test
        tests/native_ipc_receipt_loss_failpoint_test.cpp)
    target_include_directories(oreslang-native-ipc-loss-failpoint-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-ipc-loss-failpoint-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-ipc-loss-failpoint-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_ipc_loss_retirement_allocation_failure
        COMMAND oreslang-native-ipc-loss-failpoint-test)
    set_tests_properties(native_ipc_loss_retirement_allocation_failure
        PROPERTIES TIMEOUT 25)

    # Source-free compiler-negative harness mutation scenarios. A passing
    # synthetic compiler proves only that malformed receipts are rejected;
    # it cannot promote actual llvmc or physical GPU execution.
    if(UNIX)
        add_test(NAME native_atto_gpu_kernel_link_negative_evidence_source_free
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_kernel_source_link_harness_selftest.sh")
        set_tests_properties(native_atto_gpu_kernel_link_negative_evidence_source_free
            PROPERTIES TIMEOUT 90)
    endif()

    # Compiler-checked restricted SAXPY source-body HIR, not GPU execution.
    add_executable(oreslang-native-gpu-kernel-body-hir-test
        tests/gpu_kernel_body_hir_test.cpp)
    target_include_directories(oreslang-native-gpu-kernel-body-hir-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-gpu-kernel-body-hir-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-gpu-kernel-body-hir-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_kernel_body_hir_host_only
        COMMAND oreslang-native-gpu-kernel-body-hir-test)
    set_tests_properties(native_atto_gpu_kernel_body_hir_host_only
        PROPERTIES TIMEOUT 30)

    # Source-declared GPU kernel types -> typed kernel signature *shape*.
    # Does not resolve host values or yield device execution permissions.
    add_executable(oreslang-native-gpu-kernel-source-link-test
        tests/gpu_kernel_source_link_test.cpp)
    target_include_directories(oreslang-native-gpu-kernel-source-link-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-gpu-kernel-source-link-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-gpu-kernel-source-link-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_kernel_source_link_host_only
        COMMAND oreslang-native-gpu-kernel-source-link-test)
    set_tests_properties(native_atto_gpu_kernel_source_link_host_only
        PROPERTIES TIMEOUT 30)

    # Actual oreslang-llvmc invocation: 13 source-only negative cases and no
    # emitted LLVM IR/artifact. This is NOT physical GPU execution.
    add_test(NAME native_atto_gpu_kernel_source_link_compiler_negative
        COMMAND "${CMAKE_COMMAND}"
            "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/atto-gpu-kernel-source-link"
            "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_gpu_kernel_source_link_admission.cmake")
    set_tests_properties(native_atto_gpu_kernel_source_link_compiler_negative
        PROPERTIES TIMEOUT 230)

    # Adversarial host-only source MIR mutation and negative-evidence tests.
    # These do NOT run native llvmc or a physical GPU.
    add_executable(oreslang-native-gpu-source-mir-adversarial-test
        tests/gpu_source_mir_adversarial_test.cpp)
    target_include_directories(oreslang-native-gpu-source-mir-adversarial-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-gpu-source-mir-adversarial-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-gpu-source-mir-adversarial-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_source_mir_adversarial_host_only
        COMMAND oreslang-native-gpu-source-mir-adversarial-test)
    set_tests_properties(native_atto_gpu_source_mir_adversarial_host_only
        PROPERTIES TIMEOUT 30)
    if(UNIX)
        add_test(NAME native_atto_gpu_negative_evidence_harness_source_free
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_native_negative_harness_selftest.sh")
        set_tests_properties(native_atto_gpu_negative_evidence_harness_source_free
            PROPERTIES TIMEOUT 30)
    endif()

    # Native frontend-source MIR is deliberately non-executable; the actual
    # compiler admission test below also verifies source diagnostics.
    add_executable(oreslang-native-gpu-source-mir-test
        tests/gpu_source_launch_mir_test.cpp)
    target_include_directories(oreslang-native-gpu-source-mir-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-gpu-source-mir-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-gpu-source-mir-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_source_mir_host_only
        COMMAND oreslang-native-gpu-source-mir-test)
    set_tests_properties(native_atto_gpu_source_mir_host_only
        PROPERTIES TIMEOUT 20)

    # Host-only typed GPU IR v1: checks ordered ABI arguments, device effects,
    # queue scope, byte extents and mutable-loan aliasing. No physical dispatch.
    add_executable(oreslang-native-gpu-typed-launch-ir-test
        tests/gpu_typed_launch_ir_test.cpp)
    target_include_directories(oreslang-native-gpu-typed-launch-ir-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-gpu-typed-launch-ir-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-gpu-typed-launch-ir-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_typed_launch_host_only
        COMMAND oreslang-native-gpu-typed-launch-ir-test)
    set_tests_properties(native_atto_gpu_typed_launch_host_only
        PROPERTIES TIMEOUT 30)

    # AttoLang native launch spelling: exact compiler-backed scalar control and
    # fail-closed GPU admission, not executed kernels or hardware parity.
    add_test(NAME native_atto_gpu_explicit_launch_source_admission
        COMMAND "${CMAKE_COMMAND}"
            "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/atto-gpu-launch-admission"
            "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_gpu_explicit_launch_admission.cmake")
    set_tests_properties(native_atto_gpu_explicit_launch_source_admission
        PROPERTIES TIMEOUT 75)

    # Standalone native geometry model: a host-only checked-arithmetic test,
    # not a physical device launch or kernel execution claim.
    add_executable(oreslang-native-gpu-launch-geometry-test
        tests/gpu_explicit_launch_geometry_test.cpp)
    target_include_directories(oreslang-native-gpu-launch-geometry-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-gpu-launch-geometry-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_atto_gpu_launch_geometry_host_only
        COMMAND oreslang-native-gpu-launch-geometry-test)
    set_tests_properties(native_atto_gpu_launch_geometry_host_only
        PROPERTIES TIMEOUT 20)

    # An actual C11 native state gate, shared with LLVM's future typed
    # handle_state lowering. This is HOST execution, not guest .atto dispatch.
    add_executable(oreslang-declared-actor-critical-counter-test
        tests/native_declared_actor_critical_counter_gate_test.c)
    set_target_properties(oreslang-declared-actor-critical-counter-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-declared-actor-critical-counter-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-declared-actor-critical-counter-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_declared_actor_critical_counter_host_state_gate
        COMMAND oreslang-declared-actor-critical-counter-test)
    set_tests_properties(native_declared_actor_critical_counter_host_state_gate
        PROPERTIES TIMEOUT 20)

    # The first cross-backend namespace-header AOT receipt executes actual
    # guest-generated LLVM IR with Clang. Declared actor source remains a
    # separate strict negative codegen test until mailbox/loan MIR exists.
    find_program(ORE_DECLARED_ACTOR_CLANG NAMES clang-20 clang-19 clang-18 clang-17 clang clang-16 clang-15 clang-14)
    if(ORE_DECLARED_ACTOR_CLANG)
        add_test(NAME native_use_namespace_scalar_aot_and_actor_codegen_gate
            COMMAND "${CMAKE_COMMAND}"
                "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DCLANG=${ORE_DECLARED_ACTOR_CLANG}"
                "-DPOSITIVE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/use-namespace-llvm-scalar-main.atto"
                "-DARITHMETIC=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/use-namespace-scalar-arithmetic.atto"
                "-DNEGATIVE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/declared-actor-use-namespace-reject.atto"
                "-DBAD_NONLITERAL=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/use-namespace-nonliteral-reject.atto"
                "-DBAD_MULTIFUNCTION=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/use-namespace-multifunction-reject.atto"
                "-DSOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/declared-actor-namespace-v1"
                "-DRECEIVE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/declared-actor-namespace-v1/receive-canonical.atto"
                "-DOUTPUT_DIR=${CMAKE_CURRENT_BINARY_DIR}/declared-actor-native-aot"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/native_declared_actor_llvm_frontend_gate.cmake")
        set_tests_properties(native_use_namespace_scalar_aot_and_actor_codegen_gate
            PROPERTIES TIMEOUT 90)
    endif()

    # Pure host C++17 hashing/path admission unit. It can run without LLVM
    # emission and is NEVER a source-language actor execution receipt.
    add_executable(oreslang-actor-source-set-admission-unit
        tests/native_actor_source_set_integrity_unit_test.cpp)
    target_include_directories(oreslang-actor-source-set-admission-unit PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-source-set-admission-unit PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_actor_source_set_git_blob_and_paths_host_only
        COMMAND oreslang-actor-source-set-admission-unit)
    set_tests_properties(native_actor_source_set_git_blob_and_paths_host_only
        PROPERTIES TIMEOUT 15)

    # Authenticated manifest + all seven materialized .atto sources must pass
    # through the actual compiler binary. Explicit verification-only mode
    # has NO AST/IR/actor execution authority. Node only materializes the
    # already-sha-pinned canonical fixture and is not a guest interpreter.
    find_program(ORE_COUNTER_NODE NAMES node nodejs)
    # The symlink-negative source-set test requires POSIX symlink semantics.
    if(ORE_COUNTER_NODE AND UNIX)
        add_test(NAME native_actor_counter_source_set_integrity_only
            COMMAND "${CMAKE_COMMAND}"
                "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DNODE=${ORE_COUNTER_NODE}"
                "-DTOOL=${CMAKE_CURRENT_SOURCE_DIR}/tools/actor-counter-parity.mjs"
                "-DMANIFEST=${CMAKE_CURRENT_SOURCE_DIR}/contracts/actor-counter-identical-source-v1/actor-source-set.v1"
                "-DEXPECTED_SHA=e4fd0afea3d21e6fed54f9943abbf0851550e028"
                "-DOUTPUT_DIR=${CMAKE_CURRENT_BINARY_DIR}/actor-source-set-admission"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/native_actor_source_set_integrity_only.cmake")
        set_tests_properties(native_actor_counter_source_set_integrity_only
            PROPERTIES TIMEOUT 60)
    endif()

    # Fixed-budget, byte-exact Map<Bytes,Bytes> substrate. C++ host regression,
    # not compiler-lowered .atto or authenticated typed mailbox dispatch.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-bytes-map-test
        tests/native_actor_bytes_map_test.cpp)
    target_include_directories(oreslang-native-actor-bytes-map-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-bytes-map-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-bytes-map-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-actor-bytes-map-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_owned_bytes_map_host_only
        COMMAND oreslang-native-actor-bytes-map-test)
    set_tests_properties(native_actor_owned_bytes_map_host_only
        PROPERTIES TIMEOUT 60)
    # Only the full compiler build can check that still-unsupported guest
    # typed Map<Bytes,Bytes> actor source fails before any LLVM IR emission.
    add_test(NAME native_actor_bytes_map_typed_source_fail_closed
        COMMAND "${CMAKE_COMMAND}"
            "-DORE_BYTES_MAP_COMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DORE_BYTES_MAP_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-actor-bytes-map-target.atto"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_actor_bytes_map_source_negative.cmake")
    set_tests_properties(native_actor_bytes_map_typed_source_fail_closed
        PROPERTIES TIMEOUT 30)
    add_test(NAME native_actor_bytes_map_scalar_atto_baseline
        COMMAND "${CMAKE_COMMAND}"
            "-DORE_ACTOR_LLVM_CLI=$<TARGET_FILE:oreslang-llvmc>"
            "-DORE_ACTOR_SCALAR_ATTO=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-actor-redis-scalar-baseline.atto"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_actor_bytes_map_source_baseline.cmake")
    set_tests_properties(native_actor_bytes_map_scalar_atto_baseline
        PROPERTIES TIMEOUT 40)
    # Compiler-linked API regression: genuine LLVM 19/20+ intrinsic declaration
    # selection, including overloaded checked-i64 arithmetic and trap.
    # It is host LLVM API evidence; NOT AttoLang guest-execution parity.
    add_executable(atto-llvm-intrinsic-compat-test
        tests/llvm_intrinsic_compat_api_test.cpp)
    target_include_directories(atto-llvm-intrinsic-compat-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    # SYSTEM: -Werror below is for this test, not for warnings in LLVM headers.
    target_include_directories(atto-llvm-intrinsic-compat-test SYSTEM PRIVATE
        "${LLVM_INCLUDE_DIRS}")
    target_link_libraries(atto-llvm-intrinsic-compat-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_options(atto-llvm-intrinsic-compat-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_llvm_intrinsic_api_compat
        COMMAND atto-llvm-intrinsic-compat-test)
    set_tests_properties(native_llvm_intrinsic_api_compat PROPERTIES TIMEOUT 15)
    # Native CUDA CUBIN AOT admission only: host tests with a fake driver,
    # not evidence of physical-GPU execution or AttoLang guest lowering.
    add_executable(oreslang-cuda-cubin-aot-test tests/cuda_cubin_aot_test.cpp)
    target_include_directories(oreslang-cuda-cubin-aot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-cuda-cubin-aot-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME cuda_cubin_aot_nojit_admission_host_only
        COMMAND oreslang-cuda-cubin-aot-test)
    set_tests_properties(cuda_cubin_aot_nojit_admission_host_only
        PROPERTIES TIMEOUT 20)

    add_executable(oreslang-cuda-cubin-driver-smoke-test
        tests/cuda_cubin_system_smoke_test.cpp)
    target_include_directories(oreslang-cuda-cubin-driver-smoke-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-cuda-cubin-driver-smoke-test PRIVATE
        ${CMAKE_DL_LIBS})
    target_compile_options(oreslang-cuda-cubin-driver-smoke-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME cuda_cubin_driver_context_gate_host_only
        COMMAND oreslang-cuda-cubin-driver-smoke-test)
    set_tests_properties(cuda_cubin_driver_context_gate_host_only
        PROPERTIES TIMEOUT 20 SKIP_RETURN_CODE 77)
    # SOURCE-FREE LLVM native metadata planner for recursively nested actor
    # pipelines. This is not .atto parsing, LLVM guest execution, actor
    # startup, message transport or supervision parity.
    add_executable(oreslang-native-nested-actor-pipe-plan-test
        tests/native_nested_actor_pipeline_plan_test.cpp)
    target_include_directories(oreslang-native-nested-actor-pipe-plan-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-nested-actor-pipe-plan-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-nested-actor-pipe-plan-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_nested_actor_pipe_plan_host_only
        COMMAND oreslang-native-nested-actor-pipe-plan-test)
    set_tests_properties(native_nested_actor_pipe_plan_host_only
        PROPERTIES TIMEOUT 15)

    find_package(Threads REQUIRED)
    # Host-only wiring of native WAL retirement with AsyncIoActorLifetimePin
    # and NativeActorRetirementBarrier. NOT compiled Oreslang guest evidence.
    add_executable(oreslang-native-db-wal-physical-pin-integration-test
        tests/native_db_wal_physical_pin_integration_test.cpp)
    target_include_directories(oreslang-native-db-wal-physical-pin-integration-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-db-wal-physical-pin-integration-test
        PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-db-wal-physical-pin-integration-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_physical_pin_integration_host_only
        COMMAND oreslang-native-db-wal-physical-pin-integration-test)
    set_tests_properties(native_db_wal_physical_pin_integration_host_only
        PROPERTIES TIMEOUT 30)
    # Host-only integration of the context/shard/actor/generation grant
    # with the physical WAL write, fdatasync and drain fence.
    add_executable(oreslang-native-db-wal-authorized-writer-test
        tests/native_db_wal_authorized_writer_test.cpp)
    target_include_directories(oreslang-native-db-wal-authorized-writer-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-db-wal-authorized-writer-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-db-wal-authorized-writer-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_authorized_writer_host_only
        COMMAND oreslang-native-db-wal-authorized-writer-test)
    set_tests_properties(native_db_wal_authorized_writer_host_only PROPERTIES TIMEOUT 30)
    # Host-only bounded ODB1 restart/replay proof. The scanner never
    # truncates, does not publish recovered state, and is not a guest ABI.
    add_executable(oreslang-native-db-wal-replay-v1-test
        tests/native_db_wal_replay_v1_test.cpp)
    target_include_directories(oreslang-native-db-wal-replay-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-db-wal-replay-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_replay_v1_host_only
        COMMAND oreslang-native-db-wal-replay-v1-test)
    set_tests_properties(native_db_wal_replay_v1_host_only PROPERTIES TIMEOUT 30)
    # Trusted host-only validated ODB1 append/fdatasync and process reopen.
    # No guest authority, commit ACK or physical power-cut proof.
    add_executable(oreslang-native-db-wal-validated-append-test
        tests/native_db_wal_validated_append_test.cpp)
    target_include_directories(oreslang-native-db-wal-validated-append-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-db-wal-validated-append-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_validated_append_host_only
        COMMAND oreslang-native-db-wal-validated-append-test)
    set_tests_properties(native_db_wal_validated_append_host_only PROPERTIES TIMEOUT 30)
    # Host-only ODB1 v1 frame integrity and shard identity checks.
    # This is not authenticated guest WAL append or durable ACK evidence.
    add_executable(oreslang-native-db-wal-frame-v1-test
        tests/native_db_wal_frame_v1_test.cpp)
    target_include_directories(oreslang-native-db-wal-frame-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-db-wal-frame-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_frame_v1_host_only
        COMMAND oreslang-native-db-wal-frame-v1-test)
    set_tests_properties(native_db_wal_frame_v1_host_only PROPERTIES TIMEOUT 30)
    # Bounded, allocator-free host WAL shard authority model. This does
    # not authenticate guest tenant credentials or grant fsync/ACK authority.
    add_executable(oreslang-native-db-wal-shard-grant-test
        tests/native_db_wal_shard_grant_registry_test.cpp)
    target_include_directories(oreslang-native-db-wal-shard-grant-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-db-wal-shard-grant-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-db-wal-shard-grant-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_shard_grant_host_only
        COMMAND oreslang-native-db-wal-shard-grant-test)
    set_tests_properties(native_db_wal_shard_grant_host_only PROPERTIES TIMEOUT 30)
    # Host-only generation-fenced WAL retirement model, not guest I/O or
    # a durable commit receipt. Keep as a separate host evidence class.
    add_executable(oreslang-native-db-wal-owner-retirement-test
        tests/native_db_wal_owner_retirement_test.cpp)
    target_include_directories(oreslang-native-db-wal-owner-retirement-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-db-wal-owner-retirement-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-db-wal-owner-retirement-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_db_wal_owner_physical_retirement_host_only
        COMMAND oreslang-native-db-wal-owner-retirement-test)
    set_tests_properties(native_db_wal_owner_physical_retirement_host_only
        PROPERTIES TIMEOUT 30)
    # Host-only POSIX storage substrate, NOT source guest or durable DB proof.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux" OR APPLE)
        add_executable(oreslang-native-db-durable-wal-host-test
            tests/native_db_durable_wal_file_test.cpp)
        target_include_directories(oreslang-native-db-durable-wal-host-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_options(oreslang-native-db-durable-wal-host-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        add_test(NAME native_db_durable_wal_host_only
            COMMAND oreslang-native-db-durable-wal-host-test)
        set_tests_properties(native_db_durable_wal_host_only PROPERTIES TIMEOUT 20)
    endif()
    # Source-only package-local multi-file namespace identity proof.
    # This does NOT accept, link or execute Oreslang import ns guest code.
    add_executable(oreslang-native-namespace-package-index-test
        tests/native_package_namespace_graph_test.cpp)
    target_include_directories(oreslang-native-namespace-package-index-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-namespace-package-index-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_namespace_package_identity_source_only
        COMMAND oreslang-native-namespace-package-index-test)
    set_tests_properties(native_namespace_package_identity_source_only PROPERTIES TIMEOUT 15)

    # ACTUAL production Parser.cpp boundary, not the source-only graph's
    # synthetic Program fixtures. No native namespace guest/IR is enabled.
    add_executable(oreslang-native-verified-namespace-parser-v2-test
        tests/native_verified_namespace_parser_v2_test.cpp
        src/main/cpp/oreslang/frontend/Parser.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp
        src/main/cpp/oreslang/types/NativeScalarTypes.cpp
        src/main/cpp/oreslang/types/OwnershipChecker.cpp)
    target_include_directories(oreslang-native-verified-namespace-parser-v2-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
        "${LLVM_INCLUDE_DIRS}")
    target_link_libraries(oreslang-native-verified-namespace-parser-v2-test PRIVATE
        ${ORE_LLVM_LIBS})
    target_compile_options(oreslang-native-verified-namespace-parser-v2-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_verified_namespace_parser_v2_host_only
        COMMAND oreslang-native-verified-namespace-parser-v2-test)
    set_tests_properties(native_verified_namespace_parser_v2_host_only
        PROPERTIES TIMEOUT 30)

    # G1 typed-device-IR prototype only: NOT parsed .atto guest, PTX, or GPU
    # execution. Keep independent of the native host scalar compiler.
    add_executable(oreslang-gpu-g1-typed-saxpy-ir-test
        tests/gpu_g1_nvptx_saxpy_ir_test.cpp)
    target_include_directories(oreslang-gpu-g1-typed-saxpy-ir-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-g1-typed-saxpy-ir-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_gpu_g1_typed_nvptx_saxpy_ir_host_only
        COMMAND oreslang-gpu-g1-typed-saxpy-ir-test)
    set_tests_properties(native_gpu_g1_typed_nvptx_saxpy_ir_host_only
        PROPERTIES TIMEOUT 20)

    # First positive, *restricted* source compiler island: one canonical
    # .atto SAXPY kernel. It cannot admit host launch or arbitrary GPU source.
    add_executable(oreslang-gpu-g1-atto-saxpy-llvmc
        tests/gpu_g1_atto_saxpy_cli.cpp)
    target_include_directories(oreslang-gpu-g1-atto-saxpy-llvmc PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-g1-atto-saxpy-llvmc PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_executable(oreslang-gpu-g1-atto-source-test
        tests/gpu_g1_atto_saxpy_source_test.cpp)
    target_include_directories(oreslang-gpu-g1-atto-source-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-g1-atto-source-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    # Typed source-alpha-renaming and 71 adversarial source mutations.
    # Independently compiled and run; no CUDA/PTX hardware claim.
    add_executable(oreslang-gpu-g1-typed-frontend-matrix-test
        tests/gpu_g1_typed_frontend_matrix_test.cpp)
    target_include_directories(oreslang-gpu-g1-typed-frontend-matrix-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-g1-typed-frontend-matrix-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_gpu_g1_typed_frontend_alpha_rename_negative_host_only
        COMMAND oreslang-gpu-g1-typed-frontend-matrix-test)
    set_tests_properties(native_gpu_g1_typed_frontend_alpha_rename_negative_host_only
        PROPERTIES TIMEOUT 20 LABELS "restricted-atto;negative-mutation;host-only")

    add_test(NAME native_gpu_g1_restricted_atto_source_host_only
        COMMAND oreslang-gpu-g1-atto-source-test)
    set_tests_properties(native_gpu_g1_restricted_atto_source_host_only
        PROPERTIES TIMEOUT 20 LABELS "restricted-atto;host-only")

    # Host-only structural admission, distinct from authentic provider/device authority.
    add_executable(oreslang-gpu-g1-launch-admission-test
        tests/gpu_g1_saxpy_launch_admission_test.cpp)
    target_include_directories(oreslang-gpu-g1-launch-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-g1-launch-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_gpu_g1_structural_buffer_loan_host_only
        COMMAND oreslang-gpu-g1-launch-admission-test)
    set_tests_properties(native_gpu_g1_structural_buffer_loan_host_only
        PROPERTIES TIMEOUT 20)

    # Independent source-free identity mutation tests for the physical
    # receipt script: no actual PTX or CUDA claims are permitted here.
    if(UNIX)
        find_program(ATTO_GPU_G1_GIT NAMES git)
        if(ATTO_GPU_G1_GIT)
            add_test(NAME native_gpu_g1_receipt_toctou_negative_host_only
                COMMAND "${CMAKE_COMMAND}"
                    "-DG1_NEGATIVE_GIT=${ATTO_GPU_G1_GIT}"
                    "-DG1_NEGATIVE_GATE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_cuda_hardware_required.cmake"
                    "-DG1_NEGATIVE_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-receipt-negative"
                    "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_receipt_toctou_negative.cmake")
            set_tests_properties(native_gpu_g1_receipt_toctou_negative_host_only
                PROPERTIES TIMEOUT 30 LABELS "host-only;negative-mutation;not-device-evidence")
        endif()
    endif()

    # Actual NVPTX llc translation, not a physical device or .atto guest test.
    option(ATTO_GPU_G1_REQUIRE_NVPTX "Require an installed NVPTX-capable llc" OFF)
    find_program(ATTO_GPU_G1_LLC NAMES llc-21 llc-20 llc-19 llc-18 llc-17 llc)
    if(ATTO_GPU_G1_REQUIRE_NVPTX AND NOT ATTO_GPU_G1_LLC)
        message(FATAL_ERROR "GPU-G1-PTX-001: real llc is required")
    endif()
    if(ATTO_GPU_G1_LLC)
        add_test(NAME native_gpu_g1_llc_ptx_no_device_claim
            COMMAND "${CMAKE_COMMAND}"
                "-DG1_EMITTER=$<TARGET_FILE:oreslang-gpu-g1-typed-saxpy-ir-test>"
                "-DG1_LLC=${ATTO_GPU_G1_LLC}"
                "-DG1_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-ptx-proof"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_nvptx_ptx_compile.cmake")
        set_tests_properties(native_gpu_g1_llc_ptx_no_device_claim PROPERTIES TIMEOUT 30)
        # Separate source-backed PTX gate: it must consume .atto source,
        # not the prebuilt typed-IR emitter, to support guest-source claims.
        add_test(NAME native_gpu_g1_llc_ptx_from_restricted_atto
            COMMAND "${CMAKE_COMMAND}"
                "-DG1_EMITTER=$<TARGET_FILE:oreslang-llvmc>"
                "-DG1_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/gpu-g1-saxpy.atto"
                "-DG1_SOURCE_FLAG=--gpu-g1-kernel-ir"
                "-DG1_REQUIRE_ATTO_SOURCE=ON"
                "-DG1_LLC=${ATTO_GPU_G1_LLC}"
                "-DG1_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-source-ptx-proof"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_nvptx_ptx_compile.cmake")
        set_tests_properties(native_gpu_g1_llc_ptx_from_restricted_atto
            PROPERTIES TIMEOUT 30 LABELS "restricted-atto;requires-nvptx;not-device-evidence")
    endif()

    # This lane is STRICTLY OPT-IN. It cannot skip or pass on hosts without
    # CUDA Driver API, an NVPTX-capable llc, actual GPU hardware and exact head.
    option(ATTO_GPU_G1_REQUIRE_PHYSICAL "Require physical CUDA G1 proof; fail rather than skip" OFF)
    set(ATTO_GPU_G1_EXACT_HEAD_SHA "" CACHE STRING
        "Immutable PR head SHA required for physical CUDA G1 evidence")
    if(ATTO_GPU_G1_REQUIRE_PHYSICAL)
        if(NOT ATTO_GPU_G1_LLC)
            message(FATAL_ERROR "GPU-G1-HW: real NVPTX llc is required")
        endif()
        if(ATTO_GPU_G1_EXACT_HEAD_SHA STREQUAL "")
            message(FATAL_ERROR "GPU-G1-HW: exact PR head SHA must be supplied")
        endif()
        find_package(Git REQUIRED)
        find_package(CUDAToolkit REQUIRED)
        add_executable(oreslang-gpu-g1-cuda-physical-probe
            tests/gpu_g1_cuda_physical_probe.cpp)
        target_link_libraries(oreslang-gpu-g1-cuda-physical-probe PRIVATE
            CUDA::cuda_driver)
        target_compile_options(oreslang-gpu-g1-cuda-physical-probe PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        add_test(NAME native_gpu_g1_physical_cuda_hardware_required
            COMMAND "${CMAKE_COMMAND}"
                "-DG1_GIT=${GIT_EXECUTABLE}"
                "-DG1_REPO=${CMAKE_CURRENT_SOURCE_DIR}"
                "-DG1_HEAD=${ATTO_GPU_G1_EXACT_HEAD_SHA}"
                "-DG1_EMITTER=$<TARGET_FILE:oreslang-llvmc>"
                "-DG1_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/gpu-g1-saxpy.atto"
                "-DG1_SOURCE_FLAG=--gpu-g1-kernel-ir"
                "-DG1_LLC=${ATTO_GPU_G1_LLC}"
                "-DG1_PROBE=$<TARGET_FILE:oreslang-gpu-g1-cuda-physical-probe>"
                "-DG1_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-physical-proof"
                "-DG1_PTX_GATE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_nvptx_ptx_compile.cmake"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_cuda_hardware_required.cmake")
        set_tests_properties(native_gpu_g1_physical_cuda_hardware_required
            PROPERTIES TIMEOUT 90 LABELS "physical-gpu;requires-nvidia;not-atto-guest")
    endif()

    # Actual LLVM IR parser and deliberately broken-SSA mutation, never
    # conflated with PTX generation or a physical CUDA launch.
    find_program(ATTO_GPU_G1_CLANG NAMES
        clang-21 clang-20 clang-19 clang-18 clang-17 clang)
    if(ATTO_GPU_G1_CLANG)
        add_test(NAME native_gpu_g1_llvm_ir_parse_and_mutation_host_only
            COMMAND "${CMAKE_COMMAND}"
                "-DG1_EMITTER=$<TARGET_FILE:oreslang-gpu-g1-typed-saxpy-ir-test>"
                "-DG1_CLANG=${ATTO_GPU_G1_CLANG}"
                "-DG1_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-ir-proof"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_nvptx_llvm_ir_verify.cmake")
        set_tests_properties(native_gpu_g1_llvm_ir_parse_and_mutation_host_only
            PROPERTIES TIMEOUT 30)
        add_test(NAME native_gpu_g1_atto_source_to_llvm_ir_clang_host_only
            COMMAND "${CMAKE_COMMAND}"
                "-DG1_COMPILER=$<TARGET_FILE:oreslang-gpu-g1-atto-saxpy-llvmc>"
                "-DG1_MAIN_COMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DG1_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/gpu-g1-saxpy.atto"
                "-DG1_EMITTER=$<TARGET_FILE:oreslang-gpu-g1-typed-saxpy-ir-test>"
                "-DG1_CLANG=${ATTO_GPU_G1_CLANG}"
                "-DG1_WORK=${CMAKE_CURRENT_BINARY_DIR}/gpu-g1-source-ir-proof"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_g1_atto_source_verify.cmake")
        set_tests_properties(native_gpu_g1_atto_source_to_llvm_ir_clang_host_only
            PROPERTIES TIMEOUT 30 LABELS "restricted-atto;host-only")
    endif()
    # AOT trace policy gate; this does not compile a source-level Atto trace.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-failure-observer-host-test
        tests/actor_reliability_fault_observer_test.cpp)
    target_include_directories(oreslang-native-actor-failure-observer-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-failure-observer-host-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-failure-observer-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-actor-failure-observer-host-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_failure_observer_ring_host_only
        COMMAND oreslang-native-actor-failure-observer-host-test)
    set_tests_properties(native_actor_failure_observer_ring_host_only PROPERTIES TIMEOUT 20)
    # The full LLVM build already registers supervisor_control_turn_test.cpp
    # as oreslang-supervisor-control-turn-test / native_supervisor_priority_control.
    # Keep its second entry exclusive to the early host-only return branch.
    add_executable(oreslang-native-trace-probe-policy-test
        tests/native_trace_probe_policy_test.cpp)
    target_include_directories(oreslang-native-trace-probe-policy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-trace-probe-policy-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-trace-probe-policy-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-trace-probe-policy-test PRIVATE Threads::Threads)
    add_test(NAME native_trace_probe_policy_host_only
        COMMAND oreslang-native-trace-probe-policy-test)
    set_tests_properties(native_trace_probe_policy_host_only PROPERTIES TIMEOUT 20)
    add_executable(oreslang-native-traceparent-v00-test
        tests/native_traceparent_v00_test.cpp)
    target_include_directories(oreslang-native-traceparent-v00-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-traceparent-v00-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-traceparent-v00-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_traceparent_v00_host_only
        COMMAND oreslang-native-traceparent-v00-test)
    set_tests_properties(native_traceparent_v00_host_only PROPERTIES TIMEOUT 20)
    add_executable(oreslang-native-semantic-span-buffer-test
        tests/native_semantic_span_buffer_test.cpp)
    target_include_directories(oreslang-native-semantic-span-buffer-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-semantic-span-buffer-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-semantic-span-buffer-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-semantic-span-buffer-test PRIVATE Threads::Threads)
    add_test(NAME native_semantic_span_buffer_host_only
        COMMAND oreslang-native-semantic-span-buffer-test)
    set_tests_properties(native_semantic_span_buffer_host_only PROPERTIES TIMEOUT 20)
    # C11 dormant telemetry hook lifecycle, bounded per-carrier stack,
    # disabled-mode behavior and runtime switch. Independent from guest proof.
    add_executable(oreslang-native-telemetry-span-runtime-test
        tests/native_telemetry_span_runtime_test.c
        src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
    target_include_directories(oreslang-native-telemetry-span-runtime-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-telemetry-span-runtime-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-telemetry-span-runtime-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_telemetry_dormant_span_runtime
        COMMAND oreslang-native-telemetry-span-runtime-test)
    set_tests_properties(native_telemetry_dormant_span_runtime PROPERTIES TIMEOUT 20)

    # Concrete C runtime for compiler-emitted literal log.info AOT probes.
    # This host/native CTest does NOT replace compiler IR or linked guest proof.
    add_executable(oreslang-native-static-log-ring-test
        tests/native_static_log_ring_v1_test.c
        src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
    target_include_directories(oreslang-native-static-log-ring-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-static-log-ring-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-static-log-ring-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_literal_guest_log_ring_runtime
        COMMAND oreslang-native-static-log-ring-test)
    set_tests_properties(native_literal_guest_log_ring_runtime PROPERTIES TIMEOUT 20)
    add_executable(oreslang-native-static-log-model-test
        tests/native_static_log_model_v1_test.c
        src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
    target_include_directories(oreslang-native-static-log-model-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-static-log-model-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-static-log-model-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_literal_log_50k_model_runtime
        COMMAND oreslang-native-static-log-model-test)
    set_tests_properties(native_literal_log_50k_model_runtime PROPERTIES TIMEOUT 20)

    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-native-static-log-carriers-test
            tests/native_static_log_carriers_v1_test.c
            src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
        target_include_directories(oreslang-native-static-log-carriers-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-static-log-carriers-test PRIVATE Threads::Threads)
        set_target_properties(oreslang-native-static-log-carriers-test PROPERTIES
            C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
        target_compile_options(oreslang-native-static-log-carriers-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_literal_guest_log_eight_carriers_runtime
            COMMAND oreslang-native-static-log-carriers-test)
        set_tests_properties(native_literal_guest_log_eight_carriers_runtime
            PROPERTIES TIMEOUT 20)
    endif()

    # Real POSIX carrier isolation: compare unique span IDs across pthreads,
    # then prove the wrong-carrier close receives SIGABRT in a forked child.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        find_package(Threads REQUIRED)
        add_executable(oreslang-native-telemetry-cross-carrier-test
            tests/native_telemetry_cross_carrier_test.c
            src/main/cpp/oreslang/runtime/NativeTelemetrySpan.c)
        target_include_directories(oreslang-native-telemetry-cross-carrier-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-telemetry-cross-carrier-test PRIVATE
            Threads::Threads)
        set_target_properties(oreslang-native-telemetry-cross-carrier-test PROPERTIES
            C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
        target_compile_options(oreslang-native-telemetry-cross-carrier-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_telemetry_cross_carrier_nonforgeability
            COMMAND oreslang-native-telemetry-cross-carrier-test)
        set_tests_properties(native_telemetry_cross_carrier_nonforgeability
            PROPERTIES TIMEOUT 20)
    endif()

    # Fail-closed host-level type resolver checks; these do not claim guest
    # Option/Result/trap native parity without compiler-backed .ores execution.
    add_executable(oreslang-native-sum-payload-admission-test
        tests/native_sum_payload_admission_test.cpp)
    target_include_directories(oreslang-native-sum-payload-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-sum-payload-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_sum_payload_type_admission
        COMMAND oreslang-native-sum-payload-admission-test)
    set_tests_properties(native_sum_payload_type_admission PROPERTIES TIMEOUT 20)
    # Recursive Option/Result functor type-shape admission, including the
    # real scalar sum resolver. This is HOST-ONLY, not compiled .atto guest
    # or native ownership/dtor evidence.
    add_executable(oreslang-functor-type-shape-test
        tests/functor_type_rules_test.cpp)
    target_include_directories(oreslang-functor-type-shape-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-functor-type-shape-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_functor_recursive_type_shape_host_only
        COMMAND oreslang-functor-type-shape-test)
    set_tests_properties(native_functor_recursive_type_shape_host_only
        PROPERTIES TIMEOUT 20)

    # Rank-2 tensor descriptor geometry admission, independent native host
    # oracle. Guest source execution remains a separate gate in CI.
    add_executable(oreslang-native-tensor2d-admission-test
        tests/native_tensor2d_admission_test.cpp)
    target_include_directories(oreslang-native-tensor2d-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-tensor2d-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_tensor2d_descriptor_geometry_host_only
        COMMAND oreslang-native-tensor2d-admission-test)
    set_tests_properties(native_tensor2d_descriptor_geometry_host_only
        PROPERTIES TIMEOUT 15)
    # Structural-only hard-RT admission: period, memory and scheduler must
    # fail closed. This test never certifies WCET, physical timing or no-GC.
    add_executable(oreslang-rt-admission-bounds-test
        tests/rt_admission_bounds_test.cpp)
    target_include_directories(oreslang-rt-admission-bounds-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-rt-admission-bounds-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_rt_declared_bound_admission
        COMMAND oreslang-rt-admission-bounds-test)
    set_tests_properties(native_rt_declared_bound_admission PROPERTIES TIMEOUT 15)
    # HOST-ONLY shared finance semantics; this is NOT native Oreslang guest
    # float/Region lowering and must not be counted as compiled guest parity.
    add_executable(oreslang-hpc-finance-risk-host-contract
        tests/hpc_finance_risk_host_contract_test.cpp)
    target_compile_options(oreslang-hpc-finance-risk-host-contract PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME hpc_finance_risk_host_only
        COMMAND oreslang-hpc-finance-risk-host-contract
        "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/hpc-finance/risk-gate-v1.tsv")
    set_tests_properties(hpc_finance_risk_host_only PROPERTIES TIMEOUT 15)
    add_test(NAME hpc_finance_source_float_region_fail_closed
        COMMAND "${CMAKE_COMMAND}"
            "-DORES_FINANCE_LLVM_CLI=$<TARGET_FILE:oreslang-llvmc>"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/hpc_finance_float_negative_test.cmake")
    set_tests_properties(hpc_finance_source_float_region_fail_closed
        PROPERTIES TIMEOUT 40)
    # Real compiler gate: currently unsupported native trap/Result effects must
    # not emit LLVM IR. Do not count rejection as semantic parity.
    add_test(NAME native_trap_result_option_fail_closed
        COMMAND "${CMAKE_COMMAND}"
            "-DORES_TRAP_LLVM_CLI=$<TARGET_FILE:oreslang-llvmc>"
            "-DORES_TRAP_SHARED_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/trap-three-backend-target.atto"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/trap_option_result_native_source_gate.cmake")
    set_tests_properties(native_trap_result_option_fail_closed PROPERTIES TIMEOUT 90)

    # Byte-identical shared .atto corpus: admit only the current pure trap
    # source profile; reject all unlowered ordinary throw/Result/Option cases.
    # Positive CTest on a compiler is not the same as native runtime parity.
    add_test(NAME native_caller_trap_canonical_source_gate
        COMMAND "${CMAKE_COMMAND}"
            "-DORES_TRAP_LLVM_CLI=$<TARGET_FILE:oreslang-llvmc>"
            "-DORES_TRAP_CORPUS=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/caller-trap-parity"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/trap_canonical_corpus_source_gate.cmake")
    set_tests_properties(native_caller_trap_canonical_source_gate PROPERTIES TIMEOUT 150)

    # Native phase-one proof: actual compiler -> audited LLVM IR -> Clang
    # O0/O2 executable -> real exit 42. Never count this as Result/throw parity.
    add_test(NAME native_trap_int_aot_guest_O0_O2
        COMMAND "${CMAKE_COMMAND}"
            "-DORES_TRAP_LLVM_CLI=$<TARGET_FILE:oreslang-llvmc>"
            "-DORES_TRAP_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/trap-int-native42.atto"
            "-DORES_TRAP_AOT_SCRIPT=${CMAKE_CURRENT_SOURCE_DIR}/scripts/compile-aot.sh"
            "-DORES_TRAP_AUDITOR=${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit"
            "-DORES_TRAP_CLANG=clang"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/trap_int_native_aot_execution.cmake")
    set_tests_properties(native_trap_int_aot_guest_O0_O2 PROPERTIES TIMEOUT 300)


    # Trusted-host capability admission only; NOT source-level/hostile-native parity.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-tenant-domain-host-test
        tests/native_tenant_domain_host_test.c
        src/main/cpp/oreslang/runtime/TenantDomainHost.c)
    target_include_directories(oreslang-native-tenant-domain-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-tenant-domain-host-test PRIVATE Threads::Threads)
    set_target_properties(oreslang-native-tenant-domain-host-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-native-tenant-domain-host-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_tenant_domain_trusted_host_admission
        COMMAND oreslang-native-tenant-domain-host-test)
    set_tests_properties(native_tenant_domain_trusted_host_admission PROPERTIES TIMEOUT 45)
    # Compiler-level trait projection contract: closed-world HasFuture dispatch
    # for native ActorRef readiness and its one-shot Future<void> token.
    # Independently runnable without LLVM libraries or a guest scheduler.
    add_executable(oreslang-native-hasfuture-contract-test
        tests/native_has_future_contract_test.cpp)
    target_include_directories(oreslang-native-hasfuture-contract-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-hasfuture-contract-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_hasfuture_trait_projection_admission
        COMMAND oreslang-native-hasfuture-contract-test)
    set_tests_properties(native_hasfuture_trait_projection_admission PROPERTIES TIMEOUT 15)

    # Compile-time associated-type dictionary coherence and static projection.
    # This verifies the checker helper, not .ores parsing or native guest codegen.
    add_executable(oreslang-native-associated-witness-test
        tests/native_associated_witness_contract_test.cpp)
    target_include_directories(oreslang-native-associated-witness-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-associated-witness-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_associated_witness_static_coherence
        COMMAND oreslang-native-associated-witness-test)
    set_tests_properties(native_associated_witness_static_coherence PROPERTIES TIMEOUT 15)

    # Source-free compiler admission helper for actor session CFG joins and
    # finite signed-i64 function refinement contracts (not guest source lowering).
    add_executable(oreslang-native-p0-session-refinement-test
        tests/native_p0_session_refinement_test.cpp)
    target_include_directories(oreslang-native-p0-session-refinement-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-p0-session-refinement-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_p0_session_refinement_compiler_contract
        COMMAND oreslang-native-p0-session-refinement-test)
    set_tests_properties(native_p0_session_refinement_compiler_contract
        PROPERTIES TIMEOUT 15)

    # Compiler-only Send/Sync parity model. LLVM guest lowering remains gated.
    add_executable(oreslang-native-send-sync-contract-test
        tests/native_send_sync_contract_test.cpp)
    target_include_directories(oreslang-native-send-sync-contract-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-send-sync-contract-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_send_sync_marker_contract
        COMMAND oreslang-native-send-sync-contract-test)
    set_tests_properties(native_send_sync_marker_contract PROPERTIES TIMEOUT 15)
    # Host-only tagged Symbol property identity / static layout parity.
    # This is NOT compiled guest Symbol source, a Unicode NFC decoder,
    # actor admission or property-key codegen.
    add_executable(oreslang-native-symbol-property-layout-v1-test
        tests/symbol_property_layout_v1_test.cpp)
    target_include_directories(oreslang-native-symbol-property-layout-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-symbol-property-layout-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_symbol_property_layout_host_only_v1
        COMMAND oreslang-native-symbol-property-layout-v1-test)
    set_tests_properties(native_symbol_property_layout_host_only_v1
        PROPERTIES TIMEOUT 15)

    # Host-only immutable Symbol literal image, generation pins and scoped
    # core-copy checks. NOT native guest #Symbol lowering or OS RO protection.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-shared-symbol-image-v1-test
        tests/native_shared_symbol_literal_image_v1_test.cpp)
    target_include_directories(oreslang-native-shared-symbol-image-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-shared-symbol-image-v1-test
        PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-shared-symbol-image-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_shared_symbol_image_host_only_v1
        COMMAND oreslang-native-shared-symbol-image-v1-test)
    set_tests_properties(native_shared_symbol_image_host_only_v1
        PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-string-region-test
        tests/native_string_region_test.c src/main/cpp/oreslang/runtime/NativeStringRegion.c)
    target_include_directories(oreslang-native-string-region-test PRIVATE src/main/cpp)
    set_target_properties(oreslang-native-string-region-test PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
    target_compile_options(oreslang-native-string-region-test PRIVATE -UNDEBUG -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-string-region-test PRIVATE Threads::Threads)
    add_test(NAME native_string_region_lifetime_and_budgets COMMAND oreslang-native-string-region-test)
    set_tests_properties(native_string_region_lifetime_and_budgets PROPERTIES TIMEOUT 20)
    find_program(ORES_CARGO_EXECUTABLE NAMES cargo REQUIRED)
    set(ORES_RUST_TEST_HARNESS_MANIFEST
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/rust-harness/Cargo.toml")
    set(ORES_RUST_TEST_HARNESS_TARGET_DIR
        "${CMAKE_CURRENT_BINARY_DIR}/rust-test-harness-target")
    set(ORES_RUST_TEST_HARNESS_BIN
        "${ORES_RUST_TEST_HARNESS_TARGET_DIR}/release/oreslang-llvm-test-harness${CMAKE_EXECUTABLE_SUFFIX}")
    add_custom_target(oreslang-rust-test-harness ALL
        COMMAND "${ORES_CARGO_EXECUTABLE}" build
            --manifest-path "${ORES_RUST_TEST_HARNESS_MANIFEST}"
            --release --locked
            --target-dir "${ORES_RUST_TEST_HARNESS_TARGET_DIR}"
        BYPRODUCTS "${ORES_RUST_TEST_HARNESS_BIN}"
        COMMENT "Building Rust-only LLVM test harness"
        VERBATIM)
    # Host-only generational reply slab proof; not source-level actor RPC.
    add_executable(oreslang-native-generational-reply-test
        tests/native_generational_reply_slots_test.cpp)
    target_include_directories(oreslang-native-generational-reply-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-generational-reply-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-generational-reply-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_supervisor_generational_reply_host_only
        COMMAND oreslang-native-generational-reply-test)
    set_tests_properties(native_actor_supervisor_generational_reply_host_only
        PROPERTIES TIMEOUT 30)
    # Bounded unary + stream reply host proof (NOT compiled Atto guest).
    add_executable(oreslang-native-actor-reply-v1-test
        tests/native_actor_reply_protocol_v1_test.cpp)
    target_include_directories(oreslang-native-actor-reply-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-reply-v1-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-reply-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_reply_v1_host_only COMMAND oreslang-native-actor-reply-v1-test)
    set_tests_properties(native_actor_reply_v1_host_only PROPERTIES TIMEOUT 40)
    # Host-only instrumented-trace analyzer (not full guest instrumentation).
    add_executable(oreslang-concurrency-race-analyzer-test
        tests/concurrency_race_analyzer_test.cpp)
    target_include_directories(oreslang-concurrency-race-analyzer-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-concurrency-race-analyzer-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-concurrency-race-analyzer-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_concurrency_vector_clock_host_only
        COMMAND oreslang-concurrency-race-analyzer-test)
    set_tests_properties(native_concurrency_vector_clock_host_only
        PROPERTIES TIMEOUT 20)

    # Strict hard-RT substrate: caller-backed, sealed/prefaulted, single-carrier
    # arena with no system allocator or lock path after initialization.
    add_executable(oreslang-realtime-actor-arena-test
        tests/realtime_actor_arena_test.cpp
        src/main/cpp/oreslang/runtime/RealtimeActorArena.c)
    target_include_directories(oreslang-realtime-actor-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-realtime-actor-arena-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-realtime-actor-arena-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_realtime_actor_arena_contract
        COMMAND oreslang-realtime-actor-arena-test)
    set_tests_properties(native_realtime_actor_arena_contract PROPERTIES TIMEOUT 15)

    # Trusted native runtime: preallocated arena + four heterogeneous layouts.
    # Proves only host in-place operation and C++ new-free iteration, NOT
    # compiler-generated guest, malloc-free full image or hardware WCET.
    add_executable(oreslang-realtime-four-layout-kernel-test
        tests/realtime_four_layout_inplace_kernel_test.cpp
        src/main/cpp/oreslang/runtime/RealtimeActorArena.c)
    target_include_directories(oreslang-realtime-four-layout-kernel-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-realtime-four-layout-kernel-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-realtime-four-layout-kernel-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-realtime-four-layout-kernel-test PRIVATE Threads::Threads)
    add_test(NAME native_realtime_four_layout_noalloc_inplace
        COMMAND oreslang-realtime-four-layout-kernel-test)
    set_tests_properties(native_realtime_four_layout_noalloc_inplace PROPERTIES TIMEOUT 20)

    # Experimental native compiler AST safety graph; not a full security proof.
    add_executable(oreslang-native-static-safety-test
        tests/native_static_safety_test.cpp)
    target_include_directories(oreslang-native-static-safety-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-static-safety-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_static_safety_ast_contract
        COMMAND oreslang-native-static-safety-test)
    set_tests_properties(native_static_safety_ast_contract PROPERTIES TIMEOUT 15)

    # Compiler-owned transitive effect/call-graph hard-RT admission.
    add_executable(oreslang-realtime-analysis-test
        tests/realtime_analysis_test.cpp)
    target_include_directories(oreslang-realtime-analysis-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-realtime-analysis-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_realtime_transitive_effect_analysis
        COMMAND oreslang-realtime-analysis-test)
    set_tests_properties(native_realtime_transitive_effect_analysis PROPERTIES TIMEOUT 15)

    # Oreslang core synchronization, not Java/JVM locks. The C11 gate itself
    # is freestanding and nonparking; pthreads are used only in this test.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-core-sync-test tests/native_core_sync_test.c)
    set_target_properties(oreslang-native-core-sync-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-native-core-sync-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-core-sync-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-core-sync-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    # Keep assert() live in Release/NDEBUG: these are security admission checks.
    target_compile_options(oreslang-native-core-sync-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-UNDEBUG>)
    add_test(NAME native_core_atomic_mutex_rw_admission
        COMMAND oreslang-native-core-sync-test)
    set_tests_properties(native_core_atomic_mutex_rw_admission
        PROPERTIES TIMEOUT 45)
    # Oreslang stdlib scheduler adapter: bounded nonparking logical tickets,
    # FIFO mutex, reader cohorts, physical-quiescence fences and cancellation.
    add_executable(oreslang-native-std-sync-test tests/native_std_sync_admission_test.c)
    set_target_properties(oreslang-native-std-sync-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-native-std-sync-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-std-sync-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-std-sync-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_stdlib_mutex_rw_queue_and_quiescence
        COMMAND oreslang-native-std-sync-test)
    set_tests_properties(native_stdlib_mutex_rw_queue_and_quiescence
        PROPERTIES TIMEOUT 60)

    add_executable(oreslang-native-actor-state-rw-test
        tests/native_actor_state_rw_admission_test.c)
    set_target_properties(oreslang-native-actor-state-rw-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-native-actor-state-rw-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-state-rw-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-state-rw-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_state_rw_critical_using_admission
        COMMAND oreslang-native-actor-state-rw-test)
    set_tests_properties(native_actor_state_rw_critical_using_admission
        PROPERTIES TIMEOUT 60)

    add_executable(oreslang-native-http-exchange-test tests/native_http_exchange_test.cpp)
    target_include_directories(oreslang-native-http-exchange-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-http-exchange-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-http-exchange-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_http_exchange_generation_ownership COMMAND oreslang-native-http-exchange-test)
    set_tests_properties(native_http_exchange_generation_ownership PROPERTIES TIMEOUT 20)

    # Host-only trusted pointer substrate. This is NOT a source-level unsafe
    # pointer permission, compiler lowering, or LLVM guest execution proof.
    find_package(Threads REQUIRED)
    add_executable(oreslang-trusted-native-pointer-window-test
        tests/trusted_pointer_window_test.cpp)
    target_include_directories(oreslang-trusted-native-pointer-window-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-trusted-native-pointer-window-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-trusted-native-pointer-window-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME trusted_native_pointer_window_host_only
        COMMAND oreslang-trusted-native-pointer-window-test)
    add_executable(oreslang-native-region-registry-test tests/native_region_registry_test.cpp)
    target_include_directories(oreslang-native-region-registry-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-region-registry-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-region-registry-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_region_registry_host_only COMMAND oreslang-native-region-registry-test)
    set_tests_properties(native_region_registry_host_only PROPERTIES TIMEOUT 20)
    set_tests_properties(trusted_native_pointer_window_host_only PROPERTIES TIMEOUT 20)
    # Native host-only CoreLog diagnostics. This test does not require guest
    # source/OTel modules or grant the compiled program a logging capability.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-core-log-test tests/native_core_log_test.cpp)
    target_include_directories(oreslang-native-core-log-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-core-log-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-core-log-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_core_log_severity_capacity_and_concurrency
        COMMAND oreslang-native-core-log-test)
    set_tests_properties(native_core_log_severity_capacity_and_concurrency
        PROPERTIES TIMEOUT 20)

    # OS-independent allocator-backed AllocJson text/tree substrate. This does
    # not expose guest-source Json/Map/record syntax until the native frontend
    # has checked string and structural value lowering.
    add_executable(oreslang-native-alloc-json-test
        tests/native_alloc_json_test.cpp)
    target_include_directories(oreslang-native-alloc-json-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-alloc-json-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_alloc_json_strict_text_tree_substrate
        COMMAND oreslang-native-alloc-json-test)
    set_tests_properties(native_alloc_json_strict_text_tree_substrate
        PROPERTIES TIMEOUT 20)
    # HOST-ONLY closed Result<void,ArrayError> nominal/tag and exhaustive
    # pattern proof. This is not guest source Result/match lowering.
    add_executable(oreslang-native-array-result-contract-test
        tests/native_array_result_contract_test.cpp)
    target_include_directories(oreslang-native-array-result-contract-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-array-result-contract-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_array_result_nominal_closed_proof_host_only
        COMMAND oreslang-native-array-result-contract-test)
    set_tests_properties(native_array_result_nominal_closed_proof_host_only
        PROPERTIES TIMEOUT 20)
    # Pure compile-time nominal selector proof. This does not imply that the
    # restricted i64 native AST accepts source `define enum` yet.
    add_executable(oreslang-native-nominal-selector-test
        tests/nominal_overload_selector_test.cpp)
    target_include_directories(oreslang-native-nominal-selector-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-nominal-selector-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_nominal_overload_selector_static
        COMMAND oreslang-native-nominal-selector-test)
    set_tests_properties(native_nominal_overload_selector_static PROPERTIES TIMEOUT 15)

    # Host-only fairness budget; not evidence of compiled Oreslang suspension.
    add_executable(oreslang-native-reduction-quantum-test
        tests/native_cooperation_quantum_test.cpp)
    target_include_directories(oreslang-native-reduction-quantum-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-reduction-quantum-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_reduction_quantum_watchdog_host_only
        COMMAND oreslang-native-reduction-quantum-test)
    set_tests_properties(native_reduction_quantum_watchdog_host_only
        PROPERTIES TIMEOUT 15)

    # Native Array<int> substrate, independent of source-level admission.
    # Invalid operations must abort even in Release/NDEBUG.
    add_executable(oreslang-native-int-array-region-test
        tests/native_int_array_region_test.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-int-array-region-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-int-array-region-test PRIVATE
        ORESLANG_NATIVE_TEST_TOKEN_EXHAUSTION=1)
    target_compile_options(oreslang-native-int-array-region-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Werror -Wpedantic>)
    add_test(NAME native_int_array_region_security
        COMMAND oreslang-native-int-array-region-test)

    # Checked, recoverable internal allocation/resize + staged prepare/commit.
    # Failpoint injection only exists in this test executable; existing
    # compiler-generated abort ABI and production runtime remain unchanged.
    add_executable(oreslang-native-int-array-transaction-test
        tests/native_int_array_transaction_test.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-int-array-transaction-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-int-array-transaction-test PRIVATE
        ORESLANG_NATIVE_TEST_ALLOC_FAILURE=1)
    target_compile_options(oreslang-native-int-array-transaction-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Werror -Wpedantic>)
    add_test(NAME native_int_array_transaction_recovery
        COMMAND oreslang-native-int-array-transaction-test)
    set_tests_properties(native_int_array_transaction_recovery PROPERTIES TIMEOUT 120)
    add_executable(oreslang-native-int-array-transfer-test
        tests/native_int_array_transfer_test.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-int-array-transfer-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-int-array-transfer-test PRIVATE ORESLANG_NATIVE_TEST_ALLOC_FAILURE=1)
    target_compile_options(oreslang-native-int-array-transfer-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Werror -Wpedantic>)
    add_test(NAME native_int_array_cross_region_transfer COMMAND oreslang-native-int-array-transfer-test)
    # Deterministic model-based differential stress on real Array<int> ABI.
    add_executable(oreslang-native-int-array-model-test
        tests/native_int_array_model_test.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-int-array-model-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-int-array-model-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Werror -Wpedantic>)
    add_test(NAME native_int_array_model_differential
        COMMAND oreslang-native-int-array-model-test)
    set_tests_properties(native_int_array_model_differential PROPERTIES TIMEOUT 90)

    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    add_test(NAME native_realtime_profile_cli
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}"
            realtime-profile "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_realtime_profile_cli PROPERTIES TIMEOUT 60)

    # Executable source admission gate: function values are not nominal classes.
    add_test(NAME native_callable_nominal_boundary
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}"
            callable-nominal "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_callable_nominal_boundary PROPERTIES TIMEOUT 35)
    # Constrained compiler-owned alias/trait/interface type declarations:
    # exact source syntax, no runtime value, and fail-closed unsupported types.
    add_test(NAME native_type_only_declaration_boundaries
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_type_only_declarations.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_type_only_declaration_boundaries PROPERTIES TIMEOUT 90)
    add_test(NAME native_type_constraint_descriptor_v1
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_type_constraint_descriptors.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_type_constraint_descriptor_v1 PROPERTIES TIMEOUT 90)
    # Broader refinement boundary: bounded int/i64 min/max descriptors are
    # supported, but unchecked runtime use and unimplemented refinement forms
    # must fail before emitting LLVM IR.
    add_test(NAME native_refinement_admission_failclosed
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/refinement_admission_gate.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_refinement_admission_failclosed PROPERTIES TIMEOUT 90)
    add_test(NAME native_nominal_type_binder_boundary
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}"
            nominal-type-binders "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_nominal_type_binder_boundary PROPERTIES TIMEOUT 35)
    add_test(NAME native_scalar_generic_contract_harness_selftest
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_native_scalar_generic_contract.py")
    set_tests_properties(native_scalar_generic_contract_harness_selftest PROPERTIES TIMEOUT 25)
    add_test(NAME native_scalar_generic_contract_harness_python_O
        COMMAND "${Python3_EXECUTABLE}" -O
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_native_scalar_generic_contract.py")
    set_tests_properties(native_scalar_generic_contract_harness_python_O PROPERTIES TIMEOUT 25)
    add_test(NAME native_compiler_source_budget
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_source_budget.py"
            "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_compiler_source_budget PROPERTIES TIMEOUT 25)
    # End-to-end source -> LLVM IR -> Clang -> actual native OS process.
    # Exact signed i64 results observed through a full-width C ABI harness, not lossy exit status.
    find_program(ORE_SOURCE_I64_CLANG NAMES clang-18 clang-19 clang clang-17 clang-16 clang-15 clang-14)
    if(NOT ORE_SOURCE_I64_CLANG)
        message(WARNING "Portable native i64 execution gate will fail: Clang not found")
        set(ORE_SOURCE_I64_CLANG "${CMAKE_CURRENT_BINARY_DIR}/missing-source-i64-clang")
    endif()
    # Real LLVM guest -> dormant span intrinsics -> Clang-linked C11 runtime
    # -> actual native OS process; five positive, five negative cases.
    # The host toggle test verifies same-process AOT activation/deactivation.
    add_test(NAME native_telemetry_live_toggle_aot_guest
        COMMAND bash
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_telemetry_live_toggle_aot.sh"
            "$<TARGET_FILE:oreslang-llvmc>"
            "${ORE_SOURCE_I64_CLANG}"
            "${CMAKE_CURRENT_SOURCE_DIR}")
    set_tests_properties(native_telemetry_live_toggle_aot_guest PROPERTIES TIMEOUT 150)
    add_test(NAME native_telemetry_using_scope_aot_guest
        COMMAND bash
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_telemetry_using_aot.sh"
            "$<TARGET_FILE:oreslang-llvmc>"
            "${ORE_SOURCE_I64_CLANG}"
            "${CMAKE_CURRENT_SOURCE_DIR}")
    set_tests_properties(native_telemetry_using_scope_aot_guest PROPERTIES TIMEOUT 150)

    add_test(NAME native_source_i64_fullwidth_execution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_source_i64_executable.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_source_i64_fullwidth_execution PROPERTIES TIMEOUT 120)
    # Java TransparentTypeAliasTest parity: `type N = T;`, `define type alias N as T;`
    # and alias-prefixed scalar locals, compiled to native guests at O0/O2.
    add_test(NAME native_type_alias_spellings
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_type_alias_spellings.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_type_alias_spellings PROPERTIES TIMEOUT 120)
    # Java lexer parity: contextual words (borrow, critical, enum, gen, pure,
    # requires, serial, structural, suspend, using) are ordinary identifiers.
    add_test(NAME native_contextual_keyword_identifiers
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_contextual_keyword_identifiers.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_contextual_keyword_identifiers PROPERTIES TIMEOUT 120)
    # Compiler-backed .atto -> LLVM IR -> Clang -> native guest at O0/O2.
    # Boolean punctuation contract: &&, ||, ^^ and compile-negative cases.
    add_test(NAME native_logical_punctuation_operators
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_logical_punctuation_operators.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_logical_punctuation_operators PROPERTIES TIMEOUT 120)
    # Java parity: `if c then return; fi` in void functions/methods is an
    # ordinary conditional with an early-returning arm (native O0/O2 guests).
    add_test(NAME native_void_early_return
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_void_early_return.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_void_early_return PROPERTIES TIMEOUT 120)
    # New real .ores guest span-boundary contract: no host/reference promotion.
    # Uses the production source compiler and Clang AOT executable path.
    add_test(NAME native_tensor_span_source_aot
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/verify-tensor-span-source.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_tensor_span_source_aot PROPERTIES TIMEOUT 120
        ENVIRONMENT "ORES_NO_GC_IR_AUDIT=${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit")
    # Real `.ores` Array<int> native region ownership/lane execution.
    # A passing host-side reference model cannot satisfy this gate.
    add_test(NAME native_tensor_owned_array_source_aot
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/verify-tensor-owned-array.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_tensor_owned_array_source_aot PROPERTIES TIMEOUT 120
        ENVIRONMENT "ORES_NO_GC_IR_AUDIT=${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit")
    # Pinned portable scalar corpus (shared with java/net) and the five-case
    # zero-assert guest set: source -> LLVM IR -> Clang -> OS exit status,
    # plus fail-closed rejection of removed/forbidden source operators.
    add_test(NAME native_portable_scalar_v1_smoke
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/portable_scalar_v1_native_smoke.sh"
            "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_portable_scalar_v1_smoke PROPERTIES TIMEOUT 120)
    add_test(NAME native_scalar_zero_smoke
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_scalar_zero_smoke.sh"
            "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_scalar_zero_smoke PROPERTIES TIMEOUT 120)
    # Source-executable Java-style signed integer remainder subset:
    # nonzero positive/negative i64 literals only. MIN % -1 is lowered
    # without LLVM srem poison; dynamic/zero divisors still fail closed.
    add_test(NAME native_java_signed_remainder_source_parity
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" native-remainder-parity
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_java_signed_remainder_source_parity
        PROPERTIES TIMEOUT 120)
    # Exact source alias-extension proof: compiler-owned static receiver dispatch,
    # LLVM IR emission, Clang link and native process execution.
    add_test(NAME native_alias_extension_method_aot
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_alias_extension_methods.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_alias_extension_method_aot PROPERTIES TIMEOUT 120)
    # Exact compiler -> LLVM -> Clang -> OS conformance from the shared TSV.
    add_test(NAME native_shared_type_declaration_conformance
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/shared_type_declarations_conformance.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_shared_type_declaration_conformance PROPERTIES TIMEOUT 120)
    # First executable-level tree-shaking proof: main/pub roots plus transitive
    # direct calls. Size is measured but not thresholded for this tiny ELF fixture.
    add_test(NAME native_tree_shaking_direct_calls
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_tree_shaking.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_tree_shaking_direct_calls PROPERTIES TIMEOUT 120)
    # Self-test for a measurement-only compile benchmark. No performance
    # threshold is asserted in CI: runner timing is not reproducible.
    add_test(NAME native_compile_benchmark_harness_selftest
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_compile_benchmark_harness.py")
    set_tests_properties(native_compile_benchmark_harness_selftest
        PROPERTIES TIMEOUT 30)
    find_program(ORE_NATIVE_ARRAY_CLANG NAMES clang-18 clang-19 clang clang-17 clang-16 clang-15 clang-14)
    if(NOT ORE_NATIVE_ARRAY_CLANG)
        message(WARNING "Native array LLVM ABI gate cannot pass without Clang")
        set(ORE_NATIVE_ARRAY_CLANG "${CMAKE_CURRENT_BINARY_DIR}/missing-clang")
    endif()
    # Exercise the scalar generic source compiler through real LLVM IR, Clang,
    # and guest execution on every full CTest run. Loader-only tests are not
    # sufficient evidence that scalar specialization still works.
    add_test(NAME native_scalar_generic_source_execution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_scalar_generic_source_execution.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}")
    set_tests_properties(native_scalar_generic_source_execution PROPERTIES TIMEOUT 240)

    add_test(NAME native_annotation_macro_aot_admission
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/annotation_macro_aot_admission_matrix.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}")
    set_tests_properties(native_annotation_macro_aot_admission PROPERTIES TIMEOUT 180)
    add_test(NAME native_annotation_macro_aot_harness_selftest
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_annotation_macro_aot_harness.py")
    set_tests_properties(native_annotation_macro_aot_harness_selftest PROPERTIES TIMEOUT 30)
    add_test(NAME native_int_array_llvm_abi
        COMMAND "${Python3_EXECUTABLE}"
          "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_int_array_llvm_abi.py"
          --clang "${ORE_NATIVE_ARRAY_CLANG}"
          --source-root "${CMAKE_CURRENT_SOURCE_DIR}")
    set_tests_properties(native_int_array_llvm_abi PROPERTIES TIMEOUT 120)

    # Actual Oreslang -> LLVM compiler-generated IR -> Clang linked with
    # the trusted region ABI. Separate from hand-authored native ABI fixture.
    add_test(NAME native_int_array_source_execution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_int_array_source_execution.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}")
    add_test(NAME native_int_array_source_asan_ubsan
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_int_array_source_execution.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}"
            --sanitizers)
    set_tests_properties(native_int_array_source_execution
        native_int_array_source_asan_ubsan PROPERTIES TIMEOUT 180)

    # Verify compiler-generated checked array allocation/growth actually
    # destroys exactly the initialized owners on every injected fault.
    # Requires the Linux ELF --wrap linker protocol.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_test(NAME native_checked_array_source_failure_cleanup
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_checked_array_failure_source.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_NATIVE_ARRAY_CLANG}"
                --source-root "${CMAKE_CURRENT_SOURCE_DIR}")
        add_test(NAME native_checked_array_source_failure_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_checked_array_failure_source.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_NATIVE_ARRAY_CLANG}"
                --source-root "${CMAKE_CURRENT_SOURCE_DIR}"
                --sanitizers)
        set_tests_properties(native_checked_array_source_failure_cleanup
            native_checked_array_source_failure_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()

    # Real native guest class-owned Array<int> fields, independent of the
    # handcrafted ABI fixture and lexical-array-only source admission.
    add_test(NAME native_class_owned_array_source_execution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_class_owned_array_source.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}")
    add_test(NAME native_class_owned_array_source_asan_ubsan
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_class_owned_array_source.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_NATIVE_ARRAY_CLANG}"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}"
            --sanitizers)
    set_tests_properties(native_class_owned_array_source_execution
        native_class_owned_array_source_asan_ubsan PROPERTIES TIMEOUT 180)
    # Pre-compiler ActorStream admission and DONE-barrier transition contract.
    # This host-only gate is NOT evidence of compiled self.forward/backward.
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actorstream-route-test
        tests/native_actorstream_route_test.cpp)
    target_include_directories(oreslang-native-actorstream-route-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actorstream-route-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-actorstream-route-test PRIVATE Threads::Threads)
    add_test(NAME native_actorstream_bounded_route_contract
        COMMAND oreslang-native-actorstream-route-test)
    set_tests_properties(native_actorstream_bounded_route_contract
        PROPERTIES TIMEOUT 30)
    add_executable(oreslang-owned-i64-region-security-test
        tests/native_owned_region_security_test.c
        src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c)
    target_include_directories(oreslang-owned-i64-region-security-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-owned-i64-region-security-test PRIVATE
        ORESLANG_NATIVE_TEST_TOKEN_EXHAUSTION=1)
    target_compile_options(oreslang-owned-i64-region-security-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_owned_region_slot_security
        COMMAND oreslang-owned-i64-region-security-test)
    set_tests_properties(native_owned_region_slot_security PROPERTIES TIMEOUT 25)
    # The global token allocator must not repeat handles under parallel
    # allocations in separate, single-owner regions. This does NOT admit
    # concurrent access to one region or remove close-quiescence requirements.
    find_package(Threads REQUIRED)
    add_executable(oreslang-owned-i64-token-concurrent-regions-test
        tests/native_owned_region_token_concurrency_test.c
        src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c)
    target_include_directories(oreslang-owned-i64-token-concurrent-regions-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-owned-i64-token-concurrent-regions-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-owned-i64-token-concurrent-regions-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_owned_region_unique_parallel_tokens
        COMMAND oreslang-owned-i64-token-concurrent-regions-test)
    set_tests_properties(native_owned_region_unique_parallel_tokens PROPERTIES TIMEOUT 25)

    # Independent thread-confined regions may allocate concurrently. The
    # process-global handle allocator must never issue duplicate tokens.
    add_executable(oreslang-native-int-array-token-concurrent-regions-test
        tests/native_int_array_token_concurrency_test.c
        src/main/cpp/oreslang/runtime/NativeIntArrayRegion.c)
    target_include_directories(oreslang-native-int-array-token-concurrent-regions-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-int-array-token-concurrent-regions-test PRIVATE Threads::Threads)
    set_target_properties(oreslang-native-int-array-token-concurrent-regions-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON)
    target_compile_options(oreslang-native-int-array-token-concurrent-regions-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_int_array_unique_parallel_tokens
        COMMAND oreslang-native-int-array-token-concurrent-regions-test)
    set_tests_properties(native_int_array_unique_parallel_tokens PROPERTIES TIMEOUT 30)
    # Header-only native arena proof: independent actor byte regions without
    # tracing GC. This is not yet a compiled Oreslang actor object heap.
    add_executable(oreslang-actor-owned-heap-test tests/actor_owned_heap_test.cpp)
    target_include_directories(oreslang-actor-owned-heap-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-owned-heap-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_owned_heap_contract
        COMMAND oreslang-actor-owned-heap-test)

    # Host-only regression: one native callback runs on independent actor
    # heaps and supervisor state. This does not certify compiled .atto actor
    # execution, read-only OS code mappings, or compiler borrow checking.
    find_package(Threads REQUIRED)
    add_executable(oreslang-actor-shared-code-private-state-test
        tests/actor_shared_code_private_state_test.cpp)
    target_include_directories(oreslang-actor-shared-code-private-state-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-actor-shared-code-private-state-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-actor-shared-code-private-state-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_actor_shared_code_private_state_host_only
        COMMAND oreslang-actor-shared-code-private-state-test)
    set_tests_properties(native_actor_shared_code_private_state_host_only
        PROPERTIES TIMEOUT 30)

    # Compiler-ready compact object field substrate: physical i32 edges
    # with original full-domain/generation proofs kept outside guest objects.
    add_executable(oreslang-actor-compact-field-store-test
        tests/actor_compact_field_store_test.cpp)
    target_include_directories(oreslang-actor-compact-field-store-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-compact-field-store-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_compressed32_object_fields
        COMMAND oreslang-actor-compact-field-store-test)
    set_tests_properties(native_actor_compressed32_object_fields
        PROPERTIES TIMEOUT 30)

    add_executable(oreslang-managed-ref-layout-plan-test
        tests/managed_ref_layout_plan_test.cpp)
    target_include_directories(oreslang-managed-ref-layout-plan-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-managed-ref-layout-plan-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_compiler_managed_ref_layout_admission
        COMMAND oreslang-managed-ref-layout-plan-test)
    set_tests_properties(native_compiler_managed_ref_layout_admission
        PROPERTIES TIMEOUT 30)

    # LLVM IR-level implementation: i32 actor-local reference loads and
    # trusted original-generation validation before checked address decode.
    add_executable(oreslang-managed-ref-llvm-lowering-test
        tests/managed_ref_llvm_lowering_test.cpp)
    target_include_directories(oreslang-managed-ref-llvm-lowering-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    # SYSTEM: -Werror below is for this test, not for warnings in LLVM headers.
    target_include_directories(oreslang-managed-ref-llvm-lowering-test SYSTEM PRIVATE
        "${LLVM_INCLUDE_DIRS}")
    target_link_libraries(oreslang-managed-ref-llvm-lowering-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_options(oreslang-managed-ref-llvm-lowering-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_compact32_llvm_checked_load_store
        COMMAND oreslang-managed-ref-llvm-lowering-test)
    set_tests_properties(native_compact32_llvm_checked_load_store
        PROPERTIES TIMEOUT 30)

    # Execute the IR rather than trusting string/shape checks alone. IR must be
    # compiled by the LLVM that produced it, so prefer that LLVM's own tools. A
    # missing or mismatched toolchain fails this test rather than removing it.
    find_program(ORE_REF32_CLANG
        NAMES "clang++-${LLVM_VERSION_MAJOR}" clang++
        HINTS "${LLVM_TOOLS_BINARY_DIR}")
    find_program(ORE_STRUCTURAL_RUSTC rustc)
    set(ORE_REF32_CLANG_MAJOR "")
    if(ORE_REF32_CLANG)
        execute_process(COMMAND "${ORE_REF32_CLANG}" --version
            OUTPUT_VARIABLE ORE_REF32_CLANG_VERSION_TEXT
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET)
        if(ORE_REF32_CLANG_VERSION_TEXT MATCHES "version[ \t]+([0-9]+)")
            set(ORE_REF32_CLANG_MAJOR "${CMAKE_MATCH_1}")
        endif()
    endif()
    if(ORE_STRUCTURAL_RUSTC AND ORE_REF32_CLANG_MAJOR STREQUAL "${LLVM_VERSION_MAJOR}")
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-managed-ref-llvm-e2e"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/managed_ref_llvm_e2e.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-managed-ref-llvm-e2e"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/managed_ref_llvm_e2e.rs"
            VERBATIM)
        add_custom_target(oreslang-managed-ref-llvm-e2e-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-managed-ref-llvm-e2e")
        add_test(NAME native_compact32_llvm_executable_e2e
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-managed-ref-llvm-e2e"
                --ir-producer "$<TARGET_FILE:oreslang-managed-ref-llvm-lowering-test>"
                --clang "${ORE_REF32_CLANG}"
                --harness "${CMAKE_CURRENT_SOURCE_DIR}/tests/managed_ref_llvm_e2e_harness.cpp")
        set_tests_properties(native_compact32_llvm_executable_e2e
            PROPERTIES TIMEOUT 180)
    else()
        message(WARNING "ref32 LLVM e2e needs rustc and a Clang matching LLVM ${LLVM_VERSION_MAJOR}; registering it as a failing test")
        add_test(NAME native_compact32_llvm_executable_e2e
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()


    find_package(Python3 COMPONENTS Interpreter REQUIRED)


    # Exact queue snapshots are pinned to the merged upstream Git objects.
    # An unsupported feature is NOT a blanket allowance to ignore arbitrary
    # compiler failures. The harness also builds and runs supported scalar IR.
    find_program(ORE_QUEUE_CLANG NAMES clang-18 clang-19 clang clang-17 clang-16 clang-15 clang-14)
    # Never silently omit the admission test when Clang is absent: a reduced
    # test count must not look like proof of a successful compiler/runtime gate.
    # The test explicitly fails if its native execution control is unavailable.
    if(NOT ORE_QUEUE_CLANG)
        message(WARNING "Linked-queue CTest will fail: Clang is required for the native execution control")
        set(ORE_QUEUE_CLANG "${CMAKE_CURRENT_BINARY_DIR}/missing-clang-executable")
    endif()
    add_test(NAME native_linked_queue_exact_source_failclosed
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_linked_queue_fail_closed.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --fixture-root "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/linked-queue"
            --clang "${ORE_QUEUE_CLANG}")
    set_tests_properties(native_linked_queue_exact_source_failclosed
        PROPERTIES TIMEOUT 120)
    add_test(NAME native_linked_queue_harness_adversarial
        COMMAND "${Python3_EXECUTABLE}" -m unittest discover
            -s "${CMAKE_CURRENT_SOURCE_DIR}/tests"
            -p "test_native_linked_queue_fail_closed.py" -v)
    set_tests_properties(native_linked_queue_harness_adversarial
        PROPERTIES TIMEOUT 30)
    find_package(OpenSSL QUIET COMPONENTS Crypto)
    # NativeIpcAuthenticatedStreamSession.hpp is Linux-only (#error elsewhere).
    if(OpenSSL_FOUND AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-native-ipc-authenticated-session-failpoint-test
            tests/native_ipc_authenticated_session_failpoint_test.cpp)
        target_include_directories(oreslang-native-ipc-authenticated-session-failpoint-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-ipc-authenticated-session-failpoint-test PRIVATE
            OpenSSL::Crypto Threads::Threads)
        target_compile_options(oreslang-native-ipc-authenticated-session-failpoint-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_ipc_authenticated_session_retire_failpoint
            COMMAND oreslang-native-ipc-authenticated-session-failpoint-test)
        set_tests_properties(native_ipc_authenticated_session_retire_failpoint
            PROPERTIES TIMEOUT 20)

        add_executable(oreslang-native-ipc-authenticated-session-test
            tests/native_ipc_authenticated_stream_session_test.cpp)
        target_include_directories(oreslang-native-ipc-authenticated-session-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-ipc-authenticated-session-test PRIVATE
            OpenSSL::Crypto Threads::Threads)
        target_compile_options(oreslang-native-ipc-authenticated-session-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_ipc_authenticated_stream_session
            COMMAND oreslang-native-ipc-authenticated-session-test)
        set_tests_properties(native_ipc_authenticated_stream_session
            PROPERTIES TIMEOUT 30)

        add_executable(oreslang-native-ipc-wire-v3-test
            tests/native_ipc_wire_v3_test.cpp)
        target_include_directories(oreslang-native-ipc-wire-v3-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-ipc-wire-v3-test PRIVATE
            OpenSSL::Crypto)
        target_compile_options(oreslang-native-ipc-wire-v3-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_ipc_wire_v3_java_golden_parity
            COMMAND oreslang-native-ipc-wire-v3-test)
        set_tests_properties(native_ipc_wire_v3_java_golden_parity
            PROPERTIES TIMEOUT 20)

        # Host-only independently pinned dependency alias contract.
        # This proves fail-closed lock parsing, NOT executable namespaces.
        add_executable(oreslang-native-verified-namespace-dependency-lock-v2-test
            tests/native_verified_namespace_dependency_lock_v2_test.cpp)
        target_include_directories(
            oreslang-native-verified-namespace-dependency-lock-v2-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(
            oreslang-native-verified-namespace-dependency-lock-v2-test PRIVATE
            OpenSSL::Crypto)
        target_compile_options(
            oreslang-native-verified-namespace-dependency-lock-v2-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
        add_test(NAME native_verified_namespace_dependency_lock_v2_host_only
            COMMAND oreslang-native-verified-namespace-dependency-lock-v2-test)
        set_tests_properties(native_verified_namespace_dependency_lock_v2_host_only
            PROPERTIES TIMEOUT 15)
        # Real production Parser.cpp + physical v2 snapshot + verified owner
        # graph, without authorizing native guest namespace execution.
        add_executable(oreslang-native-verified-namespace-real-parser-v2-test
            tests/native_verified_namespace_real_parser_v2_test.cpp
            src/main/cpp/oreslang/frontend/Parser.cpp
            src/main/cpp/oreslang/frontend/MethodResolver.cpp
            src/main/cpp/oreslang/types/NativeScalarTypes.cpp
            src/main/cpp/oreslang/types/OwnershipChecker.cpp)
        target_include_directories(oreslang-native-verified-namespace-real-parser-v2-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
            "${LLVM_INCLUDE_DIRS}")
        target_link_libraries(oreslang-native-verified-namespace-real-parser-v2-test PRIVATE
            OpenSSL::Crypto ${ORE_LLVM_LIBS})
        target_compile_options(oreslang-native-verified-namespace-real-parser-v2-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
        add_test(NAME native_verified_namespace_real_parser_v2_host_only
            COMMAND oreslang-native-verified-namespace-real-parser-v2-test)
        set_tests_properties(native_verified_namespace_real_parser_v2_host_only
            PROPERTIES TIMEOUT 30)

        add_executable(oreslang-messagepack-wire-v2-test
            tests/messagepack_wire_v2_test.cpp)
        target_include_directories(oreslang-messagepack-wire-v2-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-messagepack-wire-v2-test PRIVATE
            OpenSSL::Crypto)
        target_compile_options(oreslang-messagepack-wire-v2-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_messagepack_wire_v2_java_parity
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/messagepack_wire_v2_matrix.py"
                --binary "$<TARGET_FILE:oreslang-messagepack-wire-v2-test>"
                --vectors "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/design/messagepack_collection_vectors.json")
        set_tests_properties(native_messagepack_wire_v2_java_parity PROPERTIES TIMEOUT 30)
        add_executable(oreslang-tagged-message-wire-v2-test
            tests/tagged_message_wire_v2_test.cpp)
        target_include_directories(oreslang-tagged-message-wire-v2-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-tagged-message-wire-v2-test PRIVATE
            OpenSSL::Crypto)
        target_compile_options(oreslang-tagged-message-wire-v2-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_tagged_enum_message_java_wire_parity
            COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" tagged-message-wire
                "$<TARGET_FILE:oreslang-tagged-message-wire-v2-test>"
                "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/design/tagged_message_vectors.json")
        set_tests_properties(native_tagged_enum_message_java_wire_parity PROPERTIES TIMEOUT 30)
    else()
        message(STATUS "Native MessagePack v2 parity test requires OpenSSL Crypto; not enabled")
    endif()
    find_program(ORE_CLANG NAMES clang clang-19 clang-18 clang-17 clang-16 clang-15 clang-14)
    if(ORE_CLANG AND ((CMAKE_SYSTEM_NAME STREQUAL "Linux"
            AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
            OR (CMAKE_SYSTEM_NAME STREQUAL "Darwin"
            AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)$")))
        add_test(NAME native_checked_i64_arithmetic
            COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" checked-i64
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
        set_tests_properties(native_checked_i64_arithmetic PROPERTIES TIMEOUT 120)
    endif()
    # Executable Oreslang method fixtures, using Rust-only tooling.
    if(ORE_CLANG)
        add_test(NAME native_inline_self_receiver_arity_aot
            COMMAND "${ORES_RUST_TEST_HARNESS_BIN}"
                native-inline-self-arity
                "$<TARGET_FILE:oreslang-llvmc>"
                "${ORE_CLANG}")
        set_tests_properties(native_inline_self_receiver_arity_aot PROPERTIES TIMEOUT 90)
    endif()
    # Java-parity syntax as .atto guests: if/then, stdio.stdout.write/println
    # of String/int/bool, Result.expect, and `import fnc` named imports.
    # Register these gates even when Clang is missing: absent native toolchains
    # must be visible failed tests, not falsely green CTest selections.
    if(ORE_CLANG)
        add_test(NAME native_atto_parity_syntax
            COMMAND "${CMAKE_COMMAND}"
                "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
                "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
                "-DCLANG=${ORE_CLANG}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_parity_syntax.cmake")
        set_tests_properties(native_atto_parity_syntax PROPERTIES TIMEOUT 240)
    endif()
    add_test(NAME native_sealed_scalar_aot_v1
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            "-DCLANG=${ORE_CLANG}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/sealed_scalar_aot_v1.cmake")
    set_tests_properties(native_sealed_scalar_aot_v1 PROPERTIES TIMEOUT 180)
    add_test(NAME native_sealed_class_fields_reachability_v1
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            "-DCLANG=${ORE_CLANG}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/sealed_class_fields_reachability_v1.cmake")
    set_tests_properties(native_sealed_class_fields_reachability_v1 PROPERTIES TIMEOUT 240)
    add_test(NAME native_sealed_aot_profile_mutations
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/sealed_aot_profile_mutations.cmake")
    set_tests_properties(native_sealed_aot_profile_mutations PROPERTIES TIMEOUT 240)
    # Independently prove invalid/missing build roots cannot trigger recursive
    # cleanup before the native compiler or a guest exists.
    add_test(NAME native_sealed_aot_workspace_guard
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/sealed_aot_profile_guard_selftest.cmake")
    set_tests_properties(native_sealed_aot_workspace_guard PROPERTIES TIMEOUT 30)
    # Mock-tool regression: prove strict link-closure rejection without
    # suggesting the fake compiler or Clang produced real guest evidence.
    if(UNIX)
        add_test(NAME native_sealed_aot_link_closure_sourcefree
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-sealed-aot-link-closure.sh")
        set_tests_properties(native_sealed_aot_link_closure_sourcefree
            PROPERTIES TIMEOUT 45)
    endif()
    # Positive compiler-only contracts must assemble the emitted IR, not just
    # accept an expected substring after the textual ModuleID header.
    if(ORE_CLANG)
        add_test(NAME native_contract_positive_object_verifies
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/contract.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/constant-main.ores"
                --verify-object --clang "${ORE_CLANG}")
        set_tests_properties(native_contract_positive_object_verifies
            PROPERTIES TIMEOUT 90)
    else()
        message(FATAL_ERROR "Native source IR object verification requires Clang")
    endif()
    # This isolated AOT source-to-native frame test does not relax the nine
    # default compiler-negative RT admission contracts.
    if(ORE_CLANG)
        add_test(NAME native_rt_scalar_frame_aot_two_tasks
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_step_aot.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-rt-scalar-step-positive.ores"
                --harness "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_step_harness.c")
        set_tests_properties(native_rt_scalar_frame_aot_two_tasks PROPERTIES TIMEOUT 120)
        # Frame-integrity gate (forward-port of #1570 onto #1569's batching):
        # O0/O2/O3 + sanitizer guest matrix, child-signal trap suite and
        # adversarial mutations of the compiler-emitted LLVM IR. Set
        # ORE_SANITIZER_CLANG to a clang whose ASan runtime runs on this host
        # when ORE_CLANG's does not (the gate skips unrunnable sanitizers).
        set(ORE_SANITIZER_CLANG "" CACHE FILEPATH
            "Optional extra clang for ASan/UBSan runs of generated native guests")
        add_test(NAME native_rt_scalar_frame_integrity_aot
            COMMAND "${CMAKE_COMMAND}"
                "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DCLANG=${ORE_CLANG}"
                "-DSAN_CLANG=${ORE_SANITIZER_CLANG}"
                "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-rt-scalar-step-positive.ores"
                "-DHARNESS=${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_step_harness.c"
                "-DWORK=${CMAKE_CURRENT_BINARY_DIR}/native-rt-scalar-frame-integrity"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_frame_integrity_aot.cmake")
        set_tests_properties(native_rt_scalar_frame_integrity_aot PROPERTIES TIMEOUT 600)
    else()
        message(WARNING "Experimental scalar-loop AOT gate requires clang")
    endif()
    find_program(ORE_NODE NAMES node nodejs)
    if(ORE_CLANG AND ORE_NODE)
        add_test(NAME wasm32_source_class_ownership_runtime
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/wasm32_owned_classes_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --node "${ORE_NODE}"
                --fixtures "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
        set_tests_properties(wasm32_source_class_ownership_runtime PROPERTIES TIMEOUT 240)
        add_test(NAME wasm32_compiler_generated_source_actor_state
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/wasm32_source_actor_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --node "${ORE_NODE}"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/wasm32-source-actor-counter.ores")
        set_tests_properties(wasm32_compiler_generated_source_actor_state PROPERTIES TIMEOUT 120)
        # Actual .ores actor handler lowered to import-free Wasm, with a
        # monotonic lifecycle and one private state image per Wasm instance.
        add_test(NAME wasm32_source_actor_guest_lifecycle
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/wasm32_source_actor_guest.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --node "${ORE_NODE}"
                --fixtures "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
        set_tests_properties(wasm32_source_actor_guest_lifecycle PROPERTIES TIMEOUT 120)
    endif()
    # First real compiler-emitted AOT while continuation. The host owns the
    # frame and schedules finite reductions; actor/mailbox integration is not
    # claimed until generated actor continuations use the carrier ABI.
    find_program(ORE_RT_AOT_CLANG NAMES clang-18 clang-19 clang clang-17 clang-16 clang-15 clang-14)
    find_program(ORE_RT_AOT_CXX NAMES clang++-18 clang++-19 clang++ clang++-17 clang++-16 clang++-15 clang++-14)
    if(NOT ORE_RT_AOT_CLANG OR NOT ORE_RT_AOT_CXX)
        message(FATAL_ERROR "AOT scalar continuation requires Clang and Clang++ to run")
    endif()
    add_test(NAME native_source_scalar_while_compiler_aot_resume
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_aot_scalar_while.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_RT_AOT_CLANG}"
            --cxx "${ORE_RT_AOT_CXX}"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}")
    set_tests_properties(native_source_scalar_while_compiler_aot_resume
        PROPERTIES TIMEOUT 120)

    if(APPLE OR CMAKE_SYSTEM_NAME STREQUAL "Linux")
        foreach(mode IN ITEMS release sanitizer)
            set(native_http_extra)
            if(mode STREQUAL "sanitizer")
                set(native_http_extra --sanitize)
            endif()
            add_test(NAME native_http_compiled_actor_${mode}
                COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_http_build_and_test.py"
                    --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_RT_AOT_CLANG}"
                    --cxx "${ORE_RT_AOT_CXX}" --root "${CMAKE_CURRENT_SOURCE_DIR}" ${native_http_extra})
            set_tests_properties(native_http_compiled_actor_${mode} PROPERTIES TIMEOUT 180)
        endforeach()
    endif()

    foreach(mode IN ITEMS release sanitizer)
        set(native_method_extra)
        if(mode STREQUAL "sanitizer")
            set(native_method_extra --sanitize)
        endif()
        add_test(NAME native_source_class_methods_${mode}
            COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_class_methods.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}" ${native_method_extra})
        set_tests_properties(native_source_class_methods_${mode} PROPERTIES TIMEOUT 120)
    endforeach()

    # Real .atto compiler -> LLVM IR -> native OS process, plus negative
    # no-IR compiler admissions. This test must run, not merely exist in git.
    add_test(NAME native_mtd_receiver_v1
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-mtd-receiver-v1.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
    set_tests_properties(native_mtd_receiver_v1 PROPERTIES TIMEOUT 120)
    if(APPLE OR CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_test(NAME native_mtd_receiver_v1_sanitized
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-mtd-receiver-v1.sh"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}" --sanitize)
        set_tests_properties(native_mtd_receiver_v1_sanitized PROPERTIES TIMEOUT 180)
    endif()

    foreach(mode IN ITEMS release sanitizer)
        set(native_bool_extra)
        if(mode STREQUAL "sanitizer")
            set(native_bool_extra --sanitize)
        endif()
        add_test(NAME native_source_bool_${mode}
            COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_bool_types.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}" ${native_bool_extra})
        set_tests_properties(native_source_bool_${mode} PROPERTIES TIMEOUT 120)
    endforeach()

    # Source-level compiler admission (not a host-only ownership model):
    # rejected sources must fail before emitting LLVM IR, and three accepted
    # controls must produce IR to prevent parser-only false positives.
    find_program(ORE_BORROW_RUSTC rustc REQUIRED)
    set(ORE_BORROW_ADMISSION_BIN "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-admission${CMAKE_EXECUTABLE_SUFFIX}")
    set(ORE_BORROW_PREDICATES_BIN "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-predicates${CMAKE_EXECUTABLE_SUFFIX}")
    add_custom_command(OUTPUT "${ORE_BORROW_ADMISSION_BIN}" "${ORE_BORROW_PREDICATES_BIN}"
        COMMAND "${ORE_BORROW_RUSTC}" --edition=2021 -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_borrow_admission.rs" -o "${ORE_BORROW_ADMISSION_BIN}"
        COMMAND "${ORE_BORROW_RUSTC}" --edition=2021 -D warnings --test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_borrow_admission.rs" -o "${ORE_BORROW_PREDICATES_BIN}"
        DEPENDS tests/native_borrow_admission.rs VERBATIM)
    add_custom_target(oreslang-native-borrow-admission-build ALL
        DEPENDS "${ORE_BORROW_ADMISSION_BIN}" "${ORE_BORROW_PREDICATES_BIN}")
    add_test(NAME native_compile_time_borrow_escape_admission
        COMMAND "${ORE_BORROW_ADMISSION_BIN}" "$<TARGET_FILE:oreslang-llvmc>")
    add_test(NAME native_borrow_harness_predicates COMMAND "${ORE_BORROW_PREDICATES_BIN}")
    set_tests_properties(native_compile_time_borrow_escape_admission
        PROPERTIES TIMEOUT 90)

    # Supplemental real compiler frontend unit: reject invalid linked method
    # slots before call-site resolution; not a substitute for executed guests.
    add_executable(oreslang-native-static-dispatch-resolver-test
        tests/native_static_dispatch_resolver_test.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp)
    target_include_directories(oreslang-native-static-dispatch-resolver-test
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-static-dispatch-resolver-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-static-dispatch-resolver-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_static_dispatch_resolver_ir_integrity
        COMMAND oreslang-native-static-dispatch-resolver-test)
    set_tests_properties(native_static_dispatch_resolver_ir_integrity
        PROPERTIES TIMEOUT 20)

    # Compile the real post-checked native scalar specialization planner with
    # the project C++17 standard; reject forged generic identities and ABI.
    add_executable(oreslang-native-scalar-specialization-plan-test
        tests/native_scalar_specialization_plan_test.cpp)
    target_include_directories(oreslang-native-scalar-specialization-plan-test
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-scalar-specialization-plan-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-scalar-specialization-plan-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_scalar_i64_specialization_plan
        COMMAND oreslang-native-scalar-specialization-plan-test)
    set_tests_properties(native_scalar_i64_specialization_plan PROPERTIES TIMEOUT 20)

    # Not a predicate-only proof: source -> actual LLVM compiler -> IR with
    # one canonical generic implementation -> Clang -> executed native guest.
    if(ORE_CLANG)
        add_test(NAME native_scalar_i64_specialization_guest
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_scalar_generic_source_execution.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        set_tests_properties(native_scalar_i64_specialization_guest PROPERTIES TIMEOUT 180)
    endif()

    # Direct nominal calls are compiler-bound before linking; prove this
    # against emitted IR and executed O0/O2 AttoLang guests (not host models).
    # The independent Rust predicate tests reject function-pointer regressions.
    set(ORE_STATIC_DISPATCH_BIN "${CMAKE_CURRENT_BINARY_DIR}/oreslang-static-dispatch-aot${CMAKE_EXECUTABLE_SUFFIX}")
    set(ORE_STATIC_DISPATCH_PREDICATES_BIN "${CMAKE_CURRENT_BINARY_DIR}/oreslang-static-dispatch-predicates${CMAKE_EXECUTABLE_SUFFIX}")
    add_custom_command(OUTPUT "${ORE_STATIC_DISPATCH_BIN}" "${ORE_STATIC_DISPATCH_PREDICATES_BIN}"
        COMMAND "${ORE_BORROW_RUSTC}" --edition=2021 -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_static_dispatch_aot.rs" -o "${ORE_STATIC_DISPATCH_BIN}"
        COMMAND "${ORE_BORROW_RUSTC}" --edition=2021 -D warnings --test
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_static_dispatch_aot.rs" -o "${ORE_STATIC_DISPATCH_PREDICATES_BIN}"
        DEPENDS tests/native_static_dispatch_aot.rs VERBATIM)
    add_custom_target(oreslang-static-dispatch-aot-build ALL
        DEPENDS "${ORE_STATIC_DISPATCH_BIN}" "${ORE_STATIC_DISPATCH_PREDICATES_BIN}")
    add_test(NAME native_static_dispatch_aot
        COMMAND "${ORE_STATIC_DISPATCH_BIN}" "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
    add_test(NAME native_static_dispatch_predicates
        COMMAND "${ORE_STATIC_DISPATCH_PREDICATES_BIN}")
    set_tests_properties(native_static_dispatch_aot native_static_dispatch_predicates
        PROPERTIES TIMEOUT 90)

    add_test(NAME native_shared_borrow_contract
        COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-alias-matrix"
            --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}"
            --corpus "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/borrow-contract.tsv")
    set_tests_properties(native_shared_borrow_contract PROPERTIES TIMEOUT 120)

    add_test(NAME native_source_module_graph_execution
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_module_imports.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_RT_AOT_CLANG}")
    set_tests_properties(native_source_module_graph_execution PROPERTIES TIMEOUT 120)
    
    # Portable AttoLang source should reach real LLVM IR and an O0/O2 native
    # guest binary. The shell gate uses no Python or synthetic interpreters.
    # This is the scalar/import subset only, NOT Java TestKit/actor parity.
    if(UNIX)
        find_program(ORE_ATTO_BASH NAMES bash REQUIRED)
        add_test(NAME native_atto_first_source_import_aot_parity
            COMMAND "${ORE_ATTO_BASH}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_first_aot_import_parity.sh"
                "$<TARGET_FILE:oreslang-llvmc>"
                "${ORE_RT_AOT_CLANG}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto-first-parity")
        set_tests_properties(native_atto_first_source_import_aot_parity
            PROPERTIES TIMEOUT 90)
        # Independent source-free fault injection: fake compilers must never
        # yield a successful parity verdict. Does NOT count as native guest PASS.
        add_test(NAME native_atto_first_aot_gate_fault_injection
            COMMAND "${ORE_ATTO_BASH}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_atto_first_aot_import_gate.sh"
                "${ORE_RT_AOT_CLANG}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto-first-parity")
        set_tests_properties(native_atto_first_aot_gate_fault_injection
            PROPERTIES TIMEOUT 60)
    endif()

    # Pure host resolver security test for canonical +/ project-root imports.
    # This does not claim pkg:/bare-path resolution or full TOML semantics.
    add_executable(oreslang-native-project-root-import-test
        tests/project_root_import_test.cpp)
    target_include_directories(oreslang-native-project-root-import-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-project-root-import-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_project_root_import_security
        COMMAND oreslang-native-project-root-import-test)
    set_tests_properties(native_project_root_import_security PROPERTIES TIMEOUT 20)

    function(oreslang_contract_test name fixture)
        add_test(NAME ${name}
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/contract.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/${fixture}"
                ${ARGN})
    endfunction()

    # Actual LLVM IR -> Clang -> native guest execution of shared-source i64 candidates.
    # These CTests must fail (never skip) when the compiler/lowerer lacks parity.
    oreslang_contract_test(native_six_backend_i64_40
        six-backend-i64/40-wide-i64-difference.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_40 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_41
        six-backend-i64/41-min-i64-successor.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_41 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_42
        six-backend-i64/42-max-i64-predecessor.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_42 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_43
        six-backend-i64/43-wide-checked-product.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_43 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_44
        six-backend-i64/44-double-negative-product.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_44 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_45
        six-backend-i64/45-grouped-negation.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_45 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_46
        six-backend-i64/46-named-bitxor.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_46 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_47
        six-backend-i64/47-mantissa-precision-product.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_47 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_48
        six-backend-i64/48-max-i64-delta.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_48 PROPERTIES TIMEOUT 90)
    oreslang_contract_test(native_six_backend_i64_49
        six-backend-i64/49-signed-boundary-product.ores
        --run 42 --clang "${ORE_SOURCE_I64_CLANG}")
    set_tests_properties(native_six_backend_i64_49 PROPERTIES TIMEOUT 90)


    # HTTP actor source lowering is not verified on LLVM. These compiler-
    # negative fixtures must exercise the real oreslang-llvmc, not a host model.
    oreslang_contract_test(native_http_actor_serial_alias_failclosed
        native-http-actor-serial-failclosed.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_http_actor_explicit_1x1_failclosed
        native-http-actor-explicit-1x1-failclosed.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_http_actor_nonserial_3x4_failclosed
        native-http-actor-nonserial-3x4-failclosed.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_http_actor_public_failclosed
        native-http-actor-public-failclosed.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")

    # Canonical shared AttoLang fixtures: identical Git blobs to Java and
    # oreslang-platform-conformance. Until real HTTP actor lowering exists,
    # each must fail with the explicit capability diagnostic.
    oreslang_contract_test(native_http_actor_canonical_serial_failclosed
        http-actor-v1/serial-1x1.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_http_actor_canonical_explicit_failclosed
        http-actor-v1/explicit-1x1.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_http_actor_canonical_three_four_failclosed
        http-actor-v1/unsupported-3x4.atto --diagnostic "HTTP_ACTOR_BACKEND_UNSUPPORTED")

    # Durable M0 language declarations are compiler-metadata only in Java.
    # LLVM does not have the history/manifest/replay ABI, so native compilation
    # must fail with the same explicit capability marker for all introducers.
    # These invoke the real oreslang-llvmc; they cannot be green on a parser mock.
    oreslang_contract_test(native_durable_m0_activity_failclosed
        durable-m0-activity-reject.atto --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_durable_m0_workflow_failclosed
        durable-m0-workflow-reject.atto --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_durable_m0_public_activity_failclosed
        durable-m0-public-activity-reject.atto --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_durable_m0_public_workflow_failclosed
        durable-m0-public-workflow-reject.atto --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    oreslang_contract_test(native_durable_m0_nested_comment_failclosed
        durable-m0-comment-gap-reject.atto --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    # A durable declaration in comments must not poison an ordinary scalar guest.
    oreslang_contract_test(native_durable_m0_comment_nontrigger
        durable-m0-quoted-comments-native.atto --ir "ret i64 42")

    add_test(NAME native_durable_m0_shared_corpus_failclosed
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/contract.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --fixture "${CMAKE_CURRENT_SOURCE_DIR}/contracts/durable-m0/v1/checkout.atto"
            --diagnostic "DURABLE_M0_BACKEND_UNSUPPORTED")
    set_tests_properties(native_durable_m0_shared_corpus_failclosed
        PROPERTIES TIMEOUT 30)

    # Same 1,671-byte canonical manifest reference checked in .NET/Wasm and
    # platform conformance. This does NOT imply LLVM emitted the manifest.
    add_test(NAME native_durable_m0_manifest_v1_reference_bytes
        COMMAND "${CMAKE_COMMAND}" -P
            "${CMAKE_CURRENT_SOURCE_DIR}/contracts/durable-m0/v1/verify-manifest.cmake")
    set_tests_properties(native_durable_m0_manifest_v1_reference_bytes
        PROPERTIES TIMEOUT 15)
    # ADT source admission reservation (NOT semantic-negative conformance).
    # These files are byte-identical to reviewed canonical Oreslang fixtures
    # in oreslang-platform-conformance #270. The current LLVM source frontend
    # must refuse user-defined enums before emitting even partial IR.
    # Any future LLVM implementation MUST replace this reservation with real
    # source positive/negative execution checks before claiming parity.
    foreach(adt_name IN ITEMS
            nullary payload owner max-tag guarded-not-exhaustive nested-foreign-payload)
        oreslang_contract_test(native_adt_unsupported_${adt_name}
            "adt-source-v1/${adt_name}.ores"
            --diagnostic "native user-defined tagged enum requires ADT layout and constructor lowering")
        set_tests_properties(native_adt_unsupported_${adt_name}
            PROPERTIES TIMEOUT 30)
    endforeach()
    file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/flags2env-client-abi-status.ores" FLAGS2ENV_CLIENT_ABI_SHA256)
    if(NOT FLAGS2ENV_CLIENT_ABI_SHA256 STREQUAL "9add46476c703374f76dd1a0a5827fbad0eb69ff703f4f3cf302604a6f506750")
        message(FATAL_ERROR "flags2env client ABI fixture bytes drifted")
    endif()

    # Exact flags2env Oreslang client ABI scalar fixture. This is real
    # Oreslang source -> LLVM IR -> native executable execution; it proves
    # only the shared numeric host-status contract, not String/FFI support.
    if(ORE_CLANG)
        oreslang_contract_test(flags2env_client_abi_status_native
            flags2env-client-abi-status.ores
            --run 42 --clang "${ORE_CLANG}")
        set_tests_properties(flags2env_client_abi_status_native
            PROPERTIES TIMEOUT 90)
    else()
        message(FATAL_ERROR "flags2env client ABI status parity requires Clang")
    endif()

    # A .atto guest fixture is NOT accepted as a working actor pipeline:
    # LLVM must reject before emitting partial IR until typed nested graph
    # lowering, supervisor scopes and transactional start are implemented.
    oreslang_contract_test(native_nested_actor_pipe_guest_failclosed
        nested-actor-pipe-unsupported.atto
        --diagnostic "NESTED_ACTOR_PIPE_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_pipe_type_sugar_failclosed
        actor-pipe-types-unsupported.atto
        --diagnostic "NESTED_ACTOR_PIPE_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_pipe_generic_failclosed
        actor-pipe-generic-unsupported.atto
        --diagnostic "NESTED_ACTOR_PIPE_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_pipe_namespace_failclosed
        actor-pipe-namespace-unsupported.atto
        --diagnostic "NESTED_ACTOR_PIPE_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_connect_guest_failclosed
        actor-connect-unsupported.atto
        --diagnostic "ACTOR_CONNECT_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_connect_generic_failclosed
        actor-connect-generic-unsupported.atto
        --diagnostic "ACTOR_CONNECT_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_pipe_comments_gap_failclosed
        actor-pipe-comment-gap-unsupported.atto
        --diagnostic "NESTED_ACTOR_PIPE_LLVM_NOT_IMPLEMENTED")
    oreslang_contract_test(native_actor_connect_namespace_failclosed
        actor-connect-namespace-unsupported.atto
        --diagnostic "ACTOR_CONNECT_LLVM_NOT_IMPLEMENTED")
    # Compiler-positive control: reserved factory names inside comments do
    # not trigger source admission; actual Clang executable must exit 7.
    oreslang_contract_test(native_actor_pipe_lexical_comment_control
        actor-pipe-lexical-control.atto --run 7 --clang "${ORE_CLANG}")

    # A Python -O/PYTHONOPTIMIZE run must never turn failed compiler
    # admission/IR checks into a green CTest. Uses only fake compilers;
    # no LLVM toolchain or private-source mirror is needed.
    add_test(NAME native_contract_harness_optimize_failclosed
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_contract_harness.py")
    add_test(NAME native_contract_harness_python_O
        COMMAND "${Python3_EXECUTABLE}" -O
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_contract_harness.py")
    add_test(NAME native_contract_harness_python_OO
        COMMAND "${Python3_EXECUTABLE}" -OO
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_contract_harness.py")
    set_tests_properties(
        native_contract_harness_optimize_failclosed
        native_contract_harness_python_O
        native_contract_harness_python_OO
        PROPERTIES TIMEOUT 240)

    # Validate the CTest inventory itself, including non-skippable admission,
    # optimized Python harness registration and native executable gates.
    add_test(NAME native_ctest_manifest_harness_adversarial
        COMMAND "${Python3_EXECUTABLE}" -m unittest discover
            -s "${CMAKE_CURRENT_SOURCE_DIR}/tests"
            -p "test_ctest_manifest_gate.py" -v)
    set_tests_properties(native_ctest_manifest_harness_adversarial
        PROPERTIES TIMEOUT 30)

    # Reject all unsupported source-level scheduler suspension until verified
    # heap-owned continuations exist. The harness rejects any partial LLVM IR.
    oreslang_contract_test(native_rt_while_loop_failclosed
        native-rt-while-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_for_loop_failclosed
        native-rt-for-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_unbounded_loop_failclosed
        native-rt-loop-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_cooperate_failclosed
        native-rt-cooperate-reject.ores
        --diagnostic "native rt cooperate requires verified heap-owned continuation lowering")
    oreslang_contract_test(native_rt_cooperate_expression_failclosed
        native-rt-cooperate-expression-reject.ores
        --diagnostic "native rt cooperate requires verified heap-owned continuation lowering")
    oreslang_contract_test(native_rt_nested_while_failclosed
        native-rt-nested-while-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_nested_for_failclosed
        native-rt-nested-for-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_nested_loop_failclosed
        native-rt-nested-loop-reject.ores
        --diagnostic "native realtime source loop requires verified LLVM continuation lowering")
    oreslang_contract_test(native_rt_nested_cooperate_failclosed
        native-rt-nested-cooperate-reject.ores
        --diagnostic "native rt cooperate requires verified heap-owned continuation lowering")

    oreslang_contract_test(native_generator_v1_async_failclosed
        generator-v1-async-reject.ores
        --diagnostic "native async gen* requires scheduler-owned async iterator continuation lowering")
    oreslang_contract_test(native_generator_v1_suspend_failclosed
        generator-v1-suspend-reject.ores
        --diagnostic "native suspends declaration requires suspension-aware borrow checking")
    foreach(generator_case IN ITEMS
            legacy-fnc legacy-routine legacy-async-fnc)
        # WILL_FAIL inverts a matched PASS_REGULAR_EXPRESSION, so that form could never pass.
        oreslang_contract_test(native_generator_v1_${generator_case}_failclosed
            generator-v1-${generator_case}-reject.ores --diagnostic "native generator v1 requires iterator-frame, ownership and LLVM continuation lowering")
    endforeach()

    add_executable(oreslang-ownership-cfg-dataflow-test
        tests/ownership_cfg_dataflow_test.cpp)
    target_include_directories(oreslang-ownership-cfg-dataflow-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-ownership-cfg-dataflow-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ownership_cfg_dataflow_fixed_point
        COMMAND oreslang-ownership-cfg-dataflow-test)
    # HOST ONLY: additional path/loan-token fixed-point negatives. These do
    # not certify typed MIR->LLVM SSA identity or native guest semantics.
    add_executable(oreslang-ownership-cfg-phi-loan-test
        tests/ownership_cfg_phi_loan_adversarial_test.cpp)
    target_include_directories(oreslang-ownership-cfg-phi-loan-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-ownership-cfg-phi-loan-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_ownership_cfg_phi_loan_adversarial_host_only
        COMMAND oreslang-ownership-cfg-phi-loan-test)
    set_tests_properties(native_ownership_cfg_phi_loan_adversarial_host_only
        PROPERTIES TIMEOUT 20)
    # Field-wise native destruction verifier and concrete owner flags.
    # Separate from unsupported source-level nontrivial class drop glue.
    add_executable(oreslang-ownership-drop-plan-test
        tests/ownership_drop_plan_test.cpp)
    target_include_directories(oreslang-ownership-drop-plan-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-ownership-drop-plan-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ownership_cfg_exact_once_field_destruction
        COMMAND oreslang-ownership-drop-plan-test)
    # Actor graph heap host drop-glue order. This is not guest-source lowering.
    add_executable(oreslang-actor-graph-reverse-drop-test
        tests/actor_graph_reverse_drop_test.cpp)
    target_include_directories(oreslang-actor-graph-reverse-drop-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-actor-graph-reverse-drop-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-actor-graph-reverse-drop-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_graph_reverse_destructor_order
        COMMAND oreslang-actor-graph-reverse-drop-test)
    add_executable(oreslang-target-abi-test tests/target_abi_test.cpp)
    target_include_directories(oreslang-target-abi-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-target-abi-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME portable_target_abi_v1 COMMAND oreslang-target-abi-test
        "${CMAKE_CURRENT_SOURCE_DIR}/contracts/architecture/v1/targets.tsv")
    add_executable(oreslang-dop-kernel-test tests/dop_kernel_lowering_test.cpp)
    target_include_directories(oreslang-dop-kernel-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-dop-kernel-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-dop-kernel-test PRIVATE Threads::Threads)
    add_test(NAME dop_typed_cpu_opencl_ownership COMMAND oreslang-dop-kernel-test)
    add_executable(oreslang-dop-gpu-admission-test tests/dop_gpu_admission_test.cpp)
    target_include_directories(oreslang-dop-gpu-admission-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-dop-gpu-admission-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-dop-gpu-admission-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME dop_gpu_exact_layout_fence_admission COMMAND oreslang-dop-gpu-admission-test)
    # Host-only parity for the source-visible std/compute descriptor contract.
    # These values are metadata only; they carry no device/buffer/fence authority
    # and do not imply source-module linking or physical GPU execution.
    add_executable(oreslang-portable-compute-descriptor-test
        tests/portable_compute_descriptor_test.cpp)
    target_include_directories(oreslang-portable-compute-descriptor-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-portable-compute-descriptor-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_portable_compute_descriptor_contract
        COMMAND oreslang-portable-compute-descriptor-test)
    add_executable(oreslang-portable-compute-authority-test
        tests/portable_compute_authority_test.cpp)
    target_include_directories(oreslang-portable-compute-authority-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-portable-compute-authority-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-portable-compute-authority-test PRIVATE Threads::Threads)
    add_test(NAME native_portable_compute_authority_contract
        COMMAND oreslang-portable-compute-authority-test)
    add_executable(oreslang-portable-compute-kernel-g2-test
        tests/portable_compute_kernel_g2_test.cpp)
    target_include_directories(oreslang-portable-compute-kernel-g2-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-portable-compute-kernel-g2-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_portable_compute_kernel_g2_contract
        COMMAND oreslang-portable-compute-kernel-g2-test)
    add_executable(oreslang-portable-compute-opencl-g2-test
        tests/portable_compute_opencl_g2_test.cpp)
    target_include_directories(oreslang-portable-compute-opencl-g2-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-portable-compute-opencl-g2-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_portable_compute_opencl_g2_contract
        COMMAND oreslang-portable-compute-opencl-g2-test)
    add_executable(oreslang-opencl-provider-g3-test
        tests/opencl_provider_g3_test.cpp)
    target_include_directories(oreslang-opencl-provider-g3-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-opencl-provider-g3-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-opencl-provider-g3-test PRIVATE Threads::Threads)
    add_test(NAME native_opencl_provider_g3_state_machine
        COMMAND oreslang-opencl-provider-g3-test)
    set_tests_properties(native_opencl_provider_g3_state_machine PROPERTIES TIMEOUT 30)

    add_executable(oreslang-opencl-system-gpu-probe
        tests/opencl_system_gpu_probe.cpp)
    target_include_directories(oreslang-opencl-system-gpu-probe PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-opencl-system-gpu-probe PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-opencl-system-gpu-probe PRIVATE
        Threads::Threads ${CMAKE_DL_LIBS})
    add_test(NAME native_opencl_real_gpu_probe_conditional
        COMMAND oreslang-opencl-system-gpu-probe)
    set_tests_properties(native_opencl_real_gpu_probe_conditional PROPERTIES TIMEOUT 30)
    add_executable(oreslang-dop-read-loan-thread-test tests/dop_hostbuffer_concurrency_test.cpp)
    target_include_directories(oreslang-dop-read-loan-thread-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-dop-read-loan-thread-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-dop-read-loan-thread-test PRIVATE Threads::Threads)
    add_test(NAME dop_hostbuffer_multithread_read_loan_quiescence
        COMMAND oreslang-dop-read-loan-thread-test)
    set_tests_properties(dop_hostbuffer_multithread_read_loan_quiescence PROPERTIES TIMEOUT 25)
    # Pure compile-time ECS component/chunk dependency planning. No guest
    # multi-threaded lowering or actor-state loan splitting is implied.
    add_executable(oreslang-dop-access-planner-test tests/dop_system_access_planner_test.cpp)
    target_include_directories(oreslang-dop-access-planner-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-dop-access-planner-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME dop_static_access_batch_conflicts
        COMMAND oreslang-dop-access-planner-test)
    # Backend-independent ownership auto traits plus logical DOP place
    # conflicts. This is a semantic gate, not source syntax/lowering proof.
    add_executable(oreslang-ownership-auto-traits-dop-test
        tests/ownership_auto_traits_dop_test.cpp)
    target_include_directories(oreslang-ownership-auto-traits-dop-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-ownership-auto-traits-dop-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ownership_auto_traits_and_logical_dop
        COMMAND oreslang-ownership-auto-traits-dop-test)
    # Future-pipeline ownership proof under the strict 2026-10-27
    # no-auto-flatten decision. The consumed Future<int> source slice below is
    # intentionally narrower; general Future payloads and borrowed roots remain
    # fail-closed. An async unary stage returns another Future layer and is
    # rejected until an explicit checked flatten/flat_map lowering exists.
    add_executable(oreslang-future-pipeline-ownership-contract-test
        tests/future_pipeline_ownership_contract_test.cpp)
    target_include_directories(oreslang-future-pipeline-ownership-contract-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-future-pipeline-ownership-contract-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_future_pipeline_ownership_contract
        COMMAND oreslang-future-pipeline-ownership-contract-test)
    add_executable(oreslang-future-pipeline-authority-fingerprint-test
        tests/future_pipeline_authority_fingerprint_test.cpp)
    target_include_directories(oreslang-future-pipeline-authority-fingerprint-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-future-pipeline-authority-fingerprint-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_future_pipeline_post_ownership_rewrite_guard
        COMMAND oreslang-future-pipeline-authority-fingerprint-test)
    add_executable(oreslang-quantum-circuit-test tests/quantum_circuit_lowering_test.cpp)
    target_include_directories(oreslang-quantum-circuit-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-quantum-circuit-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME quantum_circuit_qasm3_simulator COMMAND oreslang-quantum-circuit-test)
    add_executable(oreslang-gpu-fence-test tests/gpu_buffer_fence_test.cpp)
    target_include_directories(oreslang-gpu-fence-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-gpu-fence-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-gpu-fence-test PRIVATE Threads::Threads)
    add_test(NAME gpu_device_generation_quiescence_fences COMMAND oreslang-gpu-fence-test)

    # Scalar match/switch parity. Value forms lower to lazy conditional
    # expressions (ARM_RESULT); do forms lower to structured statement CFG
    # where arm returns retain FUNCTION_RETURN semantics. Async suspension,
    # replay-unsafe subjects, duplicate cases, and nb dispatch stay fail-closed.
    # Java-compatible eager scalar |> lowering and fail-closed actor pipe.
    oreslang_contract_test(native_scalar_value_pipeline_call_ir
        scalar-pipeline-run.ores --ir "call")
    oreslang_contract_test(native_scalar_value_pipeline_named_ir
        scalar-pipeline-named-run.ores --ir "call")
    oreslang_contract_test(native_scalar_value_pipeline_bad_arity
        scalar-pipeline-arity-reject.ores --diagnostic "call arity mismatch")
    oreslang_contract_test(native_scalar_value_pipeline_bad_type
        scalar-pipeline-type-reject.ores --diagnostic "requires int, got bool")
    oreslang_contract_test(native_scalar_value_pipeline_noncallable
        scalar-pipeline-noncallable-reject.ores --diagnostic "native scalar pipeline stage must be a named function")
    oreslang_contract_test(native_scalar_value_pipeline_budget
        scalar-pipeline-budget-reject.ores --diagnostic "native scalar pipeline exceeds 64 stages")
    oreslang_contract_test(native_actor_pipe_rejects_untyped_edges
        actor-pipe-untyped-reject.ores --diagnostic "actor pipe expressions require typed roles")
    if(ORE_CLANG)
        oreslang_contract_test(native_scalar_value_pipeline_call_runtime
            scalar-pipeline-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_scalar_value_pipeline_named_runtime
            scalar-pipeline-named-run.ores --run 42 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(native_value_match_arm_result
        dispatch-value-match-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_value_match_default_ir
        dispatch-value-match-default-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_value_match_default_only_ir
        dispatch-value-match-default-only-run.ores --ir "ret i64 42")
    oreslang_contract_test(native_do_match_default_ir
        dispatch-do-match-default-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_do_match_default_only_ir
        dispatch-do-match-default-only-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_value_match_default_must_be_last
        dispatch-value-match-default-not-last-reject.ores
        --diagnostic "native value match default arm must be last")
    oreslang_contract_test(native_do_match_default_must_be_last
        dispatch-do-match-default-not-last-reject.ores
        --diagnostic "native do match default arm must be last")
    oreslang_contract_test(native_tilde_map_requires_typed_native_lowering
        tilde-map-literal-native-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    oreslang_contract_test(native_tilde_record_requires_typed_native_lowering
        tilde-record-literal-native-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    oreslang_contract_test(native_match_fallback_hardening_1
        dispatch-value-match-wildcard-then-default-reject.ores
        --diagnostic "native value match wildcard arm must be last")
    oreslang_contract_test(native_match_fallback_hardening_2
        dispatch-do-match-wildcard-then-default-reject.ores
        --diagnostic "native do match wildcard arm must be last")
    oreslang_contract_test(native_match_fallback_hardening_3
        dispatch-value-match-duplicate-case-reject.ores
        --diagnostic "duplicate native value match pattern")
    oreslang_contract_test(native_match_fallback_hardening_4
        dispatch-do-match-duplicate-case-reject.ores
        --diagnostic "duplicate native do match pattern")
    oreslang_contract_test(native_structural_field_failclosed_1
        dispatch-value-structural-field-reject.ores
        --diagnostic "native structural field pattern requires a stack-constructed const class local")
    oreslang_contract_test(native_structural_field_failclosed_2
        dispatch-do-structural-field-reject.ores
        --diagnostic "native structural field pattern requires a stack-constructed const class local")
    oreslang_contract_test(native_structural_field_failclosed_3
        dispatch-value-record-field-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    oreslang_contract_test(native_structural_field_failclosed_4
        dispatch-do-record-field-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    oreslang_contract_test(native_structural_local_1
        dispatch-value-public-class-field-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_structural_local_2
        dispatch-do-public-class-field-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_structural_local_3
        dispatch-value-private-class-field-reject.ores --diagnostic "native structural field pattern requires a declared public primitive field")
    oreslang_contract_test(native_structural_local_4
        dispatch-value-mutable-class-owner-reject.ores --diagnostic "native structural field pattern requires a stack-constructed const class local")
    oreslang_contract_test(native_structural_local_5
        dispatch-value-structural-mixed-fields-reject.ores --diagnostic "native ordinary structural match cannot prove differently named field arms disjoint")
    oreslang_contract_test(native_structural_local_6
        dispatch-value-structural-duplicate-reject.ores --diagnostic "duplicate native structural field match pattern")
    oreslang_contract_test(native_structural_local_7
        dispatch-value-structural-multi-field-reject.ores --diagnostic "native structural field pattern supports exactly one field")
    oreslang_contract_test(native_structural_local_8
        dispatch-value-structural-wrong-type-reject.ores --diagnostic "native structural field pattern literal type does not match declared field")
    # Match-default hardening: ensure unmatched arms actually execute the
    # fallback, and reject duplicate/guarded patterns and tilde bypasses.
    oreslang_contract_test(native_value_match_default_fallthrough_ir
        dispatch-value-match-default-fallthrough-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_do_match_default_fallthrough_ir
        dispatch-do-match-default-fallthrough-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_value_match_duplicate_pattern
        dispatch-value-match-duplicate-pattern-reject.ores
        --diagnostic "duplicate native value match pattern")
    oreslang_contract_test(native_do_match_duplicate_pattern
        dispatch-do-match-duplicate-pattern-reject.ores
        --diagnostic "duplicate native do match pattern")
    oreslang_contract_test(native_value_match_guard_failclosed
        dispatch-value-match-guard-reject.ores
        --diagnostic "native scalar value match binding/guard patterns require dedicated pattern lowering")
    oreslang_contract_test(native_do_match_guard_failclosed
        dispatch-do-match-guard-reject.ores
        --diagnostic "native scalar do match binding/guard patterns require dedicated pattern lowering")
    oreslang_contract_test(native_tilde_explicit_record_failclosed
        tilde-explicit-record-native-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    oreslang_contract_test(native_tilde_ambiguous_empty_failclosed
        tilde-ambiguous-empty-native-reject.ores
        --diagnostic "native tilde map/record literal unsupported")
    # Strict fallback admission: no duplicate, guarded or 'on default' forms.
    oreslang_contract_test(native_value_match_default_fallback_ir
        dispatch-value-match-default-fallback-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_do_match_default_fallback_ir
        dispatch-do-match-default-fallback-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_value_match_duplicate_default_reject
        dispatch-value-match-default-duplicate-reject.ores
        --diagnostic "native value match can contain only one default arm")
    oreslang_contract_test(native_do_match_duplicate_default_reject
        dispatch-do-match-default-duplicate-reject.ores
        --diagnostic "native do match can contain only one default arm")
    oreslang_contract_test(native_value_match_on_default_reject
        dispatch-value-match-on-default-reject.ores
        --diagnostic "native value match fallback must use 'default ->' without 'on'")
    oreslang_contract_test(native_do_match_on_default_reject
        dispatch-do-match-on-default-reject.ores
        --diagnostic "native do match fallback must use 'default ->' without 'on'")
    oreslang_contract_test(native_value_match_guarded_default_reject
        dispatch-value-match-default-guard-reject.ores
        --diagnostic "native value match default arm cannot have a guard or binding")
    oreslang_contract_test(native_do_match_guarded_default_reject
        dispatch-do-match-default-guard-reject.ores
        --diagnostic "native do match default arm cannot have a guard or binding")
    # Direct signed-minimum literal remains valid; nested unary negation
    # would overflow host i64 and must reject before any LLVM IR.
    oreslang_contract_test(native_dispatch_signed_min_literal_ir
        dispatch-signed-min-pattern-run.ores --ir "br i1 true")
    oreslang_contract_test(native_dispatch_nested_min_match_failclosed
        dispatch-nested-signed-min-match-reject.ores
        --diagnostic "native scalar value match supports literal patterns or '_'")
    oreslang_contract_test(native_dispatch_nested_min_do_switch_failclosed
        dispatch-nested-signed-min-do-switch-reject.ores
        --diagnostic "native do switch case must be a scalar compile-time constant")
    oreslang_contract_test(native_dispatch_negative_zero_duplicate
        dispatch-negative-zero-duplicate-reject.ores
        --diagnostic "duplicate native value switch case")
    oreslang_contract_test(native_value_switch_arm_result
        dispatch-value-switch-run.ores --ir "ternary.merge")
    oreslang_contract_test(native_do_match_function_return
        dispatch-do-match-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_do_switch_function_return
        dispatch-do-switch-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_do_switch_default_only
        dispatch-do-switch-default-only-run.ores --ir "return.snapshot.slot")
    oreslang_contract_test(native_do_match_wildcard_only
        dispatch-do-match-wildcard-only-run.ores --ir "return.snapshot.slot")
    if(ORE_CLANG)
        oreslang_contract_test(native_dispatch_signed_min_literal_runtime
            dispatch-signed-min-pattern-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_match_arm_result_runtime
            dispatch-value-match-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_match_default_runtime
            dispatch-value-match-default-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_match_default_only_runtime
            dispatch-value-match-default-only-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_default_runtime
            dispatch-do-match-default-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_default_only_runtime
            dispatch-do-match-default-only-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_match_default_fallthrough_runtime
            dispatch-value-match-default-fallthrough-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_default_fallthrough_runtime
            dispatch-do-match-default-fallthrough-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_match_default_fallback_runtime
            dispatch-value-match-default-fallback-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_default_fallback_runtime
            dispatch-do-match-default-fallback-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_value_switch_arm_result_runtime
            dispatch-value-switch-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_function_return_runtime
            dispatch-do-match-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_switch_function_return_runtime
            dispatch-do-switch-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_switch_default_only_runtime
            dispatch-do-switch-default-only-run.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_do_match_wildcard_only_runtime
            dispatch-do-match-wildcard-only-run.ores --run 42 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(native_value_switch_requires_default
        dispatch-value-switch-nonexhaustive.ores
        --diagnostic "native value switch requires a default arm")
    oreslang_contract_test(native_value_switch_duplicate_case_rejected
        dispatch-value-switch-duplicate.ores
        --diagnostic "duplicate native value switch case")
    oreslang_contract_test(native_value_dispatch_type_mismatch_rejected
        dispatch-value-arm-type-mismatch.ores
        --diagnostic "ternary arms require a common scalar type")
    oreslang_contract_test(native_dispatch_replay_unsafe_subject_rejected
        dispatch-replay-unsafe-subject.ores
        --diagnostic "native value switch subject must be a replay-safe scalar expression")
    oreslang_contract_test(native_dispatch_async_arm_not_yet_lowered
        dispatch-async-arm-unsupported.ores
        --diagnostic "native async Future<int> v1 callable must be private, capture-free, pure")
    oreslang_contract_test(native_nb_match_rejected
        dispatch-invalid-nb-match.ores
        --diagnostic "native nb match/switch is invalid: nb applies to channel/select operations, not dispatch arms")
    oreslang_contract_test(native_nb_switch_rejected
        dispatch-invalid-nb-switch.ores
        --diagnostic "native nb match/switch is invalid: nb applies to channel/select operations, not dispatch arms")

    # Java '/' produces a float even for int/int. Until native float lowering
    # exists, reject '/' and '%' before IR rather than silently emitting i64.
    oreslang_contract_test(native_float_division_unavailable numeric-division-float-reject.ores
        --diagnostic "native floating division unsupported")
    oreslang_contract_test(native_float_division_precedence_unavailable numeric-division-precedence-reject.ores
        --diagnostic "native floating division unsupported")
    oreslang_contract_test(native_decimal_suffix_unavailable numeric-decimal-suffix-reject.ores
        --diagnostic "native floating-point/decimal literal unsupported")
    oreslang_contract_test(native_float_exponent_unavailable numeric-floating-exponent-reject.ores
        --diagnostic "native floating-point/decimal literal unsupported")
    oreslang_contract_test(native_float_fraction_unavailable numeric-floating-fraction-reject.ores
        --diagnostic "native floating-point/decimal literal unsupported")
    oreslang_contract_test(native_integer_remainder_unavailable numeric-remainder-unsupported.ores
        --diagnostic "'%' is reserved")
    oreslang_contract_test(constant_main constant-main.ores --ir "ret i64 42")
    oreslang_contract_test(native_using_typed_effects using-pure-constant.ores --ir "ret i64 42")
    oreslang_contract_test(native_using_unknown_effect using-unknown-effect.ores --diagnostic "unknown function effect")
    oreslang_contract_test(native_using_duplicate_effect using-duplicate-effect.ores --diagnostic "duplicate function effect")
    oreslang_contract_test(native_critical_actor_state_not_lowered critical-handle-state-using.ores --diagnostic "native actor state { ... } requires sealed handle_state lowering")
    oreslang_contract_test(native_requires_rejects_unverified_wcet requires-unverified.ores --diagnostic "native requires clause awaits verified")
    oreslang_contract_test(native_actor_state_sealed_until_lowering state-sealed-unsupported.ores --diagnostic "native actor state")

    # Native OS ABI is i32 even though Oreslang int is represented as i64.
    oreslang_contract_test(native_exit_reject_main_arguments exit-invalid-main-args.ores
        --diagnostic "native main() must have zero arguments")
    oreslang_contract_test(native_exit_reject_reserved_symbol exit-reserved-native-symbol.ores
        --diagnostic "reserved native runtime ABI symbol '__oreslang_guest_main'")
    oreslang_contract_test(native_exit_reject_unsupported_explicit_exit exit-unsupported-process-exit.ores
        --diagnostic "oreslang-llvmc:")
    oreslang_contract_test(native_exit_reject_unsupported_async_root exit-unsupported-async-main.ores
        --diagnostic "native first async Future<int> producer requires async fnc")
    oreslang_contract_test(native_exit_void_main_ir exit-void-main.ores
        --ir "define i32 @main()")
    if(ORE_CLANG)
        oreslang_contract_test(native_exit_void_fallthrough exit-void-main.ores
            --run 0 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_module_main module-main.ores
            --run 7 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_max_valid exit-max-main.ores
            --run 255 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_overflow_fails_closed exit-invalid-positive-main.ores
            --run 1 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_negative_fails_closed exit-invalid-negative-main.ores
            --run 1 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_zero exit-zero-main.ores
            --run 0 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_one exit-one-main.ores
            --run 1 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_wide_64bit_overflow_fails_closed exit-wide-overflow-main.ores
            --run 1 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_void_runs_defer exit-void-deferred-cleanup-main.ores
            --run 0 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exit_no_main_is_not_linkable exit-no-main.ores
            --no-main-link --clang "${ORE_CLANG}")
    endif()
    add_test(NAME native_explicit_wasm32_target_ir
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/wasm32_target_ir_test.py"
            "$<TARGET_FILE:oreslang-llvmc>"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/constant-main.ores")
    oreslang_contract_test(module_main module-main.ores --ir "demo.main")
    # Compile-time i64 contract subset: actually type-check a module before
    # emitting verified IR, then link/run a native main. Cross-unit imports and
    # String contracts must reject without emitting any LLVM IR.
    oreslang_contract_test(native_i64_module_contract_valid native-contract-i64-valid.ores
        --ir "call i64 @Math.add")
    oreslang_contract_test(native_i64_module_contract_missing native-contract-i64-missing.ores
        --diagnostic "missing public native module contract method")
    oreslang_contract_test(native_i64_module_contract_private native-contract-i64-private.ores
        --diagnostic "missing public native module contract method")
    oreslang_contract_test(native_i64_module_contract_arity native-contract-i64-arity.ores
        --diagnostic "native module contract parameter arity mismatch")
    oreslang_contract_test(native_i64_module_contract_borrow native-contract-i64-borrow.ores
        --diagnostic "native module contract ownership mode mismatch")
    oreslang_contract_test(native_i64_module_contract_void native-contract-i64-void.ores
        --diagnostic "native module contract requires int return")
    oreslang_contract_test(native_i64_module_contract_duplicate_parameter native-contract-i64-duplicate-param.ores
        --diagnostic "duplicate native module contract parameter")
    oreslang_contract_test(native_i64_module_contract_split_arrow native-contract-i64-split-arrow.ores
        --diagnostic "contract signatures require contiguous '=>'")
    oreslang_contract_test(native_i64_module_contract_unknown native-contract-i64-unknown.ores
        --diagnostic "unknown native module contract")
    oreslang_contract_test(native_i64_module_contract_duplicate native-contract-i64-duplicate.ores
        --diagnostic "duplicate native module contract method")
    oreslang_contract_test(native_contract_unlinked_import native-contract-i64-import.ores
        --diagnostic "native contract imports require a validated cross-unit linker")
    oreslang_contract_test(native_contract_string_unimplemented native-contract-reject-string.ores
        --diagnostic "expected 'int'")

    oreslang_contract_test(call_scoped_borrow_parameter_refresh unsupported-borrow-param.ores
        --ir "call i64 @inspect" --run 42 --clang "${ORE_CLANG}")

    # Original source-free OresLLM ownership grammar admission probes.
    # These DO NOT import/mirror any private ores-llm source or prove those PR heads.
    oreslang_contract_test(oresllm_explicit_ownership_borrow_accept
        oresllm-explicit-ownership-borrow-accept.ores
        --ir "call i64 @read_oresllm")
    if(ORE_CLANG)
        oreslang_contract_test(oresllm_explicit_ownership_borrow_execute
            oresllm-explicit-ownership-borrow-accept.ores
            --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(oresllm_arithmetic_star_preserved
            oresllm-ownership-multiply-accept.ores
            --run 42 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(oresllm_c_style_reference_parameter_rejected
        oresllm-ownership-ampersand-parameter-reject.ores
        --diagnostic "oreslang-llvmc:")
    oreslang_contract_test(oresllm_c_style_address_of_rejected
        oresllm-ownership-ampersand-expression-reject.ores
        --diagnostic "oreslang-llvmc:")
    oreslang_contract_test(oresllm_c_style_pointer_decl_rejected
        oresllm-ownership-star-pointer-reject.ores
        --diagnostic "oreslang-llvmc:")

    oreslang_contract_test(call_and_forward_ref forward-call-main.ores --ir "call i64 @add")
    oreslang_contract_test(canonical_arrow_entrypoint canonical-arrow-main.ores
        --run 42 --clang "${ORE_CLANG}")
    add_test(NAME native_named_call_lexical_ir_order
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" named-call-order
          "$<TARGET_FILE:oreslang-llvmc>"
          "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/named-call-source-order.ores")
    set_tests_properties(native_named_call_lexical_ir_order PROPERTIES TIMEOUT 30)
    if(ORE_CLANG)
        oreslang_contract_test(native_named_call_reordered_guest named-call-reordered-main.ores
            --run 43 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_named_call_mixed_guest named-call-mixed-main.ores
            --run 43 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_named_call_mut_borrow_binding named-call-mut-borrow-native.ores
            --run 7 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_named_call_overload_arity_guest named-call-arity-overload-main.ores
            --run 43 --clang "${ORE_CLANG}")
    endif()
    # Same exact public Oreslang handler fixture as Java/Graal; execute native code.
    if(ORE_CLANG)
        oreslang_contract_test(shared_java_llvm_http_handler http-guest-counter-handler.ores --ir "call i64 @counter_response" --run 43 --clang "${ORE_CLANG}")
        add_test(NAME shared_java_llvm_128_call_matrix
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/shared_guest_handler_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/http-guest-counter-handler.ores")
    endif()
    # Bounded synchronous defer: LIFO calls on taken/fallthrough return CFG edges.
    if(ORE_CLANG)
        oreslang_contract_test(native_defer_lifo_guarded_execution native-defer-lifo-cfg.ores
            --ir "call void @multiply_ten" --run 111 --clang "${ORE_CLANG}")
    endif()
    if(ORE_CLANG)
        oreslang_contract_test(native_defer_multiguard_cfg_exit_execution native-defer-multiguard-cfg.ores
            --ir "call void @multiply_two" --run 15 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(reject_native_defer_noncall native-defer-noncall-reject.ores
        --diagnostic "native defer requires a direct function call")
    oreslang_contract_test(reject_native_defer_moved_return native-defer-moved-after-return.ores
        --diagnostic "borrow of explicitly moved int")
    # Native return snapshot is immutable under subsequent deferred field cleanup.
    if(ORE_CLANG)
        oreslang_contract_test(native_defer_field_return_snapshot native-defer-field-return.ores
            --ir "return.guard.cond.i1" --run 222 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(reject_native_defer_field_shared_borrow
        native-defer-field-shared-borrow-reject.ores
        --diagnostic "cannot mutate through shared class borrow")
    oreslang_contract_test(reject_native_defer_field_moved_return
        native-defer-field-moved-after-return-reject.ores
        --diagnostic "not definitely initialized")
    oreslang_contract_test(reject_native_deferred_return_rebinding
        native-defer-return-rebinding-reject.ores
        --diagnostic "native defer requires a direct function call or class field assignment")
    oreslang_contract_test(reject_native_finally_override
        native-defer-finally-unsupported.ores
        --diagnostic "cannot escape finally")
    if(ORE_CLANG)
        oreslang_contract_test(native_try_finally_return_snapshot
            native-try-finally-return-snapshot.ores
            --run 246 --clang "${ORE_CLANG}")
    endif()
    oreslang_contract_test(reject_caught_non_error_scalar
        native-caught-scalar-error-only-reject.atto
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_non_error_string
        native-caught-string-error-only-reject.atto
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_non_error_null
        native-caught-null-error-only-reject.atto
        --diagnostic "native caught throw requires canonical std/error.Error")
    # Catch must not authorize non-Error scalar throws. These are real
    # compiler-negative gates and run even without a native linker.
    oreslang_contract_test(reject_caught_throw_unwind_lifo
        native-caught-throw-unwind-lifo.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_payload
        native-caught-throw-payload.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_nested_call
        native-caught-throw-nested-call-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_unsafe_defer
        native-caught-throw-defer-call-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_effect_signature
        native-caught-throw-qualified-effects-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_finally_call
        native-caught-throw-finally-call-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    # Rejection applies regardless of operand storage or cleanup shape.
    oreslang_contract_test(reject_caught_throw_array_size_operand
        native-caught-throw-array-size-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_heap_owned_operand
        native-caught-throw-heap-owned-reject.ores --experimental-owned-int
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_defer_array_size
        native-caught-throw-defer-array-size-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_catch_array_size
        native-caught-throw-catch-array-size-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_finally_array_size
        native-caught-throw-finally-array-size-reject.ores
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_caught_throw_wasm32
        native-caught-throw-payload.ores --target wasm32
        --diagnostic "native caught throw requires canonical std/error.Error")
    oreslang_contract_test(reject_finally_return_override
        native-finally-return-reject.ores --diagnostic "cannot escape finally")
    oreslang_contract_test(reject_finally_break_override
        native-finally-break-reject.ores --diagnostic "cannot escape finally")
    oreslang_contract_test(reject_finally_continue_override
        native-finally-continue-reject.ores --diagnostic "cannot escape finally")
    oreslang_contract_test(reject_try_nonempty_catch
        native-try-nonempty-catch-reject.ores
        --diagnostic "native nonthrowing catch requires only an integer literal return")
    oreslang_contract_test(reject_try_effectful_body
        native-try-effectful-body-reject.ores
        --diagnostic "native terminal try supports only return guards and final return")
    oreslang_contract_test(reject_try_throw_capable_return
        native-try-throw-capable-return-reject.ores
        --diagnostic "native protected try return requires nonthrowing scalar/field read")
    # `trap e` now lowers to Option<int> (pure integer operand only). Using
    # it where an int is required must still reject, as on Java: there is no
    # implicit Option flatten/erase into a result, argument or operand.
    oreslang_contract_test(reject_unadmitted_trap_expression
        unsupported-trap-expression.ores
        --diagnostic "native result cannot implicitly wrap or erase Option/Result")
    oreslang_contract_test(reject_nested_unadmitted_trap_expression
        unsupported-trap-nested-call.ores
        --diagnostic "native call argument cannot implicitly wrap or erase Option/Result")
    oreslang_contract_test(reject_binary_operand_trap_expression
        unsupported-trap-binary-operand.ores
        --diagnostic "native tagged Option/Result is not an integer arithmetic operand")
    # Native source if/else CFG: separate branch states must join safely.
    # Real Clang execution, ASan/UBSan and no-partial-IR rejection matrix.
    if(ORE_CLANG)
        add_test(NAME native_source_conditional_cfg
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/conditional_cfg_native_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        add_test(NAME native_source_conditional_cfg_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/conditional_cfg_native_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}" --sanitize)
        set_tests_properties(native_source_conditional_cfg
            native_source_conditional_cfg_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()
    # Canonical value-producing conditional expression. Conditions are
    # first-class bool expressions on this stacked branch; exactly one value
    # arm executes and affine state must agree at the PHI join.
    if(ORE_CLANG)
        add_test(NAME native_source_ternary_expression
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/ternary_native_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        add_test(NAME native_source_ternary_expression_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/ternary_native_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}" --sanitize)
        set_tests_properties(native_source_ternary_expression
            native_source_ternary_expression_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()
    # First-class native bool scalars. Source typing distinguishes int/bool
    # while the initial same-module ABI canonicalizes bool to i64 0/1.
    if(ORE_CLANG)
        add_test(NAME native_source_bool_scalars
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_bool_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        add_test(NAME native_source_bool_scalars_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_bool_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}" --sanitize)
        set_tests_properties(native_source_bool_scalars
            native_source_bool_scalars_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()
    # Synchronous branch-scoped i64 lifetimes cannot escape the owning arm.
    if(ORE_CLANG)
        add_test(NAME native_branch_local_scope_source_execution
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/branch_local_cfg_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}")
        add_test(NAME native_branch_local_scope_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/branch_local_cfg_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}" --sanitize)
        set_tests_properties(native_branch_local_scope_source_execution
            native_branch_local_scope_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()
    oreslang_contract_test(arithmetic_precedence return-arithmetic.ores --ir "llvm.sadd.with.overflow.i64" --run 28 --clang "${ORE_CLANG}")
    # Proven-nonescaping integer-field classes are stack-only and emit no
    # malloc, tracing collector roots, or native heap calls.
    oreslang_contract_test(owned_stack_class owned-stack-class.ores --ir "alloca %Box")
    # Nonescaping class state passed by exclusive scoped borrow must remain
    # a direct stack-allocated aggregate, not a heap/GC-backed proxy.
    oreslang_contract_test(native_owned_inventory_stack_allocation
        native-owned-inventory-reservation.ores --ir "alloca %Inventory")
    oreslang_contract_test(reject_owned_stack_escape owned-stack-escape.ores --diagnostic "class is non-Copy")
    oreslang_contract_test(reject_owned_stack_unknown_field owned-stack-bad-field.ores --diagnostic "unknown class field")
    oreslang_contract_test(reject_owned_stack_duplicate_field owned-stack-double-field.ores --diagnostic "duplicate class field")
    oreslang_contract_test(negative_literal negative-literal.ores --ir "llvm.sadd.with.overflow.i64" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(nested_comments nested-comments.ores --ir "llvm.sadd.with.overflow.i64" --run 42 --clang "${ORE_CLANG}")
    # Real native lexer/compiler boundary, same limit as Java and .NET main:
    # depth 128 compiles, depth 129 is rejected before parser/IR.
    oreslang_contract_test(native_lexer_nested_comment_depth_128
        nested-comments-depth-128.ores --ir "ret i64 42")
    oreslang_contract_test(native_lexer_nested_comment_depth_129_failclosed
        nested-comments-depth-129.ores
        --diagnostic "block comment nesting exceeds 128")
    oreslang_contract_test(native_owned_lexical_lifetime owned-lexical-main.ores --no-native-abi --ir "llvm.lifetime.end")
    oreslang_contract_test(native_owned_identifier owned-identifier-main.ores --no-native-abi --ir "call i64 @owned")
    # Port the unique generated stress corpus from old stacked #72 without its
    # outdated frontend/backend implementation. This compiles + runs native IR.
    if(ORE_CLANG)
        add_test(NAME native_owned_scalar_cfg_generated_stress
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/owned_syntax_stress.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --native-runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c")
        set_tests_properties(native_owned_scalar_cfg_generated_stress PROPERTIES TIMEOUT 240)
    endif()
    # Class moves are compiled and exercised through real native guests.
    find_program(ORE_MOVE_RUSTC rustc)
    if(ORE_CLANG AND ORE_MOVE_RUSTC)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix"
            COMMAND "${ORE_MOVE_RUSTC}" --edition=2021 -O -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/class_move_native_matrix.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix"
            DEPENDS tests/class_move_native_matrix.rs VERBATIM)
        add_custom_target(oreslang-class-move-native-matrix-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix")
        add_test(NAME native_class_move_source_execution
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix"
                --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}")
        add_test(NAME native_class_move_source_asan_ubsan
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix"
                --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}" --sanitize)
        add_test(NAME native_class_move_optimized_rust_assertions
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-class-move-native-matrix" --self-test)
        set_tests_properties(native_class_move_source_execution
            native_class_move_source_asan_ubsan
            native_class_move_optimized_rust_assertions PROPERTIES TIMEOUT 180)
    else()
        foreach(move_gate IN ITEMS native_class_move_source_execution
                native_class_move_source_asan_ubsan native_class_move_optimized_rust_assertions)
            add_test(NAME ${move_gate} COMMAND "${CMAKE_COMMAND}" -E false)
        endforeach()
    endif()
    oreslang_contract_test(reject_owned_int_without_opt_in owned-native-region-main.ores --diagnostic "experimental owned int requires --experimental-owned-int")
    oreslang_contract_test(owned_native_heap_lowering owned-native-region-main.ores --experimental-owned-int --ir "call void @oreslang_owned_i64_region_drop")
    oreslang_contract_test(owned_native_region_nested owned-native-region-call.ores --experimental-owned-int --ir "call void @oreslang_owned_i64_region_close")
    oreslang_contract_test(reject_unsupported_native_heap_type owned-native-region-unsupported.ores --experimental-owned-int --diagnostic "native scalar local requires int, bool or checked scalar alias")
    oreslang_contract_test(reject_local_uninitialized owned-uninitialized.ores --diagnostic "unknown parameter")
    oreslang_contract_test(reject_local_duplicate owned-duplicate.ores --diagnostic "duplicate owned local")
    oreslang_contract_test(reject_local_forward_reference owned-use-before-declaration.ores --diagnostic "unknown parameter")
    oreslang_contract_test(reject_local_unsupported_object owned-unsupported-heap.ores --diagnostic "unknown record class")
    oreslang_contract_test(reject_local_moved_before_read owned-local-use-after-take.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(native_owned_local_shared_borrow owned-local-shared-borrow.ores --ir "llvm.lifetime.end")
    oreslang_contract_test(native_owned_local_mutable_borrow owned-local-mutable-borrow.ores --ir "llvm.lifetime.end")
    oreslang_contract_test(reject_actor unsupported-actor.ores --diagnostic "actor requires at least one scalar field")
    # Hierarchy and protocol contracts are Java-only at present; the native
    # scalar actor subset must reject before emitting IR or a guest artifact.
    foreach(hierarchy_case IN ITEMS inheritance interface trait)
        oreslang_contract_test(native_actor_${hierarchy_case}_unsupported
            source-actor-${hierarchy_case}-unsupported.ores
            --diagnostic "native actor extends/implements requires actor inheritance, trait/interface dispatch and sealed-state lowering")
    endforeach()
    # Exact canonical lifecycle source is a positive Java guest candidate,
    # but the native bootstrap cannot lower ActorMail<T>, outputs or
    # receive(ActorMail<T>). The rejection must return no LLVM IR.
    oreslang_contract_test(native_actor_canonical_lifecycle_unsupported
        source-actor-lifecycle-canonical-unsupported.ores
        --diagnostic "native ActorMail<T> receive and actor outputs require typed message and mailbox lowering")
    # Optional instance method marker: fnc, method, and no marker all lower
    # to the same native supported on_message(int) handler.
    oreslang_contract_test(native_actor_explicit_method source-actor-method-keyword.ores
        --ir "oreslang_source_actor_spawn")
    oreslang_contract_test(native_contextual_method_identifier contextual-method-identifier.ores
        --ir "ret i64")
    oreslang_contract_test(native_actor_bare_method source-actor-bare-method.ores
        --ir "oreslang_source_actor_spawn")
    # Real compiled .atto source actor handler now calls the actor-owned frame
    # bridge (not just a host-only model). Full Clang execution lives in the
    # existing source_actor_compiled_native_lifecycle CTest.
    oreslang_contract_test(native_actor_aot_frame_call
        actor-logical-frame-aot-two-actors.atto --ir "call i32 @oreslang_source_actor_frame_push")
    oreslang_contract_test(reject_source_actor_frame_abi_symbol_forgery
        actor-frame-abi-forgery-reject.atto --diagnostic "reserved native runtime ABI symbol 'oreslang_source_actor_frame_push'")
    oreslang_contract_test(native_actor_suspending_method_gate source-actor-suspend-method-reject.ores
        --diagnostic "native suspends actor method requires continuation lowering")
    oreslang_contract_test(native_actor_final_group_field_gate source-actor-final-group-field-reject.ores
        --diagnostic "native final actor fields require typed field initialization and ownership lowering")
    # Host-only ActorGroup substrate: source actor spawn/await fail closed.
    oreslang_contract_test(reject_native_actor_group_spawn unsupported-actor-group-spawn.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_native_actor_group_await_spawn unsupported-actor-group-await-spawn.ores --diagnostic "native async/channel/select boundary unsupported")
    # LLVM source ActorGroups remain unsupported until typed handle/permit lowering.
    # No native IR may be emitted on these negative compiler fixtures.
    oreslang_contract_test(reject_guest_actor_group_create source-actor-group-create-unsupported.ores --diagnostic "native ActorGroup source lowering unsupported")
    oreslang_contract_test(reject_guest_actor_group_join source-actor-group-join-unsupported.ores --diagnostic "native ActorGroup source lowering unsupported")
    oreslang_contract_test(reject_guest_actor_group_shadow source-actor-group-declaration-unsupported.ores --diagnostic "native ActorGroup source lowering unsupported")

    # ActorRef<T> is a source-checked non-Copy capability; Future<T> remains
    # explicitly blocked until compiler-generated suspension is lowered.
    oreslang_contract_test(source_actor_typed_ref_admit source-actor-typed-ref.ores
        --ir "oreslang_source_actor_spawn")
    oreslang_contract_test(source_actor_typed_ref_mismatch source-actor-typed-ref-mismatch.ores
        --diagnostic "ActorRef<T> type mismatch")
    oreslang_contract_test(source_actor_typed_ref_nonspawn source-actor-typed-ref-nonspawn.ores
        --diagnostic "ActorRef<T> requires spawn initializer")
    oreslang_contract_test(source_actor_await_ready_continuation_ir source-actor-await-ready.ores
        --ir "ready_continuation")
    oreslang_contract_test(source_actor_await_ready_trace_runtime_ir source-actor-await-ready.ores
        --ir "oreslang_source_actor_await_ready")
    # ActorRef implements HasFuture: awaiting its capability directly
    # delegates to the same get_ready() root CONTROL continuation.
    oreslang_contract_test(source_actor_hasfuture_direct_await_ir
        source-actor-await-worker.ores --ir "ready_continuation")
    oreslang_contract_test(source_actor_hasfuture_direct_await_runtime_ir
        source-actor-await-worker.ores --ir "oreslang_source_actor_await_ready")
    oreslang_contract_test(reject_source_actor_hasfuture_unknown_receiver
        source-actor-await-unknown.ores
        --diagnostic "await get_ready requires a live direct ActorRef receiver")
    oreslang_contract_test(reject_source_actor_hasfuture_get_done
        source-actor-await-get-done.ores
        --diagnostic "actor effects after await get_done are unsupported")
    oreslang_contract_test(reject_source_actor_hasfuture_double_await
        source-actor-await-worker-twice.ores
        --diagnostic "native source supports only one direct ActorRef lifecycle await")
    # HasFuture must be a checked live actor capability, not merely an
    # identifier that happens to appear in an await expression.
    oreslang_contract_test(reject_source_actor_hasfuture_scalar_operand
        source-actor-await-non-hasfuture-scalar.ores
        --diagnostic "await get_ready requires a live direct ActorRef receiver")
    oreslang_contract_test(reject_source_actor_hasfuture_stopped_actor
        source-actor-await-after-stop.ores
        --diagnostic "await get_ready must precede post-await actor effects")
    oreslang_contract_test(reject_source_actor_hasfuture_skips_linear_future
        source-actor-future-void-await-worker-instead.ores
        --diagnostic "native Future<void> await requires its declared readiness token")
    oreslang_contract_test(reject_source_actor_hasfuture_reuses_future
        source-actor-future-void-await-again.ores
        --diagnostic "native source supports only one direct ActorRef lifecycle await")
    # First actual native source-level typed Future: Future<void> from a
    # single ActorRef.get_ready(). It uses the existing root CONTROL
    # continuation, not synchronous polling or a generic Future<T> ABI.
    oreslang_contract_test(source_actor_typed_ready_future_continuation_ir
        source-actor-future-void-ready.ores --ir "ready_continuation")
    oreslang_contract_test(source_actor_typed_ready_future_runtime_ir
        source-actor-future-void-ready.ores --ir "oreslang_source_actor_await_ready")
    oreslang_contract_test(reject_source_actor_unused_ready_future
        source-actor-future-void-unused.ores
        --diagnostic "Future<void> readiness token must be awaited exactly once")
    oreslang_contract_test(reject_source_actor_wrong_ready_future
        source-actor-future-void-wrong-name.ores
        --diagnostic "native Future<void> await requires its declared readiness token")
    oreslang_contract_test(reject_source_actor_double_ready_future
        source-actor-future-void-double-await.ores
        --diagnostic "native source supports only one direct ActorRef lifecycle await")
    oreslang_contract_test(reject_source_actor_unspawned_ready_future
        source-actor-future-void-unspawned.ores
        --diagnostic "Future<void> requires previously spawned ActorRef.get_ready")
    oreslang_contract_test(reject_source_actor_ready_future_shadow
        source-actor-future-void-shadow.ores
        --diagnostic "Future<void> readiness token shadows a live owner")
    oreslang_contract_test(reject_source_actor_duplicate_ready_future
        source-actor-future-void-duplicate.ores
        --diagnostic "native source supports only one Future<void> readiness token")
    oreslang_contract_test(reject_source_actor_ready_future_wrong_producer
        source-actor-future-void-wrong-producer.ores
        --diagnostic "expected 'get_ready'")
    oreslang_contract_test(reject_source_actor_ready_future_as_message
        source-actor-future-void-send.ores
        --diagnostic "unknown parameter")
    oreslang_contract_test(reject_source_actor_await_suspended_local
        source-actor-spawn-unavailable-await.ores
        --diagnostic "await get_ready requires one source actor without suspended locals")
    oreslang_contract_test(reject_source_actor_await_callable_result
        source-actor-await-ready-impure-result.ores
        --diagnostic "await get_ready requires side-effect-free scalar return")
    oreslang_contract_test(source_future_typed_await_boundary source-future-typed-unimplemented.ores
        --diagnostic "Future<T> requires native continuation lowering")
    # Nested wrappers are distinct source types. Until native generic
    # representation/continuation lowering lands, reject them explicitly rather
    # than accidentally accepting/canonicalizing them or failing on punctuation.
    # This is a fail-closed semantic gate, not flatten/unwrap_all execution.
    oreslang_contract_test(source_nested_option_preserves_type_fail_closed
        source-nested-option-flatten-unimplemented.ores
        --diagnostic "implicit Option flatten is forbidden")
    oreslang_contract_test(source_nested_future_preserves_type_fail_closed
        source-nested-future-flatten-unimplemented.ores
        --diagnostic "implicit Future flatten is forbidden")
    oreslang_contract_test(source_nested_future_generic_payload_preserves_type_fail_closed
        source-nested-future-generic-payload-fail-closed.ores
        --diagnostic "implicit Future flatten is forbidden")
    oreslang_contract_test(source_nested_option_generic_payload_preserves_type_fail_closed
        source-nested-option-generic-payload-fail-closed.ores
        --diagnostic "implicit Option flatten is forbidden")
    oreslang_contract_test(source_option_single_layer_not_nested_diagnostic
        source-option-single-layer-unimplemented.ores
        --diagnostic "native local Option/Result initializer cannot implicitly wrap or erase tagged value")


    # Entry<K,V> is a compiler-owned nominal product in Java/GraalVM.
    # Native lowering is intentionally denied until representation, ownership,
    # projection and transport parity land together.
    oreslang_contract_test(reject_native_entry_shadow_class
        entry-shadow-class-reject.ores --diagnostic "builtin Entry is compiler-owned")
    oreslang_contract_test(reject_native_entry_shadow_actor
        entry-shadow-actor-reject.ores --diagnostic "builtin Entry is compiler-owned")
    oreslang_contract_test(reject_native_entry_parameter_until_parity
        entry-parameter-unimplemented.ores --diagnostic "native Entry<K,V> requires nominal product lowering and ownership parity")
    oreslang_contract_test(reject_native_entry_return_until_parity
        entry-return-unimplemented.ores --diagnostic "native Entry<K,V> requires nominal product lowering and ownership parity")
    oreslang_contract_test(reject_native_entry_local_until_parity
        entry-local-unimplemented.ores --diagnostic "native Entry<K,V> requires nominal product lowering and ownership parity")

    # Real source-compiled Oreslang actors, not host-only C callback tests.
    # The synchronous stop barrier is allowed only in trusted top-level main.
    # One zero-local await ActorRef.get_ready() is admitted through a traced
    # root CONTROL continuation; generic Future<T>/select remain fail-closed.
    add_executable(oreslang-source-actor-await-trace-runtime-test
        tests/source_actor_await_trace_runtime_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-source-actor-await-trace-runtime-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-source-actor-await-trace-runtime-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-source-actor-await-trace-runtime-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME source_actor_await_trace_runtime
        COMMAND oreslang-source-actor-await-trace-runtime-test)
    set_tests_properties(source_actor_await_trace_runtime PROPERTIES TIMEOUT 30)

    if(ORE_CLANG)
        add_test(NAME source_actor_compiled_native_lifecycle
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_actor_e2e.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --root "${CMAKE_CURRENT_SOURCE_DIR}")
        set_tests_properties(source_actor_compiled_native_lifecycle PROPERTIES TIMEOUT 180)
        add_test(NAME source_actor_compiled_native_asan_ubsan
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_actor_e2e.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --root "${CMAKE_CURRENT_SOURCE_DIR}" --sanitize)
        set_tests_properties(source_actor_compiled_native_asan_ubsan PROPERTIES TIMEOUT 180)
    endif()

    # Run both direct await and typed Future<void> source through real
    # Clang-linked actor runtime, including independent release/sanitizer
    # physical lifecycle receipts. No new Python test drivers.
    find_program(ORE_READY_FUTURE_RUSTC NAMES rustc)
    if(ORE_CLANG AND ORE_READY_FUTURE_RUSTC)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-ready-future-void-e2e"
            COMMAND "${ORE_READY_FUTURE_RUSTC}" --edition=2021 -O -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_future_void_source_e2e.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-ready-future-void-e2e"
            DEPENDS tests/native_future_void_source_e2e.rs VERBATIM)
        add_custom_target(oreslang-ready-future-void-e2e-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-ready-future-void-e2e")
        add_test(NAME native_ready_future_void_guest_release
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-ready-future-void-e2e"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --root "${CMAKE_CURRENT_SOURCE_DIR}")
        add_test(NAME native_ready_future_void_guest_asan_ubsan
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-ready-future-void-e2e"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --root "${CMAKE_CURRENT_SOURCE_DIR}" --sanitize)
        set_tests_properties(native_ready_future_void_guest_release
            native_ready_future_void_guest_asan_ubsan PROPERTIES TIMEOUT 150)
    endif()

    # Trusted host resource reclamation: close in reverse acquisition order only
    # after active turns, outstanding loans and native callbacks quiesce.
    add_executable(oreslang-actor-resource-registry-test
        tests/actor_resource_registry_test.cpp)
    target_include_directories(oreslang-actor-resource-registry-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-resource-registry-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-resource-registry-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_resource_registry_quiescence
        COMMAND oreslang-actor-resource-registry-test)
    set_tests_properties(native_actor_resource_registry_quiescence PROPERTIES TIMEOUT 15)

    add_executable(oreslang-actor-resource-registry-identity-test
        tests/actor_resource_registry_identity_test.cpp
        tests/actor_resource_registry_identity_peer.cpp)
    target_include_directories(oreslang-actor-resource-registry-identity-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-actor-resource-registry-identity-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-actor-resource-registry-identity-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-resource-registry-identity-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_resource_registry_identity
        COMMAND oreslang-actor-resource-registry-identity-test)
    set_tests_properties(native_actor_resource_registry_identity PROPERTIES TIMEOUT 30)

    # Counter injection exists only in this isolated single-TU test binary.
    add_executable(oreslang-actor-resource-registry-exhaustion-test
        tests/actor_resource_registry_exhaustion_test.cpp)
    target_include_directories(oreslang-actor-resource-registry-exhaustion-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-actor-resource-registry-exhaustion-test PRIVATE cxx_std_17)
    target_compile_definitions(oreslang-actor-resource-registry-exhaustion-test PRIVATE
        ORESLANG_NATIVE_TEST_REGISTRY_DOMAIN_EXHAUSTION=1)
    target_compile_options(oreslang-actor-resource-registry-exhaustion-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-resource-registry-exhaustion-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_resource_registry_domain_exhaustion
        COMMAND oreslang-actor-resource-registry-exhaustion-test)
    set_tests_properties(native_actor_resource_registry_domain_exhaustion PROPERTIES TIMEOUT 30)

    add_executable(oreslang-actor-group-lifecycle-test tests/actor_group_lifecycle_test.cpp)
    target_include_directories(oreslang-actor-group-lifecycle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-group-lifecycle-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-group-lifecycle-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_group_managed_worker_readiness COMMAND oreslang-actor-group-lifecycle-test)
    set_tests_properties(native_actor_group_managed_worker_readiness PROPERTIES TIMEOUT 15)
    # Cooperation membership is many-to-many, distinct from one lifecycle owner.
    add_executable(oreslang-native-actor-membership-test tests/native_actor_membership_test.cpp)
    target_include_directories(oreslang-native-actor-membership-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-membership-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-actor-membership-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_multi_group_membership COMMAND oreslang-native-actor-membership-test)
    set_tests_properties(native_actor_multi_group_membership PROPERTIES TIMEOUT 20)
    # Host-only ETS scalar set and pg-style membership snapshots; neither
    # proves source lowering, distributed pg or VM/IsolateVM admission.
    add_executable(oreslang-native-ets-scalar-set-test
        tests/native_ets_scalar_set_test.cpp)
    target_include_directories(oreslang-native-ets-scalar-set-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-ets-scalar-set-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-ets-scalar-set-test PRIVATE Threads::Threads)
    add_test(NAME native_ets_host_scalar_acl_lifetime
        COMMAND oreslang-native-ets-scalar-set-test)
    set_tests_properties(native_ets_host_scalar_acl_lifetime PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-pg-snapshot-test
        tests/native_pg_membership_snapshot_test.cpp)
    target_include_directories(oreslang-native-pg-snapshot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-pg-snapshot-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-pg-snapshot-test PRIVATE Threads::Threads)
    add_test(NAME native_pg_host_membership_snapshot
        COMMAND oreslang-native-pg-snapshot-test)
    set_tests_properties(native_pg_host_membership_snapshot PROPERTIES TIMEOUT 30)
    # Adversarial host-only ETS and pg tests: stop races, reincarnation,
    # group-ID ABA, callback reentrancy, and individual mailbox rejection.
    add_executable(oreslang-native-ets-linearization-test
        tests/native_ets_linearizable_retirement_test.cpp)
    target_include_directories(oreslang-native-ets-linearization-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-ets-linearization-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-ets-linearization-test PRIVATE Threads::Threads)
    add_test(NAME native_ets_insert_new_and_retire_race
        COMMAND oreslang-native-ets-linearization-test)
    set_tests_properties(native_ets_insert_new_and_retire_race PROPERTIES TIMEOUT 45)
    add_executable(oreslang-native-ets-versioned-cas-test
        tests/native_ets_versioned_cas_test.cpp)
    target_include_directories(oreslang-native-ets-versioned-cas-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-ets-versioned-cas-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-ets-versioned-cas-test PRIVATE Threads::Threads)
    add_test(NAME native_ets_aba_versioned_cas_contention
        COMMAND oreslang-native-ets-versioned-cas-test)
    set_tests_properties(native_ets_aba_versioned_cas_contention
        PROPERTIES TIMEOUT 45)
    # UHL-1 owner-death handoff with immutable table identity, fenced
    # pending receipt, single ACK and bounded revoked generation history.
    # This is host-only ETS; no source actor/VM supervision proof yet.
    add_executable(oreslang-native-ets-uhl1-heir-test
        tests/native_ets_uhl1_heir_handoff_test.cpp)
    target_include_directories(oreslang-native-ets-uhl1-heir-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-ets-uhl1-heir-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-ets-uhl1-heir-test PRIVATE Threads::Threads)
    add_test(NAME native_ets_uhl1_heir_handoff_host_only
        COMMAND oreslang-native-ets-uhl1-heir-test)
    set_tests_properties(native_ets_uhl1_heir_handoff_host_only
        PROPERTIES TIMEOUT 45)

    add_executable(oreslang-native-pg-broadcast-test
        tests/native_pg_broadcast_test.cpp)
    target_include_directories(oreslang-native-pg-broadcast-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-pg-broadcast-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-pg-broadcast-test PRIVATE Threads::Threads)
    add_test(NAME native_pg_bounded_broadcast_and_group_aba
        COMMAND oreslang-native-pg-broadcast-test)
    set_tests_properties(native_pg_bounded_broadcast_and_group_aba PROPERTIES TIMEOUT 45)
    # Host-only CONTROL timer queue: no guest callback, no source async lowering.
    # Expiry tickets are held until exactly-once claim/discard, including
    # accounting for polled-but-not-yet-dispatched timers.
    add_executable(oreslang-native-timer-queue-test tests/native_timer_queue_test.cpp)
    target_include_directories(oreslang-native-timer-queue-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-timer-queue-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-timer-queue-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_monotonic_timer_admission_and_cancel_race
        COMMAND oreslang-native-timer-queue-test)
    set_tests_properties(native_monotonic_timer_admission_and_cancel_race
        PROPERTIES TIMEOUT 20)

    # Pinned native Future<void> lifecycle frame: host callbacks only record
    # completion; resumption is admitted on a parent actor's CONTROL lane.
    # Guest-source await remains compiler-rejected pending typed MIR lowering.
    add_executable(oreslang-actor-lifecycle-await-test tests/actor_lifecycle_await_test.cpp)
    target_include_directories(oreslang-actor-lifecycle-await-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-lifecycle-await-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-lifecycle-await-test PRIVATE Threads::Threads)
    add_test(NAME native_pinned_actor_future_control_continuation
        COMMAND oreslang-actor-lifecycle-await-test)
    set_tests_properties(native_pinned_actor_future_control_continuation PROPERTIES TIMEOUT 15)
    # Host-only typed i64 Future completion frame. Source-level Future<T>
    # remains fail-closed until compiler-generated suspension is implemented.
    add_executable(oreslang-native-scalar-future-await-test
        tests/native_scalar_future_await_test.cpp)
    target_include_directories(oreslang-native-scalar-future-await-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-scalar-future-await-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-scalar-future-await-test PRIVATE Threads::Threads)
    add_test(NAME native_scalar_future_single_winner_control
        COMMAND oreslang-native-scalar-future-await-test)
    set_tests_properties(native_scalar_future_single_winner_control PROPERTIES TIMEOUT 30)
    add_executable(oreslang-bounded-actor-port-test tests/bounded_actor_port_test.cpp)
    target_include_directories(oreslang-bounded-actor-port-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-bounded-actor-port-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_port_bounded_owned_transport COMMAND oreslang-bounded-actor-port-test)
    # Independent multicore transport proof: sends are move-only,
    # saturated admission cannot consume a producer's payload, and only
    # receiver acknowledgement refunds queue/checked-out resource credits.
    find_package(Threads REQUIRED)
    add_executable(oreslang-bounded-actor-port-concurrency-test
        tests/bounded_actor_port_concurrency_test.cpp)
    target_include_directories(oreslang-bounded-actor-port-concurrency-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-bounded-actor-port-concurrency-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-bounded-actor-port-concurrency-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_port_owned_transport_concurrency
        COMMAND oreslang-bounded-actor-port-concurrency-test)
    set_tests_properties(native_actor_port_owned_transport_concurrency
        PROPERTIES TIMEOUT 60)
    # Bounded-port mailbox ledger, physical ownership and supervisor CONTROL
    # transport tests; host primitives only, not guest compiler parity.
    target_link_libraries(oreslang-bounded-actor-port-test PRIVATE Threads::Threads)
    set_tests_properties(native_actor_port_bounded_owned_transport PROPERTIES TIMEOUT 30)

    # Host-runtime-only typed-tag selective mailbox primitive. A full
    # guest-language selective receive and compiled matcher remains separate.
    add_executable(oreslang-native-selective-tagged-port-test
        tests/native_selective_tagged_port_test.cpp)
    target_include_directories(oreslang-native-selective-tagged-port-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-selective-tagged-port-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-selective-tagged-port-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-selective-tagged-port-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_tag_selective_bounded_receive
        COMMAND oreslang-native-selective-tagged-port-test)
    set_tests_properties(native_actor_tag_selective_bounded_receive PROPERTIES TIMEOUT 30)

    # Real BoundedActorPort + preallocated slot pool + shared completion ledger.
    add_executable(oreslang-native-preallocated-port-oracle-test
        tests/native_preallocated_port_reference_model_test.cpp)
    target_include_directories(oreslang-native-preallocated-port-oracle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-preallocated-port-oracle-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-preallocated-port-oracle-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-preallocated-port-oracle-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_preallocated_production_port_oracle
        COMMAND oreslang-native-preallocated-port-oracle-test)
    set_tests_properties(native_actor_preallocated_production_port_oracle PROPERTIES TIMEOUT 30)

    # Full runtime path (not a standalone slot model): bound PriorityActorInbox,
    # NativeActorGroup CONTROL/user turns, generation-fenced STOP, physical ACK,
    # and 400 concurrent sender-vs-stop trials.
    add_executable(oreslang-native-preallocated-supervisor-races-test
        tests/native_preallocated_supervisor_races_test.cpp)
    target_include_directories(oreslang-native-preallocated-supervisor-races-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-preallocated-supervisor-races-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-preallocated-supervisor-races-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-preallocated-supervisor-races-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_preallocated_supervisor_races
        COMMAND oreslang-native-preallocated-supervisor-races-test)
    set_tests_properties(native_actor_preallocated_supervisor_races PROPERTIES TIMEOUT 60)

    # Separately executable exact native host-component regression:
    # NativeActorGroup CONTROL/STOP, BoundedActorPort preallocated slots,
    # sequence-ledger gap fencing and 600 admission-vs-STOP races.
    add_executable(oreslang-native-group-physical-port-stress-test
        tests/native_group_physical_port_stress_test.cpp)
    target_include_directories(oreslang-native-group-physical-port-stress-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-group-physical-port-stress-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-group-physical-port-stress-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-group-physical-port-stress-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_group_physical_port_stop_races
        COMMAND oreslang-native-group-physical-port-stress-test)
    set_tests_properties(native_actor_group_physical_port_stop_races PROPERTIES TIMEOUT 60)

    # Independently buildable host primitive: fixed-capacity intrusive slots,
    # compiler-generated tag selectors and generation-fenced scan continuations.
    # BoundedActorPort now uses this queue; full native CMake, ACK, lifecycle
    # and compiler-generated guest AOT tests remain independent merge gates.
    add_executable(oreslang-preallocated-selective-slots-test
        tests/preallocated_selective_slots_test.cpp)
    target_include_directories(oreslang-preallocated-selective-slots-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-preallocated-selective-slots-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-preallocated-selective-slots-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-preallocated-selective-slots-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_preallocated_selective_slots
        COMMAND oreslang-preallocated-selective-slots-test)
    set_tests_properties(native_actor_preallocated_selective_slots PROPERTIES TIMEOUT 30)
    # Real shared ActorMailboxCompletion with two independently preallocated
    # priority lanes: global sequence fence versus lane-local receipt ID.
    add_executable(oreslang-native-slot-ledger-integration-test
        tests/native_slot_ledger_integration_test.cpp)
    target_include_directories(oreslang-native-slot-ledger-integration-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-slot-ledger-integration-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-slot-ledger-integration-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-slot-ledger-integration-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_preallocated_slot_ledger_fence
        COMMAND oreslang-native-slot-ledger-integration-test)
    set_tests_properties(native_actor_preallocated_slot_ledger_fence PROPERTIES TIMEOUT 30)

    # Compiler-executed production port (not a stand-alone slot model):
    # 100k deterministic operations, selective resume, FIFO, physical ACK.
    add_executable(oreslang-native-bounded-selective-slot-oracle-test
        tests/native_bounded_selective_slot_oracle_test.cpp)
    target_include_directories(oreslang-native-bounded-selective-slot-oracle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-bounded-selective-slot-oracle-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-bounded-selective-slot-oracle-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-bounded-selective-slot-oracle-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_bounded_selective_slot_oracle
        COMMAND oreslang-native-bounded-selective-slot-oracle-test)
    set_tests_properties(native_actor_bounded_selective_slot_oracle PROPERTIES TIMEOUT 30)
    # Compile-negative actor safety gate: custom deleter destructors or moves
    # must not call back into an actor mailbox while the physical lock is held.
    if(UNIX)
        add_test(NAME native_actor_preallocated_selective_deleter_rejections
            COMMAND "${CMAKE_COMMAND}"
                "-DCXX=${CMAKE_CXX_COMPILER}"
                "-DINCLUDE_DIR=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
                "-DSOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/preallocated_selective_slots_negative_compile.cpp"
                "-P" "${CMAKE_CURRENT_SOURCE_DIR}/tests/preallocated_selective_slots_negative_compile.cmake")
        set_tests_properties(native_actor_preallocated_selective_deleter_rejections
            PROPERTIES TIMEOUT 30)
    endif()

    add_executable(oreslang-native-mailbox-parallel-terminal-test
        tests/native_mailbox_parallel_terminal_test.cpp)
    target_include_directories(oreslang-native-mailbox-parallel-terminal-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-mailbox-parallel-terminal-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-mailbox-parallel-terminal-test PRIVATE Threads::Threads)
    add_test(NAME native_mailbox_parallel_terminal_races
        COMMAND oreslang-native-mailbox-parallel-terminal-test)
    set_tests_properties(native_mailbox_parallel_terminal_races PROPERTIES TIMEOUT 60)

    add_executable(oreslang-native-actor-port-alloc-fault-test
        tests/native_actor_port_allocation_failure_test.cpp)
    target_include_directories(oreslang-native-actor-port-alloc-fault-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-port-alloc-fault-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_port_ownership_allocator_rollback
        COMMAND oreslang-native-actor-port-alloc-fault-test)
    set_tests_properties(native_actor_port_ownership_allocator_rollback PROPERTIES TIMEOUT 15)

    add_executable(oreslang-priority-actor-inbox-test tests/priority_actor_inbox_test.cpp)
    target_include_directories(oreslang-priority-actor-inbox-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-priority-actor-inbox-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-priority-actor-inbox-test PRIVATE Threads::Threads)
    add_test(NAME native_priority_user_inbox_control_isolation
        COMMAND oreslang-priority-actor-inbox-test)
    set_tests_properties(native_priority_user_inbox_control_isolation PROPERTIES TIMEOUT 30)

    # Incarnation ticket finalization is a CAS winner-takes-all race.
    # Register this target exactly once; stale #894 duplicated it.
    add_executable(oreslang-actor-resource-loan-terminal-race-test
        tests/actor_resource_loan_terminal_race_test.cpp)
    target_include_directories(oreslang-actor-resource-loan-terminal-race-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-resource-loan-terminal-race-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-resource-loan-terminal-race-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_resource_loan_terminal_race
        COMMAND oreslang-actor-resource-loan-terminal-race-test)
    set_tests_properties(native_actor_resource_loan_terminal_race PROPERTIES TIMEOUT 60)

    add_executable(oreslang-actor-mailbox-completion-model-test
        tests/actor_mailbox_completion_model_test.cpp)
    target_include_directories(oreslang-actor-mailbox-completion-model-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-mailbox-completion-model-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-mailbox-completion-model-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_mailbox_completion_model
        COMMAND oreslang-actor-mailbox-completion-model-test)
    set_tests_properties(native_actor_mailbox_completion_model PROPERTIES TIMEOUT 60)

    # Post-merge regression against the production completion ledger.
    add_executable(oreslang-actor-mailbox-completion-stress-test
        tests/native_actor_mailbox_completion_stress_test.cpp)
    target_include_directories(oreslang-actor-mailbox-completion-stress-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-mailbox-completion-stress-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-mailbox-completion-stress-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_mailbox_completion_stress
        COMMAND oreslang-actor-mailbox-completion-stress-test)
    set_tests_properties(native_actor_mailbox_completion_stress PROPERTIES TIMEOUT 90)

    # Independent per-frame oracle for randomized prepare/publish/rollback,
    # out-of-order completion and terminal abort across window sizes 1..64.
    add_executable(oreslang-actor-mailbox-randomized-model-test
        tests/native_actor_mailbox_randomized_model_test.cpp)
    target_include_directories(oreslang-actor-mailbox-randomized-model-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-actor-mailbox-randomized-model-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-actor-mailbox-randomized-model-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-mailbox-randomized-model-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_mailbox_randomized_model
        COMMAND oreslang-actor-mailbox-randomized-model-test)
    set_tests_properties(native_actor_mailbox_randomized_model PROPERTIES TIMEOUT 30)

    # End-to-end host lifecycle proof across priority dequeue, STOP/detach,
    # queued terminal abort, checked-out ACK, resource-loan drain and DONE.
    add_executable(oreslang-native-actor-mailbox-lifecycle-test
        tests/native_actor_mailbox_lifecycle_test.cpp)
    target_include_directories(oreslang-native-actor-mailbox-lifecycle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-mailbox-lifecycle-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-mailbox-lifecycle-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    target_link_libraries(oreslang-native-actor-mailbox-lifecycle-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_mailbox_stop_loan_done_lifecycle
        COMMAND oreslang-native-actor-mailbox-lifecycle-test)
    set_tests_properties(native_actor_mailbox_stop_loan_done_lifecycle PROPERTIES TIMEOUT 30)
    add_executable(oreslang-native-ndarray-view-admission-test tests/native_ndarray_view_admission_test.cpp)
    target_include_directories(oreslang-native-ndarray-view-admission-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-ndarray-view-admission-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ndarray_write_view_reference COMMAND oreslang-native-ndarray-view-admission-test)
    # Prove control-plane stop requests honor existing loan-gated native heaps.
    add_executable(oreslang-actor-group-heap-quiescence-test
        tests/actor_group_heap_quiescence_test.cpp
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
    target_include_directories(oreslang-actor-group-heap-quiescence-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-group-heap-quiescence-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-actor-group-heap-quiescence-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_group_heap_loan_quiescence
        COMMAND oreslang-actor-group-heap-quiescence-test)
    set_tests_properties(native_actor_group_heap_loan_quiescence PROPERTIES TIMEOUT 15)
    # Bind the actual native actor heap, resource registry and ActorGroup
    # physical-done acknowledgment into one quiescence-checked host lifecycle.
    add_executable(oreslang-native-actor-retirement-barrier-test
        tests/native_actor_retirement_barrier_test.cpp
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
    target_include_directories(oreslang-native-actor-retirement-barrier-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-retirement-barrier-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-actor-retirement-barrier-test PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-actor-retirement-barrier-test PRIVATE
        ORESLANG_NATIVE_SOURCE_CLEANUP_TEST_PEER=1)
    add_test(NAME native_actor_done_resource_quiescence
        COMMAND oreslang-native-actor-retirement-barrier-test)
    set_tests_properties(native_actor_done_resource_quiescence PROPERTIES TIMEOUT 15)
    # Optional source-unwind receipt: cancellation does not imply physical
    # teardown, and no native allocation can be reclaimed without a receipt.
    add_executable(oreslang-native-source-cleanup-gate-test
        tests/native_source_cleanup_gate_test.cpp)
    target_include_directories(oreslang-native-source-cleanup-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-source-cleanup-gate-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-source-cleanup-gate-test PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-source-cleanup-gate-test PRIVATE
        ORESLANG_NATIVE_SOURCE_CLEANUP_TEST_PEER=1)
    add_test(NAME native_source_cleanup_deferred_physical_receipt
        COMMAND oreslang-native-source-cleanup-gate-test)
    set_tests_properties(native_source_cleanup_deferred_physical_receipt PROPERTIES TIMEOUT 30)
    # Validate cleanup admission against a live, claimed continuation-store
    # generation and exact actor incarnation instead of a caller's raw ticket.
    add_executable(oreslang-native-source-cleanup-binding-test
        tests/native_source_cleanup_binding_test.cpp
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c)
    target_include_directories(oreslang-native-source-cleanup-binding-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-source-cleanup-binding-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-source-cleanup-binding-test PRIVATE Threads::Threads)
    add_test(NAME native_source_cleanup_continuation_binding
        COMMAND oreslang-native-source-cleanup-binding-test)
    set_tests_properties(native_source_cleanup_continuation_binding PROPERTIES TIMEOUT 30)
    oreslang_contract_test(reject_gpu_kernel unsupported-gpu-kernel.ores --diagnostic "GPU-LAUNCH-001: GPU kernel must return void")
    oreslang_contract_test(reject_quantum_kernel unsupported-quantum-kernel.ores --diagnostic "native gpu/quantum boundary unsupported")
    oreslang_contract_test(reject_gpu_expression unsupported-gpu-expression.ores --diagnostic "native gpu/quantum boundary unsupported")
    oreslang_contract_test(reject_cpu_expression unsupported-cpu-expression.ores --diagnostic "native cpu execution block boundary unsupported")
    add_executable(oreslang-data-layout-advisor-test tests/native_data_layout_advisor_test.cpp)
    target_include_directories(oreslang-data-layout-advisor-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-data-layout-advisor-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-data-layout-advisor-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_data_layout_advisor COMMAND oreslang-data-layout-advisor-test)
    add_executable(oreslang-dop-layout-test tests/dop_layout_test.cpp)
    target_include_directories(oreslang-dop-layout-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-dop-layout-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME dop_layout_checked_geometry COMMAND oreslang-dop-layout-test)

    # Heterogeneous u32/f64/bool/ref32 geometry; 4 distinct AoS/SoA/AoSoA/SoAoS
    # plans. Native host ABI tests only; source-compiled guest parity is separate.
    add_executable(oreslang-dop-heterogeneous-layout-test
        tests/dop_heterogeneous_layout_test.cpp)
    target_include_directories(oreslang-dop-heterogeneous-layout-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-dop-heterogeneous-layout-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-dop-heterogeneous-layout-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME dop_heterogeneous_four_layout_checked_geometry
        COMMAND oreslang-dop-heterogeneous-layout-test)
    set_tests_properties(dop_heterogeneous_four_layout_checked_geometry
        PROPERTIES TIMEOUT 20)

    # Host-only physical AoS/SoA/AoSoA storage and checked opaque row identity.
    # No guest Ref<T> lowering or actor execution authority is implied.
    add_executable(oreslang-dop-checked-region-test tests/dop_checked_region_test.cpp)
    target_include_directories(oreslang-dop-checked-region-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-dop-checked-region-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-dop-checked-region-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_dop_checked_region_addressing
        COMMAND oreslang-dop-checked-region-test)
    set_tests_properties(native_dop_checked_region_addressing PROPERTIES TIMEOUT 20)

    add_executable(oreslang-dop-inverted-columns-test tests/dop_inverted_columns_test.cpp)
    target_include_directories(oreslang-dop-inverted-columns-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-dop-inverted-columns-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-dop-inverted-columns-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-dop-inverted-columns-test PRIVATE Threads::Threads)
    add_test(NAME dop_inverted_columns_safe_handles COMMAND oreslang-dop-inverted-columns-test)
    # An opt-in *native host* sanitizer build for the same row/column/borrow
    # differential stress tests. This does not certify guest source lowering.
    option(ORESLANG_DOP_ASAN_UBSAN
           "Build additional SoA C++ regression executable under ASan+UBSan" OFF)
    if(ORESLANG_DOP_ASAN_UBSAN)
        if(NOT CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
            message(FATAL_ERROR "SoA ASan/UBSan requires GNU or Clang")
        endif()
        add_executable(oreslang-dop-inverted-columns-sanitized-test
                       tests/dop_inverted_columns_test.cpp)
        target_include_directories(oreslang-dop-inverted-columns-sanitized-test PRIVATE
                                   "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-dop-inverted-columns-sanitized-test PRIVATE cxx_std_20)
        target_compile_options(oreslang-dop-inverted-columns-sanitized-test PRIVATE
                               -O1 -g -Wall -Wextra -Wpedantic -Werror
                               -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(oreslang-dop-inverted-columns-sanitized-test PRIVATE
                            -fsanitize=address,undefined)
        target_link_libraries(oreslang-dop-inverted-columns-sanitized-test PRIVATE Threads::Threads)
        add_test(NAME dop_inverted_columns_asan_ubsan
                 COMMAND oreslang-dop-inverted-columns-sanitized-test)
        set_tests_properties(dop_inverted_columns_asan_ubsan PROPERTIES
                             TIMEOUT 120
                             ENVIRONMENT "ASAN_OPTIONS=detect_leaks=1:halt_on_error=1;UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1")
    endif()
    add_test(NAME dop_inverted_type_admission
        COMMAND ${CMAKE_COMMAND} -E env "CXX=${CMAKE_CXX_COMPILER}"
                bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/dop_inverted_negative_admission.sh")

    # Actor-independent Arc-style immutable buffer reference. This validates
    # host ownership/pinning only; it does not establish Oreslang guest lowering.
    add_executable(oreslang-dop-shared-immutable-test tests/dop_shared_immutable_test.cpp)
    target_include_directories(oreslang-dop-shared-immutable-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-dop-shared-immutable-test PRIVATE cxx_std_20)
    target_link_libraries(oreslang-dop-shared-immutable-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-dop-shared-immutable-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME dop_shared_immutable_actor_independent
        COMMAND oreslang-dop-shared-immutable-test)
    set_tests_properties(dop_shared_immutable_actor_independent PROPERTIES TIMEOUT 20)

    # Actor-independent disjoint partition borrow reference for Task/ECS lowering.
    # Host-only CPU proof; not source-level Send/Sync, ECS or Task execution.
    add_executable(oreslang-dop-scoped-partitions-test tests/dop_scoped_partitions_test.cpp)
    target_include_directories(oreslang-dop-scoped-partitions-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-dop-scoped-partitions-test PRIVATE cxx_std_20)
    target_link_libraries(oreslang-dop-scoped-partitions-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-dop-scoped-partitions-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME dop_scoped_partitions_actor_independent
        COMMAND oreslang-dop-scoped-partitions-test)
    set_tests_properties(dop_scoped_partitions_actor_independent PROPERTIES TIMEOUT 20)

    add_executable(oreslang-managed-ref-test tests/managed_ref_test.cpp)
    target_include_directories(oreslang-managed-ref-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-managed-ref-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_managed_reference_width_and_dod_layout
        COMMAND oreslang-managed-ref-test)

    # Run both physical-reference defaults in every build configuration, and
    # force NDEBUG variants even when CI configures a Debug build. The runtime
    # assertions must survive Release optimizations and keep testing native64
    # fallback independently of compressed32.
    foreach(ref_variant IN ITEMS native64 ndebug native64_ndebug)
        set(ref_target "oreslang-managed-ref-test-${ref_variant}")
        add_executable(${ref_target} tests/managed_ref_test.cpp)
        target_include_directories(${ref_target} PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_options(${ref_target} PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        if(ref_variant MATCHES "native64")
            target_compile_definitions(${ref_target} PRIVATE
                ORES_MANAGED_REF_DEFAULT_NATIVE64=1)
        endif()
        if(ref_variant MATCHES "ndebug")
            target_compile_definitions(${ref_target} PRIVATE NDEBUG=1)
            target_compile_options(${ref_target} PRIVATE -O2)
        endif()
        add_test(NAME native_managed_reference_${ref_variant}_contract
            COMMAND ${ref_target})
    endforeach()

    option(ORESLANG_MANAGED_REF_ASAN_UBSAN
           "Enable managed-ref ABI address/undefined sanitizer tests" OFF)
    if(ORESLANG_MANAGED_REF_ASAN_UBSAN)
        if(NOT CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
            message(FATAL_ERROR "Managed-ref ASan/UBSan requires GCC or Clang")
        endif()
        foreach(ref_mode IN ITEMS compressed32 native64)
            set(san_target "oreslang-managed-ref-${ref_mode}-sanitized-test")
            add_executable(${san_target} tests/managed_ref_test.cpp)
            target_include_directories(${san_target} PRIVATE
                "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
            target_compile_options(${san_target} PRIVATE
                -O1 -g -Wall -Wextra -Wpedantic -Werror
                -fsanitize=address,undefined -fno-omit-frame-pointer)
            target_link_options(${san_target} PRIVATE
                -fsanitize=address,undefined)
            if(ref_mode STREQUAL "native64")
                target_compile_definitions(${san_target} PRIVATE
                    ORES_MANAGED_REF_DEFAULT_NATIVE64=1)
            endif()
            add_test(NAME native_managed_reference_${ref_mode}_asan_ubsan
                COMMAND ${san_target})
            set_tests_properties(native_managed_reference_${ref_mode}_asan_ubsan
                PROPERTIES TIMEOUT 90
                ENVIRONMENT "ASAN_OPTIONS=halt_on_error=1;UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1")
        endforeach()
    endif()
    # Nonescaping int loans are supported at checked call boundaries only;
    # a reference still cannot be returned or stored, or cross suspension.
    oreslang_contract_test(reject_rt_borrow unsupported-rt-borrow.ores --diagnostic "borrowed reference may only be passed")
    oreslang_contract_test(native_borrowed_parameter unsupported-borrowed-parameter.ores --ir "load i64")
    oreslang_contract_test(reject_rt_borrow_mut unsupported-rt-borrow-mut.ores --diagnostic "borrowed reference may only be passed")
    oreslang_contract_test(reject_duplicate_mutable_borrow unsupported-double-mut-borrow.ores --diagnostic "duplicate mutable borrow qualifier")
    oreslang_contract_test(reject_rt_ref unsupported-rt-ref.ores --diagnostic "pointer-style rt operation is not valid Oreslang source")
    oreslang_contract_test(reject_rt_deref unsupported-rt-deref.ores --diagnostic "pointer-style rt operation is not valid Oreslang source")
    oreslang_contract_test(reject_rt_unref unsupported-rt-unref.ores --diagnostic "pointer-style rt operation is not valid Oreslang source")
    oreslang_contract_test(reject_rt_raw unsupported-rt-raw.ores --diagnostic "pointer-style rt operation is not valid Oreslang source")
    oreslang_contract_test(reject_pointer_address_of unsupported-address-of.ores --diagnostic "unsupported Oreslang token")
    oreslang_contract_test(reject_pointer_address_of_mut unsupported-address-of-mut.ores --diagnostic "unsupported Oreslang token")
    oreslang_contract_test(reject_pointer_unary_deref unsupported-unary-deref.ores --diagnostic "expected int expression")
    oreslang_contract_test(reject_pointer_type_star unsupported-pointer-type.ores --diagnostic "expected non-keyword identifier")
    oreslang_contract_test(reject_rt_take unsupported-rt-take.ores --diagnostic "unknown parameter")
    oreslang_contract_test(reject_rt_copy unsupported-rt-copy.ores --diagnostic "unknown parameter")
    oreslang_contract_test(reject_rt_share unsupported-rt-share.ores --diagnostic "unknown parameter")
    oreslang_contract_test(reject_unqualified_borrow_call unsupported-borrow-function.ores --diagnostic "unknown function or wrong call arity 'borrow'")
    oreslang_contract_test(reject_suspends unsupported-suspends.ores --diagnostic "native suspends declaration requires")
    oreslang_contract_test(reject_critical unsupported-critical.ores --diagnostic "native critical declaration requires")
    oreslang_contract_test(reject_hyphenated_critical_read unsupported-hyphenated-critical-read.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_critical_read_after_fnc unsupported-hyphenated-critical-read-after-fnc.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_critical_write unsupported-hyphenated-critical-write.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_critical_write_after_fnc unsupported-hyphenated-critical-write-after-fnc.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_actor_critical_read_method unsupported-hyphenated-critical-read-actor-method.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_actor_critical_write_after_fnc unsupported-hyphenated-critical-write-actor-after-fnc.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_actor_critical_read_after_method unsupported-hyphenated-critical-read-actor-after-method.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_actor_critical_read_after_fnc unsupported-hyphenated-critical-read-actor-after-fnc.ores --diagnostic "native reader/writer critical declaration requires")
    oreslang_contract_test(reject_hyphenated_actor_critical_write_before_method unsupported-hyphenated-critical-write-actor-method.ores --diagnostic "native reader/writer critical declaration requires")
    # Whitespace/suffix near-misses must not be interpreted as new RW modes.
    oreslang_contract_test(reject_hyphenated_critical_spaced_as_invalid_name unsupported-hyphenated-critical-spaced.ores --diagnostic "expected '('")
    oreslang_contract_test(reject_hyphenated_critical_suffix_as_invalid_name unsupported-hyphenated-critical-suffix.ores --diagnostic "expected '('")
    oreslang_contract_test(reject_hyphenated_nb_select unsupported-hyphenated-nb-select.ores --diagnostic "native async/channel/select boundary unsupported")
    oreslang_contract_test(reject_hyphenated_transform_pipe unsupported-hyphenated-transform-pipe.ores --diagnostic "native transform-pipe actor requires")
    oreslang_contract_test(hyphenated_transform_minus_pipe hyphenated-transform-minus-pipe.ores)
    oreslang_contract_test(reject_suspend_critical unsupported-suspend-critical.ores --diagnostic "native suspends declaration requires")
    # Native parity substrate for the Graal per-incarnation i64/bool
    # StateAccess lifetime. LLVM guest-source lowering is a separate gate.
    add_executable(oreslang-native-scalar-state-test
        tests/native_scalar_state_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-scalar-state-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    find_package(Threads REQUIRED)
    target_link_libraries(oreslang-native-scalar-state-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-scalar-state-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_scalar_state_critical_lifetimes
        COMMAND oreslang-native-scalar-state-test)
    # Pinned hot code revisions: exercise exact-ABI CAS, bounded retention,
    # failed candidate reclamation and callbacks outside the CONTROL mutex.
    # This is a host-only runtime substrate, not source actor hot replacement.
    add_executable(oreslang-native-supervisor-code-slot-test
        tests/native_supervisor_code_slot_test.cpp)
    target_include_directories(oreslang-native-supervisor-code-slot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-supervisor-code-slot-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-native-supervisor-code-slot-test PRIVATE Threads::Threads)
    add_test(NAME native_supervisor_hot_reload_generation_reclamation
        COMMAND oreslang-native-supervisor-code-slot-test)
    set_tests_properties(native_supervisor_hot_reload_generation_reclamation
        PROPERTIES TIMEOUT 20)
    # Actor-incarnation bounded code-only release; host-only precompiler gate.
    add_executable(oreslang-native-actor-hot-upgrade-gate-test
        tests/native_actor_hot_upgrade_gate_test.cpp)
    target_include_directories(oreslang-native-actor-hot-upgrade-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-hot-upgrade-gate-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-native-actor-hot-upgrade-gate-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_hot_upgrade_generation_quiescence
        COMMAND oreslang-native-actor-hot-upgrade-gate-test)
    set_tests_properties(native_actor_hot_upgrade_generation_quiescence
        PROPERTIES TIMEOUT 20)
    # Real statically linked native machine-code callbacks, with runtime
    # generation pins and guarded publication (not dynamic AOT/ORC loading).
    add_executable(oreslang-native-actor-code-execution-test
        tests/native_actor_code_execution_test.cpp)
    target_include_directories(oreslang-native-actor-code-execution-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-code-execution-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-native-actor-code-execution-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_compiled_aot_callable_generation_execution
        COMMAND oreslang-native-actor-code-execution-test)
    set_tests_properties(native_actor_compiled_aot_callable_generation_execution
        PROPERTIES TIMEOUT 25)
    # Dedicated shutdown-from-reclaim and concurrent terminal-close safety
    # proof. A timeout is a test failure: a deadlocked cleanup cannot pass.
    add_executable(oreslang-native-actor-reentrancy-test
        tests/native_actor_hot_upgrade_reentrancy_test.cpp)
    target_include_directories(oreslang-native-actor-reentrancy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-actor-reentrancy-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-native-actor-reentrancy-test PRIVATE Threads::Threads)
    add_test(NAME native_actor_hot_upgrade_reentrant_close_and_race
        COMMAND oreslang-native-actor-reentrancy-test)
    set_tests_properties(native_actor_hot_upgrade_reentrant_close_and_race
        PROPERTIES TIMEOUT 20)
    # Bounded independent supervisor CONTROL transport (runtime substrate only).
    add_executable(oreslang-supervisor-hierarchy-test tests/supervisor_hierarchy_test.cpp)
    target_include_directories(oreslang-supervisor-hierarchy-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-supervisor-hierarchy-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-supervisor-hierarchy-test PRIVATE Threads::Threads)
    add_test(NAME native_supervisor_hierarchy_control COMMAND oreslang-supervisor-hierarchy-test)
    add_executable(oreslang-supervisor-control-turn-test tests/supervisor_control_turn_test.cpp)
    target_include_directories(oreslang-supervisor-control-turn-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-supervisor-control-turn-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-supervisor-control-turn-test PRIVATE Threads::Threads)
    add_test(NAME native_supervisor_priority_control COMMAND oreslang-supervisor-control-turn-test)
    # An explicit, separate source STOP-only plan with checked Failure
    # admission. Runtime executes parent CONTROL, not general LLVM guest IR.
    add_executable(oreslang-native-supervisor-stop-only-source-test
        tests/native_supervisor_stop_only_source_test.cpp)
    target_include_directories(oreslang-native-supervisor-stop-only-source-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-supervisor-stop-only-source-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-supervisor-stop-only-source-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-supervisor-stop-only-source-test PRIVATE Threads::Threads)
    add_test(NAME native_supervisor_source_stop_only_control
        COMMAND oreslang-native-supervisor-stop-only-source-test)
    add_executable(oreslang-native-supervisor-stop-only-lexical-test
        tests/native_supervisor_stop_only_lexical_test.cpp)
    target_include_directories(oreslang-native-supervisor-stop-only-lexical-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-supervisor-stop-only-lexical-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-supervisor-stop-only-lexical-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_supervisor_stop_only_lexical_security
        COMMAND oreslang-native-supervisor-stop-only-lexical-test)
    add_executable(oreslang-effect-unwind-test tests/effect_unwind_test.cpp)
    target_include_directories(oreslang-effect-unwind-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-effect-unwind-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_typed_exception_unwind COMMAND oreslang-effect-unwind-test)
    # Primitive-only native Assert ABI substrate; imported source execution
    # and compiler-emitted trap remain separately gated.
    add_executable(oreslang-native-assert-scalar-abi-test
        tests/native_assert_scalar_abi_test.cpp)
    target_include_directories(oreslang-native-assert-scalar-abi-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-assert-scalar-abi-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_assert_scalar_abi_contract
        COMMAND oreslang-native-assert-scalar-abi-test)
    # Actor-local uncaught routing never admits PANIC into guest callbacks.
    add_executable(oreslang-actor-local-failure-test tests/actor_local_failure_policy_test.cpp)
    target_include_directories(oreslang-actor-local-failure-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-local-failure-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_local_unhandled_panic_bypass COMMAND oreslang-actor-local-failure-test)
    # Authenticated data-only HTTP/SSR response framing, NOT socket I/O.
    add_executable(oreslang-http-response-guard-test tests/http_response_frame_guard_test.cpp)
    target_include_directories(oreslang-http-response-guard-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-http-response-guard-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_http_response_supervisor_guard COMMAND oreslang-http-response-guard-test)
    # Typed host-runtime bridge; native guest actor compiler lowering is still gated.
    add_executable(oreslang-effect-supervision-bridge-test tests/effect_supervision_bridge_test.cpp)
    target_include_directories(oreslang-effect-supervision-bridge-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-effect-supervision-bridge-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-effect-supervision-bridge-test PRIVATE Threads::Threads)
    add_test(NAME native_effect_unwind_supervisor_control_bridge
        COMMAND oreslang-effect-supervision-bridge-test)
    # Actual host-runtime path: native cleanup -> CONTROL publication ->
    # supervisor admission, with promoted Panic and original typed origin.
    add_executable(oreslang-effect-supervisor-e2e-test
        tests/effect_supervisor_end_to_end_test.cpp)
    target_include_directories(oreslang-effect-supervisor-e2e-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-effect-supervisor-e2e-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-effect-supervisor-e2e-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_effect_unwind_supervisor_end_to_end
        COMMAND oreslang-effect-supervisor-e2e-test)
    add_executable(oreslang-supervisor-failure-lane-test tests/supervisor_failure_lane_test.cpp)
    target_include_directories(oreslang-supervisor-failure-lane-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-supervisor-failure-lane-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_supervisor_failure_lane COMMAND oreslang-supervisor-failure-lane-test)
    oreslang_contract_test(reject_throw_effect unsupported-throw-effect.ores --diagnostic "native exceptional-control boundary unsupported")
    oreslang_contract_test(reject_raise_effect unsupported-raise-effect.ores --diagnostic "native exceptional-control boundary unsupported")
    oreslang_contract_test(reject_panic_effect unsupported-panic-effect.ores --diagnostic "native exceptional-control boundary unsupported")
    oreslang_contract_test(reject_recover_effect unsupported-recover-effect.ores --diagnostic "native exceptional-control boundary unsupported")

    add_executable(oreslang-critical-gate-test tests/critical_gate_test.cpp)
    target_include_directories(oreslang-critical-gate-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    find_package(Threads REQUIRED)
    target_link_libraries(oreslang-supervisor-failure-lane-test PRIVATE Threads::Threads)
    target_link_libraries(oreslang-critical-gate-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-critical-gate-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_logical_critical_gate COMMAND oreslang-critical-gate-test)

    # HOST-ONLY immutable snapshot root protocol, not compiled guest critical
    # syntax or canonical .atto persistent map/set/list implementation.
    # Six real competing native threads exercise versioned compare/publish,
    # pinned old generations, bounded capacity and lost-update rejection.
    add_executable(oreslang-native-immutable-critical-snapshot-test
        tests/native_immutable_critical_snapshot_test.cpp)
    target_include_directories(oreslang-native-immutable-critical-snapshot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-immutable-critical-snapshot-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-immutable-critical-snapshot-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-immutable-critical-snapshot-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_immutable_snapshot_critical_publication_host_only
        COMMAND oreslang-native-immutable-critical-snapshot-test)
    set_tests_properties(native_immutable_snapshot_critical_publication_host_only
        PROPERTIES TIMEOUT 45)

    add_executable(oreslang-function-effects-test tests/function_effects_test.cpp)
    target_include_directories(oreslang-function-effects-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-function-effects-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_function_effect_bitset_contract COMMAND oreslang-function-effects-test)
    add_executable(oreslang-critical-state-effects-test tests/critical_handle_state_contract_test.cpp)
    target_include_directories(oreslang-critical-state-effects-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-critical-state-effects-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_critical_handle_state_using_contract COMMAND oreslang-critical-state-effects-test)
    add_executable(oreslang-critical-state-loan-bridge-test tests/critical_state_loan_bridge_test.cpp)
    target_include_directories(oreslang-critical-state-loan-bridge-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-critical-state-loan-bridge-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_critical_state_loan_runtime_bridge COMMAND oreslang-critical-state-loan-bridge-test)
    add_executable(oreslang-owned-scalar-state-test tests/actor_owned_scalar_state_test.cpp)
    target_include_directories(oreslang-owned-scalar-state-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-owned-scalar-state-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_owned_typed_scalar_state COMMAND oreslang-owned-scalar-state-test)
    # Bounded synchronous state-loan guard; not source-level suspend lowering.
    add_executable(oreslang-actor-state-scope-test tests/actor_state_scope_test.c)
    target_compile_options(oreslang-actor-state-scope-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_state_scope_nested_loan_fencing
        COMMAND oreslang-actor-state-scope-test)
    add_executable(oreslang-logical-mutex-test tests/logical_mutex_test.cpp)
    target_include_directories(oreslang-logical-mutex-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-logical-mutex-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-logical-mutex-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_logical_mutex_fifo_and_cancellation COMMAND oreslang-logical-mutex-test)
    # Host policy only; guest exceptional effects remain rejected.
    add_executable(oreslang-native-exception-policy-test tests/native_exception_policy_test.cpp)
    target_include_directories(oreslang-native-exception-policy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-exception-policy-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_exception_failure_precedence_and_diagnostics
             COMMAND oreslang-native-exception-policy-test)
    # Task borrow/capture regressions: source Task execution remains
    # fail-closed, while the host-only admission primitive proves exact
    # boundary/epoch/domain identity and Send gating independently.
    find_program(ORE_RUSTC NAMES rustc REQUIRED)

    # Generator v1 native source-to-frame E2E. Preserve the existing fail-closed
    # fixture matrix while also proving the admitted canonical sync slice.
    set(ORE_NATIVE_GENERATOR_E2E
        "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-generator-i64-e2e")
    add_custom_command(
        OUTPUT "${ORE_NATIVE_GENERATOR_E2E}"
        COMMAND "${ORE_RUSTC}" --edition=2021 -O -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_generator_i64_e2e.rs"
            -o "${ORE_NATIVE_GENERATOR_E2E}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_generator_i64_e2e.rs"
        VERBATIM)
    add_custom_target(oreslang-native-generator-i64-e2e-build ALL
        DEPENDS "${ORE_NATIVE_GENERATOR_E2E}")
    if(ORE_CLANG)
        add_test(NAME native_generator_i64_compiled_execution
            COMMAND "${ORE_NATIVE_GENERATOR_E2E}"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
    else()
        add_test(NAME native_generator_i64_compiled_execution
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_generator_i64_compiled_execution
        PROPERTIES TIMEOUT 180)
    set(ORE_NATIVE_TASK_BORROW_TEST
        "${CMAKE_CURRENT_BINARY_DIR}/native_task_borrow_rejection")
    add_custom_command(
        OUTPUT "${ORE_NATIVE_TASK_BORROW_TEST}"
        COMMAND "${ORE_RUSTC}" --edition=2021 -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_task_borrow_rejection.rs"
            -o "${ORE_NATIVE_TASK_BORROW_TEST}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_task_borrow_rejection.rs"
        VERBATIM)
    add_custom_target(oreslang-native-task-borrow-rejection-rust ALL
        DEPENDS "${ORE_NATIVE_TASK_BORROW_TEST}")
    add_test(NAME native_task_borrow_rejection_no_ir
        COMMAND "${ORE_NATIVE_TASK_BORROW_TEST}"
            "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_task_borrow_rejection_no_ir PROPERTIES TIMEOUT 60)

    add_executable(oreslang-native-task-capture-admission-test
        tests/native_task_capture_admission_test.cpp)
    target_include_directories(oreslang-native-task-capture-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-task-capture-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_task_capture_admission_host_only
        COMMAND oreslang-native-task-capture-admission-test)
    set_tests_properties(native_task_capture_admission_host_only
        PROPERTIES TIMEOUT 20)

    # First executable source Task<int> slice. Producer work is admitted to one
    # shared NativeActorScheduler carrier and never invoked inline by submit.
    # Await is trusted external-host glue only; the checked runtime API must
    # reject a scheduler carrier rather than park it.
    add_executable(oreslang-source-task-i64-runtime-test
        tests/source_task_i64_runtime_test.c
        src/main/cpp/oreslang/runtime/SourceTaskRuntime.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-source-task-i64-runtime-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-source-task-i64-runtime-test PRIVATE c_std_11)
    target_compile_definitions(oreslang-source-task-i64-runtime-test PRIVATE
        ORESLANG_SOURCE_TASK_TESTING=1)
    target_compile_options(oreslang-source-task-i64-runtime-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-source-task-i64-runtime-test PRIVATE
        Threads::Threads)
    add_test(NAME native_source_task_i64_shared_scheduler_runtime
        COMMAND oreslang-source-task-i64-runtime-test)
    set_tests_properties(native_source_task_i64_shared_scheduler_runtime
        PROPERTIES TIMEOUT 30)

    # Actor-owned runtime Task lane shares the existing actor scheduler's
    # single-writer lease and worker pool. This is a native host acceptance
    # test, NOT compiler-lowered .atto task/await guest execution.
    add_executable(oreslang-native-actor-task-pool-affinity-test
        tests/native_actor_task_pool_affinity_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-task-pool-affinity-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-task-pool-affinity-test PRIVATE c_std_11)
    target_compile_options(oreslang-native-actor-task-pool-affinity-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-actor-task-pool-affinity-test PRIVATE
        Threads::Threads)
    add_test(NAME native_actor_task_pool_affinity_host_only
        COMMAND oreslang-native-actor-task-pool-affinity-test)
    set_tests_properties(native_actor_task_pool_affinity_host_only
        PROPERTIES TIMEOUT 40)

    set(ORE_SOURCE_TASK_E2E
        "${CMAKE_CURRENT_BINARY_DIR}/oreslang-source-task-i64-e2e")
    add_custom_command(
        OUTPUT "${ORE_SOURCE_TASK_E2E}"
        COMMAND "${ORE_RUSTC}" --edition=2021 -O -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_task_i64_e2e.rs"
            -o "${ORE_SOURCE_TASK_E2E}"
        DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_task_i64_e2e.rs"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/source-task-i64-await.ores"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/SourceTaskRuntime.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/SourceTaskRuntime.h"
        VERBATIM)
    add_custom_target(oreslang-source-task-i64-e2e-build ALL
        DEPENDS "${ORE_SOURCE_TASK_E2E}")
    if(ORE_CLANG)
        add_test(NAME native_source_task_i64_compiled_execution
            COMMAND "${ORE_SOURCE_TASK_E2E}"
                "$<TARGET_FILE:oreslang-llvmc>"
                "${ORE_CLANG}"
                "${CMAKE_CURRENT_SOURCE_DIR}")
    else()
        add_test(NAME native_source_task_i64_compiled_execution
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_source_task_i64_compiled_execution
        PROPERTIES TIMEOUT 180)

    set(ORE_SOURCE_FUTURE_PIPELINE_E2E
        "${CMAKE_CURRENT_BINARY_DIR}/oreslang-source-future-pipeline-i64-e2e")
    add_custom_command(
        OUTPUT "${ORE_SOURCE_FUTURE_PIPELINE_E2E}"
        COMMAND "${ORE_RUSTC}" --edition=2021 -O -D warnings
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_future_pipeline_i64_e2e.rs"
            -o "${ORE_SOURCE_FUTURE_PIPELINE_E2E}"
        DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/source_future_pipeline_i64_e2e.rs"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/source-future-pipeline-i64.ores"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/source-future-pipeline-async-stage-i64.ores"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/SourceTaskRuntime.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/SourceTaskRuntime.h"
        VERBATIM)
    add_custom_target(oreslang-source-future-pipeline-i64-e2e-build ALL
        DEPENDS "${ORE_SOURCE_FUTURE_PIPELINE_E2E}")
    if(ORE_CLANG)
        add_test(NAME native_source_future_pipeline_i64_compiled_execution
            COMMAND "${ORE_SOURCE_FUTURE_PIPELINE_E2E}"
                "$<TARGET_FILE:oreslang-llvmc>"
                "${ORE_CLANG}"
                "${CMAKE_CURRENT_SOURCE_DIR}")
    else()
        add_test(NAME native_source_future_pipeline_i64_compiled_execution
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_source_future_pipeline_i64_compiled_execution
        PROPERTIES TIMEOUT 180)

    add_test(NAME native_reserved_java_keywords
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_reserved_java_keywords.py"
            "$<TARGET_FILE:oreslang-llvmc>")
    oreslang_contract_test(reject_static_select unsupported-static-select.ores --diagnostic "native async/channel/select boundary unsupported")
    oreslang_contract_test(reject_dynamic_select_plan unsupported-dynamic-select-plan.ores --diagnostic "native async/channel/select boundary unsupported")
    # Until typed descriptor ownership and atomic selection land, source
    # SelectPlan factories and persistent case writes must fail closed.
    oreslang_contract_test(reject_select_plan_factory unsupported-select-plan-constructor.ores --diagnostic "native SelectPlan/SelectCase ownership unsupported")
    oreslang_contract_test(reject_reusable_case_write unsupported-select-case-write.ores --diagnostic "native SelectPlan/SelectCase ownership unsupported")
    oreslang_contract_test(reject_undefined undefined-name.ores --diagnostic "unknown parameter")

    # Native i64 is a Copy value: rt copy/rt share are scalar value copies,
    # while rt take invalidates named scalar binding until function exit.
    oreslang_contract_test(native_rt_copy_scalar native-rt-copy-scalar.ores --ir "llvm.sadd.with.overflow.i64" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(native_rt_take_scalar native-rt-take-scalar.ores --ir "llvm.sadd.with.overflow.i64" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(native_rt_share_scalar native-rt-share-scalar.ores --ir "llvm.sadd.with.overflow.i64" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(reject_take_then_reuse native-reject-take-then-reuse.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(reject_take_call_arg_overlap native-reject-take-call-args.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(reject_double_take native-reject-double-take.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(native_loan_read_ir native-borrow-read.ores --ir "call i64 @inspect")
    oreslang_contract_test(native_loan_exclusive_ir native-borrow-exclusive.ores --ir "call i64 @inspect_mut")
    oreslang_contract_test(native_reborrow_shared_ir native-reborrow-shared.ores --ir "call i64 @inspect")
    oreslang_contract_test(native_reborrow_mut_ir native-reborrow-mut.ores --ir "call i64 @inspect_mut")
    oreslang_contract_test(native_reborrow_mut_to_shared_ir native-reborrow-mut-shared.ores --ir "call i64 @inspect")
    add_test(NAME native_ownership_async_boundary_matrix
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_ownership_boundary_matrix.py"
            "$<TARGET_FILE:oreslang-llvmc>")
    # LLVM has no native I/O binding registry or continuation lowering yet.
    # Both spellings must fail closed with no IR until implementation lands.
    oreslang_contract_test(reject_unsupported_native_io_blocking unsupported-native-io-blocking.ores --diagnostic "unknown function or wrong call arity")
    oreslang_contract_test(reject_unsupported_native_io_bare unsupported-native-io-bare.ores --diagnostic "unknown function or wrong call arity")
    oreslang_contract_test(reject_unsupported_native_io_global_launder unsupported-native-io-global-launder.ores --diagnostic "native I/O callable name is compiler-owned")
    oreslang_contract_test(reject_unsupported_native_io_method_launder unsupported-native-io-method-launder.ores --diagnostic "native I/O callable name is compiler-owned")
    oreslang_contract_test(reject_unverified_target_header unsupported-target-header.ores --diagnostic "native target admission unsupported")
    # Neither naming style is permitted without a typed I/O registry, async
    # transport and continuation lowering. No IR may be emitted on failure.
    add_test(NAME native_async_only_io_boundary_matrix
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" native-async-io-boundary
            "$<TARGET_FILE:oreslang-llvmc>")
    # General structural shapes are not a scalar-i64 ABI: prove negative
    # admission with a functioning scalar-positive compiler control.
    # Rust-only test executable, built with ordinary CMake ALL builds.
    # If rustc is absent, the CTest deliberately fails rather than claiming coverage.
    find_program(ORE_STRUCTURAL_RUSTC rustc)
    if(ORE_STRUCTURAL_RUSTC)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-structural-gate"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_structural_shape_failclosed.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-structural-gate"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_structural_shape_failclosed.rs"
            VERBATIM)
        add_custom_target(oreslang-native-structural-gate-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-structural-gate")
        add_test(NAME native_structural_shape_failclosed
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-structural-gate"
                "$<TARGET_FILE:oreslang-llvmc>")
    else()
        add_test(NAME native_structural_shape_failclosed
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_structural_shape_failclosed PROPERTIES TIMEOUT 180)
    # Bounded exhaustive shared/exclusive loan and alias partitions. Keep both
    # registrations even without rustc/clang: unavailable tooling must fail.
    if(ORE_STRUCTURAL_RUSTC AND ORE_CLANG)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-alias-matrix"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_borrow_alias_matrix.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-alias-matrix"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_borrow_alias_matrix.rs"
            VERBATIM)
        add_custom_target(oreslang-native-borrow-alias-matrix-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-alias-matrix")
    endif()
    foreach(mode IN ITEMS release sanitizer)
        if(ORE_STRUCTURAL_RUSTC AND ORE_CLANG)
            set(alias_matrix_extra)
            if(mode STREQUAL "sanitizer")
                set(alias_matrix_extra --sanitize)
            endif()
            add_test(NAME native_borrow_alias_matrix_${mode}
                COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-borrow-alias-matrix"
                    --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}"
                    ${alias_matrix_extra})
        else()
            add_test(NAME native_borrow_alias_matrix_${mode}
                COMMAND "${CMAKE_COMMAND}" -E false)
        endif()
        set_tests_properties(native_borrow_alias_matrix_${mode} PROPERTIES TIMEOUT 180)
    endforeach()
    if(ORE_STRUCTURAL_RUSTC AND ORE_CLANG)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-class-string-parity"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_class_string_parity.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-class-string-parity"
            DEPENDS tests/native_class_string_parity.rs
                tests/fixtures/native-class-string/main.ores tests/fixtures/native-class-string/main-1.ores
                tests/fixtures/native-class-string/string-array.ores
                tests/fixtures/native-class-string/path-segment.ores
                tests/fixtures/native-class-string/record-array.ores
                tests/fixtures/native-class-string/owned-path-pattern.ores
                tests/fixtures/native-class-string/record-return.ores
                tests/fixtures/native-class-string/nested-owned-capture.ores
                tests/fixtures/native-class-string/routing-option-capture.ores
                tests/fixtures/native-class-string/ordered-routing-locals.ores
                tests/fixtures/native-class-string/ordered-array-locals.ores
                tests/fixtures/native-class-string/copy-option-v1.atto
                tests/fixtures/native-class-string/routing-path-validation.ores
                tests/fixtures/native-class-string/typed-array-iteration.ores
                tests/fixtures/native-class-string/owned-aggregate-return.ores
            VERBATIM)
        add_custom_target(oreslang-native-class-string-parity-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-class-string-parity")
    endif()
    foreach(mode IN ITEMS release sanitizer)
        set(string_parity_extra)
        if(mode STREQUAL "sanitizer")
            set(string_parity_extra --sanitize)
        endif()
        if(ORE_STRUCTURAL_RUSTC AND ORE_CLANG)
            add_test(NAME native_class_string_parity_${mode}
                COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-class-string-parity"
                    --compiler "$<TARGET_FILE:oreslang-llvmc>" --clang "${ORE_CLANG}"
                    --runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeStringRegion.c"
                    --aot-driver "${CMAKE_CURRENT_SOURCE_DIR}/scripts/compile-aot.sh"
                    ${string_parity_extra})
        else()
            add_test(NAME native_class_string_parity_${mode} COMMAND "${CMAKE_COMMAND}" -E false)
        endif()
        set_tests_properties(native_class_string_parity_${mode} PROPERTIES TIMEOUT 180)
    endforeach()
    # Unary + now lowers as an int-only identity under the existing typed
    # scalar checker. Verify guest compilation, real AOT execution, and
    # fail-closed type/range/recursion rejection without Python tooling.
    if(ORE_STRUCTURAL_RUSTC AND ORE_CLANG)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-unary-plus-parity"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_unary_plus_source_parity.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-unary-plus-parity"
            DEPENDS tests/native_unary_plus_source_parity.rs
            VERBATIM)
        add_custom_target(oreslang-native-unary-plus-parity-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-unary-plus-parity")
        add_test(NAME native_unary_plus_source_aot
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-native-unary-plus-parity"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
    else()
        add_test(NAME native_unary_plus_source_aot COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_unary_plus_source_aot PROPERTIES TIMEOUT 180)
    if(ORE_STRUCTURAL_RUSTC)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-strict-nonblocking-io-source-guard"
            COMMAND "${ORE_STRUCTURAL_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/strict_nonblocking_io_source_guard.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-strict-nonblocking-io-source-guard"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/tests/strict_nonblocking_io_source_guard.rs"
            VERBATIM)
        add_custom_target(oreslang-strict-nonblocking-io-source-guard-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-strict-nonblocking-io-source-guard")
        add_test(NAME native_strict_nonblocking_io_source_guard
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-strict-nonblocking-io-source-guard"
                "${CMAKE_CURRENT_SOURCE_DIR}")
    else()
        add_test(NAME native_strict_nonblocking_io_source_guard
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_strict_nonblocking_io_source_guard PROPERTIES TIMEOUT 30)

    # Executable Linux readiness/data-plane contract. This complements the
    # Rust source invariant guard with behavior over the actual native substrate.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-native-nonblocking-io-contract-test
            tests/native_nonblocking_io_contract_test.cpp)
        target_include_directories(oreslang-native-nonblocking-io-contract-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_options(oreslang-native-nonblocking-io-contract-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_strict_nonblocking_io_contract
            COMMAND oreslang-native-nonblocking-io-contract-test)
        set_tests_properties(native_strict_nonblocking_io_contract PROPERTIES TIMEOUT 15)
    endif()

    oreslang_contract_test(pure_user_blocking_suffix pure-user-blocking-suffix.ores --ir "call i64 @pure_math_blocking")
    # The terminal-state gate is a standalone native safety primitive, not
    # an async I/O reactor or source-level await implementation.
    add_executable(oreslang-async-io-operation-test tests/async_io_operation_test.cpp)
    target_include_directories(oreslang-async-io-operation-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    find_package(Threads REQUIRED)
    target_link_libraries(oreslang-async-io-operation-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-async-io-operation-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_async_io_completion_generation_races COMMAND oreslang-async-io-operation-test)
    # Linux-only: nonblocking TCP syscalls + epoll readiness, not std/net lowering.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-nonblocking-tcp-linux-test tests/nonblocking_tcp_linux_test.cpp)
        target_include_directories(oreslang-nonblocking-tcp-linux-test PRIVATE src/main/cpp)
        target_compile_options(oreslang-nonblocking-tcp-linux-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_linux_nonblocking_tcp_readiness COMMAND oreslang-nonblocking-tcp-linux-test)
        set_tests_properties(native_linux_nonblocking_tcp_readiness PROPERTIES TIMEOUT 20)
        # Kernel-level multi-listener accept: four independent worker sockets
        # bind to one port through SO_REUSEPORT. No compiled guest actor claim.
        add_executable(oreslang-worker-reuseport-accept-test
            tests/native_worker_reuseport_accept_test.cpp)
        target_include_directories(oreslang-worker-reuseport-accept-test PRIVATE src/main/cpp)
        target_link_libraries(oreslang-worker-reuseport-accept-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-worker-reuseport-accept-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_linux_worker_reuseport_accept
            COMMAND oreslang-worker-reuseport-accept-test)
        set_tests_properties(native_linux_worker_reuseport_accept PROPERTIES TIMEOUT 20)
        # Host-only, bounded network request buffer/loan contract. The test
        # executes actual nonblocking TCP handoff on Linux; it does not prove
        # Oreslang guest actor lowering or HTTP parser parity.
        add_executable(oreslang-request-slot-table-test tests/request_slot_table_test.cpp)
        target_include_directories(oreslang-request-slot-table-test PRIVATE src/main/cpp)
        target_link_libraries(oreslang-request-slot-table-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-request-slot-table-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_network_request_slot_borrow_host_only
                 COMMAND oreslang-request-slot-table-test)
        set_tests_properties(native_network_request_slot_borrow_host_only
            PROPERTIES TIMEOUT 30)
        # Host-only, strict-subset HTTP/1 head views over transport read leases.
        add_executable(oreslang-http-request-head-view-test tests/http_request_head_view_test.cpp)
        target_include_directories(oreslang-http-request-head-view-test PRIVATE src/main/cpp)
        target_link_libraries(oreslang-http-request-head-view-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-http-request-head-view-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_http_request_head_borrow_noalloc_host_only
                 COMMAND oreslang-http-request-head-view-test)
        set_tests_properties(native_http_request_head_borrow_noalloc_host_only
            PROPERTIES TIMEOUT 30)
        # Incarnation-bound host authority and teardown revocation proof.
        # No guest actor compiler or source mailbox integration is implied.
        add_executable(oreslang-actor-bound-request-table-test
            tests/actor_bound_request_table_test.cpp)
        target_include_directories(oreslang-actor-bound-request-table-test PRIVATE src/main/cpp)
        target_link_libraries(oreslang-actor-bound-request-table-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-actor-bound-request-table-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_actor_request_incarnation_revocation_host_only
            COMMAND oreslang-actor-bound-request-table-test)
        set_tests_properties(native_actor_request_incarnation_revocation_host_only
            PROPERTIES TIMEOUT 30)
    endif()
    set_tests_properties(native_async_io_completion_generation_races PROPERTIES TIMEOUT 20)
    # Pre-source-lowering safety gate: prevents stale continuation wakeups,
    # premature actor-heap retirement, duplicate resumes and live-loan drops.
    add_executable(oreslang-pinned-continuation-gate-test tests/pinned_continuation_gate_test.cpp)
    target_include_directories(oreslang-pinned-continuation-gate-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-pinned-continuation-gate-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-pinned-continuation-gate-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_pinned_continuation_loan_cancel_quiescence
             COMMAND oreslang-pinned-continuation-gate-test)
    set_tests_properties(native_pinned_continuation_loan_cancel_quiescence PROPERTIES TIMEOUT 30)
    # Real actor-owned fixed-field loans must survive logical suspension
    # without permitting mutation, cancellation-time drop, or cross-actor alias.
    if(UNIX)
        add_executable(oreslang-pinned-actor-heap-suspend-test
            tests/pinned_actor_heap_suspend_test.cpp
            src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
        target_include_directories(oreslang-pinned-actor-heap-suspend-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_options(oreslang-pinned-actor-heap-suspend-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        target_link_libraries(oreslang-pinned-actor-heap-suspend-test PRIVATE Threads::Threads)
        add_test(NAME native_pinned_actor_heap_suspend_cancel_quiescence
                 COMMAND oreslang-pinned-actor-heap-suspend-test)
        set_tests_properties(native_pinned_actor_heap_suspend_cancel_quiescence PROPERTIES TIMEOUT 30)
    endif()
    # Tuple / Entry are nominal aggregate values in Java/Graal. Until the
    # LLVM frontend provides a checked native product ABI, fail closed before
    # emitting IR for *all* arities, including () and (x,).
    oreslang_contract_test(native_tuple_explicit_literal_failclosed
        native-tuple-explicit-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_tuple_compact_literal_failclosed
        native-tuple-compact-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_tuple_empty_explicit_failclosed
        native-tuple-empty-explicit-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_tuple_empty_compact_failclosed
        native-tuple-empty-compact-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_tuple_singleton_explicit_failclosed
        native-tuple-singleton-explicit-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_tuple_singleton_compact_failclosed
        native-tuple-singleton-compact-reject.ores --diagnostic "native tuple literal unsupported")
    oreslang_contract_test(native_entry_value_failclosed
        native-entry-reject.ores --diagnostic "native Entry<K,V> unsupported")
    oreslang_contract_test(native_entry_generic_product_failclosed
        native-entry-generic-reject.ores --diagnostic "native Entry<K,V> unsupported")
    oreslang_contract_test(native_user_tuple_call_is_ordinary
        native-user-tuple-call.ores --ir "call i64 @tuple")
    oreslang_contract_test(native_local_entry_identifier_is_ordinary
        native-local-entry-name.ores --ir "define i64 @__oreslang_guest_main")
    if(ORE_CLANG)
        oreslang_contract_test(native_user_tuple_call_executes
            native-user-tuple-call.ores --run 42 --clang "${ORE_CLANG}")
        set_tests_properties(native_user_tuple_call_executes PROPERTIES TIMEOUT 60)
        oreslang_contract_test(native_local_entry_identifier_executes
            native-local-entry-name.ores --run 42 --clang "${ORE_CLANG}")
        set_tests_properties(native_local_entry_identifier_executes PROPERTIES TIMEOUT 60)
    endif()

    oreslang_contract_test(reject_wrong_arity wrong-arity.ores --diagnostic "wrong call arity")
    oreslang_contract_test(native_named_call_unknown_label named-call-unknown-reject.ores
        --diagnostic "unknown named argument")
    oreslang_contract_test(native_named_call_duplicate_slot named-call-duplicate-reject.ores
        --diagnostic "duplicate binding of parameter")
    oreslang_contract_test(native_named_call_positional_after_named named-call-positional-after-named-reject.ores
        --diagnostic "positional argument cannot follow named argument")
    oreslang_contract_test(native_named_call_mut_borrow_conflict named-call-mut-borrow-after-loan-reject.ores
        --diagnostic "field read conflicts with active mutable borrow")
    oreslang_contract_test(native_named_member_positional_array_baseline
        named-member-array-positional-main.ores
        --ir "define i64 @__oreslang_guest_main")
    # Member-call parsing is a separate syntax path. It must enforce the
    # same duplicate/ordering constraints as direct calls, and native array
    # operations must reject labels until signature-aware permutation exists.
    oreslang_contract_test(native_named_member_unlowered_array_label
        named-member-array-unlowered-reject.ores
        --diagnostic "named native array/member arguments require checked lowering")
    oreslang_contract_test(native_named_member_duplicate_label
        named-member-array-duplicate-reject.ores
        --diagnostic "duplicate named member argument")
    oreslang_contract_test(native_named_member_positional_after_named
        named-member-array-positional-after-reject.ores
        --diagnostic "positional member argument cannot follow named argument")
    oreslang_contract_test(reject_duplicates duplicate-function.ores
        --diagnostic "already has arity 0")
    oreslang_contract_test(reject_same_arity_different_names native-name-arity-duplicate.ores
        --diagnostic "already has arity 1")
    # Audit canonical name+arity selection through the ownership checker,
    # not merely LLVM symbol generation. No type/mode scoring or loan escapes.
    oreslang_contract_test(native_name_arity_conflicting_loans native-name-arity-conflicting-loans.ores
        --diagnostic "conflicting active native borrow")
    oreslang_contract_test(native_name_arity_nested_loan_conflict native-name-arity-nested-loan-conflict.ores
        --diagnostic "conflicting active native borrow")
    oreslang_contract_test(native_name_arity_mode_mismatch native-name-arity-borrow-mode-mismatch.ores
        --diagnostic "borrow mode does not match borrowed parameter")
    oreslang_contract_test(native_name_arity_borrow_mode_duplicate native-name-arity-duplicate-borrow-modes.ores
        --diagnostic "already has arity 1")
    oreslang_contract_test(native_name_arity_main_overload_reject native-name-arity-overloaded-main.ores
        --diagnostic "main cannot be overloaded")
    oreslang_contract_test(native_name_arity_gap_reject native-name-arity-gap-reject.ores
        --diagnostic "wrong call arity")
    if(ORE_CLANG)
        oreslang_contract_test(native_compiled_name_arity_overloads
            native-name-arity-overloads.ores
            --ir "pick\$arity0" --run 24 --clang "${ORE_CLANG}")
        set_tests_properties(native_compiled_name_arity_overloads PROPERTIES TIMEOUT 60)
        oreslang_contract_test(native_compiled_name_arity_borrow_abi
            native-name-arity-borrow-execute.ores
            --ir "read\$arity1" --run 41 --clang "${ORE_CLANG}")
        set_tests_properties(native_compiled_name_arity_borrow_abi PROPERTIES TIMEOUT 60)
    endif()
    oreslang_contract_test(reject_duplicate_parameters duplicate-parameter.ores --diagnostic "duplicate parameter")
    oreslang_contract_test(reject_oversized_integer overflow-integer.ores --diagnostic "overflows signed 64-bit")
    oreslang_contract_test(reject_unclosed_comment unclosed-comment.ores --diagnostic "unterminated block comment")

    # Primitive field projections use synchronous whole-object loan
    # admission until per-field provenance can be proven by both checker and
    # LLVM lowering. Rejections must emit zero IR.
    oreslang_contract_test(native_projection_alias_conflict
        class-projection-overlap-reject.ores --diagnostic "conflicting active native borrow")
    oreslang_contract_test(native_projection_immutable_exclusive
        class-projection-immutable-mut-reject.ores --diagnostic "cannot mutably borrow immutable owner")
    oreslang_contract_test(native_projection_moved_field
        class-projection-moved-field-reject.ores --diagnostic "cannot borrow a moved class field")
    oreslang_contract_test(native_projection_private_field
        class-projection-private-reject.ores --diagnostic "private class field")
    oreslang_contract_test(native_projection_type_mismatch
        class-projection-type-mismatch-reject.ores --diagnostic "borrowed argument type mismatch")
    oreslang_contract_test(native_projection_whole_owner_conflict
        class-projection-whole-object-conflict-reject.ores --diagnostic "conflicting active native borrow")

    # Source-level bounded class ownership and per-call shared/exclusive loans.
    # All class records are stack allocated; unverified class shapes and
    # by-value object parameters remain compile-time errors with no IR.
    # Primitive i64 field takes/reinitializations admit checked partial-state
    # ownership. Nontrivial drop/region transfer remains compile-time rejected.
    add_test(NAME native_field_and_region_move_admission
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_class_field_move_admission.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_field_and_region_move_admission PROPERTIES TIMEOUT 90)
    add_test(NAME native_colon_class_field_admission
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_colon_class_fields.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_colon_class_field_admission PROPERTIES TIMEOUT 90)
    if(ORE_CLANG)
        add_test(NAME native_colon_class_field_execution
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_colon_class_fields.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        set_tests_properties(native_colon_class_field_execution PROPERTIES TIMEOUT 120)
    endif()
    # Executable transfer + exact-once conditional owned-region drop flags.
    # Wasm has no native region ABI; unsafe transfers must reject with no IR.
    if(ORE_CLANG)
        add_test(NAME native_owned_region_conditional_drop_matrix
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/owned_region_conditional_drop_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c"
                --include "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        set_tests_properties(native_owned_region_conditional_drop_matrix PROPERTIES TIMEOUT 240)
        add_test(NAME native_owned_region_conditional_drop_sanitized
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/owned_region_conditional_drop_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c"
                --include "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp" --sanitize)
        set_tests_properties(native_owned_region_conditional_drop_sanitized PROPERTIES TIMEOUT 240)
        add_test(NAME native_single_cleanup_epilogue
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_single_cleanup_epilogue_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c"
                --include "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        add_test(NAME native_single_cleanup_epilogue_sanitized
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_single_cleanup_epilogue_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c"
                --include "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp" --sanitize)
        set_tests_properties(native_single_cleanup_epilogue
            native_single_cleanup_epilogue_sanitized PROPERTIES TIMEOUT 240)
    endif()
    oreslang_contract_test(class_reject_byvalue_mutation class-mutation-byvalue-reject.ores --diagnostic "ownership violation")
    # This is the *exact same Git blob* as the Java/Graal conformance
    # source (contracts/v1/semantics/fixtures/ownership.ores), not a
    # purpose-built LLVM-only approximation. A generic parse failure
    # does NOT count as an ownership diagnostic.
    add_test(NAME class_exact_shared_ownership_diagnostic
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/contract.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --fixture "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/semantics/fixtures/ownership.ores"
            --diagnostic "ownership violation")

    oreslang_contract_test(class_reject_shared_mutation class-shared-mutation-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_overlapping_borrows class-double-borrow-reject.ores --diagnostic "conflicting active native borrow")
    oreslang_contract_test(class_reject_unknown_field class-unknown-field-reject.ores --diagnostic "unknown class field")
    oreslang_contract_test(class_reject_noncopy_value class-noncopy-return-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_suspend_boundary class-async-borrow-reject.ores --diagnostic "native await requires HasFuture ActorRef or explicit get_ready()/get_done()")
    oreslang_contract_test(class_admit_ordered_legacy_two_field_layout class-unsupported-layout-reject.ores)
    oreslang_contract_test(class_reject_duplicate_field class-multifield-duplicate-field-reject.ores --diagnostic "duplicate class field")
    oreslang_contract_test(class_reject_nonint_nested_field class-multifield-nonint-field-reject.ores --diagnostic "class field initializer type")
    oreslang_contract_test(class_reject_shared_write_secondary class-multifield-shared-write-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_overlap_across_fields class-multifield-overlap-reject.ores --diagnostic "conflicting active native borrow")
    oreslang_contract_test(class_reject_immutable_exclusive_loan class-immutable-owner-mut-borrow-reject.ores --diagnostic "cannot mutably borrow immutable owner")
    oreslang_contract_test(class_reject_immutable_owner_field_write class-immutable-owner-field-write-reject.ores --diagnostic "class mutation requires let mut owner")
    oreslang_contract_test(class_reject_unknown_secondary_field class-multifield-unknown-field-reject.ores --diagnostic "unknown class field")
    oreslang_contract_test(class_reject_byvalue_multifield_move class-multifield-byvalue-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_taken_field_reuse class-field-use-after-take-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_field_take_on_borrowed_owner class-field-borrowed-take-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_whole_borrow_of_partially_moved_object class-field-borrow-partial-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(class_reject_actor_send_after_move source-actor-send-after-take-reject.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(class_reject_actor_borrowed_payload source-actor-send-borrow-reject.ores --diagnostic "borrowed reference may only be passed")
    oreslang_contract_test(cfg_reject_impure_move_condition cfg-guard-impure-take-reject.ores --diagnostic "use of explicitly moved int")
    oreslang_contract_test(cfg_execute_call_condition cfg-guard-call.ores --ir "call i64 @observe" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(cfg_reject_multifield_class_return cfg-multifield-branch-noncopy-reject.ores --diagnostic "ownership violation")
    oreslang_contract_test(cfg_execute_multifield_call_in_condition cfg-multifield-guard-call.ores --ir "call i64 @observe" --run 42 --clang "${ORE_CLANG}")
    oreslang_contract_test(cfg_reject_missing_fallthrough cfg-guard-missing-final-reject.ores --diagnostic "expected 'return'")
    oreslang_contract_test(cfg_reject_missing_fi cfg-guard-missing-fi-reject.ores --diagnostic "expected 'fi'")
    oreslang_contract_test(cfg_reject_void_guard cfg-guard-void-reject.ores --diagnostic "RETURN_VOID_VALUE: void function cannot return a value")




    # Native deterministic ownership is a runtime primitive, not yet source
    # Oreslang object/borrow lowering. Compile and execute it in every LLVM CI.
    find_package(Threads REQUIRED)
    add_executable(oreslang-owned-heap-test tests/owned_heap_test.cpp)
    target_include_directories(oreslang-owned-heap-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-owned-heap-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-owned-heap-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_owned_heap_deterministic_drop
             COMMAND oreslang-owned-heap-test)
    set_tests_properties(native_owned_heap_deterministic_drop PROPERTIES TIMEOUT 30)

    add_executable(oreslang-epoch-arena-test tests/epoch_arena_test.cpp)
    target_include_directories(oreslang-epoch-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-epoch-arena-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_epoch_deterministic_drop
             COMMAND oreslang-epoch-arena-test)
    set_tests_properties(native_actor_epoch_deterministic_drop PROPERTIES TIMEOUT 20)

    oreslang_contract_test(reject_native_private_array_mutation class-private-array-mutation-reject.ores --diagnostic "private class field")
    oreslang_contract_test(reject_native_private_inventory_bypass
        native-private-inventory-bypass-reject.ores --diagnostic "private class field")
    oreslang_contract_test(reject_native_private_filled_commit class-private-filled-array-commit-reject.ores --diagnostic "private class field")

    if(ORE_CLANG)
        oreslang_contract_test(native_public_class_field_read class-public-field-read-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(class_exec_mutable_borrow class-mutable-borrow-native.ores --run 42 --clang "${ORE_CLANG}")
        # Native guest execution: synchronous borrowed state mutation, overdraw
        # rejection, and joint two-field invariant. Not a GC/no-heap proof.
        oreslang_contract_test(native_exec_owned_inventory_reservation
            native-owned-inventory-reservation.ores --run 74 --clang "${ORE_CLANG}")

        oreslang_contract_test(native_projection_shared_clang
            class-projection-shared-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_projection_exclusive_clang
            class-projection-exclusive-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_projection_shared_reborrow_clang
            class-projection-shared-reborrow-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_projection_two_shared_clang
            class-projection-two-shared-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)

        add_test(NAME native_projection_borrow_compiler_to_clang_matrix
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_projected_field_borrow_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --fixtures "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
        set_tests_properties(native_projection_borrow_compiler_to_clang_matrix
            PROPERTIES TIMEOUT 120)

        oreslang_contract_test(class_exec_shared_read class-shared-read-native.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(class_exec_mut_to_shared_reborrow class-reborrow-native.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(class_exec_owned_stack_field class-owned-field-native.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(class_exec_field_take_reinitialization class-field-take-reinit-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(class_exec_cfg_branch_field_take class-field-take-branch-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(class_exec_two_field_mutator class-multifield-mutator.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(cfg_exec_mutated_multifield_early_true cfg-multifield-mutator-early-true.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(cfg_exec_mutated_multifield_early_false cfg-multifield-mutator-early-false.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(class_exec_three_field_mutator class-multifield-independent-write.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        # Exact same .ores source is exercised by GraalVM ParityScalarEcsGuestTest.
        # This runs actual emitted LLVM IR through Clang; NOT four-layout lowering.
        oreslang_contract_test(parity_source_scalar_ecs_guest
            ecs-scalar-guest-baseline.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(cfg_exec_multifield_guard_taken cfg-multifield-guard-true.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi --ir "guard.return")
        oreslang_contract_test(cfg_exec_multifield_guard_fallthrough cfg-multifield-guard-false.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi --ir "guard.next")
        oreslang_contract_test(cfg_exec_multifield_guard_chain cfg-multifield-guard-chain.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(cfg_exec_scalar_branch_move cfg-scalar-branch-move.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(cfg_exec_experimental_owned_early_drop cfg-heap-owned-early-drop.ores --experimental-owned-int --run 42 --clang "${ORE_CLANG}" --native-runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c" --ir "guard.return")
        add_test(NAME cfg_guarded_drop_all_native_exit_edges
            COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/guarded_cfg_drop_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --native-runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c")
        set_tests_properties(cfg_guarded_drop_all_native_exit_edges PROPERTIES TIMEOUT 240)
        oreslang_contract_test(class_exec_two_field_shared_read class-multifield-shared-read.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        add_test(NAME class_native_1_to_16_field_layout_matrix
            COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/class_multifield_layout_matrix.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}")
        set_tests_properties(class_native_1_to_16_field_layout_matrix PROPERTIES TIMEOUT 240)
        # Free functions must never project private class state, whether
        # by read, write, take or a nested Array<int> member operation.
        oreslang_contract_test(reject_native_private_field_read
            class-private-read-reject.ores --diagnostic "private class field")
        oreslang_contract_test(reject_native_private_field_write
            class-private-write-reject.ores --diagnostic "private class field")
        oreslang_contract_test(reject_native_private_field_take
            class-private-take-reject.ores --diagnostic "private class field")
        oreslang_contract_test(reject_native_private_array_size
            class-private-array-size-reject.ores --diagnostic "private class field")
        oreslang_contract_test(reject_native_final_field_write
            class-final-write-reject.ores --diagnostic "cannot reassign final class field")
        oreslang_contract_test(reject_native_final_field_take
            class-final-take-reject.ores --diagnostic "cannot take final class field")
        oreslang_contract_test(native_class_final_read class-final-read-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_class_colon_write class-colon-rebindable-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_class_val_final_mut_read class-val-read-native.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(reject_native_const_alias_write class-final-alias-write-reject.ores --diagnostic "cannot reassign final class field")
        oreslang_contract_test(reject_native_final_uninitialized class-final-uninitialized-reject.ores --diagnostic "native class constructor argument arity mismatch")
        oreslang_contract_test(reject_native_val_alias_write class-val-alias-write-reject.ores --diagnostic "cannot reassign final class field")
        oreslang_contract_test(reject_native_final_mut_write class-final-mut-write-reject.ores --diagnostic "cannot reassign final class field")
        oreslang_contract_test(reject_native_const_alias_take class-const-take-reject.ores --diagnostic "cannot take final class field")
        # Source-identical Java/GraalVM constructor parity and fail-closed
        # malformed/effectful/owned-input negative controls.
        oreslang_contract_test(native_scalar_constructor_arithmetic
            class-scalar-constructor-arithmetic-native.ores --run 40 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_scalar_constructor_simple
            class-constructor-init-unsupported.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(native_scalar_constructor_public
            class-public-constructor-init-unsupported.ores --run 42 --clang "${ORE_CLANG}" --no-native-abi)
        oreslang_contract_test(reject_scalar_constructor_arity
            class-scalar-constructor-arity-reject.ores --diagnostic "native class constructor argument arity mismatch")
        oreslang_contract_test(reject_scalar_constructor_effect_call
            class-scalar-constructor-call-reject.ores --diagnostic "native class field initializer requires pure scalar")
        oreslang_contract_test(reject_scalar_constructor_unknown
            class-scalar-constructor-unknown-reject.ores --diagnostic "unknown parameter 'other'")
        oreslang_contract_test(reject_scalar_constructor_duplicate
            class-scalar-constructor-duplicate-reject.ores --diagnostic "duplicate native constructor parameter")
        oreslang_contract_test(reject_scalar_constructor_owned_array
            class-scalar-constructor-owned-reject.ores --diagnostic "native owned Array<int> constructor requires checked failure cleanup")
        oreslang_contract_test(reject_scalar_constructor_effect_arg
            class-scalar-constructor-args-effect-reject.ores --diagnostic "native scalar constructor argument requires pure integer expression")
        oreslang_contract_test(reject_scalar_constructor_owned_int_arg
            class-scalar-constructor-owned-arg-reject.ores --experimental-owned-int --diagnostic "native scalar constructor arguments require plain stack scalar values")
        oreslang_contract_test(reject_scalar_constructor_effect_body
            class-scalar-constructor-effectful-body-reject.ores --diagnostic "native scalar constructor body must be empty or contain only")
        oreslang_contract_test(native_exec_constant constant-main.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(numerics_atto_integer_guest numerics-atto-main.atto --run 42 --clang "${ORE_CLANG}")
        # LLVM compiler-produced AttoXP scalar guests; FFI linking is separate.
        oreslang_contract_test(attoxp_counter_guest_42 attoxp-counter-main.atto --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(attoxp_counter_guest_reset attoxp-counter-reset.atto --run 0 --clang "${ORE_CLANG}")
        oreslang_contract_test(attoxp_counter_guest_decrement attoxp-counter-decrement.atto --run 41 --clang "${ORE_CLANG}")
        oreslang_contract_test(atto_shared_scalar_guest ../../contracts/atto-parity-v1/add.atto --run 42 --clang "${ORE_CLANG}")
        # Same byte-exact scalar corpus in LLVM, .NET and Wasm. These are
        # genuine LLVM compiler/Clang/OS guest tests, not UI/FFI parity.
        # Host integrity only; compile/link/run evidence comes from guest CTests below.
        add_test(NAME attoxp_scalar_shared_source_pins
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/attoxp_scalar_manifest_test.py")
        set_tests_properties(attoxp_scalar_shared_source_pins PROPERTIES TIMEOUT 15)
        oreslang_contract_test(attoxp_scalar_guest_answer42 ../../contracts/attoxp-scalar-v1/answer-42.atto --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(attoxp_scalar_guest_decrement41 ../../contracts/attoxp-scalar-v1/decrement-41.atto --run 41 --clang "${ORE_CLANG}")
        oreslang_contract_test(attoxp_scalar_guest_reset0 ../../contracts/attoxp-scalar-v1/reset-zero.atto --run 0 --clang "${ORE_CLANG}")
        oreslang_contract_test(attoxp_scalar_guest_multiply42 ../../contracts/attoxp-scalar-v1/multiply-42.atto --run 42 --clang "${ORE_CLANG}")

        oreslang_contract_test(native_exec_forward_call forward-call-main.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_arithmetic return-arithmetic.ores --run 28 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_owned_stack_class owned-stack-class.ores --run 13 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_negative negative-literal.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_lexical_local owned-lexical-main.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_owned_identifier owned-identifier-main.ores --no-native-abi --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_owned_region owned-native-region-main.ores --experimental-owned-int --run 42 --clang "${ORE_CLANG}" --native-runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c")
        oreslang_contract_test(native_exec_owned_region_nested owned-native-region-call.ores --experimental-owned-int --run 42 --clang "${ORE_CLANG}" --native-runtime "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c")
        oreslang_contract_test(native_exec_lexical_shared_loan owned-local-shared-borrow.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_lexical_mutable_loan owned-local-mutable-borrow.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_rt_copy_scalar native-rt-copy-scalar.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_rt_take_scalar native-rt-take-scalar.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_rt_share_scalar native-rt-share-scalar.ores --run 42 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_loan_read native-borrow-read.ores --run 83 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_loan_exclusive native-borrow-exclusive.ores --run 40 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_reborrow_shared native-reborrow-shared.ores --run 81 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_reborrow_mut native-reborrow-mut.ores --run 40 --clang "${ORE_CLANG}")
        oreslang_contract_test(native_exec_reborrow_mut_to_shared native-reborrow-mut-shared.ores --run 41 --clang "${ORE_CLANG}")
    else()
        message(WARNING "Clang unavailable: native executable tests are disabled")
    endif()

    # Inert native POD allocator: deterministic ownership without tracing GC.
    # The test does not establish compiler-enforced object lifetime parity.
    # Bounded native region with provenance-checked deterministic scalar drops.
    add_executable(oreslang-native-owned-i64-region-test
        tests/native_owned_region_test.c
        src/main/cpp/oreslang/runtime/NativeOwnedI64Region.c)
    target_include_directories(oreslang-native-owned-i64-region-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-owned-i64-region-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_owned_i64_region_drop_and_provenance
        COMMAND oreslang-native-owned-i64-region-test)

    # Host-only deterministic actor arena; no source actor lowering implied.
    add_executable(oreslang-native-actor-arena-test
        tests/native_actor_arena_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-actor-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-arena-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-arena-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_arena_persistent_scratch
        COMMAND oreslang-native-actor-arena-test)
    set_tests_properties(native_actor_arena_persistent_scratch PROPERTIES TIMEOUT 25)

    # ActorRegionV1 native host evidence at both optimization levels.
    # Optional sanitizers instrument the real allocator and trusted bridge;
    # this is still host-only and not a compiler-generated guest receipt.
    option(ORESLANG_ACTOR_REGION_SANITIZERS
        "Enable ASan + UBSan for ActorRegionV1 native host tests" OFF)
    function(add_actor_region_native_host_test kind level)
        if(kind STREQUAL "allocator")
            set(test_file tests/actor_region_v1_test.c)
            set(runtime_sources
                src/main/cpp/oreslang/runtime/ActorRegionV1.c
                src/main/cpp/oreslang/runtime/ActorArena.c)
        elseif(kind STREQUAL "guest-bridge")
            set(test_file tests/actor_region_guest_v1_bridge_test.c)
            set(runtime_sources
                src/main/cpp/oreslang/runtime/ActorRegionGuestV1.c
                src/main/cpp/oreslang/runtime/ActorRegionV1.c
                src/main/cpp/oreslang/runtime/ActorArena.c)
        elseif(kind STREQUAL "supervisor")
            if(NOT UNIX)
                return()
            endif()
            set(test_file tests/actor_region_v1_fault_supervisor_test.c)
            set(runtime_sources
                src/main/cpp/oreslang/runtime/ActorRegionV1.c
                src/main/cpp/oreslang/runtime/ActorArena.c)
        else()
            message(FATAL_ERROR "unknown ActorRegionV1 host test kind: ${kind}")
        endif()
        set(target "oreslang-actor-region-${kind}-O${level}")
        add_executable(${target} ${test_file} ${runtime_sources})
        target_include_directories(${target} PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(${target} PRIVATE c_std_11)
        if(MSVC)
            if(level STREQUAL "0")
                target_compile_options(${target} PRIVATE /W4 /UNDEBUG /Od)
            else()
                target_compile_options(${target} PRIVATE /W4 /UNDEBUG /O2)
            endif()
            if(ORESLANG_ACTOR_REGION_SANITIZERS)
                message(FATAL_ERROR "ActorRegionV1 ASan/UBSan host tests require GCC or Clang")
            endif()
        else()
            target_compile_options(${target} PRIVATE
                -Wall -Wextra -Wpedantic -Werror -UNDEBUG "-O${level}")
            if(ORESLANG_ACTOR_REGION_SANITIZERS)
                target_compile_options(${target} PRIVATE
                    -fsanitize=address,undefined -fno-omit-frame-pointer)
                target_link_options(${target} PRIVATE -fsanitize=address,undefined)
            endif()
        endif()
        set(test_name "native_actor_region_v1_${kind}_O${level}_host_only")
        add_test(NAME ${test_name} COMMAND ${target})
        set_tests_properties(${test_name} PROPERTIES TIMEOUT 45)
    endfunction()
    foreach(level IN ITEMS 0 2)
        add_actor_region_native_host_test(allocator ${level})
        add_actor_region_native_host_test(guest-bridge ${level})
        add_actor_region_native_host_test(supervisor ${level})
    endforeach()

    # Compiler-generated LLVM IR proof. This does NOT link or execute a guest;
    # compile-aot.sh still refuses the unchecked ActorRegion native closure.
    add_test(NAME actor_region_v1_compiler_generated_ir_and_negative_admission
        COMMAND "${CMAKE_COMMAND}"
            "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/actor_region_v1_compiler_lowering_v1.cmake")
    set_tests_properties(actor_region_v1_compiler_generated_ir_and_negative_admission
        PROPERTIES TIMEOUT 45)

    # Host-only compiler-ready 32-bit actor-region adapter, built against
    # the real ActorArena.c; it is NOT native guest .atto codegen evidence.
    add_executable(oreslang-native-actor-region-v1-test
        tests/actor_region_v1_test.c
        src/main/cpp/oreslang/runtime/ActorRegionV1.c
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-actor-region-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-region-v1-test PRIVATE c_std_11)
    target_compile_options(oreslang-native-actor-region-v1-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_region_v1_offsets_generation_drop
        COMMAND oreslang-native-actor-region-v1-test)
    set_tests_properties(native_actor_region_v1_offsets_generation_drop
        PROPERTIES TIMEOUT 30)

    # A child-process worker may abort on an illegal region operation;
    # the supervising host process MUST remain alive and start a new worker.
    # This does not implement the production sandbox or source compiler ABI.
    if(UNIX)
        add_executable(oreslang-native-actor-region-supervisor-fault-test
            tests/actor_region_v1_fault_supervisor_test.c
            src/main/cpp/oreslang/runtime/ActorRegionV1.c
            src/main/cpp/oreslang/runtime/ActorArena.c)
        target_include_directories(oreslang-native-actor-region-supervisor-fault-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-native-actor-region-supervisor-fault-test PRIVATE c_std_11)
        target_link_libraries(oreslang-native-actor-region-supervisor-fault-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-native-actor-region-supervisor-fault-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
        add_test(NAME native_actor_region_v1_fault_isolation_host_only
            COMMAND oreslang-native-actor-region-supervisor-fault-test)
        set_tests_properties(native_actor_region_v1_fault_isolation_host_only
            PROPERTIES TIMEOUT 45)
    endif()

    # Real checked .atto source -> validated LLVM function IR -> native Clang
    # executable linked with ActorRegionV1 and the production ActorArena.c.
    # This is a narrow compiler-backed guest slice; not full general AOT.
    add_test(NAME native_actor_region_v1_compiled_guest_aot
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            "-DCLANG=${ORE_CLANG}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/actor_region_v1_compiled_guest.cmake")
    set_tests_properties(native_actor_region_v1_compiled_guest_aot
        PROPERTIES TIMEOUT 240)

    # Two checked class owners and a genuine guarded early return converge
    # at ONE LLVM-generated drop block. Host runtime from #1599 is unchanged.
    # This verifies compiler-backed native guest execution, not full GC proof.
        add_executable(oreslang-ownership-dataflow-v1-test
        tests/ownership_dataflow_v1_test.cpp)
    target_include_directories(oreslang-ownership-dataflow-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-ownership-dataflow-v1-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-ownership-dataflow-v1-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_executable(oreslang-ownership-shadow-v0-test
        tests/ownership_shadow_v0_test.cpp)
    target_include_directories(oreslang-ownership-shadow-v0-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-ownership-shadow-v0-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-ownership-shadow-v0-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ownership_shadow_v0
        COMMAND oreslang-ownership-shadow-v0-test)
    set_tests_properties(native_ownership_shadow_v0 PROPERTIES TIMEOUT 30)
    add_executable(oreslang-ownership-source-lowering-v1-test
        tests/ownership_source_lowering_v1_test.cpp)
    target_include_directories(oreslang-ownership-source-lowering-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-ownership-source-lowering-v1-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-ownership-source-lowering-v1-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ownership_source_lowering_v1
        COMMAND oreslang-ownership-source-lowering-v1-test)
    set_tests_properties(native_ownership_source_lowering_v1 PROPERTIES TIMEOUT 30)

    add_test(NAME native_ownership_dataflow_v1
        COMMAND oreslang-ownership-dataflow-v1-test)
    set_tests_properties(native_ownership_dataflow_v1 PROPERTIES TIMEOUT 30)
    # Check the test dispatcher itself: a deliberately failing middle case
    # must not prevent a later named case from executing.
    add_test(NAME native_ownership_dataflow_v1_runner_continuation
        COMMAND oreslang-ownership-dataflow-v1-test --self-check-runner)
    set_tests_properties(native_ownership_dataflow_v1_runner_continuation
        PROPERTIES TIMEOUT 30)

    add_test(NAME native_actor_region_v2_multiowner_guarded_cleanup
        COMMAND "${CMAKE_COMMAND}"
            "-DROOT=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DBUILD=${CMAKE_CURRENT_BINARY_DIR}"
            "-DCLANG=${ORE_CLANG}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/actor_region_v2_multiowner_guest.cmake")
    set_tests_properties(native_actor_region_v2_multiowner_guarded_cleanup
        PROPERTIES TIMEOUT 240)

    # Security regression: reject actor-private/control-buffer overlap and
    # overflowing host spans before any memcpy. POSIX fork oracle verifies abort.
    if(UNIX)
        add_executable(oreslang-actor-arena-host-buffer-boundary-test
            tests/actor_arena_host_buffer_boundary_test.c
            src/main/cpp/oreslang/runtime/ActorArena.c)
        target_include_directories(oreslang-actor-arena-host-buffer-boundary-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        set_target_properties(oreslang-actor-arena-host-buffer-boundary-test PROPERTIES
            C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
        target_compile_options(oreslang-actor-arena-host-buffer-boundary-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_actor_arena_host_buffer_boundary
            COMMAND oreslang-actor-arena-host-buffer-boundary-test)
        set_tests_properties(native_actor_arena_host_buffer_boundary PROPERTIES TIMEOUT 20)
    endif()

    # Trusted host-only shared-region capability and read/write lease prototype.
    add_executable(oreslang-native-shared-domain-arena-test
        tests/native_shared_domain_arena_test.cpp)
    target_include_directories(oreslang-native-shared-domain-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-shared-domain-arena-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-shared-domain-arena-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-shared-domain-arena-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_shared_domain_arena_leases
        COMMAND oreslang-native-shared-domain-arena-test)
    set_tests_properties(native_shared_domain_arena_leases PROPERTIES TIMEOUT 25)

    # Persist actor-native data across message turns and carrier migration.
    # Retirement denies new admissions; physical free waits for active loans.
    find_package(Threads REQUIRED)
    # Host-only native access evidence, not a language-wide race-proof.
    add_executable(oreslang-actor-heap-access-trace-test
        tests/actor_heap_access_trace_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
    set_target_properties(oreslang-actor-heap-access-trace-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-actor-heap-access-trace-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-heap-access-trace-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_i64_access_evidence_host_only
        COMMAND oreslang-actor-heap-access-trace-test)
    set_tests_properties(native_actor_i64_access_evidence_host_only
        PROPERTIES TIMEOUT 20)

    add_executable(oreslang-actor-owned-i64-heap-test
        tests/actor_owned_heap_lifecycle_test.cpp
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
    target_include_directories(oreslang-actor-owned-i64-heap-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-actor-owned-i64-heap-test PRIVATE Threads::Threads)
    # The test intercepts the runtime object's calloc/free with linker --wrap (GNU ld/lld only).
    if(APPLE)
        target_compile_definitions(oreslang-actor-owned-i64-heap-test PRIVATE ORES_HEAP_NO_LINK_WRAP=1)
    else()
        target_link_options(oreslang-actor-owned-i64-heap-test PRIVATE -Wl,--wrap=calloc -Wl,--wrap=free)
    endif()
    target_compile_options(oreslang-actor-owned-i64-heap-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_owned_i64_heap_loan_quiescence
        COMMAND oreslang-actor-owned-i64-heap-test)
    set_tests_properties(native_actor_owned_i64_heap_loan_quiescence PROPERTIES TIMEOUT 20)

    # Host/runtime substrate only: real per-actor i64 slots, read loan and
    # physical retirement. This does NOT prove compiled guest tensor lowering.
    add_executable(oreslang-actor-tensor-i64-test
        tests/actor_tensor_i64_heap_test.c
        src/main/cpp/oreslang/runtime/ActorTensorI64.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c)
    set_target_properties(oreslang-actor-tensor-i64-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-actor-tensor-i64-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-tensor-i64-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_tensor_i64_heap_host_only
        COMMAND oreslang-actor-tensor-i64-test)
    set_tests_properties(native_actor_tensor_i64_heap_host_only PROPERTIES TIMEOUT 20)

    add_executable(oreslang-fixed-arena-test tests/fixed_arena_test.cpp)
    target_include_directories(oreslang-fixed-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-fixed-arena-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_fixed_arena_contract COMMAND oreslang-fixed-arena-test)

    add_executable(oreslang-deterministic-graph-arena-test tests/deterministic_graph_arena_test.cpp)
    target_include_directories(oreslang-deterministic-graph-arena-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-deterministic-graph-arena-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_deterministic_graph_arena COMMAND oreslang-deterministic-graph-arena-test)
    # Independent actor-owned native heap tests are portable (no Linux syscalls).
    find_package(Threads REQUIRED)
    add_executable(oreslang-actor-heap-test tests/actor_heap_test.cpp)
    target_include_directories(oreslang-actor-heap-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-actor-heap-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-actor-heap-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_heap_no_tracing_gc
             COMMAND oreslang-actor-heap-test)

    # The gate itself must remain live until every frame/FFI lease drops.
    # A dangling lease used to silently address destroyed gate storage.
    add_executable(oreslang-actor-heap-borrow-gate-lifetime-test
        tests/actor_heap_borrow_gate_lifetime_test.cpp)
    target_include_directories(oreslang-actor-heap-borrow-gate-lifetime-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-actor-heap-borrow-gate-lifetime-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_heap_gate_live_lease_drop_fence
        COMMAND oreslang-actor-heap-borrow-gate-lifetime-test)
    set_tests_properties(native_actor_heap_gate_live_lease_drop_fence
        PROPERTIES TIMEOUT 30)

    # Linux-specific actor-neutral wake/park primitive. This does NOT
    # implement the actor scheduler or certify lock-free mailbox delivery.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        find_package(Threads REQUIRED)
        # Real joined carrier is the sole issuer of retirement proofs.
        add_executable(oreslang-actor-graph-heap-test
            tests/actor_owned_graph_heap_test.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-actor-graph-heap-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-actor-graph-heap-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-actor-graph-heap-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_actor_owned_cyclic_graph_heap
            COMMAND oreslang-actor-graph-heap-test)
        set_tests_properties(native_actor_owned_cyclic_graph_heap PROPERTIES TIMEOUT 15)

        add_executable(oreslang-parking-signal-test
            tests/parking_signal_test.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-parking-signal-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-parking-signal-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-parking-signal-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME parking_signal_wake_cancel_races COMMAND oreslang-parking-signal-test)
        set_tests_properties(parking_signal_wake_cancel_races PROPERTIES TIMEOUT 25)

        add_executable(oreslang-hungry-carrier-test
            tests/hungry_carrier_test.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-hungry-carrier-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-hungry-carrier-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-hungry-carrier-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME hungry_carrier_async_bounded_cancel
                 COMMAND oreslang-hungry-carrier-test)
        set_tests_properties(hungry_carrier_async_bounded_cancel PROPERTIES TIMEOUT 30)

        # Actual compiled Oreslang scalar frame is driven by a real one-actor
        # HungryCarrier OS thread. CPU cooperation is NOT IO or logical DONE.
        # The build compiler may be GCC, which cannot compile LLVM IR.
        find_program(ORE_CLANGXX NAMES clang++)
        if(ORE_CLANG AND ORE_CLANGXX)
            add_test(NAME native_rt_compiled_scalar_carrier_cooperate
                COMMAND "${Python3_EXECUTABLE}"
                    "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_carrier_aot.py"
                    --compiler "$<TARGET_FILE:oreslang-llvmc>"
                    --clang "${ORE_CLANG}"
                    --clangxx "${ORE_CLANGXX}"
                    --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-rt-scalar-step-positive.ores"
                    --test "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_rt_scalar_carrier_aot_test.cpp"
                    --source-root "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
            set_tests_properties(native_rt_compiled_scalar_carrier_cooperate
                PROPERTIES TIMEOUT 120)
        endif()

        # Experimental multiple actors on ONE OS worker, using two
        # independently compiled scalar .ores continuations. Not production
        # source actor lowering and not a hard realtime guarantee.
        if(ORE_CLANG AND ORE_CLANGXX)
            add_test(NAME native_multi_actor_compiled_round_robin_fairness
                COMMAND "${Python3_EXECUTABLE}"
                    "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_multi_actor_carrier_aot.py"
                    --compiler "$<TARGET_FILE:oreslang-llvmc>"
                    --clangxx "${ORE_CLANGXX}"
                    --fast "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-rt-scalar-step-positive.ores"
                    --slow "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-rt-scalar-multi-actor-slow.ores"
                    --test "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_multi_actor_carrier_aot_test.cpp"
                    --source-root "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
            set_tests_properties(native_multi_actor_compiled_round_robin_fairness
                PROPERTIES TIMEOUT 150)
        endif()

        add_executable(oreslang-carrier-supervisor-test
            tests/native_carrier_supervisor_integration.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-carrier-supervisor-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-carrier-supervisor-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-carrier-supervisor-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_carrier_urgent_supervisor_turn
                 COMMAND oreslang-carrier-supervisor-test)
        set_tests_properties(native_carrier_urgent_supervisor_turn PROPERTIES TIMEOUT 15)

        # Real native carrier admissions and handler observations are used to
        # produce an executable trace (not fabricated actor-model fixtures).
        add_executable(oreslang-actor-trace-test
            tests/actor_trace_integration.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-actor-trace-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-actor-trace-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-actor-trace-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_actor_ingress_handler_trace
                 COMMAND oreslang-actor-trace-test)
        set_tests_properties(native_actor_ingress_handler_trace PROPERTIES TIMEOUT 15)

        # Actual dedicated carrier callback accesses its own native ownership
        # heap, while the broker and supervisor cannot discover that heap.
        add_executable(oreslang-native-actor-heap-test
            tests/native_actor_heap_integration_test.cpp
            src/main/cpp/oreslang/runtime/HungryCarrier.cpp
            src/main/cpp/oreslang/runtime/ParkingSignal.cpp)
        target_include_directories(oreslang-native-actor-heap-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-actor-heap-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-native-actor-heap-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_actor_heap_isolation_and_drop
                 COMMAND oreslang-native-actor-heap-test)
        set_tests_properties(native_actor_heap_isolation_and_drop PROPERTIES TIMEOUT 30)
    endif()
    # Current-main reconciliation of the native actor substrate from draft
    # LLVM #118. Never replace the newer main ownership/continuation tests.
    # This admits host-level actor scheduling and reserved CONTROL only;
    # source-level spawn/await lowering remains a separate compiler gate.
    find_package(Threads REQUIRED)

    add_executable(oreslang-native-actor-mailbox-test
        tests/native_actor_mailbox_test.c
        src/main/cpp/oreslang/runtime/NativeActorMailbox.c)
    target_include_directories(oreslang-native-actor-mailbox-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-mailbox-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-mailbox-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_fifo_mailbox_admission
        COMMAND oreslang-native-actor-mailbox-test)
    set_tests_properties(native_actor_fifo_mailbox_admission PROPERTIES TIMEOUT 20)

    # Zero-thread central CONTROL wake coordinator on the four-domain VM.
    add_executable(oreslang-native-vm-control-plane-test
        tests/native_vm_control_plane_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-control-plane-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-control-plane-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-control-plane-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_vm_master_control_wake_coalescing
        COMMAND oreslang-native-vm-control-plane-test)
    set_tests_properties(native_vm_master_control_wake_coalescing PROPERTIES TIMEOUT 25)

    # Opt-in native main CONTROL service prestarted before source guest work.
    # No actor callbacks on this thread; actor carriers remain lazy.
    add_executable(oreslang-native-vm-main-control-loop-test
        tests/native_vm_main_control_loop_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-main-control-loop-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-main-control-loop-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-main-control-loop-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_vm_main_control_bootstrap_and_shutdown
        COMMAND oreslang-native-vm-main-control-loop-test)
    set_tests_properties(native_vm_main_control_bootstrap_and_shutdown
        PROPERTIES TIMEOUT 25)

    # Linux exact-runtime pthread-create failure injection, not a mock VM.
    # GNU ld/lld --wrap makes the first CONTROL pthread creation return EAGAIN.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux"
            AND CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        add_executable(oreslang-native-vm-main-control-create-failure-test
            tests/native_vm_main_control_create_failure_test.c
            src/main/cpp/oreslang/runtime/NativeActorFuture.c
            src/main/cpp/oreslang/runtime/NativeOresVM.c
            src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
        target_include_directories(oreslang-native-vm-main-control-create-failure-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-vm-main-control-create-failure-test
            PRIVATE Threads::Threads)
        target_link_options(oreslang-native-vm-main-control-create-failure-test
            PRIVATE "LINKER:--wrap=pthread_create")
        target_compile_options(oreslang-native-vm-main-control-create-failure-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror)
        add_test(NAME native_vm_main_control_pthread_failure_retry
            COMMAND oreslang-native-vm-main-control-create-failure-test)
        set_tests_properties(native_vm_main_control_pthread_failure_retry
            PROPERTIES TIMEOUT 25)
    endif()

    # VM-level ownership: four independent scheduler domains, five carriers
    # by default; cross-domain ActorRefs cannot be admitted by another pool.
    add_executable(oreslang-native-ores-vm-test
        tests/native_ores_vm_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-ores-vm-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-ores-vm-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-ores-vm-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ores_vm_four_carrier_domains
        COMMAND oreslang-native-ores-vm-test)
    set_tests_properties(native_ores_vm_four_carrier_domains PROPERTIES TIMEOUT 30)
    # Actual native VM DATA/CONTROL callbacks bridged to tenant actor-private
    # host arenas. This does not execute compiled .ores guests or establish
    # an OS tenant sandbox or hard-real-time admission.
    add_executable(oreslang-native-tenant-vm-callback-bridge-test
        tests/native_tenant_vm_callback_bridge_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-tenant-vm-callback-bridge-test
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-tenant-vm-callback-bridge-test
        PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-tenant-vm-callback-bridge-test PRIVATE
        -UNDEBUG -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_tenant_vm_callback_host_bridge
        COMMAND oreslang-native-tenant-vm-callback-bridge-test)
    set_tests_properties(native_tenant_vm_callback_host_bridge
        PROPERTIES TIMEOUT 30)
    # Native scheduler cache-locality hint: bounded same-carrier reuse is
    # preempted by CONTROL and yields to competing actor lanes.
    add_executable(oreslang-native-actor-carrier-locality-test
        tests/native_actor_carrier_locality_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-carrier-locality-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-carrier-locality-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-carrier-locality-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_carrier_locality_control_priority
        COMMAND oreslang-native-actor-carrier-locality-test)
    set_tests_properties(native_actor_carrier_locality_control_priority
        PROPERTIES TIMEOUT 30)

    # Opt-in budget-aware sizing; legacy VM defaults remain unchanged.
    add_executable(oreslang-native-vm-cpu-budget-test
        tests/native_vm_cpu_budget_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-cpu-budget-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-cpu-budget-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-cpu-budget-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_vm_cgroup_cpu_budgeted_carrier_admission
        COMMAND oreslang-native-vm-cpu-budget-test)
    set_tests_properties(native_vm_cgroup_cpu_budgeted_carrier_admission
        PROPERTIES TIMEOUT 30)
    # One physical turn permit for all native scheduler domains; idle
    # carriers do not execute guest callbacks while another owns the permit.
    add_executable(oreslang-native-vm-turn-gate-test
        tests/native_vm_physical_turn_gate_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-turn-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-turn-gate-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-turn-gate-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_vm_cross_domain_physical_turn_limit
        COMMAND oreslang-native-vm-turn-gate-test)
    set_tests_properties(native_vm_cross_domain_physical_turn_limit
        PROPERTIES TIMEOUT 30)

    # Deterministic, isolated C11 physical permit contention test;
    # no guest compiler lowering or NUMA policy implied.
    add_executable(oreslang-native-physical-turn-gate-stress-test
        tests/native_physical_turn_gate_stress_test.c)
    target_include_directories(oreslang-native-physical-turn-gate-stress-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-physical-turn-gate-stress-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-physical-turn-gate-stress-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_shared_actor_turn_gate_pthread_contention
        COMMAND oreslang-native-physical-turn-gate-stress-test)
    set_tests_properties(native_shared_actor_turn_gate_pthread_contention
        PROPERTIES TIMEOUT 30)

    # Local atomics/quotas stress does not require the guest compiler or VM.
    add_executable(oreslang-native-shard-budget-stress-test
        tests/native_actor_shard_budget_stress_test.cpp)
    target_include_directories(oreslang-native-shard-budget-stress-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-shard-budget-stress-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-shard-budget-stress-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_and_vm_shard_budget_contention
        COMMAND oreslang-native-shard-budget-stress-test)
    set_tests_properties(native_actor_and_vm_shard_budget_contention
        PROPERTIES TIMEOUT 30)

    # Standalone exact header CTest; its mock scheduler certifies one
    # physical actor turn, while reservations use the real shard code.
    add_executable(oreslang-native-actor-shard-snapshot-test
        tests/native_actor_shard_snapshot_unit_test.cpp)
    target_include_directories(oreslang-native-actor-shard-snapshot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-shard-snapshot-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-shard-snapshot-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_shard_snapshot_quota_and_generation
        COMMAND oreslang-native-actor-shard-snapshot-test)
    set_tests_properties(native_actor_shard_snapshot_quota_and_generation
        PROPERTIES TIMEOUT 30)

    # Host-only trusted actor-owned AoS/SoA/AoSoA scalar region.
    # The actor scheduler certifies owner identity; no source-level lowering.
    add_executable(oreslang-native-actor-shard-layout-test
        tests/native_actor_shard_layout_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-shard-layout-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-shard-layout-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-shard-layout-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_owned_aos_soa_aosoa_dense_kernel
        COMMAND oreslang-native-actor-shard-layout-test)
    set_tests_properties(native_actor_owned_aos_soa_aosoa_dense_kernel
        PROPERTIES TIMEOUT 30)
    # Manual performance benchmark; do not gate correctness on wall times.
    add_executable(oreslang-native-actor-shard-bench
        tests/native_actor_shard_layout_bench.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-shard-bench PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-shard-bench PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-shard-bench PRIVATE
        -Wall -Wextra -Wpedantic -Werror)

    # VM-owned exact parent binding -> quiescent typed CONTROL publication.
    # No guest supervisor compiler lowering is claimed by this host C test.
    add_executable(oreslang-native-vm-supervisor-test
        tests/native_vm_supervisor_owner_test.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-supervisor-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-supervisor-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-supervisor-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_vm_supervisor_owner_typed_quiescent_control
        COMMAND oreslang-native-vm-supervisor-test)
    set_tests_properties(native_vm_supervisor_owner_typed_quiescent_control
        PROPERTIES TIMEOUT 30)
    # Host/supervisor execution lease transitions: linearizable carrier claim,
    # stop/retirement quiescence, stale resume and generation exhaustion.
    add_executable(oreslang-native-execution-authority-test
        tests/native_execution_authority_test.c)
    target_include_directories(oreslang-native-execution-authority-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-execution-authority-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-execution-authority-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_supervisor_execution_authority
        COMMAND oreslang-native-execution-authority-test)
    set_tests_properties(native_actor_supervisor_execution_authority PROPERTIES TIMEOUT 30)
    # Trusted native authority adversarial lifecycle test: a foreign physical
    # carrier cannot steal an execution claim, retirement fences active work,
    # and stale generation claims fail after restart. Does not execute guest code.
    add_executable(oreslang-native-actor-authority-stop-claim-race-test
        tests/native_actor_authority_stop_claim_race_test.c)
    target_include_directories(oreslang-native-actor-authority-stop-claim-race-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-authority-stop-claim-race-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-authority-stop-claim-race-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_authority_stop_claim_race
        COMMAND oreslang-native-actor-authority-stop-claim-race-test)
    set_tests_properties(native_actor_authority_stop_claim_race PROPERTIES TIMEOUT 30)


    # Across all actor-owned entrypoints (DATA and supervisor CONTROL), no
    # actor can have two physical guest executors, even on separate workers.
    # A typed supervisor panic is actor-owned CONTROL work and must obey
    # the same exclusive carrier admission as normal DATA for that parent.
    add_executable(oreslang-native-actor-typed-control-exclusive-test
        tests/native_actor_typed_control_exclusive_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-typed-control-exclusive-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-typed-control-exclusive-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-typed-control-exclusive-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_typed_panic_exclusive_carrier
        COMMAND oreslang-native-actor-typed-control-exclusive-test)
    set_tests_properties(native_actor_typed_panic_exclusive_carrier PROPERTIES TIMEOUT 20)

    # Real scheduler: reserved per-child terminal receipts drain under USER saturation.
    add_executable(oreslang-native-supervisor-control-overflow-test
        tests/native_supervisor_control_overflow_adversarial_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-supervisor-control-overflow-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-supervisor-control-overflow-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-supervisor-control-overflow-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_supervisor_control_overflow_lossless
        COMMAND oreslang-native-supervisor-control-overflow-test)
    set_tests_properties(native_supervisor_control_overflow_lossless PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-actor-exclusive-control-test
        tests/native_actor_exclusive_control_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-exclusive-control-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-exclusive-control-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-exclusive-control-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_exclusive_data_and_control
        COMMAND oreslang-native-actor-exclusive-control-test)
    set_tests_properties(native_actor_exclusive_data_and_control PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-actor-ready-queue-test
        tests/native_actor_scheduler_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-ready-queue-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-ready-queue-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-ready-queue-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_nonblocking_ready_queue
        COMMAND oreslang-native-actor-ready-queue-test)
    set_tests_properties(native_actor_nonblocking_ready_queue PROPERTIES TIMEOUT 30)
    # Native scheduler handler-originated sealed STOP: a running callback
    # must not self-wait, seal must abandon queued DATA exactly once, and a
    # host observer must see the correct terminal receipt after quiescence.
    # This is a native host test, not a compiled .ores guest test.
    add_executable(oreslang-native-actor-self-sealed-stop-test
        tests/native_actor_self_sealed_stop_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-self-sealed-stop-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-self-sealed-stop-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-self-sealed-stop-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_self_sealed_stop_terminal_accounting
        COMMAND oreslang-native-actor-self-sealed-stop-test)
    set_tests_properties(native_actor_self_sealed_stop_terminal_accounting
        PROPERTIES TIMEOUT 30)

    # O(1) intrusive ready queues, idle spin and CPU affinity. Every queue
    # mutation is cross-checked against an O(N) scan of lane state.
    add_executable(oreslang-native-actor-ready-queue-stress-test
        tests/native_actor_ready_queue_stress_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-ready-queue-stress-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-ready-queue-stress-test PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-actor-ready-queue-stress-test PRIVATE
        ORESLANG_NATIVE_SCHED_VALIDATE=1)
    target_compile_options(oreslang-native-actor-ready-queue-stress-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_actor_ready_queue_stress
        COMMAND oreslang-native-actor-ready-queue-stress-test)
    set_tests_properties(native_actor_ready_queue_stress PROPERTIES TIMEOUT 60)
    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        add_executable(oreslang-native-actor-ready-queue-stress-tsan-test
            tests/native_actor_ready_queue_stress_test.c
            src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
        target_include_directories(oreslang-native-actor-ready-queue-stress-tsan-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-actor-ready-queue-stress-tsan-test PRIVATE
            Threads::Threads)
        target_compile_definitions(oreslang-native-actor-ready-queue-stress-tsan-test PRIVATE
            ORESLANG_NATIVE_SCHED_VALIDATE=1)
        target_compile_options(oreslang-native-actor-ready-queue-stress-tsan-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG
            -fsanitize=thread -fno-omit-frame-pointer)
        target_link_options(oreslang-native-actor-ready-queue-stress-tsan-test PRIVATE
            -fsanitize=thread -fno-omit-frame-pointer)
        add_test(NAME native_actor_ready_queue_stress_tsan
            COMMAND oreslang-native-actor-ready-queue-stress-tsan-test)
        set_tests_properties(native_actor_ready_queue_stress_tsan PROPERTIES TIMEOUT 120)
    endif()
    # Scheduler latency vs. kernel (condvar) and hardware (spin) floors. CTest
    # runs a --smoke pass only; run the binary directly for real numbers.
    # Dedicated per-core RT domain: static placement, SPSC ring matrix, no
    # scheduler lock. Contract, drain, lost-wakeup probe and stress.
    add_executable(oreslang-native-rt-domain-test
        tests/native_rt_domain_test.c
        src/main/cpp/oreslang/runtime/NativeRtDomain.c)
    target_include_directories(oreslang-native-rt-domain-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-rt-domain-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-rt-domain-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_rt_domain COMMAND oreslang-native-rt-domain-test)
    set_tests_properties(native_rt_domain PROPERTIES TIMEOUT 60)
    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        add_executable(oreslang-native-rt-domain-tsan-test
            tests/native_rt_domain_test.c
            src/main/cpp/oreslang/runtime/NativeRtDomain.c)
        target_include_directories(oreslang-native-rt-domain-tsan-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(oreslang-native-rt-domain-tsan-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-native-rt-domain-tsan-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG
            -fsanitize=thread -fno-omit-frame-pointer)
        target_link_options(oreslang-native-rt-domain-tsan-test PRIVATE
            -fsanitize=thread -fno-omit-frame-pointer)
        add_test(NAME native_rt_domain_tsan COMMAND oreslang-native-rt-domain-tsan-test)
        set_tests_properties(native_rt_domain_tsan PROPERTIES TIMEOUT 180)
    endif()
    add_executable(oreslang-native-actor-scheduler-latency-bench
        tests/native_actor_scheduler_latency_bench.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeRtDomain.c)
    target_include_directories(oreslang-native-actor-scheduler-latency-bench PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-scheduler-latency-bench PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-actor-scheduler-latency-bench PRIVATE
        ORESLANG_BENCH_HAS_TUNING=1 ORESLANG_BENCH_HAS_RT_DOMAIN=1)
    target_compile_options(oreslang-native-actor-scheduler-latency-bench PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_scheduler_latency_bench_smoke
        COMMAND oreslang-native-actor-scheduler-latency-bench --smoke)
    set_tests_properties(native_actor_scheduler_latency_bench_smoke PROPERTIES TIMEOUT 60)
    # Scheduler-owned producer continuation wake escrow: independent from
    # supervisor CONTROL and USER mail capacity. Native host substrate only.
    add_executable(oreslang-native-continuation-wake-escrow-test
        tests/native_continuation_wake_escrow_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-continuation-wake-escrow-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-continuation-wake-escrow-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-continuation-wake-escrow-test PRIVATE
        Threads::Threads)
    add_test(NAME native_continuation_wake_escrow_stop_cancel_aba
        COMMAND oreslang-native-continuation-wake-escrow-test)
    set_tests_properties(native_continuation_wake_escrow_stop_cancel_aba
        PROPERTIES TIMEOUT 45)

    # Reservation must fail cleanly if pthread_create cannot allocate a
    # future wake carrier. The macro is absent from production builds.
    add_executable(oreslang-native-wake-carrier-failure-test
        tests/native_continuation_wake_carrier_failure_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-wake-carrier-failure-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-wake-carrier-failure-test PRIVATE
        ORESLANG_NATIVE_TEST_PTHREAD_CREATE_FAILURE=1
        ORESLANG_NATIVE_TEST_EXECUTION_EXHAUSTION=1)
    target_compile_options(oreslang-native-wake-carrier-failure-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(oreslang-native-wake-carrier-failure-test PRIVATE
        Threads::Threads)
    add_test(NAME native_continuation_wake_carrier_failure_fence
        COMMAND oreslang-native-wake-carrier-failure-test)
    set_tests_properties(native_continuation_wake_carrier_failure_fence
        PROPERTIES TIMEOUT 30)

    # Fault injection is compiled only into this test executable. A blocked
    # actor must not make a different actor's unserviceable work "ACCEPTED".
    add_executable(oreslang-native-carrier-create-failure-test
        tests/native_actor_carrier_create_failure_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-carrier-create-failure-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-carrier-create-failure-test PRIVATE
        ORESLANG_NATIVE_TEST_PTHREAD_CREATE_FAILURE=1)
    target_link_libraries(oreslang-native-carrier-create-failure-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-carrier-create-failure-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_lazy_carrier_creation_failure_fence
        COMMAND oreslang-native-carrier-create-failure-test)
    set_tests_properties(native_actor_lazy_carrier_creation_failure_fence PROPERTIES TIMEOUT 30)

    # SCHED_FIFO is a bootstrap-time qualification step. Unprivileged hosts may
    # reject the carrier creation, but no actor work may be admitted first.
    add_executable(oreslang-native-realtime-policy-test
        tests/native_actor_realtime_policy_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-realtime-policy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-realtime-policy-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-realtime-policy-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_realtime_policy_fail_closed
        COMMAND oreslang-native-realtime-policy-test)
    set_tests_properties(native_actor_realtime_policy_fail_closed PROPERTIES TIMEOUT 30)

    add_executable(oreslang-linux-realtime-environment-test
        tests/linux_realtime_environment_test.c
        src/main/cpp/oreslang/runtime/LinuxRealtimeEnvironment.c)
    target_include_directories(oreslang-linux-realtime-environment-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-linux-realtime-environment-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME linux_realtime_memory_lock_fail_closed
        COMMAND oreslang-linux-realtime-environment-test)
    set_tests_properties(linux_realtime_memory_lock_fail_closed PROPERTIES TIMEOUT 20)

    # Send and stop contend on one linearizable actor admission authority.
    add_executable(oreslang-native-send-stop-race-test
        tests/native_actor_send_stop_race_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-send-stop-race-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-send-stop-race-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-send-stop-race-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_send_stop_linearizable_race
        COMMAND oreslang-native-send-stop-race-test)
    set_tests_properties(native_actor_send_stop_linearizable_race PROPERTIES TIMEOUT 30)

    # Integration of the *actual native C scheduler* with the actor-owned C++
    # graph heap under two distinct actor lanes. No source-language actor
    # lowering or guest-state-loan enforcement is claimed by this host test.
    add_executable(oreslang-native-actor-graph-scheduler-bridge-test
        tests/native_actor_scheduler_graph_bridge_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-graph-scheduler-bridge-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-graph-scheduler-bridge-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-native-actor-graph-scheduler-bridge-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_scheduler_graph_heap_host_integration
        COMMAND oreslang-native-actor-graph-scheduler-bridge-test)
    set_tests_properties(native_actor_scheduler_graph_heap_host_integration
        PROPERTIES TIMEOUT 30)

    # ActorRef scheduler domains are capabilities: exhaustion must not wrap
    # and concurrent opens near UINT64_MAX must never issue duplicates.
    add_executable(oreslang-native-domain-exhaustion-test
        tests/native_actor_domain_exhaustion_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-domain-exhaustion-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-domain-exhaustion-test PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-domain-exhaustion-test PRIVATE
        ORESLANG_NATIVE_TEST_DOMAIN_EXHAUSTION=1)
    target_compile_options(oreslang-native-domain-exhaustion-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_domain_exhaustion_no_reuse
        COMMAND oreslang-native-domain-exhaustion-test)
    set_tests_properties(native_actor_domain_exhaustion_no_reuse PROPERTIES TIMEOUT 30)

    # Logical execution ids are reserved at admission. The final UINT64_MAX id
    # may execute exactly once; additional work must fail closed before dispatch.
    add_executable(oreslang-native-execution-context-exhaustion-test
        tests/native_execution_context_exhaustion_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-execution-context-exhaustion-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-execution-context-exhaustion-test PRIVATE Threads::Threads)
    target_compile_definitions(oreslang-native-execution-context-exhaustion-test PRIVATE
        ORESLANG_NATIVE_TEST_EXECUTION_EXHAUSTION=1)
    target_compile_options(oreslang-native-execution-context-exhaustion-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_execution_context_exhaustion_admission
        COMMAND oreslang-native-execution-context-exhaustion-test)
    set_tests_properties(native_execution_context_exhaustion_admission PROPERTIES TIMEOUT 30)

    # Generation-checked AoSoA logical execution/continuation stores. The
    # fixture suspends, destroys the worker C stack, resumes on fresh pthreads,
    # and reconstructs execution + continuation + actor/supervisor ancestry.
    add_executable(oreslang-native-execution-continuation-store-test
        tests/native_execution_continuation_store_test.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-native-execution-continuation-store-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-execution-continuation-store-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-native-execution-continuation-store-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_execution_continuation_aosoa_trace
        COMMAND oreslang-native-execution-continuation-store-test)
    set_tests_properties(native_execution_continuation_aosoa_trace
        PROPERTIES TIMEOUT 30)

    # Stop one child without waiting for an unrelated actor's busy turn.
    add_executable(oreslang-native-child-wait-test
        tests/native_actor_group_wait_done_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-child-wait-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-child-wait-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-child-wait-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actorgroup_per_child_wait_done
        COMMAND oreslang-native-child-wait-test)
    set_tests_properties(native_actorgroup_per_child_wait_done PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-supervisor-control-test
        tests/native_supervisor_control_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c)
    target_include_directories(oreslang-native-supervisor-control-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-supervisor-control-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-supervisor-control-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_supervisor_reserved_control_future
        COMMAND oreslang-native-supervisor-control-test)
    set_tests_properties(native_supervisor_reserved_control_future PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-supervisor-panic-test
        tests/native_supervisor_panic_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-supervisor-panic-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-supervisor-panic-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-supervisor-panic-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_supervisor_typed_panic_event
        COMMAND oreslang-native-supervisor-panic-test)
    set_tests_properties(native_supervisor_typed_panic_event PROPERTIES TIMEOUT 30)
    # Optimistic CAS for logical continuations: state must not be overwritten
    # after stale, foreign, retired, or exhausted version evidence.
    add_executable(oreslang-native-versioned-actor-cell-test
        tests/versioned_actor_cell_test.cpp)
    target_include_directories(oreslang-native-versioned-actor-cell-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-versioned-actor-cell-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-versioned-actor-cell-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_versioned_state_logical_races
        COMMAND oreslang-native-versioned-actor-cell-test)
    set_tests_properties(native_actor_versioned_state_logical_races
        PROPERTIES TIMEOUT 20)
    set_tests_properties(native_actor_state_scope_nested_loan_fencing PROPERTIES TIMEOUT 20)

    # Portable native ML staging boundaries only: the sink is volatile and
    # MPA preflight never authorizes a privileged effect.
    add_executable(oreslang-native-ml-admission-test
        tests/native_ml_admission_test.cpp)
    target_include_directories(oreslang-native-ml-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-ml-admission-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-ml-admission-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_ml_volatile_fence_and_mpa_failclosed
        COMMAND oreslang-native-ml-admission-test)
    set_tests_properties(native_ml_volatile_fence_and_mpa_failclosed PROPERTIES TIMEOUT 20)

    # A real .ores -> LLVM IR -> Clang executable evidence slice, deliberately
    # labeled scalar-only; this is NOT a full actor/HPC/ML parity claim.
    if(ORE_CLANG)
        add_test(NAME native_hpc_ml_scalar_source_provenance
            COMMAND "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_hpc_ml_source_evidence.py"
                --compiler "$<TARGET_FILE:oreslang-llvmc>"
                --clang "${ORE_CLANG}"
                --fixture "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/hpc-ml-scalar-evidence.ores"
                --samples 3 --warmups 1 --expected-exit 42)
        set_tests_properties(native_hpc_ml_scalar_source_provenance PROPERTIES TIMEOUT 60)
    endif()
endif()

if(BUILD_TESTING AND ORE_CLANG)
    add_test(NAME refreshed_i64_module_contract_execution
        COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/module_contract_refresh.py"
        "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}" "${CMAKE_CURRENT_SOURCE_DIR}")
endif()

if(BUILD_TESTING)
    add_executable(oreslang-refreshed-control-data-lane-test tests/native_actor_control_data_lane_test.cpp)
    target_include_directories(oreslang-refreshed-control-data-lane-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-refreshed-control-data-lane-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-refreshed-control-data-lane-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME refreshed_actor_control_data_lane COMMAND oreslang-refreshed-control-data-lane-test)
    set_tests_properties(refreshed_actor_control_data_lane PROPERTIES TIMEOUT 30)

    add_executable(oreslang-test-assertions-test tests/test_assertions_enabled.cpp)
    add_test(NAME native_test_assertions_remain_enabled
        COMMAND oreslang-test-assertions-test)

    # Release tests use assertions for ownership checks and sometimes for
    # operations such as pthread_create. NDEBUG must not erase those operations
    # or turn safety tests into false passes. Keep the compiler's Release flags.
    get_property(oreslang_directory_targets DIRECTORY PROPERTY BUILDSYSTEM_TARGETS)
    foreach(oreslang_test_target IN LISTS oreslang_directory_targets)
        get_target_property(oreslang_target_type ${oreslang_test_target} TYPE)
        if(oreslang_target_type STREQUAL "EXECUTABLE"
                AND NOT oreslang_test_target STREQUAL "oreslang-llvmc")
            if(MSVC)
                target_compile_options(${oreslang_test_target} PRIVATE /UNDEBUG)
            else()
                target_compile_options(${oreslang_test_target} PRIVATE -UNDEBUG)
            endif()
        endif()
    endforeach()
endif()

if(BUILD_TESTING AND ORE_CLANG)
    add_test(NAME refreshed_actor_persistent_fields
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" actor-persistent-fields
        "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}" "${CMAKE_CURRENT_SOURCE_DIR}")
endif()

if(BUILD_TESTING)
    oreslang_contract_test(refreshed_actor_abi_actor_collision actor-abi-actor-collision.ores --diagnostic "NATIVE_ACTOR_DUPLICATE_DEFINITION: duplicate actor 'Counter'")
    oreslang_contract_test(refreshed_actor_abi_func_collision actor-abi-func-collision.ores --diagnostic "reserved native runtime ABI symbol")
    oreslang_contract_test(refreshed_actor_duplicate_field actor-duplicate-field.ores --diagnostic "duplicate actor field")
    oreslang_contract_test(refreshed_actor_field_shadow actor-field-shadow.ores --diagnostic "actor parameter shadows field")
    oreslang_contract_test(refreshed_actor_immutable_write actor-immutable-write.ores --diagnostic "cannot assign immutable or unknown actor field")
    oreslang_contract_test(refreshed_actor_unknown_field actor-unknown-field.ores --diagnostic "unknown parameter")
    oreslang_contract_test(refreshed_actor_unknown_write actor-unknown-write.ores --diagnostic "cannot assign immutable or unknown actor field")
    oreslang_contract_test(refreshed_actor_unsupported_field actor-unsupported-field.ores --diagnostic "expected 'int'")
endif()

if(BUILD_TESTING)
    add_test(NAME native_aot_artifact_header_negative
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" aot-artifact-header-selftest)
    set_tests_properties(native_aot_artifact_header_negative PROPERTIES TIMEOUT 15)
endif()

if(BUILD_TESTING AND ORE_CLANG)
    add_test(NAME native_actor_hierarchy_ml_source_gate
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-actor-hierarchy-ml-admission.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}" "${CMAKE_CURRENT_SOURCE_DIR}")
    set_tests_properties(native_actor_hierarchy_ml_source_gate PROPERTIES TIMEOUT 40)
endif()

if(BUILD_TESTING AND ORE_CLANG)
    # Known tracing-GC IR release screen, run by compile-aot.sh before Clang.
    # The full linked-image and FFI proof is separate.
    find_program(ORE_NO_GC_RUSTC rustc)
    if(ORE_NO_GC_RUSTC)
        set(ORE_NO_GC_IR_AUDIT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit")
        add_custom_command(
            OUTPUT "${ORE_NO_GC_IR_AUDIT}"
            COMMAND "${ORE_NO_GC_RUSTC}" --edition=2021 -D warnings
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/audit_no_gc_ir.rs" -o "${ORE_NO_GC_IR_AUDIT}"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/scripts/audit_no_gc_ir.rs"
            VERBATIM)
        add_custom_target(oreslang-no-gc-ir-audit-build ALL DEPENDS "${ORE_NO_GC_IR_AUDIT}")
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit-tests"
            COMMAND "${ORE_NO_GC_RUSTC}" --edition=2021 -D warnings --test
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/audit_no_gc_ir.rs"
                -o "${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit-tests"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/scripts/audit_no_gc_ir.rs"
            VERBATIM)
        add_custom_target(oreslang-no-gc-ir-audit-tests-build ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit-tests")
        add_test(NAME native_no_gc_ir_audit_unit
            COMMAND "${CMAKE_CURRENT_BINARY_DIR}/oreslang-no-gc-ir-audit-tests")
        # True native guest execution, not merely source admission or a host
        # constructor model: compiler IR -> no-GC audit -> Clang O0/O2 -> run.
        # This remains the restricted nonthrowing Option<int> trap slice.
        add_test(NAME native_trap_int_guest_aot_o0_o2
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-trap-int-aot.sh"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}"
                "${ORE_NO_GC_IR_AUDIT}"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/trap-int-native42.atto"
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/trap-int-overflow-terminal.atto"
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/compile-aot.sh")
        set_tests_properties(native_trap_int_guest_aot_o0_o2 PROPERTIES TIMEOUT 240)
    else()
        add_test(NAME native_no_gc_ir_audit_unit COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    # Exact canonical AttoNATS source -> LLVM IR/no-GC audit -> Clang O0/O2
    # -> real native guest execution. Distinct from host-only policy tests.
    # Authenticated actor-owned routing is a separate acceptance gate.
    if(ORE_NO_GC_RUSTC)
        add_test(NAME native_atto_nats_pretokenized_string_aot
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-atto-nats-string-aot.sh"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}"
                "${ORE_NO_GC_IR_AUDIT}")
    else()
        add_test(NAME native_atto_nats_pretokenized_string_aot
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_atto_nats_pretokenized_string_aot
        PROPERTIES TIMEOUT 180)

    # Canonical core AttoNATS subjects.atto, exact application Git blob,
    # plus a native compiler-to-Clang validation/matching guest. This MUST
    # execute both real AOT optimization levels, not a source-only oracle.
    if(ORE_NO_GC_RUSTC)
        add_test(NAME native_atto_nats_canonical_subjects_aot
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-atto-nats-subjects-native-aot.sh"
                "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}"
                "${ORE_NO_GC_IR_AUDIT}")
    else()
        add_test(NAME native_atto_nats_canonical_subjects_aot
            COMMAND "${CMAKE_COMMAND}" -E false)
    endif()
    set_tests_properties(native_atto_nats_canonical_subjects_aot
        PROPERTIES TIMEOUT 240)

    add_test(NAME native_aot_driver
        COMMAND "${ORES_RUST_TEST_HARNESS_BIN}" aot-driver
            "${CMAKE_CURRENT_SOURCE_DIR}/scripts/compile-aot.sh"
            "$<TARGET_FILE:oreslang-llvmc>" "${ORE_CLANG}")
    set_tests_properties(native_aot_driver PROPERTIES TIMEOUT 30)
    if(ORE_NO_GC_RUSTC)
        set_tests_properties(native_aot_driver PROPERTIES
            ENVIRONMENT "ORES_NO_GC_IR_AUDIT=${ORE_NO_GC_IR_AUDIT}")
    endif()
endif()

# Independent source-free publication safety test. It uses fake guest IR and a
# real linker; native_aot_driver above is the separate actual guest AOT gate.
if(BUILD_TESTING AND ORE_CLANG AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    add_test(NAME native_aot_publication_negative
        COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-compile-aot-publication.sh"
            "${CMAKE_CURRENT_SOURCE_DIR}/scripts/compile-aot.sh" "${ORE_CLANG}")
    set_tests_properties(native_aot_publication_negative PROPERTIES TIMEOUT 30
        ENVIRONMENT "ORES_NO_GC_IR_AUDIT=${ORE_NO_GC_IR_AUDIT}")
endif()

# Compiler-side nominal enum signature policy (host-only). This does NOT
# admit same-arity guest overloads in native LLVM source or claim runtime parity.
if(BUILD_TESTING)
    add_executable(oreslang-nominal-enum-overload-policy-test
        tests/nominal_enum_overload_policy_test.cpp)
    target_include_directories(oreslang-nominal-enum-overload-policy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-nominal-enum-overload-policy-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_nominal_enum_overload_static_policy
        COMMAND oreslang-nominal-enum-overload-policy-test)
    set_tests_properties(native_nominal_enum_overload_static_policy
        PROPERTIES TIMEOUT 10)
endif()

# Freestanding Cortex-M target: actual Oreslang source -> LLVM IR -> ARM object.
# This is not Zephyr/QEMU firmware evidence; the embedded platform owns that.
if(BUILD_TESTING)
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    add_executable(oreslang-library-profile-policy-test
        tests/library_profile_policy_test.cpp)
    target_include_directories(oreslang-library-profile-policy-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-library-profile-policy-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-library-profile-policy-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_core_std_library_profile_policy
        COMMAND oreslang-library-profile-policy-test)
    set_tests_properties(native_core_std_library_profile_policy PROPERTIES TIMEOUT 10)
    add_executable(oreslang-core-library-sources-test
        tests/core_library_sources_test.cpp)
    target_include_directories(oreslang-core-library-sources-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-core-library-sources-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-core-library-sources-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_core_library_registry_provider_policy
        COMMAND oreslang-core-library-sources-test)
    set_tests_properties(native_core_library_registry_provider_policy PROPERTIES TIMEOUT 10)
    add_test(NAME cortexm_freestanding_source_object
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/cortexm_freestanding_source_object.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(cortexm_freestanding_source_object PROPERTIES TIMEOUT 90)
    add_test(NAME native_core_std_library_profile_admission
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/library_profile_boundary.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_core_std_library_profile_admission PROPERTIES TIMEOUT 60)
    add_test(NAME native_core_library_profile_artifact_boundary
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/library_profile_artifact_boundary.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_core_library_profile_artifact_boundary
        PROPERTIES TIMEOUT 120)
    add_test(NAME native_core_library_import_resolution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/core_library_imports.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(native_core_library_import_resolution
        PROPERTIES TIMEOUT 120)
    add_test(NAME native_alloc_library_import_resolution
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/alloc_library_imports.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_RT_AOT_CLANG}")
    set_tests_properties(native_alloc_library_import_resolution
        PROPERTIES TIMEOUT 120)
    add_executable(oreslang-allocator-provider-v1-test
        tests/allocator_provider_v1_test.c
        src/main/cpp/oreslang/runtime/AllocatorProviderV1.c)
    target_include_directories(oreslang-allocator-provider-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    add_test(NAME native_allocator_provider_v1
        COMMAND oreslang-allocator-provider-v1-test)
    set_tests_properties(native_allocator_provider_v1 PROPERTIES TIMEOUT 10)

    add_executable(oreslang-freestanding-int-array-region-test
        tests/freestanding_int_array_region_test.c
        src/main/cpp/oreslang/runtime/AllocatorProviderV1.c
        src/main/cpp/oreslang/runtime/FreestandingIntArrayRegion.c)
    target_include_directories(oreslang-freestanding-int-array-region-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    # The test performs its setup inside assert(), so Release must keep assertions.
    target_compile_options(oreslang-freestanding-int-array-region-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-UNDEBUG>)
    add_test(NAME native_freestanding_int_array_region
        COMMAND oreslang-freestanding-int-array-region-test)
    set_tests_properties(native_freestanding_int_array_region PROPERTIES TIMEOUT 10)

    add_test(NAME native_freestanding_allocator_provider
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/freestanding_allocator_provider.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --source-root "${CMAKE_CURRENT_SOURCE_DIR}"
            --clang "${ORE_RT_AOT_CLANG}")
    set_tests_properties(native_freestanding_allocator_provider
        PROPERTIES TIMEOUT 240)
    add_test(NAME cortexm_source_actor_admission
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/cortexm_source_actor_admission.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(cortexm_source_actor_admission PROPERTIES TIMEOUT 90)
    # Exhaustive bounded-queue pairs and shuffled actor lifecycle schedules.
    add_test(NAME cortexm_actor_admission_model_matrix
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/cortexm_actor_admission_stress.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>")
    set_tests_properties(cortexm_actor_admission_model_matrix PROPERTIES TIMEOUT 180)
endif()

# Admission gates for language surfaces that this backend does not lower yet.
if(BUILD_TESTING)
  find_package(Python3 COMPONENTS Interpreter REQUIRED)
  add_test(NAME value_boundary_admission
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/value_boundaries.py"
      "$<TARGET_FILE:oreslang-llvmc>")
  set_tests_properties(value_boundary_admission PROPERTIES TIMEOUT 60)
endif()

# HOST-ONLY Option<T> recursive flatten substrate. Source-language admission,
# Future<T> scheduler integration, and guest execution remain independent gates.
if(BUILD_TESTING)
  add_executable(oreslang_wrapper_flatten_option_host tests/wrapper_flatten_option_host_test.cpp)
  target_include_directories(oreslang_wrapper_flatten_option_host PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime")
  target_compile_options(oreslang_wrapper_flatten_option_host PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
  add_test(NAME llvm_wrapper_option_host_conformance COMMAND oreslang_wrapper_flatten_option_host)
  set_tests_properties(llvm_wrapper_option_host_conformance PROPERTIES TIMEOUT 20)
endif()

# HOST-ONLY token bucket arithmetic and concurrent reservation prototype.
# Does NOT enforce guest sends or prove native UNTRUSTED isolation.
if(BUILD_TESTING)
    add_executable(oreslang-trusted-outbound-token-bucket-host-test
        tests/trusted_outbound_token_bucket_test.cpp)
    target_include_directories(oreslang-trusted-outbound-token-bucket-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-trusted-outbound-token-bucket-host-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-trusted-outbound-token-bucket-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME trusted_outbound_token_bucket_host_only
        COMMAND oreslang-trusted-outbound-token-bucket-host-test)
    set_tests_properties(trusted_outbound_token_bucket_host_only PROPERTIES TIMEOUT 15)
endif()

# HOST-ONLY UNTRUSTED HTTP/TCP budget helper test. No guest network admission
# path is wired here and no in-process native security claim is made.
if(BUILD_TESTING)
    add_executable(oreslang-untrusted-network-quota-host-test
        tests/trusted_untrusted_network_quota_test.cpp)
    target_include_directories(oreslang-untrusted-network-quota-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-untrusted-network-quota-host-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-untrusted-network-quota-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME untrusted_network_quota_host_only
        COMMAND oreslang-untrusted-network-quota-host-test)
    set_tests_properties(untrusted_network_quota_host_only PROPERTIES TIMEOUT 20)
endif()

# Hierarchical host-only HTTP/TCP quota: child, supervisor, tenant.
# This does not admit arbitrary native UNTRUSTED guest source.
if(BUILD_TESTING)
    add_executable(oreslang-trusted-hierarchical-network-gate-host-test
        tests/trusted_hierarchical_network_gate_test.cpp)
    target_include_directories(oreslang-trusted-hierarchical-network-gate-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-trusted-hierarchical-network-gate-host-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-trusted-hierarchical-network-gate-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME trusted_hierarchical_network_gate_host_only
        COMMAND oreslang-trusted-hierarchical-network-gate-host-test)
    set_tests_properties(trusted_hierarchical_network_gate_host_only PROPERTIES TIMEOUT 20)
endif()


# Standalone native IPC receipt reference; not live cross-process actor transport.
if(BUILD_TESTING)
    add_executable(oreslang-native-ipc-receipt-ledger-test
        tests/native_ipc_receipt_ledger_test.cpp)
    target_include_directories(oreslang-native-ipc-receipt-ledger-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    find_package(Threads REQUIRED)
    target_link_libraries(oreslang-native-ipc-receipt-ledger-test PRIVATE Threads::Threads)
    target_compile_features(oreslang-native-ipc-receipt-ledger-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-ipc-receipt-ledger-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_ipc_receipt_correlation_host_reference
        COMMAND oreslang-native-ipc-receipt-ledger-test)
    set_tests_properties(native_ipc_receipt_correlation_host_reference
        PROPERTIES TIMEOUT 10)
endif()


# Advisory v0 only: compiler-backed literal-main facts, no actor/termination claims.
if(BUILD_TESTING)
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    add_test(NAME llvm_static_facts_v0_negative
        COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/static_facts_v0.py" --self-test)
    add_test(NAME llvm_static_facts_v0_real_compiler
        COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tests/static_facts_v0.py"
        --compiler "$<TARGET_FILE:oreslang-llvmc>"
        --source "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/fixtures/literal-42.ores")
    set_tests_properties(llvm_static_facts_v0_negative llvm_static_facts_v0_real_compiler
        PROPERTIES TIMEOUT 30)
endif()

# Source-compiled JVM/native declaration parity slice. This deliberately tests
# native scalar aliases only; generic and trait/interface forms MUST fail closed.
if(BUILD_TESTING)
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    find_program(ORE_FORWARD_TYPE_CLANG
        NAMES clang-18 clang-19 clang clang-17 clang-16 clang-15 clang-14 REQUIRED)
    add_test(NAME native_declare_type_scalar_source_parity
        COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_declare_type_parity.py"
            --compiler "$<TARGET_FILE:oreslang-llvmc>"
            --clang "${ORE_FORWARD_TYPE_CLANG}")
    set_tests_properties(native_declare_type_scalar_source_parity PROPERTIES TIMEOUT 120)
endif()

# Host-only native proxy slab parity with Java ActorProxyRequestSlab.
# The borrowed arena span requires caller-owned actor-turn fencing; this is
# not guest Oreslang lowering or a live HTTP/CONNECT transport.
if(BUILD_TESTING)
    add_executable(oreslang-native-proxy-request-slab-test
        tests/native_proxy_request_slab_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-proxy-request-slab-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-proxy-request-slab-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-proxy-request-slab-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_proxy_request_slab_host_only
        COMMAND oreslang-native-proxy-request-slab-test)
    set_tests_properties(native_proxy_request_slab_host_only
        PROPERTIES TIMEOUT 20)
endif()

# Full native actor scheduler proof: exact-generation ownership and paired
# actor/VM quota, with backpressure, receipt reuse and foreign-turn rejection.
# C scheduler source must be compiled as C; the actor test uses C++20.
if(BUILD_TESTING)
    add_executable(oreslang-native-actor-proxy-slab-owner-test
        tests/native_actor_proxy_request_slab_owner_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-actor-proxy-slab-owner-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-proxy-slab-owner-test PRIVATE Threads::Threads)
    target_compile_features(oreslang-native-actor-proxy-slab-owner-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-actor-proxy-slab-owner-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_proxy_slab_generation_and_quota
        COMMAND oreslang-native-actor-proxy-slab-owner-test)
    set_tests_properties(native_actor_proxy_slab_generation_and_quota
        PROPERTIES TIMEOUT 30)
endif()
# Host-only fixed-size, lock-free physical turn credits. This does NOT enable
# concurrent source actors or relax the existing NativeExecutionAuthority.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-turn-credits-test
        tests/native_actor_turn_credits_test.cpp)
    target_include_directories(oreslang-native-actor-turn-credits-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-turn-credits-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-turn-credits-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_actor_physical_turn_credit_host_only
        COMMAND oreslang-native-actor-turn-credits-test)
    set_tests_properties(native_actor_physical_turn_credit_host_only
        PROPERTIES TIMEOUT 25)
endif()

# Dedicated Linux epoll reactor thread, bounded completion ring and generation
# fencing. Host-only substrate; no compiler source-level await linkage yet.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-epoll-reactor-test tests/native_epoll_reactor_test.cpp)
    target_include_directories(oreslang-native-epoll-reactor-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-reactor-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-epoll-reactor-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_epoll_reactor_generation_completion
        COMMAND oreslang-native-epoll-reactor-test)
    set_tests_properties(native_epoll_reactor_generation_completion PROPERTIES TIMEOUT 20)

    # Separate translation unit interposes eventfd only for controlled
    # constructor failure-injection, checking both errno and FD reclamation.
    add_executable(oreslang-native-epoll-reactor-bootstrap-failure-test
        tests/native_epoll_reactor_bootstrap_failure_test.cpp)
    target_include_directories(oreslang-native-epoll-reactor-bootstrap-failure-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-reactor-bootstrap-failure-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-epoll-reactor-bootstrap-failure-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_epoll_reactor_eventfd_bootstrap_failclosed
        COMMAND oreslang-native-epoll-reactor-bootstrap-failure-test)
    set_tests_properties(native_epoll_reactor_eventfd_bootstrap_failclosed PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-epoll-reactor-manager-test
        tests/native_epoll_reactor_manager_test.cpp)
    target_include_directories(oreslang-native-epoll-reactor-manager-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-reactor-manager-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-epoll-reactor-manager-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_epoll_reactor_manager_rt_preallocated
        COMMAND oreslang-native-epoll-reactor-manager-test)
    set_tests_properties(native_epoll_reactor_manager_rt_preallocated PROPERTIES TIMEOUT 20)
endif()

# Trusted native host-only immutable payload descriptors and bounded DATA inbox.
# Zero-copy proof is NOT compiled .atto actor mailbox lowering or guest parity.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-shared-immutable-payload-test
        tests/native_shared_immutable_payload_test.cpp)
    target_include_directories(oreslang-native-shared-immutable-payload-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-shared-immutable-payload-test PRIVATE cxx_std_20)
    target_link_libraries(oreslang-native-shared-immutable-payload-test PRIVATE Threads::Threads)
    add_test(NAME native_shared_immutable_payload_host
        COMMAND oreslang-native-shared-immutable-payload-test)
    set_tests_properties(native_shared_immutable_payload_host PROPERTIES TIMEOUT 25)
endif()

# Connect immutable payload tickets to the REAL native actor scheduler DATA lane.
# Host-only integration: no source .atto lowering and no five-backend parity claim.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-shared-payload-bridge-test
        tests/native_actor_shared_payload_scheduler_bridge_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-shared-payload-bridge-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-shared-payload-bridge-test PRIVATE cxx_std_20)
    target_link_libraries(oreslang-native-actor-shared-payload-bridge-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-shared-payload-bridge-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror)
    add_test(NAME native_actor_shared_payload_real_scheduler
        COMMAND oreslang-native-actor-shared-payload-bridge-test)
    set_tests_properties(native_actor_shared_payload_real_scheduler PROPERTIES TIMEOUT 30)
endif()

# Host-only actor-shared TCP/HTTP pool over real loopback sockets (POSIX).
# NOT compiled .atto guest networking.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-actor-shared-tcp-pool-test
        tests/actor_shared_tcp_pool_test.cpp)
    target_include_directories(oreslang-actor-shared-tcp-pool-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-actor-shared-tcp-pool-test PRIVATE cxx_std_20)
    target_link_libraries(oreslang-actor-shared-tcp-pool-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-actor-shared-tcp-pool-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME actor_shared_tcp_pool_loopback
        COMMAND oreslang-actor-shared-tcp-pool-test)
    set_tests_properties(actor_shared_tcp_pool_loopback PROPERTIES TIMEOUT 120)
endif()

# Native RT profile has no pthread creation after VM preparation. Existing
# throughput open remains metadata-only until first runnable admission.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-vm-realtime-prestart-test
        tests/native_vm_realtime_prestart_test.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-vm-realtime-prestart-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-vm-realtime-prestart-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-realtime-prestart-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_vm_realtime_preallocated_startup
        COMMAND oreslang-native-vm-realtime-prestart-test)
    set_tests_properties(native_vm_realtime_preallocated_startup PROPERTIES TIMEOUT 25)
endif()

# Narrow C ABI for the C++ Linux reactor domain. Validates C compiler + C++
# link, typed generation tickets, readiness/completion, and RT startup mode.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-epoll-reactor-abi-test
        tests/native_epoll_reactor_abi_test.c
        src/main/cpp/oreslang/runtime/NativeEpollReactorABI.cpp)
    target_include_directories(oreslang-native-epoll-reactor-abi-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-epoll-reactor-abi-test PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(oreslang-native-epoll-reactor-abi-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-epoll-reactor-abi-test PRIVATE
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wpedantic -Werror>
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_epoll_reactor_c_abi
        COMMAND oreslang-native-epoll-reactor-abi-test)
    set_tests_properties(native_epoll_reactor_c_abi PROPERTIES TIMEOUT 25)
endif()

# Host VM ↔ epoll integration. C-only actor/scheduler paths remain linkable
# without NIO C++ objects; only this opt-in Linux target links the bridge.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-vm-nio-lifecycle-test
        tests/native_vm_nio_lifecycle_test.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVMNio.cpp
        src/main/cpp/oreslang/runtime/NativeEpollReactorABI.cpp)
    target_include_directories(oreslang-native-vm-nio-lifecycle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-vm-nio-lifecycle-test PROPERTIES
        LINKER_LANGUAGE CXX)
    target_compile_features(oreslang-native-vm-nio-lifecycle-test PRIVATE c_std_11 cxx_std_17)
    target_link_libraries(oreslang-native-vm-nio-lifecycle-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-vm-nio-lifecycle-test PRIVATE
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wpedantic -Werror>
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_vm_nio_lifecycle_and_rt_prestart
        COMMAND oreslang-native-vm-nio-lifecycle-test)
    set_tests_properties(native_vm_nio_lifecycle_and_rt_prestart
        PROPERTIES TIMEOUT 30)
    # One host process, zero VM-created pthreads: ETS, pg, control wake and
    # attached-but-idle epoll must not secretly start scheduler/reactor workers.
    add_executable(oreslang-native-vm-ets-pg-cold-boot-test
        tests/native_vm_ets_pg_cold_boot_test.cpp
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVMNio.cpp
        src/main/cpp/oreslang/runtime/NativeEpollReactorABI.cpp)
    target_include_directories(oreslang-native-vm-ets-pg-cold-boot-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-vm-ets-pg-cold-boot-test PRIVATE
        c_std_11 cxx_std_17)
    set_target_properties(oreslang-native-vm-ets-pg-cold-boot-test PROPERTIES
        LINKER_LANGUAGE CXX)
    target_link_libraries(oreslang-native-vm-ets-pg-cold-boot-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-native-vm-ets-pg-cold-boot-test PRIVATE
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_vm_ets_pg_zero_cold_threads
        COMMAND oreslang-native-vm-ets-pg-cold-boot-test)
    set_tests_properties(native_vm_ets_pg_zero_cold_threads
        PROPERTIES TIMEOUT 30)
    # Real native VM mailbox admission, not a mock send callback. Test only
    # runs with trusted host refs; source pg lowering remains unsupported.
    add_executable(oreslang-native-pg-vm-mailbox-test
        tests/native_pg_vm_mailbox_admission_test.cpp
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-pg-vm-mailbox-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-pg-vm-mailbox-test PROPERTIES
        LINKER_LANGUAGE CXX)
    target_link_libraries(oreslang-native-pg-vm-mailbox-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-native-pg-vm-mailbox-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_pg_real_vm_mailbox_stop_backpressure
        COMMAND oreslang-native-pg-vm-mailbox-test)
    set_tests_properties(native_pg_real_vm_mailbox_stop_backpressure
        PROPERTIES TIMEOUT 30)
endif()

# Opt-in native VM NIO actor-owned completion path. The C++ host adapter
# retains callback permits through explicit terminal acknowledgment; the
# existing C NIO ABI remains available only on distinct backend instances.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-vm-actor-nio-owned-test
        tests/native_vm_actor_nio_owned_test.cpp
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVMNio.cpp
        src/main/cpp/oreslang/runtime/NativeEpollReactorABI.cpp)
    target_include_directories(oreslang-native-vm-actor-nio-owned-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    set_target_properties(oreslang-native-vm-actor-nio-owned-test
        PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(oreslang-native-vm-actor-nio-owned-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-vm-actor-nio-owned-test PRIVATE
        c_std_11 cxx_std_20)
    target_compile_options(oreslang-native-vm-actor-nio-owned-test PRIVATE
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_vm_actor_nio_owned_callbacks
        COMMAND oreslang-native-vm-actor-nio-owned-test)
    set_tests_properties(native_vm_actor_nio_owned_callbacks
        PROPERTIES TIMEOUT 40)
endif()

# This compiles the REAL native frontend parser and ownership checker,
# independent of LLVM IR emission. LOW is executable; MEDIUM/HIGH fail closed
# before codegen until safe per-turn actor frames and state loans exist.
if(BUILD_TESTING)
    add_executable(oreslang-native-actor-max-threads-admission-test
        tests/actor_max_threads_source_admission_test.cpp
        src/main/cpp/oreslang/frontend/Parser.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp
        src/main/cpp/oreslang/types/NativeScalarTypes.cpp
        src/main/cpp/oreslang/types/OwnershipChecker.cpp)
    target_include_directories(oreslang-native-actor-max-threads-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    # The production parser hashes type-only descriptors through LLVM Support.
    target_include_directories(oreslang-native-actor-max-threads-admission-test SYSTEM PRIVATE
        ${LLVM_INCLUDE_DIRS})
    target_link_libraries(oreslang-native-actor-max-threads-admission-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_features(oreslang-native-actor-max-threads-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-max-threads-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_actor_max_threads_source_admission
        COMMAND oreslang-native-actor-max-threads-admission-test)
    set_tests_properties(native_actor_max_threads_source_admission
        PROPERTIES TIMEOUT 20)
endif()

# GPU actors have a distinct source-admission diagnostic, but no GPU-native
# state/MIR, kernel or physical hardware implementation. This test compiles
# the REAL parser and must never be used as GPU execution evidence.
if(BUILD_TESTING)
    add_executable(oreslang-native-gpu-actor-admission-test
        tests/gpu_actor_source_admission_test.cpp
        src/main/cpp/oreslang/frontend/Parser.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp
        src/main/cpp/oreslang/types/NativeScalarTypes.cpp
        src/main/cpp/oreslang/types/OwnershipChecker.cpp)
    target_include_directories(oreslang-native-gpu-actor-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_include_directories(oreslang-native-gpu-actor-admission-test SYSTEM PRIVATE
        ${LLVM_INCLUDE_DIRS})
    target_link_libraries(oreslang-native-gpu-actor-admission-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_features(oreslang-native-gpu-actor-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-gpu-actor-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_gpu_actor_source_admission
        COMMAND oreslang-native-gpu-actor-admission-test)
    set_tests_properties(native_gpu_actor_source_admission PROPERTIES TIMEOUT 20)
endif()

# Compile the production native parser against explicit v1 macro syntax.
# Until native expansion exists, every macro declaration/invocation must fail
# before LLVM IR with the dedicated no-runtime-fallback diagnostic.
if(BUILD_TESTING)
    add_executable(oreslang-native-macro-aot-admission-test
        tests/native_macro_aot_admission_test.cpp
        src/main/cpp/oreslang/frontend/Parser.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp
        src/main/cpp/oreslang/types/NativeScalarTypes.cpp
        src/main/cpp/oreslang/types/OwnershipChecker.cpp)
    target_include_directories(oreslang-native-macro-aot-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_include_directories(oreslang-native-macro-aot-admission-test SYSTEM PRIVATE
        ${LLVM_INCLUDE_DIRS})
    target_link_libraries(oreslang-native-macro-aot-admission-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_features(oreslang-native-macro-aot-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-macro-aot-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_macro_aot_admission_no_runtime_fallback
        COMMAND oreslang-native-macro-aot-admission-test)
    set_tests_properties(native_macro_aot_admission_no_runtime_fallback
        PROPERTIES TIMEOUT 20)

    # Exercise the actual compiler executable without adding a Python test
    # dependency. Rejection must exit 1, carry the dedicated diagnostic, and
    # emit no stdout/partial LLVM IR.
    foreach(macro_fixture IN ITEMS
            native-macro-declaration-reject.ores
            native-macro-invocation-reject.ores)
        string(REPLACE ".ores" "" macro_case "${macro_fixture}")
        add_test(NAME "native_${macro_case}_no_ir"
            COMMAND "${CMAKE_COMMAND}"
                "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/${macro_fixture}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_macro_reject.cmake")
        set_tests_properties("native_${macro_case}_no_ir"
            PROPERTIES TIMEOUT 25)
    endforeach()
endif()

# Canonical shared stage-0 .atto negative vectors from common-source #218.
# These are compiled by the actual production LLVM binary. No source text
# interpreter, stub, or synthetic return-42 IR may substitute for compiler output.
if(BUILD_TESTING)
    foreach(macro_case IN ITEMS
            overflow-reject wrong-kind-reject dynamic-const-reject unresolved-reject)
        if(macro_case STREQUAL "overflow-reject")
            set(macro_diagnostic "NATIVE_MACRO_CONST_OVERFLOW")
        elseif(macro_case STREQUAL "dynamic-const-reject")
            # Const<int> cannot read an unresolved runtime variable. Demand
            # a dedicated compiler-only diagnostic, not an unrelated parse
            # failure that might accidentally make a negative test pass.
            set(macro_diagnostic "NATIVE_MACRO_CONST_UNCLOSED")
        else()
            set(macro_diagnostic "NATIVE_MACRO_EXPANDER_REQUIRED")
        endif()
        add_test(NAME "native_macro_shared_${macro_case}_no_ir"
            COMMAND "${CMAKE_COMMAND}"
                "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-macro-scalar-v1/${macro_case}.atto"
                "-DDIAGNOSTIC=${macro_diagnostic}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_macro_reject.cmake")
        set_tests_properties("native_macro_shared_${macro_case}_no_ir"
            PROPERTIES TIMEOUT 25)
    endforeach()
endif()

# Real guest-native LLVM proof for the two bounded typed macro AST cases.
# This invokes the production compiler, then links emitted IR into a host
# executable using clang; an IR printout alone is not guest execution.
if(BUILD_TESTING)
    find_program(ORE_NATIVE_MACRO_CLANG_EXECUTABLE NAMES clang)
    if(ORE_NATIVE_MACRO_CLANG_EXECUTABLE)
        foreach(macro_case IN ITEMS const identity)
            set(macro_fixture
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-macro-${macro_case}-aot.ores")
            add_test(NAME "native_macro_${macro_case}_linked_guest_aot_42"
                COMMAND "${CMAKE_COMMAND}"
                    "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                    "-DFIXTURE=${macro_fixture}"
                    "-DCLANG=${ORE_NATIVE_MACRO_CLANG_EXECUTABLE}"
                    "-DSTAGING=${CMAKE_CURRENT_BINARY_DIR}/macro-aot-${macro_case}"
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_macro_guest_aot.cmake")
            set_tests_properties("native_macro_${macro_case}_linked_guest_aot_42"
                PROPERTIES TIMEOUT 90)
        endforeach()

        # In addition to legacy .ores, run the three exact shared .atto
        # compiler-plus-Clang guest vectors. All must return native i64 42.
        foreach(macro_case IN ITEMS literal-42 identity-42 nested-42)
            set(macro_fixture
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/native-macro-scalar-v1/${macro_case}.atto")
            add_test(NAME "native_macro_shared_${macro_case}_linked_guest_aot_42"
                COMMAND "${CMAKE_COMMAND}"
                    "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
                    "-DFIXTURE=${macro_fixture}"
                    "-DCLANG=${ORE_NATIVE_MACRO_CLANG_EXECUTABLE}"
                    "-DSTAGING=${CMAKE_CURRENT_BINARY_DIR}/macro-aot-shared-${macro_case}"
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_macro_guest_aot.cmake")
            set_tests_properties("native_macro_shared_${macro_case}_linked_guest_aot_42"
                PROPERTIES TIMEOUT 90)
        endforeach()
    endif()
endif()

# Compiler-owned singleton admission must remain contextual and fail closed
# until the shared v1 semantics and lowering are implemented.
if(BUILD_TESTING)
    add_executable(oreslang-native-singleton-admission-test
        tests/native_singleton_admission_test.cpp
        src/main/cpp/oreslang/frontend/Parser.cpp
        src/main/cpp/oreslang/frontend/MethodResolver.cpp
        src/main/cpp/oreslang/types/NativeScalarTypes.cpp
        src/main/cpp/oreslang/types/OwnershipChecker.cpp)
    target_include_directories(oreslang-native-singleton-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_include_directories(oreslang-native-singleton-admission-test SYSTEM PRIVATE
        ${LLVM_INCLUDE_DIRS})
    target_link_libraries(oreslang-native-singleton-admission-test PRIVATE ${ORE_LLVM_LIBS})
    target_compile_features(oreslang-native-singleton-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-singleton-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_singleton_admission
        COMMAND oreslang-native-singleton-admission-test)
    set_tests_properties(native_singleton_admission PROPERTIES TIMEOUT 20)
endif()

# Host-only GPU actor turn/fence/supervisor composition. This is not a
# GPU provider, source actor lowering, or physical GPU execution test.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-gpu-actor-host-bridge-v0-test
        tests/gpu_actor_host_turn_bridge_v0_test.cpp)
    target_include_directories(oreslang-gpu-actor-host-bridge-v0-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-gpu-actor-host-bridge-v0-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-gpu-actor-host-bridge-v0-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-gpu-actor-host-bridge-v0-test PRIVATE Threads::Threads)
    add_test(NAME native_gpu_actor_host_turn_fence_control_v0
        COMMAND oreslang-gpu-actor-host-bridge-v0-test)
    set_tests_properties(native_gpu_actor_host_turn_fence_control_v0 PROPERTIES TIMEOUT 30)

    # Production authority gate: only the supervisor V1 registry may invoke
    # raw host Boolean completion/release methods. Ensure an ordinary native
    # translation unit is rejected by the configured compiler.
    add_test(NAME native_gpu_actor_v0_completion_authority_private
        COMMAND "${CMAKE_COMMAND}"
            "-DGPU_ACTOR_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
            "-DGPU_ACTOR_INCLUDE_ROOT=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
            "-DGPU_ACTOR_NEGATIVE_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_actor_v0_completion_authority_negative.cpp"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/GpuActorV0CompletionAuthorityGate.cmake")
    set_tests_properties(native_gpu_actor_v0_completion_authority_private
        PROPERTIES TIMEOUT 30)
    # Worker-only quiescence/cleanup authority: ordinary C++ callers MUST
    # fail compilation when invoking these potentially blocking methods.
    add_test(NAME native_gpu_actor_recovery_worker_authority_private
        COMMAND "${CMAKE_COMMAND}"
            "-DGPU_ACTOR_CXX_COMPILER=${CMAKE_CXX_COMPILER}"
            "-DGPU_ACTOR_INCLUDE_ROOT=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
            "-DGPU_ACTOR_NEGATIVE_SOURCE=${CMAKE_CURRENT_SOURCE_DIR}/tests/gpu_actor_recovery_authority_negative.cpp"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/GpuActorRecoveryAuthorityGate.cmake")
    set_tests_properties(native_gpu_actor_recovery_worker_authority_private
        PROPERTIES TIMEOUT 30)

endif()

# V1 opaque host provider event identity and bounded actor quarantine. This
# test uses an injected MOCK port; no physical GPU execution is implied.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-gpu-actor-provider-bridge-v1-test
        tests/gpu_actor_provider_bridge_v1_test.cpp)
    target_include_directories(oreslang-gpu-actor-provider-bridge-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-gpu-actor-provider-bridge-v1-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-gpu-actor-provider-bridge-v1-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-gpu-actor-provider-bridge-v1-test PRIVATE Threads::Threads)
    add_test(NAME native_gpu_actor_provider_bridge_v1_host_mock
        COMMAND oreslang-gpu-actor-provider-bridge-v1-test)
    set_tests_properties(native_gpu_actor_provider_bridge_v1_host_mock PROPERTIES TIMEOUT 30)
endif()

# V2 OpenCL actor adapter integration: the first target uses an injected
# fake Api, the second REQUIRES a real GPU. A CPU OpenCL device cannot pass.
# A physical probe returning 77 reports GPU_NOT_EXECUTED, not success.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-gpu-actor-opencl-v2-host-test
        tests/gpu_actor_opencl_provider_v2_test.cpp)
    target_include_directories(oreslang-gpu-actor-opencl-v2-host-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-gpu-actor-opencl-v2-host-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-gpu-actor-opencl-v2-host-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-gpu-actor-opencl-v2-host-test PRIVATE Threads::Threads dl)
    add_test(NAME native_gpu_actor_opencl_v2_host_mock
        COMMAND oreslang-gpu-actor-opencl-v2-host-test)
    set_tests_properties(native_gpu_actor_opencl_v2_host_mock PROPERTIES TIMEOUT 30)

    add_executable(oreslang-gpu-actor-opencl-v2-physical-probe
        tests/gpu_actor_opencl_v2_physical_probe.cpp)
    target_include_directories(oreslang-gpu-actor-opencl-v2-physical-probe PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-gpu-actor-opencl-v2-physical-probe PRIVATE cxx_std_17)
    target_compile_options(oreslang-gpu-actor-opencl-v2-physical-probe PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-gpu-actor-opencl-v2-physical-probe PRIVATE Threads::Threads dl)
    # Explicit physical GPU gate is run separately on GPU-capable runners.
    # Do NOT mark SKIP_RETURN_CODE=77: skipped CTest is NOT hardware proof.
endif()

# Dependency-free, executable import/source admission boundary. This test
# compiles the production SourceInput.hpp with the host C++ compiler.
if(BUILD_TESTING AND UNIX)
    add_executable(oreslang-native-source-input-admission-test
        tests/native_source_input_admission.cpp)
    target_include_directories(oreslang-native-source-input-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-source-input-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-source-input-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_source_input_admission
        COMMAND oreslang-native-source-input-admission-test)
    set_tests_properties(native_source_input_admission PROPERTIES TIMEOUT 10)
endif()

if(BUILD_TESTING)
    add_executable(oreslang-native-record-region-test
        tests/native_record_region_test.c
        src/main/cpp/oreslang/runtime/NativeRecordRegion.c)
    target_include_directories(oreslang-native-record-region-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-record-region-test PRIVATE ORESLANG_RECORD_TEST_ALLOC_FAILURE=1)
    target_compile_features(oreslang-native-record-region-test PRIVATE c_std_11)
    target_compile_options(oreslang-native-record-region-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    target_link_libraries(oreslang-native-record-region-test PRIVATE Threads::Threads)
    add_test(NAME native_record_region_generation_quota_cleanup COMMAND oreslang-native-record-region-test)
    set_tests_properties(native_record_region_generation_quota_cleanup PROPERTIES TIMEOUT 30)

    # Execute the source-level negative mutations as a separate native HOST
    # evidence class. Never treat this as guest compiler/actor parity.
    if(UNIX AND CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        find_program(ORE_RECORD_MUTATION_BASH NAMES bash)
        if(ORE_RECORD_MUTATION_BASH)
            add_test(NAME native_record_region_negative_mutations
                COMMAND "${CMAKE_COMMAND}" -E env "CC=${CMAKE_C_COMPILER}"
                "${ORE_RECORD_MUTATION_BASH}"
                "${CMAKE_CURRENT_SOURCE_DIR}/scripts/test-native-record-region-mutations.sh")
            set_tests_properties(native_record_region_negative_mutations
                PROPERTIES TIMEOUT 90 LABELS "native-host;negative-mutation")
        endif()
    endif()
endif()

# Stage-2 prerequisite: readiness-gated Future continuation ownership.
# This host race test is not source-level async, channels or select parity.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-continuation-ready-resume-test
        tests/native_continuation_ready_resume_test.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c)
    set_target_properties(oreslang-native-continuation-ready-resume-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_include_directories(oreslang-native-continuation-ready-resume-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-continuation-ready-resume-test PRIVATE
        Threads::Threads)
    target_compile_options(oreslang-native-continuation-ready-resume-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_continuation_ready_cancel_resume_race
        COMMAND oreslang-native-continuation-ready-resume-test)
    set_tests_properties(native_continuation_ready_cancel_resume_race PROPERTIES TIMEOUT 45)
endif()

# Source-free continuation wake failure-boundary oracle. This verifies only
# model invariants; production continuation-store tests remain separate.
if(BUILD_TESTING)
    add_executable(oreslang-continuation-wake-transaction-oracle
        tests/continuation_wake_transaction_oracle.c)
    set_target_properties(oreslang-continuation-wake-transaction-oracle PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
    target_compile_options(oreslang-continuation-wake-transaction-oracle PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME continuation_wake_transaction_model
        COMMAND oreslang-continuation-wake-transaction-oracle)
    set_tests_properties(continuation_wake_transaction_model PROPERTIES TIMEOUT 10)
endif()

# Source-actor lifecycle prerequisite for real ActorRef.get_done lowering:
# stop request must not wait for physical termination; join_done owns the
# host-side terminal ACK and heap-reclamation barrier.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)

    add_executable(oreslang-native-actor-sealed-stop-test
        tests/native_actor_sealed_stop_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-sealed-stop-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-sealed-stop-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-actor-sealed-stop-test PRIVATE c_std_11)
    target_compile_options(oreslang-native-actor-sealed-stop-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_actor_sealed_stop_abandons_queued_data
        COMMAND oreslang-native-actor-sealed-stop-test)
    set_tests_properties(native_actor_sealed_stop_abandons_queued_data
        PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-actor-sealed-stop-race-test
        tests/native_actor_sealed_stop_race_test.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-native-actor-sealed-stop-race-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-sealed-stop-race-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-actor-sealed-stop-race-test PRIVATE c_std_11)
    target_compile_options(oreslang-native-actor-sealed-stop-race-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME native_actor_sealed_stop_send_race
        COMMAND oreslang-native-actor-sealed-stop-race-test)
    set_tests_properties(native_actor_sealed_stop_send_race
        PROPERTIES TIMEOUT 60)

    add_executable(oreslang-source-actor-stop-join-split-test
        tests/source_actor_stop_join_split_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-source-actor-stop-join-split-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-source-actor-stop-join-split-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-source-actor-stop-join-split-test PRIVATE c_std_11)
    target_compile_options(oreslang-source-actor-stop-join-split-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME source_actor_stop_request_join_split
        COMMAND oreslang-source-actor-stop-join-split-test)
    set_tests_properties(source_actor_stop_request_join_split PROPERTIES TIMEOUT 30)

    add_executable(oreslang-source-actor-await-done-trace-test
        tests/source_actor_await_done_trace_runtime_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-source-actor-await-done-trace-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-source-actor-await-done-trace-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-source-actor-await-done-trace-test PRIVATE c_std_11)
    target_compile_definitions(oreslang-source-actor-await-done-trace-test PRIVATE
        ORESLANG_SOURCE_ACTOR_TESTING=1)
    target_compile_options(oreslang-source-actor-await-done-trace-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME source_actor_await_done_trace_runtime
        COMMAND oreslang-source-actor-await-done-trace-test)
    set_tests_properties(source_actor_await_done_trace_runtime PROPERTIES TIMEOUT 30)


    add_executable(oreslang-source-actor-stop-before-ready-test
        tests/source_actor_stop_before_ready_test.c
        src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c
        src/main/cpp/oreslang/runtime/NativeOresVM.c
        src/main/cpp/oreslang/runtime/NativeActorFuture.c
        src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
        src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
    target_include_directories(oreslang-source-actor-stop-before-ready-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-source-actor-stop-before-ready-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-source-actor-stop-before-ready-test PRIVATE c_std_11)
    target_compile_definitions(oreslang-source-actor-stop-before-ready-test PRIVATE
        ORESLANG_SOURCE_ACTOR_TESTING=1)
    target_compile_options(oreslang-source-actor-stop-before-ready-test PRIVATE
        -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
    add_test(NAME source_actor_stop_before_ready
        COMMAND oreslang-source-actor-stop-before-ready-test)
    set_tests_properties(source_actor_stop_before_ready PROPERTIES TIMEOUT 30)

    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        add_executable(oreslang-source-actor-stop-before-ready-sanitizer-test
            tests/source_actor_stop_before_ready_test.c
            src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
            src/main/cpp/oreslang/runtime/NativeActorScheduler.c
            src/main/cpp/oreslang/runtime/NativeOresVM.c
            src/main/cpp/oreslang/runtime/NativeActorFuture.c
            src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
            src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
            src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
            src/main/cpp/oreslang/runtime/NativeContinuationStore.c
            src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
            src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
        target_include_directories(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            Threads::Threads)
        target_compile_features(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            c_std_11)
        target_compile_definitions(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            ORESLANG_SOURCE_ACTOR_TESTING=1)
        target_compile_options(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG
            -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(
            oreslang-source-actor-stop-before-ready-sanitizer-test PRIVATE
            -fsanitize=address,undefined -fno-omit-frame-pointer)
        add_test(NAME source_actor_stop_before_ready_asan_ubsan
            COMMAND oreslang-source-actor-stop-before-ready-sanitizer-test)
        set_tests_properties(source_actor_stop_before_ready_asan_ubsan
            PROPERTIES TIMEOUT 60)

        add_executable(oreslang-source-actor-stop-before-ready-tsan-test
            tests/source_actor_stop_before_ready_test.c
            src/main/cpp/oreslang/runtime/ActorOwnedI64Heap.c
            src/main/cpp/oreslang/runtime/NativeActorScheduler.c
            src/main/cpp/oreslang/runtime/NativeOresVM.c
            src/main/cpp/oreslang/runtime/NativeActorFuture.c
            src/main/cpp/oreslang/runtime/SourceActorBootstrap.c
            src/main/cpp/oreslang/runtime/NativeActorLogicalFramesV1.c
            src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
            src/main/cpp/oreslang/runtime/NativeContinuationStore.c
            src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
            src/main/cpp/oreslang/runtime/NativeLogicalTrace.c)
        target_include_directories(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_link_libraries(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            Threads::Threads)
        target_compile_features(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            c_std_11)
        target_compile_definitions(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            ORESLANG_SOURCE_ACTOR_TESTING=1)
        target_compile_options(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            -Wall -Wextra -Wpedantic -Werror -UNDEBUG
            -fsanitize=thread -fno-omit-frame-pointer)
        target_link_options(
            oreslang-source-actor-stop-before-ready-tsan-test PRIVATE
            -fsanitize=thread -fno-omit-frame-pointer)
        add_test(NAME source_actor_stop_before_ready_tsan
            COMMAND oreslang-source-actor-stop-before-ready-tsan-test)
        set_tests_properties(source_actor_stop_before_ready_tsan
            PROPERTIES TIMEOUT 60)
    endif()
endif()

# Receiver-elided source overload selector; this C++ unit is intentionally
# independent of LLVM libraries and can be compiled on minimal hosts.
if(BUILD_TESTING)
    add_executable(oreslang-native-callable-selector-test
        tests/native_callable_selector_test.cpp)
    target_include_directories(oreslang-native-callable-selector-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-callable-selector-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-callable-selector-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_callable_selector_source_arity
        COMMAND oreslang-native-callable-selector-test)
    set_tests_properties(native_callable_selector_source_arity PROPERTIES TIMEOUT 10)
endif()

# Host-only structured Task cancellation authority. This mirrors the Java
# stage-1 runtime primitive only: it is NOT source-level task lowering,
# scheduler continuation integration, or proof that get_done is quiescent.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-task-cancellation-test
        tests/native_task_cancellation_test.cpp)
    target_include_directories(oreslang-native-task-cancellation-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-task-cancellation-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-task-cancellation-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-task-cancellation-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_task_cancellation_tree_host_only
        COMMAND oreslang-native-task-cancellation-test)
    set_tests_properties(native_task_cancellation_tree_host_only
        PROPERTIES TIMEOUT 30)
endif()

# Task authority <-> generation-checked continuation integration, with
# producer-ready/cancel and scheduler-claim/cancel adversarial races.
# Tests a real shared host admission primitive, not arbitrary guest Task syntax.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-task-continuation-gate-test
        tests/native_task_continuation_gate_test.cpp
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c)
    target_include_directories(oreslang-native-task-continuation-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-task-continuation-gate-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-task-continuation-gate-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-task-continuation-gate-test PRIVATE
        "$<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>"
        "$<$<COMPILE_LANG_AND_ID:C,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>")
    add_test(NAME native_task_continuation_cancel_ready_races
        COMMAND oreslang-native-task-continuation-gate-test)
    set_tests_properties(native_task_continuation_cancel_ready_races
        PROPERTIES TIMEOUT 60)
endif()

# Root effect policy forward-port (#273); host substrate only.
if(BUILD_TESTING)
    add_executable(oreslang-root-failure-boundary-test tests/root_failure_boundary_test.cpp)
    target_include_directories(oreslang-root-failure-boundary-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-root-failure-boundary-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-root-failure-boundary-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_nonactor_root_failure_boundary COMMAND oreslang-root-failure-boundary-test)
endif()

# #769: host-only synchronous logical snapshot of typed failure ancestry.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-failure-trace-test
        tests/native_failure_trace_test.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c
        src/main/cpp/oreslang/runtime/NativeLogicalTrace.c
        src/main/cpp/oreslang/runtime/NativeFailureTrace.c)
    target_include_directories(oreslang-native-failure-trace-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-failure-trace-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-failure-trace-test PRIVATE $<$<C_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_failure_trace_capture_and_render COMMAND oreslang-native-failure-trace-test)
    set_tests_properties(native_failure_trace_capture_and_render PROPERTIES TIMEOUT 30)
endif()

# #170: independently check atomic nested actor adoption, host-only.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-child-adoption-test tests/native_actor_child_adoption_test.cpp)
    target_include_directories(oreslang-native-actor-child-adoption-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-child-adoption-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-actor-child-adoption-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-actor-child-adoption-test PRIVATE $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror>)
    add_test(NAME native_actor_child_adoption COMMAND oreslang-native-actor-child-adoption-test)
endif()

# Reconciled #73: String wire contracts remain deliberately unsupported by
# native int-only contract lowering; assert diagnostic without Python.
if(BUILD_TESTING)
    foreach(fixture IN ITEMS clients-core-client-contract.ores ores-interfaces-route-contract.ores)
        add_test(NAME "native_string_client_contract_rejected_${fixture}"
            COMMAND "${CMAKE_COMMAND}"
                "-DORES_COMPILER=$<TARGET_FILE:oreslang-llvmc>"
                "-DORES_FIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures/${fixture}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/check_string_contract_rejection.cmake")
    endforeach()
endif()

# PR #113 scoped UTF-8/JSON host C implementation: NOT native guest String ABI.
if(BUILD_TESTING)
  add_executable(oreslang-native-string-host-test
    tests/native_string_test.c
    src/main/cpp/oreslang/runtime/NativeString.c)
  target_include_directories(oreslang-native-string-host-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(oreslang-native-string-host-test PRIVATE c_std_11)
  target_compile_options(oreslang-native-string-host-test PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
  add_test(NAME native_string_utf8_json_host_contract
    COMMAND oreslang-native-string-host-test)
  set_tests_properties(native_string_utf8_json_host_contract PROPERTIES TIMEOUT 30)

  add_executable(oreslang-native-cstring-host-test
    tests/native_cstring_test.c
    src/main/cpp/oreslang/runtime/NativeCString.c
    src/main/cpp/oreslang/runtime/NativeString.c)
  target_include_directories(oreslang-native-cstring-host-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
  target_compile_features(oreslang-native-cstring-host-test PRIVATE c_std_11)
  target_compile_options(oreslang-native-cstring-host-test PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror>)
  add_test(NAME native_string_cstring_explicit_length_contract
    COMMAND oreslang-native-cstring-host-test)
  set_tests_properties(native_string_cstring_explicit_length_contract PROPERTIES TIMEOUT 30)
endif()


# Host reactor operation lifecycle: logical Future settlement is separate from
# physical registration/callback retirement. Actor teardown may reclaim only
# after ready_to_reclaim() becomes true.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-async-io-operation-lifecycle-test
        tests/async_io_operation_lifecycle_test.cpp)
    target_include_directories(oreslang-async-io-operation-lifecycle-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-async-io-operation-lifecycle-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-async-io-operation-lifecycle-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-async-io-operation-lifecycle-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME async_io_operation_lifecycle_races
        COMMAND oreslang-async-io-operation-lifecycle-test)
    set_tests_properties(async_io_operation_lifecycle_races PROPERTIES TIMEOUT 30)

    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        add_executable(oreslang-async-io-epoll-operation-test
            tests/async_io_epoll_operation_test.cpp)
        target_include_directories(oreslang-async-io-epoll-operation-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-async-io-epoll-operation-test PRIVATE cxx_std_17)
        target_link_libraries(oreslang-async-io-epoll-operation-test PRIVATE Threads::Threads)
        target_compile_options(oreslang-async-io-epoll-operation-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        add_test(NAME async_io_epoll_operation_readiness
            COMMAND oreslang-async-io-epoll-operation-test)
        set_tests_properties(async_io_epoll_operation_readiness PROPERTIES TIMEOUT 30)
    endif()
endif()


# Async I/O continuation bridge: scheduler-visible READY is published only
# after the native operation's registration/callback has physically retired.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-async-io-continuation-gate-test
        tests/async_io_continuation_gate_test.cpp
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c)
    target_include_directories(oreslang-async-io-continuation-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-async-io-continuation-gate-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-async-io-continuation-gate-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-async-io-continuation-gate-test PRIVATE
        "$<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>"
        "$<$<COMPILE_LANG_AND_ID:C,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>")
    add_test(NAME async_io_continuation_retirement_gate
        COMMAND oreslang-async-io-continuation-gate-test)
    set_tests_properties(async_io_continuation_retirement_gate PROPERTIES TIMEOUT 60)
endif()


# Linux end-to-end host proof: socketpair -> epoll readiness -> physical
# retirement -> continuation READY -> scheduler claim.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-async-io-epoll-continuation-test
        tests/async_io_epoll_continuation_test.cpp
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c)
    target_include_directories(oreslang-async-io-epoll-continuation-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-async-io-epoll-continuation-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-async-io-epoll-continuation-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-async-io-epoll-continuation-test PRIVATE
        "$<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>"
        "$<$<COMPILE_LANG_AND_ID:C,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>")
    add_test(NAME async_io_epoll_continuation_retirement
        COMMAND oreslang-async-io-epoll-continuation-test)
    set_tests_properties(async_io_epoll_continuation_retirement PROPERTIES TIMEOUT 60)
endif()


# Actor lifecycle integration: an admitted native I/O operation holds the exact
# actor incarnation's resource loan until its kernel registration and callback
# are physically retired. get_done() must remain pending before that point.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-async-io-actor-lifetime-pin-test
        tests/async_io_actor_lifetime_pin_test.cpp)
    target_include_directories(oreslang-async-io-actor-lifetime-pin-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-async-io-actor-lifetime-pin-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-async-io-actor-lifetime-pin-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-async-io-actor-lifetime-pin-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME async_io_actor_done_physical_retirement
        COMMAND oreslang-async-io-actor-lifetime-pin-test)
    set_tests_properties(async_io_actor_done_physical_retirement PROPERTIES TIMEOUT 60)
endif()

# Host/runtime select linearization substrate. This proves exactly-one winner,
# late-arm loser revocation and Task cancellation integration only; source
# select syntax remains fail-closed until Channel/Future lowering is wired.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-select-arbiter-test
        tests/native_select_arbiter_test.cpp)
    target_include_directories(oreslang-native-select-arbiter-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-select-arbiter-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-select-arbiter-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-select-arbiter-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_select_exactly_one_winner_and_cleanup
        COMMAND oreslang-native-select-arbiter-test)
    set_tests_properties(native_select_exactly_one_winner_and_cleanup
        PROPERTIES TIMEOUT 30)
endif()

# Native select runtime continuation stack: bounded i64 channel, immutable
# Future readiness, and actor-generation execution fencing. Source select
# lowering remains fail-closed until compiler ownership/suspension wiring lands.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)

    add_executable(oreslang-native-selectable-i64-channel-test
        tests/native_selectable_i64_channel_test.cpp)
    target_include_directories(oreslang-native-selectable-i64-channel-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-selectable-i64-channel-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-selectable-i64-channel-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-selectable-i64-channel-test PRIVATE Threads::Threads)
    add_test(NAME native_selectable_i64_channel_transaction
        COMMAND oreslang-native-selectable-i64-channel-test)
    set_tests_properties(native_selectable_i64_channel_transaction PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-selectable-i64-future-test
        tests/native_selectable_i64_future_test.cpp)
    target_include_directories(oreslang-native-selectable-i64-future-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-selectable-i64-future-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-selectable-i64-future-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_selectable_i64_future_terminal_readiness
        COMMAND oreslang-native-selectable-i64-future-test)
    set_tests_properties(native_selectable_i64_future_terminal_readiness PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-select-continuation-fence-test
        tests/native_select_continuation_fence_test.cpp)
    target_include_directories(oreslang-native-select-continuation-fence-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-select-continuation-fence-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-select-continuation-fence-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-select-continuation-fence-test PRIVATE Threads::Threads)
    add_test(NAME native_select_continuation_generation_fence
        COMMAND oreslang-native-select-continuation-fence-test)
    set_tests_properties(native_select_continuation_generation_fence PROPERTIES TIMEOUT 30)
endif()

# C ABI bridge over the fixed two-arm NativeSelectArbiter (host-only).
if(BUILD_TESTING)
    add_executable(oreslang-native-select-c-abi-test
        tests/native_select_c_abi_test.cpp
        src/main/cpp/oreslang/runtime/NativeSelectAbi.cpp)
    target_include_directories(oreslang-native-select-c-abi-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-select-c-abi-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-select-c-abi-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_select_c_abi_bridge
        COMMAND oreslang-native-select-c-abi-test)
    set_tests_properties(native_select_c_abi_bridge PROPERTIES TIMEOUT 30)
endif()

# HOST-ONLY AI dispatch fencing parity v1; NOT an Oreslang guest / durable store.
if(BUILD_TESTING)
  add_executable(ores_agent_host_dispatch_parity_v1
    contracts/v1/agent-dispatch/agent_host_dispatch_test.cpp)
  target_include_directories(ores_agent_host_dispatch_parity_v1 PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/agent-dispatch")
  target_compile_features(ores_agent_host_dispatch_parity_v1 PRIVATE cxx_std_17)
  if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(ores_agent_host_dispatch_parity_v1 PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME agent_model_dispatch_host_fencing_v1
    COMMAND ores_agent_host_dispatch_parity_v1)
  set_tests_properties(agent_model_dispatch_host_fencing_v1 PROPERTIES TIMEOUT 15)
endif()

# Trusted Linux-POSIX single-host claim v1. OpenSSL is only required for
# this test target; no multi-host CAS, provider receipt, or guest execution.
# macOS/Windows require separately reviewed filesystem durability semantics.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(OpenSSL REQUIRED COMPONENTS Crypto)
  add_executable(ores_agent_durable_local_claim_v1
    contracts/v1/agent-dispatch/agent_durable_claim_test.cpp)
  target_include_directories(ores_agent_durable_local_claim_v1 PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/contracts/v1/agent-dispatch")
  target_link_libraries(ores_agent_durable_local_claim_v1 PRIVATE OpenSSL::Crypto)
  target_compile_features(ores_agent_durable_local_claim_v1 PRIVATE cxx_std_17)
  if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(ores_agent_durable_local_claim_v1 PRIVATE
      -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
  endif()
  add_test(NAME agent_durable_local_process_claim_v1
    COMMAND ores_agent_durable_local_claim_v1)
  set_tests_properties(agent_durable_local_process_claim_v1 PROPERTIES TIMEOUT 30)
endif()

# Compile-time namespace/lifecycle admission parity with Java #2263.
if(BUILD_TESTING)
    add_test(NAME native_namespace_lifecycle_fail_closed
        COMMAND "${CMAKE_COMMAND}"
            "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DCLANG=${ORE_SOURCE_I64_CLANG}"
            "-DWORK_DIR=${CMAKE_CURRENT_BINARY_DIR}/namespace-parity"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/namespace_lifecycle_parity.cmake")
    set_tests_properties(native_namespace_lifecycle_fail_closed PROPERTIES TIMEOUT 60)
endif()


# Host-native actor-local logical I/O substrate. This is not yet source-level
# io.* lowering; it supplies bounded generation-fenced runtime semantics.
if(BUILD_TESTING)
    add_executable(oreslang-native-actor-io-envelope-test
        tests/native_actor_io_envelope_test.cpp)
    target_include_directories(oreslang-native-actor-io-envelope-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-actor-io-envelope-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-actor-io-envelope-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_io_bounded_generation_fence
        COMMAND oreslang-native-actor-io-envelope-test)
    set_tests_properties(native_actor_io_bounded_generation_fence PROPERTIES TIMEOUT 20)
endif()


# Dedicated semantic-admission test: unsupported new declarations must fail
# before LLVM IR or native artifacts are emitted, never as generic parse errors.
if(BUILD_TESTING)
    add_test(NAME native_state_container_virtual_actor_source_admission
        COMMAND "${CMAKE_COMMAND}"
          "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
          "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/state-container-virtual-actor-admission"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/state_container_virtual_actor_admission.cmake")
    set_tests_properties(native_state_container_virtual_actor_source_admission
        PROPERTIES TIMEOUT 60)
endif()

# Host-only POSIX pthread reference for proposed portable Thread API.
# This is not guest source lowering, TaskLocal or ActorLocal support.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-thread-v1-test
        tests/native_thread_v1_test.c
        src/main/cpp/oreslang/runtime/NativeThreadV1.c)
    set_target_properties(oreslang-native-thread-v1-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS OFF)
    target_compile_definitions(oreslang-native-thread-v1-test PRIVATE
        ORES_NATIVE_THREAD_TEST_ID_EXHAUSTION=1)
    target_include_directories(oreslang-native-thread-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_options(oreslang-native-thread-v1-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-thread-v1-test PRIVATE Threads::Threads)
    add_test(NAME native_thread_context_tls_join_host_only
        COMMAND oreslang-native-thread-v1-test)
    set_tests_properties(native_thread_context_tls_join_host_only PROPERTIES TIMEOUT 20)
endif()

# Header-only typed Thread capture admission model, not guest AST integration.
if(BUILD_TESTING)
    add_executable(oreslang-native-thread-capture-admission-test
        tests/native_thread_capture_admission_test.cpp)
    target_include_directories(oreslang-native-thread-capture-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-thread-capture-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-thread-capture-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_thread_capture_admission_model_only
        COMMAND oreslang-native-thread-capture-admission-test)
endif()

# Bounded host-only join/reaper model: not Task/Future guest lowering.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-thread-join-reaper-test
        tests/native_thread_join_reaper_v1_test.cpp
        src/main/cpp/oreslang/runtime/NativeThreadV1.c)
    target_include_directories(oreslang-native-thread-join-reaper-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-thread-join-reaper-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-thread-join-reaper-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-thread-join-reaper-test PRIVATE Threads::Threads)
    add_test(NAME native_thread_join_reaper_host_only
        COMMAND oreslang-native-thread-join-reaper-test)
endif()

# M0 AttoLang .atto source admission for std.thread. Real LLVM parser
# invocation is REQUIRED: unlike host-only pthread references this test
# rejects ten source forms (including bare references), preserves thread(),
# and prohibits IR/side-artifact emission on unsupported operations.
if(BUILD_TESTING)
    add_test(NAME native_thread_source_admission_m0
        COMMAND "${CMAKE_COMMAND}"
            "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
            "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/native-thread-source-admission-m0"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/native_thread_source_admission_m0.cmake")
    set_tests_properties(native_thread_source_admission_m0
        PROPERTIES TIMEOUT 60)
endif()

# LLVM threading parity host substrate (compiler guest lowering remains gated).
# Separate tests preserve the current parent CMake source and .atto admission.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-thread-scope-v1-test
        tests/native_thread_scope_v1_test.c
        src/main/cpp/oreslang/runtime/NativeThreadV1.c)
    set_target_properties(oreslang-native-thread-scope-v1-test PROPERTIES
        C_STANDARD 11 C_STANDARD_REQUIRED YES C_EXTENSIONS OFF)
    target_include_directories(oreslang-native-thread-scope-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_definitions(oreslang-native-thread-scope-v1-test PRIVATE
        ORES_NATIVE_THREAD_TEST_ID_EXHAUSTION=1)
    target_compile_options(oreslang-native-thread-scope-v1-test PRIVATE
        $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-thread-scope-v1-test PRIVATE Threads::Threads)
    add_test(NAME native_thread_scope_quota_join_host_only
        COMMAND oreslang-native-thread-scope-v1-test)
    set_tests_properties(native_thread_scope_quota_join_host_only PROPERTIES TIMEOUT 20)
endif()

# Host-only Task join notification adapter: cancellation/completion arbitration.
# This does not compile .atto programs or exercise real guest suspension.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-join-task-wait-test
        tests/native_join_task_wait_v1_test.cpp
        src/main/cpp/oreslang/runtime/NativeThreadV1.c)
    target_include_directories(oreslang-native-join-task-wait-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-join-task-wait-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-join-task-wait-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    target_link_libraries(oreslang-native-join-task-wait-test PRIVATE Threads::Threads)
    add_test(NAME native_join_task_wait_notification_host_only
        COMMAND oreslang-native-join-task-wait-test)
    set_tests_properties(native_join_task_wait_notification_host_only
        PROPERTIES TIMEOUT 30)
endif()

# Host-only pthread join -> Task cancellation/READY admission -> carrier claim.
# This uses actual native continuation stores, not source .atto lowering.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-join-task-continuation-bridge-test
        tests/native_join_task_continuation_bridge_v1_test.cpp
        src/main/cpp/oreslang/runtime/NativeThreadV1.c
        src/main/cpp/oreslang/runtime/NativeExecutionContextStore.c
        src/main/cpp/oreslang/runtime/NativeContinuationStore.c
        src/main/cpp/oreslang/runtime/NativeAwaitTraceBridge.c)
    target_include_directories(oreslang-native-join-task-continuation-bridge-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-join-task-continuation-bridge-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-join-task-continuation-bridge-test PRIVATE
        "$<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>"
        "$<$<COMPILE_LANG_AND_ID:C,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>")
    target_link_libraries(oreslang-native-join-task-continuation-bridge-test PRIVATE Threads::Threads)
    add_test(NAME native_join_task_continuation_bridge_host_only
        COMMAND oreslang-native-join-task-continuation-bridge-test)
    set_tests_properties(native_join_task_continuation_bridge_host_only
        PROPERTIES TIMEOUT 45)
endif()

# Opt-in shared polling/execution host substrate. Not source-actor qualification.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(Threads REQUIRED)
  add_executable(ores-shared-reactor-pool-test tests/shared_reactor_pool_test.cpp)
  target_include_directories(ores-shared-reactor-pool-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime")
  target_compile_features(ores-shared-reactor-pool-test PRIVATE cxx_std_17)
  target_compile_options(ores-shared-reactor-pool-test PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
  target_link_libraries(ores-shared-reactor-pool-test PRIVATE Threads::Threads)
  add_test(NAME shared_reactor_pool_host COMMAND ores-shared-reactor-pool-test)
  set_tests_properties(shared_reactor_pool_host PROPERTIES TIMEOUT 30)
  add_executable(ores-shared-worker-layout-benchmark tests/shared_worker_layout_benchmark.cpp)
  target_compile_features(ores-shared-worker-layout-benchmark PRIVATE cxx_std_17)
  target_link_libraries(ores-shared-worker-layout-benchmark PRIVATE Threads::Threads)
endif()

# Host-only logical HTTP RequestLocal: independent of native pthread TLS.
# Real actor scheduler and cancellation are exercised; .atto compiler
# lowering and true Task continuation migration are separate release gates.
if(BUILD_TESTING AND UNIX)
    find_package(Threads REQUIRED)
    add_executable(oreslang-request-context-local-v1-test
        tests/native_request_context_local_v1_test.cpp
        src/main/cpp/oreslang/runtime/NativeActorScheduler.c)
    target_include_directories(oreslang-request-context-local-v1-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-request-context-local-v1-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-request-context-local-v1-test PRIVATE
        "$<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>"
        "$<$<COMPILE_LANG_AND_ID:C,GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Werror;-UNDEBUG>")
    target_link_libraries(oreslang-request-context-local-v1-test PRIVATE Threads::Threads)
    add_test(NAME native_request_context_local_host_only
        COMMAND oreslang-request-context-local-v1-test)
    set_tests_properties(native_request_context_local_host_only
        PROPERTIES TIMEOUT 30)
endif()

# Byte-identical 50-program Java/JS/LLVM scalar floor. The host manifest gate
# never reports backend parity: only the second test executes a native guest.
# The LLVM compiler must lower each unchanged program to IR, and Clang must
# link and execute all 50 .atto programs. Parser-only tests do not qualify.
if(BUILD_TESTING AND UNIX)
    find_program(ATTO_SCALAR50_CLANG NAMES clang)
    set(ATTO_EXPECTED_SOURCE_SHA "" CACHE STRING
        "Optional exact checked-out source SHA for the AttoLang native parity receipt")
    add_test(NAME atto_shared_scalar50_manifest_host_only
        COMMAND "${CMAKE_COMMAND}"
          "-DMODE=validate"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/shared-scalar50-v1.tsv"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_shared_scalar50_native.cmake")
    set_tests_properties(atto_shared_scalar50_manifest_host_only
        PROPERTIES TIMEOUT 15 LABELS "host-only;parity-candidate")
    add_test(NAME atto_shared_scalar50_native_guest
        COMMAND "${CMAKE_COMMAND}"
          "-DMODE=native"
          "-DSOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
          "-DEXPECTED_SOURCE_SHA=${ATTO_EXPECTED_SOURCE_SHA}"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/shared-scalar50-v1.tsv"
          "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
          "-DCLANG=${ATTO_SCALAR50_CLANG}"
          "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/atto-shared-scalar50-native"
          "-DREPORT=${CMAKE_CURRENT_BINARY_DIR}/atto-shared-scalar50-native/llvm-scalar50-native-receipt.json"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_shared_scalar50_native.cmake")
    set_tests_properties(atto_shared_scalar50_native_guest
        PROPERTIES TIMEOUT 600 LABELS "native-guest;source-execution;atto-parity"
        RUN_SERIAL TRUE)
endif()


# Full-width checked-i64 JS->LLVM parity candidates. 37 source expressions
# must compare at full i64 width inside compiled .atto guests; thirteen
# overflow cases must lower to a checked intrinsic and execute a real trap.
# The manifest-only admission test is NOT native execution.
if(BUILD_TESTING AND UNIX)
    add_test(NAME atto_checked_i64_manifest_host_only
        COMMAND "${CMAKE_COMMAND}" "-DMODE=validate"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/js-checked-i64-candidates.json"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_checked_i64_native.cmake")
    set_tests_properties(atto_checked_i64_manifest_host_only
        PROPERTIES TIMEOUT 15 LABELS "host-only;checked-i64-candidate")
    add_test(NAME atto_checked_i64_native_guest
        COMMAND "${CMAKE_COMMAND}" "-DMODE=native"
          "-DSOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
          "-DEXPECTED_SOURCE_SHA=${ATTO_EXPECTED_SOURCE_SHA}"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/js-checked-i64-candidates.json"
          "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
          "-DCLANG=${ATTO_SCALAR50_CLANG}"
          "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/atto-checked-i64-native"
          "-DREPORT=${CMAKE_CURRENT_BINARY_DIR}/atto-checked-i64-native/llvm-checked-i64-native-receipt.json"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_checked_i64_native.cmake")
    set_tests_properties(atto_checked_i64_native_guest
        PROPERTIES TIMEOUT 900 LABELS "native-guest;checked-i64;atto-parity"
        RUN_SERIAL TRUE)
endif()


# Direct original-source checked-i64 parity. The LLVM native C wrapper is i32
# and limits OS exit status; only the exported @__oreslang_guest_main() is i64.
# Keep the original source bytes completely unchanged, compile emitted IR to
# an ELF object, rename the i32 OS main via objcopy and invoke the i64 guest
# symbol from a fixed C oracle. No AttoLang expression rewrite is permitted.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_program(ATTO_NATIVE_OBJCOPY NAMES objcopy llvm-objcopy)
    add_test(NAME atto_i64_original_manifest_host_only
        COMMAND "${CMAKE_COMMAND}" "-DMODE=validate"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/js-checked-i64-candidates.json"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_i64_original_native.cmake")
    set_tests_properties(atto_i64_original_manifest_host_only
        PROPERTIES TIMEOUT 20 LABELS "host-only;original-source-i64")
    add_test(NAME atto_i64_original_native_guest
        COMMAND "${CMAKE_COMMAND}" "-DMODE=native"
          "-DSOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
          "-DEXPECTED_SOURCE_SHA=${ATTO_EXPECTED_SOURCE_SHA}"
          "-DCORPUS=${CMAKE_CURRENT_SOURCE_DIR}/contracts/atto-parity-v1/js-checked-i64-candidates.json"
          "-DCOMPILER=$<TARGET_FILE:oreslang-llvmc>"
          "-DCLANG=${ATTO_SCALAR50_CLANG}"
          "-DOBJCOPY=${ATTO_NATIVE_OBJCOPY}"
          "-DSHIM=${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_i64_original_guest_probe.c"
          "-DWORKDIR=${CMAKE_CURRENT_BINARY_DIR}/atto-i64-original-native"
          "-DREPORT=${CMAKE_CURRENT_BINARY_DIR}/atto-i64-original-native/llvm-original-i64-native-receipt.json"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/atto_i64_original_native.cmake")
    set_tests_properties(atto_i64_original_native_guest
        PROPERTIES TIMEOUT 900 LABELS "native-guest;original-source-i64;atto-parity"
        RUN_SERIAL TRUE)
endif()


if(BUILD_TESTING)
    add_executable(oreslang-native-proxy-arena-pages-test
        tests/native_proxy_request_slab_arena_pages_test.cpp
        src/main/cpp/oreslang/runtime/ActorArena.c)
    target_include_directories(oreslang-native-proxy-arena-pages-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-proxy-arena-pages-test PRIVATE c_std_11 cxx_std_20)
    target_compile_options(oreslang-native-proxy-arena-pages-test PRIVATE
        $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wpedantic -Werror>
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_proxy_slab_arena_pages_and_zeroization
        COMMAND oreslang-native-proxy-arena-pages-test)
    set_tests_properties(native_proxy_slab_arena_pages_and_zeroization
        PROPERTIES TIMEOUT 30)
endif()

# Counted physical callback permits and slab/generation-bound drain receipts.
# Host-only proof: no guest lowering, real epoll integration, or I/O transport.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-proxy-transport-gate-test
        tests/native_proxy_transport_gate_test.cpp)
    target_include_directories(oreslang-native-proxy-transport-gate-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-proxy-transport-gate-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-proxy-transport-gate-test PRIVATE
        cxx_std_20)
    target_compile_options(oreslang-native-proxy-transport-gate-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_proxy_transport_callback_gate_races
        COMMAND oreslang-native-proxy-transport-gate-test)
    set_tests_properties(native_proxy_transport_callback_gate_races
        PROPERTIES TIMEOUT 20)
endif()

# Linux-only host epoll callback ownership proof with a real socketpair.
# The bridge wraps the checked-in native epoll reactor rather than mocking it.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-epoll-transport-bridge-test
        tests/native_epoll_transport_bridge_test.cpp)
    target_include_directories(oreslang-native-epoll-transport-bridge-test
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-transport-bridge-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-epoll-transport-bridge-test
        PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-epoll-transport-bridge-test
        PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_epoll_transport_callback_retirement
        COMMAND oreslang-native-epoll-transport-bridge-test)
    set_tests_properties(native_epoll_transport_callback_retirement
        PROPERTIES TIMEOUT 20)

    add_executable(oreslang-native-epoll-transport-stress-test
        tests/native_epoll_transport_capacity_stress_test.cpp)
    target_include_directories(oreslang-native-epoll-transport-stress-test
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-transport-stress-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-epoll-transport-stress-test
        PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-epoll-transport-stress-test
        PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_epoll_transport_capacity_stale_shutdown_stress
        COMMAND oreslang-native-epoll-transport-stress-test)
    set_tests_properties(native_epoll_transport_capacity_stale_shutdown_stress
        PROPERTIES TIMEOUT 30)

    add_executable(oreslang-native-epoll-bridge-lifetime-test
        tests/native_epoll_bridge_lifetime_test.cpp)
    target_include_directories(oreslang-native-epoll-bridge-lifetime-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-epoll-bridge-lifetime-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-epoll-bridge-lifetime-test PRIVATE
        cxx_std_20)
    target_compile_options(oreslang-native-epoll-bridge-lifetime-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_epoll_bridge_external_handoff_failstop
        COMMAND oreslang-native-epoll-bridge-lifetime-test)
    set_tests_properties(native_epoll_bridge_external_handoff_failstop
        PROPERTIES TIMEOUT 20)
endif()

# Focused Linux epoll actor-owned FD lifetime contract without LLVM frontend or
# the full VM bootstrap. Compiles the REAL reactor/manager + exact callback
# ledger, never the lightweight external-adapter mock.
if(BUILD_TESTING AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-actor-nio-fd-lease-test
        tests/native_actor_nio_fd_lease_test.cpp)
    target_include_directories(oreslang-native-actor-nio-fd-lease-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-actor-nio-fd-lease-test PRIVATE
        Threads::Threads)
    target_compile_features(oreslang-native-actor-nio-fd-lease-test
        PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-actor-nio-fd-lease-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_actor_nio_fd_lease_exclusive_rearm
        COMMAND oreslang-native-actor-nio-fd-lease-test)
    set_tests_properties(native_actor_nio_fd_lease_exclusive_rearm
        PROPERTIES TIMEOUT 20)
endif()


# Stacked OresVM actor resource-retirement acceptance gate.
# Accounting only: physical external I/O retirement must be signaled by its adapter.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-oresvm-resource-retirement-test
        tests/native_oresvm_resource_retirement_test.cpp)
    target_include_directories(oreslang-native-oresvm-resource-retirement-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-oresvm-resource-retirement-test PRIVATE cxx_std_17)
    target_link_libraries(oreslang-native-oresvm-resource-retirement-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-oresvm-resource-retirement-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_oresvm_actor_resource_retirement
        COMMAND oreslang-native-oresvm-resource-retirement-test)
    set_tests_properties(native_oresvm_actor_resource_retirement PROPERTIES TIMEOUT 30)
endif()

# Host-only persistence sidecar model: NOT .ores actor source execution or durable storage.
if(BUILD_TESTING)
    add_executable(oreslang-native-batch-persistence-contract-test
        tests/native_batch_persistence_test.cpp)
    target_include_directories(oreslang-native-batch-persistence-contract-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-batch-persistence-contract-test PRIVATE cxx_std_17)
    find_package(Threads REQUIRED)
    target_link_libraries(oreslang-native-batch-persistence-contract-test PRIVATE Threads::Threads)
    target_compile_options(oreslang-native-batch-persistence-contract-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_batch_persistence_host_only_contract
        COMMAND oreslang-native-batch-persistence-contract-test)
    set_tests_properties(native_batch_persistence_host_only_contract PROPERTIES TIMEOUT 30)
endif()


# REAL SQLite WAL/FULL host provider. The test executes native C++ and SQLite,
# but is not compiler-generated Oreslang guest execution.
if(BUILD_TESTING AND UNIX)
    find_package(SQLite3 QUIET)
    if(SQLite3_FOUND)
        add_executable(oreslang-native-sqlite-persistence-test
            tests/native_sqlite_persistence_test.cpp)
        target_include_directories(oreslang-native-sqlite-persistence-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime")
        target_compile_features(oreslang-native-sqlite-persistence-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-native-sqlite-persistence-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        target_link_libraries(oreslang-native-sqlite-persistence-test PRIVATE SQLite::SQLite3)
        add_test(NAME native_sqlite_wal_actor_state_crash_recovery_host_only
            COMMAND oreslang-native-sqlite-persistence-test)
        set_tests_properties(native_sqlite_wal_actor_state_crash_recovery_host_only
            PROPERTIES TIMEOUT 60)
    endif()
endif()


# Native actor-owned memory + durable storage reactivation HOST INTEGRATION.
# Executes real OwnedHeap and SQLite; does not run compiler-generated .ores guests.
if(BUILD_TESTING AND UNIX)
    find_package(SQLite3 QUIET)
    if(SQLite3_FOUND)
        add_executable(oreslang-actor-arena-persistence-restart-test
            tests/native_actor_arena_persistence_reactivation_test.cpp)
        target_include_directories(oreslang-actor-arena-persistence-restart-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-actor-arena-persistence-restart-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-actor-arena-persistence-restart-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        target_link_libraries(oreslang-actor-arena-persistence-restart-test PRIVATE SQLite::SQLite3)
        add_test(NAME native_actor_arena_sqlite_reactivation_host_only
            COMMAND oreslang-actor-arena-persistence-restart-test)
        set_tests_properties(native_actor_arena_sqlite_reactivation_host_only PROPERTIES TIMEOUT 45)
    endif()
endif()

# Native host event sourcing fault: _exit after SQLite event COMMIT before ACK.
# This is not compiled .atto guest or actor scheduler execution.
if(BUILD_TESTING AND UNIX)
    find_package(SQLite3 QUIET)
    if(SQLite3_FOUND)
        add_executable(oreslang-native-event-sourced-fenced-recovery-test
            tests/native_event_sourced_fenced_recovery_test.cpp)
        target_include_directories(oreslang-native-event-sourced-fenced-recovery-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-native-event-sourced-fenced-recovery-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-native-event-sourced-fenced-recovery-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        target_link_libraries(oreslang-native-event-sourced-fenced-recovery-test PRIVATE SQLite::SQLite3)
        add_test(NAME native_event_sourced_crash_after_commit_fenced_replay_host_only
            COMMAND oreslang-native-event-sourced-fenced-recovery-test)
        set_tests_properties(native_event_sourced_crash_after_commit_fenced_replay_host_only
            PROPERTIES TIMEOUT 45)
    endif()
endif()

# Durable command inbox + multi-event journal host integration only.
if(BUILD_TESTING AND UNIX)
    find_package(SQLite3 QUIET)
    if(SQLite3_FOUND)
        add_executable(oreslang-native-durable-command-inbox-test
            tests/native_durable_command_inbox_test.cpp)
        target_include_directories(oreslang-native-durable-command-inbox-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
        target_compile_features(oreslang-native-durable-command-inbox-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-native-durable-command-inbox-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        target_link_libraries(oreslang-native-durable-command-inbox-test PRIVATE SQLite::SQLite3)
        add_test(NAME native_durable_command_inbox_atomic_batch_host_only
            COMMAND oreslang-native-durable-command-inbox-test)
        set_tests_properties(native_durable_command_inbox_atomic_batch_host_only
            PROPERTIES TIMEOUT 45)
    endif()
endif()

# Native v2 durable logical-command host-only conformance: real WAL/FULL and SIGKILL.
# This does not compile an AttoLang/Oreslang guest actor.
if(BUILD_TESTING AND UNIX)
    find_package(SQLite3 QUIET)
    if(SQLite3_FOUND)
        add_executable(oreslang-native-command-v2-sqlite-test
            tests/native_command_persistence_v2_test.cpp)
        target_include_directories(oreslang-native-command-v2-sqlite-test PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/oreslang/runtime")
        target_compile_features(oreslang-native-command-v2-sqlite-test PRIVATE cxx_std_17)
        target_compile_options(oreslang-native-command-v2-sqlite-test PRIVATE
            $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
        target_link_libraries(oreslang-native-command-v2-sqlite-test PRIVATE SQLite::SQLite3)
        add_test(NAME native_v2_sqlite_command_epoch_retry_crash_host_only
            COMMAND oreslang-native-command-v2-sqlite-test)
        set_tests_properties(native_v2_sqlite_command_epoch_retry_crash_host_only
            PROPERTIES TIMEOUT 60)
    endif()
endif()


# Allocation-free bounded pool primitive, host-only (NOT compiled .atto guest).
# Keep separate from native guest compiler/actor parity CI claims.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-fixed-buffer-pool-test
        tests/native_fixed_buffer_pool_test.cpp)
    target_include_directories(oreslang-native-fixed-buffer-pool-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-fixed-buffer-pool-test PRIVATE Threads::Threads)
    target_compile_features(oreslang-native-fixed-buffer-pool-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-fixed-buffer-pool-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_fixed_buffer_pool_host_only
        COMMAND oreslang-native-fixed-buffer-pool-test)
    set_tests_properties(native_fixed_buffer_pool_host_only PROPERTIES TIMEOUT 30)
endif()


# Separate host-only atomically published buffer-lease mailbox data lane.
# No source guest, scheduler CONTROL or actor sandbox admission claim.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-fixed-buffer-mailbox-test
        tests/native_fixed_buffer_mailbox_test.cpp)
    target_include_directories(oreslang-native-fixed-buffer-mailbox-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-fixed-buffer-mailbox-test PRIVATE Threads::Threads)
    target_compile_features(oreslang-native-fixed-buffer-mailbox-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-fixed-buffer-mailbox-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_fixed_buffer_mailbox_host_only
        COMMAND oreslang-native-fixed-buffer-mailbox-test)
    set_tests_properties(native_fixed_buffer_mailbox_host_only PROPERTIES TIMEOUT 30)
endif()


# Physical actor resource registry loan/handoff bridge for pooled messages.
# HOST ONLY: registry tickets can allocate and do not certify guest AOT.
if(BUILD_TESTING)
    find_package(Threads REQUIRED)
    add_executable(oreslang-native-fixed-buffer-registry-mailbox-test
        tests/native_fixed_buffer_registry_mailbox_test.cpp)
    target_include_directories(oreslang-native-fixed-buffer-registry-mailbox-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_link_libraries(oreslang-native-fixed-buffer-registry-mailbox-test PRIVATE Threads::Threads)
    target_compile_features(oreslang-native-fixed-buffer-registry-mailbox-test PRIVATE cxx_std_20)
    target_compile_options(oreslang-native-fixed-buffer-registry-mailbox-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_fixed_buffer_registry_mailbox_host_only
        COMMAND oreslang-native-fixed-buffer-registry-mailbox-test)
    set_tests_properties(native_fixed_buffer_registry_mailbox_host_only PROPERTIES TIMEOUT 30)
endif()

# Phase-zero typed inverted class MIR admission: no guest .atto lowering or
# actual actor memory operations. Validates guard requirements before codegen.
if(BUILD_TESTING)
    add_executable(oreslang-native-inverted-class-mir-admission-test
        tests/inverted_class_mir_admission_test.cpp)
    target_include_directories(oreslang-native-inverted-class-mir-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-native-inverted-class-mir-admission-test PRIVATE cxx_std_17)
    target_compile_options(oreslang-native-inverted-class-mir-admission-test PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wpedantic -Werror -UNDEBUG>)
    add_test(NAME native_inverted_class_typed_mir_admission
        COMMAND oreslang-native-inverted-class-mir-admission-test)
    set_tests_properties(native_inverted_class_typed_mir_admission PROPERTIES TIMEOUT 30)
endif()


# Source preflight only. This is not guest compilation or native AOT proof.
if(BUILD_TESTING)
    add_executable(oreslang-source-bidi-admission-test tests/source_bidi_admission_test.cpp)
    target_include_directories(oreslang-source-bidi-admission-test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
    target_compile_features(oreslang-source-bidi-admission-test PRIVATE cxx_std_17)
    add_test(NAME llvm_source_bidi_admission_host
        COMMAND oreslang-source-bidi-admission-test)
    set_tests_properties(llvm_source_bidi_admission_host PROPERTIES TIMEOUT 20)
endif()
