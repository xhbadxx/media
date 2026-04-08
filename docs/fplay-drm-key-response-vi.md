# FPlay DRM Key Response — Chi tiet & Huong dan Server

## Tong quan

Khi client gui Widevine challenge len server, server tra ve **license response** duoi dang JSON. Response nay chua Widevine license da duoc **ma hoa** theo format SMWV/FPTWV. Client se giai ma va feed vao `MediaDrm.provideKeyResponse()` de cai dat content keys.

```
Server Response (HTTP)
  |
  v
JSON { "license": "Base64(SMWV_BYTES)" }
  |
  v  Base64 decode
SMWV binary (encrypted Widevine license)
  |
  v  FPlayDrmPacker.provideKeyResponse()
       -> parseAndDecryptResponse()
       -> deriveKey() -> MD5
       -> AES-CBC decrypt hoac XOR decrypt
  |
  v
Raw Widevine license protobuf
  |
  v  MediaDrm.provideKeyResponse()
Content keys installed -> video phat duoc
```

---

## 1. Server Response Format

### 1.1 HTTP Response

Server tra ve JSON body:

```json
{
  "license": "U01XVgAABQ..."
}
```

| Field     | Type   | Mo ta                                            |
|-----------|--------|--------------------------------------------------|
| `license` | string | Base64-encoded cua SMWV/FPTWV binary payload     |

Client parse JSON, Base64 decode ra bytes, roi truyen cho `FPlayMediaDrm.provideKeyResponse()`.

**Code client xu ly (HttpMediaDrmCallback.java:201-213):**

```java
// Nhan HTTP response
Response response = executePost(dataSource, url, request.getData(), requestProperties);

// Parse JSON, lay field "license"
JSONObject jsonObject = new JSONObject(new String(response.data));
String licenseEncrypted = jsonObject.getString("license");

// Base64 decode -> raw SMWV bytes
byte[] licenseBytes = Base64.decode(licenseEncrypted, Base64.DEFAULT);

// Truyen cho FPlayMediaDrm.provideKeyResponse(scope, licenseBytes)
```

---

## 2. SMWV/FPTWV Binary Format

Sau khi Base64 decode, du lieu co cau truc nhu sau:

### 2.1 SMWV Format (Sigma's original)

```
Offset  Size    Field               Mo ta
------  ------  ------------------  ----------------------------------------
0       4       magic               "SMWV" (0x534D5756, big-endian)
4       4       totalSize           Tong kich thuoc toan bo binary (int32 BE)
8       4       metadataSize        Kich thuoc vung metadata (int32)
12      1       encryptionType      1 = XOR, 2 = AES-128-CBC
13      1       fieldB              Separator byte cho key derivation
14      1       numKeyBytes         So luong index trong keyMaterial
15      N       keyMaterial[N]      Mang cac index (0-17) tro toi device property
15+N    16      IV                  Initialization Vector cho AES-CBC
31+N    4       innerSize           Kich thuoc cua innerData (int32)
35+N    M       innerData[M]        Du lieu bo sung cho key derivation
35+N+M  *       encryptedLicense    Widevine license da ma hoa (phan con lai)
```

### 2.2 FPTWV Format (FPlay variant)

```
Offset  Size    Field               Mo ta
------  ------  ------------------  ----------------------------------------
0       5       magic               "FPTWV" (5 bytes ASCII)
5       4       totalSize           Tong kich thuoc toan bo binary (int32 BE)
9       ...     (giong SMWV tu offset 8)
```

### 2.3 RAW Format

Neu 4 byte dau KHONG phai "SMWV" va 5 byte dau KHONG phai "FPTWV" -> tra nguyen (standard Widevine license, khong ma hoa).

---

## 3. Key Derivation Algorithm

Day la phan QUAN TRONG NHAT. Server va client phai thong nhat cach tao key de client giai ma duoc.

### 3.1 Property Lookup Table

Server gui `keyMaterial` la mang cac **index** (0-17), moi index tro toi 1 device property:

