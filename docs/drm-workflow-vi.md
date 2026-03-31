# DRM Workflow trong Media3 ExoPlayer — Chi tiết đầy đủ

## Tổng quan: 8 Phase của DRM

```
Phase 1: Phát hiện DRM        ← Parse manifest, tìm PSSH/ContentProtection
Phase 2: Tạo DrmSessionManager ← Khởi tạo từ MediaItem.DrmConfiguration
Phase 3: DrmSession lifecycle   ← Mở session, reference counting
Phase 4: Key Request flow       ← Tạo challenge → gửi server → nhận license
Phase 5: Provisioning           ← Cấp device certificate (lần đầu)
Phase 6: Tích hợp playback      ← MediaCodec chờ key → decrypt → phát
Phase 7: State transitions      ← OPENING → OPENED → OPENED_WITH_KEYS → RELEASED
Phase 8: Offline DRM             ← Download license → phát offline
```

---

## Phase 1: Phát hiện DRM (DRM Detection)

### Khi nào ExoPlayer biết content có DRM?

```
Manifest (DASH/HLS/SmoothStreaming)
  ↓ Parse
  ↓
DASH: <ContentProtection> element
  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed">
    <cenc:pssh>AAAAxnBzc2gAAAAA7e...</cenc:pssh>   ← PSSH box (Widevine)
  </ContentProtection>

HLS: #EXT-X-KEY tag
  #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://..."

  ↓
Tạo DrmInitData object:
  DrmInitData {
    schemeType: "cenc" / "cbcs"
    schemeDatas: [
      SchemeData {
        uuid: WIDEVINE_UUID (edef8ba9-...)
        mimeType: "video/mp4"
        data: byte[] (PSSH box binary)
        licenseServerUrl: "https://..."  (optional)
      }
    ]
  }
  ↓
Gắn vào Format.drmInitData
  ↓
ExoPlayer biết: "content này cần DRM!"
```

### DrmInitData đi đâu?

```
Manifest Parser
  → DrmInitData gắn vào Format
    → Format đi vào SampleQueue
      → SampleQueue pre-acquire DrmSession
        → MediaCodecRenderer kiểm tra → cần DRM session
```

---

## Phase 2: Tạo DrmSessionManager

### Flow: MediaItem → DrmSessionManager

```
App tạo MediaItem:
  MediaItem.Builder()
    .setUri("https://example.com/video.mpd")
    .setDrmConfiguration(
      DrmConfiguration.Builder(C.WIDEVINE_UUID)
        .setLicenseUri("https://license-server.com/license")
        .setLicenseRequestHeaders({"token": "abc123"})
        .setIsSigmaDrm(true)              ← Custom: bật Sigma DRM
        .build()
    ).build()

  ↓ player.setMediaItem(mediaItem)
  ↓ player.prepare()

DefaultDrmSessionManagerProvider.get(mediaItem):
  ↓
  1. Lấy DrmConfiguration từ mediaItem
  ↓
  2. Tạo HttpMediaDrmCallback:
     - licenseUrl = "https://license-server.com/license"
     - headers = {"token": "abc123"}
  ↓
  3. Chọn ExoMediaDrm Provider:
     if (isSigmaDrm):
       provider = SigmaMediaDrm.DEFAULT_PROVIDER    ← Sigma wrap
     else:
       provider = FrameworkMediaDrm.DEFAULT_PROVIDER ← Android gốc
  ↓
  4. Build DefaultDrmSessionManager:
     DefaultDrmSessionManager.Builder(uuid)
       .setUuidAndExoMediaDrmProvider(uuid, provider)
       .setMultiSession(false)                 ← default: 1 session cho tất cả
       .setSessionKeepaliveMs(5 * 60 * 1000)   ← giữ session 5 phút
       .build(httpDrmCallback)
  ↓
  Return: DefaultDrmSessionManager (cached, reuse nếu cùng config)
```

### FrameworkMediaDrm vs SigmaMediaDrm

