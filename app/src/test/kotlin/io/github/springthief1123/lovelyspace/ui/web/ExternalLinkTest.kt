package io.github.springthief1123.lovelyspace.ui.web

import org.junit.Assert.*
import org.junit.Test

class ExternalLinkTest {
    @Test fun opensOnlyWebLinksTheUserTappedOnAVisiblePage() {
        assertTrue(opensExternally("https", hasGesture = true, shown = true))
        assertTrue(opensExternally("http", hasGesture = true, shown = true))
    }

    @Test fun automaticRedirectsDoNotOpenOtherApps() {
        assertFalse(opensExternally("https", hasGesture = false, shown = true))
    }

    @Test fun preloadingPageNeverOpensOtherApps() {
        assertFalse(opensExternally("https", hasGesture = true, shown = false))
    }

    @Test fun appSchemesAreBlockedEvenWhenTapped() {
        assertFalse(opensExternally("intent", hasGesture = true, shown = true))
        assertFalse(opensExternally("market", hasGesture = true, shown = true))
        assertFalse(opensExternally(null, hasGesture = true, shown = true))
    }
}
