pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // ---------------------------------------------------------------------------------------
    // Embedded VLESS + REALITY core (XTLS/libXray).
    //
    // The artifact is not committed: it statically links GPL-3.0-or-later code, so committing it
    // would make this repository a distributor of GPL binaries (see licenses/README.md). It is
    // installed into a local, git-ignored Maven repository by:
    //
    //     python scripts/install_libxray.py --source <path-to-libXRay.aar>
    //
    // A Maven coordinate is used instead of a direct `files(...)` dependency because AGP refuses
    // local .aar file dependencies inside a `com.android.library` module.
    //
    // A missing artifact fails here, at configuration time, with instructions - it must never
    // degrade into an obscure "unresolved reference: Libxray" inside a compile task.
    // ---------------------------------------------------------------------------------------
    val libXrayCoordinate = "com.xtls:libxray:26.9.9"
    val libXrayAar = layout.settingsDirectory
        .dir("local-repo")
        .file("com/xtls/libxray/26.9.9/libxray-26.9.9.aar")
    if (!libXrayAar.asFile.isFile) {
        throw GradleException(
            buildString {
                appendLine("libXRay.aar is missing from the local Maven repository: ${libXrayAar.asFile}")
                appendLine()
                appendLine("It is deliberately not committed (GPL-3.0-or-later boundary; see licenses/README.md).")
                appendLine("Install the pinned, locally built artifact first (the upstream release is")
                appendLine("a different, partially stripped artifact and is rejected by the installer):")
                appendLine()
                appendLine("    python scripts/install_libxray.py --source <path-to-libXRay.aar>")
                appendLine()
                appendLine("Expected coordinate : $libXrayCoordinate")
                append("Expected SHA-256    : cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb")
            },
        )
    }

    @Suppress("UnstableApiUsage")
    repositories {
        maven {
            name = "libXrayLocal"
            url = layout.settingsDirectory.dir("local-repo").asFile.toURI()
            // This repository exists for exactly one artifact. Restricting it by group, module and
            // version means it cannot satisfy any other dependency, and cannot silently serve a
            // different libXray build. scripts/install_libxray.py enforces the same invariant on disk.
            content {
                includeGroup("com.xtls")
                includeModule("com.xtls", "libxray")
                includeVersion("com.xtls", "libxray", "26.9.9")
            }
        }
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Mihon"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:metro")
include(":data")
include(":domain")
include(":i18n")
include(":icons:material-symbols")
include(":icons:simple-icons")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
