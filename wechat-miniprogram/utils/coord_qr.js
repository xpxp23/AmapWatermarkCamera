/**
 * 极速高德水印相机 v1.0 · 微信小程序三坐标系（默认 WGS-84 / 国标 CGCS2000 / 高德 GCJ-02）双向解耦引擎、
 * ISO/IEC 18004 V1-V14 全容量 8 掩码择优离线二维码引擎与 FIPS 180-4 SHA-256 外业防篡改验真引擎
 */

const PI = Math.PI;
const AXIS = 6378245.0;
const OFFSET_EE = 0.00669342162296594323;
const ANTI_TAMPER_SALT = 'AMAP_WM_V7_TRUST_ANCHOR_2026';

// 默认未定位占位坐标（天安门广场），防伪模式下严禁对此默认坐标签发防伪哈希
const DEFAULT_FALLBACK_LAT = 39.908823;
const DEFAULT_FALLBACK_LON = 116.397470;

function outOfChina(lat, lon) {
  return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((y / 12.0) * PI) + 320 * Math.sin((y * PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}

function transformLon(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((x / 12.0) * PI) + 300.0 * Math.sin((x / 30.0) * PI)) * 2.0) / 3.0;
  return ret;
}

function wgs84ToGcj02(wgsLat, wgsLon) {
  const lat = Number(wgsLat);
  const lon = Number(wgsLon);
  if (outOfChina(lat, lon)) return { lat: Number(lat.toFixed(6)), lon: Number(lon.toFixed(6)) };
  let dLat = transformLat(lon - 105.0, lat - 35.0);
  let dLon = transformLon(lon - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - OFFSET_EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((AXIS * (1 - OFFSET_EE)) / (magic * sqrtMagic)) * PI);
  dLon = (dLon * 180.0) / ((AXIS / sqrtMagic) * Math.cos(radLat) * PI);
  return {
    lat: Number((lat + dLat).toFixed(6)),
    lon: Number((lon + dLon).toFixed(6))
  };
}

function gcj02ToWgs84(gcjLat, gcjLon) {
  const lat = Number(gcjLat);
  const lon = Number(gcjLon);
  if (outOfChina(lat, lon)) return { lat: Number(lat.toFixed(6)), lon: Number(lon.toFixed(6)) };
  let wgsLat = lat;
  let wgsLon = lon;
  for (let i = 0; i < 6; i++) {
    const curr = wgs84ToGcj02(wgsLat, wgsLon);
    wgsLat += lat - curr.lat;
    wgsLon += lon - curr.lon;
  }
  return {
    lat: Number(wgsLat.toFixed(6)),
    lon: Number(wgsLon.toFixed(6))
  };
}

function distanceMeters(lat1, lon1, lat2, lon2) {
  const r = 6371000.0;
  const dLat = ((lat2 - lat1) * PI) / 180.0;
  const dLon = ((lon2 - lon1) * PI) / 180.0;
  const a =
    Math.pow(Math.sin(dLat / 2.0), 2) +
    Math.cos((lat1 * PI) / 180.0) * Math.cos((lat2 * PI) / 180.0) * Math.pow(Math.sin(dLon / 2.0), 2);
  return r * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
}

function isDefaultFallbackCoord(lat, lon) {
  return (
    Math.abs(Number(lat) - DEFAULT_FALLBACK_LAT) < 0.00001 &&
    Math.abs(Number(lon) - DEFAULT_FALLBACK_LON) < 0.00001
  );
}

/**
 * 校验当前是否具备防伪签发资格：必须已获得真实定位且非默认占位坐标
 */
function canSignAntiTamper(hasRealFix, gcjLat, gcjLon) {
  if (!hasRealFix) return false;
  const lat = Number(gcjLat);
  const lon = Number(gcjLon);
  if (isNaN(lat) || isNaN(lon)) return false;
  if (lat === 0 && lon === 0) return false;
  if (isDefaultFallbackCoord(lat, lon)) return false;
  return true;
}