```
FrameworkMediaDrm (Standard):
  → Wrap android.media.MediaDrm trực tiếp
  → getKeyRequest()    = MediaDrm.getKeyRequest()     (gốc)
  → provideKeyResponse() = MediaDrm.provideKeyResponse() (gốc)

SigmaMediaDrm (Custom):
  → Wrap android.media.MediaDrm + thêm native layer
  → getKeyRequest()    = SigmaDrmPacker.getKeyRequest()     (native C++)
  → provideKeyResponse() = SigmaDrmPacker.provideKeyResponse() (native C++)
  → Còn lại 100% pass-through giống FrameworkMediaDrm
```

### SigmaMediaDrm — Chi tiết pass-through

```
SigmaMediaDrm chỉ đổi 2 method, còn lại gọi thẳng mediaDrm gốc:

Method                       SigmaMediaDrm              FrameworkMediaDrm
──────────────────────       ──────────────             ─────────────────
openSession()                mediaDrm.openSession()      ← GIỐNG
closeSession()               mediaDrm.closeSession()     ← GIỐNG
restoreKeys()                mediaDrm.restoreKeys()      ← GIỐNG
getProvisionRequest()        mediaDrm.getProvisionRequest() ← GIỐNG
provideProvisionResponse()   mediaDrm.provideProvisionResponse() ← GIỐNG
queryKeyStatus()             mediaDrm.queryKeyStatus()   ← GIỐNG
getPropertyString()          mediaDrm.getPropertyString() ← GIỐNG
setPropertyString()          mediaDrm.setPropertyString() ← GIỐNG
getPropertyByteArray()       mediaDrm.getPropertyByteArray() ← GIỐNG
setPropertyByteArray()       mediaDrm.setPropertyByteArray() ← GIỐNG
acquire()/release()          referenceCount++/--          ← GIỐNG
─────────────────────────────────────────────────────────────────
getKeyRequest()              SigmaDrmPacker.getKeyRequest()    ← KHÁC (native)
provideKeyResponse()         SigmaDrmPacker.provideKeyResponse() ← KHÁC (native)
```

---

## Phase 3: DrmSession Lifecycle

### Session Acquisition — 2 đường đi

```
Đường 1: Pre-acquire (async, trên loading thread):
  SampleQueue.onUpstreamFormatChanged()
    → drmSessionManager.preacquireSession()
    → Return DrmSessionReference (lightweight)
    → Session bắt đầu load keys NGẦM

Đường 2: Full acquire (sync, trên playback thread):
  MediaCodecRenderer.updateDrmSession()
    → drmSessionManager.acquireSession()
    → Return DrmSession (phải ở STATE_OPENED_WITH_KEYS mới decode được)
```

### Session Reuse Logic

```
multiSession = false (default):
  → Dùng 1 session cho TẤT CẢ tracks
  → noMultiSessionDrmSession = shared session
  → Phù hợp hầu hết trường hợp

multiSession = true:
  → Mỗi track/format có thể có session riêng
  → Tìm session matching schemeDatas
  → Cần khi: key rotation, multiple keys per content
```

### Reference Counting

```
acquire(dispatcher)   → referenceCount++
release(dispatcher)   → referenceCount--

Khi referenceCount == 0:
  → Close session: mediaDrm.closeSession(sessionId)
  → Clear cryptoConfig
  → State → STATE_RELEASED

Session Keep-Alive (default 5 phút):
  → Khi upper layer release, manager giữ thêm 5 phút
  → Tránh re-open session liên tục (ad breaks, format switch)
  → Sau 5 phút không ai dùng → tự release
```

---

## Phase 4: Key Request Flow (QUAN TRỌNG NHẤT)

### Timeline đầy đủ