| Index | Property Name    | Vi du (FPT Play Box)           |
|-------|------------------|--------------------------------|
| 0     | deviceId         | `"8010a3899890c7b9"`           |
| 1     | packageName      | `"FPT Play"`                   |
| 2     | packageId        | `"net.fptplay.ottbox"`         |
| 3     | appVersion       | `"7.29.1"`                     |
| 4     | appVersionCode   | `"4290"`                       |
| 5     | signature        | `"9DFF668B73E0...A612"`        |
| 6     | osVersion        | `"11"`                         |
| 7     | osBuild          | `"RTT0.211222.001..."`         |
| 8     | deviceModel      | `"FIAT2W"`                     |
| 9     | cpuInfo          | `"amlogic"`                    |
| 10    | sdkVersion       | `"30"`                         |
| 11    | fingerprint      | `"FPT-Play/FIAT2W/..."`       |
| 12    | manufacture      | `"Innopia"`                    |
| 13    | buildHost        | `"..."`                        |
| 14    | buildProduct     | `"FIAT2W"`                     |
| 15    | brand            | `"FPT-Play"`                   |
| 16    | buildBoard       | `"FIAT2W"`                     |
| 17    | packerVersion    | `"1.0.3"`                      |

**Luu y:** Server biet cac property nay vi client da gui `deviceInfo` JSON trong header `custom-data` truoc do (xem Section 6).

### 3.2 Derive Key — Tung buoc

```
Input:
  keyMaterial = [3, 4, 11, 7, 9, 10, 5, 0, 16, 12, 15]  (tu SMWV header)
  fieldB      = 0x2A  (ky tu '*')                          (tu SMWV header)
  innerData   = <raw bytes>                                (tu SMWV header)

Buoc 1: Tra cuu property theo tung index
  keyMaterial[0] = 3  -> appVersion     = "7.29.1"
  keyMaterial[1] = 4  -> appVersionCode = "4290"
  keyMaterial[2] = 11 -> fingerprint    = "FPT-Play/FIAT2W/..."
  keyMaterial[3] = 7  -> osBuild        = "RTT0.211222.001..."
  ...

Buoc 2: Noi chuoi voi separator fieldB SAU MOI gia tri (ke ca phan tu cuoi)
  concat = "7.29.1" + "*"
         + "4290" + "*"
         + "FPT-Play/FIAT2W/..." + "*"
         + "RTT0.211222.001..." + "*"
         + "amlogic" + "*"
         + "30" + "*"
         + "9DFF668B73E0...A612" + "*"
         + "8010a3899890c7b9" + "*"
         + "FIAT2W" + "*"
         + "Innopia" + "*"
         + "FPT-Play" + "*"        <-- separator CA SAU PHAN TU CUOI

Buoc 3: Append innerData raw bytes vao cuoi
  concat = concat + innerData

Buoc 4: Hash MD5 -> 16 bytes = decryption key
  key = MD5(concat)
      = cd7792b3afb8a77bab96f1240af10c79  (vi du)
```

**CRITICAL:** Separator (fieldB) phai co SAU MOI phan tu, KE CA phan tu cuoi cung. Day la diem khac biet quan trong — neu thieu trailing separator se ra sai key.

### 3.3 Pseudocode cho server

```python
def derive_key(key_material_indices, field_b, inner_data, device_info):
    """
    key_material_indices: list[int] — cac index 0-17
    field_b: int — separator byte (VD: 0x2A = '*')
    inner_data: bytes — du lieu bo sung
    device_info: dict — thong tin thiet bi tu header custom-data
    """
    # Map index -> property name
    PROPERTY_MAP = {
        0: "deviceId",      1: "packageName",   2: "packageId",
        3: "appVersion",    4: "appVersionCode", 5: "signature",
        6: "osVersion",     7: "osBuild",       8: "deviceModel",
        9: "cpuInfo",      10: "sdkVersion",    11: "fingerprint",
        12: "manufacture", 13: "buildHost",     14: "buildProduct",
        15: "brand",       16: "buildBoard",    17: "packerVersion",
    }

    concat = ""
    for idx in key_material_indices:
        prop_name = PROPERTY_MAP[idx]
        prop_value = device_info[prop_name]
        concat += prop_value + chr(field_b)  # separator SAU MOI phan tu

    # Append innerData
    concat_bytes = concat.encode("utf-8") + inner_data

    # MD5 hash -> 16-byte key
    import hashlib
    key = hashlib.md5(concat_bytes).digest()
    return key
```

