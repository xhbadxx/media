# FPlay DRM Full Workflow — Tu HTTP den Video phat

## Tong quan

```
HTTP JSON → Base64 decode → SMWV bytes
  → ByteBuffer doc tuan tu (magic, metadata, keyMaterial, IV, innerData, encrypted)
  → deriveKey (tra bang property → noi chuoi + separator → MD5)
  → AES-CBC decrypt + PKCS7 unpad
  → raw Widevine license
  → MediaDrm.provideKeyResponse()
  → video phat
```

---

## Khai niem co ban: Offset va Size

Hay tuong tuong `licenseBytes` la mot day o lien tiep, moi o chua 1 byte, danh so tu 0:

```
O so:  0    1    2    3    4    5    6    7    8  ...
      +----+----+----+----+----+----+----+----+----+
      | 53 | 4D | 57 | 56 | 00 | 00 | 04 | 30 | 00 |...
      +----+----+----+----+----+----+----+----+----+
        S    M    W    V
```

- **Offset** = o bat dau (vi tri bat dau doc)
- **Size** = doc bao nhieu o (so byte can doc)

Vi du: doc chu `"SMWV"` → offset=0, size=4 (doc o 0, 1, 2, 3)
Vi du: doc `totalSize` → offset=4, size=4 (doc o 4, 5, 6, 7)

**Quy luat:** field sau bat dau o = offset truoc + size truoc.

### Size co dinh vs dong

**Co dinh (hardcode trong code):**

| Field          | Size | Ghi chu           |
|----------------|------|-------------------|
| magic          | 4/5  | SMWV=4, FPTWV=5   |
| totalSize      | 4    | luon la int32     |
| metadataSize   | 4    | luon la int32     |
| encryptionType | 1    | luon 1 byte       |
| fieldB         | 1    | luon 1 byte       |
| numKeyBytes    | 1    | luon 1 byte       |
| IV             | 16   | luon 16 bytes     |
| innerSize      | 4    | luon la int32     |

**Dong (doc tu data, moi response khac nhau):**

| Field            | Size                          | Doc tu dau             |
|------------------|-------------------------------|------------------------|
| keyMaterial      | numKeyBytes (N)               | doc tu o 14            |
| innerData        | innerSize (M)                 | doc tu o 31+N den 34+N |
| encryptedLicense | totalSize - headerSize - 4 - metadataSize | phan con lai  |

Code dung `ByteBuffer` doc tuan tu — bien `position_` tu nhay sau moi lan doc, khong ai tinh offset tay ca.

---

## Phase 1: Gui request len server

**File:** `HttpMediaDrmCallback.java:174-199`

```java
// 1. Lay sigma-custom-data tu keyRequestProperties (chua merchantId, appId, userId...)
originalData = new JSONObject(keyRequestProperties.get("sigma-custom-data"));

// 2. Goi native C++ tao requestId + deviceInfo
RequestInfo fplayInfo = FPlayDrmPacker.requestInfo(request.getData());
//    | JNI vao C++

// 3. Nhet reqId + deviceInfo vao JSON
originalData.put("reqId", fplayInfo.requestId);       // UUID kieu "a1b2c3d4-..."
originalData.put("deviceInfo", fplayInfo.deviceInfo);  // JSON string chua 19 device properties

// 4. Base64 encode -> header "custom-data"
customDataB64 = Base64.encodeToString(originalData.toString().getBytes(), Base64.NO_WRAP);
requestProperties.put("custom-data", customDataB64);

// 5. POST len server: body = Widevine challenge, header = custom-data
response = executePost(url, request.getData(), requestProperties);
```

---

## Phase 2: Nhan response, parse JSON

**File:** `HttpMediaDrmCallback.java:203-208`

```java
// 6. Server tra ve JSON: {"license": "U01XVg..."}
JSONObject jsonObject = new JSONObject(new String(response.data));
String licenseEncrypted = jsonObject.getString("license");

// 7. Base64 decode -> raw SMWV bytes (vi du 1080 bytes)
byte[] licenseBytes = Base64.decode(licenseEncrypted, Base64.DEFAULT);

// 8. Tra ve cho DRM framework
return withNewData(response, licenseBytes);
```

---

## Phase 3: Decrypt SMWV

### 3.1 Java goi vao C++

**File:** `FPlayMediaDrm.java:225`

```java
// 9. DRM framework goi provideKeyResponse voi licenseBytes
return FPlayDrmPacker.provideKeyResponse(mediaDrm, scope, response);
//     | JNI vao C++
```

