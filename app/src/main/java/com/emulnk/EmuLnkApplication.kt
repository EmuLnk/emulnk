package com.emulnk

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.emulnk.core.memory.MemoryRepository
import com.emulnk.core.memory.MemoryService
import com.emulnk.data.ConfigManager
import com.emulnk.model.AppConfig

class EmuLnkApplication : Application(), ImageLoaderFactory {
    val memoryService: MemoryService by lazy {
        val host = try {
            ConfigManager(this).getAppConfig().emulatorHost
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("EmuLnkApplication", "Failed to read emulator host: ${e.message}")
            AppConfig.DEFAULT_HOST
        }
        MemoryService(MemoryRepository(host = host))
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