```
DefaultDrmSession                    Background Thread              License Server
────────────────                    ─────────────────              ──────────────

acquire() called
  ↓
openInternal()
  mediaDrm.openSession()
  → sessionId = byte[]
  → cryptoConfig = mediaDrm.createCryptoConfig(sessionId)
  → state = STATE_OPENED
  ↓
doLicense()
  ↓ (MODE_PLAYBACK)
postKeyRequest(sessionId, KEY_TYPE_STREAMING)
  ↓
mediaDrm.getKeyRequest(
  sessionId,
  schemeDatas,         ← PSSH boxes từ manifest
  KEY_TYPE_STREAMING,
  keyRequestParams
)
  ↓
KeyRequest {
  data: byte[]         ← Widevine challenge (CENC EME)
  licenseServerUrl     ← từ PSSH hoặc MediaItem
  requestType          ← INITIAL / RENEWAL
}
  ↓
  ├──────────────────── MSG_KEYS ────►
  │                                    callback.executeKeyRequest(uuid, keyRequest)
  │                                      ↓
  │                                    HttpMediaDrmCallback:
  │                                      1. URL = licenseServerUrl
  │                                      2. Headers = Content-Type + custom
  │                                      3. [Sigma] SigmaDrmPacker.requestInfo()
  │                                         → inject reqId + deviceInfo
  │                                      4. HTTP POST ────────────────────────►
  │                                                                            License Server
  │                                                                            validates:
  │                                                                            - challenge
  │                                                                            - device info
  │                                                                            - entitlements
  │                                      5. Response ◄────────────────────────
  │                                         [Standard] raw Widevine license
  │                                         [Sigma] JSON{"license":"Base64..."}
  │                                      6. [Sigma] parse JSON, Base64 decode
  │                                      7. Return Response(licenseBytes)
  │                                    ◄──────────────────────
  ↓
onKeyResponse(licenseBytes)
  ↓
mediaDrm.provideKeyResponse(sessionId, licenseBytes)
  [Standard] MediaDrm xử lý trực tiếp
  [Sigma]    SigmaDrmPacker.provideKeyResponse():
             → Parse "SMWV" header
             → AES decrypt hoặc XOR decrypt
             → Feed decrypted bytes vào MediaDrm thật
  ↓
Return keySetId (for offline)
  ↓
state = STATE_OPENED_WITH_KEYS ✅
  ↓
Dispatch event: drmKeysLoaded
  ↓
MediaCodecRenderer: "OK, keys ready, tôi có thể decrypt rồi!"
```

### Sigma DRM flow chi tiết tại Phase 4

```
                    Standard Widevine              Sigma DRM
                    ─────────────────              ─────────
getKeyRequest:      MediaDrm gốc                   SigmaDrmPacker.getKeyRequest()
                                                    → KHÔNG modify challenge
                                                    → chỉ track request hash (FIFO max 50)
                                                    → return KeyRequest GỐC

requestInfo:        Không có                        SigmaDrmPacker.requestInfo()
                                                    → collect device info (model, fingerprint,
                                                      androidId, appVersion...)
                                                    → AES-128 encrypt với hardcoded key
                                                    → inject vào header "custom-data"

HTTP Request:       POST challenge                  POST challenge + custom-data header
                    Content-Type: octet-stream       Content-Type: octet-stream

Response:           Raw Widevine license bytes      JSON { "license": "Base64..." }
                                                    → Parse JSON, Base64 decode

provideKeyResponse: MediaDrm gốc                   SigmaDrmPacker.provideKeyResponse()
                                                    → Parse "SMWV" format (magic: 0x534D5756)
                                                    → type 2: AES-128-CBC decrypt
                                                    → type 1: XOR decrypt
                                                    → Feed decrypted license vào MediaDrm gốc
```

### 3 Native Functions — Chi tiết

