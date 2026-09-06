// JNI glue between com.pocketchat.app.inference.PocketChatEngine (Kotlin) and
// core/inference/ + core/memory/ (the shared C++ core). Kept as thin as
// possible: marshal JVM types to/from the C API, no logic of its own.
#include <jni.h>
#include <android/log.h>

#include <vector>

#include "pocketchat_inference.h"
#include "pocketchat_memory.h"
#include "pocketchat_safety.h"

#define LOG_TAG "PocketChatJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// Marshals parallel JVM String[] role/content arrays into pc_chat_message[],
// keeping the underlying jstrings/UTF-8 copies alive for this object's
// lifetime. Used by both nativeGenerateChat and nativeMemoryUpdateSession.
class JniMessageArray {
public:
    JniMessageArray(JNIEnv * env, jobjectArray roles, jobjectArray contents) : env_(env) {
        const jsize n = env->GetArrayLength(roles);
        role_refs_.resize(n);
        content_refs_.resize(n);
        role_chars_.resize(n);
        content_chars_.resize(n);
        messages_.resize(n);
        for (jsize i = 0; i < n; i++) {
            role_refs_[i]    = (jstring) env->GetObjectArrayElement(roles, i);
            content_refs_[i] = (jstring) env->GetObjectArrayElement(contents, i);
            role_chars_[i]    = env->GetStringUTFChars(role_refs_[i], nullptr);
            content_chars_[i] = env->GetStringUTFChars(content_refs_[i], nullptr);
            messages_[i] = { role_chars_[i], content_chars_[i] };
        }
    }

    ~JniMessageArray() {
        for (size_t i = 0; i < messages_.size(); i++) {
            env_->ReleaseStringUTFChars(role_refs_[i], role_chars_[i]);
            env_->ReleaseStringUTFChars(content_refs_[i], content_chars_[i]);
            env_->DeleteLocalRef(role_refs_[i]);
            env_->DeleteLocalRef(content_refs_[i]);
        }
    }

    JniMessageArray(const JniMessageArray &) = delete;
    JniMessageArray & operator=(const JniMessageArray &) = delete;

    const pc_chat_message * data() const { return messages_.data(); }
    size_t size() const { return messages_.size(); }

private:
    JNIEnv                    * env_;
    std::vector<jstring>        role_refs_;
    std::vector<jstring>        content_refs_;
    std::vector<const char *>   role_chars_;
    std::vector<const char *>   content_chars_;
    std::vector<pc_chat_message> messages_;
};

// Bridges pc_token_callback (C function pointer + void*) to a JVM
// PocketChatEngine.TokenCallback instance. Valid only for the duration of a
// single nativeGenerateChat() call, on the thread that called it — the JNIEnv*
// and local ref are both call-scoped, not safe to stash anywhere longer-lived.
struct JniCallback {
    JNIEnv    * env;
    jobject     callback;
    jmethodID   method;
};

int jni_token_callback(const char * piece, void * user_data) {
    auto * cb = static_cast<JniCallback *>(user_data);

    const jstring jpiece = cb->env->NewStringUTF(piece);
    const jboolean keep_going = cb->env->CallBooleanMethod(cb->callback, cb->method, jpiece);
    cb->env->DeleteLocalRef(jpiece);

    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionDescribe();
        cb->env->ExceptionClear();
        return 0; // stop generation if the Kotlin callback threw
    }
    return keep_going ? 1 : 0;
}

// Same shape as JniCallback, for PocketChatEngine.MemoryProgressCallback
// (onProgress(Int, String): Boolean) instead of TokenCallback.
struct JniMemoryProgressCallback {
    JNIEnv    * env;
    jobject     callback;
    jmethodID   method;
};

int jni_memory_progress_callback(pc_memory_phase phase, const char * piece, void * user_data) {
    auto * cb = static_cast<JniMemoryProgressCallback *>(user_data);

    const jstring jpiece = cb->env->NewStringUTF(piece);
    const jboolean keep_going = cb->env->CallBooleanMethod(cb->callback, cb->method, (jint) phase, jpiece);
    cb->env->DeleteLocalRef(jpiece);

    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionDescribe();
        cb->env->ExceptionClear();
        return 0;
    }
    return keep_going ? 1 : 0;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeInit(JNIEnv *, jclass) {
    pc_init();
}

