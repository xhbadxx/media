// =====================================================================
// libdrmpacker.so — Java-style Pseudocode (dễ đọc)
// Viết lại từ Ghidra decompiled C code
// =====================================================================


// ─────────────────────────────────────────────────────────────────────
// CLASS: GlobalState (Singleton — lưu thông tin device suốt app lifecycle)
// Native address: DAT_0020af88
// Size: 0x130 bytes
// ─────────────────────────────────────────────────────────────────────

class GlobalState {
    JNIEnv env;                    // offset +0x08
    JavaVM javaVM;                 // offset +0x18
    Object application;            // offset +0x20 (Android Application context)
    String requestIdBuffer;        // offset +0x28
    String deviceInfoBuffer;       // offset +0xa8
    boolean initialized;           // offset +0x128

    // Device info fields (collected at init)
    String sdkVersion;             // ro.build.version.sdk
    String buildType;              // ro.build.type
    String buildHost;              // ro.build.host
    String buildUser;              // ro.build.user
    String displayId;              // ro.build.display.id
    String fingerprint;            // ro.build.fingerprint
    String model;                  // ro.product.model
    String brand;                  // Build.BRAND
    String androidId;              // Settings.Secure.ANDROID_ID
    String appVersion;
    String packerVersion;

    static GlobalState instance = null;
}


// ─────────────────────────────────────────────────────────────────────
// CLASS: RequestHistory (Lưu lịch sử request — FIFO, max 50 entries)
// Native address: DAT_0020af90/98/a0
// ─────────────────────────────────────────────────────────────────────

class RequestHistory {
    static List<Integer> hashes = new ArrayList<>();  // max 50 entries (200 bytes / 4)
}


// ─────────────────────────────────────────────────────────────────────
// AES Key (hardcoded trong binary)
// Native address: DAT_0020aaa8
// ─────────────────────────────────────────────────────────────────────

static final byte[] HARDCODED_AES_KEY = /* 16 bytes tại DAT_0020aaa8 */;


// =====================================================================
// 1. JNI_OnLoad — Gọi khi System.loadLibrary("drmpacker")
// =====================================================================

int JNI_OnLoad(JavaVM vm) {
    // Tạo singleton chứa toàn bộ state
    GlobalState state = GlobalState.getInstance();

    // Thu thập thông tin thiết bị
    state.initDeviceInfo(vm);

    return JNI_VERSION_1_6;
}


// =====================================================================
// 2. GetOrCreateGlobalState — Singleton pattern
//    Native: FUN_00176de4
// =====================================================================

static GlobalState getInstance() {
    if (GlobalState.instance == null) {
        GlobalState.instance = new GlobalState();  // allocate 0x130 bytes
        GlobalState.instance.requestIdBuffer = "";
        GlobalState.instance.deviceInfoBuffer = "";
        GlobalState.instance.initialized = false;
    }
    return GlobalState.instance;
}


// =====================================================================
// 3. InitDeviceInfo — Thu thập thông tin thiết bị
//    Native: FUN_00176e7c
//    Gọi từ: JNI_OnLoad
// =====================================================================

void initDeviceInfo(JavaVM vm) {
    // Lấy JNIEnv
    vm.GetEnv(this.env, JNI_VERSION_1_6);

    // Lấy Application context qua ActivityThread
    Class activityThreadClass = Class.forName("android.app.ActivityThread");
    Object activityThread = activityThreadClass.callStatic("currentActivityThread");
    this.application = activityThread.call("getApplication");

    // Thu thập Build properties
    collectBuildInfo();    // FUN_00176f60
    collectDeviceId();     // FUN_0017710c
}


// =====================================================================
// 4. CollectBuildInfo — Đọc system properties
//    Native: FUN_00176f60
//    Gọi từ: InitDeviceInfo
// =====================================================================

void collectBuildInfo() {
    this.sdkVersion  = SystemProperties.get("ro.build.version.sdk");
    this.buildType   = SystemProperties.get("ro.build.type");
    this.buildHost   = SystemProperties.get("ro.build.host");
    this.buildUser   = SystemProperties.get("ro.build.user");
    this.displayId   = SystemProperties.get("ro.build.display.id");
    this.fingerprint = SystemProperties.get("ro.build.fingerprint");
    this.model       = SystemProperties.get("ro.product.model");
    this.brand       = Build.BRAND;
}


