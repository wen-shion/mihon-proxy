# Native dependency licensing

`libgojni.so` (the libXray / Xray-core Go runtime) is a **native binary**, so its dependencies never
appear in Gradle's dependency graph and AboutLibraries cannot discover them. This directory holds the
inventory that closes that gap, plus the method used to produce it.

| File | Purpose |
|---|---|
| `native-dependencies.json` | The committed inventory: one entry per Go module, with version, SPDX id, licence evidence, linkage verdict and obligations. |
| `../scripts/verify_native_licenses.py` | Recomputes the inventory from the pinned artifact and compares it with the JSON above. `--write` regenerates it. |
| `../scripts/install_libxray.py` | Installs and SHA-256-verifies the pinned AAR into the local Maven repository (it is not committed). |
| `../THIRD_PARTY_LICENSES.md` | Human-readable rendering of the JSON. |

## Why the AAR is not committed

The artifact statically links **GPL-3.0-or-later** code (`github.com/sagernet/sing`,
`github.com/sagernet/sing-shadowsocks`). Those are dependencies of **Xray-core itself**, not of the
libXray wrapper, so they cannot be removed by choosing a different wrapper.

Committing the binary would make this repository a distributor of GPL binaries. Keeping it out keeps
the GPL obligation attached to whoever chooses to publish a built APK, and not to the source tree:

* Building and running locally - unaffected.
* Publishing a binary containing the core - the whole work becomes subject to GPL-3.0-or-later,
  including the corresponding-source obligation.
* **Public APK distribution is `NOT APPROVED YET - requires GPLv3 distribution/compliance review
  before first public binary release.`**

Unmodified Xray-core is MPL-2.0, which is compatible with GPL-3.0 here only because Exhibit B
("Incompatible With Secondary Licenses") was **not** applied: none of the `.go` files carries that
notice, it appears in `LICENSE` purely as a template. Do not modify Xray-core without re-running the
reasoning in this file, since modification adds MPL file-level source obligations.

## Verification method, and why one source is not enough

The inventory is built from three sources. Using only one of them produces a wrong answer, which is
demonstrated below with the actual readings from the pinned artifact.

### 1. `go version -m` - primary inventory

The `.so` embeds the Go build info recorded at link time, which lists every module with its exact
version. It is read straight out of the artifact, so the inventory is bound to the binary rather than
to a lock file that might have drifted.

### 2. Target-platform dependency graph - decides `not-linked`

`go list -deps` with `GOOS=android GOARCH=arm64` answers "is this module even eligible to be linked
on this platform". A module only counts as `not-linked` when it is **absent from both** the build
info and this graph.

### 3. `llvm-nm --defined-only -C` - positive evidence only

A symbol match **proves** that a module's code was linked. **A zero hit proves nothing**, and this is
not theoretical:

* Symbol text for a package's own symbols is `module.(*Type).Method`, while sub-packages are
  `module/subpkg.Func`. Matching only on `module/` reported **26 of 54** modules as having no
  symbols; matching on `module/` **or** `module.` reports **5**.
* Those 5 (`github.com/google/gopacket`, `github.com/pion/logging`, `github.com/wlynxg/anet`,
  `golang.org/x/exp`, `gopkg.in/yaml.v2`) are all **present in the android/arm64 graph**, so they are
  treated as shipped. Inlining and interface-only use are enough to make symbols disappear.

Policy therefore:

* every module in the build info is treated as **shipped** unless it is absent from the target graph;
* `linked: false` requires the graph evidence, never a symbol miss alone;
* each entry records `linkageEvidence` so the reasoning is auditable rather than implicit.

### Cross-check with the source graph

Compiling the same module graph from the pinned source under a **non-target** GOOS is what puts
Windows-only modules in scope. `golang.zx2c4.com/wintun` and `golang.zx2c4.com/wireguard/windows`
appear only in that Windows source graph; they are absent from the build info **and** from the
android/arm64 graph, and they are recorded under `expectedNotShipped` so that a future AAR cannot
introduce them silently.

## Working with this inventory

Verify (this is the CI gate; a non-zero exit means drift):

```bash
python scripts/verify_native_licenses.py \
    --libxray-src <path-to-XTLS/libXray-checkout>
```

Environment notes:

* A **Go toolchain is required** - `go version -m` is the primary source. The pinned toolchain is
  1.27.1.
* `llvm-nm` is found via `PATH` or `$ANDROID_NDK_HOME`. Without it, linkage is reported as `unknown`
  (the inventory still verifies).
* If the Go toolchain's `GOMODCACHE` differs from your shell's, pass `--gomodcache <path>`,
  otherwise licences are reported as `UNKNOWN` because module `LICENSE` files cannot be read.
* Add `--no-nm` to skip symbol evidence, and `--libxray-src` to include the target-graph evidence.

Regenerate after an intended change, and review the diff:

```bash
python scripts/verify_native_licenses.py --write --libxray-src <path> --gomodcache <path>
```

### Upgrading the AAR

1. Update the pinned values in **both** `scripts/install_libxray.py` and
   `scripts/verify_native_licenses.py` (`PINNED_AAR_SHA256` and friends).
2. Re-run the verifier; it will refuse to pass while the manifest disagrees with the artifact.
3. Review the module diff for licence changes, then `--write` and commit both files together.

## Scope

This is an inventory plus a consistency gate. It is **not legal advice**, and it does not evaluate
whether any particular distribution model is compliant; it exists so that the composition of the
shipped native binary is known, reproducible and auditable.
