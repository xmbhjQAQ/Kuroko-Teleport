#!/usr/bin/env python3
"""Build, strip, ELF-audit, and package the Kuroko Teleport KSU2/Zygisk All-in-One module ZIP."""

import argparse
import hashlib
import os
import platform
import re
import shutil
import stat
import struct
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
IS_WIN = sys.platform == "win32"
EXE = ".exe" if IS_WIN else ""

VERSION_PROPS_PATH = os.path.join(ROOT, "version.properties")
NATIVE_BUILD_SCRIPT = os.path.join(ROOT, "native", "build_native.py")
NATIVE_OUT_DIR = os.path.join(ROOT, "native", "build", "arm64-v8a")
TEMPLATE_DIR = os.path.join(ROOT, "module_template")
STAGING_DIR = os.path.join(ROOT, "build", "module_staging")
DIST_DIR = os.path.join(ROOT, "dist")
ZIP_PATH = os.path.join(DIST_DIR, "kuroko-teleport-ksu-module.zip")
DEFAULT_APK_PATH = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

PT_LOAD = 1
PT_GNU_PROPERTY = 0x6474E553
EM_AARCH64 = 183
NT_GNU_PROPERTY_TYPE_0 = 5
GNU_PROPERTY_AARCH64_FEATURE_1_AND = 0xC0000000
AARCH64_FEATURE_1_BTI_PAC = 0x3

TEMPLATE_FILES = {
    "module.prop": 0o644,
    "sepolicy.rule": 0o644,
    "customize.sh": 0o755,
    "post-fs-data.sh": 0o755,
    "service.sh": 0o755,
    "uninstall.sh": 0o755,
}
BCP47_FILENAME_RE = re.compile(r"^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,2}\.sh$")


def version_key(name: str):
    """Natural version sort key ranking stable releases ahead of prereleases."""
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
        return bool(path) and os.path.isdir(os.path.join(path, "toolchains", "llvm", "prebuilt"))

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


def find_llvm_bin_dir(ndk_dir: str) -> str:
    prebuilt_root = os.path.join(ndk_dir, "toolchains", "llvm", "prebuilt")
    if not os.path.isdir(prebuilt_root):
        raise FileNotFoundError(f"NDK LLVM prebuilt directory not found: {prebuilt_root}")

    preferred_tags = []
    if IS_WIN:
        preferred_tags = ["windows-x86_64"]
    elif sys.platform == "darwin":
        preferred_tags = ["darwin-x86_64", "darwin-aarch64"]
    else:
        arch = platform.machine().lower()
        if arch in ("aarch64", "arm64"):
            preferred_tags = ["linux-aarch64", "linux-x86_64"]
        else:
            preferred_tags = ["linux-x86_64", "linux-aarch64"]

    for tag in preferred_tags:
        bin_dir = os.path.join(prebuilt_root, tag, "bin")
        if os.path.isdir(bin_dir):
            return bin_dir

    # Fallback: first prebuilt directory containing llvm-strip
    for entry in sorted(os.listdir(prebuilt_root)):
        bin_dir = os.path.join(prebuilt_root, entry, "bin")
        if os.path.isfile(os.path.join(bin_dir, f"llvm-strip{EXE}")):
            return bin_dir

    raise FileNotFoundError(f"No usable LLVM toolchain found under {prebuilt_root}")


def align8(val: int) -> int:
    return (val + 7) & ~7


def align4(val: int) -> int:
    return (val + 3) & ~3