// =====================================================================
// 5. CollectDeviceId — Lấy Android ID và app info
//    Native: FUN_0017710c
//    Gọi từ: InitDeviceInfo
// =====================================================================

void collectDeviceId() {
    ContentResolver resolver = this.application.getContentResolver();
    this.androidId = Settings.Secure.getString(resolver, "android_id");

    PackageInfo packageInfo = this.application.getPackageManager()
        .getPackageInfo(this.application.getPackageName(), 0);
    this.appVersion = packageInfo.versionName;
    this.packerVersion = "1.0.3";  // hardcoded hoặc từ BuildConfig
}


// =====================================================================
// 6. DecryptString — AES decrypt các string bị obfuscate
//    Native: FUN_0017a4b8
//    Dùng ở: getKeyRequest, provideKeyResponse (decrypt tên class/method)
// =====================================================================

String decryptString(byte[] encryptedInput) {
    // Bước 1: Convert input thành byte stream
    ByteBuffer stream = new ByteBuffer(encryptedInput);

    // Bước 2: AES decrypt với key hardcoded (16 bytes = AES-128)
    byte[] decrypted = AES.decrypt(stream.getData(), HARDCODED_AES_KEY);

    // Bước 3: Convert bytes → String
    return new String(decrypted, "UTF-8");

    // Ví dụ:
    //   "YDt8oX564bau5g..."  → "android/media/MediaDrm"
    //   "5gR_XBdIQ_PynVaZ"  → "getKeyRequest"
    //   "MDRRh09H..."       → "provideKeyResponse"
}


// =====================================================================
// 7. requestInfo — HÀM CHÍNH #1
//    Native: Java_com_sigma_packer_SigmaDrmPacker_requestInfo
//    Gọi từ: HttpMediaDrmCallback.executeKeyRequest()
//
//    Input:  byte[] widevineChallenge (từ MediaDrm.getKeyRequest)
//    Output: RequestInfo { requestId, deviceInfo }
// =====================================================================

public static RequestInfo requestInfo(byte[] challengeBytes) {
    // Bước 1: Copy challenge vào native buffer
    ByteBuffer buffer = new ByteBuffer(challengeBytes);

    // Bước 2: Generate requestId + encrypt device info
    String[] result = generateRequestIdAndDeviceInfo(buffer);
    String requestId  = result[0];  // UUID dạng plain text
    String deviceInfo = result[1];  // AES encrypted + Base64 encoded

    // Bước 3: Tạo Java object trả về
    return new RequestInfo(requestId, deviceInfo);
}


// =====================================================================
// 8. GenerateRequestIdAndDeviceInfo — Core logic của requestInfo
//    Native: FUN_001797f0
//    Gọi từ: requestInfo
// =====================================================================

String[] generateRequestIdAndDeviceInfo(ByteBuffer challengeData) {
    // Lấy global state (chứa device info đã collect từ JNI_OnLoad)
    GlobalState state = GlobalState.getInstance();

    // Generate UUID ngẫu nhiên làm requestId
    String requestId = UUID.randomUUID().toString();

    // Lấy encryption type từ state
    byte encryptionType = state.initialized ? 1 : 0;

    // Tạo params chứa: encryptionType + requestId
    EncryptionParams params = new EncryptionParams();
    params.type = encryptionType;
    params.requestId = requestId;

    // AES encrypt toàn bộ device info
    // Input gồm: requestId + device info (model, fingerprint, androidId, ...)
    //             + challengeData (Widevine challenge)
    String encryptedDeviceInfo = aesEncryptDeviceInfo(params, challengeData);

    return new String[] { requestId, encryptedDeviceInfo };
}


// =====================================================================
// 9. AESEncryptDeviceInfo — Encrypt device info gửi lên server
//    Native: FUN_00179950
//    Gọi từ: GenerateRequestIdAndDeviceInfo
// =====================================================================

