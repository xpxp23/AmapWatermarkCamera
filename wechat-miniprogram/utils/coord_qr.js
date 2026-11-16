/**
 * 极速经纬水印相机 · 微信小程序高德 GCJ-02 / WGS-84 双向纠偏与纯离线 QR 二维码矩阵引擎
 * 零第三方依赖，100% 离线运行，支持高德 URI 解析与小程序扫码/长按跳高德闭环
 */

const PI = Math.PI;
const AXIS = 6378245.0;
const OFFSET_EE = 0.00669342162296594323;

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

const QR_SPECS = [
  { ver: 1, size: 21, dataBytes: 19, eccBytes: 7, align: [] },
  { ver: 2, size: 25, dataBytes: 34, eccBytes: 10, align: [6, 18] },
  { ver: 3, size: 29, dataBytes: 55, eccBytes: 15, align: [6, 22] },
  { ver: 4, size: 33, dataBytes: 80, eccBytes: 20, align: [6, 26] },
  { ver: 5, size: 37, dataBytes: 108, eccBytes: 26, align: [6, 30] }
];

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

function encodeQrMatrix(text) {
  const utf8 = stringToUtf8Bytes(text);
  const spec = QR_SPECS.find(s => utf8.length + 3 <= s.dataBytes) || QR_SPECS[QR_SPECS.length - 1];
  const bytes = utf8.slice(0, spec.dataBytes - 3);
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
  const allCw = dataCw.concat(rsEncode(dataCw, spec.eccBytes));
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
  const fmt = 0x77c4;
  const bit = i => ((fmt >>> i) & 1) !== 0;
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

function buildAmapJumpUrl(gcjLat, gcjLon, shortPoi) {
  const latStr = Number(gcjLat).toFixed(6);
  const lonStr = Number(gcjLon).toFixed(6);
  const cleanPoi = encodeURIComponent((shortPoi || '实拍点位').slice(0, 8));
  return `https://uri.amap.com/marker?position=${lonStr},${latStr}&name=${cleanPoi}&coordinate=gaode&callnative=1`;
}

/**
 * 解析扫码结果、普通链接二维码参数 (query.q) 或好友转发卡片参数
 * 支持格式：
 * 1. https://uri.amap.com/marker?position=116.397470,39.908823&name=小庄村...
 * 2. pages/index/index?lat=39.908823&lon=116.397470&poi=小庄村
 * 3. 纯文本经纬度 "116.397470,39.908823" 或 "39.908823, 116.397470"
 */
function parseLocationPayload(rawInput) {
  if (!rawInput) return null;
  let str = String(rawInput).trim();
  try {
    str = decodeURIComponent(str);
  } catch (e) {}

  // 1. Check position=lon,lat
  const posMatch = str.match(/position=([0-9.+-]+),([0-9.+-]+)/i);
  if (posMatch) {
    const lon = parseFloat(posMatch[1]);
    const lat = parseFloat(posMatch[2]);
    const nameMatch = str.match(/[?&]name=([^&]+)/i);
    const poi = nameMatch ? decodeURIComponent(nameMatch[1]) : '好友水印实拍点';
    if (!isNaN(lat) && !isNaN(lon)) {
      return { gcjLat: Number(lat.toFixed(6)), gcjLon: Number(lon.toFixed(6)), shortPoi: poi };
    }
  }

  // 2. Check lat=...&lon=...
  const latMatch = str.match(/[?&]lat=([0-9.+-]+)/i);
  const lonMatch = str.match(/[?&]lon=([0-9.+-]+)/i);
  if (latMatch && lonMatch) {
    const lat = parseFloat(latMatch[1]);
    const lon = parseFloat(lonMatch[1]);
    const poiMatch = str.match(/[?&]poi=([^&]+)/i);
    const poi = poiMatch ? decodeURIComponent(poiMatch[1]) : '好友水印实拍点';
    if (!isNaN(lat) && !isNaN(lon)) {
      return { gcjLat: Number(lat.toFixed(6)), gcjLon: Number(lon.toFixed(6)), shortPoi: poi };
    }
  }

  // 3. Fallback pair of decimals
  const nums = str.match(/([0-9]{1,3}\.[0-9]{3,8})/g);
  if (nums && nums.length >= 2) {
    const a = parseFloat(nums[0]);
    const b = parseFloat(nums[1]);
    const isALon = a > 65 && b < 60;
    const gcjLon = isALon ? a : b;
    const gcjLat = isALon ? b : a;
    return { gcjLat: Number(gcjLat.toFixed(6)), gcjLon: Number(gcjLon.toFixed(6)), shortPoi: '扫码识别点位' };
  }
  return null;
}

module.exports = {
  wgs84ToGcj02,
  gcj02ToWgs84,
  formatCoords,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload
};