```
┌─────────────────────────────────────────────────────────────────────────┐
│ SigmaDrmPacker (com.sigma.packer) — 3 native JNI methods              │
│ Implement trong: libdrmpacker.so (C++, ~2MB, chứa AES implementation) │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ 1. getKeyRequest(MediaDrm, sessionId, initData, mimeType, keyType, params)
│    ├── Decrypt obfuscated strings (AES):                                │
│    │   "YDt8oX564bau5g..." → "android/media/MediaDrm"                  │
│    │   "5gR_XBdIQ_PynVaZ"  → "getKeyRequest"                           │
│    │   Tại sao? Chống `strings libdrmpacker.so` thấy tên API            │
│    ├── Gọi: mediaDrm.getKeyRequest() qua JNI reflection                │
│    ├── Track: lưu hash request (FIFO, max 50 entries)                  │
│    └── Return: KeyRequest GỐC, KHÔNG modify                            │
│                                                                         │
│ 2. requestInfo(byte[] challenge)                                        │
│    ├── GlobalState.getInstance() → lấy device info đã collect           │
│    │   (model, fingerprint, androidId, sdkVersion, appVersion...)       │
│    ├── Generate: UUID.randomUUID() → requestId                         │
│    ├── AES-128 encrypt: deviceInfo + challenge data                    │
│    │   Key: hardcoded 16 bytes tại DAT_0020aaa8                        │
│    ├── Base64 encode encrypted data                                    │
│    └── Return: RequestInfo { requestId, encryptedDeviceInfo }          │
│                                                                         │
│ 3. provideKeyResponse(MediaDrm, sessionId, response)                   │
│    ├── parseAndDecryptResponse():                                      │
│    │   ├── Check magic "SMWV" (0x534D5756 = Sigma Media WideVine)     │
│    │   │   Không có "SMWV" → trả nguyên (raw Widevine response)       │
│    │   ├── Parse header: totalSize, payloadSize                        │
│    │   ├── Read: encryptionType (1=XOR, 2=AES)                        │
│    │   ├── Read: key material (N bytes, server gửi kèm)               │
│    │   ├── Read: IV (16 bytes, cho AES-CBC)                            │
│    │   ├── Read: encrypted payload                                     │
│    │   └── Decrypt:                                                    │
│    │       type 2 → AES-128-CBC + PKCS7 unpadding                     │
│    │       type 1 → XOR (key[i % key.length])                         │
│    ├── Gọi: mediaDrm.provideKeyResponse(decryptedLicense) qua JNI     │
│    └── Return: keySetId (cho offline DRM)                              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### SMWV Response Format

```
Sigma server KHÔNG trả raw Widevine license.
Nó wrap trong format custom "SMWV" + encrypt:

Offset  Size    Field                 Ví dụ
──────  ──────  ────────────────────  ─────────────────
0x00    4       Magic "SMWV"          0x534D5756
0x04    4       Total size            0x00000500 (1280 bytes)
0x08    4       Payload size          0x000004E0
0x0C    1       Encryption type       0x02 (AES) hoặc 0x01 (XOR)
0x0D    1       Unknown               0x00
0x0E    1       Num extra fields (N)  0x10 (16 bytes key material)
0x0F    N       Key material          [16 bytes — dùng để decrypt]
0x0F+N  16      IV                    [16 bytes — cho AES-CBC]
...     4       Encrypted size        0x000004C0
...     M       Encrypted payload     [actual Widevine license, encrypted]

Sau decrypt payload → raw Widevine license → feed cho MediaDrm
```

### Obfuscation trong libdrmpacker.so

```
Sigma dùng C++ native (thay vì Java) để giấu logic:

1. String Encryption (AES-128):
   - Tất cả tên class/method Android API đều bị AES encrypt
   - Lưu dạng: "YDt8oX564bau5g..." trong binary
   - Runtime: decrypt → dùng → xóa khỏi memory
   - Mục đích: `strings libdrmpacker.so` không thấy gì

2. JNI Reflection (thay vì gọi trực tiếp):
   - KHÔNG gọi: mediaDrm.getKeyRequest()  (Java direct)
   - MÀ gọi:    env->FindClass("android/media/MediaDrm")
                 env->GetMethodID(cls, "getKeyRequest", sig)
                 env->CallObjectMethod(mediaDrm, mid, ...)
   - Tên class/method là string → mã hóa được

3. Key hardcoded:
   - AES key 16 bytes tại DAT_0020aaa8 trong binary
   - Ghidra vẫn tìm được → obfuscate chỉ làm KHÓ, không CHẶN

4. Tại sao không dùng ProGuard?
   - ProGuard chỉ đổi tên code MÌNH viết
   - KHÔNG đổi được tên Android SDK API
   - "android/media/MediaDrm" = tên cố định, ProGuard bất lực
   - C++ native + AES encrypt = giải pháp duy nhất giấu string
```

### Workflow tổng hợp (dạng đơn giản)

```
1. getKeyRequest()        ← Tạo Widevine challenge (mặc định, không thêm gì)
   │
2. executeKeyRequest()    ← Gửi challenge lên server
   │  ├── requestInfo()   ← THÊM: encrypt device info vào header
   │  ├── POST challenge + header "custom-data" → server
   │  └── Nhận response "SMWV"
   │
