package com.resync.app.engine

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.resync.app.data.model.AppCacheItem
import com.resync.app.data.model.AppInfo
import com.resync.app.util.FormatUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton repository managing application cache measurement and surgical cleaning.
 * Serves as the single source of truth for StorageCacheScreen, StorageDetailsScreen,
 * AppDetailScreen, and DashboardScreen (Home broom icon).
 */
object AppCacheRepository {

    private val _appCacheList = MutableStateFlow<List<AppCacheItem>>(emptyList())
    val appCacheList: StateFlow<List<AppCacheItem>> = _appCacheList.asStateFlow()

    private val _totalAppCacheBytes = MutableStateFlow<Long>(0L)
    val totalAppCacheBytes: StateFlow<Long> = _totalAppCacheBytes.asStateFlow()

    private val _isCacheScanning = MutableStateFlow<Boolean>(false)
    val isCacheScanning: StateFlow<Boolean> = _isCacheScanning.asStateFlow()

    private val _cacheAnalyzedCount = MutableStateFlow<Int>(0)
    val cacheAnalyzedCount: StateFlow<Int> = _cacheAnalyzedCount.asStateFlow()

    private val _cacheTotalAppsCount = MutableStateFlow<Int>(0)
    val cacheTotalAppsCount: StateFlow<Int> = _cacheTotalAppsCount.asStateFlow()

