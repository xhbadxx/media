# generateRequestId — Giải thích chi tiết với ví dụ

## Kiến thức nền tảng

### Hệ thống số Hexadecimal (Hex)

Máy tính lưu dữ liệu dưới dạng **byte** (8 bit). Mỗi byte biểu diễn bằng 2 ký tự hex.

```
Decimal:  0  1  2  3  4  5  6  7  8  9  10  11  12  13  14  15
Hex:      0  1  2  3  4  5  6  7  8  9   A   B   C   D   E   F
```

Ví dụ: byte `0xA7` = `1010 0111` trong binary = `167` trong decimal.

Prefix `0x` nghĩa là "số hex". `0xA7` = hex A7.

### Nibble là gì?

Mỗi byte (8 bit) chia thành 2 **nibble** (4 bit):

```
Byte 0xA7 = 1010 0111
            ^^^^-^^^^
            |    |
            |    └── Low nibble  = 0111 = 0x7
            └─────── High nibble = 1010 = 0xA

Nói cách khác:
  High nibble = chữ số hex BÊN TRÁI  = A
  Low nibble  = chữ số hex BÊN PHẢI  = 7
```

### Phép toán bit (Bitwise Operations)

#### `>>` — Shift Right (dịch phải)

Dời tất cả bit sang phải N vị trí, bit bên trái điền 0.

```
0xA7 >> 4   (dịch phải 4 bit)

Trước:  1010 0111  = 0xA7
Sau:    0000 1010  = 0x0A

→ Lấy HIGH nibble (A) ra, đưa về vị trí thấp
```

```
0xA7 >> 5   (dịch phải 5 bit)

Trước:  1010 0111  = 0xA7 = 167
Sau:    0000 0101  = 0x05 = 5

→ Lấy 3 bit cao nhất: 101 = 5
→ Dùng làm index (0..7) vì 3 bit tối đa = 111 = 7
```

#### `<<` — Shift Left (dịch trái)

Dời tất cả bit sang trái N vị trí, bit bên phải điền 0.

```
0x09 << 4   (dịch trái 4 bit)

Trước:  0000 1001  = 0x09
Sau:    1001 0000  = 0x90

→ Đẩy low nibble (9) lên vị trí high nibble
```

#### `&` — AND (chỉ giữ bit nào CẢ HAI đều = 1)

```
0xA7 & 0xF0

  1010 0111   (0xA7)
& 1111 0000   (0xF0)
= 1010 0000   (0xA0)

→ Giữ lại HIGH nibble, xóa LOW nibble
```

```
0xA7 & 0x0F

  1010 0111   (0xA7)
& 0000 1111   (0x0F)
= 0000 0111   (0x07)

→ Giữ lại LOW nibble, xóa HIGH nibble
```

```
0xC7 & 0xFE

  1100 0111   (0xC7)
& 1111 1110   (0xFE)
= 1100 0110   (0xC6)

→ Clear bit 0 (bit cuối cùng bên phải = 0)
```

#### `|` — OR (ghép 2 nibble thành 1 byte)

```
0xA0 | 0x07

  1010 0000   (high nibble A ở vị trí cao)
| 0000 0111   (low nibble 7 ở vị trí thấp)
= 1010 0111   (0xA7)

→ Ghép: A ở trên + 7 ở dưới = A7
```

#### `^` — XOR (khác thì 1, giống thì 0)

```
  1010 0111
^ 0011 1100
= 1001 1011

→ Dùng trong mã hóa: XOR 2 lần sẽ khôi phục dữ liệu gốc
```

### Tổng hợp: Các combo thường gặp trong code

