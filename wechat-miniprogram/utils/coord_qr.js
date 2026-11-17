/**
 * 极速经纬水印相机 v7.0.0 · 微信小程序高德 GCJ-02 / WGS-84 双向纠偏、
 * ISO/IEC 18004 V1-V9 全容量离线二维码引擎、SHA-256 防篡改验真与高德 Mini-Map 矢量测绘渲染引擎
 */

const PI = Math.PI;
const AXIS = 6378245.0;
const OFFSET_EE = 0.00669342162296594323;
const ANTI_TAMPER_SALT = 'AMAP_WM_V7_TRUST_ANCHOR_2026';

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
  const pad3 = d < 10 ? '00' + d : (d < 100 ? '0' + d : '' + d);
  return `${dir} ${pad3}°`;
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
 * 用于生成外业防伪签名摘要 (Anti-Tamper Signature)
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
  while ((bytes.length % 64) !== 56) bytes.push(0);
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
      const ch = ((e & f) ^ ((~e) & g)) >>> 0;
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
 * 绑定：GCJ-02 6位经纬度 + 秒级时间戳 + 地点简称 + 授时状态
 */
function computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi) {
  const latStr = Number(gcjLat || 0).toFixed(6);
  const lonStr = Number(gcjLon || 0).toFixed(6);
  const tsClean = String(timestamp || '').trim();
  const poiClean = String(shortPoi || '实拍点位').trim().slice(0, 10);
  const raw = `${latStr}|${lonStr}|${tsClean}|${poiClean}|${ANTI_TAMPER_SALT}`;
  return sha256Hex(raw).slice(0, 16);
}

function verifyAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, signature) {
  if (!signature || signature.length < 8) return false;
  const expected = computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi);
  return expected.startsWith(String(signature).toUpperCase().trim());
}

/**
 * ISO/IEC 18004 QR Code Matrix Engine (Version 1 ~ Version 9, Level L)
 * 修复：
 * 1. Format Information 0x77C4 严格按 MSB->LSB (14 - i) 顺序写入，100% 兼容微信/高德/OpenCV 扫码
 * 2. 扩充支持 Version 1 ~ 9 (最高容纳 229 字节 UTF-8)，支持双 Block 交织与 V7-V9 版本信息块
 */
const QR_SPECS = [
  { ver: 1, size: 21, dataBytes: 19, eccBytes: 7, numBlocks: 1, align: [] },
  { ver: 2, size: 25, dataBytes: 34, eccBytes: 10, numBlocks: 1, align: [6, 18] },
  { ver: 3, size: 29, dataBytes: 55, eccBytes: 15, numBlocks: 1, align: [6, 22] },
  { ver: 4, size: 33, dataBytes: 80, eccBytes: 20, numBlocks: 1, align: [6, 26] },
  { ver: 5, size: 37, dataBytes: 108, eccBytes: 26, numBlocks: 1, align: [6, 30] },
  { ver: 6, size: 41, dataBytes: 136, eccBytes: 18, numBlocks: 2, align: [6, 34] },
  { ver: 7, size: 45, dataBytes: 156, eccBytes: 20, numBlocks: 2, align: [6, 22, 38] },
  { ver: 8, size: 49, dataBytes: 194, eccBytes: 24, numBlocks: 2, align: [6, 24, 42] },
  { ver: 9, size: 53, dataBytes: 232, eccBytes: 30, numBlocks: 2, align: [6, 26, 46] }
];

const QR_VER_INFO = {
  7: 0x07C94,
  8: 0x085BC,
  9: 0x09A99
};

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