function resolveDisplayCoordinates(coordSys, gcjLat, gcjLon, wgsLat, wgsLon) {
  if (coordSys === 'GCJ-02') {
    return {
      lat: Number(Number(gcjLat).toFixed(6)),
      lon: Number(Number(gcjLon).toFixed(6)),
      tag: 'GCJ-02'
    };
  }
  const cleanWgs =
    wgsLat !== undefined && wgsLon !== undefined
      ? { lat: Number(Number(wgsLat).toFixed(6)), lon: Number(Number(wgsLon).toFixed(6)) }
      : gcj02ToWgs84(gcjLat, gcjLon);
  return {
    lat: cleanWgs.lat,
    lon: cleanWgs.lon,
    tag: coordSys === 'CGCS2000' ? 'CGCS2000' : 'WGS-84'
  };
}

function toDMS(deg, isLat) {
  const dir = isLat ? (deg >= 0 ? 'N' : 'S') : (deg >= 0 ? 'E' : 'W');
  const totalCentis = Math.round(Math.abs(Number(deg)) * 360000);
  const d = Math.floor(totalCentis / 360000);
  const rem = totalCentis % 360000;
  const m = Math.floor(rem / 6000);
  const s = ((rem % 6000) / 100).toFixed(2);
  const mStr = m < 10 ? '0' + m : '' + m;
  const sStr = Number(s) < 10 ? '0' + s : '' + s;
  return `${d}°${mStr}'${sStr}"${dir}`;
}

function formatCoords(lat, lon, useDms) {
  if (useDms) {
    return `${toDMS(lat, true)}, ${toDMS(lon, false)}`;
  }
  const ns = lat >= 0 ? 'N' : 'S';
  const ew = lon >= 0 ? 'E' : 'W';
  return `${Math.abs(Number(lat)).toFixed(6)}°${ns}, ${Math.abs(Number(lon)).toFixed(6)}°${ew}`;
}

function formatAzimuthText(deg) {
  const d = ((Math.round(Number(deg) || 0) % 360) + 360) % 360;
  let dir = '西北';
  if (d <= 10 || d >= 350) dir = '正北';
  else if (d < 80) dir = '东北';
  else if (d <= 100) dir = '正东';
  else if (d < 170) dir = '东南';
  else if (d <= 190) dir = '正南';
  else if (d < 260) dir = '西南';
  else if (d <= 280) dir = '正西';
  const pad3 = d < 10 ? '00' + d : d < 100 ? '0' + d : '' + d;
  return `${dir} ${pad3}°`;
}

function formatTimeFromDate(dateObj) {
  const d = dateObj instanceof Date && !isNaN(dateObj.getTime()) ? dateObj : new Date();
  const pad = n => (n < 10 ? '0' + n : '' + n);
  const weeks = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const hms = `${hm}:${pad(d.getSeconds())}`;
  const datePart = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const weekStr = weeks[d.getDay()];
  const dateWeek = `${datePart} ${weekStr}`;
  const fullTs = `${datePart} ${hms}`;
  return { hm, hms, datePart, weekStr, dateWeek, fullTs };
}

function getTimeStrings(offsetMs) {
  return formatTimeFromDate(new Date(Date.now() + (Number(offsetMs) || 0)));
}

