#!/usr/bin/env python3
"""Cross-platform (Windows / Linux / macOS) ARM64 native builder for Kuroko Teleport."""

import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(ROOT)
BUILD_DIR = os.path.join(ROOT, "build", "arm64-v8a")
IS_WIN = sys.platform == "win32"
EXE = ".exe" if IS_WIN else ""


def version_key(name: str):
    """Natural version sort key that ranks stable releases ahead of prereleases without TypeError."""
    m = re.match(r"^(\d+(?:\.\d+)*)(?:[-_](.+))?$", name)
    if m:
        nums = tuple(int(x) for x in m.group(1).split("."))
        is_stable = 1 if m.group(2) is None else 0
        suffix = m.group(2).lower() if m.group(2) else ""
        return (1, nums, is_stable, suffix)
    return (0, (), 0, name.lower())


def load_local_properties(repo_root: str) -> dict:
    """Parse optional Gradle local.properties (unescaping Java .properties backslash escapes)."""
    path = os.path.join(repo_root, "local.properties")
    props = {}
    if not os.path.isfile(path):
        return props
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            m = re.match(r"^([^=:]+?)\s*[=:]\s*(.*)$", line)
            if not m:
                continue
            key = m.group(1).strip()
            val = re.sub(r"\\([:\\= ])", r"\1", m.group(2).strip())
            props[key] = val
    return props


def find_android_sdk(local_props: dict) -> str:
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        local_props.get("sdk.dir"),
    ]
    home = os.path.expanduser("~")
    if IS_WIN:
        local_app = os.environ.get("LOCALAPPDATA")
        if local_app:
            candidates.append(os.path.join(local_app, "Android", "Sdk"))
    elif sys.platform == "darwin":
        candidates.append(os.path.join(home, "Library", "Android", "sdk"))
    else:
        candidates.append(os.path.join(home, "Android", "Sdk"))

    for c in candidates:
        if c and os.path.isdir(c):
            return os.path.abspath(c)
    raise FileNotFoundError(
        "Android SDK directory not found. Set ANDROID_HOME / ANDROID_SDK_ROOT or sdk.dir in local.properties."
    )


def find_android_ndk(sdk_dir: str, local_props: dict) -> str:
    def is_valid_ndk(path: str) -> bool:
        return bool(path) and os.path.isfile(os.path.join(path, "build", "cmake", "android.toolchain.cmake"))

    for env_key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        val = os.environ.get(env_key)
        if is_valid_ndk(val):
            return os.path.abspath(val)

    local_ndk = local_props.get("ndk.dir")
    if is_valid_ndk(local_ndk):
        return os.path.abspath(local_ndk)

    ndk_root = os.path.join(sdk_dir, "ndk")
    if os.path.isdir(ndk_root):
        versions = sorted(
            (d for d in os.listdir(ndk_root) if is_valid_ndk(os.path.join(ndk_root, d))),
            key=version_key,
            reverse=True,
        )
        if versions:
            return os.path.join(ndk_root, versions[0])

    bundle = os.path.join(sdk_dir, "ndk-bundle")
    if is_valid_ndk(bundle):
        return bundle

    raise FileNotFoundError(
        f"Android NDK not found under {ndk_root}. Set ANDROID_NDK_HOME / ANDROID_NDK_ROOT or install an NDK."
    )


