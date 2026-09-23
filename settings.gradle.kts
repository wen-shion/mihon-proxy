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
    // degrade into an obscure "unresolved reference: Libxray" inside a compile task. A present but
    // *wrong* artifact fails here too: the hash is computed and checked on every configuration, not
    // only by the installer script, so a substituted AAR cannot be linked silently.
    // ---------------------------------------------------------------------------------------
    val libXrayCoordinate = "com.xtls:libxray:26.9.9"
    val libXraySha256 = "cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb"
    val libXrayAar = layout.settingsDirectory
        .dir("local-repo")
        .file("com/xtls/libxray/26.9.9/libxray-26.9.9.aar")
    if (!libXrayAar.asFile.isFile) {
        throw GradleException(
            buildString {
                appendLine("libXRay.aar is missing from the local Maven repository: ${libXrayAar.asFile}")
                appendLine()
                appendLine("It is deliberately not committed (GPL-3.0-or-later boundary; see licenses/README.md).")
                appendLine("Install the frozen artifact first (the upstream release asset is a different")
                appendLine("build and is rejected by the installer):")
                appendLine()
                appendLine("    python scripts/install_libxray.py --source <path-to-libXRay.aar>")
                appendLine()
                appendLine("Expected coordinate : $libXrayCoordinate")
                append("Expected SHA-256    : $libXraySha256")
            },
        )
    }

    val libXrayActualSha256 = try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(libXrayAar.asFile.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (error: java.io.IOException) {
        throw GradleException("Could not read ${libXrayAar.asFile} to verify its SHA-256: ${error.message}")
    }
    if (!libXrayActualSha256.equals(libXraySha256, ignoreCase = true)) {
        throw GradleException(
            buildString {
                appendLine("The libXray artifact in the local Maven repository is NOT the frozen build.")
                appendLine()
                appendLine("  file     : ${libXrayAar.asFile}")
                appendLine("  expected : $libXraySha256")
                appendLine("  actual   : $libXrayActualSha256")
                appendLine()
                appendLine("The upstream release asset is a different, partially stripped build, and")
                appendLine("substituting it would also invalidate licenses/native-dependencies.json.")
                appendLine()
                append("    python scripts/install_libxray.py --source <path-to-libXRay.aar>")
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
