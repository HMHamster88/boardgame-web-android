#include <jni.h>

#include <android/log.h>
#include <cerrno>
#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

#include "node.h"

namespace {
constexpr const char* kLogTag = "NODEJS-MOBILE";
constexpr jint kStreamStdout = 1;
constexpr jint kStreamStderr = 2;

JavaVM* g_jvm = nullptr;
jclass g_node_runtime_class = nullptr;
jmethodID g_on_output_method = nullptr;

int g_stdout_pipe[2];
int g_stderr_pipe[2];
int g_stdin_pipe[2] = {-1, -1};
pthread_t g_stdout_thread;
pthread_t g_stderr_thread;
pthread_mutex_t g_stdin_mutex = PTHREAD_MUTEX_INITIALIZER;

JNIEnv* get_java_env(bool& attached) {
    attached = false;

    if (g_jvm == nullptr) {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    const jint result = g_jvm->GetEnv(
            reinterpret_cast<void**>(&env),
            JNI_VERSION_1_6);

    if (result == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        attached = true;
    } else if (result != JNI_OK) {
        return nullptr;
    }

    return env;
}

void emit_output(JNIEnv* env, jint stream, const char* text, size_t length) {
    if (env == nullptr || g_node_runtime_class == nullptr || g_on_output_method == nullptr) {
        return;
    }

    auto bytes = env->NewByteArray(static_cast<jsize>(length));
    if (bytes == nullptr) {
        return;
    }

    env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(length),
            reinterpret_cast<const jbyte*>(text));

    env->CallStaticVoidMethod(
            g_node_runtime_class,
            g_on_output_method,
            stream,
            bytes);

    env->DeleteLocalRef(bytes);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void log_output(jint stream, const std::string& text) {
    std::string line = text;
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
        line.pop_back();
    }

    __android_log_write(
            stream == kStreamStdout ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
            kLogTag,
            line.c_str());
}

void* forward_stream(void* argument) {
    const int file_descriptor = *static_cast<int*>(argument);
    const jint stream = file_descriptor == g_stdout_pipe[0]
            ? kStreamStdout
            : kStreamStderr;

    bool attached = false;
    JNIEnv* env = get_java_env(attached);

    char buffer[4096];
    ssize_t bytes_read;
    while ((bytes_read = read(file_descriptor, buffer, sizeof(buffer))) > 0) {
        const std::string chunk(buffer, static_cast<size_t>(bytes_read));
        log_output(stream, chunk);
        emit_output(env, stream, chunk.data(), chunk.size());
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }

    return nullptr;
}

int start_redirecting_stdout_stderr() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    if (pipe(g_stdout_pipe) != 0) {
        return -1;
    }
    dup2(g_stdout_pipe[1], STDOUT_FILENO);

    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(g_stderr_pipe) != 0) {
        return -1;
    }
    dup2(g_stderr_pipe[1], STDERR_FILENO);

    if (pthread_create(&g_stdout_thread, nullptr, forward_stream, &g_stdout_pipe[0]) != 0) {
        return -1;
    }
    pthread_detach(g_stdout_thread);

    if (pthread_create(&g_stderr_thread, nullptr, forward_stream, &g_stderr_pipe[0]) != 0) {
        return -1;
    }
    pthread_detach(g_stderr_thread);

    return 0;
}

void register_output_callback(JNIEnv* env, jclass clazz) {
    if (g_on_output_method != nullptr || clazz == nullptr) {
        return;
    }

    g_node_runtime_class = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (g_node_runtime_class == nullptr) {
        return;
    }

    g_on_output_method = env->GetStaticMethodID(
            g_node_runtime_class,
            "onNativeOutput",
            "(I[B)V");

    if (g_on_output_method == nullptr) {
        __android_log_write(
                ANDROID_LOG_WARN,
                kLogTag,
                "Could not register the native console output callback.");
    }
}

