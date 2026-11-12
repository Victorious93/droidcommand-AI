import ai.droidcommand.agent.AgentMode;
import ai.droidcommand.agent.InMemoryConversationStore;
import ai.droidcommand.agent.Initiator;
import ai.droidcommand.agent.Message;
import ai.droidcommand.agent.RetryPolicy;
import ai.droidcommand.agent.Role;
import ai.droidcommand.agent.SecurityLevel;
import ai.droidcommand.agent.ToolResult;
import ai.droidcommand.agent.ToolRunner;
import ai.droidcommand.agent.ToolSpec;
import ai.droidcommand.config.InMemorySecretsVault;
import ai.droidcommand.llm.LlmResponse;
import ai.droidcommand.llm.factory.ChatResult;
import ai.droidcommand.llm.factory.ChatSession;
import ai.droidcommand.llm.factory.LocalProviderResources;
import ai.droidcommand.llm.local.GenerationRequest;
import ai.droidcommand.llm.local.InferenceException;
import ai.droidcommand.llm.local.LocalLlmProvider;
import ai.droidcommand.llm.local.LocalToolProtocol;
import ai.droidcommand.llm.local.LocalToolSupport;
import ai.droidcommand.llm.local.ModelMetadata;
import ai.droidcommand.llm.local.ModelRepository;
import ai.droidcommand.llm.local.android.LlamaCppBackend;
import ai.droidcommand.remote.HttpTransport;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * On-device tool calling on a Linux HOST with the REAL JNI shim and llama.cpp: the grammar LocalToolProtocol writes is
 * handed to llama.cpp's own grammar sampler and real tokens are sampled under it. Run through host-tool-flow.sh.
 *
 * With the default RANDOM-WEIGHT model this proves the mechanism (llama.cpp accepts the grammar; output sampled under
 * it is a well-formed call to an offered tool; the app's parser, runner hand-off and follow-up turn work on that
 * output). It proves nothing about whether a trained model decides to call a tool, or calls the right one.
 *
 *   ToolFlow <tool-template model.gguf> <plain-template model.gguf>
 */
public class ToolFlow {
    static final HttpTransport NO_NETWORK = request -> {
        throw new IllegalStateException("NETWORK USED: " + request.getUrl());
    };
    static int failures = 0;