### 3.2 C++ nhan bytes, goi parser

**File:** `fplay_drm_packer.cpp:388-445`

```cpp
// 10. Java byte[] -> C++ vector
std::vector<uint8_t> responseData = jbyteArrayToVector(env, response);
// responseData = [53 4D 57 56 00 00 04 30 00 00 00 1C 02 2A 03 ...]
//                 S  M  W  V  --totalSize-  --metaSize-- ...

// 11. Goi parser
fplay::ParsedResponse parsed = fplay::parseAndDecryptResponse(
    responseData.data(), responseData.size());
//     |
```

### 3.3 Nhan dien format

**File:** `fptwv_parser.cpp:181-209`

```cpp
// 12. Nhan dien format — doc 4 byte dau
ByteBuffer buf(data, 1080);          // position_ = 0

int32_t magic = buf.readInt32();     // position_: 0 -> 4
// magic = 0x534D5756 = "SMWV"

// 13. Doc totalSize
int32_t totalSize = buf.readInt32(); // position_: 4 -> 8
// totalSize = 1080 (toan bo file)

// 14. Goi decryptPayload
return decryptPayload(buf, 1080, headerSize=8, SMWV, data, 1080);
//     |
```

Neu 5 byte dau la "FPTWV" → headerSize = 9.
Neu khong khop ca hai → RAW (tra nguyen, khong decrypt).

### 3.4 Doc tuan tu bang ByteBuffer

**File:** `fptwv_parser.cpp:86-136`

Day la core — `ByteBuffer` doc tuan tu, `position_` tu nhay:

```cpp
// 15. Kiem tra size hop le
// remaining = 1080 - 8 = 1072, headerSize = 8
// Check: remaining + headerSize == totalSize?
//        1072      + 8          == 1080?  -> YES, OK

// 16. Doc metadata header
int32_t metadataSize = buf.readInt32();  // position_: 8 -> 12   = 28
uint8_t encryptionType = buf.readByte(); // position_: 12 -> 13  = 2 (AES)
uint8_t fieldB = buf.readByte();         // position_: 13 -> 14  = 0x2A ('*')
uint8_t numKeyBytes = buf.readByte();    // position_: 14 -> 15  = 3

// 17. Doc keyMaterial (dynamic size = numKeyBytes = 3)
buf.readBytes(keyMaterial.data(), 3);    // position_: 15 -> 18
// keyMaterial = [3, 4, 0]

// 18. Doc IV (fixed 16 bytes)
buf.readBytes(iv, 16);                   // position_: 18 -> 34
// iv = [00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF]

// 19. Doc innerData (dynamic size)
int32_t innerSize = buf.readInt32();     // position_: 34 -> 38  = 2
buf.readBytes(innerData.data(), 2);      // position_: 38 -> 40
// innerData = [AB, CD]

// 20. Phan con lai = encryptedLicense
size_t encLicenseSize = buf.remaining(); // 1080 - 40 = 1040
buf.readBytes(encLicense.data(), 1040);  // position_: 40 -> 1080
```

### 3.5 ByteBuffer hoat dong nhu the nao

**File:** `byte_buffer.cpp`

`ByteBuffer` chi co 1 bien `position_`, moi lan doc xong tu cong len:

```cpp
// Doc 1 byte, position_ += 1
uint8_t readByte() {
    return buffer_[position_++];
}

// Doc 4 bytes big-endian, position_ += 4
int32_t readInt32() {
    int32_t value = (buffer_[position_] << 24) |
                    (buffer_[position_ + 1] << 16) |
                    (buffer_[position_ + 2] << 8) |
                    (buffer_[position_ + 3]);
    position_ += 4;
    return value;
}

// Doc N bytes, position_ += N
void readBytes(void* dest, size_t length) {
    memcpy(dest, buffer_ + position_, length);
    position_ += length;
}

// Con lai bao nhieu byte chua doc
size_t remaining() {
    return capacity_ - position_;
}
```

### Trace position_ tu dau den cuoi (vi du N=3, M=2)

```
Buoc                      position_ truoc → sau    Doc duoc gi
-----------------------   ----------------------    ---------------------------
readInt32() magic         0 -> 4                    "SMWV"
readInt32() totalSize     4 -> 8                    1072
readInt32() metadataSize  8 -> 12                   28
readByte()  encType       12 -> 13                  2 (AES)
readByte()  fieldB        13 -> 14                  0x2A ('*')
readByte()  numKeyBytes   14 -> 15                  3
readBytes() keyMaterial   15 -> 18                  [3, 4, 0]
readBytes() IV            18 -> 34                  16 bytes
readInt32() innerSize     34 -> 38                  2
readBytes() innerData     38 -> 40                  [AB, CD]
remaining()               40                        1040 bytes con lai
readBytes() encLicense    40 -> 1080                1040 bytes encrypted
```