| Code | Ý nghĩa | Ví dụ |
|------|---------|-------|
| `x >> 4` | Lấy high nibble | `0xA7 >> 4 = 0x0A` |
| `x & 0x0F` | Lấy low nibble | `0xA7 & 0x0F = 0x07` |
| `x & 0xF0` | Lấy high nibble (giữ vị trí) | `0xA7 & 0xF0 = 0xA0` |
| `(x & 0x0F) << 4` | Low nibble → đẩy lên high | `(0xA7 & 0x0F) << 4 = 0x70` |
| `x & 0xFE` | Clear bit 0 | `0xC7 & 0xFE = 0xC6` |
| `a \| b` | Ghép 2 nibble | `0xA0 \| 0x07 = 0xA7` |
| `x >> 5` | Lấy 3 bit cao → index 0..7 | `0xA7 >> 5 = 5` |

---

## generateRequestId — Từng bước với demo data

### Dữ liệu đầu vào

```
challengeData = Widevine license request bytes (giả sử 256 bytes từ MediaDrm)
deviceInfoJson = '{"appVersionCode":4290,"appVersion":"7.29.1",...}'
```

---

### Step 1: Random 8 bytes

Dùng `java.security.SecureRandom` tạo 8 byte ngẫu nhiên.

```
random[8] = { A7, 3B, F2, 19, 84, C6, 5D, E8 }
              [0] [1] [2] [3] [4] [5] [6] [7]
```

**Vai trò:** Đây là nguồn ngẫu nhiên duy nhất. Tất cả 16 low nibble trong UUID đều lấy từ 8 byte này (mỗi byte có 2 nibble = 16 nibble tổng cộng).

---

### Step 2: Status flag — Clear bit 0

**Mục đích:** Server Sigma validate 1 bit cụ thể trong UUID. Bit này PHẢI = 0 thì request mới được chấp nhận.

**Bước 2a: Tính index**

```
idx = random[0] >> 5

random[0] = 0xA7 = 1010 0111

Dịch phải 5 bit:
  1010 0111
  → 0000 0101 = 5

idx = 5
```

`>> 5` lấy 3 bit cao nhất (101 = 5), cho ra index từ 0..7 (vì 3 bit max = 111 = 7).
Nên index luôn trỏ vào 1 trong 8 byte random[0..7].

**Bước 2b: Clear bit 0 tại random[idx]**

```
random[5] = 0xC6 = 1100 0110
                              ^-- bit 0 đã = 0 rồi

random[5] & 0xFE:
  1100 0110   (0xC6)
& 1111 1110   (0xFE)
= 1100 0110   (0xC6)  ← không đổi

Kết quả: random[5] vẫn = 0xC6
```

**Ví dụ nếu random[5] = 0xC7 (bit 0 = 1):**

```
random[5] = 0xC7 = 1100 0111
                              ^-- bit 0 = 1, cần clear!

random[5] & 0xFE:
  1100 0111   (0xC7)
& 1111 1110   (0xFE)
= 1100 0110   (0xC6)  ← bit 0 đã được clear

random[5] = 0xC6
```

**Tại sao?**
- Decompiler ban đầu đọc là `| 0x01` (SET bit 0 = 1)
- Nhưng phân tích thực tế: TẤT CẢ request Sigma thành công có bit 0 = 0
- TẤT CẢ request FPlay thất bại có bit 0 = 1
- Kết luận: Sigma CLEAR bit 0, decompiler đọc sai phép toán
- Server dùng bit này như "magic flag" để filter request invalid

**Kết quả Step 2:**

```
random[8] = { A7, 3B, F2, 19, 84, C6, 5D, E8 }  (không đổi trong ví dụ này)
```

---

### Step 3: Seed từ 4 byte đầu (big-endian)

Ghép 4 byte random đầu thành 1 số 32-bit:

```
seed = (random[0] << 24) | (random[1] << 16) | (random[2] << 8) | random[3]

     = (0xA7 << 24) | (0x3B << 16) | (0xF2 << 8) | 0x19

     = 0xA7000000 | 0x003B0000 | 0x0000F200 | 0x00000019

     = 0xA73BF219

Decimal: 2,806,186,521
```

**Big-endian** = byte quan trọng nhất (A7) ở đầu. Đây là cách network/Java lưu số.

---

