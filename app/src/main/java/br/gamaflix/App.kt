package br.gamaflix

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cache de imagens enxuto: pensado para TVs com ~2 GB de RAM. */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                File(filesDir, CRASH_FILE).writeText("Erro em $stamp\n\n" + error.stackTraceToString())
            } catch (e: Exception) {
                // sem espaço ou sem permissão: segue o fluxo normal de erro
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img"))
                    .maxSizeBytes(80L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