String aesEncryptDeviceInfo(EncryptionParams params, ByteBuffer challengeData) {
    GlobalState state = GlobalState.getInstance();

    // Thu thập tất cả device info thành 1 chuỗi
    // Có thể dạng JSON hoặc concatenated string:
    // {
    //   "requestId": "uuid-here",
    //   "androidId": "...",
    //   "model": "...",
    //   "fingerprint": "...",
    //   "sdkVersion": "...",
    //   "appVersion": "...",
    //   "packerVersion": "1.0.3"
    // }
    String deviceInfoJson = buildDeviceInfoString(state);

    // AES-128 encrypt với HARDCODED_AES_KEY
    byte[] encrypted = AES.encrypt(deviceInfoJson.getBytes(), HARDCODED_AES_KEY);

    // Base64 encode
    return Base64.encode(encrypted);
}


// =====================================================================
// 10. getKeyRequest — HÀM CHÍNH #2
//     Native: Java_com_sigma_packer_SigmaDrmPacker_getKeyRequest
//     Gọi từ: SigmaMediaDrm.getKeyRequest()
//
//     Input:  MediaDrm, sessionId, initData (PSSH), mimeType, keyType, params
//     Output: MediaDrm.KeyRequest (không modify, chỉ tracking)
// =====================================================================

public static MediaDrm.KeyRequest getKeyRequest(
        MediaDrm mediaDrm,
        byte[] sessionId,
        byte[] initData,
        String mimeType,
        int keyType,
        HashMap<String, String> optionalParams) {

    // ─── Bước 1: Decrypt tên class (obfuscated) ───
    // "YDt8oX564bau5g..." → decrypt → "android/media/MediaDrm"
    String className = decryptString(OBFUSCATED_MEDIADRM_CLASS);
    // → className = "android/media/MediaDrm"

    // ─── Bước 2: Decrypt tên method (obfuscated) ───
    // "5gR_XBdIQ_PynVaZ" → decrypt → "getKeyRequest"
    String methodName = decryptString(OBFUSCATED_GETKEYREQUEST);
    // → methodName = "getKeyRequest"

    // ─── Bước 3: Decrypt method signature (obfuscated) ───
    // "3NRaAg1RxZGn4IiVIA__" → decrypt
    String methodSig = decryptString(OBFUSCATED_SIGNATURE);
    // → methodSig = "([B[BLjava/lang/String;ILjava/util/HashMap;)Landroid/media/MediaDrm$KeyRequest;"

    // ─── Bước 4: Gọi MediaDrm.getKeyRequest() THẬT ───
    // Đây là standard Widevine call, KHÔNG modify gì cả
    MediaDrm.KeyRequest keyRequest = mediaDrm.getKeyRequest(
        sessionId, initData, mimeType, keyType, optionalParams);

    // ─── Bước 5: Tracking — lưu hash của request ───
    byte[] requestData = keyRequest.getData();
    trackRequest(requestData);

    // ─── Return KeyRequest GỐC — không sửa đổi ───
    return keyRequest;
}


// =====================================================================
// 11. TrackRequest — Lưu history request (FIFO, max 50)
//     Native: FUN_001795b4
//     Gọi từ: getKeyRequest
// =====================================================================

void trackRequest(byte[] requestData) {
    // Tính hash từ request data
    int hash = computeHash(0, requestData);

    // Thêm vào list
    RequestHistory.hashes.add(hash);

    // Giữ max 50 entries (200 bytes / 4 bytes mỗi hash)
    // Nếu vượt quá → xóa entry đầu tiên (FIFO)
    while (RequestHistory.hashes.size() * 4 > 200) {
        RequestHistory.hashes.remove(0);
    }
}


// =====================================================================
// 12. ComputeHash — Tính hash của byte array
//     Native: FUN_001787d8
//     Gọi từ: TrackRequest
// =====================================================================

int computeHash(int seed, byte[] data) {
    // Có thể là CRC32, MurmurHash, hoặc custom hash
    int hash = seed;
    for (byte b : data) {
        hash = hash ^ (b & 0xFF);
        // ... bitwise operations ...
    }
    return hash;
}