int start_stdin_pipe() {
    if (pipe(g_stdin_pipe) != 0) {
        return -1;
    }

    if (dup2(g_stdin_pipe[0], STDIN_FILENO) < 0) {
        return -1;
    }

    // Node reads from the duplicated descriptor 0. The original read end is
    // no longer needed; the app keeps the write end for console input.
    close(g_stdin_pipe[0]);
    g_stdin_pipe[0] = -1;

    return 0;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_jvm = vm;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass local_class = env->FindClass("com/hmhamster/boardgameweb/NodeRuntime");
    if (local_class != nullptr) {
        g_node_runtime_class = static_cast<jclass>(env->NewGlobalRef(local_class));
        env->DeleteLocalRef(local_class);

        if (g_node_runtime_class != nullptr) {
            g_on_output_method = env->GetStaticMethodID(
                    g_node_runtime_class,
                    "onNativeOutput",
                    "(I[B)V");
        }
    }

    if (g_on_output_method == nullptr) {
        __android_log_write(
                ANDROID_LOG_WARN,
                kLogTag,
                "Native console output callback is unavailable.");
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK
            && g_node_runtime_class != nullptr) {
        env->DeleteGlobalRef(g_node_runtime_class);
    }

    g_node_runtime_class = nullptr;
    g_on_output_method = nullptr;
    g_jvm = nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hmhamster_boardgameweb_NodeRuntime_startNodeWithArguments(
        JNIEnv* env,
        jclass clazz,
        jobjectArray arguments) {
    register_output_callback(env, clazz);

    const jsize argument_count = env->GetArrayLength(arguments);

    // node::Start expects a contiguous, mutable argv array.
    std::vector<std::string> argument_storage;
    argument_storage.reserve(static_cast<size_t>(argument_count));

    for (jsize i = 0; i < argument_count; ++i) {
        auto java_argument = static_cast<jstring>(
                env->GetObjectArrayElement(arguments, i));
        const char* utf8_argument = env->GetStringUTFChars(java_argument, nullptr);

        if (utf8_argument == nullptr) {
            env->DeleteLocalRef(java_argument);
            return -1;
        }

        argument_storage.emplace_back(utf8_argument);
        env->ReleaseStringUTFChars(java_argument, utf8_argument);
        env->DeleteLocalRef(java_argument);
    }

    std::vector<char*> argv;
    argv.reserve(argument_storage.size());
    for (std::string& argument : argument_storage) {
        argv.push_back(argument.data());
    }

    if (start_redirecting_stdout_stderr() != 0) {
        __android_log_write(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Could not redirect Node.js stdout/stderr to logcat.");
    }

    if (start_stdin_pipe() != 0) {
        __android_log_write(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Could not create the Node.js stdin pipe.");
    }

    const int result = node::Start(
            static_cast<int>(argv.size()),
            argv.data());

    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hmhamster_boardgameweb_NodeRuntime_writeToNodeStdin(
        JNIEnv* env,
        jclass /* clazz */,
        jstring text) {
    if (g_stdin_pipe[1] < 0) {
        return JNI_FALSE;
    }

    const char* utf8_text = env->GetStringUTFChars(text, nullptr);
    if (utf8_text == nullptr) {
        return JNI_FALSE;
    }

    std::string line(utf8_text);
    env->ReleaseStringUTFChars(text, utf8_text);

    if (line.empty() || line.back() != '\n') {
        line.push_back('\n');
    }

    bool success = true;
    pthread_mutex_lock(&g_stdin_mutex);

    size_t offset = 0;
    while (offset < line.size()) {
        const ssize_t written = write(
                g_stdin_pipe[1],
                line.data() + offset,
                line.size() - offset);

        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            success = false;
            break;
        }

        if (written == 0) {
            success = false;
            break;
        }

        offset += static_cast<size_t>(written);
    }

    pthread_mutex_unlock(&g_stdin_mutex);
    return success ? JNI_TRUE : JNI_FALSE;
}