def load_version_properties(path: str) -> dict:
    if not os.path.isfile(path):
        raise FileNotFoundError(f"Missing unified version contract: {path}")
    props = {}
    with open(path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise RuntimeError(f"Malformed line in {path}: {raw_line!r}")
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    required = [
        "RELEASE_VERSION_NAME",
        "RELEASE_VERSION_CODE",
        "SHM_SCHEMA_VERSION",
        "IPC_PROTOCOL_VERSION",
        "MIN_COMPAT_MODULE_CODE",
        "MIN_COMPAT_APP_CODE",
    ]
    missing = [k for k in required if not props.get(k)]
    if missing:
        raise RuntimeError(f"Missing required keys in {path}: {missing}")
    return props


def parse_lang_script(path: str) -> dict:
    """Parse KEY='...' entries from a module_template/lang/*.sh file and assert strict LF."""
    if not os.path.isfile(path):
        raise FileNotFoundError(f"Missing language file: {path}")
    with open(path, "rb") as f:
        raw = f.read()
    if b"\r" in raw:
        raise RuntimeError(f"Language file {path} contains CR (\\r); strict LF required")
    if not raw.endswith(b"\n"):
        raise RuntimeError(f"Language file {path} missing trailing LF")

    entries = {}
    for lineno, line in enumerate(raw.decode("utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        m = re.match(r"^([A-Z0-9_]+)='([^']*)'$", stripped)
        if not m:
            raise RuntimeError(f"Malformed assignment in {path}:{lineno}: {line!r}")
        k, v = m.group(1), m.group(2)
        if k in entries:
            raise RuntimeError(f"Duplicate key {k!r} in {path}:{lineno}")
        entries[k] = v
    if not entries:
        raise RuntimeError(f"Language file {path} defines no keys")
    return entries


def discover_and_verify_lang_scripts() -> dict:
    """Dynamically discover all module_template/lang/*.sh, verify BCP-47 names, LF, and en.sh key/placeholder parity."""
    lang_dir = os.path.join(TEMPLATE_DIR, "lang")
    if not os.path.isdir(lang_dir):
        raise FileNotFoundError(f"Missing language directory: {lang_dir}")

    filenames = sorted(f for f in os.listdir(lang_dir) if f.endswith(".sh"))
    if "en.sh" not in filenames:
        raise FileNotFoundError(f"Missing required English base language file: {os.path.join(lang_dir, 'en.sh')}")

    parsed = {}
    lang_entries = {}
    for fname in filenames:
        if not BCP47_FILENAME_RE.match(fname):
            raise RuntimeError(f"Invalid BCP-47 language filename in module_template/lang/: {fname!r}")
        full_path = os.path.join(lang_dir, fname)
        parsed[fname] = parse_lang_script(full_path)
        lang_entries[f"lang/{fname}"] = 0o644

    en_map = parsed["en.sh"]
    en_keys = set(en_map.keys())
    if "MOD_DESC" not in en_keys:
        raise RuntimeError("Missing required MOD_DESC key in lang/en.sh")

    for fname, lmap in parsed.items():
        if fname == "en.sh":
            continue
        lkeys = set(lmap.keys())
        if lkeys != en_keys:
            only_en = sorted(en_keys - lkeys)
            only_other = sorted(lkeys - en_keys)
            raise RuntimeError(
                f"lang/{fname} vs lang/en.sh key mismatch: missing={only_en}, extra={only_other}"
            )
        for k in sorted(en_keys):
            en_ph = re.findall(r"%(?:%|[a-zA-Z])", en_map[k])
            other_ph = re.findall(r"%(?:%|[a-zA-Z])", lmap[k])
            if en_ph != other_ph:
                raise RuntimeError(
                    f"Placeholder mismatch in lang/{fname} for key {k!r}: en={en_ph} vs {fname}={other_ph}"
                )

    print(f"  lang/*.sh dynamic parity PASS: {filenames} ({len(en_keys)} keys each, LF-only)")
    return lang_entries


def sync_module_prop(props: dict):
    """Ensure module_template/module.prop matches version.properties with strict LF endings."""
    prop_path = os.path.join(TEMPLATE_DIR, "module.prop")
    if not os.path.isfile(prop_path):
        raise FileNotFoundError(f"Missing template file: {prop_path}")
    expected_ver = f"v{props['RELEASE_VERSION_NAME']}"
    expected_code = props["RELEASE_VERSION_CODE"]

    with open(prop_path, "rb") as f:
        raw = f.read()
    lines = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n").splitlines()
    new_lines = []
    seen_ver = False
    seen_code = False
    for line in lines:
        if line.startswith("version="):
            new_lines.append(f"version={expected_ver}")
            seen_ver = True
        elif line.startswith("versionCode="):
            new_lines.append(f"versionCode={expected_code}")
            seen_code = True
        else:
            new_lines.append(line)
    if not seen_ver or not seen_code:
        raise RuntimeError(f"{prop_path}: missing version= or versionCode= entries")

    new_raw = ("\n".join(new_lines) + "\n").encode("utf-8")
    if new_raw != raw:
        with open(prop_path, "wb") as f:
            f.write(new_raw)
        print(f"  Synced module_template/module.prop -> version={expected_ver}, versionCode={expected_code}")
    else:
        print(f"  module_template/module.prop aligned: version={expected_ver}, versionCode={expected_code}")


def find_build_tools_dir(sdk_dir: str) -> str:
    bt_root = os.path.join(sdk_dir, "build-tools")
    if not os.path.isdir(bt_root):
        raise FileNotFoundError(f"Android SDK build-tools dir not found: {bt_root}")
    apksigner_name = "apksigner.bat" if IS_WIN else "apksigner"
    candidates = sorted(
        (d for d in os.listdir(bt_root) if os.path.isdir(os.path.join(bt_root, d))),
        key=version_key,
        reverse=True,
    )
    for ver in candidates:
        cand = os.path.join(bt_root, ver)
        if (
            os.path.isfile(os.path.join(cand, f"aapt2{EXE}"))
            and os.path.isfile(os.path.join(cand, f"zipalign{EXE}"))
            and os.path.isfile(os.path.join(cand, apksigner_name))
        ):
            return cand
    raise FileNotFoundError(
        f"No complete Android build-tools (aapt2, zipalign, {apksigner_name}) found in {bt_root}"
    )


def verify_and_read_apk(apk_path: str, props: dict, sdk_dir: str, local_props: dict) -> dict:
    """Audit APK version via aapt2, 16KB zipalign, and apksigner verify; return metadata."""
    if not os.path.isfile(apk_path):
        raise FileNotFoundError(f"Bundled APK not found: {apk_path}")

    bt_dir = find_build_tools_dir(sdk_dir)
    aapt2 = os.path.join(bt_dir, f"aapt2{EXE}")
    zipalign = os.path.join(bt_dir, f"zipalign{EXE}")
    apksigner = os.path.join(bt_dir, "apksigner.bat" if IS_WIN else "apksigner")
    for tool in (aapt2, zipalign, apksigner):
        if not os.path.isfile(tool):
            raise FileNotFoundError(f"Required build-tools binary not found: {tool}")

    badging = subprocess.check_output(
        [aapt2, "dump", "badging", apk_path], text=True, encoding="utf-8", errors="replace"
    )
    m = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
        badging,
    )
    if not m:
        raise RuntimeError(f"Failed to parse package metadata from aapt2 dump badging: {apk_path}")
    pkg_name, apk_vcode, apk_vname = m.group(1), m.group(2), m.group(3)
    if pkg_name != "com.kuroko.teleport":
        raise RuntimeError(f"Unexpected APK packageName={pkg_name!r}, expected 'com.kuroko.teleport'")
    if apk_vcode != props["RELEASE_VERSION_CODE"] or apk_vname != props["RELEASE_VERSION_NAME"]:
        raise RuntimeError(
            f"APK version mismatch: APK has {apk_vname}({apk_vcode}), "
            f"version.properties requires {props['RELEASE_VERSION_NAME']}({props['RELEASE_VERSION_CODE']})"
        )

    subprocess.run([zipalign, "-c", "-P", "16", "4", apk_path], check=True, stdout=subprocess.DEVNULL)

    env = os.environ.copy()
    local_java = local_props.get("java.home")
    if local_java and os.path.isdir(local_java):
        env["JAVA_HOME"] = local_java
        env["PATH"] = os.path.join(local_java, "bin") + os.pathsep + env.get("PATH", "")

    apksigner_cmd = ["cmd", "/c", apksigner, "verify", apk_path] if IS_WIN else [apksigner, "verify", apk_path]
    subprocess.run(apksigner_cmd, check=True, env=env)

    with open(apk_path, "rb") as f:
        apk_bytes = f.read()
    apk_sha256 = hashlib.sha256(apk_bytes).hexdigest()
    return {
        "packageName": pkg_name,
        "versionName": apk_vname,
        "versionCode": apk_vcode,
        "size": len(apk_bytes),
        "sha256": apk_sha256,
        "bytes": apk_bytes,
    }