### Step 4: Reseed với challenge data

```
seed = CRC32(0xA73BF219, challengeData)
     = 0x5E2D41F8    (ví dụ — phụ thuộc vào nội dung challenge)
```

CRC32 là hàm hash nhanh, nhận seed + data → ra 32-bit hash.

**Mục đích:** Cùng 1 device nhưng challenge khác (video khác, session khác) → seed khác → requestId khác. Server detect replay attack nếu thấy cùng requestId cho challenge khác.

---

### Step 5: Hash deviceInfo

```
hashValue = CRC32(0x5E2D41F8, deviceInfoJson)
          = 0xB9A3E72C    (ví dụ)
```

**Chain:** `random → seed → CRC32(challenge) → CRC32(deviceInfo) → hashValue`

4 bytes hashValue này sẽ được nhúng vào UUID (8 high nibbles).

---

### Step 6: Timestamp

```
Thời điểm: 2026-03-30 15:23:50 UTC

timeSec = 1774988630 = 0x69D5C3A6
```

4 bytes timestamp sẽ được nhúng vào UUID (8 high nibbles khác).

---

### Step 7: Chuyển sang byte arrays

```
hashValue = 0xB9A3E72C
hashBytes[4] = { B9, A3, E7, 2C }
                 [0] [1] [2] [3]

timeSec = 0x69D5C3A6
timeBytes[4] = { 69, D5, C3, A6 }
                 [0] [1] [2] [3]
```

---

### Step 8: Nibble Interleaving — Tạo 16 byte UUID

Đây là bước chính: ghép 3 nguồn dữ liệu vào 16 byte.

**Nguyên tắc:** Mỗi byte UUID = 1 high nibble (từ hash hoặc time) + 1 low nibble (từ random)

```
3 nguồn:
  H = hashBytes  (4 bytes = 8 nibbles)  → high nibble tại vị trí 0,1,4,5,8,9,12,13
  T = timeBytes  (4 bytes = 8 nibbles)  → high nibble tại vị trí 2,3,6,7,10,11,14,15
  R = random     (8 bytes = 16 nibbles) → low nibble tại MỌI vị trí
```

**Vòng lặp i=0 đến 3, mỗi vòng tạo 4 byte UUID:**

#### Vòng i=0

```
Inputs: hashBytes[0] = 0xB9    timeBytes[0] = 0x69
        random[0]    = 0xA7    random[1]    = 0x3B
```

**uuid[0]:** High từ hash, Low từ random[0]

```
(B9 & F0) | (A7 >> 4)

B9 & F0:                    A7 >> 4:
  1011 1001                   1010 0111
& 1111 0000                   → 0000 1010
= 1011 0000 = 0xB0           = 0x0A

0xB0 | 0x0A:
  1011 0000
| 0000 1010
= 1011 1010 = 0xBA

uuid[0] = 0xBA   ← high=B(hash), low=A(random)
```

**uuid[1]:** Nibble còn lại của hash + random[0]

```
((B9 & 0F) << 4) | (A7 & 0F)

B9 & 0F = 0x09              A7 & 0F = 0x07
0x09 << 4 = 0x90

0x90 | 0x07 = 0x97

uuid[1] = 0x97   ← high=9(hash), low=7(random)
```

**uuid[2]:** High từ time, Low từ random[1]

```
(69 & F0) | (3B >> 4)

69 & F0 = 0x60              3B >> 4 = 0x03

0x60 | 0x03 = 0x63

uuid[2] = 0x63   ← high=6(time), low=3(random)
```

**uuid[3]:** Nibble còn lại của time + random[1]

```
((69 & 0F) << 4) | (3B & 0F)

69 & 0F = 0x09              3B & 0F = 0x0B
0x09 << 4 = 0x90

0x90 | 0x0B = 0x9B

uuid[3] = 0x9B   ← high=9(time), low=B(random)
```

#### Vòng i=1

