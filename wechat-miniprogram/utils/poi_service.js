/**
 * 极速高德水印相机 v1.0 · 自动周边 POI 逆地理编码与权威授时客户端服务
 * 特性：
 * - 通过现有微信云开发 regeoPoi 云函数代理腾讯位置服务 / 高德逆地理编码（隐藏 Key、免配合法域名）
 * - 50m 位移 + 3 分钟时间双阈值空间缓存，避免 15s GPS 轮询浪费配额
 * - 支持批量 EXIF 坐标网格缓存与离线优雅降级
 */

const { distanceMeters } = require('./coord_qr.js');
const { initCloudSecurity } = require('./sec_check.js');

const CACHE_DISTANCE_M = 50;
const CACHE_TTL_MS = 3 * 60 * 1000;
const FALLBACK_QUICK_POIS = ['实拍点位', '施工现场', '1号巡检点', '验收核验点'];

let _lastFixCache = null; // { lat, lon, ts, data }
const _gridCache = {}; // key: "lat3_lon3" -> { ts, data }
const _gridKeys = [];

function gridBucketKey(lat, lon) {
  return `${Number(lat).toFixed(3)}_${Number(lon).toFixed(3)}`;
}

function readGridCache(lat, lon) {
  const key = gridBucketKey(lat, lon);
  const hit = _gridCache[key];
  if (hit && Date.now() - hit.ts < CACHE_TTL_MS * 4) {
    return hit.data;
  }
  return null;
}

function writeGridCache(lat, lon, data) {
  const key = gridBucketKey(lat, lon);
  if (!_gridCache[key]) {
    if (_gridKeys.length >= 32) {
      const oldest = _gridKeys.shift();
      delete _gridCache[oldest];
    }
    _gridKeys.push(key);
  }
  _gridCache[key] = { ts: Date.now(), data };
}

function normalizeCandidateNames(nearbyPoiDetails, shortPoi) {
  const out = [];
  const seen = new Set();
  const push = name => {
    const s = String(name || '').trim();
    if (!s || seen.has(s)) return;
    seen.add(s);
    out.push(s);
  };
  push(shortPoi);
  if (Array.isArray(nearbyPoiDetails)) {
    nearbyPoiDetails.forEach(item => {
      if (item && typeof item === 'object') push(item.name);
      else push(item);
    });
  }
  if (out.length === 0) {
    FALLBACK_QUICK_POIS.forEach(push);
  }
  return out.slice(0, 8);
}

/**
 * 解析坐标对应的最近周边商铺/小区/地标
 * @param {number} gcjLat GCJ-02 纬度
 * @param {number} gcjLon GCJ-02 经度
 * @param {object} [options] { forceRefresh: boolean }
 */