// =====================================================================
// 13. provideKeyResponse — HÀM CHÍNH #3
//     Native: Java_com_sigma_packer_SigmaDrmPacker_provideKeyResponse
//     Gọi từ: SigmaMediaDrm.provideKeyResponse()
//
//     Input:  MediaDrm, sessionId, response (từ Sigma server, format "SMWV")
//     Output: byte[] keySetId (cho offline DRM)
// =====================================================================

public static byte[] provideKeyResponse(
        MediaDrm mediaDrm,
        byte[] sessionId,
        byte[] response) {

    // ─── Bước 1: Parse response vào native buffer ───
    ByteBuffer responseBuffer = new ByteBuffer(response);

    // ─── Bước 2: Giải mã response format "SMWV" ───
    // Server Sigma KHÔNG trả raw Widevine license
    // Mà wrap trong format custom "SMWV" + encrypt
    byte[] decryptedLicense = parseAndDecryptResponse(responseBuffer);

    // ─── Bước 3: Decrypt tên class/method (obfuscated) ───
    // "YDt8oX564bau5g..." → "android/media/MediaDrm"
    // "MDRRh09H..."       → "provideKeyResponse"
    // "k6UdVX1x7J8..."    → "([B[B)[B"
    String className = decryptString(OBFUSCATED_MEDIADRM_CLASS);
    String methodName = decryptString(OBFUSCATED_PROVIDEKEYRESPONSE);
    String methodSig = decryptString(OBFUSCATED_PROVIDE_SIG);

    // ─── Bước 4: Gọi MediaDrm.provideKeyResponse() THẬT ───
    // Với response ĐÃ GIẢI MÃ (không phải response gốc từ server)
    byte[] keySetId = mediaDrm.provideKeyResponse(sessionId, decryptedLicense);

    return keySetId;
}


// =====================================================================
// 14. ParseAndDecryptResponse — Parse format "SMWV" + decrypt
//     Native: FUN_00178f60
//     Gọi từ: provideKeyResponse
//
//     Đây là hàm QUAN TRỌNG NHẤT — giải mã response từ Sigma server
// =====================================================================

byte[] parseAndDecryptResponse(ByteBuffer input) {

    // ─── Bước 1: Đọc magic number ───
    int magic = input.readInt32();

    if (magic != 0x534D5756) {  // "SMWV" = Sigma Media WideVine
        // Không phải format Sigma → trả về nguyên bản
        // (có thể là raw Widevine response)
        input.reset();
        return input.getAllBytes();
    }

    // ─── Bước 2: Đọc header ───
    int totalSize = input.readInt32();

    // Validate size
    long dataSize = input.remainingSize();
    if (dataSize + 8 != totalSize) {
        return input.getAllBytes();  // size mismatch → trả nguyên bản
    }

    int payloadSize = input.readInt32();
    if (payloadSize >= totalSize) {
        return input.getAllBytes();  // invalid → trả nguyên bản
    }

    // ─── Bước 3: Đọc encryption info ───
    byte encryptionType = input.readByte();   // 1 = XOR, 2 = AES
    byte unknown        = input.readByte();   // chưa rõ mục đích
    byte numExtraFields = input.readByte();   // số lượng extra fields

    // ─── Bước 4: Đọc extra fields (dùng làm key material) ───
    byte[] extraFields = new byte[numExtraFields];
    for (int i = 0; i < numExtraFields; i++) {
        extraFields[i] = input.readByte();
    }

    // ─── Bước 5: Đọc IV (16 bytes, cho AES) ───
    byte[] iv = new byte[16];
    input.readBytes(iv, 16);

    // ─── Bước 6: Đọc encrypted payload ───
    int encPayloadSize = input.readInt32();
    byte[] encryptedPayload = new byte[encPayloadSize];
    input.readBytes(encryptedPayload, encPayloadSize);

    // ─── Bước 7: Tạo key từ extra fields ───
    byte[] decryptionKey = buildKey(extraFields);

    // ─── Bước 8: Decrypt theo type ───
    byte[] decryptedLicense;

    if (encryptionType == 2) {
        // ═══ AES-128-CBC Decrypt ═══
        AESContext ctx = new AESContext();        // FUN_00174b40
        ctx.setKey(decryptionKey);                // FUN_00174b48

        byte[] plaintext = new byte[dataSize];
        ctx.cbcDecrypt(                           // FUN_00174fd0
            plaintext,           // output
            encryptedPayload,    // encrypted data
            decryptionKey,       // key
            iv                   // IV (16 bytes)
        );

        // PKCS7 unpadding
        byte paddingLen = plaintext[dataSize - 1];
        if (paddingLen > 16) {
            // Padding không hợp lệ → lỗi
            return null;
        }
        int actualSize = (int)(dataSize - paddingLen);
        decryptedLicense = Arrays.copyOf(plaintext, actualSize);

        ctx.free();  // FUN_00174b44

    } else if (encryptionType == 1) {
        // ═══ XOR Decrypt ═══
        decryptedLicense = xorDecrypt(            // FUN_0016cbcc
            encryptedPayload,
            decryptionKey
        );

    } else {
        // Unknown encryption type
        return null;
    }

    // decryptedLicense = raw Widevine license (cái mà MediaDrm cần)
    return decryptedLicense;
}


