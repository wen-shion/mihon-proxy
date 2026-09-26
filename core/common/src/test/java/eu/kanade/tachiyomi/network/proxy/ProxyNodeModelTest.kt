package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeId
import eu.kanade.tachiyomi.network.proxy.model.NodeProtocol
import eu.kanade.tachiyomi.network.proxy.model.NodeSecurity
import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import eu.kanade.tachiyomi.network.proxy.model.ProxyNode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T-14, the part that belongs to the adapter layer: the split between what the UI may hold and what
 * only the proxy layer may hold.
 *
 * The domain model is checked by reflection because the risk is a *field*, not a behaviour: adding
 * `server` or `uuid` to [ProxyNode] would be convenient today and would start leaking endpoints into
 * logs and bug reports tomorrow.
 */
class ProxyNodeModelTest {

    private val payload =
        """{"protocol":"vless","tag":"tokyo-1","settings":{"address":"example.invalid","port":443,""" +
            """"id":"11111111-2222-3333-4444-555555555555"},"streamSettings":{"security":"reality",""" +
            """"realitySettings":{"publicKey":"PUBLIC-KEY","shortId":"abcd"}}}"""

    @Test
    fun `the template redacts its payload in every string form`() {
        val template = NodeTemplate(payload)
        template.toString() shouldBe "NodeTemplate(<redacted>)"
        template.toString().contains("11111111-2222-3333-4444-555555555555") shouldBe false
        template.toString().contains("PUBLIC-KEY") shouldBe false
        // A container's toString would otherwise print the elements verbatim.
        listOf(template).toString().contains("PUBLIC-KEY") shouldBe false
    }

    @Test
    fun `the template is marked sensitive`() {
        (NodeTemplate::class.java.getAnnotation(Sensitive::class.java) != null) shouldBe true
    }

    @Test
    fun `templates compare by payload`() {
        NodeTemplate(payload) shouldBe NodeTemplate(payload)
        (NodeTemplate(payload) == NodeTemplate("""{"protocol":"vless"}""")) shouldBe false
        NodeTemplate(payload).hashCode() shouldBe NodeTemplate(payload).hashCode()
    }

    @Test
    fun `the domain model exposes no endpoint or credential fields`() {
        val fields = ProxyNode::class.java.declaredFields.map { it.name.lowercase() }
        listOf(
            "server",
            "address",
            "port",
            "uuid",
            "publickey",
            "shortid",
            "password",
            "sni",
            "psk",
        ).forEach { forbidden ->
            (forbidden in fields) shouldBe false
        }
    }

    @Test
    fun `the domain model carries what the list needs`() {
        val node = ProxyNode(
            id = NodeId("local-1"),
            ordinal = 0,
            displayName = "tokyo-1",
            protocol = NodeProtocol.VLESS,
            security = NodeSecurity.REALITY,
        )
        node.id.value shouldBe "local-1"
        node.displayName shouldBe "tokyo-1"
        // Identity is local and stable within a snapshot, and it is not derived from the endpoint.
        node.toString().contains("example.invalid") shouldBe false
        // The remark is reachable through the property but not through the string form.
        node.toString().contains("tokyo-1") shouldBe false
    }

    @Test
    fun `the node redacts the provider remark in every string form`() {
        val remark = "SECRET_PROVIDER_REMARK_123"
        val node = ProxyNode(
            id = NodeId("local-7"),
            ordinal = 3,
            displayName = remark,
            protocol = NodeProtocol.VLESS,
            security = NodeSecurity.REALITY,
        )

        // The UI reads the property, so the remark has to stay reachable there...
        node.displayName shouldBe remark

        // ...but a data class prints every property by default, and a node list is exactly what gets
        // printed when something looks wrong. None of these may carry it.
        node.toString().contains(remark) shouldBe false
        listOf(node).toString().contains(remark) shouldBe false
        mapOf("selected" to node).toString().contains(remark) shouldBe false
        node.copy().toString().contains(remark) shouldBe false
    }
}
