package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.XrayAdapter
import eu.kanade.tachiyomi.network.proxy.XrayErrorCategory
import eu.kanade.tachiyomi.network.proxy.XrayException
import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

// Collaborators for the runtime tests.
//
// Each fake records the calls it received and can fail on demand, so a test can drive the machine
// through every transition and every crash point without a native core or a real socket.

/** An in-memory store whose write can be made to fail, simulating an unrecordable intent. */
internal class FakeStore(initial: Boolean = false) : ProxyEnabledStore {

    var enabled: Boolean = initial
    var failPersist = false
    var persistCalls = 0
    var sharedLog: MutableList<String>? = null

    /** What the machine looked like at each write, for the persist-before-block ordering evidence. */
    val persistSnapshots = mutableListOf<String>()
    var onPersistSnapshot: (() -> Unit)? = null

    override fun enabledSync(): Boolean = enabled

    override fun persist(enabled: Boolean): Boolean {
        persistCalls++
        sharedLog?.add("store.persist")
        onPersistSnapshot?.invoke()
        if (failPersist) return false
        this.enabled = enabled
        return true
    }
}

/**
 * The adapter, recording every call.
 *
 * [startErrors] is consumed one per start, so a test scripts the exact sequence the core answers
 * with; [startRuntimeError] is a non-Xray failure, which is how a process death in the middle of a
 * step is simulated. With [pauseOnStart], every start parks on a deferred the test holds, which is
 * what makes the serialisation tests deterministic.
 */
internal class FakeAdapter : XrayAdapter {

    val events = mutableListOf<String>()
    val startedConfigs = mutableListOf<String>()
    var sharedLog: MutableList<String>? = null
    var onStartSnapshot: (() -> Unit)? = null

    private var nextFreePort = 10808
    val freePortResults = ArrayDeque<Int>()
    var freePortError: XrayException? = null

    val startErrors = ArrayDeque<XrayException>()
    var startRuntimeError: RuntimeException? = null
    var pauseOnStart = false
    val inFlightStarts = mutableListOf<CompletableDeferred<Unit>>()

    val stopCount: Int get() = events.count { it == "stop" }

    override suspend fun version(): String {
        events += "version"
        return "26.9.9"
    }

    override suspend fun freePort(): Int {
        events += "freePort"
        sharedLog?.add("adapter.freePort")
        freePortError?.let { throw it }
        return freePortResults.removeFirstOrNull() ?: nextFreePort++
    }

    override suspend fun validate(configJson: String) {
        events += "validate"
        sharedLog?.add("adapter.validate")
    }

    override suspend fun start(configJson: String) {
        events += "start"
        startedConfigs += configJson
        sharedLog?.add("adapter.start")
        onStartSnapshot?.invoke()
        if (pauseOnStart) {
            val parked = CompletableDeferred<Unit>()
            inFlightStarts += parked
            parked.await()
        }
        startRuntimeError?.let { throw it }
        startErrors.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun stop() {
        events += "stop"
        sharedLog?.add("adapter.stop")
    }

    override suspend fun isRunning(): Boolean {
        events += "state"
        return false
    }

    override suspend fun convertShareLinks(text: String, ageSecretKey: String?): List<NodeTemplate> {
        events += "convert"
        sharedLog?.add("adapter.convert")
        return emptyList()
    }
}

/** The evictor, recording each pass and able to fail one, for the crash-point tests. */
internal class RecordingEvictor : ConnectionEvictor {

    val events = mutableListOf<String>()
    var sharedLog: MutableList<String>? = null
    var evictError: RuntimeException? = null
    var onEvictSnapshot: (() -> Unit)? = null

    override fun cancelAndEvict() {
        events += "evict"
        sharedLog?.add("evictor.evict")
        onEvictSnapshot?.invoke()
        evictError?.let { throw it }
    }
}

/** The probe, recording the ports it was asked about and able to refuse one. */
internal class FakeProbe : LoopbackProbe {

    val ports = mutableListOf<Int>()
    var error: Exception? = null

    override fun assertListening(port: Int) {
        ports += port
        error?.let { throw it }
    }
}

/**
 * A node whose payload the config builder accepts: libXray's own projection shape for a
 * VLESS + REALITY link, so the runtime can get all the way to `Ready` in a JVM test.
 */
internal fun runtimeNodeTemplate(tag: String = "tokyo-1") = NodeTemplate(
    """{"protocol":"vless","tag":"$tag","settings":{"address":"example.invalid","port":443,""" +
        """"id":"11111111-2222-3333-4444-555555555555","encryption":"none"},""" +
        """"streamSettings":{"network":"raw","security":"reality","realitySettings":{""" +
        """"serverName":"example.invalid","fingerprint":"chrome",""" +
        """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"0000"}}}""",
)

internal fun xrayException(category: XrayErrorCategory) = XrayException(category)

internal fun illegalCancellation() = CancellationException("the caller went away")

/**
 * A wired-up runtime over the fakes.
 *
 * [timeline] is the merged, exact order of every call the machine made across all of its
 * collaborators - which is how "the content plane is cleared before the core is touched" and "the
 * intent is recorded before the gate is blocked" are asserted rather than assumed. The per-fake
 * snapshot hooks report what the machine looked like at the moment of a call.
 */
internal class RuntimeFixture(
    val store: FakeStore = FakeStore(),
    val adapter: FakeAdapter = FakeAdapter(),
    val evictor: RecordingEvictor = RecordingEvictor(),
    val probe: FakeProbe = FakeProbe(),
    io: kotlinx.coroutines.CoroutineDispatcher? = null,
) {

    val timeline = mutableListOf<String>()

    val gate = ProxyRouteGate(store)

    val runtime = ProxyRuntime(adapter, evictor, store, gate, probe, io ?: kotlinx.coroutines.Dispatchers.IO)

    init {
        store.sharedLog = timeline
        adapter.sharedLog = timeline
        evictor.sharedLog = timeline
    }

    /** Snapshot of both views of the machine, taken by a fake in the middle of a transition. */
    fun snapshot(): String = "state=${runtime.state.value::class.simpleName} gate=${gate.state()::class.simpleName}"

    /** Lets every fake report what the machine looked like while it was being called. */
    fun wireSnapshots() {
        adapter.onStartSnapshot = { adapter.events += "  while starting: ${snapshot()}" }
        evictor.onEvictSnapshot = { evictor.events += "  while evicting: ${snapshot()}" }
        store.onPersistSnapshot = { store.persistSnapshots += "  while persisting: ${snapshot()}" }
    }
}
