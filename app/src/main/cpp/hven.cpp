// Native side of LlamaEngine.kt: load a model, generate text, stop, timings, reset, free.
// Kotlin can't hold C++ pointers, so it gets an Engine* as a Long (the "handle")
// and passes it back on every call.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

struct Engine {
    llama_model *model;
    llama_context *ctx;
    llama_sampler *sampler;
    llama_batch_ext *batch;
    // Written by stop() on the UI thread, read by generate() on the model thread.
    std::atomic<bool> stop_requested{false};
    // Timings of the current or last generate(), returned by stats(). Atomic because stats()
    // reads them from the UI thread while generate() updates them on the model thread.
    std::atomic<int> prompt_tokens{0};
    std::atomic<double> prompt_ms{0};
    std::atomic<int> generated_tokens{0};
    std::atomic<double> generate_ms{0};
};

using Clock = std::chrono::steady_clock;

static double ms_since(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

// llama.cpp prints to stderr, which Android throws away; send it to logcat instead.
static void log_to_logcat(ggml_log_level level, const char *text, void * /* user_data */) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                 : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                 : ANDROID_LOG_INFO;
    __android_log_print(priority, "hven", "%s", text);
}

// Text crosses JNI as raw UTF-8 bytes: JNI's own strings use "modified UTF-8",
// which encodes emoji differently from what the tokenizer expects.
static std::string bytes_from_java(JNIEnv *env, jbyteArray array) {
    std::string s(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, s.size(), reinterpret_cast<jbyte *>(s.data()));
    return s;
}

// Length of the longest prefix of s that doesn't end halfway through a UTF-8 character.
// A token can stop in the middle of one (emoji, accented letters); the rest comes with the next token.
static size_t complete_utf8_len(const std::string &s) {
    size_t i = s.size();
    // Step back over continuation bytes (10xxxxxx) to the first byte of the last character.
    while (i > 0 && s.size() - i < 3 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) i--;
    if (i == 0) return s.size();
    unsigned char lead = s[i - 1];
    size_t needed = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return s.size() - (i - 1) >= needed ? s.size() : i - 1;
}

// Appends the tokens of text to out. With parse_special, control markers like <|im_start|> become
// control tokens (our template); without it they stay plain text (anything the user typed).
static bool tokenize(const llama_vocab *vocab, const std::string &text, bool add_start, bool parse_special,
                     std::vector<llama_token> &out) {
    int n = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, add_start, parse_special);
    size_t old_size = out.size();
    out.resize(old_size + n);
    return llama_tokenize(vocab, text.data(), text.size(), out.data() + old_size, n, add_start, parse_special) >= 0;
}

// Puts tokens into the batch at positions pos_0, pos_0 + 1, ...; we only need the
// model's prediction (logits) after the last one.
static void fill_batch(llama_batch_ext *batch, const llama_token *tokens, int n_tokens, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < n_tokens; i++) {
        int idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n_tokens - 1, true);
}

// Returns the handle, or 0 if the model can't be loaded.
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_tostapane_hven_LlamaEngine_load(JNIEnv *env, jobject /* this */, jstring path,
                                               jint context_size, jint threads, jfloat temperature,
                                               jint top_k, jfloat presence_penalty) {
    llama_log_set(log_to_logcat, nullptr);
    llama_backend_init();

    const char *c_path = env->GetStringUTFChars(path, nullptr);
    llama_model *model = llama_model_load_from_file(c_path, llama_model_default_params());
    env->ReleaseStringUTFChars(path, c_path);
    if (!model) return 0;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = context_size;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, params);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    // How the next token is picked from the model's predictions.
    const llama_vocab *vocab = llama_model_get_vocab(model);
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Penalize tokens seen in the last 64 (llama.cpp's default window), to avoid repetition.
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, presence_penalty));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto *engine = new Engine{model, ctx, sampler, llama_batch_ext_init(ctx)};
    return reinterpret_cast<jlong>(engine);
}

