#!/usr/bin/env python3
"""Fetch and verify the pinned libXray Android AAR.

Why this script exists
----------------------
The AAR is intentionally **not committed** to this repository. It statically links
GPL-3.0-or-later code (``github.com/sagernet/sing`` and ``github.com/sagernet/sing-shadowsocks``,
pulled in by Xray-core itself), so committing it here would turn this repository into a
distributor of GPL binaries. See ``licenses/README.md`` and ``THIRD_PARTY_LICENSES.md``.

The build fails at configuration time when the artifact is missing, so every contributor (and CI)
has to run this script once.

Usage
-----
::

    python scripts/fetch_libxray.py              # download the pinned release and verify it
    python scripts/fetch_libxray.py --verify-only  # verify an existing file, download nothing
    python scripts/fetch_libxray.py --local-build   # verify an AAR you built yourself
    python scripts/fetch_libxray.py --print-hash    # print the SHA-256 of the current file

The pinned values below are the ones recorded in the Phase 1 spikes; changing them requires
re-running ``scripts/verify_native_licenses.py`` and updating ``licenses/native-dependencies.json``.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# --- pinned artifact -------------------------------------------------------------------------
LIBXRAY_TAG = "v26.9.9"
LIBXRAY_COMMIT = "50b95979f5db551bd273165cf469e5daaf791341"
XRAY_CORE_COMMIT = "52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120"
RELEASE_URL = f"https://github.com/XTLS/libXray/releases/download/{LIBXRAY_TAG}/libxray-android.zip"

# SHA-256 of the *libXRay.aar* that the release zip contains (not of the zip itself).
AAR_SHA256 = "cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb"
AAR_SIZE = 97_878_333

REPO_ROOT = Path(__file__).resolve().parent.parent
DEST = REPO_ROOT / "core" / "common" / "libs" / "libXRay.aar"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe(path: Path) -> str:
    if not path.is_file():
        return f"{path} (missing)"
    return f"{path} ({path.stat().st_size:,} bytes, sha256={sha256_of(path)})"


def verify(path: Path) -> bool:
    """Checks that *path* is the pinned AAR. Returns True when it matches."""
    if not path.is_file():
        print(f"FAIL  artifact not found: {path}")
        return False

    size = path.stat().st_size
    digest = sha256_of(path)
    print(f"      file   : {path}")
    print(f"      size   : {size:,} bytes")
    print(f"      sha256 : {digest}")

    ok = True
    if digest != AAR_SHA256:
        print("FAIL  SHA-256 does not match the pinned value")
        print(f"      expected: {AAR_SHA256}")
        ok = False
    if size != AAR_SIZE:
        print(f"WARN  size differs from the recorded {AAR_SIZE:,} bytes (not fatal on its own)")
    if ok:
        print("OK    artifact matches the pinned libXRay.aar")
    return ok


def check_inside(aar: Path) -> bool:
    """Sanity-checks the AAR layout: the Java binding plus the arm64 native core must be present."""
    wanted = {
        "classes.jar",
        "jni/arm64-v8a/libgojni.so",
    }
    try:
        with zipfile.ZipFile(aar) as archive:
            names = set(archive.namelist())
            sizes = {info.filename: info.file_size for info in archive.infolist()}
    except zipfile.BadZipFile as error:
        print(f"FAIL  not a readable zip/aar: {error}")
        return False

    ok = True
    for name in sorted(wanted):
        if name in names:
            print(f"OK    contains {name} ({sizes[name]:,} bytes)")
        else:
            print(f"FAIL  missing {name}")
            ok = False
    abis = sorted({n.split("/")[1] for n in names if n.startswith("jni/") and n.endswith(".so")})
    print(f"      ABIs packaged: {', '.join(abis) if abis else '(none)'}")
    return ok


def download(dest: Path) -> int:
    """Downloads the pinned release zip and extracts the AAR to *dest*."""
    with tempfile.TemporaryDirectory(prefix="libxray-") as tmp:
        zip_path = Path(tmp) / "libxray-android.zip"
        print(f"      url    : {RELEASE_URL}")
        tools = []
        if shutil.which("curl"):
            tools = [["curl", "-fL", "--retry", "3", "-o", str(zip_path), RELEASE_URL]]
        elif shutil.which("wget"):
            tools = [["wget", "-O", str(zip_path), RELEASE_URL]]
        if not tools:
            print("FAIL  neither curl nor wget is available; download the zip manually from")
            print(f"      {RELEASE_URL}")
            print("      then re-run with --verify-only after extracting libXRay.aar into")
            print(f"      {dest.parent}")
            return 1

        if subprocess.run(tools[0]).returncode != 0:
            print("FAIL  download failed")
            return 1

        print(f"      downloaded: {zip_path.stat().st_size:,} bytes")
        with zipfile.ZipFile(zip_path) as archive:
            members = [n for n in archive.namelist() if n.endswith("libXRay.aar")]
            if not members:
                print("FAIL  the release zip does not contain libXRay.aar")
                print(f"      contents: {archive.namelist()[:20]}")
                return 1
            dest.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(members[0]) as source, dest.open("wb") as target:
                shutil.copyfileobj(source, target)
            print(f"      extracted  : {members[0]} -> {dest}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify-only", action="store_true", help="verify the existing file, do not download")
    parser.add_argument("--local-build", action="store_true",
                        help="verify an AAR you built yourself (only the structure check is enforced)")
    parser.add_argument("--print-hash", action="store_true", help="print the SHA-256 of the current file")
    parser.add_argument("--output", type=Path, default=DEST, help=f"where the AAR lives (default: {DEST})")
    args = parser.parse_args()

    dest: Path = args.output

    print(f"libXray {LIBXRAY_TAG} @ {LIBXRAY_COMMIT}  (Xray-core @ {XRAY_CORE_COMMIT})")
    print()

    if args.print_hash:
        print(describe(dest))
        return 0

    if args.local_build:
        print("local-build mode: verifying structure only (a self-built AAR has a different hash)")
        if not dest.is_file():
            print(f"FAIL  artifact not found: {dest}")
            return 1
        print()
        return 0 if check_inside(dest) else 1

    if not args.verify_only:
        if dest.is_file() and verify(dest):
            print("\nnothing to do: the pinned artifact is already in place")
            return 0 if check_inside(dest) else 1
        if dest.is_file():
            print("\nthe existing file does not match the pinned hash; re-downloading")
        if (code := download(dest)) != 0:
            return code
        print()

    if not verify(dest):
        return 1
    print()
    if not check_inside(dest):
        return 1

    print("\nNext steps:")
    print("  python scripts/verify_native_licenses.py   # confirm the Go dependency inventory")
    print("  ./gradlew :app:assembleDebug               # build")
    return 0


if __name__ == "__main__":
    sys.exit(main())