```
Inputs: hashBytes[1] = 0xA3    timeBytes[1] = 0xD5
        random[2]    = 0xF2    random[3]    = 0x19

uuid[4] = (A3 & F0) | (F2 >> 4) = A0 | 0F = 0xAF
uuid[5] = ((A3 & 0F) << 4) | (F2 & 0F) = 30 | 02 = 0x32
uuid[6] = (D5 & F0) | (19 >> 4) = D0 | 01 = 0xD1
uuid[7] = ((D5 & 0F) << 4) | (19 & 0F) = 50 | 09 = 0x59
```

#### Vòng i=2

```
Inputs: hashBytes[2] = 0xE7    timeBytes[2] = 0xC3
        random[4]    = 0x84    random[5]    = 0xC6

uuid[8]  = (E7 & F0) | (84 >> 4) = E0 | 08 = 0xE8
uuid[9]  = ((E7 & 0F) << 4) | (84 & 0F) = 70 | 04 = 0x74
uuid[10] = (C3 & F0) | (C6 >> 4) = C0 | 0C = 0xCC
uuid[11] = ((C3 & 0F) << 4) | (C6 & 0F) = 30 | 06 = 0x36
```

#### Vòng i=3

```
Inputs: hashBytes[3] = 0x2C    timeBytes[3] = 0xA6
        random[6]    = 0x5D    random[7]    = 0xE8

uuid[12] = (2C & F0) | (5D >> 4) = 20 | 05 = 0x25
uuid[13] = ((2C & 0F) << 4) | (5D & 0F) = C0 | 0D = 0xCD
uuid[14] = (A6 & F0) | (E8 >> 4) = A0 | 0E = 0xAE
uuid[15] = ((A6 & 0F) << 4) | (E8 & 0F) = 60 | 08 = 0x68
```

---

### Step 9: Format UUID string

```
uuid[16] = { BA, 97, 63, 9B, AF, 32, D1, 59, E8, 74, CC, 36, 25, CD, AE, 68 }

Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        BA97639B-AF32-D159-E874-CC3625CDAE68

→ "ba97639b-af32-d159-e874-cc3625cdae68"
```

---

## Bản đồ UUID — Nibble nào từ đâu?

```
UUID:     B A 9 7 6 3 9 B - A F 3 2 - D 1 5 9 - E 8 7 4 - C C 3 6 2 5 C D A E 6 8
          ─ ─ ─ ─ ─ ─ ─ ─   ─ ─ ─ ─   ─ ─ ─ ─   ─ ─ ─ ─   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
Nguồn:    H R H R T R T R   H R H R   T R T R   H R H R   T R T R H R H R T R T R
          │       │           │         │         │         │
          └hash   └time       └hash     └time     └hash     └time

H = CRC32 hash (device fingerprint)    — 8 nibbles total
T = timestamp (seconds)                — 8 nibbles total
R = SecureRandom                       — 16 nibbles total
```

---

## Server có thể extract gì từ UUID?

### Extract hashValue (device fingerprint)

```
Từ uuid[16], lấy high nibble tại vị trí 0,1,4,5,8,9,12,13:

uuid[0]=BA  → high=B     uuid[1]=97  → high=9     → hashBytes[0] = 0xB9
uuid[4]=AF  → high=A     uuid[5]=32  → high=3     → hashBytes[1] = 0xA3
uuid[8]=E8  → high=E     uuid[9]=74  → high=7     → hashBytes[2] = 0xE7
uuid[12]=25 → high=2     uuid[13]=CD → high=C     → hashBytes[3] = 0x2C

→ hashValue = 0xB9A3E72C
→ Server tự tính CRC32(CRC32(seed, challenge), deviceInfo) và so sánh
```

### Extract timestamp

