#include "include/device_info.h"
#include "include/obfuscation.h"
#include <android/log.h>
#include <vector>
#include <cstdint>

#define LOG_TAG "FPlayDRM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fplay {

GlobalState& GlobalState::getInstance() {
    static GlobalState instance;
    return instance;
}

// Helper: get a static String field from a class
static std::string getStaticStringField(JNIEnv* env, const char* className, const char* fieldName) {
    jclass cls = env->FindClass(className);
    if (!cls || env->ExceptionCheck()) { env->ExceptionClear(); return ""; }
    jfieldID fid = env->GetStaticFieldID(cls, fieldName, "Ljava/lang/String;");
    if (!fid || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(cls); return ""; }
    auto jstr = (jstring) env->GetStaticObjectField(cls, fid);
    if (!jstr) { env->DeleteLocalRef(cls); return ""; }
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    std::string result(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    env->DeleteLocalRef(jstr);
    env->DeleteLocalRef(cls);
    return result;
}

// Helper: get a static int field from a class
static int getStaticIntField(JNIEnv* env, const char* className, const char* fieldName) {
    jclass cls = env->FindClass(className);
    if (!cls || env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    jfieldID fid = env->GetStaticFieldID(cls, fieldName, "I");
    if (!fid || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(cls); return -1; }
    int val = env->GetStaticIntField(cls, fid);
    env->DeleteLocalRef(cls);
    return val;
}

// Helper: get a String instance field from an object
static std::string getStringField(JNIEnv* env, jobject obj, jclass cls, const char* fieldName) {
    jfieldID fid = env->GetFieldID(cls, fieldName, "Ljava/lang/String;");
    if (!fid || env->ExceptionCheck()) { env->ExceptionClear(); return ""; }
    auto jstr = (jstring) env->GetObjectField(obj, fid);
    if (!jstr) return "";
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    std::string result(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    env->DeleteLocalRef(jstr);
    return result;
}

void GlobalState::init(JNIEnv* env, JavaVM* vm) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialized) return;

    javaVM = vm;

    // Get Application context via ActivityThread.currentActivityThread().getApplication()
    std::string atClass = deobfuscate(OBF_ACTIVITY_THREAD_CLASS, OBF_ACTIVITY_THREAD_CLASS_LEN);
    std::string catMethod = deobfuscate(OBF_CURRENT_ACTIVITY_THREAD, OBF_CURRENT_ACTIVITY_THREAD_LEN);
    std::string catSig = deobfuscate(OBF_CURRENT_ACTIVITY_THREAD_SIG, OBF_CURRENT_ACTIVITY_THREAD_SIG_LEN);
    std::string gaMethod = deobfuscate(OBF_GET_APPLICATION, OBF_GET_APPLICATION_LEN);
    std::string gaSig = deobfuscate(OBF_GET_APPLICATION_SIG, OBF_GET_APPLICATION_SIG_LEN);

    jclass activityThreadCls = env->FindClass(atClass.c_str());
    if (!activityThreadCls || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to find ActivityThread class");
        return;
    }

    jmethodID catMid = env->GetStaticMethodID(activityThreadCls, catMethod.c_str(), catSig.c_str());
    if (!catMid || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(activityThreadCls);
        LOGE("Failed to find currentActivityThread method");
        return;
    }

    jobject activityThread = env->CallStaticObjectMethod(activityThreadCls, catMid);
    if (!activityThread || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(activityThreadCls);
        LOGE("Failed to call currentActivityThread");
        return;
    }

    jmethodID gaMid = env->GetMethodID(activityThreadCls, gaMethod.c_str(), gaSig.c_str());
    if (!gaMid || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(activityThread);
        env->DeleteLocalRef(activityThreadCls);
        LOGE("Failed to find getApplication method");
        return;
    }

    jobject app = env->CallObjectMethod(activityThread, gaMid);
    if (!app || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(activityThread);
        env->DeleteLocalRef(activityThreadCls);
        LOGE("Failed to get Application");
        return;
    }

    application = env->NewGlobalRef(app);
    env->DeleteLocalRef(app);
    env->DeleteLocalRef(activityThread);
    env->DeleteLocalRef(activityThreadCls);

    collectBuildInfo(env);
    collectDeviceId(env);

    packerVersion = "1.0.3";
    initialized = true;
}

void GlobalState::collectBuildInfo(JNIEnv* env) {
    // Deobfuscate class names
    std::string buildClass = deobfuscate(OBF_BUILD_CLASS, OBF_BUILD_CLASS_LEN);
    std::string buildVersionClass = deobfuscate(OBF_BUILD_VERSION_CLASS, OBF_BUILD_VERSION_CLASS_LEN);

    deviceModel = getStaticStringField(env, buildClass.c_str(), "MODEL");
    brand = getStaticStringField(env, buildClass.c_str(), "BRAND");
    fingerprint = getStaticStringField(env, buildClass.c_str(), "FINGERPRINT");
    buildBoard = getStaticStringField(env, buildClass.c_str(), "BOARD");
    buildHost = getStaticStringField(env, buildClass.c_str(), "HOST");
    buildProduct = getStaticStringField(env, buildClass.c_str(), "PRODUCT");
    manufacture = getStaticStringField(env, buildClass.c_str(), "MANUFACTURER");
    deviceName = getStaticStringField(env, buildClass.c_str(), "DEVICE");
    cpuInfo = getStaticStringField(env, buildClass.c_str(), "HARDWARE");

    // Build display string (e.g. "BT2A.250812.001.B1 dev-keys")
    osBuild = getStaticStringField(env, buildClass.c_str(), "DISPLAY");

    int sdk = getStaticIntField(env, buildVersionClass.c_str(), "SDK_INT");
    sdkVersion = std::to_string(sdk);

    std::string release = getStaticStringField(env, buildVersionClass.c_str(), "RELEASE");
    osVersion = release;

    platform = "android";
}

void GlobalState::collectDeviceId(JNIEnv* env) {
    if (!application) return;

    // --- Get android_id via Settings.Secure.getString(contentResolver, "android_id") ---
    std::string crClass = deobfuscate(OBF_CONTENT_RESOLVER_CLASS, OBF_CONTENT_RESOLVER_CLASS_LEN);
    std::string ssClass = deobfuscate(OBF_SETTINGS_SECURE_CLASS, OBF_SETTINGS_SECURE_CLASS_LEN);
    std::string getCR = deobfuscate(OBF_GET_CONTENT_RESOLVER, OBF_GET_CONTENT_RESOLVER_LEN);
    std::string getCRSig = deobfuscate(OBF_GET_CONTENT_RESOLVER_SIG, OBF_GET_CONTENT_RESOLVER_SIG_LEN);
    std::string gsMethod = deobfuscate(OBF_GET_STRING, OBF_GET_STRING_LEN);
    std::string gsSig = deobfuscate(OBF_GET_STRING_SIG, OBF_GET_STRING_SIG_LEN);
    std::string androidIdKey = deobfuscate(OBF_ANDROID_ID, OBF_ANDROID_ID_LEN);

    // application.getContentResolver()
    jclass appCls = env->GetObjectClass(application);
    jmethodID getCRMid = env->GetMethodID(appCls, getCR.c_str(), getCRSig.c_str());
    if (getCRMid && !env->ExceptionCheck()) {
        jobject resolver = env->CallObjectMethod(application, getCRMid);
        if (resolver && !env->ExceptionCheck()) {
            // Settings.Secure.getString(resolver, "android_id")
            jclass ssCls = env->FindClass(ssClass.c_str());
            if (ssCls && !env->ExceptionCheck()) {
                jmethodID gsMid = env->GetStaticMethodID(ssCls, gsMethod.c_str(), gsSig.c_str());
                if (gsMid && !env->ExceptionCheck()) {
                    jstring keyStr = env->NewStringUTF(androidIdKey.c_str());
                    auto result = (jstring) env->CallStaticObjectMethod(ssCls, gsMid, resolver, keyStr);
                    if (result && !env->ExceptionCheck()) {
                        const char* utf = env->GetStringUTFChars(result, nullptr);
                        deviceId = utf;
                        env->ReleaseStringUTFChars(result, utf);
                        env->DeleteLocalRef(result);
                    } else { env->ExceptionClear(); }
                    env->DeleteLocalRef(keyStr);
                } else { env->ExceptionClear(); }
                env->DeleteLocalRef(ssCls);
            } else { env->ExceptionClear(); }
            env->DeleteLocalRef(resolver);
        } else { env->ExceptionClear(); }
    } else { env->ExceptionClear(); }

    // --- Get app info via PackageManager ---
    std::string gpmMethod = deobfuscate(OBF_GET_PACKAGE_MANAGER, OBF_GET_PACKAGE_MANAGER_LEN);
    std::string gpmSig = deobfuscate(OBF_GET_PACKAGE_MANAGER_SIG, OBF_GET_PACKAGE_MANAGER_SIG_LEN);
    std::string gpnMethod = deobfuscate(OBF_GET_PACKAGE_NAME, OBF_GET_PACKAGE_NAME_LEN);
    std::string gpnSig = deobfuscate(OBF_GET_PACKAGE_NAME_SIG, OBF_GET_PACKAGE_NAME_SIG_LEN);
    std::string gpiMethod = deobfuscate(OBF_GET_PACKAGE_INFO, OBF_GET_PACKAGE_INFO_LEN);
    std::string gpiSig = deobfuscate(OBF_GET_PACKAGE_INFO_SIG, OBF_GET_PACKAGE_INFO_SIG_LEN);
    std::string piClass = deobfuscate(OBF_PACKAGE_INFO_CLASS, OBF_PACKAGE_INFO_CLASS_LEN);

    jmethodID gpmMid = env->GetMethodID(appCls, gpmMethod.c_str(), gpmSig.c_str());
    jmethodID gpnMid = env->GetMethodID(appCls, gpnMethod.c_str(), gpnSig.c_str());

    if (gpmMid && gpnMid && !env->ExceptionCheck()) {
        jobject pm = env->CallObjectMethod(application, gpmMid);
        auto pkgNameStr = (jstring) env->CallObjectMethod(application, gpnMid);

        if (pm && pkgNameStr && !env->ExceptionCheck()) {
            // Store package name (e.g. "net.fptplay.ottbox")
            const char* pkgUtf = env->GetStringUTFChars(pkgNameStr, nullptr);
            packageId = pkgUtf;
            env->ReleaseStringUTFChars(pkgNameStr, pkgUtf);

            jclass pmCls = env->GetObjectClass(pm);
            jmethodID gpiMid = env->GetMethodID(pmCls, gpiMethod.c_str(), gpiSig.c_str());

            if (gpiMid && !env->ExceptionCheck()) {
                // GET_SIGNATURES = 0x40
                jobject pkgInfo = env->CallObjectMethod(pm, gpiMid, pkgNameStr, (jint)0x40);
                if (pkgInfo && !env->ExceptionCheck()) {
                    jclass piCls = env->FindClass(piClass.c_str());
                    if (piCls && !env->ExceptionCheck()) {
                        appVersion = getStringField(env, pkgInfo, piCls, "versionName");

                        // Get versionCode
                        jfieldID vcFid = env->GetFieldID(piCls, "versionCode", "I");
                        if (vcFid && !env->ExceptionCheck()) {
                            int vc = env->GetIntField(pkgInfo, vcFid);
                            appVersionCode = std::to_string(vc);
                        } else { env->ExceptionClear(); }

                        // Get app label (packageName like "FPT Play")
                        jclass pmClsForLabel = env->GetObjectClass(pm);
                        jmethodID getAppLabelMid = env->GetMethodID(pmClsForLabel,
                            "getApplicationLabel",
                            "(Landroid/content/pm/ApplicationInfo;)Ljava/lang/CharSequence;");
                        if (getAppLabelMid && !env->ExceptionCheck()) {
                            // Get applicationInfo field from PackageInfo
                            jfieldID aiFid = env->GetFieldID(piCls, "applicationInfo",
                                "Landroid/content/pm/ApplicationInfo;");
                            if (aiFid && !env->ExceptionCheck()) {
                                jobject appInfo = env->GetObjectField(pkgInfo, aiFid);
                                if (appInfo && !env->ExceptionCheck()) {
                                    jobject label = env->CallObjectMethod(pm, getAppLabelMid, appInfo);
                                    if (label && !env->ExceptionCheck()) {
                                        // CharSequence.toString()
                                        jclass csCls = env->GetObjectClass(label);
                                        jmethodID toStringMid = env->GetMethodID(csCls, "toString", "()Ljava/lang/String;");
                                        if (toStringMid && !env->ExceptionCheck()) {
                                            auto labelStr = (jstring) env->CallObjectMethod(label, toStringMid);
                                            if (labelStr && !env->ExceptionCheck()) {
                                                const char* lUtf = env->GetStringUTFChars(labelStr, nullptr);
                                                packageName = lUtf;
                                                env->ReleaseStringUTFChars(labelStr, lUtf);
                                                env->DeleteLocalRef(labelStr);
                                            } else { env->ExceptionClear(); }
                                        } else { env->ExceptionClear(); }
                                        env->DeleteLocalRef(csCls);
                                        env->DeleteLocalRef(label);
                                    } else { env->ExceptionClear(); }
                                    env->DeleteLocalRef(appInfo);
                                } else { env->ExceptionClear(); }
                            } else { env->ExceptionClear(); }
                        } else { env->ExceptionClear(); }
                        env->DeleteLocalRef(pmClsForLabel);

                        // Get signature hash
                        jfieldID sigsFid = env->GetFieldID(piCls, "signatures",
                            "[Landroid/content/pm/Signature;");
                        if (sigsFid && !env->ExceptionCheck()) {
                            auto sigsArr = (jobjectArray) env->GetObjectField(pkgInfo, sigsFid);
                            if (sigsArr && !env->ExceptionCheck()) {
                                int sigCount = env->GetArrayLength(sigsArr);
                                if (sigCount > 0) {
                                    jobject sig0 = env->GetObjectArrayElement(sigsArr, 0);
                                    if (sig0 && !env->ExceptionCheck()) {
                                        // Signature.hashCode() -> hex uppercase
                                        jclass sigCls = env->GetObjectClass(sig0);
                                        jmethodID hashCodeMid = env->GetMethodID(sigCls, "hashCode", "()I");
                                        // Use toByteArray then SHA1 for proper signature
                                        jmethodID toBytesMid = env->GetMethodID(sigCls, "toByteArray", "()[B");
                                        if (toBytesMid && !env->ExceptionCheck()) {
                                            auto sigBytes = (jbyteArray) env->CallObjectMethod(sig0, toBytesMid);
                                            if (sigBytes && !env->ExceptionCheck()) {
                                                // MessageDigest.getInstance("SHA-1").digest(bytes)
                                                jclass mdCls = env->FindClass("java/security/MessageDigest");
                                                if (mdCls && !env->ExceptionCheck()) {
                                                    jmethodID getMdMid = env->GetStaticMethodID(mdCls, "getInstance",
                                                        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
                                                    if (getMdMid && !env->ExceptionCheck()) {
                                                        jstring sha1Str = env->NewStringUTF("SHA-1");
                                                        jobject md = env->CallStaticObjectMethod(mdCls, getMdMid, sha1Str);
                                                        if (md && !env->ExceptionCheck()) {
                                                            jmethodID digestMid = env->GetMethodID(mdCls, "digest", "([B)[B");
                                                            if (digestMid && !env->ExceptionCheck()) {
                                                                auto hashBytes = (jbyteArray) env->CallObjectMethod(md, digestMid, sigBytes);
                                                                if (hashBytes && !env->ExceptionCheck()) {
                                                                    jsize hashLen = env->GetArrayLength(hashBytes);
                                                                    std::vector<uint8_t> hash(hashLen);
                                                                    env->GetByteArrayRegion(hashBytes, 0, hashLen,
                                                                        reinterpret_cast<jbyte*>(hash.data()));
                                                                    // Convert to uppercase hex
                                                                    char hexBuf[41];
                                                                    for (int i = 0; i < (int)hash.size() && i < 20; i++) {
                                                                        snprintf(hexBuf + i * 2, 3, "%02X", hash[i]);
                                                                    }
                                                                    hexBuf[hash.size() * 2] = '\0';
                                                                    signature = hexBuf;
                                                                    env->DeleteLocalRef(hashBytes);
                                                                } else { env->ExceptionClear(); }
                                                            } else { env->ExceptionClear(); }
                                                            env->DeleteLocalRef(md);
                                                        } else { env->ExceptionClear(); }
                                                        env->DeleteLocalRef(sha1Str);
                                                    } else { env->ExceptionClear(); }
                                                    env->DeleteLocalRef(mdCls);
                                                } else { env->ExceptionClear(); }
                                                env->DeleteLocalRef(sigBytes);
                                            } else { env->ExceptionClear(); }
                                        } else { env->ExceptionClear(); }
                                        env->DeleteLocalRef(sigCls);
                                        env->DeleteLocalRef(sig0);
                                    } else { env->ExceptionClear(); }
                                }
                                env->DeleteLocalRef(sigsArr);
                            } else { env->ExceptionClear(); }
                        } else { env->ExceptionClear(); }

                        env->DeleteLocalRef(piCls);
                    } else { env->ExceptionClear(); }
                    env->DeleteLocalRef(pkgInfo);
                } else { env->ExceptionClear(); }
            } else { env->ExceptionClear(); }

            env->DeleteLocalRef(pmCls);
        } else { env->ExceptionClear(); }

        if (pm) env->DeleteLocalRef(pm);
        if (pkgNameStr) env->DeleteLocalRef(pkgNameStr);
    } else { env->ExceptionClear(); }

    env->DeleteLocalRef(appCls);
}

} // namespace fplay
