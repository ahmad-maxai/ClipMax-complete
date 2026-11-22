package com.clipmax.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Manages local persistent installation, removal, progress tracking, and storage accounting
 * for ClipMax sticker packs under `filesDir/sticker_packs/<packId>/`.
 * Installed packs and files persist across app restarts and process death.
 */
internal class StickerPackManager(
    context: Context,
    private val prefs: SharedPreferences
) {
    private val appContext = context.applicationContext
    private val packsRootDir = File(appContext.filesDir, "sticker_packs").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClipMax-StickerPacks").apply { isDaemon = true }
    }

    private val activeProgressByPack = ConcurrentHashMap<String, Int>()
    private val lastErrorByPack = ConcurrentHashMap<String, String>()

    @Volatile
    private var installedIds: MutableSet<String> = loadPersistedPackIds()

    init {
        ensureFirstLaunchInitialization()
    }

    private fun loadPersistedPackIds(): MutableSet<String> {
        val initialized = prefs.getBoolean(MediaKeyboardSpec.PREF_STICKER_PACKS_INIT, false)
        if (!initialized) {
            val defaults = StickerCatalog.PACKS.map { it.packId }.toMutableSet()
            prefs.edit()
                .putString(MediaKeyboardSpec.PREF_INSTALLED_STICKER_PACKS, defaults.joinToString(","))
                .putBoolean(MediaKeyboardSpec.PREF_STICKER_PACKS_INIT, true)
                .apply()
            return defaults
        }
        val raw = prefs.getString(MediaKeyboardSpec.PREF_INSTALLED_STICKER_PACKS, null)
        return MediaKeyboardSpec.StickerPackStorePolicy.decodeInstalledIds(raw).toMutableSet()
    }

    private fun persistInstalledIds() {
        val encoded = MediaKeyboardSpec.StickerPackStorePolicy.encodeInstalledIds(installedIds)
        prefs.edit()
            .putString(MediaKeyboardSpec.PREF_INSTALLED_STICKER_PACKS, encoded)
            .putBoolean(MediaKeyboardSpec.PREF_STICKER_PACKS_INIT, true)
            .apply()
    }

    private fun ensureFirstLaunchInitialization() {
        val toMaterialize = installedIds.toList()
        if (toMaterialize.isEmpty()) return
        ioExecutor.execute {
            for (packId in toMaterialize) {
                val pack = StickerCatalog.findPack(packId) ?: continue
                if (!hasAllPackFilesOnDisk(pack)) {
                    runCatching { copyPackAssetsToLocal(pack, null) }
                }
            }
        }
    }

    private fun packDir(packId: String): File {
        val safe = packId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(packsRootDir, safe)
    }

    fun localFileForSticker(item: StickerCatalog.StickerItem): File {
        return File(packDir(item.packId), "${item.id}.png")
    }

    fun hasAllPackFilesOnDisk(pack: StickerCatalog.StickerPack): Boolean {
        val dir = packDir(pack.packId)
        if (!dir.isDirectory) return false
        return pack.items.all { item ->
            val f = File(dir, "${item.id}.png")
            f.isFile && f.length() > 0L
        }
    }

    fun isPackInstalled(packId: String): Boolean = packId in installedIds

    fun installedPackIds(): Set<String> = installedIds.toSet()

    fun installedPacks(): List<StickerCatalog.StickerPack> =
        StickerCatalog.PACKS.filter { it.packId in installedIds }

    fun activeProgress(packId: String): Int? = activeProgressByPack[packId]

    fun lastError(packId: String): String? = lastErrorByPack[packId]

    fun clearError(packId: String) {
        lastErrorByPack.remove(packId)
    }

    fun packDiskBytes(packId: String): Long {
        if (packId !in installedIds) return 0L
        val dir = packDir(packId)
        if (!dir.exists()) return StickerCatalog.findPack(packId)?.estimatedSizeBytes ?: 0L
        val actual = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return if (actual > 0L) actual else (StickerCatalog.findPack(packId)?.estimatedSizeBytes ?: 0L)
    }

    fun totalDiskBytes(): Long {
        return installedIds.sumOf { packDiskBytes(it) }
    }

    fun resolveStickerStream(item: StickerCatalog.StickerItem) =
        run {
            val localFile = localFileForSticker(item)
            if (localFile.isFile && localFile.length() > 0L) {
                localFile.inputStream()
            } else {
                appContext.assets.open(item.assetPath)
            }
        }

    fun ensureStickerCachedForCommit(
        item: StickerCatalog.StickerItem,
        cacheManager: MediaCacheManager
    ): File? {
        val targetCacheName = MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(item)
        cacheManager.getCachedFile(targetCacheName)?.let { return it }

        val localFile = localFileForSticker(item)
        if (localFile.isFile && localFile.length() > 0L) {
            return cacheManager.copyFileToCache(localFile, targetCacheName)
                ?: cacheManager.ensureAssetCached(appContext.assets, item.assetPath, targetCacheName)
        }
        return cacheManager.ensureAssetCached(appContext.assets, item.assetPath, targetCacheName)
    }

    fun installPackAsync(
        packId: String,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val pack = StickerCatalog.findPack(packId)
        if (pack == null) {
            mainHandler.post { onComplete?.invoke(false, "Unknown sticker pack.") }
            return
        }
        if (activeProgressByPack.containsKey(packId)) return
        lastErrorByPack.remove(packId)
        activeProgressByPack[packId] = 0
        mainHandler.post { onProgress?.invoke(0) }

        ioExecutor.execute {
            try {
                copyPackAssetsToLocal(pack) { pct ->
                    activeProgressByPack[packId] = pct
                    mainHandler.post { onProgress?.invoke(pct) }
                }
                installedIds.add(packId)
                persistInstalledIds()
                activeProgressByPack.remove(packId)
                mainHandler.post { onComplete?.invoke(true, null) }
            } catch (e: Throwable) {
                activeProgressByPack.remove(packId)
                val err = e.message ?: "Failed to save pack locally."
                lastErrorByPack[packId] = err
                mainHandler.post { onComplete?.invoke(false, err) }
            }
        }
    }

    fun installAllPacksAsync(
        onEachUpdate: (() -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val missing = StickerCatalog.PACKS.filter { it.packId !in installedIds || !hasAllPackFilesOnDisk(it) }
        if (missing.isEmpty()) {
            mainHandler.post { onComplete?.invoke(true) }
            return
        }
        ioExecutor.execute {
            var allOk = true
            for (pack in missing) {
                activeProgressByPack[pack.packId] = 0
                mainHandler.post { onEachUpdate?.invoke() }
                try {
                    copyPackAssetsToLocal(pack) { pct ->
                        activeProgressByPack[pack.packId] = pct
                        if (pct % 25 == 0 || pct == 100) {
                            mainHandler.post { onEachUpdate?.invoke() }
                        }
                    }
                    installedIds.add(pack.packId)
                } catch (e: Throwable) {
                    allOk = false
                    lastErrorByPack[pack.packId] = e.message ?: "Install failed"
                } finally {
                    activeProgressByPack.remove(pack.packId)
                }
            }
            persistInstalledIds()
            mainHandler.post { onComplete?.invoke(allOk) }
        }
    }

    fun uninstallPack(packId: String): Boolean {
        installedIds.remove(packId)
        activeProgressByPack.remove(packId)
        lastErrorByPack.remove(packId)
        persistInstalledIds()
        return runCatching {
            packDir(packId).deleteRecursively()
            true
        }.getOrDefault(false)
    }

    private fun copyPackAssetsToLocal(pack: StickerCatalog.StickerPack, onProgress: ((Int) -> Unit)?) {
        val dir = packDir(pack.packId)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create pack directory")
        }
        val items = pack.items
        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { idx, item ->
            val target = File(dir, "${item.id}.png")
            if (!target.isFile || target.length() <= 0L) {
                val tmp = File(dir, "${item.id}.png.tmp")
                appContext.assets.open(item.assetPath).use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            val pct = (((idx + 1) * 100) / total).coerceIn(1, 100)
            onProgress?.invoke(pct)
        }
    }
}