def verify_elf_binary(path: str, llvm_readelf: str, required_exports=None, forbidden_imports=None):
    """Verify ARM64 ELF64, PT_LOAD p_align==0x4000, PT_GNU_PROPERTY==0x3, no libc++_shared.so, exports & imports."""
    with open(path, "rb") as f:
        data = f.read()

    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise RuntimeError(f"{path}: Not a valid ELF binary")
    ei_class = data[4]
    ei_data = data[5]
    if ei_class != 2 or ei_data != 1:
        raise RuntimeError(f"{path}: Expected 64-bit little-endian ELF (got class={ei_class}, data={ei_data})")

    e_machine = struct.unpack_from("<H", data, 18)[0]
    if e_machine != EM_AARCH64:
        raise RuntimeError(f"{path}: Expected EM_AARCH64 (183), got {e_machine}")

    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)

    pt_load_aligns = []
    gnu_prop_value = None

    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, off
        )
        if p_type == PT_LOAD:
            pt_load_aligns.append(p_align)
            if p_align != 0x4000:
                raise RuntimeError(f"{path}: PT_LOAD[{len(pt_load_aligns)-1}] p_align={hex(p_align)} != 0x4000")
        elif p_type == PT_GNU_PROPERTY:
            note_end = p_offset + p_filesz
            pos = p_offset
            while pos + 12 <= note_end:
                namesz, descsz, ntype = struct.unpack_from("<III", data, pos)
                pos += 12
                name = data[pos : pos + namesz]
                pos = align4(pos + namesz)
                desc = data[pos : pos + descsz]
                pos = align8(pos + descsz)
                if ntype == NT_GNU_PROPERTY_TYPE_0 and name == b"GNU\x00":
                    dpos = 0
                    while dpos + 8 <= len(desc):
                        pr_type, pr_datasz = struct.unpack_from("<II", desc, dpos)
                        dpos += 8
                        pr_data = desc[dpos : dpos + pr_datasz]
                        dpos = align8(dpos + pr_datasz)
                        if pr_type == GNU_PROPERTY_AARCH64_FEATURE_1_AND and pr_datasz == 4:
                            gnu_prop_value = struct.unpack_from("<I", pr_data, 0)[0]

    if not pt_load_aligns:
        raise RuntimeError(f"{path}: No PT_LOAD segments found")
    if gnu_prop_value != AARCH64_FEATURE_1_BTI_PAC:
        raise RuntimeError(
            f"{path}: PT_GNU_PROPERTY AARCH64_FEATURE_1_AND={gnu_prop_value!r}, expected 0x3 (BTI|PAC)"
        )

    # Check DT_NEEDED via llvm-readelf -d
    dyn_out = subprocess.check_output([llvm_readelf, "-d", path], text=True)
    needed = re.findall(r"\(NEEDED\)\s+Shared library:\s+\[([^\]]+)\]", dyn_out)
    if "libc++_shared.so" in needed:
        raise RuntimeError(f"{path}: Forbidden DT_NEEDED dependency libc++_shared.so found: {needed}")

    syms_out = None
    if required_exports or forbidden_imports:
        syms_out = subprocess.check_output([llvm_readelf, "--dyn-syms", path], text=True)

    # Check dynamic exports if required
    found_exports = []
    if required_exports:
        for sym in required_exports:
            pattern = re.compile(
                rf"^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+FUNC\s+GLOBAL\s+\S+\s+(\d+)\s+{re.escape(sym)}(?:@\S+)?\s*$",
                re.MULTILINE,
            )
            if not pattern.search(syms_out):
                raise RuntimeError(f"{path}: Required exported GLOBAL FUNC symbol '{sym}' not found in .dynsym")
            found_exports.append(sym)

    # Check forbidden dynamic imports (e.g. dlopen in zygisk/arm64-v8a.so)
    if forbidden_imports:
        for sym in forbidden_imports:
            pattern = re.compile(
                rf"^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+\S+\s+\S+\s+\S+\s+UND\s+{re.escape(sym)}(?:@\S+)?\s*$",
                re.MULTILINE,
            )
            if pattern.search(syms_out):
                raise RuntimeError(f"{path}: Forbidden dynamic import '{sym}' found in .dynsym")

    return {
        "pt_load_count": len(pt_load_aligns),
        "pt_load_align": "0x4000",
        "gnu_property": hex(gnu_prop_value),
        "needed": needed,
        "exports": found_exports,
        "forbidden_imports_absent": list(forbidden_imports or []),
    }


