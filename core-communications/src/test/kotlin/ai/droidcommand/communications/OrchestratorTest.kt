package ai.droidcommand.communications

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic load: a fake model with a fixed delay. These prove the scheduler's guarantees, not model speed or device behaviour. */
class OrchestratorTest {
    private class Harness(val conversations: Int, val perConv: Int, slots: Int, workers: Int = 16, val modelMs: Long = 2, val flooder: String? = null) {
        val pool = Executors.newFixedThreadPool(workers)
        val delivered = CopyOnWriteArrayList<Pair<String, String>>()
        val promptsSeen = ConcurrentHashMap<String, MutableList<String>>()
        val order = ConcurrentHashMap<String, MutableList<Int>>()
        val orch = ConversationOrchestrator(
            pool, slots,
            { s ->
                // Record every memory line the model was shown for this conversation.
                promptsSeen.computeIfAbsent(s.conversation) { CopyOnWriteArrayList() }.addAll(s.memory)
                order.computeIfAbsent(s.conversation) { CopyOnWriteArrayList() }.add(s.message.messageId.substringAfterLast('#').toInt())
                Thread.sleep(modelMs)
                TurnResult("re:${s.message.messageId}", listOf("secret-of-${s.conversation}"))
            },
            { c, t -> delivered.add(c to t) },
            queueCap = 100_000,
        )

        fun run(): Long {
            val t0 = System.nanoTime()
            for (m in 0 until perConv) for (c in 0 until conversations) orch.submit(InboundMessage("c$c", "c$c#$m", "hi $m", m.toLong()))
            waitDone(conversations * perConv)
            return (System.nanoTime() - t0) / 1_000_000
        }

