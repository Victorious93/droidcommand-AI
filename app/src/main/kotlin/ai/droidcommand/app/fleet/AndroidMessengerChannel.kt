package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.MessengerChannel
import ai.droidcommand.fleet.messenger.MessengerSession
import ai.droidcommand.fleet.messenger.MessengerWire
import ai.droidcommand.fleet.messenger.PackageIdentity
import ai.droidcommand.fleet.messenger.PairedNode
import ai.droidcommand.fleet.messenger.PendingReply
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Binds `<package>.action.BRIDGE` and speaks the Messenger framing: request = `Message.what=1` with the
 * envelope JSON in Bundle key `request`; reply = `Message.what=2` with the response JSON in key `response`.
 * This class is only plumbing. Envelope building and response validation are in `core-fleet-messenger`, and it
 * is only ever called after `NodeTrustVerifier` has trusted the package.
 *
 * Defence in depth on top of that check (the verified [PackageIdentity] is passed in):
 *  - the bind intent names the paired package, and the resolved service must belong to that package AND to the
 *    verified UID, so a package swapped between the trust check and the bind is refused before binding;
 *  - a reply is accepted only if the OS reports it was sent by the verified UID (`Message.sendingUid`);
 *    `Binder.getCallingUid()` is **not** used (Messenger delivers on this process's Handler thread, so that
 *    call would return our own UID). Anything else is dropped and fails the call.
 *
 * One connection per exchange, closed afterwards. Blocks the calling thread, so it must not run on the main
 * thread (bind callbacks are delivered there and would never arrive); that is refused up front.
 */
class AndroidMessengerChannel(private val context: Context) : MessengerChannel {
    override fun open(node: PairedNode, verified: PackageIdentity, timeoutMs: Long): Result<MessengerSession> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.failure(IOException("Messenger bridge calls must not run on the main thread"))
        }
        val intent = Intent(node.action).setPackage(node.packageName)

        @Suppress("DEPRECATION")
        val resolved = context.packageManager.resolveService(intent, 0)?.serviceInfo
            ?: return Result.failure(IOException("${node.packageName} exposes no service for ${node.action}"))
        if (resolved.packageName != node.packageName || resolved.applicationInfo.uid != verified.uid) {
            return Result.failure(IOException("service for ${node.action} does not belong to the verified ${node.packageName}"))
        }

        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val pending = AtomicReference<PendingReply?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                pending.get()?.fail(IOException("bridge service disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                failure.set(IOException("bridge service binding died"))
                pending.get()?.fail(IOException("bridge service binding died"))
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                failure.set(IOException("bridge service returned no binder"))
                connected.countDown()
            }
        }

        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            return Result.failure(IOException("not allowed to bind ${node.action}: ${e.message}"))
        }
        if (!bound) {
            runCatching { context.unbindService(connection) }
            return Result.failure(IOException("bindService refused for ${node.action}"))
        }
        val arrived = try {
            connected.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            context.unbindService(connection)
            return Result.failure(e)
        }
        val service = binder.get()
        if (!arrived || service == null) {
            context.unbindService(connection)
            return Result.failure(failure.get() ?: IOException("timed out after $timeoutMs ms binding ${node.action}"))
        }
        return Result.success(Session(context, connection, service, verified.uid, pending))
    }

    private class Session(
        private val context: Context,
        private val connection: ServiceConnection,
        private val service: IBinder,
        private val expectedUid: Int,
        private val pendingSlot: AtomicReference<PendingReply?>,
    ) : MessengerSession {
        private val thread = HandlerThread("fleet-messenger-reply").apply { start() }

        override fun exchange(requestJson: String, timeoutMs: Long): Result<String> {
            val pending = PendingReply()
            pendingSlot.set(pending)
            val handler = object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != MessengerWire.WHAT_RESPONSE) return
                    // Messenger quirk: sendingUid is the OS-stamped sender. Binder.getCallingUid() here
                    // is this process (the Handler thread), so it cannot identify the node.
                    if (msg.sendingUid != expectedUid) {
                        pending.fail(IOException("reply came from uid ${msg.sendingUid}, not the verified node"))
                        return
                    }
                    val json = msg.data?.getString(MessengerWire.KEY_RESPONSE)
                    if (json == null) {
                        pending.fail(IOException("reply carries no '${MessengerWire.KEY_RESPONSE}' string"))
                    } else {
                        pending.complete(json)
                    }
                }
            }
            val request = Message.obtain(null, MessengerWire.WHAT_REQUEST).apply {
                data = Bundle().apply { putString(MessengerWire.KEY_REQUEST, requestJson) }
                replyTo = Messenger(handler)
            }
            try {
                Messenger(service).send(request)
            } catch (e: DeadObjectException) {
                return Result.failure(IOException("bridge service died before the request was delivered"))
            } catch (e: RemoteException) {
                return Result.failure(IOException("could not deliver request: ${e.message}"))
            }
            return pending.await(timeoutMs)
        }

        override fun close() {
            pendingSlot.set(null)
            runCatching { context.unbindService(connection) }
            thread.quitSafely()
        }
    }
}
