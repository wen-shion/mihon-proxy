package eu.kanade.tachiyomi.network

import okhttp3.logging.HttpLoggingInterceptor

/**
 * Builds the header-level logging interceptor used when verbose logging is enabled.
 *
 * Values of credential-bearing headers are redacted before they reach logcat. Proxy credentials
 * travel in `Proxy-Authorization`, and the in-app log dump reads logcat while verbose logging is
 * on, so an unredacted value would end up in crash reports and pasted bug reports. Redaction
 * replaces the value with OkHttp's placeholder and is case-insensitive.
 *
 * [logger] exists so that the redaction can be asserted in a unit test; production call sites use
 * the default platform logger.
 */
internal fun headerLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
    level = HttpLoggingInterceptor.Level.HEADERS
    redactHeader("Proxy-Authorization")
}