3. provideKeyResponse()   ← Parse "SMWV" + decrypt → feed cho MediaDrm
```

---

## Phase 5: Provisioning (Device Certificate)

### Khi nào cần Provisioning?

```
Provisioning = cấp device certificate cho thiết bị LẦN ĐẦU
Xảy ra khi:
  - Thiết bị mới, chưa bao giờ dùng Widevine
  - Factory reset
  - Certificate hết hạn

Trigger: NotProvisionedException khi openSession() hoặc getKeyRequest()
```

### Flow

```
openInternal() hoặc onKeysError()
  → catch NotProvisionedException
  ↓
provisioningManager.provisionRequired(session)
  ↓
mediaDrm.getProvisionRequest()
  → ProvisionRequest { data, defaultUrl }
  ↓
POST to provisioning server (Google server cho Widevine)
  ↓
Response: device certificate bytes
  ↓
mediaDrm.provideProvisionResponse(certData)
  ↓
provisioningManager.onProvisionCompleted()
  ↓
Retry: openInternal() → doLicense() → bình thường
```

---

## Phase 6: Tích hợp với Playback Pipeline

### SampleQueue — Gắn DRM vào samples

```
Loading Thread:
  Demuxer đọc encrypted samples từ source
    ↓
  SampleQueue.onUpstreamFormatChanged(format)
    format.drmInitData != null → "content encrypted!"
    ↓
  drmSessionManager.preacquireSession(format)
    → DrmSessionReference gắn vào SharedSampleMetadata
    → Session bắt đầu load keys ngầm
    ↓
  Mỗi sample written:
    CryptoData {
      cryptoMode: AES_CTR hoặc AES_CBC (từ schemeType)
      encryptionKey: reference to DRM session
      clearBlocks / encryptedBlocks: subsample info
    }
```

### MediaCodecRenderer — Chờ key → Decrypt → Phát

```
Playback Thread:
  MediaCodecRenderer.render()
    ↓
  updateDrmSession():
    sourceDrmSession = từ SampleQueue
    codecDrmSession = session cho codec
    ↓
  initMediaCryptoIfDrmSessionReady():
    ↓
    Kiểm tra: codecDrmSession.getState() == ?

    STATE_OPENING:
      → CHƯA SẴN SÀNG, chờ...
      → Codec KHÔNG khởi tạo
      → Video KHÔNG render
      → User thấy: loading spinner

    STATE_OPENED:
      → Session mở nhưng CHƯA CÓ KEY
      → Vẫn chờ...

    STATE_OPENED_WITH_KEYS: ✅
      → KEYS ĐÃ SẴN SÀNG!
      → Tạo MediaCrypto(cryptoConfig)
      → Khởi tạo secure MediaCodec decoder
      → Bắt đầu decrypt + decode + render
      → User thấy: video phát!

    STATE_ERROR: ❌
      → DRM lỗi (license denied, network error...)
      → Propagate error lên player
      → User thấy: error message

  Decrypt flow (sau khi có keys):
    Encrypted sample + IV
      → Feed vào secure MediaCodec
      → Hardware DRM decrypt (TEE - Trusted Execution Environment)
      → Clear video frames output
      → Render lên Surface
```

### Timeline thực tế — Từ prepare() đến video phát

```
t=0ms:     player.prepare()
           ↓
t=0ms:     DefaultDrmSessionManagerProvider.get() → tạo DrmSessionManager
           ↓
t=50ms:    Source prepare → download manifest
           Parse manifest → phát hiện DRM (DrmInitData)
           ↓
t=100ms:   SampleQueue pre-acquire DRM session
           DefaultDrmSessionManager.preacquireSession()
           ↓
t=100ms:   DefaultDrmSession created, state = OPENING
           mediaDrm.openSession() → sessionId
           state = OPENED
           ↓
t=110ms:   doLicense() → postKeyRequest()
           mediaDrm.getKeyRequest() → challenge bytes
           ↓
t=110ms:   Background thread: executeKeyRequest()
           [Sigma] SigmaDrmPacker.requestInfo() → inject device info
           HTTP POST to license server ─────────────────────►
           ↓                                                 License Server
