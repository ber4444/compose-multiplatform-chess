#include "filament_chess_core.h"

#include <jni.h>

#include <exception>
#include <mutex>
#include <string>
#include <vector>

using chess3d::FilamentChessCore;

namespace {

std::mutex gErrorMutex;
std::string gLastCreateError;

std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    jsize n = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) {
        env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(out.data()));
    }
    return out;
}

void setLastCreateError(const std::string& error) {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    gLastCreateError = error;
}

std::string lastCreateError() {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    return gLastCreateError;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeCreate(
        JNIEnv* env, jobject, jbyteArray glbArray, jbyteArray iblArray, jbyteArray skyboxArray) {
    auto glb = bytes(env, glbArray);
    auto ibl = bytes(env, iblArray);
    auto skybox = bytes(env, skyboxArray);

    FilamentChessCore* core = nullptr;
    try {
        core = new FilamentChessCore(
            glb.data(), static_cast<int>(glb.size()),
            ibl.data(), static_cast<int>(ibl.size()),
            skybox.data(), static_cast<int>(skybox.size()));
    } catch (const std::exception& e) {
        setLastCreateError(std::string("Desktop Filament create threw: ") + e.what());
        return 0;
    } catch (...) {
        setLastCreateError("Desktop Filament create threw an unknown C++ exception");
        return 0;
    }
    if (!core->valid()) {
        setLastCreateError(core->lastError());
        delete core;
        return 0;
    }
    setLastCreateError("");
    return reinterpret_cast<jlong>(core);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<FilamentChessCore*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeResize(
        JNIEnv*, jobject, jlong handle, jint width, jint height) {
    if (auto* core = reinterpret_cast<FilamentChessCore*>(handle)) {
        core->resize(width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeSetScene(
        JNIEnv* env, jobject, jlong handle, jstring encoded) {
    if (!handle || !encoded) return;
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    reinterpret_cast<FilamentChessCore*>(handle)->setScene(chars);
    env->ReleaseStringUTFChars(encoded, chars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeSetCamera(
        JNIEnv* env, jobject, jlong handle, jstring encoded) {
    if (!handle || !encoded) return;
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    reinterpret_cast<FilamentChessCore*>(handle)->setCamera(chars);
    env->ReleaseStringUTFChars(encoded, chars);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeRenderRgba(
        JNIEnv* env, jobject, jlong handle) {
    if (!handle) return nullptr;
    auto result = reinterpret_cast<FilamentChessCore*>(handle)->render();
    if (!result.error.empty() || result.rgba.empty()) return nullptr;
    auto out = env->NewByteArray(static_cast<jsize>(result.rgba.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(
        out, 0, static_cast<jsize>(result.rgba.size()),
        reinterpret_cast<const jbyte*>(result.rgba.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_board3d_DesktopFilamentNative_nativeLastError(
        JNIEnv* env, jobject, jlong handle) {
    std::string error;
    if (auto* core = reinterpret_cast<FilamentChessCore*>(handle)) {
        error = core->lastError();
    } else {
        error = lastCreateError();
    }
    if (error.empty()) return nullptr;
    return env->NewStringUTF(error.c_str());
}
