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

    // ----- what may be plain HTTP: one integration, and it has to ask in writing -----

    @Test fun `jellyfin still accepts a plain http address on a home network`() {
        assertEquals("http://192.168.1.20:8096", EndpointPolicy.jellyfinServer("http://192.168.1.20:8096"))
        assertEquals("http://192.168.1.20:8096", EndpointPolicy.jellyfinServer("192.168.1.20:8096"))
        assertEquals("https://media.example.net", EndpointPolicy.jellyfinServer("https://media.example.net"))
    }

    @Test fun `cleartext is refused everywhere except jellyfin`() {
        // The exception is the integration, not the address. Loopback, the private ranges, a
        // .local name and a bare machine name are all refused under the default rule: "it is only
        // my own network" is an assumption the app cannot check from where it sits, and this is
        // exactly the traffic that carries an API key or a bearer token.
        listOf(
            "http://192.168.1.20:8096", "http://localhost:11434", "http://127.0.0.1:8000",
            "http://[::1]:11434", "http://10.0.0.5:9000", "http://172.16.0.1:8000",
            "http://169.254.1.1:80", "http://ollama:11434", "http://nas.local:11434",
            "http://printer.lan:8080", "http://api.openai.com/v1", "http://soundtrail.duckdns.org"
        ).forEach { address ->
            assertNotNull("$address must be refused under the default rule",
                EndpointPolicy.cleartextProblem(address))
        }
        // And HTTPS is accepted for every one of them, which is the fix the message asks for.
        assertNull(EndpointPolicy.cleartextProblem("https://192.168.1.20:11434"))
        assertNull(EndpointPolicy.cleartextProblem("https://api.openai.com/v1"))
        assertNull(EndpointPolicy.cleartextProblem("https://nas.local:11434"))
    }

    @Test fun `the default rule is the strict one, so forgetting to declare a rule is safe`() {
        // A new integration gets HTTPS by doing nothing at all and has to ask, in writing, for the
        // one exception that exists. The default is what makes that true.
        assertNotNull(EndpointPolicy.cleartextProblem("http://192.168.1.20:11434"))
        assertNull(EndpointPolicy.cleartextProblem("http://192.168.1.20:11434", TransportRule.JELLYFIN))
    }

    @Test fun `a refused cleartext address says to use https and names the host`() {
        val problem = EndpointPolicy.cleartextProblem("http://api.openai.com/v1")
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("https://"))
        assertTrue(problem, problem.contains("api.openai.com"))
        // Naming the one integration that may use plain HTTP is what stops this reading as a bug.
        assertTrue(problem, problem.contains("Jellyfin"))
        assertNotNull(EndpointPolicy.cleartextProblem("http://soundtrail.duckdns.org"))
    }

    @Test fun `jellyfin is the one integration allowed cleartext anywhere`() {
        // A self-hosted server on a public name without a certificate is unusual but legitimate,
        // and Jellyfin is the app's only music source: refusing plain HTTP here would leave the app
        // with nothing to play. So this one caller may ask for the exception — at the call site, in
        // writing — and a public host is no different from a private one under that rule.
        assertNull(EndpointPolicy.cleartextProblem("http://media.example.net", TransportRule.JELLYFIN))
        assertNotNull(EndpointPolicy.cleartextProblem("http://media.example.net"))
        assertNull(EndpointPolicy.cleartextProblem("http://192.168.1.20:8096", TransportRule.JELLYFIN))
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
        // A private address is not a permitted case under the default rule either.
        try {
            EndpointPolicy.requireSecureTransport("http://192.168.1.20:11434/api/tags")
            fail("a cleartext model server on a private address should be refused too")
        } catch (expected: EndpointPolicyException) {
            assertTrue(expected.message!!.contains("https://"))
        }
        // And the permitted cases do not throw: HTTPS anywhere, plain HTTP only for Jellyfin.
        EndpointPolicy.requireSecureTransport("https://api.openai.com/v1/chat/completions")
        EndpointPolicy.requireSecureTransport("https://192.168.1.20:11434/api/tags")
        EndpointPolicy.requireSecureTransport(
            "http://192.168.1.20:8096/Audio/x/universal", TransportRule.JELLYFIN
        )
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