---

## 4. Encryption / Decryption

### 4.1 AES-128-CBC (encryptionType = 2)

Day la phuong thuc ma hoa pho bien nhat.

**Server encrypt:**

```python
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
import os

def encrypt_license_aes(widevine_license_bytes, key, iv=None):
    """
    widevine_license_bytes: bytes — raw Widevine license protobuf
    key: bytes[16] — derived key tu Section 3
    iv: bytes[16] — random IV (tao moi cho moi response)
    """
    if iv is None:
        iv = os.urandom(16)

    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pad(widevine_license_bytes, 16))  # PKCS7 padding

    return encrypted, iv
```

**Client decrypt (C++ — fptwv_parser.cpp:150-163):**

```cpp
// AES-128-CBC decrypt + PKCS7 unpad
auto decrypted = aes_cbc_decrypt(encLicense.data(), encLicenseSize,
                                  derivedKey.data(), iv_copy);
int unpadded = pkcs7_unpad(decrypted.data(), decrypted.size());
if (unpadded > 0) decrypted.resize(unpadded);
```

### 4.2 Feedback XOR Cipher (encryptionType = 1)

Day KHONG phai XOR don gian. Moi output byte anh huong den tat ca byte sau do.

**Server encrypt:**

```python
def encrypt_license_xor(widevine_license_bytes, key):
    """
    Feedback XOR — moi byte output anh huong byte ke tiep
    key: bytes[16]
    """
    data = bytearray(widevine_license_bytes)
    result = bytearray(len(data))
    feedback = 0  # uint32

    for i in range(len(data)):
        key_byte = key[i % 16]
        # Encrypt: result = plain XOR feedback XOR key
        result[i] = data[i] ^ (feedback & 0xFF) ^ key_byte
        # Update feedback for next byte
        feedback = feedback ^ key_byte
        feedback = feedback ^ result[i]

    return bytes(result)
```

**QUAN TRONG:** Decrypt la inverse — client decrypt dung:
```
result[i] = encrypted[i] XOR (feedback & 0xFF) XOR key_byte
feedback ^= key_byte
feedback ^= result[i]  // <-- dung DECRYPTED byte, khong phai encrypted
```

Nen su dung **AES-128-CBC** (type 2) thay vi XOR (type 1). AES an toan hon va de kiem tra hon (PKCS7 padding validation).

---

## 5. Dong goi SMWV Response — Huong dan Server

### 5.1 Toan bo quy trinh phia server

```
                         Server nhan request
                               |
                    +----------+-----------+
                    |                      |
           Widevine CDM Proxy        Parse custom-data
           (forward challenge         (lay deviceInfo JSON)
            to Widevine server)
                    |                      |
           Raw Widevine license    Device properties
                    |                      |
                    +----------+-----------+
                               |
                      Chon encryption params:
                      - keyMaterial indices (random subset)
                      - fieldB separator (random byte)
                      - innerData (random bytes, optional)
                      - IV (random 16 bytes)
                      - encryptionType (1 hoac 2)
                               |
                      Derive key:
                      MD5(concat props + fieldB + innerData)
                               |
                      Encrypt Widevine license
                      (AES-CBC hoac Feedback XOR)
                               |
                      Pack SMWV binary
                               |
                      Base64 encode
                               |
                      Return JSON {"license": "..."}
```

### 5.2 Code mau: Dong goi SMWV (Python)

