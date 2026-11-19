package com.clipmax.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinkSecurityTest {

    @Test
    fun testExternalUrlValidationAllowsClipMaxRepoAndRejectsUnsafeSchemes() {
        assertTrue(AndroidBridge.isSafeExternalHttpUrl("https://github.com/ahmad-maxai/ClipMax-complete"))
        assertTrue(AndroidBridge.isSafeExternalHttpUrl("  http://example.com  "))

        assertFalse(AndroidBridge.isSafeExternalHttpUrl(null))
        assertFalse(AndroidBridge.isSafeExternalHttpUrl(""))
        assertFalse(AndroidBridge.isSafeExternalHttpUrl("javascript:alert(1)"))
        assertFalse(AndroidBridge.isSafeExternalHttpUrl("file:///etc/passwd"))
        assertFalse(AndroidBridge.isSafeExternalHttpUrl("content://com.clipmax.app/items"))
    }
}
