package com.shinigami11.app

import com.shinigami11.app.UrlPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private fun decide(
        scheme: String?,
        host: String?,
        main: Boolean = true,
        redirect: Boolean = false,
    ) = UrlPolicy.decide(scheme, host, main, redirect)

    @Test fun internalHttpsStaysInApp() =
        assertEquals(Decision.Allow, decide("https", "11.shinigami.asia"))

    @Test fun hostMatchIsCaseInsensitive() =
        assertEquals(Decision.Allow, decide("HTTPS", "11.SHINIGAMI.ASIA"))

    @Test fun subdomainStaysInApp() =
        assertEquals(Decision.Allow, decide("https", "cdn.11.shinigami.asia"))

    @Test fun lookalikeHostsAreNotInternal() {
        assertFalse(UrlPolicy.isInternalHost("evil11.shinigami.asia"))
        assertFalse(UrlPolicy.isInternalHost("11.shinigami.asia.evil.com"))
        assertFalse(UrlPolicy.isInternalHost("shinigami.asia"))
        assertFalse(UrlPolicy.isInternalHost(null))
        assertTrue(UrlPolicy.isInternalHost("11.shinigami.asia"))
    }

    @Test fun internalHttpIsUpgraded() =
        assertEquals(Decision.UpgradeToHttps, decide("http", "11.shinigami.asia"))

    @Test fun tappedExternalLinkOpensBrowser() =
        assertEquals(Decision.OpenExternally, decide("https", "example.com"))

    @Test fun externalHttpOpensBrowser() =
        assertEquals(Decision.OpenExternally, decide("http", "example.com"))

    @Test fun httpsRedirectIsFollowedInApp() =
        assertEquals(Decision.Allow, decide("https", "example.com", redirect = true))

    @Test fun httpRedirectToOtherHostIsNotLoadedInApp() =
        assertEquals(Decision.OpenExternally, decide("http", "example.com", redirect = true))

    @Test fun subFramesAlwaysLoad() =
        assertEquals(Decision.Allow, decide("https", "challenges.example.com", main = false))

    @Test fun mailAndPhoneGoToOtherApps() {
        assertEquals(Decision.OpenExternally, decide("mailto", null))
        assertEquals(Decision.OpenExternally, decide("tel", null))
    }

    @Test fun dangerousSchemesAreBlocked() {
        for (s in listOf("javascript", "intent", "file", "content", "data", "market", null)) {
            assertEquals("scheme=$s", Decision.Block, decide(s, "11.shinigami.asia"))
        }
    }
}
