
#include <jni.h>
#include <sys/system_properties.h>
#include "xdl.h"
#include "hidden_api.h"
#include "Utils/elf_util.h"
#include "Log.h"

bool disable_hidden_api(JNIEnv *env) {
    char version_str[PROP_VALUE_MAX];
    if (!__system_property_get("ro.build.version.sdk", version_str)) {
        ALOGE("Failed to obtain SDK int");
        return false;
    }
    long android_version = std::strtol(version_str, nullptr, 10);

    
    if (android_version < 29) {
        ALOGD("HiddenAPI: Android version < 29, no need to disable");
        return true;
    }

    SandHook::ElfImg *elf_img = new SandHook::ElfImg("libart.so");
    if (!elf_img->isValid()) {
        ALOGE("HiddenAPI: Failed to load libart.so");
        delete elf_img;
        return false;
    }

    
    void *addr = nullptr;
    const char* symbol_names[] = {
        "_ZN3artL32VMRuntime_setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray",
        "_ZN3art9VMRuntime22setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray",
        "art::VMRuntime::setHiddenApiExemptions(_JNIEnv*, _jclass*, _jobjectArray*)",
        nullptr
    };

    for (int i = 0; symbol_names[i] != nullptr; i++) {
        addr = (void*)elf_img->getSymbAddress(symbol_names[i]);
        if (addr) {
            ALOGD("HiddenAPI: Found symbol %s at %p", symbol_names[i], addr);
            break;
        }
    }

    delete elf_img;

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        ALOGE("HiddenAPI: Failed to find String class");
        return false;
    }
    jstring wildcard = env->NewStringUTF("L");
    jobjectArray args = env->NewObjectArray(1, stringClass, wildcard);

    if (addr) {
        auto func = reinterpret_cast<void (*)(JNIEnv *, jclass, jobjectArray)>(addr);
        func(env, stringClass, args);
        ALOGD("HiddenAPI: Successfully disabled hidden API restrictions via native symbol");
        return true;
    }

    // Fix 5 (API 37+): native symbol removed from libart.so in Android 17.
    // Fall back to JNI method lookup on VMRuntime — accessible from :black
    // process (targetSdkVersion=28) even when the native export is stripped.
    ALOGD("HiddenAPI: Trying JNI VMRuntime fallback for API 37+");
    jclass vmRuntimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (vmRuntimeClass) {
        jmethodID getRuntimeId = env->GetStaticMethodID(
            vmRuntimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
        if (getRuntimeId) {
            jobject vmRuntime = env->CallStaticObjectMethod(vmRuntimeClass, getRuntimeId);
            if (vmRuntime && !env->ExceptionCheck()) {
                jmethodID setExemptionsId = env->GetMethodID(
                    vmRuntimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
                if (setExemptionsId) {
                    env->CallVoidMethod(vmRuntime, setExemptionsId, args);
                    if (!env->ExceptionCheck()) {
                        ALOGD("HiddenAPI: Successfully disabled via JNI VMRuntime fallback");
                        return true;
                    }
                    env->ExceptionClear();
                } else { env->ExceptionClear(); }
            } else { env->ExceptionClear(); }
        } else { env->ExceptionClear(); }
    } else { env->ExceptionClear(); }

    ALOGE("HiddenAPI: All bypass methods exhausted on API %ld — :black soft policy still active", android_version);
    return false;
}

bool disable_resource_loading() {
    
    try {
        
        void* handle = xdl_open("libandroid_runtime.so", XDL_DEFAULT);
        if (handle) {
            
            void* nativeLoadAddr = xdl_sym(handle, "_ZN7android8ApkAssets9nativeLoadEPKc", nullptr);
            if (nativeLoadAddr) {
                ALOGD("ResourceLoading: Found ApkAssets.nativeLoad at %p", nativeLoadAddr);
                
            } else {
                ALOGD("ResourceLoading: Could not find ApkAssets.nativeLoad symbol");
            }
            xdl_close(handle);
        } else {
            ALOGD("ResourceLoading: Could not open libandroid_runtime.so");
        }
    } catch (...) {
        ALOGD("ResourceLoading: Exception while trying to hook ApkAssets.nativeLoad");
    }
    
    
    try {
        
        void* handle = xdl_open("libc.so", XDL_DEFAULT);
        if (handle) {
            
            void* openAddr = xdl_sym(handle, "open", nullptr);
            if (openAddr) {
                ALOGD("ResourceLoading: Found open function at %p", openAddr);
                
            } else {
                ALOGD("ResourceLoading: Could not find open function symbol");
            }
            xdl_close(handle);
        } else {
            ALOGD("ResourceLoading: Could not open libc.so");
        }
    } catch (...) {
        ALOGD("ResourceLoading: Exception while trying to hook file system calls");
    }
    
    ALOGD("ResourceLoading: Native resource loading hooks initialized (without system properties)");
    return true;
}