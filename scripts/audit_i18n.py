#!/usr/bin/env python3
"""Reusable version-contract, Android XML i18n, and shell language-pack auditor."""

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION_PROPS_PATH = os.path.join(REPO_ROOT, "version.properties")
RES_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "res")
TEMPLATE_DIR = os.path.join(REPO_ROOT, "module_template")
LANG_DIR = os.path.join(TEMPLATE_DIR, "lang")

STRING_FILES = ("strings.xml", "strings_modules.xml")
SHELL_SCRIPTS = ("customize.sh", "post-fs-data.sh", "service.sh", "uninstall.sh")
BCP47_FILENAME_RE = re.compile(r"^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,2}\.sh$")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?$")

# Java/Android Formatter specifier after '%':
#   - '%%' or '%n' (escaped literal / newline)
#   - '%[pos$][flags][width][.precision]conversion'
ANDROID_FMT_TOKEN_RE = re.compile(
    r"%(?:(?P<esc>[%n])|"
    r"(?:(?P<pos>[1-9]\d*)\$)?"
    r"(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>\d+)?"
    r"(?P<prec>\.\d+)?"
    r"(?P<conv>[bBhHsScCdoxXeEfgGaAtT]))"
)


def fail(errors: list[str], msg: str):
    errors.append(msg)


