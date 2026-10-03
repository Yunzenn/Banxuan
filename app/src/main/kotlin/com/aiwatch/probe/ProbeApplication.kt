package com.aiwatch.probe

import android.app.Application
import com.aiwatch.memory.cache.MemoryCache
import com.aiwatch.memory.cache.RoomMemoryCache
import com.aiwatch.protocol.DeviceIdentityStore
import java.io.File

/**
 * The composition root. It is the `Application` itself, deliberately.
 *
 * An `AppContainer` / `MemoryContainer` / repository-provider layer would be a second composition root
 * for one already-declared object graph. The cache is one more `by lazy` property here, not a reason to
 * introduce a container, and not a reason to add Hilt.
 *
 * Note what this class does **not** know: there is no `MemoryCacheDatabase`, no DAO and no
 * `Room.databaseBuilder` here. `:core-memory-cache-android` keeps Room as an `implementation`
 * dependency, so persistence stays behind the module that owns it.
 */
open class ProbeApplication : Application() {
    val productStore by lazy { com.aiwatch.probe.product.ProductStore(File(noBackupFilesDir, "product")) }
    // One DataStore per process, outside backup/transfer storage.
    open val identityStore: DeviceIdentityStore by lazy {
        DeviceIdentityStore(File(noBackupFilesDir, "device-identity.bin"))
    }

    /**
     * One cache per process, for the whole process lifetime.
     *
     * Process scope rather than Activity scope: the database is a file, and a per-Activity instance
     * would mean a second `RoomDatabase` over the same file with its own connection pool. This is also
     * why the object graph does not need a `ViewModel` to own it.
     */
    val memoryCache: MemoryCache by lazy { RoomMemoryCache.open(this) }
}
