const cloud = require('wx-server-sdk');
const https = require('https');
const crypto = require('crypto');

cloud.init({
  env: cloud.DYNAMIC_CURRENT_ENV
});

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
  if (outOfChina(wgsLat, wgsLon)) return { lat: wgsLat, lon: wgsLon };
  let dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
  let dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
  const radLat = (wgsLat / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - OFFSET_EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((AXIS * (1 - OFFSET_EE)) / (magic * sqrtMagic)) * PI);
  dLon = (dLon * 180.0) / ((AXIS / sqrtMagic) * Math.cos(radLat) * PI);
  return { lat: wgsLat + dLat, lon: wgsLon + dLon };
}

function gcj02ToWgs84(gcjLat, gcjLon) {
  if (outOfChina(gcjLat, gcjLon)) return { lat: gcjLat, lon: gcjLon };
  let wgsLat = gcjLat;
  let wgsLon = gcjLon;
  for (let i = 0; i < 6; i++) {
    const curr = wgs84ToGcj02(wgsLat, wgsLon);
    wgsLat += gcjLat - curr.lat;
    wgsLon += gcjLon - curr.lon;
  }
  return { lat: Number(wgsLat.toFixed(6)), lon: Number(wgsLon.toFixed(6)) };
}

function parseFiniteDist(val) {
  if (val === null || val === undefined || val === '') return 9999;
  const n = Number(val);
  return Number.isFinite(n) ? n : 9999;
}

function httpGetJson(url, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      url,
      {
        timeout: timeoutMs || 2600,
        headers: {
          'User-Agent': 'AmapWatermarkCamera-MiniProgram/1.0',
          Accept: 'application/json'
        }
      },
      res => {
        let raw = '';
        res.setEncoding('utf8');
        res.on('data', chunk => {
          raw += chunk;
        });
        res.on('end', () => {
          if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
            reject(new Error(`HTTP ${res.statusCode}`));
            return;
          }
          try {
            resolve(JSON.parse(raw));
          } catch (err) {
            reject(new Error('invalid json response'));
          }
        });
      }
    );
    req.on('timeout', () => {
      req.destroy(new Error('request timeout'));
    });
    req.on('error', reject);
  });
}

