const cloud = require('wx-server-sdk');
const https = require('https');
const crypto = require('crypto');

cloud.init({
  env: cloud.DYNAMIC_CURRENT_ENV
});

function httpGetJson(url, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: timeoutMs || 4500 }, res => {
      let raw = '';
      res.setEncoding('utf8');
      res.on('data', chunk => {
        raw += chunk;
      });
      res.on('end', () => {
        try {
          resolve(JSON.parse(raw));
        } catch (err) {
          reject(new Error('invalid json response'));
        }
      });
    });
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
 * 优先调用腾讯位置服务逆地理编码（与微信地图 POI 100% 一致）
 * 环境变量：QQMAP_KEY
 */
async function queryTencentRegeo(lat, lon, key) {
  const url =
    `https://apis.map.qq.com/ws/geocoder/v1/?location=${lat},${lon}` +
    `&get_poi=1&poi_options=policy=2;radius=500;page_size=10&key=${encodeURIComponent(key)}`;
  const data = await httpGetJson(url, 4500);
  if (!data || data.status !== 0 || !data.result) {
    throw new Error((data && data.message) || 'qqmap regeo failed');
  }
  const r = data.result;
  const pois = Array.isArray(r.pois) ? r.pois : [];
  const formatted = r.formatted_addresses || {};
  const comp = r.address_component || {};

  // 过滤无意义门牌/纯路口，按距离优先挑选最近商铺/小区/地标
  const candidates = [];
  const seen = new Set();
  const pushCand = (name, address, distance) => {
    const clean = cleanPoiName(name);
    if (!clean || seen.has(clean)) return;
    seen.add(clean);
    candidates.push({
      name: clean,
      address: String(address || '').trim(),
      distance: typeof distance === 'number' ? Math.round(distance) : null
    });
  };

  pois
    .slice()
    .sort((a, b) => (Number(a._distance) || 9999) - (Number(b._distance) || 9999))
    .forEach(p => {
      pushCand(p.title, p.address, Number(p._distance));
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

  // 智能择优：优先 150m 内最近 POI，否则取腾讯推荐简称，再降级街道/区县
  let bestPoi = '';
  const nearestClose = candidates.find(c => c.distance !== null && c.distance <= 150);
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
 * 备选调用高德 Web 服务逆地理编码（支持 AOI 小区/园区 + 周边 POI）
 * 环境变量：AMAP_KEY
 */
async function queryAmapRegeo(lat, lon, key) {
  const url =
    `https://restapi.amap.com/v3/geocode/regeo?location=${lon},${lat}` +
    `&extensions=all&radius=500&roadlevel=0&key=${encodeURIComponent(key)}`;
  const data = await httpGetJson(url, 4500);
  if (!data || data.status !== '1' || !data.regeocode) {
    throw new Error((data && data.info) || 'amap regeo failed');
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
    candidates.push({
      name: clean,
      address: String(address || '').trim(),
      distance: typeof distance === 'number' && !isNaN(distance) ? Math.round(distance) : null
    });
  };

  pois
    .slice()
    .sort((a, b) => (parseFloat(a.distance) || 9999) - (parseFloat(b.distance) || 9999))
    .forEach(p => {
      pushCand(p.name, p.address || rg.formatted_address, parseFloat(p.distance));
    });

  aois.forEach(a => {
    pushCand(a.name, rg.formatted_address, parseFloat(a.distance));
  });

  const streetNum = comp.streetNumber && comp.streetNumber.street
    ? `${comp.streetNumber.street}${comp.streetNumber.number || ''}`
    : '';
  if (streetNum) pushCand(streetNum, rg.formatted_address, null);
  if (typeof comp.township === 'string' && comp.township) pushCand(comp.township, rg.formatted_address, null);
  if (typeof comp.district === 'string' && comp.district) pushCand(comp.district, rg.formatted_address, null);

  let bestPoi = '';
  const nearestClose = candidates.find(c => c.distance !== null && c.distance <= 150);
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
    const amapKey = (process.env.AMAP_KEY || '').trim();

    if (qqKey) {
      try {
        const result = await queryTencentRegeo(lat, lon, qqKey);
        return { ok: true, serverTimeMs, ...result };
      } catch (e1) {
        if (amapKey) {
          try {
            const fallback = await queryAmapRegeo(lat, lon, amapKey);
            return { ok: true, serverTimeMs, ...fallback };
          } catch (e2) {
            return { ok: false, serverTimeMs, message: e2.message || 'regeo failed' };
          }
        }
        return { ok: false, serverTimeMs, message: e1.message || 'qqmap regeo failed' };
      }
    }

    if (amapKey) {
      try {
        const result = await queryAmapRegeo(lat, lon, amapKey);
        return { ok: true, serverTimeMs, ...result };
      } catch (e) {
        return { ok: false, serverTimeMs, message: e.message || 'amap regeo failed' };
      }
    }

    return {
      ok: false,
      unconfigured: true,
      serverTimeMs,
      message: '未配置云函数环境变量 QQMAP_KEY 或 AMAP_KEY'
    };
  }

  return {
    ok: false,
    serverTimeMs,
    message: 'unknown action'
  };
};