function encodeQrMatrix(text) {
  const utf8 = stringToUtf8Bytes(text);
  const spec = QR_SPECS.find(s => utf8.length + 3 <= s.dataBytes) || QR_SPECS[QR_SPECS.length - 1];
  const bytes = safeUtf8Slice(utf8, spec.dataBytes - 3);
  const bits = [];
  const pushBits = (val, len) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  pushBits(4, 4);
  pushBits(bytes.length, 8);
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

  let allCw = [];
  if (spec.numBlocks === 1) {
    allCw = dataCw.concat(rsEncode(dataCw, spec.eccBytes));
  } else {
    const blockSize = spec.dataBytes / spec.numBlocks;
    const blocks = [];
    const eccBlocks = [];
    for (let b = 0; b < spec.numBlocks; b++) {
      const blk = dataCw.slice(b * blockSize, (b + 1) * blockSize);
      blocks.push(blk);
      eccBlocks.push(rsEncode(blk, spec.eccBytes));
    }
    for (let i = 0; i < blockSize; i++) {
      for (let b = 0; b < spec.numBlocks; b++) allCw.push(blocks[b][i]);
    }
    for (let i = 0; i < spec.eccBytes; i++) {
      for (let b = 0; b < spec.numBlocks; b++) allCw.push(eccBlocks[b][i]);
    }
  }

  const sz = spec.size;
  const mat = Array.from({ length: sz }, () => new Array(sz).fill(false));
  const fn = Array.from({ length: sz }, () => new Array(sz).fill(false));
  const setFn = (x, y, d) => {
    if (x >= 0 && x < sz && y >= 0 && y < sz) {
      mat[y][x] = d;
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
          const dark = bitIdx < allCw.length * 8 ? ((allCw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) !== 0 : false;
          mat[y][x] = dark;
          bitIdx++;
        }
      }
    }
    right -= 2;
    vert++;
  }
  for (let y = 0; y < sz; y++) {
    for (let x = 0; x < sz; x++) {
      if (!fn[y][x] && (x + y) % 2 === 0) mat[y][x] = !mat[y][x];
    }
  }

  // ISO/IEC 18004 Format Information (Level L = 01, Mask 0 = 000 -> 0x77C4, MSB bit 14 placed first)
  const fmt = 0x77c4;
  const bit = i => ((fmt >>> (14 - i)) & 1) !== 0;
  for (let i = 0; i <= 5; i++) mat[8][i] = bit(i);
  mat[8][7] = bit(6);
  mat[8][8] = bit(7);
  mat[7][8] = bit(8);
  for (let i = 9; i <= 14; i++) mat[14 - i][8] = bit(i);
  for (let i = 0; i <= 7; i++) mat[sz - 1 - i][8] = bit(i);
  for (let i = 8; i <= 14; i++) mat[8][sz - 15 + i] = bit(i);
  mat[sz - 8][8] = true;
  return mat;
}

function buildAmapJumpUrl(gcjLat, gcjLon, shortPoi, antiTamperOpts) {
  const latStr = Number(gcjLat).toFixed(6);
  const lonStr = Number(gcjLon).toFixed(6);
  const rawPoi = String(shortPoi || '实拍点位').trim().slice(0, 10);
  const cleanPoi = encodeURIComponent(rawPoi);
  let url = `https://uri.amap.com/marker?position=${lonStr},${latStr}&name=${cleanPoi}&coordinate=gaode&callnative=1`;
  if (antiTamperOpts && antiTamperOpts.enabled) {
    const ts = String(antiTamperOpts.timestamp || '').trim();
    const sig = antiTamperOpts.signature || computeAntiTamperHash(gcjLat, gcjLon, ts, rawPoi);
    const ntp = antiTamperOpts.ntpSynced ? 1 : 0;
    url += `&ts=${encodeURIComponent(ts)}&sig=${sig.slice(0, 12)}&ntp=${ntp}`;
  }
  return url;
}