function cleanPoiName(name) {
  return String(name || '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 24);
}

/**
 * 1. 优先调用腾讯位置服务逆地理编码（与微信地图 POI 100% 一致）
 * 环境变量：QQMAP_KEY（可选 QQMAP_SK 用于签名校验模式）
 */
async function queryTencentRegeo(lat, lon, key, sk) {
  const params = {
    get_poi: '1',
    key,
    location: `${lat},${lon}`,
    poi_options: 'policy=2;radius=1000;page_size=10'
  };
  const sortedKeys = Object.keys(params).sort();
  const rawQuery = sortedKeys.map(k => `${k}=${params[k]}`).join('&');
  const encodedQuery = sortedKeys.map(k => `${k}=${encodeURIComponent(params[k])}`).join('&');

  let finalQuery = encodedQuery;
  if (sk) {
    const sig = crypto
      .createHash('md5')
      .update(`/ws/geocoder/v1/?${rawQuery}${sk}`)
      .digest('hex');
    finalQuery = `${encodedQuery}&sig=${sig}`;
  }

  const url = `https://apis.map.qq.com/ws/geocoder/v1/?${finalQuery}`;
  const data = await httpGetJson(url, 2600);
  if (!data || data.status !== 0 || !data.result) {
    throw new Error((data && `${data.status}:${data.message}`) || 'qqmap regeo failed');
  }
  const r = data.result;
  const pois = Array.isArray(r.pois) ? r.pois : [];
  const formatted = r.formatted_addresses || {};
  const comp = r.address_component || {};

  const candidates = [];
  const seen = new Set();
  const pushCand = (name, address, distance) => {
    const clean = cleanPoiName(name);
    if (!clean || seen.has(clean)) return;
    seen.add(clean);
    const dNum = parseFiniteDist(distance);
    candidates.push({
      name: clean,
      address: String(address || '').trim(),
      distance: dNum < 9999 ? Math.round(dNum) : null
    });
  };

  // 修复 distance === 0 时被误判为 9999 的排序 Bug
  pois
    .slice()
    .sort((a, b) => parseFiniteDist(a._distance) - parseFiniteDist(b._distance))
    .forEach(p => {
      pushCand(p.title, p.address, p._distance);
    });

  if (formatted.recommend) {
    pushCand(formatted.recommend, r.address, 0);
  }
  if (comp.street_number || comp.street) {
    pushCand(comp.street_number || comp.street, r.address, null);
  }
  if (comp.district) {
    pushCand(comp.district, r.address, null);
  }

  let bestPoi = '';
  const nearestClose = candidates.find(c => c.distance !== null && c.distance <= 200);
  if (nearestClose) {
    bestPoi = nearestClose.name;
  } else if (formatted.recommend) {
    bestPoi = cleanPoiName(formatted.recommend);
  } else if (candidates.length > 0) {
    bestPoi = candidates[0].name;
  } else {
    bestPoi = cleanPoiName(comp.street_number || comp.street || comp.district || '实拍点位');
  }

  return {
    provider: 'tencent',
    shortPoi: bestPoi || '实拍点位',
    fullAddr: String(r.address || '').trim(),
    nearbyPois: candidates.slice(0, 8)
  };
}

/**
 * 2. 备选调用高德 Web 服务逆地理编码（支持 AOI 小区/园区 + 周边 POI）
 * 环境变量：AMAP_KEY
 */
async function queryAmapRegeo(lat, lon, key) {
  const url =
    `https://restapi.amap.com/v3/geocode/regeo?location=${lon},${lat}` +
    `&extensions=all&radius=1000&roadlevel=0&key=${encodeURIComponent(key)}`;
  const data = await httpGetJson(url, 2600);
  if (!data || data.status !== '1' || !data.regeocode) {
    throw new Error((data && `${data.infocode || ''}:${data.info || ''}`) || 'amap regeo failed');
  }
  const rg = data.regeocode;
  const aois = Array.isArray(rg.aois) ? rg.aois : [];
  const pois = Array.isArray(rg.pois) ? rg.pois : [];
  const comp = rg.addressComponent || {};

  const candidates = [];
  const seen = new Set();
  const pushCand = (name, address, distance) => {
    const clean = cleanPoiName(name);
    if (!clean || seen.has(clean)) return;
    seen.add(clean);
    const dNum = parseFiniteDist(distance);
    candidates.push({
      name: clean,
      address: String(address || '').trim(),
      distance: dNum < 9999 ? Math.round(dNum) : null
    });
  };

  // 修复 distance === "0" 时被误判为 9999 的排序 Bug
  pois
    .slice()
    .sort((a, b) => parseFiniteDist(a.distance) - parseFiniteDist(b.distance))
    .forEach(p => {
      pushCand(p.name, (typeof p.address === 'string' && p.address) || rg.formatted_address, p.distance);
    });

  aois
    .slice()
    .sort((a, b) => parseFiniteDist(a.distance) - parseFiniteDist(b.distance))
    .forEach(a => {
      pushCand(a.name, rg.formatted_address, a.distance);
    });

  const streetNum =
    comp.streetNumber && typeof comp.streetNumber.street === 'string' && comp.streetNumber.street
      ? `${comp.streetNumber.street}${typeof comp.streetNumber.number === 'string' ? comp.streetNumber.number : ''}`
      : '';
  if (streetNum) pushCand(streetNum, rg.formatted_address, null);
  if (typeof comp.township === 'string' && comp.township) pushCand(comp.township, rg.formatted_address, null);
  if (typeof comp.district === 'string' && comp.district) pushCand(comp.district, rg.formatted_address, null);

  let bestPoi = '';
  const nearestClose = candidates.find(c => c.distance !== null && c.distance <= 200);
  if (nearestClose) {
    bestPoi = nearestClose.name;
  } else if (aois.length > 0 && aois[0].name) {
    bestPoi = cleanPoiName(aois[0].name);
  } else if (candidates.length > 0) {
    bestPoi = candidates[0].name;
  } else {
    bestPoi = cleanPoiName(streetNum || comp.district || '实拍点位');
  }

  return {
    provider: 'amap',
    shortPoi: bestPoi || '实拍点位',
    fullAddr: String(rg.formatted_address || '').trim(),
    nearbyPois: candidates.slice(0, 8)
  };
}

/**
 * 3. 零配置免费兜底：未配置 QQMAP_KEY/AMAP_KEY 时自动走 WGS-84 逆地理编码，开箱即用
 */
async function queryFreeFallbackRegeo(gcjLat, gcjLon) {
  const wgs = gcj02ToWgs84(gcjLat, gcjLon);
  const candidates = [];
  const seen = new Set();
  const pushCand = (name, address) => {
    const clean = cleanPoiName(name);
    if (!clean || seen.has(clean)) return;
    seen.add(clean);
    candidates.push({ name: clean, address: String(address || '').trim(), distance: null });
  };

  // 先试 BigDataCloud 免费中文逆地理接口（延迟极低，国内云主机访问稳定）
  try {
    const bdcUrl = `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${wgs.lat}&longitude=${wgs.lon}&localityLanguage=zh`;
    const bdc = await httpGetJson(bdcUrl, 2200);
    if (bdc && (bdc.locality || bdc.city || bdc.principalSubdivision)) {
      const adminList =
        bdc.localityInfo && Array.isArray(bdc.localityInfo.administrative)
          ? bdc.localityInfo.administrative
          : [];
      const finest = adminList.length > 0 ? adminList[adminList.length - 1].name : '';
      const fullAddr = [bdc.principalSubdivision, bdc.city, bdc.locality, finest]
        .filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i)
        .join('');
      if (finest) pushCand(finest, fullAddr);
      if (bdc.locality) pushCand(bdc.locality, fullAddr);
      if (bdc.city) pushCand(bdc.city, fullAddr);
      if (candidates.length > 0) {
        return {
          provider: 'free-bdc',
          shortPoi: candidates[0].name,
          fullAddr,
          nearbyPois: candidates.slice(0, 6)
        };
      }
    }
  } catch (e) {}

  // 再试 OpenStreetMap Nominatim（可识别具体商铺/建筑/小区/道路）
  const osmUrl =
    `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${wgs.lat}&lon=${wgs.lon}` +
    `&zoom=18&addressdetails=1&accept-language=zh-CN`;
  const osm = await httpGetJson(osmUrl, 2500);
  if (osm && (osm.name || osm.address)) {
    const addr = osm.address || {};
    const fullAddr = String(osm.display_name || '').trim();
    const parts = [
      osm.name,
      addr.amenity,
      addr.shop,
      addr.building,
      addr.neighbourhood,
      addr.residential,
      addr.quarter,
      addr.road && addr.house_number ? `${addr.road}${addr.house_number}` : addr.road,
      addr.suburb || addr.district
    ];
    parts.forEach(p => pushCand(p, fullAddr));
    if (candidates.length > 0) {
      return {
        provider: 'free-osm',
        shortPoi: candidates[0].name,
        fullAddr,
        nearbyPois: candidates.slice(0, 6)
      };
    }
  }

  throw new Error('free fallback regeo unavailable');
}

