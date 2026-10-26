package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Cleartext is scoped in code, not only in the network security config.
 *
 * `res/xml/network_security_config.xml` permits cleartext for **every** host, because a
 * self-hosted Jellyfin server on a home network usually has no certificate and refusing that
 * would break the app's only music source. A config that permissive is not a policy, so these
 * tests are the policy: what may be plain HTTP, what may not, and what the sync server's address
 * has to look like before a request is built from it.
 */
class EndpointPolicyTest {

    // ----- the sync server: HTTPS, with no local exception -----

    @Test fun `the sync server accepts an https address`() {
        assertEquals("https://sync.example.net", EndpointPolicy.syncServer("https://sync.example.net"))
        assertEquals("https://sync.example.net", EndpointPolicy.syncServer("  https://sync.example.net/  "))
        assertEquals("https://sync.example.net:8443", EndpointPolicy.syncServer("https://sync.example.net:8443"))
        assertEquals("https://sync.example.net/base", EndpointPolicy.syncServer("https://sync.example.net/base/"))
    }

    @Test fun `a bare host is given https, never http`() {
        // This is what people type for a server they run themselves. The scheme it is given is
        // the whole point: there is no path from a typed hostname to a cleartext sync request.
        assertEquals("https://soundtrail.duckdns.org", EndpointPolicy.syncServer("soundtrail.duckdns.org"))
        assertEquals("https://10.0.0.5:8080", EndpointPolicy.syncServer("10.0.0.5:8080"))
    }

    @Test fun `the sync server rejects plain http, even on a local address`() {
        // Stricter than the general rule on purpose: this endpoint carries an account password, a
        // bearer token and the encrypted library, and the server's own deployment instructions say
        // to put a TLS proxy in front of it and never expose the port directly.
        assertRefused("http://sync.example.net")
        assertRefused("http://192.168.1.20:8080")
        assertRefused("HTTP://SYNC.EXAMPLE.NET")
    }

    @Test fun `the sync server rejects credentials hidden in the authority`() {
        assertRefused("https://user:pass@sync.example.net")
        assertRefused("https://user@sync.example.net")
    }

    @Test fun `the sync server rejects a query, a fragment, a space and a bad port`() {
        assertRefused("https://sync.example.net?a=b")
        assertRefused("https://sync.example.net#here")
        assertRefused("https://sync example.net")
        assertRefused("https://sync.example.net:99999")
        assertRefused("https://sync.example.net:0")
    }

    @Test fun `the sync server rejects path traversal rather than trimming it`() {
        assertRefused("https://sync.example.net/a/../b")
        assertRefused("https://sync.example.net/..")
        assertRefused("https://sync.example.net/base/../../admin")
    }

    @Test fun `the sync server rejects a blank or unusable address`() {
        assertRefused("")
        assertRefused("   ")
        assertRefused("ftp://sync.example.net")
        assertRefused("https://")
    }

    @Test fun `the host is lower-cased so one server cannot look like two`() {
        assertEquals("https://sync.example.net", EndpointPolicy.syncServer("https://SYNC.Example.NET"))
    }

    @Test fun `a refused address says what to type instead`() {
        val message = try {
            EndpointPolicy.syncServer("http://sync.example.net")
            fail("expected a refusal")
            ""
        } catch (e: EndpointPolicyException) {
            e.message.orEmpty()
        }
        assertTrue(message, message.contains("HTTPS"))
        assertTrue(message, message.contains("https://sync.example.net"))
    }

    // ----- which hosts count as the listener's own -----

    @Test fun `loopback and private ranges are on premises`() {
        listOf(
            "localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "10.0.0.5", "192.168.1.20",
            "172.16.0.1", "172.31.255.254", "169.254.1.1", "::1", "[::1]", "nas.local",
            "printer.lan", "jellyfin", "ollama", "fd12:3456::1", "fe80::1"
        ).forEach { assertTrue("$it should be on premises", EndpointPolicy.isOnPremises(it)) }
    }

    @Test fun `public hosts are not on premises`() {
        listOf(
            "example.com", "api.openai.com", "sync.example.net", "soundtrail.duckdns.org",
            "172.32.0.1", "172.15.0.1", "11.0.0.1", "192.169.1.1", "notlocal.com.au", ""
        ).forEach { assertTrue("$it should not be on premises", !EndpointPolicy.isOnPremises(it)) }
    }

    // ----- what may be plain HTTP -----