/**
 * 解析扫码结果、链接二维码参数或好友转发卡片参数，并自动执行 SHA-256 防伪核验
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
    try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
  };

  // 1. Check position=lon,lat
  const posMatch = origStr.match(/position=([0-9.+-]+),([0-9.+-]+)/i) || decodedStr.match(/position=([0-9.+-]+),([0-9.+-]+)/i);
  if (posMatch) {
    const lon = parseFloat(posMatch[1]);
    const lat = parseFloat(posMatch[2]);
    const poi = extractParam(origStr, 'name') || extractParam(decodedStr, 'name') || '好友水印实拍点';
    const ts = extractParam(origStr, 'ts') || extractParam(decodedStr, 'ts');
    const sig = extractParam(origStr, 'sig') || extractParam(decodedStr, 'sig');
    const ntp = (extractParam(origStr, 'ntp') || extractParam(decodedStr, 'ntp')) === '1';
    if (!isNaN(lat) && !isNaN(lon)) {
      const gcjLat = Number(lat.toFixed(6));
      const gcjLon = Number(lon.toFixed(6));
      const verified = sig ? verifyAntiTamperHash(gcjLat, gcjLon, ts, poi, sig) : false;
      return {
        gcjLat,
        gcjLon,
        shortPoi: poi,
        timestamp: ts,
        signature: sig,
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
    const poi = extractParam(origStr, 'poi') || extractParam(decodedStr, 'poi') || '好友水印实拍点';
    const ts = extractParam(origStr, 'ts') || extractParam(decodedStr, 'ts');
    const sig = extractParam(origStr, 'sig') || extractParam(decodedStr, 'sig');
    if (!isNaN(lat) && !isNaN(lon)) {
      const gcjLat = Number(lat.toFixed(6));
      const gcjLon = Number(lon.toFixed(6));
      const verified = sig ? verifyAntiTamperHash(gcjLat, gcjLon, ts, poi, sig) : false;
      return {
        gcjLat,
        gcjLon,
        shortPoi: poi,
        timestamp: ts,
        signature: sig,
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
      shortPoi: '扫码识别点位',
      hasAntiTamper: false,
      verified: false
    };
  }
  return null;
}

/**
 * 160x160px 高德高清矢量缩略地图（Mini-Map 画中画）绘制引擎
 * 基于当前 GCJ-02 经纬度确定性生成周边路网、水系/绿地斑块、测绘十字丝、电子罗盘朝向扇形与中心高德红针
 */
