package com.clipmax.app

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ExpiryProtectionTest {

    private lateinit var tempDir: File
    private lateinit var store: ClipStore
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("clipmax_expiry_test").toFile()
        store = ClipStore(tempDir)
        prefs = InMemorySharedPreferences()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testExpiryLockedPersistsAcrossRestartsRenamePinSplitMergeAndHistory() {
        val item1 = store.save(
            id = null,
            title = "Secret Token",
            content = "ABCDEFGH",
            pinned = false,
            isExpiryLocked = true
        )
        assertTrue(item1.isExpiryLocked)
        assertFalse(item1.pinned)

        // Restart store instance and verify persistence
        val restarted = ClipStore(tempDir)
        val loaded = restarted.getItem(item1.id)!!
        assertTrue(loaded.isExpiryLocked)
        assertFalse(loaded.pinned)

        // Rename preserves expiry lock
        val renamed = restarted.rename(item1.id, "Renamed Token")
        assertTrue(renamed.isExpiryLocked)

        // Pin & Unpin independently preserve expiry lock
        val pinned = restarted.setPinned(item1.id, true)
        assertTrue(pinned.pinned)
        assertTrue(pinned.isExpiryLocked)

        val unpinned = restarted.setPinned(item1.id, false)
        assertFalse(unpinned.pinned)
        assertTrue(unpinned.isExpiryLocked)

        // Split preserves parent expiry lock on both halves
        val splitRes = restarted.split(item1.id)!!
        assertTrue(splitRes.part1Item.isExpiryLocked)
        assertTrue(splitRes.part2Item.isExpiryLocked)

        // Unlock one half and merge back -> merged item remains locked if either input was locked
        val unlockedHalf = restarted.setExpiryLocked(splitRes.part2Item.id, false)
        assertFalse(unlockedHalf.isExpiryLocked)
        val merged = restarted.merge(splitRes.part1Item.id, unlockedHalf.id).mergedItem
        assertTrue(merged.isExpiryLocked)

        // Atomic history transaction restores expiry lock accurately
        restarted.applyHistoryTransaction(
            deleteIds = listOf(merged.id),
            restoreRecords = listOf(
                RestoreRecord(
                    id = item1.id,
                    title = renamed.title,
                    content = "ABCDEFGH",
                    pinned = false,
                    createdAt = renamed.createdAt,
                    isExpiryLocked = true
                )
            )
        )
        val restored = restarted.getItem(item1.id)!!
        assertTrue(restored.isExpiryLocked)
    }

    @Test
    fun testBackwardCompatibleReadOfLegacyV1BinaryMetadata() {
        val storeBase = File(tempDir, "clipmax_store")
        val metaDir = File(storeBase, "metadata")
        val contentDir = File(storeBase, "content")
        val legacyId = "legacy-v1-item"
        val legacyContentFile = "$legacyId-1000.txt"
        File(contentDir, legacyContentFile).writeText("Legacy plain clip", StandardCharsets.UTF_8)

        // Write binary header matching META_VERSION_V1 = 1 (without trailing isExpiryLocked boolean)
        val v1File = File(metaDir, "$legacyId.bin")
        DataOutputStream(FileOutputStream(v1File)).use { out ->
            out.writeInt(0x434D4158) // META_MAGIC ("CMAX")
            out.writeInt(1)          // META_VERSION_V1
            out.writeUTF(legacyId)
            out.writeUTF("Legacy Clip")
            out.writeLong(10_000L)
            out.writeLong(10_000L)
            out.writeInt(17)
            out.writeLong(17L)
            out.writeBoolean(true)
            out.writeUTF(legacyContentFile)
            out.flush()
        }

        val item = store.getItem(legacyId)
        assertNotNull(item)
        assertTrue(item!!.pinned)
        assertFalse("Legacy v1 items must safely default to unlocked", item.isExpiryLocked)
        assertEquals("Legacy plain clip", store.readContent(item))

        // Locking legacy item upgrades its metadata file transparently
        val locked = store.setExpiryLocked(legacyId, true)
        assertTrue(locked.isExpiryLocked)
        assertTrue(store.getItem(legacyId)!!.isExpiryLocked)
    }

    @Test
    fun testAllFourPinAndLockCombinationsWorkIndependently() {
        val a = store.save(null, "Unlocked Unpinned", "1", pinned = false, isExpiryLocked = false)
        val b = store.save(null, "Locked Unpinned", "2", pinned = false, isExpiryLocked = true)
        val c = store.save(null, "Unlocked Pinned", "3", pinned = true, isExpiryLocked = false)
        val d = store.save(null, "Locked Pinned", "4", pinned = true, isExpiryLocked = true)

        assertFalse(a.pinned); assertFalse(a.isExpiryLocked)
        assertFalse(b.pinned); assertTrue(b.isExpiryLocked)
        assertTrue(c.pinned);  assertFalse(c.isExpiryLocked)
        assertTrue(d.pinned);  assertTrue(d.isExpiryLocked)

        // Unlocking d must NOT unpin d
        val dUnlocked = store.setExpiryLocked(d.id, false)
        assertTrue(dUnlocked.pinned)
        assertFalse(dUnlocked.isExpiryLocked)

        // Unpinning b/d or pinning b: check b stays locked after unlock->pin->unpin
        val bPinned = store.setPinned(b.id, true)
        assertTrue(bPinned.pinned)
        assertTrue(bPinned.isExpiryLocked)
        val bUnpinned = store.setPinned(b.id, false)
        assertFalse(bUnpinned.pinned)
        assertTrue(bUnpinned.isExpiryLocked)
    }

    @Test
    fun testCleanupSkipsLockedItemsEvenPastExpiryAndRemovesUnlockedExpiredItems() {
        val now = 1_000_000_000L
        val unlockedExpired = store.save(null, "Temp", "expires", pinned = false, isExpiryLocked = false)
        val lockedExpired = store.save(null, "Vault", "protected", pinned = false, isExpiryLocked = true)
        val unlockedFuture = store.save(null, "Fresh", "still valid", pinned = false, isExpiryLocked = false)
        val pinnedExpired = store.save(null, "Pin", "pinned", pinned = true, isExpiryLocked = false)

        CapturePreferences.setExpiry(prefs, unlockedExpired.id, now - 5_000L)
        CapturePreferences.setExpiry(prefs, lockedExpired.id, now - 5_000L)
        CapturePreferences.setExpiry(prefs, unlockedFuture.id, now + 60_000L)
        CapturePreferences.setExpiry(prefs, pinnedExpired.id, now - 5_000L)

        val removedCount = CapturePreferences.cleanupExpired(store, prefs, now)
        assertEquals(1, removedCount)

        assertNull("Expired unlocked item must be removed", store.getItem(unlockedExpired.id))
        assertNotNull("Expiry-locked item must NEVER be deleted past expiry", store.getItem(lockedExpired.id))
        assertNotNull("Unexpired unlocked item must stay", store.getItem(unlockedFuture.id))
        assertNotNull("Pinned item must NEVER be deleted", store.getItem(pinnedExpired.id))

        // Stale expiry key for locked/pinned items should also be cleared so no stale expiry lingers
        assertNull(CapturePreferences.getExpiry(prefs, lockedExpired.id))
        assertNull(CapturePreferences.getExpiry(prefs, pinnedExpired.id))
    }

    @Test
    fun testUnlockRecalculatesFreshExpiryWindowOrClearsUnderForeverOrOff() {
        val unlockTime = 2_000_000_000L
        val item = store.save(null, "Key", "1234", pinned = false, isExpiryLocked = true)

        // Simulate an old stale expiry stored far in the past while locked
        CapturePreferences.setExpiry(prefs, item.id, unlockTime - 86_400_000L)

        // Unlock under 1h retention -> must compute fresh expiry = unlockTime + 1h, NOT old timestamp
        CapturePreferences.setMode(prefs, "1h")
        store.setExpiryLocked(item.id, false)
        val fresh1h = CapturePreferences.recalculateExpiryOnUnlock(prefs, item.id, "1h", unlockTime)
        assertEquals(unlockTime + 3_600_000L, fresh1h)
        assertEquals(fresh1h, CapturePreferences.getExpiry(prefs, item.id))
        // Immediately running cleanup right after unlock must keep it!
        assertEquals(0, CapturePreferences.cleanupExpired(store, prefs, unlockTime + 1_000L))
        assertNotNull(store.getItem(item.id))

        // Unlock under custom retention (30m)
        val unlockCustom = CapturePreferences.recalculateExpiryOnUnlock(prefs, item.id, "custom:30m", unlockTime)
        assertEquals(unlockTime + 30L * 60_000L, unlockCustom)

        // Unlock under Forever or Off -> no expiry timestamp scheduled
        CapturePreferences.setMode(prefs, "forever")
        assertNull(CapturePreferences.recalculateExpiryOnUnlock(prefs, item.id, "forever", unlockTime))
        assertNull(CapturePreferences.getExpiry(prefs, item.id))

        CapturePreferences.setMode(prefs, "off")
        assertNull(CapturePreferences.recalculateExpiryOnUnlock(prefs, item.id, "off", unlockTime))
        assertNull(CapturePreferences.getExpiry(prefs, item.id))
    }

    @Test
    fun testNativeZipBackupAndRestorePreservesExpiryLock() {
        val locked = store.save(null, "Vault Clip", "vault data", pinned = false, isExpiryLocked = true)
        val unlocked = store.save(null, "Normal Clip", "normal data", pinned = true, isExpiryLocked = false)

        val zipOut = ByteArrayOutputStream()
        store.backup(zipOut)

        val restoreDir = Files.createTempDirectory("clipmax_restore_test").toFile()
        try {
            val restoredStore = ClipStore(restoreDir)
            val count = restoredStore.restore(ByteArrayInputStream(zipOut.toByteArray()))
            assertEquals(2, count)

            val rLocked = restoredStore.getItem(locked.id)!!
            assertTrue(rLocked.isExpiryLocked)
            assertFalse(rLocked.pinned)
            assertEquals("vault data", restoredStore.readContent(rLocked))

            val rUnlocked = restoredStore.getItem(unlocked.id)!!
            assertFalse(rUnlocked.isExpiryLocked)
            assertTrue(rUnlocked.pinned)
            assertEquals("normal data", restoredStore.readContent(rUnlocked))
        } finally {
            restoreDir.deleteRecursively()
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toMutableSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = REMOVE_SENTINEL
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v === REMOVE_SENTINEL) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        companion object {
            private val REMOVE_SENTINEL = Any()
        }
    }
}
