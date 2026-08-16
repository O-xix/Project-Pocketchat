// JNI glue between com.pocketchat.app.inference.PocketChatEngine (Kotlin) and
// core/inference/pocketchat_inference.h (the shared C++ core). Kept as thin as
// possible: marshal JVM types to/from the C API, no logic of its own.
#include <jni.h>
#include <android/log.h>

#include <vector>

#include "pocketchat_inference.h"

#define LOG_TAG "PocketChatJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

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
    const jsize n_messages = env->GetArrayLength(roles);

    // pc_chat_message only holds raw `const char *`, so the jstrings and their
    // UTF-8 copies must stay alive for the whole call.
    std::vector<jstring>            role_refs(n_messages), content_refs(n_messages);
    std::vector<const char *>       role_chars(n_messages), content_chars(n_messages);
    std::vector<pc_chat_message>    messages(n_messages);

    for (jsize i = 0; i < n_messages; i++) {
        role_refs[i]    = (jstring) env->GetObjectArrayElement(roles, i);
        content_refs[i] = (jstring) env->GetObjectArrayElement(contents, i);
        role_chars[i]    = env->GetStringUTFChars(role_refs[i], nullptr);
        content_chars[i] = env->GetStringUTFChars(content_refs[i], nullptr);
        messages[i] = { role_chars[i], content_chars[i] };
    }

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
    const int rc = pc_generate_chat(ctx, messages.data(), (size_t) n_messages, sampling,
                                     jni_token_callback, &jni_cb);
    if (rc != 0) {
        LOGW("nativeGenerateChat failed: %s", pc_last_error());
    }

    for (jsize i = 0; i < n_messages; i++) {
        env->ReleaseStringUTFChars(role_refs[i], role_chars[i]);
        env->ReleaseStringUTFChars(content_refs[i], content_chars[i]);
        env->DeleteLocalRef(role_refs[i]);
        env->DeleteLocalRef(content_refs[i]);
    }
    env->DeleteLocalRef(callback_class);

    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_pocketchat_app_inference_PocketChatEngine_nativeLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(pc_last_error());
}

} // extern "C"