def audit_version_properties(errors: list[str]) -> dict:
    if not os.path.isfile(VERSION_PROPS_PATH):
        fail(errors, f"Missing version.properties at {VERSION_PROPS_PATH}")
        return {}

    props = {}
    with open(VERSION_PROPS_PATH, "r", encoding="utf-8") as f:
        for lineno, raw_line in enumerate(f, start=1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                fail(errors, f"version.properties:{lineno}: malformed line {raw_line!r}")
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip()
            if k in props:
                fail(errors, f"version.properties:{lineno}: duplicate key {k!r}")
            props[k] = v

    required_ints = (
        "RELEASE_VERSION_CODE",
        "SHM_SCHEMA_VERSION",
        "IPC_PROTOCOL_VERSION",
        "MIN_COMPAT_MODULE_CODE",
        "MIN_COMPAT_APP_CODE",
    )
    ver_name = props.get("RELEASE_VERSION_NAME", "")
    if not ver_name or not SEMVER_RE.match(ver_name):
        fail(errors, f"version.properties: invalid or missing RELEASE_VERSION_NAME={ver_name!r}")

    parsed_ints = {}
    for key in required_ints:
        raw_val = props.get(key, "")
        if not raw_val.isdigit() or int(raw_val) <= 0:
            fail(errors, f"version.properties: {key}={raw_val!r} must be a positive integer")
        else:
            parsed_ints[key] = int(raw_val)

    if "RELEASE_VERSION_CODE" in parsed_ints:
        vcode = parsed_ints["RELEASE_VERSION_CODE"]
        for min_key in ("MIN_COMPAT_MODULE_CODE", "MIN_COMPAT_APP_CODE"):
            if min_key in parsed_ints and parsed_ints[min_key] > vcode:
                fail(errors, f"version.properties: {min_key} ({parsed_ints[min_key]}) > RELEASE_VERSION_CODE ({vcode})")

    # Cross-check module_template/module.prop alignment & LF
    mod_prop = os.path.join(TEMPLATE_DIR, "module.prop")
    if os.path.isfile(mod_prop):
        raw_mp = open(mod_prop, "rb").read()
        if b"\r" in raw_mp or not raw_mp.endswith(b"\n"):
            fail(errors, "module_template/module.prop: must use strict LF line endings with trailing newline")
        mp = {}
        for line in raw_mp.decode("utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                mp[k.strip()] = v.strip()
        if ver_name and mp.get("version") != f"v{ver_name}":
            fail(errors, f"module.prop version={mp.get('version')!r} != expected 'v{ver_name}'")
        if "RELEASE_VERSION_CODE" in parsed_ints and mp.get("versionCode") != str(parsed_ints["RELEASE_VERSION_CODE"]):
            fail(
                errors,
                f"module.prop versionCode={mp.get('versionCode')!r} != expected '{parsed_ints['RELEASE_VERSION_CODE']}'",
            )
    else:
        fail(errors, f"Missing {mod_prop}")

    return props


def extract_android_format_signature(text: str, formatted: bool, loc: str, errors: list[str]):
    """Validate no bare '%' exists and return (positional_dict, non_positional_list)."""
    if not formatted or "%" not in text:
        return {}, []

    pos_map: dict[int, str] = {}
    seq_list: list[str] = []
    idx = 0
    length = len(text)

    while idx < length:
        pct = text.find("%", idx)
        if pct == -1:
            break
        m = ANDROID_FMT_TOKEN_RE.match(text, pct)
        if not m:
            snippet = text[max(0, pct - 8) : min(length, pct + 12)]
            fail(errors, f"{loc}: unescaped or invalid '%' specifier near {snippet!r}")
            idx = pct + 1
            continue
        if m.group("esc"):
            idx = m.end()
            continue

        spec = f"{m.group('flags') or ''}{m.group('width') or ''}{m.group('prec') or ''}{m.group('conv')}"
        pos = m.group("pos")
        if pos is not None:
            pos_i = int(pos)
            prev = pos_map.get(pos_i)
            if prev is not None and prev != spec:
                fail(errors, f"{loc}: conflicting format specifiers for arg %{pos_i}$: {prev!r} vs {spec!r}")
            pos_map[pos_i] = spec
        else:
            seq_list.append(spec)
        idx = m.end()

    if pos_map and seq_list:
        fail(errors, f"{loc}: mixes positional ({pos_map}) and non-positional ({seq_list}) specifiers")
    return pos_map, seq_list


def parse_android_strings_xml(path: str, errors: list[str]) -> dict[str, tuple[str, bool]]:
    """Return {name: (full_text, formatted_bool)} and flag duplicate keys or XML errors."""
    result: dict[str, tuple[str, bool]] = {}
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        fail(errors, f"{path}: XML parse error: {exc}")
        return result

    root = tree.getroot()
    if root.tag != "resources":
        fail(errors, f"{path}: expected root <resources>, got <{root.tag}>")
        return result

    for elem in root.findall("string"):
        name = elem.attrib.get("name", "").strip()
        if not name:
            fail(errors, f"{path}: <string> missing 'name' attribute")
            continue
        if name in result:
            fail(errors, f"{path}: duplicate string key {name!r}")
            continue
        formatted = elem.attrib.get("formatted", "true").lower() != "false"
        text = "".join(elem.itertext())
        result[name] = (text, formatted)
    return result


def audit_android_xml_i18n(errors: list[str]) -> dict:
    base_dir = os.path.join(RES_DIR, "values")
    if not os.path.isdir(base_dir):
        fail(errors, f"Missing base Android resource dir: {base_dir}")
        return {}

    base_by_file: dict[str, dict[str, tuple[str, bool]]] = {}
    base_combined: dict[str, tuple[str, str, bool]] = {}
    for fname in STRING_FILES:
        fpath = os.path.join(base_dir, fname)
        if not os.path.isfile(fpath):
            fail(errors, f"Missing base string resource file: {fpath}")
            continue
        parsed = parse_android_strings_xml(fpath, errors)
        base_by_file[fname] = parsed
        for key, (val, formatted) in parsed.items():
            if key in base_combined:
                prev_file = base_combined[key][0]
                fail(errors, f"values/: cross-file key collision for {key!r} ({prev_file} & {fname})")
            else:
                base_combined[key] = (fname, val, formatted)

    base_sigs: dict[str, tuple[dict[int, str], list[str]]] = {}
    for key, (fname, val, formatted) in base_combined.items():
        base_sigs[key] = extract_android_format_signature(val, formatted, f"values/{fname}:{key}", errors)

    # Discover every values-* directory under res/
    variant_dirs = sorted(
        d for d in os.listdir(RES_DIR)
        if d.startswith("values-") and os.path.isdir(os.path.join(RES_DIR, d))
    )
    if not variant_dirs:
        fail(errors, f"No localized values-* directories found under {RES_DIR}")

    audited_locales = []
    for vdir in variant_dirs:
        vpath = os.path.join(RES_DIR, vdir)
        xml_files_present = [f for f in os.listdir(vpath) if f.startswith("strings") and f.endswith(".xml")]
        if not xml_files_present:
            # Non-string qualifier dir (e.g. values-night themes only)
            continue
        audited_locales.append(vdir)

        v_combined: dict[str, tuple[str, str, bool]] = {}
        for fname in STRING_FILES:
            fpath = os.path.join(vpath, fname)
            if not os.path.isfile(fpath):
                fail(errors, f"{vdir}/: missing required string resource file {fname}")
                continue
            parsed = parse_android_strings_xml(fpath, errors)

            base_keys = set(base_by_file.get(fname, {}).keys())
            v_keys = set(parsed.keys())
            missing = sorted(base_keys - v_keys)
            extra = sorted(v_keys - base_keys)
            if missing:
                fail(errors, f"{vdir}/{fname}: missing {len(missing)} key(s) vs values/{fname}: {missing[:10]}")
            if extra:
                fail(errors, f"{vdir}/{fname}: extra {len(extra)} key(s) vs values/{fname}: {extra[:10]}")

            for key, (val, formatted) in parsed.items():
                if key in v_combined:
                    prev_file = v_combined[key][0]
                    fail(errors, f"{vdir}/: cross-file key collision for {key!r} ({prev_file} & {fname})")
                else:
                    v_combined[key] = (fname, val, formatted)

                vsig = extract_android_format_signature(val, formatted, f"{vdir}/{fname}:{key}", errors)
                if key in base_sigs and vsig != base_sigs[key]:
                    fail(
                        errors,
                        f"{vdir}/{fname}:{key}: format specifier mismatch vs values/: "
                        f"base={base_sigs[key]} vs {vdir}={vsig}",
                    )

    return {
        "base_total_keys": len(base_combined),
        "per_file_counts": {k: len(v) for k, v in base_by_file.items()},
        "locales": audited_locales,
    }


def audit_shell_lang_packs(errors: list[str]) -> dict:
    if not os.path.isdir(LANG_DIR):
        fail(errors, f"Missing module_template/lang directory: {LANG_DIR}")
        return {}

    filenames = sorted(f for f in os.listdir(LANG_DIR) if f.endswith(".sh"))
    if "en.sh" not in filenames:
        fail(errors, f"Missing base language pack: {os.path.join(LANG_DIR, 'en.sh')}")
        return {}

    parsed: dict[str, dict[str, str]] = {}
    for fname in filenames:
        full_path = os.path.join(LANG_DIR, fname)
        if not BCP47_FILENAME_RE.match(fname):
            fail(errors, f"module_template/lang/{fname}: invalid BCP-47 filename")

        raw = open(full_path, "rb").read()
        if b"\r" in raw:
            fail(errors, f"module_template/lang/{fname}: contains CR (\\r); strict LF required")
        if not raw.endswith(b"\n"):
            fail(errors, f"module_template/lang/{fname}: missing trailing LF")

        entries: dict[str, str] = {}
        for lineno, line in enumerate(raw.decode("utf-8").splitlines(), start=1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            m = re.match(r"^([A-Z0-9_]+)='([^']*)'$", stripped)
            if not m:
                fail(errors, f"module_template/lang/{fname}:{lineno}: malformed assignment {line!r}")
                continue
            k, v = m.group(1), m.group(2)
            if k in entries:
                fail(errors, f"module_template/lang/{fname}:{lineno}: duplicate key {k!r}")
            else:
                entries[k] = v
        if not entries:
            fail(errors, f"module_template/lang/{fname}: defines no keys")
        parsed[fname] = entries

        if sys.platform != "win32":
            proc = subprocess.run(["sh", "-n", full_path], capture_output=True, text=True)
            if proc.returncode != 0:
                fail(errors, f"sh -n failed for module_template/lang/{fname}: {proc.stderr.strip()}")

    en_map = parsed.get("en.sh", {})
    en_keys = set(en_map.keys())
    if "MOD_DESC" not in en_keys:
        fail(errors, "module_template/lang/en.sh: missing required MOD_DESC key")

    for fname, lmap in parsed.items():
        if fname == "en.sh":
            continue
        lkeys = set(lmap.keys())
        missing = sorted(en_keys - lkeys)
        extra = sorted(lkeys - en_keys)
        if missing:
            fail(errors, f"module_template/lang/{fname}: missing keys vs en.sh: {missing}")
        if extra:
            fail(errors, f"module_template/lang/{fname}: extra keys vs en.sh: {extra}")
        for k in sorted(en_keys & lkeys):
            en_ph = re.findall(r"%(?:%|[a-zA-Z])", en_map[k])
            other_ph = re.findall(r"%(?:%|[a-zA-Z])", lmap[k])
            if en_ph != other_ph:
                fail(
                    errors,
                    f"module_template/lang/{fname}:{k}: placeholder mismatch en={en_ph} vs {fname}={other_ph}",
                )

    # Also verify LF + sh -n on module lifecycle scripts
    for script in SHELL_SCRIPTS:
        spath = os.path.join(TEMPLATE_DIR, script)
        if not os.path.isfile(spath):
            fail(errors, f"Missing module_template/{script}")
            continue
        raw = open(spath, "rb").read()
        if b"\r" in raw or not raw.endswith(b"\n"):
            fail(errors, f"module_template/{script}: must use strict LF with trailing newline")
        if sys.platform != "win32":
            proc = subprocess.run(["sh", "-n", spath], capture_output=True, text=True)
            if proc.returncode != 0:
                fail(errors, f"sh -n failed for module_template/{script}: {proc.stderr.strip()}")

    return {"files": filenames, "keys_per_file": len(en_keys)}


def main() -> int:
    errors: list[str] = []

    vprops = audit_version_properties(errors)
    xml_stats = audit_android_xml_i18n(errors)
    sh_stats = audit_shell_lang_packs(errors)

    if errors:
        print(f"AUDIT FAILED with {len(errors)} error(s):", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(
        f"[PASS] version.properties: v{vprops['RELEASE_VERSION_NAME']} "
        f"(code={vprops['RELEASE_VERSION_CODE']}, IPC={vprops['IPC_PROTOCOL_VERSION']}, "
        f"SHM={vprops['SHM_SCHEMA_VERSION']}, minMod={vprops['MIN_COMPAT_MODULE_CODE']}, "
        f"minApp={vprops['MIN_COMPAT_APP_CODE']})"
    )
    print(
        f"[PASS] Android XML i18n: {xml_stats['base_total_keys']} keys "
        f"({xml_stats['per_file_counts']}), locales={xml_stats['locales']}"
    )
    sh_mode = "LF + sh -n" if sys.platform != "win32" else "LF"
    print(
        f"[PASS] Shell lang packs: {sh_stats['files']} "
        f"({sh_stats['keys_per_file']} keys each, {sh_mode})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
