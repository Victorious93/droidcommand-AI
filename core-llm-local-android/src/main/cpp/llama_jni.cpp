// Phase 2 / Phase C: JNI bridge between LlamaCppBackend (Kotlin) and llama.cpp's public C API
// (third_party/llama.cpp/include/llama.h, fetched by scripts/fetch-llama-cpp.sh). This file owns
// every native call; no other translation unit in this module touches llama.h. CPU always; Vulkan
// when the library was built with it (Android, see CMakeLists.txt) — Phase D, compiled only.
//
// Lifetime: nativeLoad() returns an opaque jlong handle to a heap-allocated DcaContext; the
// Kotlin side is responsible for calling nativeUnload() exactly once per successful nativeLoad()
// (LocalLlmProvider.close() already serializes this). A load failure never leaks: on any error
// after llama_model_load_from_file() succeeds, this file frees what it allocated before throwing.
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

struct DcaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
};

std::once_flag g_backendInitFlag;

void llamaLog(ggml_log_level level, const char* text, void* /*userData*/) {
#ifdef __ANDROID__
    int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                 : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                 : ANDROID_LOG_INFO;
    __android_log_print(priority, "dca_llama", "%s", text);
#else
    (void)level;
    (void)text;
#endif
}

void ensureBackendInit() {
    std::call_once(g_backendInitFlag, []() {
        llama_log_set(llamaLog, nullptr);
        llama_backend_init();
    });
}

// Backend codes shared with Kotlin (LlamaCppBackend): 0 = CPU, 1 = Vulkan.
constexpr jint kBackendCpu = 0;
constexpr jint kBackendVulkan = 1;

bool isGpu(ggml_backend_dev_t dev) {
    const auto type = ggml_backend_dev_type(dev);
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

// ggml names a device after its backend ("Vulkan0", ...). This prefix match is from reading the
// ggml sources; it has not been observed on a real GPU.
bool isVulkanDevice(ggml_backend_dev_t dev) {
    return std::string(ggml_backend_dev_name(dev)).rfind("Vulkan", 0) == 0;
}

// Throws ai.droidcommand.llm.local.InferenceException(message, cause=null) and returns to the
// caller; per the JNI contract, the caller must stop making JNI calls and return immediately.
void throwInferenceException(JNIEnv* env, const std::string& message) {
    jclass clazz = env->FindClass("ai/droidcommand/llm/local/InferenceException");
    if (clazz == nullptr) return; // FindClass already threw (e.g. NoClassDefFoundError); nothing more to do.
    jmethodID ctor = env->GetMethodID(clazz, "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V");
    if (ctor == nullptr) return;
    jstring jMessage = env->NewStringUTF(message.c_str());
    jobject exception = env->NewObject(clazz, ctor, jMessage, nullptr);
    if (exception != nullptr) {
        env->Throw(static_cast<jthrowable>(exception));
    }
}

void freeContext(DcaContext* dca) {
    if (dca == nullptr) return;
    if (dca->ctx != nullptr) llama_free(dca->ctx);
    if (dca->model != nullptr) llama_model_free(dca->model);
    delete dca;
}

std::string roleToString(JNIEnv* env, jstring jRole) {
    const char* chars = env->GetStringUTFChars(jRole, nullptr);
    std::string role(chars);
    env->ReleaseStringUTFChars(jRole, chars);
    return role;
}

// Renders the conversation with the model's own embedded chat template (falls back to llama.cpp's
// built-in "chatml" when the GGUF carries none), growing the output buffer until it fits.
bool renderChatPrompt(
    const DcaContext* dca,
    const std::vector<llama_chat_message>& messages,
    std::string* outPrompt) {
    const char* tmpl = llama_model_chat_template(dca->model, nullptr);
    std::vector<char> buf(4096);
    int32_t needed = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), /*add_ass=*/true, buf.data(), static_cast<int32_t>(buf.size()));
    if (needed < 0) return false;
    if (needed > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(needed));
        needed = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), /*add_ass=*/true, buf.data(), static_cast<int32_t>(buf.size()));
        if (needed < 0) return false;
    }
    outPrompt->assign(buf.data(), static_cast<size_t>(needed));
    return true;
}

