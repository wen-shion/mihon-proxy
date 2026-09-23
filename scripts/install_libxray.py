#!/usr/bin/env python3
"""Install and verify the pinned libXray Android AAR into a local, git-ignored Maven repository.

The AAR is a **build artifact**, not an upstream download
--------------------------------------------------------
The pinned AAR (SHA-256 ``cd6bd2f5…bf8eb``) is the artifact produced by building XTLS/libXray
``v26.9.9`` (commit ``50b95979…``) with the toolchain recorded in the Phase 1 reproducibility
manifest (Go 1.27.1, NDK r29 ``29.0.14206865``). It is stored **unstripped** on purpose, so its
native library keeps full debug information.

The upstream release asset ``libxray-android.zip`` contains a **different** artifact - a partially
stripped build. Measured comparison of the two AARs:

    | entry                     | pinned (built) | upstream release |
    |---------------------------|---------------:|-----------------:|
    | file size                 |     97,878,333 |       99,131,846 |
    | SHA-256                   | cd6bd2f5…bf8eb |     df0cabde…2be3 |
    | jni/arm64-v8a/libgojni.so |     70,624,472 |       50,879,232 |
    | jni/x86_64/libgojni.so    |     72,963,808 |       54,014,264 |
    | jni/armeabi-v7a/…so       |     66,849,724 |       66,770,648 |
    | jni/x86/libgojni.so       |     67,175,504 |       67,097,188 |

(Only the 64-bit slices differ, by the size of the stripped DWARF data.) Downloading the release
therefore **cannot** produce the pinned artifact, which is why this script has no download branch:
it installs an already-built AAR and verifies it against the pinned hash.

Why a local Maven repository
----------------------------
AGP rejects a *direct local .aar file* dependency inside a `com.android.library` module
("Direct local .aar file dependencies are not supported when building an AAR"). Exposing the same
file as a normal Maven coordinate makes AGP treat it like any other library dependency. The AAR is
copied byte-for-byte; it is never unpacked or repacked.

Why it is not committed
-----------------------
The artifact statically links GPL-3.0-or-later code (`github.com/sagernet/sing`,
`github.com/sagernet/sing-shadowsocks`, pulled in by Xray-core itself), so committing it would turn
this repository into a distributor of GPL binaries. See ``licenses/README.md``.

The repository is restricted to a single coordinate
---------------------------------------------------
``settings.gradle.kts`` declares this repository with a ``content`` filter that allows only
``com.xtls:libxray:26.9.9``, and ``--check`` additionally fails when the repository holds anything
else, so it cannot satisfy unrelated dependencies or serve another version.

Usage
-----
::

    python scripts/install_libxray.py --source <path-to-libXRay.aar>   # install a built AAR
    python scripts/install_libxray.py --check                          # verify what is installed
    python scripts/install_libxray.py --print-hash                     # print the installed hash
    python scripts/install_libxray.py --source <aar> --repo-root <dir>  # install elsewhere (tests)

``--source`` verifies the pinned SHA-256, so an upstream release AAR or a differently built AAR is
rejected rather than silently installed.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
import zipfile
from pathlib import Path

# --- pinned artifact -------------------------------------------------------------------------
LIBXRAY_TAG = "v26.9.9"
LIBXRAY_COMMIT = "50b95979f5db551bd273165cf469e5daaf791341"
XRAY_CORE_COMMIT = "52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120"

AAR_SHA256 = "cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb"
AAR_SIZE = 97_878_333

# The upstream release asset, recorded so the difference is documented and machine-checkable.
UPSTREAM_RELEASE_URL = f"https://github.com/XTLS/libXray/releases/download/{LIBXRAY_TAG}/libxray-android.zip"
UPSTREAM_AAR_SHA256 = "df0cabde00c20c08b9ece66e83fddeabcf9aa8061057674a647eb34508c72be3"
UPSTREAM_AAR_SIZE = 99_131_846
UPSTREAM_NATIVE_LIB = "libxray-android/libXray.aar"

# --- local Maven coordinates ------------------------------------------------------------------
GROUP = "com.xtls"
ARTIFACT = "libxray"
VERSION = "26.9.9"

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCAL_REPO = REPO_ROOT / "local-repo"


def coordinates(repo_root: Path) -> tuple[Path, Path]:
    """Returns (aar_path, pom_path) for the pinned coordinate inside *repo_root*."""
    base = repo_root / GROUP.replace(".", os.sep) / ARTIFACT / VERSION
    return base / f"{ARTIFACT}-{VERSION}.aar", base / f"{ARTIFACT}-{VERSION}.pom"


POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>aar</packaging>
  <name>libXray</name>
  <description>XTLS/libXray {tag} ({libxray_commit}), Xray-core {xray_core_commit}. Installed into a
    local Maven repository by scripts/install_libxray.py and deliberately not committed: it
    statically links GPL-3.0-or-later code.</description>
</project>
"""


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repo_contents(repo_root: Path) -> list[Path]:
    """Every regular file below the local repository, relative to it."""
    if not repo_root.is_dir():
        return []
    found = []
    for root, _dirs, files in os.walk(repo_root):
        for name in files:
            found.append(Path(root, name).relative_to(repo_root))
    return sorted(found)


