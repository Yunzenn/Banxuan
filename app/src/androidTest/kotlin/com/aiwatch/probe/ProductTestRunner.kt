package com.aiwatch.probe

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.aiwatch.protocol.DeviceIdentityStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

/** Installs a test composition root; the production APK has no fixture/reload switch. */
class ProductTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, ProductTestApplication::class.java.name, context)
}

/** Identity fixtures own a file and coroutine lifetime, independently of the persistent app identity. */
class ProductTestApplication : ProbeApplication() {
    private class IdentityFixture(val root: File) {
        val file = File(root, "identity.bin")
        val job = SupervisorJob()
        val store by lazy { DeviceIdentityStore(file, CoroutineScope(job + Dispatchers.IO)) }
    }

    private var fixture: IdentityFixture? = null

    override val identityStore: DeviceIdentityStore
        get() = fixture?.store ?: super.identityStore

    val fixtureIdentityFile: File
        get() = checkNotNull(fixture) { "Identity fixture not owned by this test" }.file

    fun beginIdentityFixture() {
        check(fixture == null) { "Previous test did not release its identity owner" }
        val root = File(cacheDir, "identity-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        fixture = IdentityFixture(root)
    }

    suspend fun endIdentityFixture() {
        val owned = checkNotNull(fixture)
        try {
            // Only this fixture can address this subject; don't leave synthetic Room rows behind.
            // The damage test restores/removes its file before reaching teardown.
            if (owned.file.exists()) memoryCache.clearSubject(owned.store.getOrCreate().deviceId)
        } finally {
            try {
                owned.job.cancelAndJoin()
            } finally {
                fixture = null
                check(owned.root.deleteRecursively()) { "Cannot remove owned identity fixture" }
            }
        }
    }
}