function buildServerSignature(payload) {
  const secret = process.env.SIGN_SECRET || 'AMAP_WM_CLOUD_SIGN_DEFAULT_V1';
  const latStr = Number(payload.lat || 0).toFixed(6);
  const lonStr = Number(payload.lon || 0).toFixed(6);
  const ts = String(payload.timestamp || '').trim();
  const poi = cleanPoiName(payload.shortPoi || '实拍点位').slice(0, 10);
  const note = String(payload.note || '').trim().slice(0, 12);
  const raw = `${latStr}|${lonStr}|${ts}|${poi}|${note}`;
  return crypto.createHmac('sha256', secret).update(raw).digest('hex').toUpperCase().slice(0, 16);
}

exports.main = async event => {
  const action = (event && event.action) || 'regeo';
  const serverTimeMs = Date.now();

  if (action === 'timeAndSign') {
    const res = {
      ok: true,
      serverTimeMs
    };
    if (event && event.signPayload) {
      res.serverSig = buildServerSignature(event.signPayload);
    }
    return res;
  }

  if (action === 'regeo') {
    const lat = Number(event && event.lat);
    const lon = Number(event && event.lon);
    if (isNaN(lat) || isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      return {
        ok: false,
        serverTimeMs,
        message: 'invalid coordinates'
      };
    }

    const qqKey = (process.env.QQMAP_KEY || '').trim();
    const qqSk = (process.env.QQMAP_SK || '').trim();
    const amapKey = (process.env.AMAP_KEY || '').trim();
    const errors = [];

    if (qqKey) {
      try {
        const result = await queryTencentRegeo(lat, lon, qqKey, qqSk);
        return { ok: true, serverTimeMs, ...result };
      } catch (e1) {
        errors.push(`tencent:${e1.message}`);
      }
    }

    if (amapKey) {
      try {
        const result = await queryAmapRegeo(lat, lon, amapKey);
        return { ok: true, serverTimeMs, ...result };
      } catch (e2) {
        errors.push(`amap:${e2.message}`);
      }
    }

    // 零配置免费兜底：未填 Key 或主备 Key 暂时失败时，依然自动返回真实街道/建筑/区县地名
    try {
      const freeRes = await queryFreeFallbackRegeo(lat, lon);
      return {
        ok: true,
        unconfigured: !qqKey && !amapKey,
        serverTimeMs,
        debugReason: errors.length > 0 ? errors.join('; ') : (!qqKey && !amapKey ? '未配置QQMAP_KEY/AMAP_KEY，已用免费兜底' : ''),
        ...freeRes
      };
    } catch (e3) {
      errors.push(`fallback:${e3.message}`);
      return {
        ok: false,
        unconfigured: !qqKey && !amapKey,
        serverTimeMs,
        message: errors.join('; ') || 'regeo failed'
      };
    }
  }

  return {
    ok: false,
    serverTimeMs,
    message: 'unknown action'
  };
};