function stringToUtf8Bytes(str) {
  const utf8 = [];
  for (let i = 0; i < str.length; i++) {
    let charcode = str.charCodeAt(i);
    if (charcode < 0x80) utf8.push(charcode);
    else if (charcode < 0x800) {
      utf8.push(0xc0 | (charcode >> 6), 0x80 | (charcode & 0x3f));
    } else if (charcode < 0xd800 || charcode >= 0xe000) {
      utf8.push(0xe0 | (charcode >> 12), 0x80 | ((charcode >> 6) & 0x3f), 0x80 | (charcode & 0x3f));
    } else {
      i++;
      charcode = 0x10000 + (((charcode & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
      utf8.push(
        0xf0 | (charcode >> 18),
        0x80 | ((charcode >> 12) & 0x3f),
        0x80 | ((charcode >> 6) & 0x3f),
        0x80 | (charcode & 0x3f)
      );
    }
  }
  return utf8;
}

/**
 * 纯离线 FIPS 180-4 SHA-256 哈希算法实现
 */
const SHA256_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];

function rotr(n, x) {
  return (x >>> n) | (x << (32 - n));
}

function sha256Hex(message) {
  const bytes = stringToUtf8Bytes(String(message));
  const bitLen = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  const hi = Math.floor(bitLen / 0x100000000);
  const lo = bitLen >>> 0;
  for (let i = 3; i >= 0; i--) bytes.push((hi >>> (i * 8)) & 0xff);
  for (let i = 3; i >= 0; i--) bytes.push((lo >>> (i * 8)) & 0xff);

  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
  const w = new Array(64);

  for (let off = 0; off < bytes.length; off += 64) {
    for (let i = 0; i < 16; i++) {
      const j = off + i * 4;
      w[i] = ((bytes[j] << 24) | (bytes[j + 1] << 16) | (bytes[j + 2] << 8) | bytes[j + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const s0 = (rotr(7, w[i - 15]) ^ rotr(18, w[i - 15]) ^ (w[i - 15] >>> 3)) >>> 0;
      const s1 = (rotr(17, w[i - 2]) ^ rotr(19, w[i - 2]) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
    for (let i = 0; i < 64; i++) {
      const S1 = (rotr(6, e) ^ rotr(11, e) ^ rotr(25, e)) >>> 0;
      const ch = ((e & f) ^ (~e & g)) >>> 0;
      const temp1 = (h + S1 + ch + SHA256_K[i] + w[i]) >>> 0;
      const S0 = (rotr(2, a) ^ rotr(13, a) ^ rotr(22, a)) >>> 0;
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const temp2 = (S0 + maj) >>> 0;
      h = g; g = f; f = e;
      e = (d + temp1) >>> 0;
      d = c; c = b; b = a;
      a = (temp1 + temp2) >>> 0;
    }
    h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0;
  }

  const toHex = n => ('00000000' + (n >>> 0).toString(16)).slice(-8);
  return (toHex(h0) + toHex(h1) + toHex(h2) + toHex(h3) + toHex(h4) + toHex(h5) + toHex(h6) + toHex(h7)).toUpperCase();
}

/**
 * 计算外业防篡改签名摘要 (16位大写 SHA-256 截断验真码)
 */
function computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, note) {
  const latStr = Number(gcjLat || 0).toFixed(6);
  const lonStr = Number(gcjLon || 0).toFixed(6);
  const tsClean = String(timestamp || '').trim();
  const poiClean = String(shortPoi || '实拍点位').trim().slice(0, 10);
  const noteClean = String(note || '').trim().slice(0, 12);
  const raw = noteClean
    ? `${latStr}|${lonStr}|${tsClean}|${poiClean}|${noteClean}|${ANTI_TAMPER_SALT}`
    : `${latStr}|${lonStr}|${tsClean}|${poiClean}|${ANTI_TAMPER_SALT}`;
  return sha256Hex(raw).slice(0, 16);
}

/**
 * 严格验真：强制要求 sig.length >= 10 且仅接受十六进制字符，杜绝短签名绕过
 */
function verifyAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, signature, note) {
  const cleanSig = String(signature || '').toUpperCase().trim();
  if (!cleanSig || cleanSig.length < 10 || !/^[0-9A-F]{10,16}$/.test(cleanSig)) {
    return false;
  }
  const expectedWithNote = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, note);
  if (expectedWithNote.startsWith(cleanSig)) return true;
  // 向下兼容未附带 note 字段的 v7.x 防伪二维码
  const expectedLegacy = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, '');
  return expectedLegacy.startsWith(cleanSig);
}

/**
 * ISO/IEC 18004 QR Code Matrix Engine (Version 1 ~ Version 14, Level L)
 * 支持多分组 RS 交错编码 + 8 掩码惩罚评分择优 + LRU 缓存
 */
const QR_SPECS = [
  { ver: 1, size: 21, dataBytes: 19, eccBytes: 7, g1Count: 1, g1Data: 19, g2Count: 0, g2Data: 0, align: [] },
  { ver: 2, size: 25, dataBytes: 34, eccBytes: 10, g1Count: 1, g1Data: 34, g2Count: 0, g2Data: 0, align: [6, 18] },
  { ver: 3, size: 29, dataBytes: 55, eccBytes: 15, g1Count: 1, g1Data: 55, g2Count: 0, g2Data: 0, align: [6, 22] },
  { ver: 4, size: 33, dataBytes: 80, eccBytes: 20, g1Count: 1, g1Data: 80, g2Count: 0, g2Data: 0, align: [6, 26] },
  { ver: 5, size: 37, dataBytes: 108, eccBytes: 26, g1Count: 1, g1Data: 108, g2Count: 0, g2Data: 0, align: [6, 30] },
  { ver: 6, size: 41, dataBytes: 136, eccBytes: 18, g1Count: 2, g1Data: 68, g2Count: 0, g2Data: 0, align: [6, 34] },
  { ver: 7, size: 45, dataBytes: 156, eccBytes: 20, g1Count: 2, g1Data: 78, g2Count: 0, g2Data: 0, align: [6, 22, 38] },
  { ver: 8, size: 49, dataBytes: 194, eccBytes: 24, g1Count: 2, g1Data: 97, g2Count: 0, g2Data: 0, align: [6, 24, 42] },
  { ver: 9, size: 53, dataBytes: 232, eccBytes: 30, g1Count: 2, g1Data: 116, g2Count: 0, g2Data: 0, align: [6, 26, 46] },
  { ver: 10, size: 57, dataBytes: 274, eccBytes: 18, g1Count: 2, g1Data: 68, g2Count: 2, g2Data: 69, align: [6, 28, 50] },
  { ver: 11, size: 61, dataBytes: 324, eccBytes: 20, g1Count: 4, g1Data: 81, g2Count: 0, g2Data: 0, align: [6, 30, 54] },
  { ver: 12, size: 65, dataBytes: 370, eccBytes: 24, g1Count: 2, g1Data: 92, g2Count: 2, g2Data: 93, align: [6, 32, 58] },
  { ver: 13, size: 69, dataBytes: 428, eccBytes: 26, g1Count: 4, g1Data: 107, g2Count: 0, g2Data: 0, align: [6, 34, 62] },
  { ver: 14, size: 73, dataBytes: 461, eccBytes: 30, g1Count: 3, g1Data: 115, g2Count: 1, g2Data: 116, align: [6, 26, 46, 66] }
];

const QR_VER_INFO = {
  7: 0x07c94,
  8: 0x085bc,
  9: 0x09a99,
  10: 0x0a4d3,
  11: 0x0bbf6,
  12: 0x0c762,
  13: 0x0d847,
  14: 0x0e60d
};

// Level L (01) 对应 Mask 0..7 的 15-bit BCH 格式信息
const QR_FORMAT_INFO_L = [
  0x77c4, // mask 0
  0x72f3, // mask 1
  0x7daa, // mask 2
  0x789d, // mask 3
  0x662f, // mask 4
  0x6318, // mask 5
  0x6c41, // mask 6
  0x6976  // mask 7
];

const _qrMatrixCache = {};
const _qrCacheKeys = [];

function gfMul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function rsEncode(data, deg) {
  const gen = new Array(deg).fill(0);
  gen[deg - 1] = 1;
  let root = 1;
  for (let i = 0; i < deg; i++) {
    for (let j = 0; j < deg; j++) {
      gen[j] = gfMul(gen[j], root);
      if (j + 1 < deg) gen[j] ^= gen[j + 1];
    }
    root = gfMul(root, 2);
  }
  const rem = new Array(deg).fill(0);
  for (let k = 0; k < data.length; k++) {
    const factor = data[k] ^ rem[0];
    rem.shift();
    rem.push(0);
    for (let j = 0; j < deg; j++) rem[j] ^= gfMul(gen[j], factor);
  }
  return rem;
}

function safeUtf8Slice(utf8Bytes, maxLen) {
  if (utf8Bytes.length <= maxLen) return utf8Bytes;
  let cut = maxLen;
  while (cut > 0 && (utf8Bytes[cut] & 0xc0) === 0x80) {
    cut--;
  }
  return utf8Bytes.slice(0, cut);
}

function maskCondition(maskId, x, y) {
  switch (maskId) {
    case 0: return (x + y) % 2 === 0;
    case 1: return y % 2 === 0;
    case 2: return x % 3 === 0;
    case 3: return (x + y) % 3 === 0;
    case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0;
    case 5: return ((x * y) % 2) + ((x * y) % 3) === 0;
    case 6: return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
    case 7: return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
    default: return false;
  }
}

function writeFormatBits(mat, sz, fmt) {
  const bit = i => ((fmt >>> (14 - i)) & 1) !== 0;
  for (let i = 0; i <= 5; i++) mat[8][i] = bit(i);
  mat[8][7] = bit(6);
  mat[8][8] = bit(7);
  mat[7][8] = bit(8);
  for (let i = 9; i <= 14; i++) mat[14 - i][8] = bit(i);
  for (let i = 0; i <= 7; i++) mat[sz - 1 - i][8] = bit(i);
  for (let i = 8; i <= 14; i++) mat[8][sz - 15 + i] = bit(i);
  mat[sz - 8][8] = true;
}

function computeMaskPenalty(mat, sz) {
  let penalty = 0;

  // Rule 1: 连续 5 个及以上同色模块
  for (let y = 0; y < sz; y++) {
    let runColor = mat[y][0];
    let runLen = 1;
    for (let x = 1; x < sz; x++) {
      if (mat[y][x] === runColor) {
        runLen++;
      } else {
        if (runLen >= 5) penalty += 3 + (runLen - 5);
        runColor = mat[y][x];
        runLen = 1;
      }
    }
    if (runLen >= 5) penalty += 3 + (runLen - 5);
  }
  for (let x = 0; x < sz; x++) {
    let runColor = mat[0][x];
    let runLen = 1;
    for (let y = 1; y < sz; y++) {
      if (mat[y][x] === runColor) {
        runLen++;
      } else {
        if (runLen >= 5) penalty += 3 + (runLen - 5);
        runColor = mat[y][x];
        runLen = 1;
      }
    }
    if (runLen >= 5) penalty += 3 + (runLen - 5);
  }

  // Rule 2: 2x2 同色块
  for (let y = 0; y < sz - 1; y++) {
    for (let x = 0; x < sz - 1; x++) {
      const c = mat[y][x];
      if (c === mat[y][x + 1] && c === mat[y + 1][x] && c === mat[y + 1][x + 1]) {
        penalty += 3;
      }
    }
  }

  // Rule 3: 类似定位符的 1:1:3:1:1 序列
  const checkFinderLike = (arr, len) => {
    let count = 0;
    for (let i = 0; i <= len - 11; i++) {
      // 10111010000 or 00001011101
      if (
        arr[i] && !arr[i + 1] && arr[i + 2] && arr[i + 3] && arr[i + 4] && !arr[i + 5] && arr[i + 6] &&
        !arr[i + 7] && !arr[i + 8] && !arr[i + 9] && !arr[i + 10]
      ) {
        count++;
      } else if (
        !arr[i] && !arr[i + 1] && !arr[i + 2] && !arr[i + 3] &&
        arr[i + 4] && !arr[i + 5] && arr[i + 6] && arr[i + 7] && arr[i + 8] && !arr[i + 9] && arr[i + 10]
      ) {
        count++;
      }
    }
    return count * 40;
  };
  for (let y = 0; y < sz; y++) {
    penalty += checkFinderLike(mat[y], sz);
  }
  const colBuf = new Array(sz);
  for (let x = 0; x < sz; x++) {
    for (let y = 0; y < sz; y++) colBuf[y] = mat[y][x];
    penalty += checkFinderLike(colBuf, sz);
  }

  // Rule 4: 深色模块占比偏离 50%
  let darkCount = 0;
  for (let y = 0; y < sz; y++) {
    for (let x = 0; x < sz; x++) {
      if (mat[y][x]) darkCount++;
    }
  }
  const total = sz * sz;
  const pct = (darkCount * 100) / total;
  const prev5 = Math.floor(pct / 5) * 5;
  const next5 = prev5 + 5;
  const k = Math.min(Math.abs(prev5 - 50), Math.abs(next5 - 50)) / 5;
  penalty += k * 10;

  return penalty;
}

function encodeQrMatrix(text) {
  const key = String(text || '');
  if (_qrMatrixCache[key]) return _qrMatrixCache[key];

  const utf8 = stringToUtf8Bytes(key);
  const spec =
    QR_SPECS.find(s => utf8.length + (s.ver >= 10 ? 4 : 3) <= s.dataBytes) ||
    QR_SPECS[QR_SPECS.length - 1];
  const headerReserve = spec.ver >= 10 ? 4 : 3;
  const bytes = safeUtf8Slice(utf8, spec.dataBytes - headerReserve);

  const bits = [];
  const pushBits = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  // Byte mode indicator (0100)
  pushBits(4, 4);
  // Character count indicator: 8 bits for V1-V9, 16 bits for V10-V26
  pushBits(bytes.length, spec.ver >= 10 ? 16 : 8);
  bytes.forEach(b => pushBits(b, 8));

  const maxBits = spec.dataBytes * 8;
  pushBits(0, Math.min(4, maxBits - bits.length));
  while (bits.length % 8 !== 0) bits.push(0);
  const pads = [0xec, 0x11];
  let pi = 0;
  while (bits.length < maxBits) pushBits(pads[pi++ % 2], 8);

  const dataCw = [];
  for (let i = 0; i < spec.dataBytes; i++) {
    let v = 0;
    for (let b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
    dataCw.push(v);
  }

  // Multi-group RS block encoding & interleaving
  const blocks = [];
  const eccBlocks = [];
  let offset = 0;
  for (let b = 0; b < spec.g1Count; b++) {
    const blk = dataCw.slice(offset, offset + spec.g1Data);
    offset += spec.g1Data;
    blocks.push(blk);
    eccBlocks.push(rsEncode(blk, spec.eccBytes));
  }
  for (let b = 0; b < spec.g2Count; b++) {
    const blk = dataCw.slice(offset, offset + spec.g2Data);
    offset += spec.g2Data;
    blocks.push(blk);
    eccBlocks.push(rsEncode(blk, spec.eccBytes));
  }

  const allCw = [];
  const maxBlockData = Math.max(spec.g1Data, spec.g2Data);
  for (let i = 0; i < maxBlockData; i++) {
    for (let b = 0; b < blocks.length; b++) {
      if (i < blocks[b].length) allCw.push(blocks[b][i]);
    }
  }
  for (let i = 0; i < spec.eccBytes; i++) {
    for (let b = 0; b < eccBlocks.length; b++) {
      allCw.push(eccBlocks[b][i]);
    }
  }

  const sz = spec.size;
  const baseMat = Array.from({ length: sz }, () => new Array(sz).fill(false));
  const fn = Array.from({ length: sz }, () => new Array(sz).fill(false));
  const setFn = (x, y, d) => {
    if (x >= 0 && x < sz && y >= 0 && y < sz) {
      baseMat[y][x] = d;
      fn[y][x] = true;
    }
  };
  const finder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        setFn(cx + dx, cy + dy, dist !== 2 && dist !== 4);
      }
    }
  };
  finder(3, 3);
  finder(sz - 4, 3);
  finder(3, sz - 4);
  for (let i = 0; i < sz; i++) {
    if (!fn[6][i]) setFn(i, 6, i % 2 === 0);
    if (!fn[i][6]) setFn(6, i, i % 2 === 0);
  }
  for (let a = 0; a < spec.align.length; a++) {
    for (let b = 0; b < spec.align.length; b++) {
      const ay = spec.align[a];
      const ax = spec.align[b];
      if ((ax === 6 && ay === 6) || (ax === 6 && ay === sz - 7) || (ax === sz - 7 && ay === 6)) continue;
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          setFn(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
  }
  for (let i = 0; i <= 8; i++) {
    if (i !== 6) {
      fn[8][i] = true;
      fn[i][8] = true;
    }
  }
  for (let i = 0; i <= 7; i++) {
    fn[8][sz - 1 - i] = true;
    fn[sz - 1 - i][8] = true;
  }
  setFn(8, sz - 8, true);

  if (spec.ver >= 7 && QR_VER_INFO[spec.ver]) {
    const vbits = QR_VER_INFO[spec.ver];
    for (let i = 0; i < 6; i++) {
      for (let j = 0; j < 3; j++) {
        const b = ((vbits >>> (i * 3 + j)) & 1) !== 0;
        setFn(i, sz - 11 + j, b);
        setFn(sz - 11 + j, i, b);
      }
    }
  }

  // Place unmasked data bits
  let bitIdx = 0;
  let right = sz - 1;
  let vert = 0;
  while (right >= 1) {
    if (right === 6) right = 5;
    for (let c = 0; c < sz; c++) {
      const y = (vert & 1) === 0 ? sz - 1 - c : c;
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        if (!fn[y][x]) {
          const dark =
            bitIdx < allCw.length * 8 ? ((allCw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) !== 0 : false;
          baseMat[y][x] = dark;
          bitIdx++;
        }
      }
    }
    right -= 2;
    vert++;
  }

  // 评估全部 8 种掩码，择优选择惩罚分数最低的掩码矩阵
  let bestMat = null;
  let bestPenalty = Infinity;
  for (let maskId = 0; maskId < 8; maskId++) {
    const cand = Array.from({ length: sz }, (_, y) => {
      const row = new Array(sz);
      for (let x = 0; x < sz; x++) {
        row[x] = !fn[y][x] && maskCondition(maskId, x, y) ? !baseMat[y][x] : baseMat[y][x];
      }
      return row;
    });
    writeFormatBits(cand, sz, QR_FORMAT_INFO_L[maskId]);
    const p = computeMaskPenalty(cand, sz);
    if (p < bestPenalty) {
      bestPenalty = p;
      bestMat = cand;
    }
  }

  if (_qrCacheKeys.length >= 36) {
    const oldest = _qrCacheKeys.shift();
    delete _qrMatrixCache[oldest];
  }
  _qrCacheKeys.push(key);
  _qrMatrixCache[key] = bestMat;
  return bestMat;
}

/**
 * 构建高德零偏差跳转 URL（恒定使用 GCJ-02 坐标）
 */
function buildAmapJumpUrl(gcjLat, gcjLon, shortPoi, antiTamperOpts) {
  const latStr = Number(gcjLat).toFixed(6);
  const lonStr = Number(gcjLon).toFixed(6);
  const rawPoi = String(shortPoi || '实拍点位').trim().slice(0, 10);
  const cleanPoi = encodeURIComponent(rawPoi);
  let url = `https://uri.amap.com/marker?position=${lonStr},${latStr}&name=${cleanPoi}&coordinate=gaode&callnative=1`;
  if (antiTamperOpts && antiTamperOpts.enabled) {
    const ts = String(antiTamperOpts.timestamp || '').trim();
    const noteClean = String(antiTamperOpts.note || '').trim().slice(0, 12);
    const sig = antiTamperOpts.signature || computeAntiTamperHash(gcjLat, gcjLon, ts, rawPoi, noteClean);
    const ntp = antiTamperOpts.ntpSynced ? 1 : 0;
    url += `&ts=${encodeURIComponent(ts)}&sig=${sig.slice(0, 12)}&ntp=${ntp}`;
    if (noteClean) {
      url += `&note=${encodeURIComponent(noteClean)}`;
    }
  }
  return url;
}

/**
 * 解析扫码结果、链接二维码参数或好友转发卡片参数，并严格执行 SHA-256 防伪核验
 */
function parseLocationPayload(rawInput) {
  if (!rawInput) return null;
  const origStr = String(rawInput).trim();
  let decodedStr = origStr;
  try {
    decodedStr = decodeURIComponent(origStr);
  } catch (e) {}

  const extractParam = (s, key) => {
    const reg = new RegExp('[?&]' + key + '=([^&#]+)', 'i');
    const m = s.match(reg);
    if (!m) return '';
    try {
      return decodeURIComponent(m[1]);
    } catch (e) {
      return m[1];
    }
  };

  // 1. Check position=lon,lat
  const posMatch =
    origStr.match(/position=([0-9.+-]+),([0-9.+-]+)/i) ||
    decodedStr.match(/position=([0-9.+-]+),([0-9.+-]+)/i);
  if (posMatch) {
    const lon = parseFloat(posMatch[1]);
    const lat = parseFloat(posMatch[2]);
    const poi = extractParam(origStr, 'name') || extractParam(decodedStr, 'name') || '实拍点位';
    const ts = extractParam(origStr, 'ts') || extractParam(decodedStr, 'ts');
    const sig = extractParam(origStr, 'sig') || extractParam(decodedStr, 'sig');
    const note = extractParam(origStr, 'note') || extractParam(decodedStr, 'note') || '';
    const ntp = (extractParam(origStr, 'ntp') || extractParam(decodedStr, 'ntp')) === '1';
    if (!isNaN(lat) && !isNaN(lon)) {
      const gcjLat = Number(lat.toFixed(6));
      const gcjLon = Number(lon.toFixed(6));
      const verified = sig ? verifyAntiTamperHash(gcjLat, gcjLon, ts, poi, sig, note) : false;
      return {
        gcjLat,
        gcjLon,
        shortPoi: poi,
        timestamp: ts,
        signature: sig,
        note,
        ntpSynced: ntp,
        hasAntiTamper: Boolean(sig),
        verified
      };
    }
  }

  // 2. Check lat=...&lon=...
  const latMatch = origStr.match(/[?&]lat=([0-9.+-]+)/i) || decodedStr.match(/[?&]lat=([0-9.+-]+)/i);
  const lonMatch = origStr.match(/[?&]lon=([0-9.+-]+)/i) || decodedStr.match(/[?&]lon=([0-9.+-]+)/i);
  if (latMatch && lonMatch) {
    const lat = parseFloat(latMatch[1]);
    const lon = parseFloat(lonMatch[1]);
    const poi = extractParam(origStr, 'poi') || extractParam(decodedStr, 'poi') || '实拍点位';
    const ts = extractParam(origStr, 'ts') || extractParam(decodedStr, 'ts');
    const sig = extractParam(origStr, 'sig') || extractParam(decodedStr, 'sig');
    const note = extractParam(origStr, 'note') || extractParam(decodedStr, 'note') || '';
    const ntp = (extractParam(origStr, 'ntp') || extractParam(decodedStr, 'ntp')) === '1';
    if (!isNaN(lat) && !isNaN(lon)) {
      const gcjLat = Number(lat.toFixed(6));
      const gcjLon = Number(lon.toFixed(6));
      const verified = sig ? verifyAntiTamperHash(gcjLat, gcjLon, ts, poi, sig, note) : false;
      return {
        gcjLat,
        gcjLon,
        shortPoi: poi,
        timestamp: ts,
        signature: sig,
        note,
        ntpSynced: ntp,
        hasAntiTamper: Boolean(sig),
        verified
      };
    }
  }

  // 3. Fallback pair of decimals
  const nums = decodedStr.match(/([0-9]{1,3}\.[0-9]{3,8})/g);
  if (nums && nums.length >= 2) {
    const a = parseFloat(nums[0]);
    const b = parseFloat(nums[1]);
    const isALon = a > 65 && b < 60;
    const gcjLon = isALon ? a : b;
    const gcjLat = isALon ? b : a;
    return {
      gcjLat: Number(gcjLat.toFixed(6)),
      gcjLon: Number(gcjLon.toFixed(6)),
      shortPoi: '实拍点位',
      hasAntiTamper: false,
      verified: false
    };
  }
  return null;
}

function inferSmartPoiFromCoords() {
  // 真实周边 POI 由 utils/poi_service.js 通过云函数逆地理编码动态获取；
  // 此函数仅作为纯离线兜底快捷词，不再使用 10 城市 55km 粗粒度假地名误导用户
  return {
    shortPoi: '实拍点位',
    fullAddr: '',
    nearbyPois: [
      '实拍点位',
      '施工现场',
      '1号巡检点',
      '隐蔽工程验收点',
      '外业打卡现场'
    ]
  };
}

module.exports = {
  DEFAULT_FALLBACK_LAT,
  DEFAULT_FALLBACK_LON,
  wgs84ToGcj02,
  gcj02ToWgs84,
  distanceMeters,
  isDefaultFallbackCoord,
  canSignAntiTamper,
  resolveDisplayCoordinates,
  formatCoords,
  formatAzimuthText,
  formatTimeFromDate,
  getTimeStrings,
  sha256Hex,
  computeAntiTamperHash,
  verifyAntiTamperHash,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload,
  inferSmartPoiFromCoords
};