```python
import struct
import hashlib
import os
import base64
import json
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

def pack_smwv_response(widevine_license: bytes, device_info: dict) -> str:
    """
    Dong goi Widevine license thanh SMWV format va tra ve Base64 string.

    Args:
        widevine_license: Raw Widevine license protobuf bytes
        device_info: Dict chua device properties tu client header

    Returns:
        Base64-encoded SMWV binary, de put vao JSON {"license": "..."}
    """

    # === 1. Chon encryption parameters ===
    encryption_type = 2  # AES-128-CBC (khuyen dung)
    field_b = 0x2A       # '*' separator — co the random
    iv = os.urandom(16)  # Random IV moi cho moi response

    # Chon cac property index de tao key
    # Server quyet dinh, client se dung chinh cac index nay de derive key
    key_material = [3, 4, 11, 7, 9, 10, 5, 0, 16, 12, 15]

    # Inner data — du lieu bo sung (optional, co the la random hoac empty)
    inner_data = os.urandom(8)  # VD: 8 random bytes

    # === 2. Derive encryption key ===
    PROPERTY_MAP = {
        0: "deviceId",      1: "packageName",   2: "packageId",
        3: "appVersion",    4: "appVersionCode", 5: "signature",
        6: "osVersion",     7: "osBuild",       8: "deviceModel",
        9: "cpuInfo",      10: "sdkVersion",    11: "fingerprint",
        12: "manufacture", 13: "buildHost",     14: "buildProduct",
        15: "brand",       16: "buildBoard",    17: "packerVersion",
    }

    concat = ""
    for idx in key_material:
        prop_name = PROPERTY_MAP[idx]
        prop_value = str(device_info.get(prop_name, ""))
        concat += prop_value + chr(field_b)

    concat_bytes = concat.encode("utf-8") + inner_data
    key = hashlib.md5(concat_bytes).digest()  # 16 bytes

    # === 3. Encrypt Widevine license ===
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted_license = cipher.encrypt(pad(widevine_license, 16))

    # === 4. Pack SMWV binary ===
    key_material_bytes = bytes(key_material)
    num_key_bytes = len(key_material)
    inner_size = len(inner_data)

    # metadata = encryptionType(1) + fieldB(1) + numKeyBytes(1)
    #          + keyMaterial(N) + IV(16) + innerSize(4) + innerData(M)
    metadata_size = 1 + 1 + 1 + num_key_bytes + 16 + 4 + inner_size

    # totalSize = toan bo file = magic(4) + totalSize(4) + metadataSize(4) + metadata + encrypted
    total_size = 4 + 4 + 4 + metadata_size + len(encrypted_license)

    smwv = bytearray()
    smwv += b"SMWV"                                          # magic (4 bytes)
    smwv += struct.pack(">i", total_size)                    # totalSize (int32 BE)
    smwv += struct.pack(">i", metadata_size)                 # metadataSize (int32)
    smwv += struct.pack("B", encryption_type)                # encryptionType (1 byte)
    smwv += struct.pack("B", field_b)                        # fieldB (1 byte)
    smwv += struct.pack("B", num_key_bytes)                  # numKeyBytes (1 byte)
    smwv += key_material_bytes                               # keyMaterial (N bytes)
    smwv += iv                                               # IV (16 bytes)
    smwv += struct.pack(">i", inner_size)                    # innerSize (int32)
    smwv += inner_data                                       # innerData (M bytes)
    smwv += encrypted_license                                # encryptedLicense

    # === 5. Base64 encode ===
    return base64.b64encode(bytes(smwv)).decode("ascii")


# === Su dung ===
def handle_license_request(request_body, custom_data_header):
    """Handler cho license request endpoint"""

    # 1. Decode custom-data header
    custom_data_json = base64.b64decode(custom_data_header).decode("utf-8")
    custom_data = json.loads(custom_data_json)

    req_id = custom_data["reqId"]
    device_info_json = custom_data["deviceInfo"]
    device_info = json.loads(device_info_json)

    # 2. Forward challenge to Widevine CDM server -> get raw license
    widevine_license = forward_to_widevine_server(request_body)

    # 3. Pack SMWV
    license_b64 = pack_smwv_response(widevine_license, device_info)

    # 4. Return JSON
    return json.dumps({"license": license_b64})
```

### 5.3 Vi du cu the — tung byte

Gia su ta co:
- `widevine_license` = 1024 bytes (raw protobuf, bat dau bang `0x08`)
- `key_material` = `[3, 4, 0]` (3 index)
- `field_b` = `0x2A` (`'*'`)
- `inner_data` = `b"\xAB\xCD"` (2 bytes)
- `iv` = `00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF`
- `encryption_type` = 2 (AES-CBC)

