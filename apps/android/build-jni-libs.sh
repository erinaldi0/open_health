#!/usr/bin/env bash
# Build the shared Rust core for Android and generate its Kotlin bindings — the
# Android counterpart to apps/ios/build-xcframework.sh.
#
#   ./build-jni-libs.sh              # both ABIs, release
#   ABIS="arm64-v8a" ./build-jni-libs.sh
#   PROFILE=debug ./build-jni-libs.sh
#
# Outputs (both gitignored, like OuraCore.xcframework on iOS):
#   app/src/main/jniLibs/<abi>/liboura_core.so
#   generated/uniffi/oura_core/oura_core.kt      <- checked in, like generated/oura_core.swift
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=android-env.sh
. "$HERE/android-env.sh"

PROFILE="${PROFILE:-release}"
TARGETS="${TARGETS:-aarch64-linux-android x86_64-linux-android}"

# --lib only: the crate also has a `uniffi-bindgen` bin, and cross-compiling that for
# Android is pure waste (it only ever runs on the host).
cargo_flags=(-p oura-core --lib)
[ "$PROFILE" = "release" ] && cargo_flags+=(--release)

# AGP's externalNativeBuild (the `full` flavor's JNI bridge) goes through CMake's Android
# support, which cannot find the NDK's unified sysroot unless the host is x86_64 — see the
# toolchain note in README.md. Materialise the shim NDK for it; build.gradle.kts picks it
# up when present. On an x86_64 host the NDK works as shipped and this is skipped — as it
# is on macOS, whose NDK ships universal (x86_64 + arm64) binaries and has no
# linux-* prebuilt dir for the shim to mirror.
if [ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" != "x86_64" ]; then
    echo "==> shim NDK for Gradle (host $(uname -m) cannot run the NDK toolchain natively)"
    ANDROID_NDK_SHIM="$HERE/.ndk-shim" android_cmake_ndk > /dev/null
fi

echo "==> cross-compiling oura-core (${PROFILE}) for: $TARGETS"
for t in $TARGETS; do
    android_cargo_env "$t"
    cargo_flags+=(--target "$t")
done
( cd "$REPO" && cargo build "${cargo_flags[@]}" )

echo "==> staging jniLibs"
for t in $TARGETS; do
    abi="$(android_abi "$t")"
    src="$REPO/target/$t/$PROFILE/liboura_core.so"
    [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
    # A shared lib links fine with unresolved symbols and only fails at dlopen on the
    # device. SQLite is bundled, so any undefined sqlite3_* means its archive came out
    # empty (e.g. a host `ar` that can't index ELF) — catch that here, not in a crash.
    nm_tool="$(command -v llvm-nm || echo "$NDK_PREBUILT/bin/llvm-nm")"
    if "$nm_tool" -D --undefined-only "$src" | grep -q ' sqlite3_'; then
        echo "$src: SQLite symbols are unresolved — the bundled SQLite was not linked" >&2
        exit 1
    fi
    mkdir -p "$HERE/app/src/main/jniLibs/$abi"
    cp "$src" "$HERE/app/src/main/jniLibs/$abi/liboura_core.so"
    echo "    $abi  $(du -h "$src" | cut -f1)"
done

# UniFFI's library mode reads the metadata out of a HOST dylib, so build one for this
# machine too. Same crate, same #[uniffi::export] surface — only the bindings language
# differs from the Swift path (apps/ios/generated/oura_core.swift).
#
# STRIP=none matters: the workspace release profile sets `strip = true`, which erases
# the UNIFFI_META_* statics the bindgen reads, and it then writes NO files and still
# exits 0. Only this host lib needs them — the shipped .so keeps every runtime symbol
# (uniffi_oura_core_fn_*) when stripped, so the jniLibs above stay small.
echo "==> generating Kotlin bindings"
# env -u: cc-rs reads HOST_CC/HOST_CXX/HOST_AR as the compiler for *host* builds, and
# android-env.sh exports those names for the Android cross toolchain (the NDK's clang on
# macOS, which has no macOS SDK). Drop them so this host build uses the system toolchain.
( cd "$REPO" && env -u HOST_CC -u HOST_CXX -u HOST_AR -u HOST_RANLIB \
    CARGO_PROFILE_RELEASE_STRIP=none cargo build -p oura-core --release )
case "$(uname -s)" in
    Darwin) host_lib="liboura_core.dylib" ;;
    *)      host_lib="liboura_core.so" ;;
esac
rm -rf "$HERE/generated/uniffi"
mkdir -p "$HERE/generated"
( cd "$REPO" && ./target/release/uniffi-bindgen generate \
    --library \
    --language kotlin \
    --no-format \
    --out-dir "$HERE/generated" \
    "$REPO/target/release/$host_lib" )
[ -f "$HERE/generated/uniffi/oura_core/oura_core.kt" ] || {
    echo "bindgen wrote nothing — is the host lib stripped?" >&2; exit 1; }

find "$HERE/generated" -name '*.kt' -exec echo "    {}" \;
echo "==> done"
