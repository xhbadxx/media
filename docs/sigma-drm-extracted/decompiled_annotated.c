// =====================================================================
// libdrmpacker.so — Annotated Decompiled Code
// Ghidra decompile + manual JNI offset mapping
// =====================================================================


// =====================================================================
// JNI_OnLoad — Called khi System.loadLibrary("drmpacker")
// =====================================================================

int JNI_OnLoad(JavaVM *vm) {
    // Tạo singleton object chứa global state
    GlobalState *state = GetOrCreateGlobalState();  // FUN_00176de4

    // Init: lấy JNIEnv, collect device info
    InitDeviceInfo(state, vm);  // FUN_00176e7c

    return JNI_VERSION_1_6;  // 0x10006
}


// =====================================================================
// InitDeviceInfo — Thu thập thông tin thiết bị khi app khởi động
// FUN_00176e7c
// =====================================================================

void InitDeviceInfo(GlobalState *state, JavaVM *vm) {
    // Lấy JNIEnv từ JavaVM
    vm->GetEnv(&state->env, JNI_VERSION_1_6);
    JNIEnv *env = state->env;

    // Lấy Application context
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    jmethodID currentThreadMethod = env->GetStaticMethodID(
        activityThreadClass, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject activityThread = env->CallStaticObjectMethod(activityThreadClass, currentThreadMethod);

    jmethodID getAppMethod = env->GetMethodID(
        activityThreadClass, "getApplication", "()Landroid/app/Application;");
    jobject application = env->CallObjectMethod(activityThread, getAppMethod);

    state->application = application;

    // Thu thập Build properties
    CollectBuildInfo(state);     // FUN_00176f60
    // → Đọc: ro.build.version.sdk, ro.build.type, ro.build.host,
    //         ro.build.user, ro.build.display.id, ro.build.fingerprint,
    //         ro.product.model, brand, buildProduct, buildHost...

    // Thu thập thêm device info (android_id, etc.)
    CollectDeviceId(state);      // FUN_0017710c
    // → Gọi: Settings.Secure.getString(contentResolver, "android_id")
    // → Lưu: appVersion, packerVersion, deviceModel...
}


// =====================================================================
// GetOrCreateGlobalState — Singleton pattern
// FUN_00176de4
// =====================================================================

GlobalState* GetOrCreateGlobalState() {
    // Static singleton
    static GlobalState *instance = NULL;  // DAT_0020af88

    if (instance == NULL) {
        instance = new GlobalState();     // size = 0x130 bytes
        instance->vtable = &GlobalState_vtable;

        // Init 2 internal string buffers (offset +0x28 and +0xa8)
        InitString(&instance->requestIdBuffer);   // FUN_0016f2d0
        InitString(&instance->deviceInfoBuffer);  // FUN_0016f2d0

        instance->initialized = false;  // offset +0x128

        // Call virtual init
        instance->vtable->init(instance);
    }
    return instance;
}


// =====================================================================
// requestInfo — MAIN FUNCTION
// Java_com_sigma_packer_SigmaDrmPacker_requestInfo
// =====================================================================
//
// Input:  byte[] widevineChallenge
// Output: RequestInfo { requestId, deviceInfo }

jobject requestInfo(JNIEnv *env, jclass clazz, jbyteArray challengeBytes) {

    // ─── Step 1: Setup ───
    // Tìm class RequestInfo và constructor
    jclass requestInfoClass = env->FindClass("com/sigma/packer/RequestInfo");
    jmethodID constructor = env->GetMethodID(
        requestInfoClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

    // ─── Step 2: Copy challenge bytes vào native buffer ───
    int length = env->GetArrayLength(challengeBytes);
    jbyte *bytes = env->GetByteArrayElements(challengeBytes, NULL);

    // FUN_0016cd00: Tạo ByteBuffer object
    //   - malloc(length)
    //   - memset(0)
    //   - memcpy(bytes, length)
    ByteBuffer *buffer = CreateByteBuffer(bytes, length);

    // ─── Step 3: Generate requestId + encrypt deviceInfo ───
    // FUN_001797f0: Core logic
    //   Bên trong:
    //     1. GetOrCreateGlobalState() → lấy device info đã collect
    //     2. GenerateRequestId()      → tạo UUID/random string → requestId
    //     3. EncryptDeviceInfo()      → AES encrypt device info → deviceInfo
    //
    //   Output: 2 strings
    //     - result[0..0x17]  = requestId (plain text)
    //     - result[0x18..]   = deviceInfo (AES encrypted, base64 encoded)

    StringPair result;
    GenerateRequestIdAndDeviceInfo(&result, buffer);  // FUN_001797f0

    // ─── Step 4: Clean up buffer ───
    buffer->release();  // (*plVar8 + 0x18)(plVar8) = virtual destructor

    // ─── Step 5: Convert C++ strings to Java strings ───
    // requestId string
    char *requestIdPtr;
    if ((result.requestId[0] & 1) != 0) {  // SSO check (Small String Optimization)
        requestIdPtr = result.requestId_heap;  // Heap allocated
    } else {
        requestIdPtr = &result.requestId[1];   // Inline (short string)
    }
    jstring jRequestId = env->NewStringUTF(requestIdPtr);

    // deviceInfo string
    char *deviceInfoPtr;
    if ((result.deviceInfo[0] & 1) != 0) {
        deviceInfoPtr = result.deviceInfo_heap;
    } else {
        deviceInfoPtr = &result.deviceInfo[1];
    }
    jstring jDeviceInfo = env->NewStringUTF(deviceInfoPtr);

    // ─── Step 6: Create RequestInfo object ───
    jobject requestInfo = env->NewObject(
        requestInfoClass, constructor, jRequestId, jDeviceInfo);

    // ─── Cleanup C++ strings ───
    if ((result.requestId[0] & 1) != 0)  operator_delete(result.requestId_heap);
    if ((result.deviceInfo[0] & 1) != 0) operator_delete(result.deviceInfo_heap);

    // Stack canary check
    return requestInfo;
}


// =====================================================================
// GenerateRequestIdAndDeviceInfo
// FUN_001797f0
// =====================================================================
//
// Tạo requestId và encrypt deviceInfo

void GenerateRequestIdAndDeviceInfo(StringPair *output, ByteBuffer *challengeData) {

    // Lấy global state (chứa device info đã collect từ JNI_OnLoad)
    GlobalState *state = GetOrCreateGlobalState();  // FUN_00176de4

    // Generate requestId (UUID hoặc random)
    std::string requestId;
    GenerateUUID(&requestId);  // FUN_00177c1c

    // Lấy flag từ global state
    byte encryptionType = state->data[0x128];  // initialized flag hoặc encryption type

    // Allocate buffer cho encryption params
    byte *params = new byte[0x20];
    params[0] = encryptionType;

    // Copy requestId vào params
    std::string paramString(params + 8, requestId);

    // ─── Core: AES Encrypt ───
    // FUN_00179950: Encrypt device info
    //   Input:  params (chứa requestId + type) + challengeData
    //   Output: encrypted string
    std::string encryptedResult;
    AESEncrypt(&encryptedResult, params, challengeData);  // FUN_00179950

    // Set output
    output->requestId = requestId;      // [0..0x17]
    output->deviceInfo = encryptedResult; // [0x18..]

    // Cleanup
    delete[] params;
}


// =====================================================================
// AESEncrypt wrapper
// FUN_0017a4b8
// =====================================================================
//
// Xuất hiện nhiều lần trong code — đây là hàm decrypt/encrypt strings
// Dùng để:
// 1. Decrypt obfuscated class names (FindClass)
// 2. Decrypt obfuscated method names (GetMethodID)
// 3. Encrypt deviceInfo

void DecryptOrEncrypt(std::string *output, void *input) {
    // Step 1: Convert input to byte stream
    ByteBuffer *stream = ToByteStream(input, 1);  // FUN_0016ce94

    // Step 2: AES operation
    //   Key tại: DAT_0020aaa8 (16 bytes = 128-bit AES key, hardcoded trong binary)
    ByteBuffer *result = AES_Process(stream, &HARDCODED_AES_KEY, 0x10);  // FUN_0016cb14

    // Step 3: Convert to string
    ToString(output, result, 0);  // FUN_0016d5a4

    // Cleanup
    stream->release();
    result->release();
}


// =====================================================================
// getKeyRequest — Wrap MediaDrm.getKeyRequest()
// Java_com_sigma_packer_SigmaDrmPacker_getKeyRequest
// =====================================================================
//
// Input:  MediaDrm, scope, initData, mimeType, keyType, optionalParams
// Output: MediaDrm.KeyRequest (modified)

jobject getKeyRequest(JNIEnv *env, jclass clazz,
                      jobject mediaDrm, jbyteArray scope,
                      jbyteArray initData, jstring mimeType,
                      int keyType, jobject optionalParams) {

    // ─── Step 1: Decrypt obfuscated class name ───
    // s_YDt8oX564bau5g__ → AES decrypt → "android/media/MediaDrm"
    byte encrypted1[0x30];
    memcpy(encrypted1, OBFUSCATED_MEDIADRM_CLASS);  // "YDt8oX564bau5g..."
    std::string className;
    DecryptOrEncrypt(&className, encrypted1);  // → "android/media/MediaDrm"

    jclass mediaDrmClass = env->FindClass(className.c_str());
    if (env->ExceptionCheck()) goto ERROR;

    // ─── Step 2: Decrypt method name "getKeyRequest" ───
    // s_5gR_XBdIQ_PynVaZ → AES decrypt → "getKeyRequest"
    byte encrypted2[0x30];
    memcpy(encrypted2, OBFUSCATED_GETKEYREQUEST);  // "5gR_XBdIQ_PynVaZ..."
    std::string methodName;
    DecryptOrEncrypt(&methodName, encrypted2);  // → "getKeyRequest"

    jclass mediaDrmClass2 = env->FindClass(methodName.c_str());
    if (env->ExceptionCheck()) goto ERROR;

    // ─── Step 3: Decrypt method signature ───
    // s_3NRaAg1RxZGn4IiVIA__ → decrypt → getKeyRequest signature
    // "([B[BLjava/lang/String;ILjava/util/HashMap;)Landroid/media/MediaDrm$KeyRequest;"
    std::string methodSig;
    // ... similar decrypt ...

    // ─── Step 4: Get method ID and call ───
    jmethodID getKeyRequestMethod = env->GetMethodID(mediaDrmClass, methodName, methodSig);
    if (env->ExceptionCheck()) goto ERROR;

    // Call actual MediaDrm.getKeyRequest()
    // FUN_00175c30 = env->CallObjectMethod (offset 0x118)
    jobject keyRequest = env->CallObjectMethod(
        mediaDrm, getKeyRequestMethod, scope, initData, mimeType, keyType, optionalParams);
    if (env->ExceptionCheck()) goto ERROR;

    // ─── Step 5: Post-process response ───
    // "3NRaDQZX8A==" → decrypt → another method name
    // Có thể wrap/modify KeyRequest data trước khi return
    std::string postProcessMethod;
    InitString(&postProcessMethod, "3NRaDQZX8A==");
    // ... decrypt and call ...

    // Get byte[] from KeyRequest, wrap in internal buffer
    jbyteArray data = env->GetByteArrayElements(keyRequest, NULL);
    int dataLen = env->GetArrayLength(keyRequest);
    ByteBuffer *buf = CreateByteBuffer(data, dataLen);

    // Add to history tracking (max 200 entries)
    TrackRequest(buf);  // FUN_001795b4

    return keyRequest;

ERROR:
    env->ExceptionClear();
    return NULL;
}


// =====================================================================
// provideKeyResponse — Wrap MediaDrm.provideKeyResponse()
// Java_com_sigma_packer_SigmaDrmPacker_provideKeyResponse
// =====================================================================
//
// Input:  MediaDrm, scope, response (từ license server)
// Output: byte[] keySetId

jbyteArray provideKeyResponse(JNIEnv *env, jclass clazz,
                               jobject mediaDrm, jbyteArray scope,
                               jbyteArray response) {

    // ─── Step 1: Parse response vào native buffer ───
    jbyte *responseBytes = env->GetByteArrayElements(response, NULL);
    int responseLen = env->GetArrayLength(response);
    CreateByteBuffer(responseBytes, responseLen);

    // ─── Step 2: Process/decrypt response ───
    // FUN_00178f60: Parse response format
    //   - Check magic: 0x534d5756 ("SMWV" = Sigma Media Widevine?)
    //   - Read header: size, version
    //   - Read encryption type (1 = XOR?, 2 = AES)
    //   - Read IV (16 bytes)
    //   - Read encrypted payload
    //   - Decrypt payload:
    //       type 1: XOR decrypt (FUN_0016cbcc)
    //       type 2: AES decrypt (FUN_00174fd0) with IV
    //   - Validate padding (must be <= 16)
    //   - Return decrypted Widevine license bytes
    ByteBuffer *decryptedResponse = ParseAndDecryptResponse();  // FUN_00178f60

    // ─── Step 3: Find MediaDrm.provideKeyResponse method ───
    // Decrypt class name: "YDt8oX564bau5g..." → "android/media/MediaDrm"
    // Decrypt method name: "MDRRh09H..." → "provideKeyResponse"
    // Decrypt signature:   "k6UdVX1x7J8..." → "([B[B)[B"
    jclass mediaDrmClass = env->FindClass("android/media/MediaDrm");  // decrypted
    jmethodID provideMethod = env->GetMethodID(
        mediaDrmClass, "provideKeyResponse", "([B[B)[B");  // decrypted
    if (env->ExceptionCheck()) goto ERROR;

    // ─── Step 4: Create Java byte[] from decrypted response ───
    int decryptedLen = decryptedResponse->size();
    jbyteArray jDecrypted = env->NewByteArray(decryptedLen);
    jbyte *decryptedPtr = decryptedResponse->data();
    env->SetByteArrayRegion(jDecrypted, 0, decryptedLen, decryptedPtr);

    // ─── Step 5: Call real MediaDrm.provideKeyResponse() ───
    // with DECRYPTED response (not the original encrypted one)
    jbyteArray keySetId = env->CallObjectMethod(
        mediaDrm, provideMethod, scope, jDecrypted);

    env->DeleteLocalRef(jDecrypted);
    return keySetId;

ERROR:
    env->ExceptionClear();
    return NULL;
}


// =====================================================================
// ParseAndDecryptResponse — Parse Sigma response format
// FUN_00178f60
// =====================================================================
//
// Sigma server trả response KHÔNG phải raw Widevine license,
// mà là format custom: SMWV header + encrypted license
//
// Format:
//   [4 bytes] Magic: "SMWV" (0x534d5756)
//   [4 bytes] Total size
//   [4 bytes] Payload size
//   [1 byte]  Encryption type: 1=XOR, 2=AES
//   [1 byte]  ???
//   [1 byte]  Number of extra fields
//   [N bytes] Extra fields (1 byte each)
//   [16 bytes] IV (for AES)
//   [4 bytes]  Encrypted payload size
//   [N bytes]  Encrypted payload (actual Widevine license)
//
// Decryption:
//   Type 1: Simple XOR with key derived from extra fields
//   Type 2: AES-128-CBC decrypt with IV and hardcoded key
//           Then PKCS7 unpadding (validate padding <= 16)

ByteBuffer* ParseAndDecryptResponse(ByteBuffer *input) {
    int magic = input->readInt32();  // FUN_0016d534
    if (magic != 0x534d5756) {  // "SMWV"
        // Not Sigma format, return as-is
        input->reset();
        return input;
    }

    int totalSize = input->readInt32();
    long dataSize = input->getSize();  // FUN_0016d580

    if (dataSize + 8 != totalSize) return input;  // Size mismatch

    int payloadSize = input->readInt32();
    if (payloadSize >= totalSize) return input;  // Invalid

    byte encryptionType = input->readByte();  // FUN_0016d558
    byte unknown = input->readByte();
    byte numExtraFields = input->readByte();

    // Read extra fields
    vector<byte> extraFields;
    for (int i = 0; i < numExtraFields; i++) {
        extraFields.push_back(input->readByte());
    }

    // Read IV (16 bytes)
    byte iv[16];
    input->read(iv, 16);  // FUN_0016d4dc

    // Read encrypted payload
    int encPayloadSize = input->readInt32();
    byte *encPayload = malloc(encPayloadSize);
    input->read(encPayload, encPayloadSize);

    ByteBuffer *decrypted;

    if (encryptionType == 2) {
        // ─── AES-128-CBC Decrypt ───
        AES_Context ctx;
        AES_Init(&ctx);                           // FUN_00174b40
        AES_SetKey(&ctx, extraFields.data());      // FUN_00174b48

        byte *plaintext = malloc(dataSize);
        AES_CBC_Decrypt(&ctx,                      // FUN_00174fd0
            plaintext,                              // output
            input->data(), input->getSize(),        // encrypted data
            extraFields.data(),                     // key material
            iv);                                    // IV

        // PKCS7 unpadding
        byte paddingLen = plaintext[dataSize - 1];
        if (paddingLen > 16) {
            // Invalid padding
            free(plaintext);
            AES_Free(&ctx);
            return NULL;  // error
        }

        int actualSize = dataSize - paddingLen;
        decrypted = CreateByteBuffer(plaintext, actualSize);
        free(plaintext);
        AES_Free(&ctx);                            // FUN_00174b44

    } else if (encryptionType == 1) {
        // ─── XOR Decrypt ───
        decrypted = XOR_Decrypt(                   // FUN_0016cbcc
            input->data(), input->getSize(),
            extraFields.data(), extraFields.size());
    } else {
        return NULL;  // Unknown encryption
    }

    // Cleanup
    free(extraFields);
    return decrypted;
}


// =====================================================================
// TrackRequest — Lưu history request IDs (max 200)
// FUN_001795b4
// =====================================================================

void TrackRequest(ByteBuffer *requestData) {
    // Compute hash/ID từ request data
    byte *data = requestData->data();
    size_t size = requestData->getSize();
    uint32_t hash = ComputeHash(0, data, size);  // FUN_001787d8

    // Thêm vào global vector (DAT_0020af90)
    static vector<uint32_t> requestHistory;  // DAT_0020af90/98/a0
    requestHistory.push_back(hash);

    // Giữ tối đa 200 / 4 = 50 entries
    // Nếu vượt 200 bytes → xóa entry đầu tiên (FIFO)
    if (requestHistory.size() * 4 > 200) {
        requestHistory.erase(requestHistory.begin());
    }
}


// =====================================================================
// Utility functions
// =====================================================================

// FUN_0016cd00: Tạo ByteBuffer từ raw bytes
ByteBuffer* CreateByteBuffer(void *data, size_t length) {
    ByteBuffer *buf = new ByteBuffer();  // size = 0x40
    buf->vtable = &ByteBuffer_vtable;
    buf->capacity = length;
    buf->position = 0;
    buf->buffer = malloc(length);
    memset(buf->buffer, 0, length);
    buf->readPtr = buf->buffer;
    memcpy(buf->buffer, data, length);
    return buf;
}

// FUN_0016d578: Get data pointer
void* ByteBuffer_data(ByteBuffer *buf) {
    return buf->field_0x20;  // offset +0x20
}

// FUN_0016d580: Get size
size_t ByteBuffer_size(ByteBuffer *buf) {
    return buf->field_0x30;  // offset +0x30
}

// FUN_0016f320: C string to std::string (SSO)
void InitString(std::string *str, const char *cstr) {
    size_t len = strlen(cstr);
    if (len < 23) {
        // Small String Optimization: store inline
        str->sso_size = len << 1;  // size in first byte
        memcpy(&str->sso_data, cstr, len);
    } else {
        // Heap allocate
        char *heap = new char[aligned_size];
        str->heap_size = len;
        str->heap_ptr = heap;
        str->heap_capacity = aligned_size | 1;
        memcpy(heap, cstr, len);
    }
}