**Key derivation:**
```
device_info["appVersion"]     = "7.29.1"
device_info["appVersionCode"] = "4290"
device_info["deviceId"]       = "8010a3899890c7b9"

concat = "7.29.1" + "*" + "4290" + "*" + "8010a3899890c7b9" + "*"
       = "7.29.1*4290*8010a3899890c7b9*"

concat_bytes = b"7.29.1*4290*8010a3899890c7b9*" + b"\xAB\xCD"
             = b"7.29.1*4290*8010a3899890c7b9*\xAB\xCD"

key = MD5(concat_bytes) = <16 bytes>
```

**SMWV binary:**

```
metadata = encType(1) + fieldB(1) + numKeyBytes(1) + keyMat(3) + IV(16) + innerSize(4) + innerData(2)
         = 1 + 1 + 1 + 3 + 16 + 4 + 2 = 28

metadataSize = 28

totalSize = toan bo file
          = magic(4) + totalSize_field(4) + metadataSize_field(4) + metadata(28) + encrypted(1040)
          = 4 + 4 + 4 + 28 + 1040
          = 1080

-> Hex: 0x00000438
```

```
Offset  Hex                          Field
------  ---------------------------  ------------------
0x00    53 4D 57 56                  magic = "SMWV"
0x04    00 00 04 38                  totalSize = 1080 (toan bo file)
0x08    00 00 00 1C                  metadataSize = 28
0x0C    02                           encryptionType = 2
0x0D    2A                           fieldB = '*'
0x0E    03                           numKeyBytes = 3
0x0F    03 04 00                     keyMaterial = [3, 4, 0]
0x12    00 11 22 ... EE FF           IV (16 bytes)
0x22    00 00 00 02                  innerSize = 2
0x26    AB CD                        innerData (2 bytes)
0x28    [1040 bytes encrypted]       AES-CBC(PKCS7_pad(license))
```

Tong cong: 4 + 4 + 4 + 28 + 1040 = **1080 bytes** = totalSize.

**totalSize verify (cach client kiem tra — fptwv_parser.cpp:92-93):**
```
remaining = buf.remaining()          // bytes chua doc = 1080 - 8 = 1072
headerSize = 8                       // magic(4) + totalSize(4) da doc roi

Check: remaining + headerSize == totalSize?
       1072      + 8          == 1080?  -> YES, data hop le

Neu sai -> data corrupt hoac format la -> fallback RAW (khong decrypt)
```

---

## 6. Custom-Data Header — Client gui gi len server?

Truoc khi server tra response, client gui request voi header `custom-data`:

```
HTTP POST /license-endpoint
Headers:
  Content-Type: application/octet-stream
  custom-data: eyJhcHBWZXJzaW9uQ29kZSI6NDI5MCwi...   <-- Base64
Body: <Widevine challenge bytes>
```

**custom-data decoded:**
```json
{
  "merchantId": "...",
  "appId": "...",
  "userId": "...",
  "sessionId": "...",
  "reqId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "deviceInfo": "{\"appVersionCode\":4290,\"appVersion\":\"7.29.1\",\"packageId\":\"net.fptplay.ottbox\",\"deviceId\":\"8010a3899890c7b9\",\"platform\":\"android\",\"deviceName\":\"FIAT2W\",\"buildBoard\":\"FIAT2W\",\"brand\":\"FPT-Play\",\"buildProduct\":\"FIAT2W\",\"manufacture\":\"Innopia\",\"fingerprint\":\"FPT-Play/FIAT2W/...\",\"buildHost\":\"...\",\"cpuInfo\":\"amlogic\",\"osBuild\":\"RTT0.211222.001...\",\"signature\":\"9DFF668B73E0...\",\"packerVersion\":\"1.0.3\",\"deviceModel\":\"FIAT2W\",\"osVersion\":\"11\",\"packageName\":\"FPT Play\",\"sdkVersion\":\"30\"}"
}
```

**Luu y:** `deviceInfo` la STRING (JSON-escaped), khong phai object. Server can `JSON.parse(custom_data.deviceInfo)` de lay dict device properties.