    private val _isClearingCache = MutableStateFlow<Boolean>(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    private val _lastCacheFreedMessage = MutableStateFlow<String?>(null)
    val lastCacheFreedMessage: StateFlow<String?> = _lastCacheFreedMessage.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun dismissLastCacheFreedMessage() {
        _lastCacheFreedMessage.value = null
    }

    private fun extractPackageNameFromCachePath(path: String): String? {
        val clean = path.trim()
        val parts = clean.split("/")
        val cacheIndex = parts.indexOfLast { it == "cache" || it == "code_cache" }
        if (cacheIndex > 0) {
            return parts[cacheIndex - 1]
        }
        return null
    }

    /**
     * Scans installed applications cache using real root directory measurement in batch,
     * supplemented with StorageStatsManager.
     */
    fun scanAppCache(context: Context, apps: List<AppInfo>, force: Boolean = false) {
        if (_isCacheScanning.value) return
        if (!force && _appCacheList.value.isNotEmpty()) return

        repositoryScope.launch {
            _isCacheScanning.value = true
            _cacheTotalAppsCount.value = apps.size
            _cacheAnalyzedCount.value = 0

            val isRoot = runCatching { Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot }.getOrDefault(false)
            val storageStatsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            } else null

            val rootCacheByPkg = mutableMapOf<String, Long>()

            if (isRoot) {
                // Leitura Real via Root em 1 único comando em lote somando diretórios internos e externos
                val res = Shell.cmd("du -sk /data/data/*/cache /data/data/*/code_cache /data/user_de/0/*/cache /data/user_de/0/*/code_cache /data/media/0/Android/data/*/cache /sdcard/Android/data/*/cache 2>/dev/null").exec()
                for (line in res.out) {
                    val tokens = line.trim().split(Regex("\\s+"), limit = 2)
                    if (tokens.size == 2) {
                        val kb = tokens[0].toLongOrNull() ?: 0L
                        val path = tokens[1]
                        val pkg = extractPackageNameFromCachePath(path)
                        if (pkg != null && kb > 0L) {
                            rootCacheByPkg[pkg] = (rootCacheByPkg[pkg] ?: 0L) + (kb * 1024L)
                        }
                    }
                }
            }

            val finalItems = mutableListOf<AppCacheItem>()
            var processed = 0

            // Analisa cada app mesclando root real e StorageStatsManager (onde aplicável)
            coroutineScope {
                apps.chunked(30).forEach { chunk ->
                    val chunkResults = chunk.map { app ->
                        async(Dispatchers.IO) {
                            val rootBytes = rootCacheByPkg[app.packageName] ?: 0L
                            var statsBytes = 0L
                            if (!isRoot && storageStatsManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    val stats = storageStatsManager.queryStatsForPackage(
                                        StorageManager.UUID_DEFAULT,
                                        app.packageName,
                                        Process.myUserHandle()
                                    )
                                    statsBytes = stats.cacheBytes
                                } catch (_: Exception) {}
                            }

                            // Quando root está ativo, utiliza estritamente o valor real do disco (evita storaged atrasado)
                            val effectiveBytes = if (isRoot) rootBytes else statsBytes
                            if (effectiveBytes > 10_000L) { // > 10 KB
                                AppCacheItem(app, effectiveBytes)
                            } else null
                        }
                    }.awaitAll().filterNotNull()

                    finalItems.addAll(chunkResults)
                    processed += chunk.size
                    _cacheAnalyzedCount.value = processed
                }
            }

            val sorted = finalItems.sortedByDescending { it.cacheBytes }
            _appCacheList.value = sorted
            _totalAppCacheBytes.value = sorted.sumOf { it.cacheBytes }
            _isCacheScanning.value = false
        }
    }

    /**
     * Clears single application cache via root surgical commands and updates StateFlow immediately.
     * NEVER re-reads from StorageStatsManager after clearing.
     */
    fun clearSingleAppCache(item: AppCacheItem) {
        val pkg = item.app.packageName
        val initialBytes = item.cacheBytes

        // 1. Atualização IMEDIATA no State (remove da lista no mesmo milissegundo)
        _appCacheList.value = _appCacheList.value.filter { it.app.packageName != pkg }
        _totalAppCacheBytes.value = _appCacheList.value.sumOf { it.cacheBytes }

        AppStorageInspector.notifyCacheCleared(pkg)

        repositoryScope.launch {
            val isRoot = runCatching { Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot }.getOrDefault(false)

            if (isRoot) {
                // Limpeza cirúrgica real via Root
                Shell.cmd(
                    "rm -rf /data/data/$pkg/cache/*",
                    "rm -rf /data/data/$pkg/code_cache/*",
                    "rm -rf /data/user_de/0/$pkg/cache/*",
                    "rm -rf /data/user_de/0/$pkg/code_cache/*",
                    "rm -rf /data/media/0/Android/data/$pkg/cache/*",
                    "rm -rf /sdcard/Android/data/$pkg/cache/*"
                ).exec()

                // Medição residual estritamente via 'du -sk' root (NUNCA StorageStatsManager)
                val checkRes = Shell.cmd("du -sk /data/data/$pkg/cache /data/data/$pkg/code_cache /data/user_de/0/$pkg/cache /data/media/0/Android/data/$pkg/cache /sdcard/Android/data/$pkg/cache 2>/dev/null").exec()
                var residualBytes = 0L
                for (line in checkRes.out) {
                    val kb = line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                    residualBytes += (kb * 1024L)
                }

                // Diretórios vazios com menos de 200 KB são considerados completamente limpos
                if (residualBytes <= 200_000L) {
                    residualBytes = 0L
                }

                val freed = (initialBytes - residualBytes).coerceAtLeast(0L)
                if (freed > 0L) {
                    _lastCacheFreedMessage.value = "${FormatUtils.formatBytes(freed)} liberados de ${item.app.appName}!"
                }

                if (residualBytes > 0L) {
                    val current = _appCacheList.value.filter { it.app.packageName != pkg }.toMutableList()
                    current.add(AppCacheItem(item.app, residualBytes))
                    _appCacheList.value = current.sortedByDescending { it.cacheBytes }
                    _totalAppCacheBytes.value = _appCacheList.value.sumOf { it.cacheBytes }
                }
            } else {
                _lastCacheFreedMessage.value = "Cache de ${item.app.appName} limpo!"
            }
        }
    }

    /**
     * Clears all applications cache via root batched commands and pm trim-caches.
     * Updates StateFlow immediately and re-checks residual strictly with du -sk (NEVER StorageStatsManager).
     */
    fun clearAllAppCache() {
        _isClearingCache.value = true
        val itemsToClean = _appCacheList.value.toList()
        val totalBefore = _totalAppCacheBytes.value

        // 1. Atualização IMEDIATA no State (zera tudo instantaneamente em tela)
        _appCacheList.value = emptyList()
        _totalAppCacheBytes.value = 0L

        itemsToClean.forEach {
            AppStorageInspector.notifyCacheCleared(it.app.packageName)
        }

        repositoryScope.launch {
            val isRoot = runCatching { Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot }.getOrDefault(false)

            if (isRoot) {
                // 1. pm trim-caches no sistema
                Shell.cmd("pm trim-caches 999999999999999G").exec()

                // 2. Limpeza cirúrgica em lote por pacote (25 apps por comando)
                itemsToClean.chunked(25).forEach { batch ->
                    val cmdBuilder = StringBuilder()
                    batch.forEach { item ->
                        val p = item.app.packageName
                        cmdBuilder.append("rm -rf /data/data/$p/cache/* /data/data/$p/code_cache/* /data/user_de/0/$p/cache/* /data/user_de/0/$p/code_cache/* /data/media/0/Android/data/$p/cache/* /sdcard/Android/data/$p/cache/*; ")
                    }
                    Shell.cmd(cmdBuilder.toString()).exec()
                }

                // 3. Limpeza ampla de diretórios de cache restantes
                Shell.cmd("rm -rf /data/data/*/cache/* /data/data/*/code_cache/* /data/user_de/0/*/cache/* /data/user_de/0/*/code_cache/* /data/media/0/Android/data/*/cache/* /sdcard/Android/data/*/cache/* 2>/dev/null").exec()

                // 4. Verificação residual estritamente via 'du -sk' (PROIBIDO StorageStatsManager)
                val checkRes = Shell.cmd("du -sk /data/data/*/cache /data/data/*/code_cache /data/user_de/0/*/cache /data/user_de/0/*/code_cache /data/media/0/Android/data/*/cache 2>/dev/null").exec()
                val residualMap = mutableMapOf<String, Long>()
                for (line in checkRes.out) {
                    val tokens = line.trim().split(Regex("\\s+"), limit = 2)
                    if (tokens.size == 2) {
                        val kb = tokens[0].toLongOrNull() ?: 0L
                        val path = tokens[1]
                        val pkg = extractPackageNameFromCachePath(path)
                        // Apenas resíduos reais acima de 200 KB são considerados
                        if (pkg != null && kb > 200L) {
                            residualMap[pkg] = (residualMap[pkg] ?: 0L) + (kb * 1024L)
                        }
                    }
                }

                val appMap = itemsToClean.associateBy { it.app.packageName }
                val remainingItems = mutableListOf<AppCacheItem>()
                for ((pkg, bytes) in residualMap) {
                    val original = appMap[pkg]
                    if (original != null && bytes > 200_000L) {
                        remainingItems.add(AppCacheItem(original.app, bytes))
                    }
                }

                val totalAfter = remainingItems.sumOf { it.cacheBytes }
                val freed = (totalBefore - totalAfter).coerceAtLeast(0L)

                _appCacheList.value = remainingItems.sortedByDescending { it.cacheBytes }
                _totalAppCacheBytes.value = totalAfter
                _isClearingCache.value = false

                if (freed > 0L) {
                    _lastCacheFreedMessage.value = "${FormatUtils.formatBytes(freed)} liberados com sucesso!"
                } else {
                    _lastCacheFreedMessage.value = "Cache de todos os aplicativos limpo com sucesso!"
                }
            } else {
                _isClearingCache.value = false
                _lastCacheFreedMessage.value = "Cache limpo com sucesso!"
            }
        }
    }
}
