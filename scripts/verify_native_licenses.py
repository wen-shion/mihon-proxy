#!/usr/bin/env python3
"""Verify (or regenerate) the native dependency licence manifest for the pinned libXray AAR.

Evidence sources, and why all three are needed
----------------------------------------------
1. **``go version -m <libgojni.so>`` - primary inventory.**
   The AAR's native library embeds the Go build info, which lists every module recorded at link
   time together with its exact version. This is the authoritative inventory.

2. **Target-platform dependency graph** (``go list -deps`` with ``GOOS=android GOARCH=arm64``).
   Used to decide whether a module that shows no symbols is nevertheless *eligible* to be linked.

3. **``llvm-nm --defined-only -C`` - positive linkage evidence only.**
   A symbol match *proves* a module's code was linked. A **zero hit proves nothing**: symbols can
   disappear through inlining, and the usual ``module/`` pattern only matches sub-package symbols
   (a package's own symbols are ``module.(*T).M``), so the pattern used here matches both forms.
   ``linked: false`` is therefore **never** derived from ``nm`` alone - it requires the module to
   be absent from the target dependency graph.

Conservative default: any module present in the build info is treated as shipped, because
under-reporting a dependency is a licence-compliance risk while over-reporting it is not.

Usage
-----
::

    python scripts/verify_native_licenses.py                 # verify against the committed manifest
    python scripts/verify_native_licenses.py --write          # regenerate the manifest
    python scripts/verify_native_licenses.py --aar <path>     # use a different AAR
    python scripts/verify_native_licenses.py --no-nm          # skip symbol evidence (inventory only)

Exit status is non-zero on any drift, which makes this usable as a CI gate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "licenses" / "native-dependencies.json"
DEFAULT_AAR = REPO_ROOT / "local-repo" / "com" / "xtls" / "libxray" / "26.9.9" / "libxray-26.9.9.aar"

# Pinned expectations. These are the values the manifest must agree with.
PINNED_AAR_SHA256 = "cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb"
PINNED_TAG = "v26.9.9"
PINNED_LIBXRAY_COMMIT = "50b95979f5db551bd273165cf469e5daaf791341"
PINNED_XRAY_CORE_COMMIT = "52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120"
NATIVE_LIB = "jni/arm64-v8a/libgojni.so"

# Modules that must NOT appear: pulled in only by a non-Android source graph.
EXPECTED_NOT_SHIPPED = {
    "golang.zx2c4.com/wintun": "Windows-only; absent from build info and from the android/arm64 graph",
    "golang.zx2c4.com/wireguard/windows": "Windows-only; absent from build info and from the android/arm64 graph",
}

LICENCE_FILE_RE = re.compile(r"^(licen[cs]e|copying|notice|unlicen[cs]e|copyright)(\.|$|-|_)", re.I)

# Modules whose licence cannot be read from the module cache. Each entry states where the value
# came from, because an override without evidence would defeat the point of this script.
LICENCE_OVERRIDES = {
    "github.com/xtls/libxray": (
        "MIT",
        "XTLS/libXray repository LICENSE (MIT). The build info records a devel pseudo-version "
        "(v0.0.0-00010101000000-000000000000) because the module is built from a local checkout, "
        "so there is no module-cache entry to read.",
    ),
}


# --------------------------------------------------------------------------------------- helpers
def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def which_go(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    return shutil.which("go")


def which_nm(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    found = shutil.which("llvm-nm")
    if found:
        return found
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if ndk:
        for prebuilt in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
            candidate = Path(ndk) / "toolchains" / "llvm" / "prebuilt" / prebuilt / "bin" / "llvm-nm"
            for suffix in ("", ".exe"):
                if candidate.with_suffix(suffix).is_file():
                    return str(candidate.with_suffix(suffix))
    return None


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs)


# ------------------------------------------------------------------------------------ collection
def build_info_modules(go: str, so_path: Path) -> dict[str, dict[str, str]]:
    """Returns {module: {"version": ..., "sum": ...}} from the embedded Go build info."""
    result = run([go, "version", "-m", str(so_path)])
    if result.returncode != 0:
        raise SystemExit(f"FAIL  'go version -m' failed on {so_path}:\n{result.stderr.strip()[:600]}")
    modules: dict[str, dict[str, str]] = {}
    for line in result.stdout.splitlines():
        parts = line.strip().split("\t")
        if len(parts) >= 3 and parts[0] == "dep":
            modules[parts[1]] = {"version": parts[2], "sum": parts[3] if len(parts) > 3 else ""}
        elif len(parts) >= 2 and parts[0] == "mod":
            # the main module (the gomobile binding) - recorded separately, not a dependency
            pass
    return modules


def target_graph_modules(go: str, libxray_src: Path | None) -> set[str]:
    """Module paths reachable from libXray for GOOS=android/GOARCH=arm64. Empty when unavailable.

    Uses the module path reported by the go tool itself rather than deriving it from the import
    path: package-to-module mapping is not a fixed number of path segments (``golang.org/x/exp``
    is three, ``gvisor.dev/gvisor`` is two).
    """
    if libxray_src is None or not (libxray_src / "go.mod").is_file():
        return set()
    env = dict(os.environ)
    env.update({"GOOS": "android", "GOARCH": "arm64", "CGO_ENABLED": "1"})
    result = run([go, "list", "-deps", "-f", "{{with .Module}}{{.Path}}{{end}}",
                  "github.com/xtls/libxray"], cwd=str(libxray_src), env=env)
    if result.returncode != 0:
        print(f"WARN  could not compute the target dependency graph: {result.stderr.strip()[:200]}")
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def symbol_lines(nm: str, so_path: Path) -> list[str] | None:
    """Returns the defined-symbol lines, or None when llvm-nm could not be run."""
    result = run([nm, "--defined-only", "-C", str(so_path)])
    if result.returncode != 0:
        print(f"WARN  llvm-nm failed: {result.stderr.strip()[:200]}")
        return None
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    print(f"      {len(lines):,} defined symbols")
    return lines


def count_hits(lines: list[str], module: str) -> int:
    """Counts symbols of a module, matching both sub-package (``mod/``) and own-package (``mod.``)."""
    return sum(1 for line in lines if (module + "/") in line or (module + ".") in line)


def classify_licence(text: str) -> str:
    low = text.lower()
    if "mozilla public license" in low and "2.0" in low:
        return "MPL-2.0"
    if "gnu affero general public license" in low:
        return "AGPL-3.0-or-later"
    if "gnu lesser general public license" in low:
        return "LGPL-3.0"
    if "gnu general public license" in low:
        return "GPL-3.0-or-later" if "version 3" in low else "GPL-3.0"
    if "apache license" in low and "version 2.0" in low:
        return "Apache-2.0"
    if "permission is hereby granted, free of charge" in low and "without restriction" in low:
        return "MIT"
    if "redistribution and use in source and binary forms" in low:
        if "neither the name" in low:
            return "BSD-3-Clause"
        return "BSD-2-Clause"
    if "isc license" in low or "permission to use, copy, modify, and/or distribute this software" in low:
        return "ISC"
    if "this is free and unencumbered software released into the public domain" in low:
        return "Unlicense"
    if "creative commons zero" in low or "cc0 1.0" in low:
        return "CC0-1.0"
    return "UNKNOWN"


def licence_for(gomodcache: Path | None, module: str, version: str) -> tuple[str, str]:
    """Returns (spdx, evidence) reading the licence text from the module cache."""
    if module in LICENCE_OVERRIDES:
        return LICENCE_OVERRIDES[module]
    if gomodcache is None:
        return "UNKNOWN", "module cache not available (set GOPATH/GOMODCACHE, or pass --gomodcache)"
    if not version:
        return "UNKNOWN", "no version recorded in build info"
    parts = module.split("/")
    directory = gomodcache.joinpath(*parts[:-1], parts[-1] + "@" + version)
    if not directory.is_dir():
        return "UNKNOWN", f"module cache miss: {directory}"
    for name in sorted(os.listdir(directory)):
        path = directory / name
        if path.is_file() and LICENCE_FILE_RE.match(name) and path.stat().st_size < 4_000_000:
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            spdx = classify_licence(text)
            if spdx != "UNKNOWN":
                return spdx, f"{name} ({len(text)} bytes)"
    return "UNKNOWN", "no recognisable licence file in the module cache"


def obligations_for(spdx: str) -> list[str]:
    if spdx.startswith("GPL-3.0"):
        return ["attribution", "full-corresponding-source-on-distribution", "no-additional-restrictions"]
    if spdx.startswith("LGPL"):
        return ["attribution", "relinking-or-static-linking-exception"]
    if spdx.startswith("MPL"):
        return ["attribution", "file-level-source-on-modification"]
    return ["attribution"]


# ----------------------------------------------------------------------------------------- build
def collect(aar: Path, go: str, nm: str | None, libxray_src: Path | None, use_nm: bool,
            gomodcache_override: Path | None = None) -> dict:
    if not aar.is_file():
        raise SystemExit(
            f"FAIL  AAR not found: {aar}\n"
            "      install it first: python scripts/install_libxray.py --source <path-to-libXRay.aar>",
        )

    aar_hash = sha256_of(aar)
    with tempfile.TemporaryDirectory(prefix="libxray-lic-") as tmp:
        so_path = Path(tmp) / "libgojni.so"
        with zipfile.ZipFile(aar) as archive:
            if NATIVE_LIB not in archive.namelist():
                raise SystemExit(f"FAIL  {NATIVE_LIB} is not present in {aar}")
            so_bytes = archive.read(NATIVE_LIB)
        so_path.write_bytes(so_bytes)
        so_size = so_path.stat().st_size
        so_hash = hashlib.sha256(so_bytes).hexdigest()

        print(f"      native lib : {NATIVE_LIB} ({so_size:,} bytes)")
        print("      reading embedded Go build info ...")
        modules = build_info_modules(go, so_path)

        print(f"      {len(modules)} modules in the build info")
        graph = target_graph_modules(go, libxray_src)
        if graph:
            print(f"      {len(graph)} modules in the android/arm64 dependency graph")

        lines: list[str] = []
        if use_nm and nm:
            print("      collecting symbol evidence ...")
            collected = symbol_lines(nm, so_path)
            if collected:
                lines = collected

    gomodcache_raw = run([go, "env", "GOMODCACHE"]).stdout.strip()
    gomodcache = gomodcache_override or (Path(gomodcache_raw) if gomodcache_raw else None)
    if gomodcache is not None and not gomodcache.is_dir():
        print(f"WARN  GOMODCACHE does not exist: {gomodcache}")
        print("      set GOPATH/GOMODCACHE (or pass --gomodcache) so licences can be read")

    entries = []
    for module in sorted(modules):
        version = modules[module]["version"]
        spdx, licence_evidence = licence_for(gomodcache, module, version)

        hits = count_hits(lines, module) if lines else None
        if hits is None:
            linkage, linkage_evidence = "unknown", "symbol evidence not collected (--no-nm or no llvm-nm)"
        elif hits > 0:
            linkage, linkage_evidence = "linked", f"{hits} defined symbol(s) match the module path"
        elif graph and module not in graph:
            linkage, linkage_evidence = "not-linked", "absent from the android/arm64 dependency graph"
        else:
            linkage = "linked"
            linkage_evidence = ("no matching symbol, but the module IS in the android/arm64 dependency "
                               "graph; a zero symbol hit is not evidence of exclusion - treated as shipped")

        entries.append({
            "module": module,
            "version": version,
            "spdx": spdx,
            "licenceEvidence": licence_evidence,
            "linkage": linkage,
            "linkageEvidence": linkage_evidence,
            "symbolHits": hits if hits is not None else -1,
            "obligations": obligations_for(spdx),
        })

    return {
        "schemaVersion": 1,
        "artifact": {
            "name": "libXRay.aar",
            "sha256": aar_hash,
            "expectedSha256": PINNED_AAR_SHA256,
            "aarSizeBytes": aar.stat().st_size,
            "nativeLibrary": NATIVE_LIB,
            "nativeLibraryBytes": so_size,
            "nativeLibrarySha256": so_hash,
            "libxrayTag": PINNED_TAG,
            "libxrayCommit": PINNED_LIBXRAY_COMMIT,
            "xrayCoreCommit": PINNED_XRAY_CORE_COMMIT,
        },
        "method": {
            "inventory": "go version -m <libgojni.so> (embedded Go build info) - primary source",
            "targetGraph": "go list -deps for GOOS=android GOARCH=arm64 - used to justify not-linked",
            "linkage": "llvm-nm --defined-only -C - positive evidence only, never proof of exclusion",
            "policy": ("every module in the build info is treated as shipped unless it is absent from "
                       "the android/arm64 dependency graph"),
        },
        "moduleCount": len(entries),
        "modules": entries,
        "expectedNotShipped": [
            {"module": module, "reason": reason} for module, reason in sorted(EXPECTED_NOT_SHIPPED.items())
        ],
    }


# ---------------------------------------------------------------------------------------- verify
def compare(manifest: dict, actual: dict) -> list[str]:
    problems: list[str] = []

    m_art, a_art = manifest.get("artifact", {}), actual.get("artifact", {})
    for key in ("sha256", "nativeLibrarySha256", "libxrayTag", "libxrayCommit", "xrayCoreCommit"):
        if m_art.get(key) != a_art.get(key):
            problems.append(f"artifact.{key}: manifest={m_art.get(key)!r} actual={a_art.get(key)!r}")

    if m_art.get("sha256") != PINNED_AAR_SHA256:
        problems.append("artifact.sha256 in the manifest does not match the pinned constant")

    m_mods = {e["module"]: e for e in manifest.get("modules", [])}
    a_mods = {e["module"]: e for e in actual.get("modules", [])}

    for module in sorted(set(a_mods) - set(m_mods)):
        problems.append(f"module present in the artifact but missing from the manifest: {module}")
    for module in sorted(set(m_mods) - set(a_mods)):
        problems.append(f"module in the manifest but not in the build info: {module}")

    for module in sorted(set(m_mods) & set(a_mods)):
        m, a = m_mods[module], a_mods[module]
        if m.get("version") != a.get("version"):
            problems.append(f"{module}: version {m.get('version')!r} -> {a.get('version')!r}")
        if m.get("spdx") != a.get("spdx"):
            problems.append(f"{module}: licence {m.get('spdx')!r} -> {a.get('spdx')!r}")
        if m.get("linkage") != a.get("linkage"):
            problems.append(f"{module}: linkage {m.get('linkage')!r} -> {a.get('linkage')!r}")

    for entry in actual.get("expectedNotShipped", []):
        if entry["module"] in a_mods:
            problems.append(f"{entry['module']} is expected to be absent but appears in the build info")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--aar", type=Path, default=DEFAULT_AAR)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--go", default=None, help="path to the go binary (default: found on PATH)")
    parser.add_argument("--nm", default=None, help="path to llvm-nm (default: PATH or $ANDROID_NDK_HOME)")
    parser.add_argument("--libxray-src", type=Path, default=None,
                        help="checkout of XTLS/libXray at the pinned commit, used for the target graph")
    parser.add_argument("--no-nm", action="store_true", help="skip symbol evidence")
    parser.add_argument("--gomodcache", type=Path, default=None,
                        help="override GOMODCACHE (needed when Go env and the shell env disagree)")
    parser.add_argument("--write", action="store_true", help="regenerate the manifest instead of checking")
    args = parser.parse_args()

    go = which_go(args.go)
    if go is None:
        print("FAIL  needs a Go toolchain: 'go version -m' is the primary inventory source.")
        print("      Install Go (the pinned toolchain is 1.27.1) or pass --go <path>.")
        return 2

    nm = None if args.no_nm else which_nm(args.nm)
    if not args.no_nm and nm is None:
        print("WARN  llvm-nm not found; symbol evidence will be reported as 'unknown'")
        print("      (set ANDROID_NDK_HOME or pass --nm)")

    print(f"AAR     : {args.aar}")
    print(f"go      : {go}")
    print(f"llvm-nm : {nm or '(unavailable)'}")
    print()

    actual = collect(args.aar, go, nm, args.libxray_src, not args.no_nm, args.gomodcache)

    print()
    print(f"modules : {actual['moduleCount']}")
    linked = sum(1 for e in actual["modules"] if e["linkage"] == "linked")
    not_linked = sum(1 for e in actual["modules"] if e["linkage"] == "not-linked")
    unknown = sum(1 for e in actual["modules"] if e["linkage"] == "unknown")
    print(f"          linked={linked}  not-linked={not_linked}  unknown={unknown}")
    unknown_licence = [e["module"] for e in actual["modules"] if e["spdx"] == "UNKNOWN"]
    print(f"          licence UNKNOWN: {unknown_licence or 'none'}")
    print()

    if args.write:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        # newline="" keeps the file LF-only on every platform: the manifest is a committed
        # artifact and must not differ between Windows and CI checkouts.
        with args.manifest.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(actual, indent=2, sort_keys=False) + "\n")
        print(f"WROTE {args.manifest}")
        return 0

    if not args.manifest.is_file():
        print(f"FAIL  manifest not found: {args.manifest}")
        print("      create it with: python scripts/verify_native_licenses.py --write")
        return 2

    problems = compare(json.loads(args.manifest.read_text(encoding="utf-8")), actual)
    if problems:
        print(f"DRIFT  {len(problems)} difference(s) between the manifest and the artifact:")
        for problem in problems:
            print(f"  - {problem}")
        print()
        print("If the change is intended, re-run with --write and review the diff.")
        return 1

    print("OK  the manifest matches the pinned artifact")
    print(f"    {actual['moduleCount']} modules, AAR sha256={actual['artifact']['sha256']}")
    if unknown_licence:
        print(f"    NOTE unresolved licences: {', '.join(unknown_licence)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