**Client code tao header (HttpMediaDrmCallback.java:174-198):**
```java
// sigma-custom-data la internal key, chua business fields (merchantId, appId, ...)
JSONObject originalData = new JSONObject(keyRequestProperties.get("sigma-custom-data"));

// FPlayDrmPacker tao requestId va deviceInfo JSON
RequestInfo fplayInfo = FPlayDrmPacker.requestInfo(request.getData());
originalData.put("reqId", fplayInfo.requestId);
originalData.put("deviceInfo", fplayInfo.deviceInfo);

// Base64 encode toan bo -> header "custom-data"
String customDataB64 = Base64.encodeToString(originalData.toString().getBytes(), Base64.NO_WRAP);
requestProperties.put("custom-data", customDataB64);
```

---

## 7. Verification Checklist cho Server

Khi implement server, kiem tra cac diem sau:

### 7.1 totalSize phai chinh xac

```
totalSize = toan bo file
          = magic(4) + totalSize_field(4) + metadataSize_field(4)
          + metadataSize (actual metadata)
          + len(encrypted_license)

metadataSize = 1 (encType) + 1 (fieldB) + 1 (numKeyBytes)
             + numKeyBytes (keyMaterial)
             + 16 (IV)
             + 4 (innerSize field)
             + innerSize (innerData)

Client kiem tra (fptwv_parser.cpp:92-93):
  remaining + headerSize == totalSize
  (bytes chua doc) + (magic + totalSize field) == totalSize
```

Neu totalSize sai, client se fallback sang RAW format (khong decrypt) -> `MediaDrm` nhan encrypted data -> FAIL.

### 7.2 Trailing separator (fieldB)

Separator (fieldB) phai o SAU MOI property value, KE CA phan tu cuoi:

```
DUNG:  "value1*value2*value3*"     <-- trailing separator
SAI:   "value1*value2*value3"      <-- thieu trailing separator -> MD5 sai -> decrypt fail
```

### 7.3 Encrypted license phai la boi so 16 (voi AES)

```
encryptionType = 2 (AES):
  len(encrypted_license) % 16 == 0   <-- BAT BUOC (PKCS7 padding)

encryptionType = 1 (XOR):
  khong can padding, bat ky kich thuoc
```

### 7.4 innerData append KHONG co separator

```
concat = prop1 + fieldB + prop2 + fieldB + ... + propN + fieldB + innerData
                                                         ^                ^
                                                    co separator    KHONG co separator sau innerData
```

### 7.5 Decrypted output bat dau bang 0x08

Sau khi decrypt thanh cong, byte dau tien cua Widevine license protobuf luon la `0x08` (protobuf field 1, varint type). Neu byte dau khac -> decrypt sai.

---

## 8. Error Cases

| Trieu chung                        | Nguyen nhan                               | Cach sua                                    |
|-------------------------------------|-------------------------------------------|---------------------------------------------|
| HTTP 403 khi phat video             | requestId sai hoac thieu deviceInfo       | Kiem tra custom-data header                 |
| `DeniedByServerException`           | Widevine license bi loi sau decrypt       | Kiem tra key derivation + PKCS7 padding     |
| Video khong phat, khong co loi      | totalSize sai -> client dung RAW format   | Fix totalSize calculation                   |
| `provideKeyResponse call failed`    | Decrypted data khong phai Widevine valid  | Kiem tra trailing separator, innerData      |
| Phat duoc tren thiet bi A, fail B   | Device property khac nhau                 | Dam bao server dung dung deviceInfo tu req  |

---

## 9. Tham khao — File Source Code

| File                          | Muc dich                                           |
|-------------------------------|-----------------------------------------------------|
| `FPlayDrmPacker.java`         | JNI interface: `provideKeyResponse()`, `requestInfo()` |
| `FPlayMediaDrm.java:193-226` | Goi `FPlayDrmPacker.provideKeyResponse()` + ClearKey adjust |
| `HttpMediaDrmCallback.java:146-215` | Parse JSON response, inject custom-data header |
| `fptwv_parser.cpp`            | Parse SMWV/FPTWV binary, derive key, decrypt        |
| `crypto.cpp`                  | AES-128-CBC, Feedback XOR, MD5, PKCS7               |
| `fplay_drm_packer.cpp:388-445` | JNI `provideKeyResponse` -> parseAndDecrypt -> MediaDrm |
