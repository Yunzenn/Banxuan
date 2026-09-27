package com.aiwatch.memory

/**
 * A test double for [MemoryStore] - **not** production persistence.
 *
 * It exists so the gateway's semantics can be exercised without a database, a network or a device. Any
 * durable store - the watch-side cache, the server-side canonical store - is a separate implementation
 * with its own evidence layer. This one is deliberately dumb: records in a map, no opinions, no rules.
 */
class InMemoryMemoryStore : MemoryStore {

    private val records = LinkedHashMap<String, CanonicalMemory>()

    override suspend fun getById(id: MemoryId): CanonicalMemory? = records[id.value]

    override suspend fun findAllByScopedIdentity(identity: ScopedMemoryIdentity): List<CanonicalMemory> =
        records.values.filter { it.scopedIdentity == identity }

    override suspend fun put(memory: CanonicalMemory) {
        records[memory.id.value] = memory
    }

    override suspend fun delete(id: MemoryId): Boolean = records.remove(id.value) != null

    override suspend fun list(): List<CanonicalMemory> = records.values.toList()
}