// =====================================================================
// 15. XOR Decrypt — Giải mã XOR đơn giản
//     Native: FUN_0016cbcc
//     Gọi từ: ParseAndDecryptResponse (khi encryptionType == 1)
// =====================================================================

byte[] xorDecrypt(byte[] encryptedData, byte[] key) {
    byte[] result = new byte[encryptedData.length];
    for (int i = 0; i < encryptedData.length; i++) {
        result[i] = (byte)(encryptedData[i] ^ key[i % key.length]);
    }
    return result;
}


// =====================================================================
// 16. ByteBuffer — Utility class cho đọc binary data
//     Native: size = 0x40 bytes
// =====================================================================

class ByteBuffer {
    byte[] buffer;      // offset +0x18 (malloc'd)
    byte[] readPtr;     // offset +0x20 (current read position)
    long capacity;      // offset +0x28
    long size;          // offset +0x30
    int position;       // offset +0x30
    boolean flag;       // offset +0x38

    // FUN_0016cd00: Constructor
    ByteBuffer(byte[] data) {
        this.capacity = data.length;
        this.position = 0;
        this.buffer = new byte[data.length];
        System.arraycopy(data, 0, this.buffer, 0, data.length);
        this.readPtr = this.buffer;
    }

    // FUN_0016d578: Get data pointer
    byte[] getData() {
        return this.readPtr;  // offset +0x20
    }

    // FUN_0016d580: Get size
    long getSize() {
        return this.size;  // offset +0x30
    }

    // FUN_0016d534: Read 4 bytes as int
    int readInt32() {
        int value = (buffer[position] << 24) | (buffer[position+1] << 16)
                  | (buffer[position+2] << 8) | buffer[position+3];
        position += 4;
        return value;
    }

    // FUN_0016d558: Read 1 byte
    byte readByte() {
        return buffer[position++];
    }

    // FUN_0016d4dc: Read N bytes
    void readBytes(byte[] dest, int length) {
        System.arraycopy(buffer, position, dest, 0, length);
        position += length;
    }

    // FUN_0016d588: Reset position
    void reset() {
        this.position = 0;
        this.readPtr = this.buffer;
    }
}


// =====================================================================
// 17. InitString — C++ SSO (Small String Optimization)
//     Native: FUN_0016f320
//     Dùng ở: nhiều nơi để tạo std::string
// =====================================================================

// Trong C++, std::string có 2 mode:
//   - Short string (< 23 bytes): lưu trực tiếp trong struct (inline)
//   - Long string (>= 23 bytes): malloc trên heap
//
// Pseudocode:
String initString(char[] cstr) {
    if (cstr.length < 23) {
        // Inline: lưu trực tiếp, không cần malloc
        return new String(cstr);  // fast path
    } else {
        // Heap: malloc rồi copy
        char[] heap = new char[alignedSize(cstr.length)];
        System.arraycopy(cstr, 0, heap, 0, cstr.length);
        return new String(heap);
    }
}