def find_cmake_and_ninja(sdk_dir: str) -> tuple[str, str]:
    cmake_bin = None
    ninja_bin = None

    cmake_root = os.path.join(sdk_dir, "cmake")
    if os.path.isdir(cmake_root):
        versions = sorted(
            (d for d in os.listdir(cmake_root) if os.path.isdir(os.path.join(cmake_root, d))),
            key=version_key,
            reverse=True,
        )
        for ver in versions:
            bin_dir = os.path.join(cmake_root, ver, "bin")
            cand_cmake = os.path.join(bin_dir, f"cmake{EXE}")
            cand_ninja = os.path.join(bin_dir, f"ninja{EXE}")
            if cmake_bin is None and os.path.isfile(cand_cmake):
                cmake_bin = cand_cmake
                if os.path.isfile(cand_ninja):
                    ninja_bin = cand_ninja
            elif ninja_bin is None and os.path.isfile(cand_ninja):
                ninja_bin = cand_ninja
            if cmake_bin and ninja_bin:
                break

    if cmake_bin is None:
        cmake_bin = shutil.which("cmake")
    if ninja_bin is None:
        ninja_bin = shutil.which("ninja")

    if not cmake_bin:
        raise FileNotFoundError(f"CMake not found in {cmake_root} or PATH.")
    if not ninja_bin:
        raise FileNotFoundError(f"Ninja not found in {cmake_root} or PATH.")
    return cmake_bin, ninja_bin


def detect_android_platform(ndk_dir: str, target_api: int = 35) -> str:
    """Use android-35 when supported by NDK sysroot, otherwise highest supported API <= target_api."""
    prebuilt_root = os.path.join(ndk_dir, "toolchains", "llvm", "prebuilt")
    if os.path.isdir(prebuilt_root):
        for host_tag in os.listdir(prebuilt_root):
            lib_dir = os.path.join(prebuilt_root, host_tag, "sysroot", "usr", "lib", "aarch64-linux-android")
            if os.path.isdir(lib_dir):
                apis = sorted(
                    (int(d) for d in os.listdir(lib_dir) if d.isdigit() and os.path.isdir(os.path.join(lib_dir, d))),
                    reverse=True,
                )
                for api in apis:
                    if api <= target_api:
                        return f"android-{api}"
    return f"android-{target_api}"


def main():
    local_props = load_local_properties(REPO_ROOT)
    sdk_dir = find_android_sdk(local_props)
    ndk_dir = find_android_ndk(sdk_dir, local_props)
    cmake_bin, ninja_bin = find_cmake_and_ninja(sdk_dir)
    toolchain = os.path.join(ndk_dir, "build", "cmake", "android.toolchain.cmake")
    android_platform = detect_android_platform(ndk_dir, target_api=35)

    print(f"Using SDK:       {sdk_dir}")
    print(f"Using NDK:       {ndk_dir} ({android_platform})")
    print(f"Using CMake:     {cmake_bin}")
    print(f"Using Ninja:     {ninja_bin}")

    # Clean build directory if CMake cache is absent or belongs to a different project/toolchain
    cache_path = os.path.join(BUILD_DIR, "CMakeCache.txt")
    if os.path.isdir(BUILD_DIR):
        stale = True
        if os.path.isfile(cache_path):
            with open(cache_path, "r", encoding="utf-8", errors="ignore") as f:
                cache_text = f.read()
            norm_toolchain = toolchain.replace("\\", "/")
            if (
                "CMAKE_PROJECT_NAME:STATIC=kuroko_native" in cache_text
                and norm_toolchain in cache_text.replace("\\", "/")
            ):
                stale = False
        if stale:
            shutil.rmtree(BUILD_DIR)

    os.makedirs(BUILD_DIR, exist_ok=True)

    cfg_cmd = [
        cmake_bin,
        "-S", ROOT,
        "-B", BUILD_DIR,
        "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={ninja_bin}",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        "-DANDROID_ABI=arm64-v8a",
        f"-DANDROID_PLATFORM={android_platform}",
        "-DCMAKE_BUILD_TYPE=Release",
    ]
    print("Configuring:", " ".join(cfg_cmd))
    subprocess.run(cfg_cmd, check=True)

    bld_cmd = [cmake_bin, "--build", BUILD_DIR, "--parallel"]
    print("Building:", " ".join(bld_cmd))
    subprocess.run(bld_cmd, check=True)

    for fname in ("kuroko_daemon", "arm64-v8a.so"):
        p = os.path.join(BUILD_DIR, fname)
        print(f"Built {p} ({os.path.getsize(p)} bytes)")


if __name__ == "__main__":
    main()