std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text, bool addSpecial) {
    int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, addSpecial, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(n, 0)));
    if (!tokens.empty()) {
        llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()),
            addSpecial, true);
    }
    return tokens;
}

std::string tokenToPiece(const llama_vocab* vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, static_cast<size_t>(n));
}

} // namespace

extern "C" {

// Bitmask of GPU backends that are compiled in AND have a device right now: 1 = Vulkan. It asks
// ggml's device registry, so it reflects what can actually run, not what was requested.
JNIEXPORT jint JNICALL
Java_ai_droidcommand_llm_local_android_LlamaProbe_nativeProbe(JNIEnv* /*env*/, jclass /*clazz*/) {
    ensureBackendInit();
    jint mask = 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (isGpu(dev) && isVulkanDevice(dev)) mask |= 1;
    }
    return mask;
}

JNIEXPORT jlong JNICALL
Java_ai_droidcommand_llm_local_android_LlamaCppBackend_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jstring jModelPath, jint contextTokens, jint backend) {
    ensureBackendInit();

    llama_model_params modelParams = llama_model_default_params();
    // `devices` is read during model load and must outlive that call.
    std::vector<ggml_backend_dev_t> devices;
    if (backend == kBackendCpu) {
        modelParams.n_gpu_layers = 0;
    } else if (backend == kBackendVulkan) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (isGpu(dev) && isVulkanDevice(dev)) devices.push_back(dev);
        }
        if (devices.empty()) {
            // Fail loudly: silently running on the CPU would make a "GPU" benchmark meaningless.
            throwInferenceException(env, "requested GPU backend has no usable device on this build/device");
            return 0;
        }
        devices.push_back(nullptr); // the list is NULL-terminated
        modelParams.devices = devices.data();
        modelParams.n_gpu_layers = 999; // offload every layer
    } else {
        throwInferenceException(env, "unknown backend code " + std::to_string(backend));
        return 0;
    }

    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    llama_model* model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (model == nullptr) {
        throwInferenceException(env, "llama.cpp failed to load the model file");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(std::max(contextTokens, 1));
    ctxParams.n_batch = std::min(ctxParams.n_ctx, static_cast<uint32_t>(512));
    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        llama_model_free(model);
        throwInferenceException(env, "llama.cpp failed to create an inference context");
        return 0;
    }

    auto* dca = new DcaContext();
    dca->model = model;
    dca->ctx = ctx;
    dca->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(dca);
}