    @Test fun `jellyfin still accepts a plain http address on a home network`() {
        assertEquals("http://192.168.1.20:8096", EndpointPolicy.jellyfinServer("http://192.168.1.20:8096"))
        assertEquals("http://192.168.1.20:8096", EndpointPolicy.jellyfinServer("192.168.1.20:8096"))
        assertEquals("https://media.example.net", EndpointPolicy.jellyfinServer("https://media.example.net"))
    }

    @Test fun `cleartext is allowed for a host on the listener's own machine or network`() {
        assertNull(EndpointPolicy.cleartextProblem("http://192.168.1.20:8096"))
        assertNull(EndpointPolicy.cleartextProblem("http://localhost:11434"))
        assertNull(EndpointPolicy.cleartextProblem("http://ollama:11434"))
        assertNull(EndpointPolicy.cleartextProblem("https://api.openai.com/v1"))
    }

    @Test fun `cleartext is refused for a public host`() {
        val problem = EndpointPolicy.cleartextProblem("http://api.openai.com/v1")
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("https://"))
        assertTrue(problem, problem.contains("api.openai.com"))
        assertNotNull(EndpointPolicy.cleartextProblem("http://soundtrail.duckdns.org"))
    }

    @Test fun `jellyfin is the one integration allowed cleartext anywhere`() {
        // A self-hosted server on a public name without a certificate is unusual but legitimate,
        // and Jellyfin is the app's only music source — so this one caller may ask for the
        // exception. Everything else has to be on premises to be plain HTTP.
        assertNull(EndpointPolicy.cleartextProblem("http://media.example.net", TransportRule.JELLYFIN))
        assertNotNull(EndpointPolicy.cleartextProblem("http://media.example.net"))
    }

    @Test fun `a scheme that is not http or https is refused outright`() {
        assertNotNull(EndpointPolicy.cleartextProblem("ftp://192.168.1.20"))
        assertNotNull(EndpointPolicy.cleartextProblem("file:///etc/passwd"))
        assertNotNull(EndpointPolicy.cleartextProblem("not a url"))
    }

    @Test fun `requireSecureTransport throws before a request could be opened`() {
        try {
            EndpointPolicy.requireSecureTransport("http://api.openai.com/v1/chat/completions")
            fail("a public cleartext endpoint should be refused")
        } catch (expected: EndpointPolicyException) {
            assertTrue(expected.message!!.contains("https://"))
        }
        // And the permitted cases do not throw.
        EndpointPolicy.requireSecureTransport("https://api.openai.com/v1/chat/completions")
        EndpointPolicy.requireSecureTransport("http://192.168.1.20:8096/Audio/x/universal")
        EndpointPolicy.requireSecureTransport("http://media.example.net", TransportRule.JELLYFIN)
    }

    // ----- the transport rules the config cannot express -----

    @Test fun `redirects are never followed`() {
        // The network security config cannot say this, and neither can a lint rule: it is one line
        // in the request builder. If it is ever removed, a server — or whoever answers for it —
        // could move a request and the bearer token in its headers to a host nobody chose.
        val source = sourceFile("integrations/Http.kt").readText()
        assertTrue(
            "httpRequest must keep instanceFollowRedirects = false",
            source.contains("instanceFollowRedirects = false")
        )
        // The streaming Jellyfin download opens its own connection, so it has to say it too.
        val jellyfin = sourceFile("integrations/JellyfinService.kt").readText()
        assertTrue(
            "the Jellyfin download connection must not follow redirects either",
            jellyfin.contains("instanceFollowRedirects = false")
        )
    }

    @Test fun `every jellyfin request declares the exception it is relying on`() {
        // The strict rule is the default, so a Jellyfin call that forgot to declare itself would
        // fail at request time on a LAN server — the exact thing the config permits and the app
        // depends on. Counting the declarations keeps that from rotting quietly.
        val source = sourceFile("integrations/JellyfinService.kt").readText()
        val declared = Regex("TransportRule\\.JELLYFIN").findAll(source).count()
        val requests = Regex("httpRequest\\(").findAll(source).count()
        // One declaration per httpRequest call, plus the one applied by hand to the streaming
        // download that opens its own connection.
        assertEquals(requests + 1, declared)
    }

    private fun assertRefused(raw: String) {
        try {
            EndpointPolicy.syncServer(raw)
            fail("should have refused: $raw")
        } catch (expected: EndpointPolicyException) {
            assertNotNull(expected.message)
        }
    }

    /** Locates a main-source file without depending on where the test JVM was started. */
    private fun sourceFile(relative: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            listOf("src/main/java/tech/egrie/soundtrail/$relative",
                "app/src/main/java/tech/egrie/soundtrail/$relative").forEach { path ->
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile ?: return File(relative)
        }
        return File(relative)
    }
}
