import ai.droidcommand.agent.Message;
import ai.droidcommand.agent.Role;
import ai.droidcommand.llm.local.BackendKind;
import ai.droidcommand.llm.local.DeviceCapabilities;
import ai.droidcommand.llm.local.GenerationRequest;
import ai.droidcommand.llm.local.InferenceException;
import ai.droidcommand.llm.local.android.LlamaCppBackend;
import java.util.List;

public class Smoke {
    static String run(LlamaCppBackend b, String system, String user, int max, int[] pieces) {
        StringBuilder sb = new StringBuilder();
        b.generate(
            new GenerationRequest(system, List.of(new Message(Role.USER, user)), max, 0.0),
            s -> { sb.append(s); if (pieces != null) pieces[0]++; return true; });
        return sb.toString();
    }

    public static void main(String[] a) {
        LlamaCppBackend b = new LlamaCppBackend();
        long t0 = System.nanoTime();
        b.load(a[0], 2048);
        System.out.println("loaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms");

        int[] n = {0};
        long t1 = System.nanoTime();
        String out = run(b, null, "What is the capital of France? Answer in one short sentence.", 48, n);
        System.out.println("OUTPUT: " + out);
        System.out.println("pieces=" + n[0] + " secs=" + String.format("%.2f", (System.nanoTime() - t1) / 1e9));

        System.out.println("OUTPUT2: " + run(b, "Reply with exactly one word.", "Say hello.", 8, null));

        int[] k = {0};
        b.generate(new GenerationRequest(null, List.of(new Message(Role.USER, "Count to twenty.")), 48, 0.0), s -> ++k[0] < 3);
        System.out.println("early-stop pieces=" + k[0]);

        // Multi-byte output: a token can end mid-character. Print code points so a corrupted
        // character (U+FFFD or garbage) is visible rather than hidden by terminal rendering.
        String mb = run(b, null, "Repeat exactly this text and nothing else: 日本語 😀 café", 48, null);
        StringBuilder cps = new StringBuilder();
        mb.codePoints().forEach(c -> cps.append(String.format("U+%04X ", c)));
        System.out.println("MULTIBYTE: " + mb);
        System.out.println("MULTIBYTE_CODEPOINTS: " + cps.toString().trim());
        System.out.println("MULTIBYTE_HAS_REPLACEMENT: " + (mb.indexOf('�') >= 0));

        try {
            run(b, null, "word ".repeat(5000), 8, null);
            System.out.println("NO EXCEPTION (unexpected)");
        } catch (InferenceException e) {
            System.out.println("overflow -> InferenceException: " + e.getMessage());
        }
        b.unload();
        System.out.println("unloaded");

        // Device probing and the no-silent-fallback rule against the real native registry. The host
        // build has no GPU backend, so probing must report none and a Vulkan load must fail loudly.
        DeviceCapabilities caps = LlamaCppBackend.Companion.probeCapabilities();
        System.out.println("probe: vulkan=" + caps.getVulkanAvailable());
        try {
            new LlamaCppBackend(BackendKind.VULKAN).load(a[0], 512);
            System.out.println("VULKAN LOAD SUCCEEDED (unexpected on this host)");
        } catch (InferenceException e) {
            System.out.println("vulkan-without-device -> InferenceException: " + e.getMessage());
        }
        try {
            new LlamaCppBackend(BackendKind.OPENCL);
            System.out.println("OPENCL ACCEPTED (unexpected)");
        } catch (IllegalArgumentException e) {
            System.out.println("opencl rejected: " + e.getMessage());
        }
    }
}