def write_zip_entry(zf: zipfile.ZipFile, arcname: str, data: bytes, mode: int, compress_type=zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(arcname)
    info.create_system = 3  # Unix
    info.compress_type = compress_type
    info.external_attr = (stat.S_IFREG | mode) << 16
    zf.writestr(info, data, compress_type=compress_type)


def verify_zip_archive(zip_path: str, expected_modes: dict, text_entries: set, apk_meta=None):
    with zipfile.ZipFile(zip_path, "r") as zf:
        infos = {info.filename: info for info in zf.infolist()}
        missing = [k for k in expected_modes if k not in infos]
        if missing:
            raise RuntimeError(f"ZIP missing entries: {missing}")

        for name, expected_mode in expected_modes.items():
            info = infos[name]
            actual_mode = (info.external_attr >> 16) & 0o777
            if info.create_system != 3:
                raise RuntimeError(f"ZIP entry {name}: create_system={info.create_system}, expected 3 (Unix)")
            if actual_mode != expected_mode:
                raise RuntimeError(
                    f"ZIP entry {name}: mode={oct(actual_mode)}, expected {oct(expected_mode)}"
                )
            if name in text_entries:
                raw = zf.read(name)
                if b"\r" in raw:
                    raise RuntimeError(f"ZIP entry {name}: contains CR (\\r) line endings; LF required")
                if not raw.endswith(b"\n"):
                    raise RuntimeError(f"ZIP entry {name}: missing trailing LF")

        if apk_meta is not None:
            apk_info = infos["apk/kuroko.apk"]
            if apk_info.compress_type != zipfile.ZIP_STORED:
                raise RuntimeError(
                    f"ZIP entry apk/kuroko.apk: compress_type={apk_info.compress_type}, expected ZIP_STORED (0)"
                )
            if apk_info.file_size != apk_meta["size"]:
                raise RuntimeError(
                    f"ZIP entry apk/kuroko.apk size={apk_info.file_size} != expected {apk_meta['size']}"
                )
            embedded_sha = hashlib.sha256(zf.read("apk/kuroko.apk")).hexdigest()
            if embedded_sha != apk_meta["sha256"]:
                raise RuntimeError(
                    f"ZIP entry apk/kuroko.apk SHA256 mismatch: {embedded_sha} != {apk_meta['sha256']}"
                )


def main():
    parser = argparse.ArgumentParser(description="Build Kuroko Teleport KSU2/Zygisk All-in-One module ZIP.")
    parser.add_argument("--no-apk", action="store_true", help="Skip embedding Controller APK (native-only test build)")
    parser.add_argument("--apk", default=DEFAULT_APK_PATH, help="Path to Controller APK to bundle")
    args = parser.parse_args()

    local_props = load_local_properties(ROOT)
    sdk_dir = find_android_sdk(local_props)
    ndk_dir = find_android_ndk(sdk_dir, local_props)
    llvm_bin = find_llvm_bin_dir(ndk_dir)
    llvm_strip = os.path.join(llvm_bin, f"llvm-strip{EXE}")
    llvm_readelf = os.path.join(llvm_bin, f"llvm-readelf{EXE}")

    for tool in (llvm_strip, llvm_readelf):
        if not os.path.isfile(tool):
            raise FileNotFoundError(f"Required NDK tool not found: {tool}")

    print("=== [0/6] Loading version.properties, syncing module.prop & auditing lang/*.sh ===")
    props = load_version_properties(VERSION_PROPS_PATH)
    sync_module_prop(props)
    lang_entries = discover_and_verify_lang_scripts()
    all_template_files = {**TEMPLATE_FILES, **lang_entries}

    print("\n=== [1/6] Building native binaries ===")
    subprocess.run([sys.executable, NATIVE_BUILD_SCRIPT], check=True)

    raw_daemon = os.path.join(NATIVE_OUT_DIR, "kuroko_daemon")
    raw_zygisk = os.path.join(NATIVE_OUT_DIR, "arm64-v8a.so")
    for p in (raw_daemon, raw_zygisk):
        if not os.path.isfile(p):
            raise FileNotFoundError(f"Expected native artifact not found: {p}")

    print("\n=== [2/6] Preparing staging dir & stripping binaries ===")
    if os.path.exists(STAGING_DIR):
        shutil.rmtree(STAGING_DIR)
    os.makedirs(os.path.join(STAGING_DIR, "bin"), exist_ok=True)
    os.makedirs(os.path.join(STAGING_DIR, "zygisk"), exist_ok=True)

    staged_daemon = os.path.join(STAGING_DIR, "bin", "kuroko_daemon")
    staged_zygisk = os.path.join(STAGING_DIR, "zygisk", "arm64-v8a.so")

    subprocess.run([llvm_strip, "--strip-all", "-o", staged_daemon, raw_daemon], check=True)
    subprocess.run([llvm_strip, "--strip-unneeded", "-o", staged_zygisk, raw_zygisk], check=True)

    print(
        f"  kuroko_daemon:  {os.path.getsize(raw_daemon):,} B -> {os.path.getsize(staged_daemon):,} B (stripped)"
    )
    print(
        f"  arm64-v8a.so:   {os.path.getsize(raw_zygisk):,} B -> {os.path.getsize(staged_zygisk):,} B (stripped)"
    )

    print("\n=== [3/6] Auditing stripped ELF binaries ===")
    daemon_audit = verify_elf_binary(staged_daemon, llvm_readelf=llvm_readelf, required_exports=None)
    zygisk_audit = verify_elf_binary(
        staged_zygisk,
        llvm_readelf=llvm_readelf,
        required_exports=["zygisk_module_entry", "zygisk_companion_entry"],
        forbidden_imports=["dlopen"],
    )
    print(f"  kuroko_daemon audit PASS: {daemon_audit}")
    print(f"  arm64-v8a.so  audit PASS: {zygisk_audit}")

    apk_meta = None
    if not args.no_apk:
        print("\n=== [4/6] Auditing bundled Controller APK (All-in-One) ===")
        apk_meta = verify_and_read_apk(args.apk, props, sdk_dir=sdk_dir, local_props=local_props)
        print(
            f"  APK audit PASS: {apk_meta['packageName']} v{apk_meta['versionName']}({apk_meta['versionCode']}) "
            f"{apk_meta['size']:,} B SHA256={apk_meta['sha256']}"
        )
    else:
        print("\n=== [4/6] Skipping APK bundling (--no-apk) ===")

    print("\n=== [5/6] Verifying template files (LF) & packaging ZIP ===")
    os.makedirs(DIST_DIR, exist_ok=True)
    expected_modes = {}

    with zipfile.ZipFile(ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for fname, mode in all_template_files.items():
            src = os.path.join(TEMPLATE_DIR, *fname.split("/"))
            if not os.path.isfile(src):
                raise FileNotFoundError(f"Missing template file: {src}")
            with open(src, "rb") as f:
                raw = f.read()
            if b"\r" in raw:
                raise RuntimeError(f"Template file {src} contains CRLF (\\r); must be strict LF")
            dst = os.path.join(STAGING_DIR, *fname.split("/"))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(raw)
            write_zip_entry(zf, fname, raw, mode, compress_type=zipfile.ZIP_DEFLATED)
            expected_modes[fname] = mode

        with open(staged_daemon, "rb") as f:
            write_zip_entry(zf, "bin/kuroko_daemon", f.read(), 0o755, compress_type=zipfile.ZIP_DEFLATED)
        expected_modes["bin/kuroko_daemon"] = 0o755

        with open(staged_zygisk, "rb") as f:
            write_zip_entry(zf, "zygisk/arm64-v8a.so", f.read(), 0o755, compress_type=zipfile.ZIP_DEFLATED)
        expected_modes["zygisk/arm64-v8a.so"] = 0o755

        text_entries = set(all_template_files.keys())
        if apk_meta is not None:
            meta_text = (
                f"packageName={apk_meta['packageName']}\n"
                f"versionName={apk_meta['versionName']}\n"
                f"versionCode={apk_meta['versionCode']}\n"
                f"size={apk_meta['size']}\n"
                f"sha256={apk_meta['sha256']}\n"
            ).encode("utf-8")
            os.makedirs(os.path.join(STAGING_DIR, "apk"), exist_ok=True)
            with open(os.path.join(STAGING_DIR, "apk", "apk_meta.prop"), "wb") as f:
                f.write(meta_text)
            write_zip_entry(zf, "apk/apk_meta.prop", meta_text, 0o644, compress_type=zipfile.ZIP_DEFLATED)
            write_zip_entry(zf, "apk/kuroko.apk", apk_meta["bytes"], 0o644, compress_type=zipfile.ZIP_STORED)
            expected_modes["apk/apk_meta.prop"] = 0o644
            expected_modes["apk/kuroko.apk"] = 0o644
            text_entries.add("apk/apk_meta.prop")

    print("\n=== [6/6] Auditing final ZIP archive ===")
    verify_zip_archive(ZIP_PATH, expected_modes, text_entries=text_entries, apk_meta=apk_meta)

    with open(ZIP_PATH, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()
    mode_label = "All-in-One (with bundled APK)" if apk_meta is not None else "Native-only (--no-apk)"
    print(f"  Kuroko Teleport ZIP audit PASS ({mode_label}): {ZIP_PATH}")
    print(f"  Version: v{props['RELEASE_VERSION_NAME']} (versionCode={props['RELEASE_VERSION_CODE']})")
    print(f"  Size:    {os.path.getsize(ZIP_PATH):,} bytes")
    print(f"  SHA256:  {sha256}")


if __name__ == "__main__":
    main()