JNIEXPORT jlong JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeLoadModel(
        JNIEnv * env, jclass, jstring j_path, jint n_gpu_layers) {
    const char * path = env->GetStringUTFChars(j_path, nullptr);
    pc_model * model = pc_model_load(path, n_gpu_layers);
    env->ReleaseStringUTFChars(j_path, path);

    if (!model) {
        LOGW("nativeLoadModel failed: %s", pc_last_error());
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeFreeModel(JNIEnv *, jclass, jlong handle) {
    pc_model_free(reinterpret_cast<pc_model *>(handle));
}

JNIEXPORT jlong JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeCreateContext(
        JNIEnv *, jclass, jlong model_handle, jint n_ctx, jint n_threads) {
    auto * model = reinterpret_cast<pc_model *>(model_handle);
    pc_context * ctx = pc_context_create(model, (uint32_t) n_ctx, n_threads);

    if (!ctx) {
        LOGW("nativeCreateContext failed: %s", pc_last_error());
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeFreeContext(JNIEnv *, jclass, jlong handle) {
    pc_context_free(reinterpret_cast<pc_context *>(handle));
}

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeResetContext(JNIEnv *, jclass, jlong handle) {
    pc_context_reset(reinterpret_cast<pc_context *>(handle));
}

JNIEXPORT jint JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeContextNCtx(JNIEnv *, jclass, jlong handle) {
    return (jint) pc_context_n_ctx(reinterpret_cast<pc_context *>(handle));
}

JNIEXPORT jint JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeContextNUsed(JNIEnv *, jclass, jlong handle) {
    return (jint) pc_context_n_used(reinterpret_cast<pc_context *>(handle));
}

JNIEXPORT jint JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeGenerateChat(
        JNIEnv * env, jclass,
        jlong ctx_handle,
        jobjectArray roles, jobjectArray contents,
        jfloat temp, jfloat top_p, jfloat min_p, jint top_k, jint n_predict, jlong seed,
        jobject callback) {
    auto * ctx = reinterpret_cast<pc_context *>(ctx_handle);
    JniMessageArray msgs(env, roles, contents);

    pc_sampling_params sampling;
    sampling.temp      = temp;
    sampling.top_p     = top_p;
    sampling.min_p     = min_p;
    sampling.top_k     = top_k;
    sampling.n_predict = n_predict;
    sampling.seed      = (uint32_t) seed;

    const jclass    callback_class = env->GetObjectClass(callback);
    const jmethodID method         = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)Z");

    JniCallback jni_cb{ env, callback, method };
    const int rc = pc_generate_chat(ctx, msgs.data(), msgs.size(), sampling, jni_token_callback, &jni_cb);
    if (rc != 0) {
        LOGW("nativeGenerateChat failed: %s", pc_last_error());
    }

    env->DeleteLocalRef(callback_class);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(pc_last_error());
}

JNIEXPORT jstring JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemoryBuildContext(
        JNIEnv * env, jclass, jstring j_memory_dir, jstring j_query, jint max_summaries, jint max_chars) {
    const char * memory_dir = env->GetStringUTFChars(j_memory_dir, nullptr);
    const char * query = j_query ? env->GetStringUTFChars(j_query, nullptr) : nullptr;
    char * result = pc_memory_build_context(memory_dir, query, max_summaries, (size_t) max_chars);
    if (query) env->ReleaseStringUTFChars(j_query, query);
    env->ReleaseStringUTFChars(j_memory_dir, memory_dir);

    if (!result) {
        LOGW("nativeMemoryBuildContext failed: %s", pc_memory_last_error());
        return env->NewStringUTF("");
    }
    const jstring out = env->NewStringUTF(result);
    pc_memory_free_string(result);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemoryUpdateSession(
        JNIEnv * env, jclass,
        jlong model_handle, jstring j_memory_dir,
        jobjectArray roles, jobjectArray contents,
        jint n_ctx, jint n_threads,
        jobject progress_callback) {
    auto * model = reinterpret_cast<pc_model *>(model_handle);
    const char * memory_dir = env->GetStringUTFChars(j_memory_dir, nullptr);
    JniMessageArray msgs(env, roles, contents);

    jclass    callback_class = nullptr;
    jmethodID method         = nullptr;
    if (progress_callback) {
        callback_class = env->GetObjectClass(progress_callback);
        method         = env->GetMethodID(callback_class, "onProgress", "(ILjava/lang/String;)Z");
    }
    JniMemoryProgressCallback jni_cb{ env, progress_callback, method };

    const int rc = pc_memory_update_session(
        model, memory_dir, msgs.data(), msgs.size(), (uint32_t) n_ctx, (int32_t) n_threads,
        progress_callback ? jni_memory_progress_callback : nullptr,
        progress_callback ? &jni_cb : nullptr);
    if (rc != 0) {
        LOGW("nativeMemoryUpdateSession failed: %s", pc_memory_last_error());
    }

    if (callback_class) env->DeleteLocalRef(callback_class);
    env->ReleaseStringUTFChars(j_memory_dir, memory_dir);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemoryLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(pc_memory_last_error());
}

// FR-026: flattened as [timestamp0, content0, annotation0, timestamp1, ...]
// (3 strings per result) rather than an array of a dedicated result class —
// matches this bridge's existing style of marshaling parallel primitive
// arrays instead of constructing JVM objects from native code.
JNIEXPORT jobjectArray JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemorySearch(
        JNIEnv * env, jclass, jstring j_memory_dir, jstring j_query, jint max_results) {
    const char * memory_dir = env->GetStringUTFChars(j_memory_dir, nullptr);
    const char * query = env->GetStringUTFChars(j_query, nullptr);

    pc_memory_search_result * results = nullptr;
    size_t count = 0;
    const int rc = pc_memory_search(memory_dir, query, max_results, &results, &count);

    env->ReleaseStringUTFChars(j_query, query);
    env->ReleaseStringUTFChars(j_memory_dir, memory_dir);

    const jclass string_class = env->FindClass("java/lang/String");
    if (rc != 0) {
        LOGW("nativeMemorySearch failed: %s", pc_memory_last_error());
        return env->NewObjectArray(0, string_class, nullptr);
    }

    const jobjectArray out = env->NewObjectArray((jsize) (count * 3), string_class, nullptr);
    for (size_t i = 0; i < count; i++) {
        env->SetObjectArrayElement(out, (jsize) (i * 3),     env->NewStringUTF(results[i].timestamp));
        env->SetObjectArrayElement(out, (jsize) (i * 3 + 1), env->NewStringUTF(results[i].content));
        env->SetObjectArrayElement(out, (jsize) (i * 3 + 2), env->NewStringUTF(results[i].annotation));
    }
    pc_memory_free_search_results(results, count);
    return out;
}

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemoryDeleteSummary(
        JNIEnv * env, jclass, jstring j_memory_dir, jstring j_timestamp) {
    const char * memory_dir = env->GetStringUTFChars(j_memory_dir, nullptr);
    const char * timestamp = env->GetStringUTFChars(j_timestamp, nullptr);
    pc_memory_delete_summary(memory_dir, timestamp);
    env->ReleaseStringUTFChars(j_timestamp, timestamp);
    env->ReleaseStringUTFChars(j_memory_dir, memory_dir);
}

JNIEXPORT void JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeMemoryDeleteProfileFact(
        JNIEnv * env, jclass, jstring j_memory_dir, jstring j_fact_text) {
    const char * memory_dir = env->GetStringUTFChars(j_memory_dir, nullptr);
    const char * fact_text = env->GetStringUTFChars(j_fact_text, nullptr);
    pc_memory_delete_profile_fact(memory_dir, fact_text);
    env->ReleaseStringUTFChars(j_fact_text, fact_text);
    env->ReleaseStringUTFChars(j_memory_dir, memory_dir);
}

JNIEXPORT jint JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeSafetyCheck(JNIEnv * env, jclass, jstring j_text) {
    const char * text = env->GetStringUTFChars(j_text, nullptr);
    const int rc = pc_safety_check(text);
    env->ReleaseStringUTFChars(j_text, text);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeSafetyLastCategory(JNIEnv * env, jclass) {
    return env->NewStringUTF(pc_safety_last_category());
}

} // extern "C"