// Feeds before + user_text + after, then generates until the end of the answer or stop().
// Each piece of text goes to listener.onPiece(bytes) as soon as it's ready.
// Returns 0 when done or stopped, 1 when the context is full, 2 on error.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_tostapane_hven_LlamaEngine_generate(JNIEnv *env, jobject /* this */, jlong handle,
                                                   jbyteArray before, jbyteArray user_text,
                                                   jbyteArray after, jobject listener) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->stop_requested = false;
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    llama_memory_t memory = llama_get_memory(engine->ctx);

    // The first prompt of a chat also gets the model's start-of-text token, if it has one.
    // Control markers count in our template, not in what the user typed.
    bool first = llama_memory_seq_pos_max(memory, 0) == -1;
    std::vector<llama_token> prompt_tokens;
    if (!tokenize(vocab, bytes_from_java(env, before), first, true, prompt_tokens) ||
        !tokenize(vocab, bytes_from_java(env, user_text), false, false, prompt_tokens) ||
        !tokenize(vocab, bytes_from_java(env, after), false, true, prompt_tokens)) return 2;
    int n_prompt = prompt_tokens.size();

    engine->prompt_tokens = n_prompt;
    engine->prompt_ms = 0;
    engine->generated_tokens = 0;
    engine->generate_ms = 0;
    Clock::time_point start = Clock::now();

    jmethodID on_piece = env->GetMethodID(env->GetObjectClass(listener), "onPiece", "([B)V");
    std::string pending;  // generated bytes not sent yet (an unfinished UTF-8 character)

    // Tokens to feed next: the whole prompt first, then one sampled token at a time.
    const llama_token *next = prompt_tokens.data();
    int n_next = n_prompt;
    llama_token token;

    while (!engine->stop_requested) {
        int used = llama_memory_seq_pos_max(memory, 0) + 1;
        if (used + n_next > static_cast<int>(llama_n_ctx(engine->ctx))) return 1;

        fill_batch(engine->batch, next, n_next, used);
        if (llama_process(engine->ctx, LLAMA_PROCESS_TYPE_DECODE, engine->batch) != 0) return 2;

        token = llama_sampler_sample(engine->sampler, engine->ctx, -1);

        // The first round reads the whole prompt; every later round generates one token.
        if (next == prompt_tokens.data()) {
            engine->prompt_ms = ms_since(start);
            start = Clock::now();
        } else {
            engine->generated_tokens++;
            engine->generate_ms = ms_since(start);
        }
        if (llama_vocab_is_eog(vocab, token)) break;  // end of the answer

        char buf[256];
        int len = llama_token_to_piece(vocab, token, buf, sizeof buf, 0, false);
        if (len < 0) return 2;
        pending.append(buf, len);

        size_t ready = complete_utf8_len(pending);
        if (ready > 0) {
            jbyteArray piece = env->NewByteArray(ready);
            env->SetByteArrayRegion(piece, 0, ready, reinterpret_cast<const jbyte *>(pending.data()));
            env->CallVoidMethod(listener, on_piece, piece);
            // Free the Java reference now: in a long loop they would pile up until we return.
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) return 2;  // the Kotlin listener threw
            pending.erase(0, ready);
        }

        next = &token;
        n_next = 1;
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_tostapane_hven_LlamaEngine_stop(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    reinterpret_cast<Engine *>(handle)->stop_requested = true;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_io_github_tostapane_hven_LlamaEngine_stats(JNIEnv *env, jobject /* this */, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    jdouble values[] = {static_cast<double>(engine->prompt_tokens), engine->prompt_ms,
                        static_cast<double>(engine->generated_tokens), engine->generate_ms};
    jdoubleArray array = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(array, 0, 4, values);
    return array;
}

// Forgets the conversation (new chat); the model stays loaded.
extern "C" JNIEXPORT void JNICALL
Java_io_github_tostapane_hven_LlamaEngine_reset(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    llama_sampler_reset(engine->sampler);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_tostapane_hven_LlamaEngine_free(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    llama_batch_ext_free(engine->batch);
    llama_sampler_free(engine->sampler);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}
