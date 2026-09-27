package com.aiwatch.probe.memory

import com.aiwatch.memory.MemoryGateway

/**
 * Where the memory trust surface gets its gateway.
 *
 * The product path returns **null**, and null is a real answer rather than a gap to paper over: there
 * is no `RemoteMemoryGateway` and no deployed canonical memory service yet.
 *
 * It must not fall back to an in-memory store. A local store would make this screen look like a
 * working memory that silently forgets everything on restart, and the user would have no way to tell
 * that from a real one. Showing "记忆服务尚未连接" is the honest rendering of the same fact.
 *
 * [override] exists so instrumentation can drive the real screen against a deterministic gateway, and
 * is the seam that will be replaced by a remote gateway later. When it is, this resolution changes and
 * `MemoryTrustActivity`'s behaviour does not.
 */
object MemoryGatewayRegistry {

    @Volatile
    var override: MemoryGateway? = null

    fun resolve(): MemoryGateway? = override
}