async function resolveNearbyPoi(gcjLat, gcjLon, options) {
  const opts = options || {};
  const lat = Number(gcjLat);
  const lon = Number(gcjLon);
  if (isNaN(lat) || isNaN(lon) || (lat === 0 && lon === 0)) {
    return {
      ok: false,
      resolved: false,
      shortPoi: '实拍点位',
      fullAddr: '',
      nearbyPois: FALLBACK_QUICK_POIS.slice(),
      nearbyPoiDetails: [],
      isOffline: false
    };
  }

  const now = Date.now();
  if (!opts.forceRefresh && _lastFixCache) {
    const dist = distanceMeters(_lastFixCache.lat, _lastFixCache.lon, lat, lon);
    if (dist < CACHE_DISTANCE_M && now - _lastFixCache.ts < CACHE_TTL_MS) {
      return { ..._lastFixCache.data, fromCache: true };
    }
  }

  if (!opts.forceRefresh) {
    const gridHit = readGridCache(lat, lon);
    if (gridHit) {
      return { ...gridHit, fromCache: true };
    }
  }

  initCloudSecurity();
  if (typeof wx.cloud === 'undefined' || typeof wx.cloud.callFunction !== 'function') {
    return {
      ok: false,
      resolved: false,
      shortPoi: '实拍点位',
      fullAddr: '',
      nearbyPois: FALLBACK_QUICK_POIS.slice(),
      nearbyPoiDetails: [],
      isOffline: true
    };
  }

  const t0 = Date.now();
  try {
    const res = await wx.cloud.callFunction({
      name: 'regeoPoi',
      data: {
        action: 'regeo',
        lat: Number(lat.toFixed(6)),
        lon: Number(lon.toFixed(6))
      }
    });
    const r = res && res.result;
    const rttHalf = Math.round((Date.now() - t0) / 2);
    const serverOffsetMs =
      r && typeof r.serverTimeMs === 'number' && r.serverTimeMs > 1700000000000
        ? r.serverTimeMs + rttHalf - Date.now()
        : null;

    if (r && r.ok && r.shortPoi) {
      const details = Array.isArray(r.nearbyPois) ? r.nearbyPois : [];
      const data = {
        ok: true,
        resolved: true,
        provider: r.provider || 'cloud',
        shortPoi: r.shortPoi,
        fullAddr: r.fullAddr || '',
        nearbyPois: normalizeCandidateNames(details, r.shortPoi),
        nearbyPoiDetails: details,
        serverTimeMs: r.serverTimeMs || null,
        serverOffsetMs,
        isOffline: false
      };
      _lastFixCache = { lat, lon, ts: Date.now(), data };
      writeGridCache(lat, lon, data);
      return data;
    }

    return {
      ok: false,
      resolved: false,
      unconfigured: Boolean(r && r.unconfigured),
      shortPoi: '实拍点位',
      fullAddr: '',
      nearbyPois: FALLBACK_QUICK_POIS.slice(),
      nearbyPoiDetails: [],
      serverTimeMs: (r && r.serverTimeMs) || null,
      serverOffsetMs,
      isOffline: false
    };
  } catch (err) {
    // 离线或云函数不可达：若 300m 内有最近缓存，优先沿用缓存减少“实拍点位”回退
    if (_lastFixCache && distanceMeters(_lastFixCache.lat, _lastFixCache.lon, lat, lon) < 300) {
      return { ..._lastFixCache.data, fromCache: true, isOffline: true };
    }
    return {
      ok: false,
      resolved: false,
      shortPoi: '实拍点位',
      fullAddr: '',
      nearbyPois: FALLBACK_QUICK_POIS.slice(),
      nearbyPoiDetails: [],
      isOffline: true
    };
  }
}

/**
 * 通过云函数 regeoPoi 获取服务端权威时间偏差（并支持可选 HMAC 签名）
 */
async function syncCloudServerTime(signPayload) {
  initCloudSecurity();
  if (typeof wx.cloud === 'undefined' || typeof wx.cloud.callFunction !== 'function') {
    return { ok: false, offsetMs: 0, serverTimeMs: null, serverSig: '' };
  }
  const t0 = Date.now();
  try {
    const res = await wx.cloud.callFunction({
      name: 'regeoPoi',
      data: {
        action: 'timeAndSign',
        signPayload: signPayload || null
      }
    });
    const r = res && res.result;
    if (r && r.ok && typeof r.serverTimeMs === 'number' && r.serverTimeMs > 1700000000000) {
      const rttHalf = Math.round((Date.now() - t0) / 2);
      const offsetMs = r.serverTimeMs + rttHalf - Date.now();
      return {
        ok: true,
        offsetMs,
        serverTimeMs: r.serverTimeMs + rttHalf,
        serverSig: r.serverSig || ''
      };
    }
  } catch (e) {}
  return { ok: false, offsetMs: 0, serverTimeMs: null, serverSig: '' };
}

module.exports = {
  FALLBACK_QUICK_POIS,
  resolveNearbyPoi,
  syncCloudServerTime
};