JNIEXPORT void JNICALL
Java_ai_droidcommand_llm_local_android_LlamaCppBackend_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jSystemPrompt, jobjectArray jRoles,
    jobjectArray jContents, jint maxTokens, jdouble temperature, jobject sink) {
    auto* dca = reinterpret_cast<DcaContext*>(handle);
    if (dca == nullptr) {
        throwInferenceException(env, "generate() called with no loaded model");
        return;
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onTokenMethod = env->GetMethodID(sinkClass, "onToken", "([B)Z");
    if (onTokenMethod == nullptr) return; // GetMethodID already threw.

    // Build the llama_chat_message list: an optional system prompt first, then the turns.
    std::vector<llama_chat_message> messages;
    std::vector<std::string> roleStorage;
    std::vector<std::string> contentStorage;
    jsize turnCount = env->GetArrayLength(jRoles);
    size_t capacity = static_cast<size_t>(turnCount) + 1;
    roleStorage.reserve(capacity);
    contentStorage.reserve(capacity);
    messages.reserve(capacity);

    if (jSystemPrompt != nullptr) {
        roleStorage.push_back("system");
        const char* chars = env->GetStringUTFChars(jSystemPrompt, nullptr);
        contentStorage.emplace_back(chars);
        env->ReleaseStringUTFChars(jSystemPrompt, chars);
    }
    for (jsize i = 0; i < turnCount; ++i) {
        jstring jRole = static_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        jstring jContent = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        roleStorage.push_back(roleToString(env, jRole));
        const char* chars = env->GetStringUTFChars(jContent, nullptr);
        contentStorage.emplace_back(chars);
        env->ReleaseStringUTFChars(jContent, chars);
        env->DeleteLocalRef(jRole);
        env->DeleteLocalRef(jContent);
    }
    // llama_chat_message stores raw pointers into roleStorage/contentStorage, which must outlive
    // this loop — both vectors are fixed in size above (reserve + sequential push_back), so no
    // reallocation invalidates these pointers before messages is consumed.
    for (size_t i = 0; i < roleStorage.size(); ++i) {
        messages.push_back(llama_chat_message{roleStorage[i].c_str(), contentStorage[i].c_str()});
    }

    std::string prompt;
    if (!renderChatPrompt(dca, messages, &prompt)) {
        throwInferenceException(env, "llama.cpp could not apply a chat template to this conversation");
        return;
    }

    std::vector<llama_token> promptTokens = tokenize(dca->vocab, prompt, /*addSpecial=*/true);
    if (promptTokens.empty()) {
        throwInferenceException(env, "tokenization produced no tokens");
        return;
    }
    if (promptTokens.size() >= llama_n_ctx(dca->ctx)) {
        throwInferenceException(env, "prompt is longer than the model's context window");
        return;
    }

    llama_memory_seq_rm(llama_get_memory(dca->ctx), /*seq_id=*/0, -1, -1);

    llama_batch batch = llama_batch_get_one(promptTokens.data(), static_cast<int32_t>(promptTokens.size()));
    if (llama_decode(dca->ctx, batch) != 0) {
        throwInferenceException(env, "llama_decode failed on the prompt");
        return;
    }

    llama_sampler_chain_params chainParams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(chainParams);
    if (temperature > 0.0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(static_cast<float>(temperature)));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(/*seed=*/LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    jboolean keepGoing = JNI_TRUE;
    int32_t generated = 0;
    llama_token nextToken = 0;
    while (generated < maxTokens) {
        nextToken = llama_sampler_sample(sampler, dca->ctx, -1);
        llama_sampler_accept(sampler, nextToken);
        if (llama_vocab_is_eog(dca->vocab, nextToken)) break;

        std::string piece = tokenToPiece(dca->vocab, nextToken);
        ++generated;
        if (!piece.empty()) {
            // Raw bytes, not NewStringUTF: a token can end in the middle of a multi-byte UTF-8
            // character, and NewStringUTF (modified UTF-8) mangles or rejects a truncated sequence.
            // The Kotlin side reassembles characters (Utf8StreamDecoder).
            jbyteArray jPiece = env->NewByteArray(static_cast<jsize>(piece.size()));
            env->SetByteArrayRegion(jPiece, 0, static_cast<jsize>(piece.size()), reinterpret_cast<const jbyte*>(piece.data()));
            keepGoing = env->CallBooleanMethod(sink, onTokenMethod, jPiece);
            env->DeleteLocalRef(jPiece);
            if (env->ExceptionCheck() || keepGoing == JNI_FALSE) break;
        }

        llama_batch nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(dca->ctx, nextBatch) != 0) {
            llama_sampler_free(sampler);
            throwInferenceException(env, "llama_decode failed during generation");
            return;
        }
    }

    llama_sampler_free(sampler);
}

JNIEXPORT void JNICALL
Java_ai_droidcommand_llm_local_android_LlamaCppBackend_nativeUnload(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    freeContext(reinterpret_cast<DcaContext*>(handle));
}

} // extern "C"