        fun waitDone(n: Int) {
            val end = System.currentTimeMillis() + 120_000
            while (orch.handled.get() < n && System.currentTimeMillis() < end) Thread.sleep(5)
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun check(h: Harness, slots: Int) {
        val total = h.conversations * h.perConv
        assertEquals(total.toLong(), h.orch.handled.get())
        assertEquals(0L, h.orch.failed.get())
        assertEquals(total, h.delivered.size)
        assertTrue(h.orch.maxConcurrent.get() in 1..slots, "max concurrent ${h.orch.maxConcurrent.get()} > $slots")
        // Strict per-conversation order.
        h.order.forEach { (c, seq) -> assertEquals((0 until h.perConv).toList(), seq, "order broken in $c") }
        // Isolation: a conversation's model calls only ever saw that conversation's own canary.
        h.promptsSeen.forEach { (c, seen) -> assertTrue(seen.all { it == "secret-of-$c" }, "memory leaked into $c: ${seen.filter { it != "secret-of-$c" }.take(3)}") }
        // Replies went to the conversation they belong to.
        assertTrue(h.delivered.all { (c, t) -> t.startsWith("re:$c#") })
    }

    @Test fun `20 conversations`() = load(20, 10, 4)

    @Test fun `50 conversations`() = load(50, 10, 6)

    @Test fun `100 conversations`() = load(100, 10, 8)

    private fun load(n: Int, per: Int, slots: Int) {
        val h = Harness(n, per, slots)
        val ms = h.run()
        check(h, slots)
        println("LOAD conversations=$n messages=${n * per} slots=$slots wallMs=$ms maxConcurrent=${h.orch.maxConcurrent.get()} (synthetic: fake model, 2 ms/turn, this JVM only)")
    }

    @Test fun `duplicate ids are accepted once`() {
        val pool = Executors.newFixedThreadPool(2)
        val o = ConversationOrchestrator(pool, 1, { TurnResult("ok") }, { _, _ -> })
        assertEquals(Submit.ACCEPTED, o.submit(InboundMessage("c", "m1", "a", 1)))
        assertEquals(Submit.DUPLICATE, o.submit(InboundMessage("c", "m1", "a", 1)))
        assertEquals(Submit.ACCEPTED, o.submit(InboundMessage("other", "m1", "a", 1))) // ids are per conversation
        pool.shutdown()
    }

    @Test fun `a flooding conversation does not starve the others`() {
        val pool = Executors.newFixedThreadPool(2)
        val finishedAt = ConcurrentHashMap<String, Long>()
        val left = ConcurrentHashMap<String, Int>()
        val o = ConversationOrchestrator(
            pool, 1,
            { s ->
                Thread.sleep(2)
                if (left.merge(s.conversation, -1, Int::plus) == 0) finishedAt[s.conversation] = System.nanoTime()
                TurnResult(null)
            },
            { _, _ -> },
            queueCap = 10_000,
        )
        left["flood"] = 300
        (0 until 300).forEach { o.submit(InboundMessage("flood", "f$it", "x", 0L)) }
        (0 until 5).forEach { c ->
            left["quiet$c"] = 3
            (0 until 3).forEach { m -> o.submit(InboundMessage("quiet$c", "q$m", "x", 0L)) }
        }
        val end = System.currentTimeMillis() + 60_000
        while (o.handled.get() < 315 && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(315L, o.handled.get())
        val flood = finishedAt.getValue("flood")
        (0 until 5).forEach { assertTrue(finishedAt.getValue("quiet$it") < flood, "quiet$it waited for the flood") }
        pool.shutdown()
    }

    @Test fun `queue cap pushes back`() {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(1)
        val o = ConversationOrchestrator(pool, 1, {
            gate.await()
            TurnResult(null)
        }, { _, _ -> }, queueCap = 3)
        val results = (0 until 6).map { o.submit(InboundMessage("c", "m$it", "x", 0)) }
        assertTrue(Submit.REJECTED_FULL in results)
        gate.countDown()
        pool.shutdown()
    }

    @Test fun `pause holds one conversation and resume continues it`() {
        val pool = Executors.newFixedThreadPool(2)
        val seen = CopyOnWriteArrayList<String>()
        val o = ConversationOrchestrator(pool, 2, {
            seen.add(it.conversation)
            TurnResult(null)
        }, { _, _ -> })
        o.pause("p")
        assertEquals(Submit.REJECTED_PAUSED, o.submit(InboundMessage("p", "1", "x", 0)))
        assertEquals(Submit.ACCEPTED, o.submit(InboundMessage("q", "1", "x", 0)))
        val end = System.currentTimeMillis() + 5_000
        while (seen.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(listOf("q"), seen.toList())
        o.resume("p")
        assertEquals(Submit.ACCEPTED, o.submit(InboundMessage("p", "2", "x", 0)))
        while (seen.size < 2 && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(setOf("p", "q"), seen.toSet())
        pool.shutdown()
    }

    @Test fun `emergency stop halts delivery and keeps the queue for a snapshot`() {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(1)
        val delivered = CopyOnWriteArrayList<String>()
        val o = ConversationOrchestrator(pool, 1, {
            gate.await()
            TurnResult("r")
        }, { c, _ -> delivered.add(c) })
        (0 until 5).forEach { o.submit(InboundMessage("c", "m$it", "x", 0)) }
        o.stopAll()
        assertEquals(Submit.REJECTED_STOPPED, o.submit(InboundMessage("c", "late", "x", 0)))
        gate.countDown()
        Thread.sleep(100)
        assertTrue(delivered.isEmpty(), "a reply was delivered after the emergency stop")
        assertTrue(o.snapshot().single().pending.isNotEmpty())
        pool.shutdown()
    }

    @Test fun `restart restores pending work and never answers a handled message twice`() {
        val gate = CountDownLatch(1)
        val pool1 = Executors.newFixedThreadPool(1)
        val first = ConversationOrchestrator(pool1, 1, {
            gate.await()
            TurnResult("r", listOf("fact"))
        }, { _, _ -> })
        (0 until 4).forEach { first.submit(InboundMessage("c", "m$it", "x", 0)) }
        first.stopAll()
        val snap = first.snapshot()
        gate.countDown()
        pool1.shutdownNow()

        val handledIds = CopyOnWriteArrayList<String>()
        val pool2 = Executors.newFixedThreadPool(2)
        val second = ConversationOrchestrator(pool2, 1, {
            handledIds.add(it.message.messageId)
            TurnResult(null)
        }, { _, _ -> })
        second.restore(snap)
        assertEquals(Submit.DUPLICATE, second.submit(InboundMessage("c", "m0", "x", 0))) // already seen before the restart
        val end = System.currentTimeMillis() + 5_000
        while (second.pendingCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(5)
        Thread.sleep(50)
        assertEquals(handledIds.toSet().size, handledIds.size, "a message ran twice: $handledIds")
        assertTrue(handledIds.isNotEmpty())
        pool2.shutdown()
    }
}
