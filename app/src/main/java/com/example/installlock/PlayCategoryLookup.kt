package com.example.installlock

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Only recent Google Play installs are checked. Network access never blocks a policy sweep. */
internal object PlayCategoryLookup {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private const val RETRY_MS = 60_000L
    private const val REFRESH_MS = 24 * 60 * 60_000L
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024

    fun exemption(context: Context, pkg: PackageInfo): PlayInstallCategory? {
        val c = context.applicationContext
        val selected = Store.immediatePlayCategories(c)
        if (selected.isEmpty()) return null
        val installer = installer(c, pkg.packageName)
        if (installer != PlayCategoryRules.PLAY_STORE) return null
        val prefs = c.getSharedPreferences("play_categories", Context.MODE_PRIVATE)
        val record = PlayCategoryRecord.decode(prefs.getString(pkg.packageName, null))
            ?.takeIf { it.matches(pkg.firstInstallTime, pkg.lastUpdateTime) }
        if (record == null || System.currentTimeMillis() >= record.retryAt) {
            synchronized(pending) {
                if (pending.add(pkg.packageName)) executor.execute {
                    try {
                        val category = fetchCategory(pkg.packageName)
                        val current = c.packageManager.getPackageInfo(pkg.packageName, 0)
                        // An uninstall/reinstall/update while the request was in flight invalidates it.
                        if (current.firstInstallTime == pkg.firstInstallTime && current.lastUpdateTime == pkg.lastUpdateTime &&
                            installer(c, pkg.packageName) == PlayCategoryRules.PLAY_STORE) {
                            prefs.edit().putString(pkg.packageName, PlayCategoryRecord(pkg.firstInstallTime,
                                pkg.lastUpdateTime, category ?: record?.category.orEmpty(), System.currentTimeMillis() +
                                        if (category == null) RETRY_MS else REFRESH_MS).encode()).apply()
                            handler.post { Policy.sweep(c) }
                        }
                    } catch (_: Exception) {
                        // Package removed or unavailable. The next sweep will re-evaluate it.
                    } finally { synchronized(pending) { pending.remove(pkg.packageName) } }
                }
            }
        }
        return PlayCategoryRules.exemption(installer, record?.category, selected)
    }

    private fun installer(c: Context, packageName: String): String? =
        try { c.packageManager.getInstallSourceInfo(packageName).installingPackageName }
        catch (_: Exception) { null }

    private fun fetchCategory(packageName: String): String? {
        // Package names are the only information sent. No account, device ID or usage data is sent.
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=US")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_PAGE_BYTES) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, MAX_PAGE_BYTES + 1 - output.size()))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_PAGE_BYTES) return null
            PlayStoreListing.category(bytes.toString(Charsets.UTF_8), packageName)
        } catch (_: Exception) {
            null
        } finally { connection?.disconnect() }
    }
}