t=200ms:   ◄─────────────── Response ────────────────────────
           [Sigma] Parse JSON + Base64 decode
           ↓
t=210ms:   onKeyResponse()
           mediaDrm.provideKeyResponse(license)
           [Sigma] Parse "SMWV" + AES decrypt
           ↓
t=220ms:   state = STATE_OPENED_WITH_KEYS ✅
           ↓
t=220ms:   MediaCodecRenderer: keys ready!
           → Tạo MediaCrypto
           → Init secure decoder
           ↓
t=270ms:   Decode first encrypted frame
           ↓
t=300ms:   Video hiển thị!

Tổng: ~300ms (phụ thuộc network latency đến license server)
```

---

## Phase 7: State Transitions

### DRM Session State Machine

```
              ┌────────────────────────────┐
              │     STATE_OPENING          │
              │  (session chưa mở)         │
              └────────────┬───────────────┘
                           │ openInternal() OK
                           ↓
              ┌────────────────────────────┐
              │     STATE_OPENED           │
              │  (session mở, chưa có key) │
              │  MediaCodec: CHƯA init     │
              └────────────┬───────────────┘
                           │ provideKeyResponse() OK
                           ↓
              ┌────────────────────────────┐
              │  STATE_OPENED_WITH_KEYS    │ ← Playback bắt đầu ở đây
              │  (có key, sẵn sàng)        │
              │  MediaCodec: decrypt OK    │
              └────────┬───────┬───────────┘
                       │       │
          release()    │       │ EVENT_KEY_REQUIRED
          (refCount=0) │       │ (key rotation)
                       ↓       ↓
              ┌──────────┐  ┌──────────────┐
              │ RELEASED │  │ Re-doLicense │
              │ (done)   │  │ (refresh key)│
              └──────────┘  └──────────────┘

  Bất kỳ lúc nào:
              ┌────────────────────────────┐
              │      STATE_ERROR           │
              │  - License denied          │
              │  - Network error           │
              │  - Invalid response        │
              │  - Provision failed        │
              └────────────────────────────┘
```

### Key Events

```
EVENT_KEY_REQUIRED:
  → Widevine gửi khi key sắp hết hạn hoặc cần rotation
  → Trigger: doLicense() → request key mới
  → Không ảnh hưởng playback nếu refresh kịp

EVENT_KEY_EXPIRED:
  → Key đã hết hạn
  → Playback dừng cho đến khi có key mới

DRM_SESSION_ACQUIRED:
  → Session được acquire bởi renderer/SampleQueue
  → Analytics/logging

DRM_KEYS_LOADED:
  → Keys loaded thành công
  → STATE_OPENED_WITH_KEYS

DRM_SESSION_RELEASED:
  → Session released, resources freed
```

---

## Phase 8: Offline DRM

### Download License

```
// App code:
drmSessionManager.setMode(MODE_DOWNLOAD, null)
drmSessionManager.prepare()
session = drmSessionManager.acquireSession(format)

// Chờ STATE_OPENED_WITH_KEYS
// → keySetId = session.queryKeyStatus() hoặc từ provideKeyResponse()

// Lưu keySetId vào local storage
sharedPrefs.putByteArray("drm_key_video_123", keySetId)
```

### Offline Playback

```
// App code:
byte[] keySetId = sharedPrefs.getByteArray("drm_key_video_123")
MediaItem.Builder()
  .setDrmConfiguration(
    DrmConfiguration.Builder(C.WIDEVINE_UUID)
      .setKeySetId(keySetId)        ← Dùng license đã download
      .build()
  ).build()

// Bên trong:
drmSessionManager.setMode(MODE_PLAYBACK, keySetId)
  ↓
doLicense():
  mediaDrm.restoreKeys(sessionId, keySetId)  ← Khôi phục license từ storage
  ↓
  Kiểm tra expiration:
    - Chưa hết hạn → dùng luôn (KHÔNG cần network!)
    - Sắp hết hạn → renew (cần network)
    - Đã hết hạn → error hoặc re-download
  ↓
state = STATE_OPENED_WITH_KEYS ✅
→ Phát video OFFLINE, không cần internet
```

### Release Offline License

```
drmSessionManager.setMode(MODE_RELEASE, keySetId)
  ↓