// =====================================================================
// SMWV Response Format — Binary layout
// =====================================================================
//
// Khi Sigma server trả response, nó có format như sau:
//
// Offset  Size    Field                 Ví dụ
// ──────  ──────  ────────────────────  ─────────────────
// 0x00    4       Magic "SMWV"          0x534D5756
// 0x04    4       Total size            0x00000500
// 0x08    4       Payload size          0x000004E0
// 0x0C    1       Encryption type       0x02 (AES)
// 0x0D    1       Unknown               0x00
// 0x0E    1       Num extra fields      0x10 (16 bytes key)
// 0x0F    N       Extra fields (key)    [16 bytes AES key material]
// 0x0F+N  16      IV                    [16 bytes AES IV]
// ...     4       Encrypted size        0x000004C0
// ...     N       Encrypted payload     [actual encrypted Widevine license]
//
// Sau khi decrypt payload → raw Widevine license bytes
// Feed vào MediaDrm.provideKeyResponse() → device có key decrypt video


// =====================================================================
// TOÀN BỘ FLOW TÓM TẮT
// =====================================================================
//
// ┌─ App khởi động ──────────────────────────────────────────────────┐
// │  System.loadLibrary("drmpacker")                                 │
// │  → JNI_OnLoad()                                                  │
// │    → GlobalState.getInstance()     // tạo singleton              │
// │    → initDeviceInfo()              // thu thập device info       │
// │      → collectBuildInfo()          // model, fingerprint, sdk... │
// │      → collectDeviceId()           // android_id, app version   │
// └──────────────────────────────────────────────────────────────────┘
//
// ┌─ Khi cần key DRM ───────────────────────────────────────────────┐
// │                                                                  │
// │  1. SigmaMediaDrm.getKeyRequest()                                │
// │     → SigmaDrmPacker.getKeyRequest()  [NATIVE]                   │
// │       → decryptString("YDt8oX...")   → "android/media/MediaDrm"  │
// │       → decryptString("5gR_XB...")   → "getKeyRequest"           │
// │       → mediaDrm.getKeyRequest()     → Widevine challenge        │
// │       → trackRequest(challenge)      → lưu hash (max 50)        │
// │       → return KeyRequest (KHÔNG sửa)                            │
// │                                                                  │
// │  2. HttpMediaDrmCallback.executeKeyRequest()                     │
// │     → SigmaDrmPacker.requestInfo()  [NATIVE]                     │
// │       → GlobalState.getInstance()    → lấy device info           │
// │       → UUID.randomUUID()            → tạo requestId             │
// │       → AES.encrypt(deviceInfo)      → mã hóa device info       │
// │       → return RequestInfo { requestId, encryptedDeviceInfo }    │
// │     → HTTP POST:                                                 │
// │         body = Widevine challenge                                │
// │         header["custom-data"] = encryptedDeviceInfo              │
// │                                                                  │
// │  3. Server Sigma xử lý:                                          │
// │     → Decrypt device info → validate device                      │
// │     → Check entitlement → user có quyền xem?                    │
// │     → Forward challenge → Google Widevine CDM                    │
// │     → Nhận Widevine license ← Google                             │
// │     → Wrap thành format "SMWV" + encrypt                        │
// │     → Trả về client                                              │
// │                                                                  │
// │  4. SigmaMediaDrm.provideKeyResponse()                           │
// │     → SigmaDrmPacker.provideKeyResponse()  [NATIVE]              │
// │       → parseAndDecryptResponse()                                │
// │         → Đọc magic "SMWV"                                       │
// │         → Đọc header (size, encryption type, key, IV)            │
// │         → if type==2: AES-128-CBC decrypt                        │
// │           if type==1: XOR decrypt                                │
// │         → Kết quả: raw Widevine license                          │
// │       → decryptString("MDRRh0...")  → "provideKeyResponse"       │
// │       → mediaDrm.provideKeyResponse(decryptedLicense)            │
// │       → return keySetId (cho offline DRM)                        │
// │                                                                  │
// │  5. Video bắt đầu decrypt + phát ✓                              │
// └──────────────────────────────────────────────────────────────────┘