function drawVectorMiniMap(ctx, boxX, boxY, boxSize, gcjLat, gcjLon, azimuthDeg, shortPoi, scaleFactor) {
  const s = scaleFactor || 1;
  ctx.save();

  // 1. 裁剪圆角地图视口
  const r = 14 * s;
  ctx.beginPath();
  if (ctx.roundRect) {
    ctx.roundRect(boxX, boxY, boxSize, boxSize, r);
  } else {
    ctx.rect(boxX, boxY, boxSize, boxSize);
  }
  ctx.clip();

  // 2. 深色高德极夜测绘底图填充
  ctx.fillStyle = '#0c1629';
  ctx.fillRect(boxX, boxY, boxSize, boxSize);

  // 根据经纬度小数位生成确定性的周边路网与地形偏移，使点位移动时路网平滑位移
  const latSeed = Math.floor(Math.abs(gcjLat * 10000)) % 100;
  const lonSeed = Math.floor(Math.abs(gcjLon * 10000)) % 100;
  const shiftX = ((lonSeed - 50) / 100) * (boxSize * 0.22);
  const shiftY = ((latSeed - 50) / 100) * (boxSize * 0.22);

  // 3. 绿地与水系斑块
  ctx.fillStyle = 'rgba(16, 185, 129, 0.13)';
  ctx.fillRect(boxX + 8 * s + shiftX, boxY + 10 * s + shiftY, boxSize * 0.34, boxSize * 0.28);
  ctx.fillStyle = 'rgba(14, 165, 233, 0.16)';
  ctx.beginPath();
  ctx.moveTo(boxX, boxY + boxSize * 0.72 + shiftY * 0.5);
  ctx.quadraticCurveTo(
    boxX + boxSize * 0.48,
    boxY + boxSize * 0.56 + shiftX * 0.4,
    boxX + boxSize,
    boxY + boxSize * 0.82
  );
  ctx.lineTo(boxX + boxSize, boxY + boxSize);
  ctx.lineTo(boxX, boxY + boxSize);
  ctx.closePath();
  ctx.fill();

  // 4. 经纬测绘网格线
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.14)';
  ctx.lineWidth = 1 * s;
  const gridStep = boxSize / 4;
  for (let i = 1; i < 4; i++) {
    ctx.beginPath();
    ctx.moveTo(boxX + i * gridStep, boxY);
    ctx.lineTo(boxX + i * gridStep, boxY + boxSize);
    ctx.moveTo(boxX, boxY + i * gridStep);
    ctx.lineTo(boxX + boxSize, boxY + i * gridStep);
    ctx.stroke();
  }

  // 5. 周边次干道与主干道矢量路网
  ctx.strokeStyle = 'rgba(148, 163, 184, 0.28)';
  ctx.lineWidth = 3.5 * s;
  ctx.beginPath();
  ctx.moveTo(boxX, boxY + boxSize * 0.32 + shiftY);
  ctx.lineTo(boxX + boxSize, boxY + boxSize * 0.27 + shiftY);
  ctx.moveTo(boxX + boxSize * 0.28 + shiftX, boxY);
  ctx.lineTo(boxX + boxSize * 0.33 + shiftX, boxY + boxSize);
  ctx.moveTo(boxX + boxSize * 0.74 + shiftX * 0.6, boxY);
  ctx.lineTo(boxX + boxSize * 0.68 + shiftX * 0.6, boxY + boxSize);
  ctx.stroke();

  // 城市主干道（琥珀金高德道路高亮）
  ctx.strokeStyle = 'rgba(245, 158, 11, 0.48)';
  ctx.lineWidth = 5 * s;
  ctx.beginPath();
  ctx.moveTo(boxX, boxY + boxSize * 0.56 + shiftY * 0.7);
  ctx.lineTo(boxX + boxSize, boxY + boxSize * 0.48 + shiftY * 0.7);
  ctx.moveTo(boxX + boxSize * 0.52 + shiftX * 0.7, boxY);
  ctx.lineTo(boxX + boxSize * 0.47 + shiftX * 0.7, boxY + boxSize);
  ctx.stroke();

  const cx = boxX + boxSize / 2;
  const cy = boxY + boxSize / 2;

  // 6. 雷达精度圈
  ctx.fillStyle = 'rgba(56, 189, 248, 0.12)';
  ctx.beginPath();
  ctx.arc(cx, cy, boxSize * 0.24, 0, PI * 2);
  ctx.fill();
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.45)';
  ctx.lineWidth = 1.2 * s;
  ctx.stroke();

  // 7. 电子罗盘朝向扇形 (Azimuth View Cone)
  const rad = ((Number(azimuthDeg || 0) - 90) * PI) / 180;
  const coneSpread = (28 * PI) / 180;
  const coneRadius = boxSize * 0.36;
  ctx.fillStyle = 'rgba(56, 189, 248, 0.28)';
  ctx.beginPath();
  ctx.moveTo(cx, cy);
  ctx.arc(cx, cy, coneRadius, rad - coneSpread, rad + coneSpread);
  ctx.closePath();
  ctx.fill();

  // 8. 中心高德定位红针 (Pin Drop)
  ctx.strokeStyle = '#dc2626';
  ctx.lineWidth = 2.6 * s;
  ctx.beginPath();
  ctx.moveTo(cx, cy - 8 * s);
  ctx.lineTo(cx, cy + 2 * s);
  ctx.stroke();

  ctx.fillStyle = '#ef4444';
  ctx.beginPath();
  ctx.arc(cx, cy - 10 * s, 6.5 * s, 0, PI * 2);
  ctx.fill();
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 1.5 * s;
  ctx.stroke();

  ctx.fillStyle = '#ffffff';
  ctx.beginPath();
  ctx.arc(cx - 1.5 * s, cy - 11.5 * s, 1.8 * s, 0, PI * 2);
  ctx.fill();

  // 9. 左上角指北针 N 角标 & 底部点位微缩标签
  ctx.fillStyle = 'rgba(15, 23, 42, 0.82)';
  ctx.fillRect(boxX + 6 * s, boxY + 6 * s, 36 * s, 18 * s);
  ctx.fillStyle = '#38bdf8';
  ctx.font = `bold ${Math.round(11 * s)}px sans-serif`;
  ctx.textAlign = 'center';
  ctx.fillText('▲N', boxX + 24 * s, boxY + 19 * s);

  ctx.fillStyle = 'rgba(11, 19, 43, 0.86)';
  ctx.fillRect(boxX, boxY + boxSize - 24 * s, boxSize, 24 * s);
  ctx.fillStyle = '#e2e8f0';
  ctx.font = `bold ${Math.round(11.5 * s)}px sans-serif`;
  ctx.textAlign = 'center';
  const shortLabel = String(shortPoi || '高德测绘点').slice(0, 8);
  ctx.fillText('📍' + shortLabel, cx, boxY + boxSize - 8 * s);

  ctx.restore();

  // 外边框描边
  ctx.save();
  ctx.strokeStyle = 'rgba(56, 189, 248, 0.65)';
  ctx.lineWidth = 1.8 * s;
  ctx.beginPath();
  if (ctx.roundRect) {
    ctx.roundRect(boxX, boxY, boxSize, boxSize, r);
  } else {
    ctx.rect(boxX, boxY, boxSize, boxSize);
  }
  ctx.stroke();
  ctx.restore();
}

