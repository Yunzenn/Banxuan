package com.aiwatch.probe.reminder

import kotlin.test.*

class RemindersTest {
    private class Store : ReminderStorage {
        var records = emptyList<Reminder>()
        var fail = false
        override fun read() = records
        override fun write(records: List<Reminder>) { check(!fail); this.records = records }
    }
    private class Platform : ReminderPlatform {
        var enabled = true
        var scheduled = 0
        var posted = 0
        var removed = 0
        var failSchedule = false
        var failPost = false
        override fun notificationsEnabled() = enabled
        override fun schedule(reminder: Reminder) { scheduled++; check(!failSchedule) }
        override fun remove(id: String) { removed++ }
        override fun post(reminder: Reminder) { posted++; check(!failPost) }
    }
    @Test fun validReminderPersistsBeforeScheduling() {
        val store = Store()
        val port = object : ReminderPlatform {
            override fun notificationsEnabled() = true
            override fun schedule(reminder: Reminder) { assertEquals(listOf(reminder), store.records) }
            override fun remove(id: String) = Unit
            override fun post(reminder: Reminder) = Unit
        }
        val item = Reminders(store, port) { 1000 }.create(" 喝水 ", 2000)
        assertEquals("喝水", item.text)
        assertEquals(ReminderStatus.PENDING, item.status)
    }
    @Test fun invalidContentAndTimeNeverReachPlatform() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        for (text in listOf("", " ", "x".repeat(161), "line\nbreak")) {
            assertFailsWith<IllegalArgumentException> { book.create(text, 2000) }
        }
        for (time in listOf(0L, 1000L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { book.create("hello", time) }
        }
        assertEquals(0, port.scheduled); assertTrue(store.records.isEmpty())
    }
    @Test fun notificationDenialDoesNotSaveOrSchedule() {
        val store = Store(); val port = Platform().apply { enabled = false }
        assertFailsWith<IllegalStateException> { Reminders(store, port) { 1000 }.create("x", 2000) }
        assertTrue(store.records.isEmpty()); assertEquals(0, port.scheduled)
    }
    @Test fun persistenceFailurePreventsAlarm() {
        val store = Store().apply { fail = true }; val port = Platform()
        assertFails { Reminders(store, port) { 1000 }.create("x", 2000) }
        assertEquals(0, port.scheduled)
    }
    @Test fun failedSchedulingIsVisibleAndNotRetriedOnRestore() {
        val store = Store(); val port = Platform().apply { failSchedule = true }; val book = Reminders(store, port) { 1000 }
        assertFails { book.create("x", 2000) }
        assertEquals(ReminderStatus.FAILED, store.records.single().status)
        book.restore(); assertEquals(1, port.scheduled)
    }
    @Test fun duplicateBroadcastsPostOnlyOnce() {
        val store = Store(); val port = Platform(); var time = 1000L
        val book = Reminders(store, port) { time }; val item = book.create("x", 2000)
        time = 2000
        repeat(10) { book.deliver(item.id) }
        assertEquals(1, port.posted); assertEquals(ReminderStatus.POSTED, book.list().single().status)
    }
    @Test fun cancellationSurvivesLateBroadcast() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        val item = book.create("x", 2000)
        book.cancel(item.id); book.deliver(item.id); book.restore()
        assertEquals(0, port.posted); assertEquals(1, port.removed); assertEquals(1, port.scheduled)
        assertEquals(ReminderStatus.CANCELLED, book.list().single().status)
    }
    @Test fun notificationDisabledAfterSaveBecomesBlockedWithoutPosting() {
        val store = Store(); val port = Platform(); var time = 1000L
        val book = Reminders(store, port) { time }; val item = book.create("x", 2000)
        time = 2000; port.enabled = false; book.deliver(item.id)
        assertEquals(0, port.posted); assertEquals(ReminderStatus.BLOCKED, book.list().single().status)
    }
    @Test fun earlyBroadcastDoesNotPostBeforeSelectedTime() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        book.deliver(book.create("x", 2000).id)
        assertEquals(0, port.posted); assertEquals(2, port.scheduled)
    }
    @Test fun postExceptionStaysUncertainAndNeverBlindlyRetries() {
        val store = Store(); val port = Platform().apply { failPost = true }; var time = 1000L
        val book = Reminders(store, port) { time }; val item = book.create("x", 2000)
        time = 2000; book.deliver(item.id); book.deliver(item.id); book.restore()
        assertEquals(1, port.posted); assertEquals(ReminderStatus.POSTING, book.list().single().status)
    }
    @Test fun uncertainPostSurvivesReopenWithoutReplay() {
        val store = Store().apply { records = listOf(Reminder("id", "x", 2000, ReminderStatus.POSTING)) }
        val port = Platform(); val reopened = Reminders(store, port) { 3000 }
        reopened.restore(); reopened.deliver("id")
        assertEquals(0, port.posted); assertEquals(0, port.scheduled)
        assertEquals(ReminderStatus.POSTING, reopened.list().single().status)
    }
    @Test fun acceptedNotificationWithFailedStatusCommitRemainsUncertain() {
        val backing = Store()
        val store = object : ReminderStorage {
            override fun read() = backing.read()
            override fun write(records: List<Reminder>) {
                if (records.any { it.status == ReminderStatus.POSTED }) throw java.io.IOException("commit failed")
                backing.write(records)
            }
        }
        val port = Platform(); var time = 1000L
        val book = Reminders(store, port) { time }; val item = book.create("x", 2000)
        time = 2000
        // The storage error may propagate, but must never rewrite an accepted post as FAILED.
        runCatching { book.deliver(item.id) }
        assertEquals(1, port.posted)
        assertEquals(ReminderStatus.POSTING, backing.records.single().status)
        val reopened = Reminders(store, port) { time }
        reopened.restore(); reopened.deliver(item.id)
        assertEquals(1, port.posted)
        assertEquals(ReminderStatus.POSTING, reopened.list().single().status)
    }
    @Test fun restoreUsesThePersistedReminderIdentity() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        val item = book.create("x", 2000)
        val reopened = Reminders(store, port) { 1000 }; reopened.restore()
        assertEquals(item, reopened.list().single()); assertEquals(2, port.scheduled)
    }
    @Test fun pendingLimitDoesNotEvictExistingWork() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        repeat(32) { book.create("x", 2000) }
        assertFails { book.create("overflow", 2000) }
        assertEquals(32, book.list().size); assertEquals(32, port.scheduled)
    }
    @Test fun terminalHistoryIsBoundedWithoutDroppingPendingWork() {
        val store = Store(); val port = Platform(); val book = Reminders(store, port) { 1000 }
        val pending = book.create("keep", 2000)
        repeat(100) { book.cancel(book.create("history", 2000).id) }
        assertEquals(ReminderStatus.PENDING, book.list().find { it.id == pending.id }?.status)
        assertTrue(book.list().size <= 33)
    }
}