---

## Phase 4: Derive key

**File:** `fptwv_parser.cpp:50-83`

```cpp
// 21. Tra bang: index -> device property
// keyMaterial[0] = 3  -> getPropertyByIndex(3)  -> "7.29.1"      (appVersion)
// keyMaterial[1] = 4  -> getPropertyByIndex(4)  -> "4290"         (appVersionCode)
// keyMaterial[2] = 0  -> getPropertyByIndex(0)  -> "8010a389..."  (deviceId)

// 22. Noi chuoi: value + fieldB sau MOI phan tu (ke ca cuoi)
concat = "7.29.1" + "*" + "4290" + "*" + "8010a3899890c7b9" + "*"
//     = "7.29.1*4290*8010a3899890c7b9*"

// 23. Append innerData raw bytes (KHONG co separator)
concat_bytes = b"7.29.1*4290*8010a3899890c7b9*\xAB\xCD"

// 24. MD5 -> 16-byte key
key = md5(concat_bytes)   // vi du: cd7792b3afb8a77bab96f1240af10c79
```

### Bang tra cuu property (index 0-17)

**File:** `fptwv_parser.cpp:20-43`

| Index | Property       | Vi du (FPT Play Box)     |
|-------|----------------|--------------------------|
| 0     | deviceId       | "8010a3899890c7b9"       |
| 1     | packageName    | "FPT Play"               |
| 2     | packageId      | "net.fptplay.ottbox"     |
| 3     | appVersion     | "7.29.1"                 |
| 4     | appVersionCode | "4290"                   |
| 5     | signature      | "9DFF668B73E0...A612"    |
| 6     | osVersion      | "11"                     |
| 7     | osBuild        | "RTT0.211222.001..."     |
| 8     | deviceModel    | "FIAT2W"                 |
| 9     | cpuInfo        | "amlogic"                |
| 10    | sdkVersion     | "30"                     |
| 11    | fingerprint    | "FPT-Play/FIAT2W/..."    |
| 12    | manufacture    | "Innopia"                |
| 13    | buildHost      | "..."                    |
| 14    | buildProduct   | "FIAT2W"                 |
| 15    | brand          | "FPT-Play"               |
| 16    | buildBoard     | "FIAT2W"                 |
| 17    | packerVersion  | "1.0.3"                  |

### MD5 hash

**File:** `crypto.cpp:120-124`

```cpp
std::vector<uint8_t> md5(const uint8_t* data, size_t len) {
    std::vector<uint8_t> hash(16);
    mbedtls_md5(data, len, hash.data());  // mbedTLS tinh MD5
    return hash;                           // 16 bytes = decryption key
}
```

---

## Phase 5: AES decrypt

**File:** `fptwv_parser.cpp:150-163`

```cpp
// 25. encryptionType == 2 -> AES-128-CBC
// encLicenseSize = 1040, 1040 % 16 == 0  OK

uint8_t iv_copy[16];
memcpy(iv_copy, iv, 16);  // copy vi mbedTLS se modify IV

auto decrypted = aes_cbc_decrypt(encLicense.data(), 1040,
                                  derivedKey.data(), iv_copy);
//     |
```

### AES-128-CBC decrypt

**File:** `crypto.cpp:42-59`

```cpp
// 26. AES-128-CBC decrypt
mbedtls_aes_setkey_dec(&ctx, key, 128);         // set 16-byte key
mbedtls_aes_crypt_cbc(&ctx, DECRYPT, 1040,
                       iv_copy, ciphertext, output);  // decrypt 1040 bytes
return output;  // 1040 bytes (con padding)
```

### PKCS7 unpad

**File:** `fptwv_parser.cpp:156-159` + `crypto.cpp:102-112`

```cpp
// 27. PKCS7 unpad — bo padding cuoi
int unpadded = pkcs7_unpad(decrypted.data(), 1040);
// Byte cuoi = 0x10 (16) -> 16 bytes padding -> unpadded = 1040 - 16 = 1024

// 28. Check byte dau
// decrypted[0] == 0x08 -> dung Widevine protobuf

decrypted.resize(1024);  // bo 16 bytes padding cuoi
result.decryptedPayload = decrypted;  // 1024 bytes raw Widevine license
result.success = true;
```