def assert_single_coordinate(repo_root: Path) -> bool:
    """Fails unless the repository holds exactly the pinned coordinate and nothing else."""
    aar, pom = coordinates(repo_root)
    expected = {aar.relative_to(repo_root), pom.relative_to(repo_root)}
    actual = set(repo_contents(repo_root))
    unexpected = sorted(actual - expected)
    if unexpected:
        print("FAIL  the local repository contains artifacts other than the pinned coordinate:")
        for extra in unexpected:
            print(f"        {extra}")
        print(f"      allowed: {GROUP}:{ARTIFACT}:{VERSION} only")
        return False
    print(f"OK    local repository holds only {GROUP}:{ARTIFACT}:{VERSION}")
    return True


def describe(aar: Path) -> tuple[str, int]:
    return sha256_of(aar), aar.stat().st_size


def verify_pinned(aar: Path, label: str) -> bool:
    if not aar.is_file():
        print(f"FAIL  artifact not found: {aar}")
        return False

    digest, size = describe(aar)
    print(f"      file   : {aar}")
    print(f"      size   : {size:,} bytes")
    print(f"      sha256 : {digest}")

    if digest == UPSTREAM_AAR_SHA256:
        print("FAIL  this is the upstream *release* artifact, which is not the pinned build.")
        print(f"      upstream arm64 libgojni.so is stripped (50,879,232 vs 70,624,472 bytes).")
        print(f"      build the pinned artifact from XTLS/libXray {LIBXRAY_TAG} instead.")
        return False
    if digest != AAR_SHA256:
        print("FAIL  SHA-256 does not match the pinned value")
        print(f"      expected: {AAR_SHA256}")
        return False
    if size != AAR_SIZE:
        print(f"WARN  size differs from the recorded {AAR_SIZE:,} bytes")
    print(f"OK    {label} matches the pinned libXRay.aar")
    return True


def check_layout(aar: Path) -> bool:
    """The AAR must still contain the Java binding and the arm64 native core, untouched."""
    wanted = {"classes.jar", "jni/arm64-v8a/libgojni.so"}
    try:
        with zipfile.ZipFile(aar) as archive:
            names = set(archive.namelist())
            sizes = {info.filename: info.file_size for info in archive.infolist()}
    except zipfile.BadZipFile as error:
        print(f"FAIL  not a readable aar: {error}")
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


def write_pom(pom: Path) -> None:
    pom.parent.mkdir(parents=True, exist_ok=True)
    with pom.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            POM_TEMPLATE.format(group=GROUP, artifact=ARTIFACT, version=VERSION, tag=LIBXRAY_TAG,
                                libxray_commit=LIBXRAY_COMMIT, xray_core_commit=XRAY_CORE_COMMIT),
        )
    print(f"OK    wrote {pom.relative_to(pom.parents[3])}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=None,
                        help="install this AAR (verified against the pinned SHA-256)")
    parser.add_argument("--check", action="store_true", help="verify what is already installed")
    parser.add_argument("--print-hash", action="store_true", help="print the SHA-256 of the installed AAR")
    parser.add_argument("--repo-root", type=Path, default=DEFAULT_LOCAL_REPO,
                        help=f"local Maven repository root (default: {DEFAULT_LOCAL_REPO})")
    args = parser.parse_args()

    aar, pom = coordinates(args.repo_root)

    print(f"libXray {LIBXRAY_TAG} @ {LIBXRAY_COMMIT}  (Xray-core @ {XRAY_CORE_COMMIT})")
    print(f"coordinate: {GROUP}:{ARTIFACT}:{VERSION}")
    print(f"repository: {args.repo_root}")
    print()

    if args.print_hash:
        if not aar.is_file():
            print(f"FAIL  not installed: {aar}")
            return 1
        print(sha256_of(aar))
        return 0

    if args.source is not None:
        if not args.source.is_file():
            print(f"FAIL  source AAR not found: {args.source}")
            return 1
        print("verifying the source AAR before installing it ...")
        if not verify_pinned(args.source, "source artifact"):
            print()
            print(f"Build the pinned artifact from XTLS/libXray {LIBXRAY_TAG} @ {LIBXRAY_COMMIT}")
            print("with Go 1.27.1 and NDK r29 (see licenses/README.md), or install an AAR that was")
            print("built that way.")
            return 1
        aar.parent.mkdir(parents=True, exist_ok=True)
        # Byte-for-byte copy; the AAR is never opened or repacked.
        shutil.copyfile(args.source, aar)
        print(f"\ninstalled -> {aar}")
        write_pom(pom)
        print()
        if not check_layout(aar):
            return 1
        print()
        return 0 if assert_single_coordinate(args.repo_root) else 1

    if args.check or aar.is_file():
        if not verify_pinned(aar, "installed artifact"):
            return 1
        print()
        if not check_layout(aar):
            return 1
        print()
        if not assert_single_coordinate(args.repo_root):
            return 1
        if args.check:
            return 0
        print("\nnothing to do: the pinned artifact is already installed")
        return 0

    print(f"FAIL  not installed: {aar}")
    print()
    print("This script installs an already-built AAR; it has no download branch because the upstream")
    print(f"release asset is a different artifact ({UPSTREAM_AAR_SHA256[:12]}…, partially stripped):")
    print(f"  {UPSTREAM_RELEASE_URL}")
    print()
    print("Provide the pinned AAR, for example from a local libXray build:")
    print("  python scripts/install_libxray.py --source <path-to-libXRay.aar>")
    return 1


if __name__ == "__main__":
    sys.exit(main())
