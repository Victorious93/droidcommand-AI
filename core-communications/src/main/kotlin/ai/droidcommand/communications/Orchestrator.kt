// Victorious Framework: multi-conversation orchestration. Pure JVM.
//
// Many conversations share ONE inference engine. Each conversation has its own ordered lane and its own memory.
// Guarantees (tested at 20, 50 and 100 conversations, see OrchestratorTest):
//  - messages of one conversation are handled strictly in arrival order, one at a time;
//  - at most [inferenceSlots] model calls run at once, however many conversations are active;
//  - a conversation that floods cannot starve the others (a lane handles ONE message, then yields to the queue's tail);
//  - the model sees only its own conversation's memory: it is handed a snapshot and has no handle to the store;
//  - a message id is handled once, including after a restart when state was snapshotted;
//  - pause, per-conversation pause and emergency stop take effect before the next message starts.
package ai.droidcommand.communications

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class InboundMessage(val conversation: String, val messageId: String, val text: String, val ts: Long)

/** What the model is allowed to see for one turn. Deliberately contains no reference to other conversations. */
class TurnScope(val conversation: String, val memory: List<String>, val message: InboundMessage)

data class TurnResult(val reply: String?, val newFacts: List<String> = emptyList())

fun interface TurnEngine {
    fun run(scope: TurnScope): TurnResult
}

enum class Submit { ACCEPTED, DUPLICATE, REJECTED_STOPPED, REJECTED_PAUSED, REJECTED_FULL }

/** Persisted lane state. Enough to resume after a restart without answering anything twice. */
data class LaneSnapshot(val conversation: String, val seen: List<String>, val pending: List<InboundMessage>, val memory: List<String>, val paused: Boolean)

class ConversationOrchestrator(
    private val pool: ExecutorService,
    inferenceSlots: Int,
    private val engine: TurnEngine,
    /** Receives (conversation, reply). The real one queues through the broker; it is never called for a paused conversation. */
    private val deliver: (String, String) -> Unit,
    private val queueCap: Int = 200,
    private val seenCap: Int = 20_000,
) {
    private class Lane(val id: String) {
        val queue = ArrayDeque<InboundMessage>()
        val seen = LinkedHashSet<String>()
        val memory = ArrayList<String>()
        var scheduled = false
        var paused = false
    }

    private val lanes = ConcurrentHashMap<String, Lane>()
    private val slots = Semaphore(inferenceSlots, true)
    private val active = AtomicInteger()

    @Volatile private var stopped = false
    val maxConcurrent = AtomicInteger()
    val handled = AtomicLong()
    val failed = AtomicLong()

    /** Caller holds the lane lock and has set `scheduled`. If the pool is gone the lane is simply left idle. */
    private fun schedule(l: Lane) {
        try {
            pool.execute { step(l) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            l.scheduled = false
        }
    }

    private fun lane(id: String) = lanes.computeIfAbsent(id) { Lane(it) }

    fun submit(m: InboundMessage): Submit {
        if (stopped) return Submit.REJECTED_STOPPED
        val l = lane(m.conversation)
        synchronized(l) {
            if (m.messageId in l.seen) return Submit.DUPLICATE
            if (l.paused) return Submit.REJECTED_PAUSED
            if (l.queue.size >= queueCap) return Submit.REJECTED_FULL
            l.seen.add(m.messageId)
            while (l.seen.size > seenCap) l.seen.remove(l.seen.first())
            l.queue.addLast(m)
            if (!l.scheduled) {
                l.scheduled = true
                schedule(l)
            }
        }
        return Submit.ACCEPTED
    }

    /** One message, then back to the end of the pool's queue: this is what makes the lanes fair. */
    private fun step(l: Lane) {
        val m: InboundMessage
        val mem: List<String>
        synchronized(l) {
            if (stopped || l.paused || l.queue.isEmpty()) {
                l.scheduled = false
                return
            }
            m = l.queue.removeFirst()
            mem = l.memory.toList()
        }
        try {
            slots.acquire()
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            try {
                val r = engine.run(TurnScope(l.id, mem, m))
                synchronized(l) { l.memory.addAll(r.newFacts) }
                if (!stopped && !l.paused) r.reply?.let { deliver(l.id, it) }
                handled.incrementAndGet()
            } finally {
                active.decrementAndGet()
                slots.release()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            failed.incrementAndGet()
        }
        synchronized(l) {
            if (l.queue.isEmpty() || stopped || l.paused) l.scheduled = false else schedule(l)
        }
    }

    fun pause(conversation: String) = synchronized(lane(conversation)) { lane(conversation).paused = true }

    fun resume(conversation: String) {
        val l = lane(conversation)
        synchronized(l) {
            l.paused = false
            if (!stopped && !l.scheduled && l.queue.isNotEmpty()) {
                l.scheduled = true
                schedule(l)
            }
        }
    }

    /** Emergency stop: nothing new starts, nothing more is delivered. Queued messages stay queued (see [snapshot]). */
    fun stopAll() {
        stopped = true
    }

    /** Messages finished so far, whether the model answered or failed. Lets a caller wait for work it just submitted. */
    fun processed(): Long = handled.get() + failed.get()

    /** Blocks until [processed] reaches [target] or [timeoutMs] passes. True when the target was reached. Never throws on interrupt. */
    fun awaitProcessed(target: Long, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (processed() < target) {
            if (System.currentTimeMillis() >= end) return false
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return processed() >= target
            }
        }
        return true
    }

    fun pendingCount() = lanes.values.sumOf { synchronized(it) { it.queue.size } }

    fun memoryOf(conversation: String): List<String> = lanes[conversation]?.let { synchronized(it) { it.memory.toList() } } ?: emptyList()

    fun snapshot(): List<LaneSnapshot> = lanes.values.map { l -> synchronized(l) { LaneSnapshot(l.id, l.seen.toList(), l.queue.toList(), l.memory.toList(), l.paused) } }

    /** Restores lanes BEFORE any new message is submitted. Pending messages are resubmitted; seen ids stay seen. */
    fun restore(snaps: List<LaneSnapshot>) {
        for (s in snaps) {
            val l = lane(s.conversation)
            synchronized(l) {
                l.seen.addAll(s.seen)
                l.memory.addAll(s.memory)
                l.paused = s.paused
                s.pending.forEach { l.queue.addLast(it) }
                if (!l.paused && !stopped && l.queue.isNotEmpty() && !l.scheduled) {
                    l.scheduled = true
                    schedule(l)
                }
            }
        }
    }
}
