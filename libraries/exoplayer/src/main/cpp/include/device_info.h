#pragma once
#include <jni.h>
#include <string>
#include <mutex>

namespace fplay {

struct GlobalState {
    JavaVM* javaVM = nullptr;
    jobject application = nullptr;  // global ref

    // Device properties (matching Sigma field names)
    std::string signature;
    std::string appVersionCode;
    std::string appVersion;
    std::string packageId;
    std::string buildBoard;
    std::string buildHost;
    std::string packageName;
    std::string sdkVersion;
    std::string deviceId;       // android_id
    std::string deviceModel;
    std::string packerVersion;
    std::string brand;
    std::string osVersion;
    std::string buildProduct;
    std::string manufacture;
    std::string cpuInfo;
    std::string osBuild;
    std::string platform;
    std::string deviceName;
    std::string fingerprint;

    bool initialized = false;

    static GlobalState& getInstance();
    void init(JNIEnv* env, JavaVM* vm);

private:
    GlobalState() = default;
    void collectBuildInfo(JNIEnv* env);
    void collectDeviceId(JNIEnv* env);
    std::mutex mutex_;
};

} // namespace fplay