### Neu encryptionType == 1 (XOR)

**File:** `fptwv_parser.cpp:166-174` + `crypto.cpp:87-100`

```cpp
// Feedback XOR — KHONG phai XOR don gian
uint32_t feedback = 0;
for (size_t i = 0; i < data_len; i++) {
    uint8_t key_byte = key[i % 16];
    result[i] = data[i] ^ (feedback & 0xFF) ^ key_byte;
    feedback ^= key_byte;
    feedback ^= result[i];  // feedback dung DECRYPTED byte
}
```

---

## Phase 6: Feed vao MediaDrm

**File:** `fplay_drm_packer.cpp:427-444`

```cpp
// 29. Tao Java byte[] tu decrypted payload
jbyteArray decryptedArr = vectorToJbyteArray(env, parsed.decryptedPayload);
// decryptedArr = 1024 bytes raw Widevine license

// 30. Goi MediaDrm.provideKeyResponse() that
auto keySetId = env->CallObjectMethod(
    mediaDrm, provideKeyRespMid, scope, decryptedArr);
// MediaDrm nhan raw Widevine license -> cai content keys -> video phat duoc

// 31. Tra keySetId ve Java
return keySetId;  // byte[] hoac null
```

---

## Vi du cu the — byte map voi N=3, M=2

```
 O#  | Hex | Y nghia
-----+-----+--------------------------------------
  0  |  53 | +
  1  |  4D | | magic = "SMWV"
  2  |  57 | | offset=0, size=4
  3  |  56 | +
-----+-----+--------------------------------------
  4  |  00 | +
  5  |  00 | | totalSize = 1080 (toan bo file)
  6  |  04 | | offset=4, size=4
  7  |  38 | +
-----+-----+--------------------------------------
  8  |  00 | +
  9  |  00 | | metadataSize = 28
 10  |  00 | | offset=8, size=4
 11  |  1C | +
-----+-----+--------------------------------------
 12  |  02 |   encryptionType = 2 (AES)
     |     |   offset=12, size=1
-----+-----+--------------------------------------
 13  |  2A |   fieldB = '*'
     |     |   offset=13, size=1
-----+-----+--------------------------------------
 14  |  03 |   numKeyBytes = 3 (N)
     |     |   offset=14, size=1
-----+-----+--------------------------------------
 15  |  03 | +
 16  |  04 | | keyMaterial = [3, 4, 0]
 17  |  00 | + offset=15, size=N=3
-----+-----+--------------------------------------
 18  |  00 | +
 19  |  11 | |
 20  |  22 | |
 21  |  33 | |
 22  |  44 | |
 23  |  55 | | IV (16 bytes)
 24  |  66 | | offset=15+3=18, size=16
 25  |  77 | |
 26  |  88 | |
 27  |  99 | |
 28  |  AA | |
 29  |  BB | |
 30  |  CC | |
 31  |  DD | |
 32  |  EE | |
 33  |  FF | +
-----+-----+--------------------------------------
 34  |  00 | +
 35  |  00 | | innerSize = 2 (M)
 36  |  00 | | offset=31+3=34, size=4
 37  |  02 | +
-----+-----+--------------------------------------
 38  |  AB | + innerData
 39  |  CD | + offset=35+3=38, size=M=2
-----+-----+--------------------------------------
 40  |  XX | +
 41  |  XX | |
 ..  |  .. | | encryptedLicense (1040 bytes)
     |     | | offset=35+3+2=40
1079 |  XX | + size=1080-8-4-28=1040
```

---

## Source files

| File                                | Muc dich                                       |
|-------------------------------------|-------------------------------------------------|
| `HttpMediaDrmCallback.java:146-215` | Parse JSON response, inject custom-data header  |
| `FPlayDrmPacker.java`               | JNI interface: provideKeyResponse, requestInfo  |
| `FPlayMediaDrm.java:193-226`        | Goi FPlayDrmPacker + ClearKey adjust            |
| `fplay_drm_packer.cpp:388-445`      | JNI provideKeyResponse -> parseAndDecrypt       |
| `fptwv_parser.cpp`                  | Parse SMWV/FPTWV binary, derive key, decrypt    |
| `byte_buffer.cpp`                   | ByteBuffer: doc tuan tu voi position_ tu nhay   |
| `crypto.cpp`                        | AES-128-CBC, Feedback XOR, MD5, PKCS7           |
