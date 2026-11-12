import ai.droidcommand.agent.JsonFileConversationStore;
import ai.droidcommand.app.localmodels.ImportOutcome;
import ai.droidcommand.app.localmodels.LocalModelRuntime;
import ai.droidcommand.config.InMemorySecretsVault;
import ai.droidcommand.llm.factory.ChatResult;
import ai.droidcommand.llm.factory.ChatSession;
import ai.droidcommand.llm.local.MemorySnapshot;
import ai.droidcommand.remote.HttpTransport;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;

/**
 * Drives the app's own Models -> Chat classes on a Linux HOST with the REAL JNI shim and llama.cpp:
 * LocalModelRuntime (import, registry, restart) -> ChatSession.sendLocal -> LocalLlmProvider -> LlamaCppBackend ->
 * libdca_llama_jni.so. Nothing here is a fake backend. It is still not Android: no file picker, no Compose, no
 * ActivityManager, no phone CPU or memory limits.
 *
 * The transport throws on any request and the vault is empty, so a turn that touched the network or needed a key
 * fails loudly. Run through host-chat-flow.sh.
 *
 *   ChatFlow <model.gguf> <workdir> all|longprompt [variants-dir]
 */
public class ChatFlow {
    static final HttpTransport NO_NETWORK = request -> {
        throw new IllegalStateException("NETWORK USED: " + request.getUrl());
    };

    static int failures = 0;