```
Lấy high nibble tại vị trí 2,3,6,7,10,11,14,15:

uuid[2]=63  → 6     uuid[3]=9B  → 9     → timeBytes[0] = 0x69
uuid[6]=D1  → D     uuid[7]=59  → 5     → timeBytes[1] = 0xD5
uuid[10]=CC → C     uuid[11]=36 → 3     → timeBytes[2] = 0xC3
uuid[14]=AE → A     uuid[15]=68 → 6     → timeBytes[3] = 0xA6

→ timeSec = 0x69D5C3A6 = 1774988630
→ 2026-03-30 15:23:50 UTC
→ Server kiểm tra: request quá cũ? → reject
```

---

## Server Validation Flow

Client gửi lên server 3 thứ:

```
Request gồm:
  1. challengeData   ← Widevine license request body
  2. deviceInfo      ← JSON thông tin device
  3. requestId       ← UUID đã generate
```

Server validate theo thứ tự **từ rẻ → đắt:**

### Bước 1: Extract data từ UUID

```
Từ UUID 16 bytes:
  - random[8]    ← lấy tất cả 16 low nibbles, ghép lại thành 8 bytes
  - hashValue    ← lấy high nibbles tại vị trí 0,1,4,5,8,9,12,13
  - timestamp    ← lấy high nibbles tại vị trí 2,3,6,7,10,11,14,15
```

### Bước 2: Check bit 0 (cheap filter)

```
idx = random[0] >> 5
random[idx] & 0x01 == 0?  → OK, tiếp tục
                    != 0? → REJECT ngay
```

Chỉ cần 2 phép bitwise. ~50% request random/sai format bị loại ngay tại đây.

### Bước 3: Check timestamp (freshness)

```
timeSec = extract từ UUID
now = server time

abs(now - timeSec) > threshold?  → REJECT (request quá cũ hoặc timestamp giả)
```

### Bước 4: Verify hash (integrity check — đắt nhất)

Server tính lại **cùng thuật toán, cùng input** như client:

```
Client:                          Server:

1. Tạo random 8 bytes            1. Extract random từ UUID (low nibbles)
2. Clear bit 0                   2. Check bit 0 (đã pass ở bước 2)
3. seed = random[0..3]           3. seed = random[0..3]  ← cùng data
4. CRC32(seed, challenge)        4. CRC32(seed, challenge)
5. CRC32(result, deviceInfo)     5. CRC32(result, deviceInfo)
6. → hashValue                   6. → expected hash
7. Nhúng vào UUID → gửi lên     7. So sánh: expected == extracted?
```

```
expected == hashValue?  → OK, cấp license
expected != hashValue?  → REJECT
```

**Tại sao server tính lại được?** Vì nó có đủ input:
- **random bytes** → extract từ UUID
- **challengeData** → client gửi kèm request
- **deviceInfo** → client gửi kèm request

Nếu ai đó sửa `deviceInfo` hoặc `challengeData` mà giữ nguyên `requestId` → hash không khớp → reject. Nên `requestId` đóng vai trò như **chữ ký** gắn 3 thứ lại với nhau — đảm bảo không ai tamper được.

---

## Tổng kết

| Step | Input | Output | Mục đích |
|------|-------|--------|----------|
| 1 | - | 8 random bytes | Nguồn entropy, tạo uniqueness |
| 2 | random[0] | Clear 1 bit | Server validation flag |
| 3 | random[0..3] | seed (uint32) | Khởi tạo CRC chain |
| 4 | seed + challenge | new seed | Gắn requestId với challenge cụ thể |
| 5 | seed + deviceInfo | hashValue (4 bytes) | Fingerprint device trong UUID |
| 6 | system clock | timeSec (4 bytes) | Timestamp trong UUID |
| 7 | hashValue, timeSec | byte arrays | Chuẩn bị cho interleaving |
| 8 | hash + time + random | uuid[16] | Ghép 3 nguồn bằng nibble interleaving |
| 9 | uuid[16] | "xxxxxxxx-xxxx-..." | Format UUID string |

**Kết quả:** 1 UUID 36 ký tự chứa đồng thời:
- **Device fingerprint** (hash) — server verify device
- **Timestamp** — server check freshness
- **Randomness** — đảm bảo unique, chống replay