doLicense():
  postKeyRequest(keySetId, KEY_TYPE_RELEASE)
  → Gửi release request lên server
  → Server xóa license record
  → Xóa keySetId local
```

---

## Tổng hợp: Flow hoàn chỉnh (Standard vs Sigma)

### Standard Widevine

```
App                    ExoPlayer              Android MediaDrm        License Server
───                    ─────────              ────────────────        ──────────────
setMediaItem()
prepare()
                       Parse manifest
                       Detect DRM (PSSH)
                       ↓
                       DrmSessionManager.acquireSession()
                       ↓
                       MediaDrm.openSession()
                       → sessionId
                       ↓
                       MediaDrm.getKeyRequest()
                       → challenge bytes
                       ↓
                       HTTP POST challenge ──────────────────────────►
                                                                      Validate
                       ◄─────────────────── raw license bytes ────────
                       ↓
                       MediaDrm.provideKeyResponse(license)
                       → keys loaded
                       ↓
                       STATE_OPENED_WITH_KEYS
                       ↓
                       Init secure decoder
                       Decrypt + play
```

### Sigma DRM (thêm 2 layers: encrypt device info + decrypt SMWV response)

```
App                    ExoPlayer              SigmaMediaDrm           Sigma Server
───                    ─────────              ─────────────           ────────────
setMediaItem()
  isSigmaDrm=true
prepare()
                       Parse manifest
                       Detect DRM (PSSH)
                       ↓
                       DrmSessionManager.acquireSession()
                       ↓
                       SigmaMediaDrm.openSession()
                       → mediaDrm.openSession() (pass-through)
                       → sessionId
                       ↓
                       SigmaMediaDrm.getKeyRequest()
                       → SigmaDrmPacker.getKeyRequest() [NATIVE]
                         → decrypt obfuscated strings (AES)
                         → mediaDrm.getKeyRequest() (Widevine mặc định)
                         → trackRequest() (lưu hash)
                       → challenge bytes (KHÔNG modify)
                       ↓
                       HttpMediaDrmCallback.executeKeyRequest():
                         SigmaDrmPacker.requestInfo(challenge) [NATIVE]
                         → collect device info (model, androidId...)
                         → AES encrypt device info
                         → return RequestInfo {requestId, deviceInfo}
                         → inject vào header "custom-data"
                       ↓
                       HTTP POST challenge + custom-data ────────────►
                                                                      Validate:
                                                                      - Widevine challenge
                                                                      - device info
                                                                      - entitlements
                       ◄──── JSON {"license":"Base64(SMWV...)"} ─────
                       ↓
                       Parse JSON, Base64 decode
                       ↓
                       SigmaMediaDrm.provideKeyResponse()
                       → SigmaDrmPacker.provideKeyResponse() [NATIVE]
                         → parseAndDecryptResponse():
                           check "SMWV" magic → parse header
                           → type 2: AES-128-CBC decrypt
                           → type 1: XOR decrypt
                         → mediaDrm.provideKeyResponse(decryptedLicense)
                       → keys loaded
                       ↓
                       STATE_OPENED_WITH_KEYS
                       ↓
                       Init secure decoder
                       Decrypt + play
```

---

## Chi phí DRM trong playback pipeline

```
DRM thêm bao nhiêu thời gian?

                        Không DRM    Có DRM (Standard)   Có DRM (Sigma)
                        ─────────    ─────────────────    ──────────────
Parse manifest:         ~50ms        ~50ms                ~50ms
DRM session open:       0ms          ~20-50ms             ~20-50ms
Key request (network):  0ms          ~100-300ms           ~100-300ms
                                     (1 round-trip)        (1 round-trip)
provideKeyResponse:     0ms          ~10ms                ~20ms (decrypt SMWV)
Init decoder:           ~50ms        ~50ms (secure)       ~50ms (secure)
────────────────────    ─────        ─────────            ─────────
Tổng thêm do DRM:      0ms          ~130-360ms           ~140-370ms

→ DRM thêm khoảng 130-370ms vào thời gian startup
→ Chủ yếu là network latency đến license server
→ Sau khi có key, decrypt trong hardware (TEE) → gần như 0ms overhead
```