    static void check(String what, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " | " + detail));
    }

    static LocalModelRuntime runtime(File dir, long totalMb, long freeMb) {
        long mb = 1024L * 1024L;
        return new LocalModelRuntime(dir, () -> new MemorySnapshot(totalMb * mb, freeMb * mb), () -> null, () -> List.of("x86_64"));
    }

    static ChatSession chat(LocalModelRuntime runtime, File conversations) {
        return new ChatSession(
            new InMemorySecretsVault(), new JsonFileConversationStore(conversations.toPath()), "default", null, NO_NETWORK,
            (spec, config) -> { throw new IllegalStateException("CLOUD PROVIDER BUILT"); },
            null, null, null, null, runtime.getResources(), null, Set.of(), 600, null, "", 0, null, new ai.droidcommand.llm.pchost.PcHostTimeouts(8_000, 180_000));
    }

    static ImportOutcome importFile(LocalModelRuntime runtime, File file, Long expected) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return runtime.importModel(file.getName(), in, expected, () -> false, bytes -> Unit.INSTANCE);
        }
    }

    static String send(ChatSession chat, String id, String text, AtomicBoolean stop, AtomicInteger deltas, long[] firstDeltaNanos) {
        long t0 = System.nanoTime();
        ChatResult r = chat.sendLocal(id, text, false, stop::get, delta -> {
            if (deltas.getAndIncrement() == 0 && firstDeltaNanos != null) firstDeltaNanos[0] = System.nanoTime() - t0;
            return Unit.INSTANCE;
        });
        if (r instanceof ChatResult.Reply) return "REPLY:" + ((ChatResult.Reply) r).getText();
        return "FAILURE:" + ((ChatResult.Failure) r).getMessage();
    }

    static String send(ChatSession chat, String id, String text) {
        return send(chat, id, text, new AtomicBoolean(false), new AtomicInteger(), null);
    }

    public static void main(String[] a) throws Exception {
        File model = new File(a[0]);
        File work = new File(a[1]);
        String scenario = a[2];
        File models = new File(work, "local-models");
        File conversations = new File(work, "conversations");

        LocalModelRuntime runtime = runtime(models, 8192, 6000);
        System.out.println("native library problem: " + runtime.nativeLibraryProblem());
        ImportOutcome imported = importFile(runtime, model, model.length());
        if (!(imported instanceof ImportOutcome.Imported)) {
            System.out.println("FAIL import: " + imported);
            System.exit(1);
        }
        var meta = ((ImportOutcome.Imported) imported).getModel().getMetadata();
        String id = meta.getId();
        check("import through LocalModelRuntime (stream copy, SHA-256, GGUF metadata)", true,
            "id=" + id + " bytes=" + meta.getSizeBytes() + " context=" + meta.getContextTokens());
        ChatSession chat = chat(runtime, conversations);

        // ~1,000 tokens: more than n_batch (512), less than the 2,048-token window.
        String longText = "Summarise this note. " + "The quick brown fox jumps over the lazy dog. ".repeat(80);

        if (scenario.equals("longprompt")) {
            System.out.println("sending one message of about 1,000 tokens...");
            System.out.flush();
            String r = send(chat, id, longText);
            check("a prompt longer than 512 tokens gets a reply", r.startsWith("REPLY:"), r.substring(0, Math.min(60, r.length())));
            System.exit(failures == 0 ? 0 : 1);
        }

        // 1. First turn: load + streamed reply.
        AtomicInteger deltas = new AtomicInteger();
        long[] first = {0};
        long t0 = System.nanoTime();
        String r1 = send(chat, id, "Hello, who are you?", new AtomicBoolean(false), deltas, first);
        long ms1 = (System.nanoTime() - t0) / 1_000_000;
        check("first turn loads the model and streams a reply", r1.startsWith("REPLY:") && deltas.get() > 1 && r1.length() > 10,
            "deltas=" + deltas.get() + " chars=" + (r1.length() - 6) + " first-delta-ms=" + first[0] / 1_000_000 + " total-ms=" + ms1);

        // 2. Long prompt (the pre-fix crash).
        String r2 = send(chat, id, longText);
        check("a prompt longer than n_batch (512 tokens) gets a reply", r2.startsWith("REPLY:"), r2.substring(0, Math.min(40, r2.length())));

        // 3. History past the window: older turns are dropped, the chat keeps answering.
        boolean allOk = true;
        for (int i = 0; i < 4; i++) allOk &= send(chat, id, "Turn " + i + ": " + "tell me more about foxes and dogs. ".repeat(20)).startsWith("REPLY:");
        check("the chat keeps answering once its history is larger than the window", allOk, "saved messages=" + chat.history().size());

        // 4. Stop while tokens are streaming.
        int before = chat.history().size();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger seen = new AtomicInteger();
        long ts = System.nanoTime();
        ChatResult stopped = chat.sendLocal(id, "Write a very long story.", false, stop::get, delta -> {
            if (seen.incrementAndGet() == 5) stop.set(true);
            return Unit.INSTANCE;
        });
        long stopMs = (System.nanoTime() - ts) / 1_000_000;
        check("Stop ends generation after the next token and saves nothing",
            stopped instanceof ChatResult.Failure && ((ChatResult.Failure) stopped).getMessage().equals("Stopped.")
                && seen.get() == 5 && chat.history().size() == before,
            "deltas-delivered=" + seen.get() + " ms=" + stopMs + " (a full reply took " + ms1 + " ms)");

        // 5. Stop requested from another thread before the first token (prompt evaluation / abort callback).
        int stoppedEarly = 0;
        long worst = 0;
        for (int i = 0; i < 5; i++) {
            AtomicBoolean s = new AtomicBoolean(false);
            AtomicInteger d = new AtomicInteger();
            Thread stopper = new Thread(() -> {
                try { Thread.sleep(3); } catch (InterruptedException e) { return; }
                s.set(true);
                chat.requestStopLocal();
            });
            long t = System.nanoTime();
            stopper.start();
            String r = send(chat, id, longText + " attempt " + i, s, d, null);
            long ms = (System.nanoTime() - t) / 1_000_000;
            stopper.join();
            worst = Math.max(worst, ms);
            if (r.equals("FAILURE:Stopped.")) stoppedEarly++;
        }
        check("a stop from another thread ends the turn early every time", stoppedEarly == 5 && chat.history().size() == before,
            "stopped=" + stoppedEarly + "/5 worst-ms=" + worst);

        // 6. The model is still usable after stops.
        String r6 = send(chat, id, "And now a normal question.");
        check("the next message after a stop is answered", r6.startsWith("REPLY:"), "");

        // 7. Restart: new runtime, new session, same directories; still no key, still no network.
        runtime.unloadAll();
        int savedBefore = chat.history().size();
        LocalModelRuntime restarted = runtime(models, 8192, 6000);
        ChatSession chat2 = chat(restarted, conversations);
        boolean listed = restarted.list().size() == 1 && restarted.list().get(0).getId().equals(id);
        String r7 = send(chat2, id, "Are you still here after the restart?");
        check("after a restart the model is listed, the history is back and a turn works offline",
            listed && chat2.history().size() == savedBefore + 2 && r7.startsWith("REPLY:"),
            "history " + savedBefore + " -> " + chat2.history().size());
        restarted.unloadAll();

        // 8. Not enough RAM is refused before anything is loaded (64 MB device, as reported by the injected DeviceMemory).
        LocalModelRuntime small = runtime(models, 64, 32);
        small.list(); // what the chat screen does before a turn: reads the registry
        String r8 = send(chat(small, new File(work, "c8")), id, "hi");
        check("a device without enough RAM is told so before the model loads", r8.startsWith("FAILURE:") && r8.contains("more than this device's 64 MB"), r8);

        if (a.length > 3) {
            File variants = new File(a[3]);
            LocalModelRuntime other = runtime(new File(work, "variants"), 8192, 6000);

            // 9. A copy that was cut short, with no size from the picker: llama.cpp refuses it and says why.
            ImportOutcome truncated = importFile(other, new File(variants, "truncated.gguf"), null);
            String r9 = truncated instanceof ImportOutcome.Imported
                ? send(chat(other, new File(work, "c9")), ((ImportOutcome.Imported) truncated).getModel().getId(), "hi")
                : "IMPORT:" + truncated;
            check("a truncated file fails to load with llama.cpp's own reason, not a crash",
                r9.startsWith("FAILURE:") && r9.contains("Could not load") && r9.contains("llama.cpp:") && r9.contains("file bounds"), r9);

            // 10. The same truncated file with the picker's size known never gets that far.
            ImportOutcome short_ = importFile(other, new File(variants, "truncated.gguf"), model.length());
            check("with a known size the truncated copy is refused at import", short_ instanceof ImportOutcome.Failed, String.valueOf(short_));

            ImportOutcome split = importFile(other, new File(variants, "split.gguf"), null);
            check("a shard of a split model is rejected at import", split instanceof ImportOutcome.Rejected, String.valueOf(split));

            // 11. A model trained with a 256-token window: recorded as 256, and a longer message is refused, not crashed.
            ImportOutcome tiny = importFile(other, new File(variants, "ctx256.gguf"), null);
            var tinyMeta = ((ImportOutcome.Imported) tiny).getModel().getMetadata();
            ChatSession c11 = chat(other, new File(work, "c11"));
            String shortReply = send(c11, tinyMeta.getId(), "hi");
            String tooLong = send(c11, tinyMeta.getId(), longText);
            check("a 256-token model is loaded at 256, answers a short message and refuses a long one",
                tinyMeta.getContextTokens() == 256 && shortReply.startsWith("REPLY:") && tooLong.contains("context window is 256 tokens"),
                "context=" + tinyMeta.getContextTokens() + " | " + tooLong);

            ImportOutcome noTemplate = importFile(other, new File(variants, "notemplate.gguf"), null);
            String note = noTemplate instanceof ImportOutcome.Imported ? ((ImportOutcome.Imported) noTemplate).getNote() : null;
            check("a model without a chat template imports with a warning and still answers",
                note != null && send(chat(other, new File(work, "c12")), ((ImportOutcome.Imported) noTemplate).getModel().getId(), "hi").startsWith("REPLY:"),
                String.valueOf(note));
            other.unloadAll();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
