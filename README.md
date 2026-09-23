<div align="center">

<a href="https://mihon.app">
    <img src="./.github/assets/logo.png" alt="Mihon logo" title="Mihon logo" width="80"/>
</a>

# Mihon [App](#)

### Full-featured reader
Discover and read manga, webtoons, comics, and more – easier than ever on your Android device.

[![Discord server](https://img.shields.io/discord/1195734228319617024.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/mihon)
[![GitHub downloads](https://img.shields.io/github/downloads/mihonapp/mihon/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://mihon.app/download)

[![CI](https://img.shields.io/github/actions/workflow/status/mihonapp/mihon/build.yml?labelColor=27303D)](https://github.com/mihonapp/mihon/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/mihonapp/mihon?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/mihon?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/mihon/)

## Download

[![Mihon Stable](https://img.shields.io/github/release/mihonapp/mihon.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://mihon.app/download)
[![Mihon Beta](https://img.shields.io/github/v/release/mihonapp/mihon-preview.svg?maxAge=3600&label=Beta&labelColor=2c2c47&color=1c1c39)](https://mihon.app/download)

*Requires Android 8.0 or higher.*

## Features

<div align="left">

* Local reading of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MangaBaka](https://mangabaka.org), [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/), and [Hikka](https://hikka.io/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.
* Plus much more...

</div>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

Before reporting a new issue, take a look at the [FAQ](https://mihon.app/docs/faq/general), the [changelog](https://mihon.app/changelogs/) and the already opened [issues](https://github.com/mihonapp/mihon/issues); if you got any questions, join our [Discord server](https://discord.gg/mihon).


## Building from source

This fork embeds a VLESS + REALITY core ([XTLS/libXray](https://github.com/XTLS/libXray)) as a native
Android library. That artifact is **not committed** to this repository, so install and verify it once
before building:

```bash
# install the frozen AAR into the git-ignored local Maven repository
python scripts/install_libxray.py --source <path-to-libXRay.aar>
python scripts/verify_native_licenses.py   # re-check the Go dependency inventory of the artifact
```

The build fails at configuration time with an explanatory message while the artifact is missing. That
failure is deliberate: it must not degrade into an obscure "unresolved reference" later.

#### The frozen AAR

The only acceptable artifact is the frozen build recorded in the Phase 1 reproducibility manifest:

| | |
|---|---|
| File | `libXRay.aar` |
| Size | `97,878,333` bytes |
| **SHA-256** | **`cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb`** |
| Built from | XTLS/libXray `v26.9.9` @ `50b95979f5db551bd273165cf469e5daaf791341` |
| Toolchain | Go `1.27.1`, Android NDK `r29` (`29.0.14206865`) |
| Native library | `jni/arm64-v8a/libgojni.so` = `70,624,472` bytes, **unstripped** |

`install_libxray.py` verifies this SHA-256 **before** installing and refuses anything else.

#### What is *not* an acceptable artifact

The upstream release asset (`libxray-android.zip` → `libxray-android/libXray.aar`) is a **different
build** and must not be used as the Phase 2 artifact:

| | frozen | upstream release |
|---|---:|---:|
| SHA-256 | `cd6bd2f5…bf8eb` | `df0cabde…2be3` |
| Size | 97,878,333 | 99,131,846 |
| `arm64-v8a/libgojni.so` | 70,624,472 | **50,879,232** (stripped) |
| `x86_64/libgojni.so` | 72,963,808 | **54,014,264** (stripped) |

Only the 64-bit slices differ, by the size of the removed DWARF data. Because it also changes which
symbols the native library exposes, it would invalidate the licence inventory in this repository — so
the installer rejects it explicitly rather than installing it silently. See
[`licenses/README.md`](./licenses/README.md).

* Release artefacts target **`arm64-v8a` only**. Development builds can add the `x86_64` slice for an
  emulator with `-PproxyDevAbiX86_64`.
* The native library **keeps its debug symbols on purpose** (see the `keepDebugSymbols` note in
  `app/build.gradle.kts`).

### Native dependencies and licensing

The APK statically links components licensed under **GPL-3.0-or-later**
(`github.com/sagernet/sing`, `github.com/sagernet/sing-shadowsocks`, both pulled in by Xray-core
itself), together with MPL-2.0 (Xray-core, REALITY) and permissive dependencies. The inventory is
machine-verified against the embedded Go build info of the pinned artifact and recorded in
[`licenses/native-dependencies.json`](./licenses/native-dependencies.json); a readable summary is in
[`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md), and the verification method is described in
[`licenses/README.md`](./licenses/README.md).

Consequences:

* Building and running this source tree for personal use is unaffected.
* **Distributing a binary that contains the native core makes the whole work subject to
  GPL-3.0-or-later**, including the corresponding-source obligation.
* Public APK distribution from this repository is therefore
  **`NOT APPROVED YET - requires GPLv3 distribution/compliance review before first public binary release.`**


### Repositories

[![mihonapp/website - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=website&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/website/)
[![mihonapp/bitmap.kt - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=bitmap.kt&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/bitmap.kt/)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