    static void check(String what, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " | " + detail));
    }

    static ToolSpec tool(String name, String description) {
        return new ToolSpec(name, description, Set.of(), false, SecurityLevel.NORMAL, false, EnumSet.allOf(AgentMode.class), null, null, null, null);
    }

    static ModelRepository repository(File model, String id) throws Exception {
        ModelRepository repo = new ModelRepository(model.getParentFile(), false);
        String sha;
        try (FileInputStream in = new FileInputStream(model)) {
            sha = ModelRepository.Companion.sha256Hex(in);
        }
        repo.register(new ModelMetadata(id, id, model.getName(), sha, 2048, model.length(), null), null);
        return repo;
    }

    static String oneLine(String s) {
        String flat = s.replace("\n", "\\n");
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    public static void main(String[] a) throws Exception {
        File toolModel = new File(a[0]);
        File plainModel = new File(a[1]);
        List<ToolSpec> tools = List.of(tool("clock", "Tells the current time"), tool("echo", "Repeats text"));
        String grammar = LocalToolProtocol.INSTANCE.grammar(tools);

        // 1. Capability status comes from the file and the runtime, not from a guess.
        LocalLlmProvider capable = new LocalLlmProvider("tool", repository(toolModel, "tool"), new LlamaCppBackend(), new ai.droidcommand.llm.LlmConfig("local", "tool", null, null, null, () -> null), null);
        LocalLlmProvider plain = new LocalLlmProvider("plain", repository(plainModel, "plain"), new LlamaCppBackend(), new ai.droidcommand.llm.LlmConfig("local", "plain", null, null, null, () -> null), null);
        LocalToolSupport yes = capable.toolSupport();
        LocalToolSupport no = plain.toolSupport();
        check("a model whose template defines <tool_call> is reported tool-capable on this runtime", yes.getSupported(), yes.getReason());
        check("a plain ChatML model is reported not tool-capable, with the reason", !no.getSupported() && no.getReason().equals(LocalToolProtocol.NO_TEMPLATE_SUPPORT), oneLine(no.getReason()));

        // 2. llama.cpp's grammar sampler takes the protocol grammar, and rejects a broken one instead of ignoring it.
        LlamaCppBackend backend = new LlamaCppBackend();
        backend.load(toolModel.getPath(), 2048);
        List<Message> ask = List.of(new Message(Role.USER, "What time is it?"));
        StringBuilder free = new StringBuilder();
        backend.generate(new GenerationRequest(LocalToolProtocol.INSTANCE.systemBlock(tools), ask, 96, 0.0, grammar), piece -> { free.append(piece); return true; });
        LlmResponse parsedFree = LocalToolProtocol.INSTANCE.parse(free.toString(), tools);
        boolean cutOff = parsedFree instanceof LlmResponse.Error && ((LlmResponse.Error) parsedFree).getError().getMessage().contains("cut off");
        check("real sampling under the protocol grammar yields text, a valid call, or a call cut off by the token limit",
            !(parsedFree instanceof LlmResponse.Error) || cutOff, parsedFree.getClass().getSimpleName() + " | " + oneLine(free.toString()));

        boolean rejected = false;
        String rejection = "";
        try {
            backend.generate(new GenerationRequest(null, ask, 8, 0.0, "root ::= ( unclosed"), piece -> true);
        } catch (InferenceException e) {
            rejected = true;
            rejection = e.getMessage();
        }
        check("a grammar llama.cpp cannot parse is an error, not unconstrained output", rejected, rejection);

        // 3. Forced to call (same rules, root narrowed to one call with empty arguments): what comes out of real
        //    sampling is a complete, well-formed call naming an offered tool, and the app's parser accepts it.
        String forced = grammar.replaceFirst("(?m)^root ::= .*$", "root ::= ws call")
            .replaceFirst("(?m)^object ::= .*$", "object ::= \"{\" ws \"}\"");
        StringBuilder call = new StringBuilder();
        int[] pieces = {0};
        backend.generate(new GenerationRequest(LocalToolProtocol.INSTANCE.systemBlock(tools), ask, 128, 0.0, forced), piece -> { call.append(piece); pieces[0]++; return true; });
        Pattern shape = Pattern.compile("^\\s*<tool_call>\\s*\\{\\s*\"name\"\\s*:\\s*\"(clock|echo)\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{\\s*}\\s*}\\s*</tool_call>\\s*$");
        check("output sampled under a call-only grammar is exactly one well-formed call to an offered tool", shape.matcher(call).matches(), pieces[0] + " pieces | " + oneLine(call.toString()));
        LlmResponse parsedCall = LocalToolProtocol.INSTANCE.parse(call.toString(), tools);
        check("the app's parser accepts that output as a tool call", parsedCall instanceof LlmResponse.ToolCall,
            parsedCall instanceof LlmResponse.ToolCall ? ((LlmResponse.ToolCall) parsedCall).getToolName() + " " + ((LlmResponse.ToolCall) parsedCall).getArgumentsJson() : parsedCall.toString());

        // 4. Plain generation on the same loaded model is not constrained.
        StringBuilder unconstrained = new StringBuilder();
        backend.generate(new GenerationRequest(null, ask, 24, 0.0), piece -> { unconstrained.append(piece); return true; });
        check("generation without a grammar still works on the same loaded model", unconstrained.length() > 0, oneLine(unconstrained.toString()));
        backend.unload();

        // 5. The normal entry point: ChatSession.sendLocal with a helper bot's runner, on both models.
        List<String> ran = new ArrayList<>();
        ToolRunner runner = new ToolRunner() {
            @Override
            public ToolResult run(String toolName, java.util.Map<String, String> input, RetryPolicy retryPolicy, Function0<Boolean> isCancelled, AgentMode mode, Initiator initiator, String grantId) {
                ran.add(toolName + input);
                return new ToolResult.Success("12:00");
            }
        };
        for (File model : new File[] {toolModel, plainModel}) {
            boolean capableModel = model == toolModel;
            ChatSession chat = new ChatSession(
                new InMemorySecretsVault(), new InMemoryConversationStore(), "default", null, NO_NETWORK,
                (spec, config) -> { throw new IllegalStateException("CLOUD PROVIDER BUILT"); },
                null, null, null, null, new LocalProviderResources(repository(model, "m"), null, null, LlamaCppBackend::new), null, Set.of(), 600, null, "", 0, null, new ai.droidcommand.llm.pchost.PcHostTimeouts(8_000, 180_000));
            chat.setToolAccess(runner, tools);
            ran.clear();
            ChatResult r = chat.sendLocal("m", "What time is it?", false, () -> false, delta -> Unit.INSTANCE);
            String what = capableModel ? "tool-template model" : "plain-template model";
            if (r instanceof ChatResult.Reply) {
                ChatResult.Reply reply = (ChatResult.Reply) r;
                boolean noticeOk = capableModel ? reply.getNotice() == null : LocalToolProtocol.NO_TEMPLATE_SUPPORT.equals(reply.getNotice());
                check("ChatSession.sendLocal with a bot on the " + what + " replied, with the right notice", noticeOk,
                    "tools run: " + ran + " | notice: " + reply.getNotice() + " | " + oneLine(reply.getText()));
                check("the bot's runner was reached only where tools were offered", capableModel || ran.isEmpty(), ran.toString());
            } else {
                String message = ((ChatResult.Failure) r).getMessage();
                // A random-weight model may ramble inside a call's string until the token limit: a refused, cut-off call.
                boolean acceptable = capableModel && (message.contains("cut off") || message.contains("too many tool calls") || message.contains("empty reply"));
                check("ChatSession.sendLocal with a bot on the " + what + " ended in a bounded, explained failure", acceptable, "tools run: " + ran + " | " + oneLine(message));
            }
            chat.close();
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
