package eu.kanade.tachiyomi.network.proxy.runtime

/**
 * Whether the user wants the proxy on, persisted synchronously.
 *
 * This is the one input [ProxyRouteGate] needs at construction time, and the gate is constructed
 * before any HTTP request can be made - which is why the read has to be synchronous. A
 * `DataStore`/repository flow would make the first-read async and reopen the "persisted on but the
 * gate still says disabled" window the gate exists to close.
 *
 * Only non-sensitive values live here: the enabled flag. No credential-derived value and no
 * provider remark is ever written through this interface.
 */
interface ProxyEnabledStore {

    /**
     * Reads the persisted flag synchronously.
     *
     * The only I/O this may do is a single boolean read out of an already-open preference file; it is
     * called from gate construction, which happens on the main thread before the first request.
     */
    fun enabledSync(): Boolean

    /**
     * Records the user's intent.
     *
     * Returns `false` when the value could not be persisted. A caller must not continue its
     * transition on `false`: acting first and recording afterwards is what makes a crash revive a
     * proxy the user just turned off. The write happens off the main thread; the caller is
     * responsible for that, because the synchronous read above implies a `commit()`-style write.
     */
    fun persist(enabled: Boolean): Boolean
}

/**
 * The real store, over an app-private preference file.
 *
 * `commit()` rather than `apply()` on purpose: `apply()` is asynchronous and cannot report whether
 * the value reached the disk, and a caller that cannot tell has to guess which fail-closed side to
 * land on. `commit()` is synchronous, so it belongs behind [ProxyEnabledStore] on a worker dispatcher.
 */
class SharedPreferencesProxyEnabledStore(
    private val prefs: android.content.SharedPreferences,
) : ProxyEnabledStore {

    override fun enabledSync(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    override fun persist(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()

    private companion object {
        const val KEY_ENABLED = "proxy_enabled"
        const val DEFAULT_ENABLED = false
    }
}
