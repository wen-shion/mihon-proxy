plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.core.common"
}

// ---------------------------------------------------------------------------------------------
// libXray - the in-process VLESS + REALITY core (XTLS/libXray v26.9.9, Xray-core v26.9.9).
//
// The AAR is deliberately NOT committed to this repository: it statically links
// GPL-3.0-or-later code (sagernet/sing, sagernet/sing-shadowsocks - see
// licenses/native-dependencies.json), so committing it would turn this repository into a
// distributor of GPL binaries. Fetch and verify the pinned artifact instead:
//
//     python scripts/fetch_libxray.py
//
// A missing artifact is a configuration-time failure on purpose: it must never degrade into an
// obscure "unresolved reference: Libxray" deep inside a compile task.
// ---------------------------------------------------------------------------------------------
val libXrayAarFile = layout.projectDirectory.file("libs/libXRay.aar").asFile
val libXrayAarSha256 = "cd6bd2f5287d23f3648910d8bdf80a4bd7f1fd8f74e6303975a7541f4bcbf8eb"
if (!libXrayAarFile.isFile) {
    throw GradleException(
        buildString {
            appendLine("libXRay.aar is missing: ${libXrayAarFile.absolutePath}")
            appendLine()
            appendLine("It is intentionally not committed to this repository (GPL-3.0-or-later boundary;")
            appendLine("see licenses/README.md). Fetch and verify the pinned artifact first:")
            appendLine()
            appendLine("    python scripts/fetch_libxray.py")
            appendLine()
            append("Expected SHA-256: $libXrayAarSha256")
        },
    )
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    // libXray is a local AAR, fetched (not committed) - see the guard above.
    implementation(files(libXrayAarFile))

    implementation(projects.core.metro)
    implementation(projects.i18n)

    api(libs.logcat)

    api(libs.rxJava)

    api(libs.okhttp.core)
    api(libs.okhttp.logging)
    api(libs.okhttp.brotli)
    api(libs.okhttp.dnsOverHttps)
    api(libs.okio)

    implementation(libs.image.decoder)

    implementation(libs.unifile)
    implementation(libs.archive)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.jsonOkio)

    api(libs.androidx.preference)
    implementation(libs.androidx.webkit)

    implementation(libs.jsoup)

    // Sort
    implementation(libs.natural.comparator)

    // JavaScript engine
    implementation(libs.quickJs)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    implementation(libs.metro.runtime)
}
