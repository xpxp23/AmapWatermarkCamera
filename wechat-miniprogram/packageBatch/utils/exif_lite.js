/**
 * 极速高德水印相机 v9.0.0 · 轻量纯 JS JPEG EXIF 解析引擎
 * 仅读取图片前 64KB 头部字节，零依赖解析 APP1 Exif 中的 DateTimeOriginal 与 GPS 经纬度/海拔
 */

const { wgs84ToGcj02, formatTimeFromDate } = require('../../utils/coord_qr.js');

const EXIF_HEAD_BYTES = 65536;

function readAscii(view, offset, length) {
  let out = '';
  const end = Math.min(view.byteLength, offset + length);
  for (let i = offset; i < end; i++) {
    const ch = view.getUint8(i);
    if (ch === 0) break;
    out += String.fromCharCode(ch);
  }
  return out.trim();
}

function readRational(view, offset, littleEndian) {
  if (offset + 8 > view.byteLength) return 0;
  const num = view.getUint32(offset, littleEndian);
  const den = view.getUint32(offset + 4, littleEndian);
  if (!den) return 0;
  return num / den;
}

function readGpsDms(view, valueOffset, littleEndian, refChar) {
  const deg = readRational(view, valueOffset, littleEndian);
  const min = readRational(view, valueOffset + 8, littleEndian);
  const sec = readRational(view, valueOffset + 16, littleEndian);
  let val = deg + min / 60 + sec / 3600;
  if (refChar === 'S' || refChar === 'W') val = -val;
  if (isNaN(val) || !isFinite(val)) return null;
  return Number(val.toFixed(6));
}

function parseExifDateString(str) {
  if (!str) return null;
  // 标准 EXIF 格式: "YYYY:MM:DD HH:MM:SS"
  const m = str.match(/(\d{4})[:/-](\d{2})[:/-](\d{2})\s+(\d{2}):(\d{2}):(\d{2})/);
  if (!m) return null;
  const d = new Date(
    parseInt(m[1], 10),
    parseInt(m[2], 10) - 1,
    parseInt(m[3], 10),
    parseInt(m[4], 10),
    parseInt(m[5], 10),
    parseInt(m[6], 10)
  );
  if (isNaN(d.getTime())) return null;
  return formatTimeFromDate(d);
}

/**
 * 从 ArrayBuffer 解析 JPEG APP1 EXIF 元数据
 */
