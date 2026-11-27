/**
 * 极速高德水印相机 v1.0 · 自动周边 POI 逆地理编码与权威授时客户端服务
 * 特性：
 * - 优先调用云函数 regeoPoi（腾讯位置服务 -> 高德 Web 服务 -> 零 Key 免费逆地理兜底）
 * - 支持可选客户端直连兜底（app.globalData.poiConfig 配置 qqMapKey / amapKey 时，即使未部署云函数也能识别）
 * - 50m 位移 + 3 分钟时间双阈值空间缓存，避免 15s GPS 轮询浪费配额
 * - 返回清晰诊断提示 debugReason，彻底杜绝静默失败
 */

const { distanceMeters } = require('./coord_qr.js');
const { initCloudSecurity } = require('./sec_check.js');

const CACHE_DISTANCE_M = 50;
const CACHE_TTL_MS = 3 * 60 * 1000;
const FALLBACK_QUICK_POIS = ['实拍点位', '施工现场', '1号巡检点', '验收核验点'];

let _lastFixCache = null;
const _gridCache = {};
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

function parseFiniteDist(val) {
  if (val === null || val === undefined || val === '') return 9999;
  const n = Number(val);
  return Number.isFinite(n) ? n : 9999;
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
 * 可选直连兜底：仅当云函数未部署且 app.globalData.poiConfig 配置了 Key 时生效
 */
function queryDirectRegeoFallback(lat, lon) {
  return new Promise(resolve => {
    let poiCfg = null;
    try {
      const app = typeof getApp === 'function' ? getApp() : null;
      poiCfg = app && app.globalData && app.globalData.poiConfig;
    } catch (e) {}
    if (!poiCfg || typeof wx.request !== 'function') {
      resolve(null);
      return;
    }
    const qqKey = String(poiCfg.qqMapKey || '').trim();
    const amapKey = String(poiCfg.amapKey || '').trim();
    if (!qqKey && !amapKey) {
      resolve(null);
      return;
    }

    if (qqKey) {
      const url =
        `https://apis.map.qq.com/ws/geocoder/v1/?location=${lat},${lon}` +
        `&get_poi=1&poi_options=${encodeURIComponent('policy=2;radius=1000;page_size=10')}&key=${encodeURIComponent(qqKey)}`;
      wx.request({
        url,
        timeout: 4000,
        success: res => {
          const d = res && res.data;
          if (d && d.status === 0 && d.result) {
            const r = d.result;
            const pois = Array.isArray(r.pois) ? r.pois.slice().sort((a, b) => parseFiniteDist(a._distance) - parseFiniteDist(b._distance)) : [];
            const details = pois.slice(0, 8).map(p => ({
              name: String(p.title || '').trim(),
              address: String(p.address || r.address || '').trim(),
              distance: parseFiniteDist(p._distance) < 9999 ? Math.round(parseFiniteDist(p._distance)) : null
            })).filter(p => p.name);
            const best = (details[0] && details[0].name) || (r.formatted_addresses && r.formatted_addresses.recommend) || r.address || '';
            if (best) {
              resolve({
                ok: true,
                resolved: true,
                provider: 'tencent-direct',
                shortPoi: best.slice(0, 24),
                fullAddr: String(r.address || '').trim(),
                nearbyPois: normalizeCandidateNames(details, best),
                nearbyPoiDetails: details,
                isOffline: false
              });
              return;
            }
          }
          resolve(null);
        },
        fail: () => resolve(null)
      });
      return;
    }

    const amapUrl =
      `https://restapi.amap.com/v3/geocode/regeo?location=${lon},${lat}` +
      `&extensions=all&radius=1000&roadlevel=0&key=${encodeURIComponent(amapKey)}`;
    wx.request({
      url: amapUrl,
      timeout: 4000,
      success: res => {
        const d = res && res.data;
        if (d && d.status === '1' && d.regeocode) {
          const rg = d.regeocode;
          const pois = Array.isArray(rg.pois) ? rg.pois.slice().sort((a, b) => parseFiniteDist(a.distance) - parseFiniteDist(b.distance)) : [];
          const details = pois.slice(0, 8).map(p => ({
            name: String(p.name || '').trim(),
            address: String((typeof p.address === 'string' && p.address) || rg.formatted_address || '').trim(),
            distance: parseFiniteDist(p.distance) < 9999 ? Math.round(parseFiniteDist(p.distance)) : null
          })).filter(p => p.name);
          const best = (details[0] && details[0].name) || (rg.aois && rg.aois[0] && rg.aois[0].name) || rg.formatted_address || '';
          if (best) {
            resolve({
              ok: true,
              resolved: true,
              provider: 'amap-direct',
              shortPoi: best.slice(0, 24),
              fullAddr: String(rg.formatted_address || '').trim(),
              nearbyPois: normalizeCandidateNames(details, best),
              nearbyPoiDetails: details,
              isOffline: false
            });
            return;
          }
        }
        resolve(null);
      },
      fail: () => resolve(null)
    });
  });
}

/**
 * 解析坐标对应的最近周边商铺/小区/地标
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
      isOffline: false,
      debugReason: '尚未获取到有效经纬度'
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
  let cloudErrMsg = '';
  if (typeof wx.cloud !== 'undefined' && typeof wx.cloud.callFunction === 'function') {
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
          unconfigured: Boolean(r.unconfigured),
          provider: r.provider || 'cloud',
          shortPoi: r.shortPoi,
          fullAddr: r.fullAddr || '',
          nearbyPois: normalizeCandidateNames(details, r.shortPoi),
          nearbyPoiDetails: details,
          serverTimeMs: r.serverTimeMs || null,
          serverOffsetMs,
          debugReason: r.debugReason || '',
          isOffline: false
        };
        _lastFixCache = { lat, lon, ts: Date.now(), data };
        writeGridCache(lat, lon, data);
        return data;
      }

      cloudErrMsg = (r && (r.message || r.debugReason)) || '云函数未返回有效地点';
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[POI] regeoPoi returned non-ok:', r);
      }
    } catch (err) {
      cloudErrMsg = (err && (err.errMsg || err.message)) || '云函数调用失败';
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[POI] regeoPoi callFunction error:', cloudErrMsg);
      }
    }
  } else {
    cloudErrMsg = '当前基础库未初始化 wx.cloud';
  }

  // 尝试可选客户端直连兜底（如 app.globalData.poiConfig 配置了 Key）
  const direct = await queryDirectRegeoFallback(lat, lon);
  if (direct && direct.ok) {
    _lastFixCache = { lat, lon, ts: Date.now(), data: direct };
    writeGridCache(lat, lon, direct);
    return direct;
  }

  if (_lastFixCache && distanceMeters(_lastFixCache.lat, _lastFixCache.lon, lat, lon) < 300) {
    return { ..._lastFixCache.data, fromCache: true, isOffline: true };
  }

  const isNotDeployed =
    cloudErrMsg.indexOf('FunctionName') !== -1 ||
    cloudErrMsg.indexOf('not found') !== -1 ||
    cloudErrMsg.indexOf('-501000') !== -1;

  return {
    ok: false,
    resolved: false,
    shortPoi: '实拍点位',
    fullAddr: '',
    nearbyPois: FALLBACK_QUICK_POIS.slice(),
    nearbyPoiDetails: [],
    isOffline: !isNotDeployed,
    debugReason: isNotDeployed
      ? '请先在微信开发者工具上传部署 regeoPoi 云函数'
      : cloudErrMsg
  };
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