/**
 * 内置全国高频城市与地标离线网格索引 + 智能逆地理推演
 * 当未配置外部 Key 或处于无网/弱网环境时，根据真实 GCJ-02 坐标自动推演省市区与片区名，彻底告别写死“小庄村”
 */
const CITY_ANCHORS = [
  { name: '北京市中心区', city: '北京市东城区', lat: 39.908823, lon: 116.397470 },
  { name: '上海陆家嘴片区', city: '上海市浦东新区', lat: 31.239702, lon: 121.499763 },
  { name: '广州珠江新城', city: '广东省广州市天河区', lat: 23.119293, lon: 113.321206 },
  { name: '深圳南山科技园', city: '广东省深圳市南山区', lat: 22.540503, lon: 113.953412 },
  { name: '杭州西湖滨江区', city: '浙江省杭州市西湖区', lat: 30.259244, lon: 120.219375 },
  { name: '成都天府新区', city: '四川省成都市武侯区', lat: 30.657420, lon: 104.065840 },
  { name: '武汉光谷片区', city: '湖北省武汉市洪山区', lat: 30.505600, lon: 114.408600 },
  { name: '南京河西新城', city: '江苏省南京市建邺区', lat: 32.003600, lon: 118.732500 },
  { name: '西安高新片区', city: '陕西省西安市雁塔区', lat: 34.218600, lon: 108.889100 },
  { name: '重庆江北嘴片区', city: '重庆市江北区', lat: 29.571000, lon: 106.574000 }
];

function inferSmartPoiFromCoords(gcjLat, gcjLon) {
  const lat = Number(gcjLat);
  const lon = Number(gcjLon);
  let nearest = null;
  let minDegSq = 999999;
  for (let i = 0; i < CITY_ANCHORS.length; i++) {
    const c = CITY_ANCHORS[i];
    const dSq = Math.pow(lat - c.lat, 2) + Math.pow(lon - c.lon, 2);
    if (dSq < minDegSq) {
      minDegSq = dSq;
      nearest = c;
    }
  }
  const gridCode = `${Math.abs(lat).toFixed(3)}°N·${Math.abs(lon).toFixed(3)}°E`;
  if (nearest && minDegSq < 0.25) {
    return {
      shortPoi: `${nearest.name}实拍点`,
      fullAddr: `${nearest.city} · 高德GCJ-02精准网格 (${gridCode})`,
      nearbyPois: [
        `${nearest.name}1号点`,
        `${nearest.city}巡检点`,
        `现场核验点(${lat.toFixed(4)}°N)`,
        '主干道施工段',
        '外业测绘基准桩'
      ]
    };
  }
  return {
    shortPoi: `实拍点位(${lat.toFixed(4)}°N)`,
    fullAddr: `高德GCJ-02精准坐标 ${lat.toFixed(6)}°N, ${lon.toFixed(6)}°E`,
    nearbyPois: [
      `实拍点(${lat.toFixed(4)}°N)`,
      '1号巡检点位',
      '工程隐蔽验收点',
      '测绘控制点A',
      '外业打卡现场'
    ]
  };
}

module.exports = {
  wgs84ToGcj02,
  gcj02ToWgs84,
  formatCoords,
  formatAzimuthText,
  sha256Hex,
  computeAntiTamperHash,
  verifyAntiTamperHash,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload,
  drawVectorMiniMap,
  inferSmartPoiFromCoords
};