function parseExifFromArrayBuffer(buffer) {
  const empty = {
    hasExif: false,
    hasTime: false,
    hasGps: false,
    timestamp: '',
    dateOnlyStr: '',
    hm: '',
    hms: '',
    weekStr: '',
    dateWeek: '',
    wgsLat: null,
    wgsLon: null,
    gcjLat: null,
    gcjLon: null,
    altitude: null
  };
  if (!buffer || buffer.byteLength < 12) return empty;

  const view = new DataView(buffer);
  // JPEG SOI = 0xFFD8
  if (view.getUint16(0, false) !== 0xffd8) return empty;

  let offset = 2;
  const len = view.byteLength;
  let app1Start = -1;

  while (offset + 4 < len) {
    if (view.getUint8(offset) !== 0xff) break;
    const marker = view.getUint8(offset + 1);
    // SOS / EOI 结束头部扫描
    if (marker === 0xda || marker === 0xd9) break;
    // 无长度段
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2;
      continue;
    }
    const segLen = view.getUint16(offset + 2, false);
    if (segLen < 2) break;
    if (marker === 0xe1 && offset + 10 <= len) {
      // 检查 "Exif\0\0"
      if (
        view.getUint8(offset + 4) === 0x45 &&
        view.getUint8(offset + 5) === 0x78 &&
        view.getUint8(offset + 6) === 0x69 &&
        view.getUint8(offset + 7) === 0x66
      ) {
        app1Start = offset + 10;
        break;
      }
    }
    offset += 2 + segLen;
  }

  if (app1Start < 0 || app1Start + 8 > len) return empty;

  const byteOrder = view.getUint16(app1Start, false);
  const littleEndian = byteOrder === 0x4949;
  if (!littleEndian && byteOrder !== 0x4d4d) return empty;
  if (view.getUint16(app1Start + 2, littleEndian) !== 0x002a) return empty;

  const ifd0Rel = view.getUint32(app1Start + 4, littleEndian);
  const ifd0Offset = app1Start + ifd0Rel;
  if (ifd0Offset + 2 > len) return empty;

  let exifIfdOffset = -1;
  let gpsIfdOffset = -1;
  let ifd0DateTime = '';
  let dateTimeOriginal = '';

  const readIfd = (startOffset, onTag) => {
    if (startOffset < 0 || startOffset + 2 > len) return;
    const entries = view.getUint16(startOffset, littleEndian);
    for (let i = 0; i < entries; i++) {
      const entryOff = startOffset + 2 + i * 12;
      if (entryOff + 12 > len) break;
      const tag = view.getUint16(entryOff, littleEndian);
      const type = view.getUint16(entryOff + 2, littleEndian);
      const count = view.getUint32(entryOff + 4, littleEndian);
      const valFieldOff = entryOff + 8;
      onTag(tag, type, count, valFieldOff);
    }
  };

  readIfd(ifd0Offset, (tag, type, count, valFieldOff) => {
    if (tag === 0x8769) {
      exifIfdOffset = app1Start + view.getUint32(valFieldOff, littleEndian);
    } else if (tag === 0x8825) {
      gpsIfdOffset = app1Start + view.getUint32(valFieldOff, littleEndian);
    } else if (tag === 0x0132 && type === 2 && count > 4) {
      const strOff = app1Start + view.getUint32(valFieldOff, littleEndian);
      ifd0DateTime = readAscii(view, strOff, count);
    }
  });

  if (exifIfdOffset > 0) {
    readIfd(exifIfdOffset, (tag, type, count, valFieldOff) => {
      if ((tag === 0x9003 || tag === 0x9004) && type === 2 && count > 4) {
        const strOff = app1Start + view.getUint32(valFieldOff, littleEndian);
        const parsed = readAscii(view, strOff, count);
        if (tag === 0x9003 || !dateTimeOriginal) {
          dateTimeOriginal = parsed;
        }
      }
    });
  }

  let latRef = 'N';
  let lonRef = 'E';
  let latOff = -1;
  let lonOff = -1;
  let altVal = null;
  let altRef = 0;

  if (gpsIfdOffset > 0) {
    readIfd(gpsIfdOffset, (tag, type, count, valFieldOff) => {
      if (tag === 0x0001) {
        latRef = readAscii(view, valFieldOff, 2) || 'N';
      } else if (tag === 0x0002 && type === 5 && count >= 3) {
        latOff = app1Start + view.getUint32(valFieldOff, littleEndian);
      } else if (tag === 0x0003) {
        lonRef = readAscii(view, valFieldOff, 2) || 'E';
      } else if (tag === 0x0004 && type === 5 && count >= 3) {
        lonOff = app1Start + view.getUint32(valFieldOff, littleEndian);
      } else if (tag === 0x0005) {
        altRef = view.getUint8(valFieldOff);
      } else if (tag === 0x0006 && type === 5) {
        const aOff = app1Start + view.getUint32(valFieldOff, littleEndian);
        altVal = readRational(view, aOff, littleEndian);
      }
    });
  }

  const timeParsed = parseExifDateString(dateTimeOriginal || ifd0DateTime);
  const wgsLat = latOff > 0 ? readGpsDms(view, latOff, littleEndian, latRef) : null;
  const wgsLon = lonOff > 0 ? readGpsDms(view, lonOff, littleEndian, lonRef) : null;
  const hasGps =
    wgsLat !== null &&
    wgsLon !== null &&
    Math.abs(wgsLat) <= 90 &&
    Math.abs(wgsLon) <= 180 &&
    !(wgsLat === 0 && wgsLon === 0);

  const gcj = hasGps ? wgs84ToGcj02(wgsLat, wgsLon) : null;
  const altitude =
    altVal !== null && isFinite(altVal) ? Number((altRef === 1 ? -altVal : altVal).toFixed(1)) : null;

  return {
    hasExif: Boolean(timeParsed || hasGps),
    hasTime: Boolean(timeParsed),
    hasGps,
    timestamp: timeParsed ? timeParsed.fullTs : '',
    dateOnlyStr: timeParsed ? timeParsed.datePart : '',
    hm: timeParsed ? timeParsed.hm : '',
    hms: timeParsed ? timeParsed.hms : '',
    weekStr: timeParsed ? timeParsed.weekStr : '',
    dateWeek: timeParsed ? timeParsed.dateWeek : '',
    wgsLat: hasGps ? wgsLat : null,
    wgsLon: hasGps ? wgsLon : null,
    gcjLat: gcj ? gcj.lat : null,
    gcjLon: gcj ? gcj.lon : null,
    altitude
  };
}

/**
 * 异步读取本地图片前 64KB 并解析 EXIF（非阻塞、低内存）
 */
function readImageExifLite(filePath) {
  return new Promise(resolve => {
    const empty = parseExifFromArrayBuffer(null);
    if (!filePath || !wx.getFileSystemManager) {
      resolve(empty);
      return;
    }
    const fs = wx.getFileSystemManager();
    fs.readFile({
      filePath,
      position: 0,
      length: EXIF_HEAD_BYTES,
      success: res => {
        try {
          resolve(parseExifFromArrayBuffer(res.data));
        } catch (e) {
          resolve(empty);
        }
      },
      fail: () => {
        // 部分旧基础库不支持 position/length 超过文件大小，回退全量读取头部
        fs.readFile({
          filePath,
          success: res2 => {
            try {
              const buf = res2.data;
              const sliced = buf && buf.byteLength > EXIF_HEAD_BYTES ? buf.slice(0, EXIF_HEAD_BYTES) : buf;
              resolve(parseExifFromArrayBuffer(sliced));
            } catch (e) {
              resolve(empty);
            }
          },
          fail: () => resolve(empty)
        });
      }
    });
  });
}

module.exports = {
  parseExifFromArrayBuffer,
  readImageExifLite
};
