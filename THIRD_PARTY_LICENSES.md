# Third-party licences

This file summarises the third-party components that are **statically linked into the native
library shipped with this fork's Android build**. It is rendered from
[`licenses/native-dependencies.json`](./licenses/native-dependencies.json), which is the machine-verified
inventory - see [`licenses/README.md`](./licenses/README.md) for how it is produced and checked.

The Kotlin/Java/AndroidX side of the app keeps its own upstream notices and is shown in the in-app
licence screen (AboutLibraries).

## Pinned artifact

| | |
|---|---|
| Component | [XTLS/libXray](https://github.com/XTLS/libXray) `v26.9.9` |
| libXray commit | `50b95979f5db551bd273165cf469e5daaf791341` |
| [Xray-core](https://github.com/XTLS/Xray-core) commit | `52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120` (release tag `v26.9.9`) |
| Android artefact | `libXRay.aar` |
| SHA-256 | `cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb` |
| Native library | `jni/arm64-v8a/libgojni.so` (70,624,472 bytes, arm64-v8a) |
| Go modules recorded in the build info | 54 |

## Summary by licence

| Licence | Modules |
|---|---|
| GPL-3.0-or-later | 2 |
| MPL-2.0 | 2 |
| LGPL-3.0 | 1 |
| Apache-2.0 | 12 |
| MIT | 14 |
| BSD-3-Clause | 21 |
| BSD-2-Clause | 2 |

## Components requiring attention

### GPL-3.0-or-later - affects the licence of a distributed binary

These are dependencies of **Xray-core itself**, not of the libXray wrapper, so they cannot be
removed by choosing a different wrapper. They are proven to be linked by symbol presence in
`libgojni.so`.

| Module | Version | Linked (symbols) | Obligations |
|---|---|---|---|
| `github.com/sagernet/sing` | `v0.5.1` | 573 | attribution, full-corresponding-source-on-distribution, no-additional-restrictions |
| `github.com/sagernet/sing-shadowsocks` | `v0.2.7` | 341 | attribution, full-corresponding-source-on-distribution, no-additional-restrictions |

**Consequence:** distributing an APK that contains the native core makes the whole work subject to
GPL-3.0-or-later, including the corresponding-source obligation. Public APK distribution from this
repository is `NOT APPROVED YET - requires GPLv3 distribution/compliance review before first public
binary release.`

### MPL-2.0 - file-level copyleft

| Module | Version | Linked (symbols) | Note |
|---|---|---|---|
| `github.com/xtls/reality` | `v0.0.0-20260908062103-8cdf7bf9c7f0` | 781 | upstream source at the pinned commit; unmodified |
| `github.com/xtls/xray-core` | `v1.260327.1-0.20260908222543-52a412d9e2f5` | 11636 | upstream source at the pinned commit; unmodified |

Xray-core is used **unmodified**, so the obligation is limited to making the source of the covered
files available at the upstream commit above. Exhibit B ("Incompatible With Secondary Licenses")
is not applied anywhere in the tree, which is what makes MPL-2.0 combinable with GPL-3.0 here.

### LGPL-3.0 with a static-linking exception

| Module | Version | Linked (symbols) | Obligations |
|---|---|---|---|
| `github.com/juju/ratelimit` | `v1.0.2` | 24 | attribution, relinking-or-static-linking-exception |

`github.com/juju/ratelimit` carries an explicit static-linking exception, so its "minimal
corresponding source" requirement does not apply to this static link.

### Permissive and other licences

No additional obligations beyond attribution:

* **Apache-2.0** (12) - `github.com/google/btree`, `github.com/jackpal/go-nat-pmp`, `github.com/klauspost/compress`, `github.com/libp2p/go-nat`, `github.com/pelletier/go-toml`, `github.com/pires/go-proxyproto`, `github.com/vishvananda/netlink`, `github.com/vishvananda/netns`, `google.golang.org/genproto/googleapis/rpc`, `google.golang.org/grpc`, `gopkg.in/yaml.v2`, `gvisor.dev/gvisor`
* **MIT** (14) - `github.com/andybalholm/brotli`, `github.com/apernet/quic-go`, `github.com/ghodss/yaml`, `github.com/klauspost/cpuid/v2`, `github.com/koron/go-ssdp`, `github.com/pion/dtls/v3`, `github.com/pion/logging`, `github.com/pion/stun/v3`, `github.com/pion/transport/v4`, `github.com/quic-go/qpack`, `github.com/robfig/cron/v3`, `github.com/xtls/libxray`, `golang.zx2c4.com/wireguard`, `lukechampine.com/blake3`
* **BSD-3-Clause** (21) - `github.com/cloudflare/circl`, `github.com/google/gopacket`, `github.com/google/uuid`, `github.com/libp2p/go-netroute`, `github.com/metacubex/age`, `github.com/metacubex/hkdf`, `github.com/metacubex/hpke`, `github.com/metacubex/mlkem`, `github.com/miekg/dns`, `github.com/refraction-networking/utls`, `github.com/wlynxg/anet`, `go4.org/netipx`, `golang.org/x/crypto`, `golang.org/x/exp`, `golang.org/x/mobile`, `golang.org/x/net`, `golang.org/x/sync`, `golang.org/x/sys`, `golang.org/x/text`, `golang.org/x/time`, `google.golang.org/protobuf`
* **BSD-2-Clause** (2) - `github.com/gorilla/websocket`, `github.com/huin/goupnp`

## Modules not shipped

Recorded so that a future artifact cannot introduce them silently. They appear only in a non-target
(Windows) source graph and are absent from both the build info and the android/arm64 dependency graph.

| Module | Reason |
|---|---|
| `golang.zx2c4.com/wintun` | Windows-only; absent from build info and from the android/arm64 graph |
| `golang.zx2c4.com/wireguard/windows` | Windows-only; absent from build info and from the android/arm64 graph |

## Verification

```bash
python scripts/verify_native_licenses.py --libxray-src <path-to-XTLS/libXray-checkout>
```

The command exits non-zero when the committed inventory disagrees with the pinned artifact.
This file is documentation only and is *not* a legal opinion.

