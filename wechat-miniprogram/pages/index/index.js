const {
  wgs84ToGcj02,
  gcj02ToWgs84,
  resolveDisplayCoordinates,
  formatCoords,
  formatAzimuthText,
  computeAntiTamperHash,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload,
  inferSmartPoiFromCoords
} = require('../../utils/coord_qr.js');

const STORAGE_KEY_CFG = 'amap_wm_wx_cfg_v7_1';
const STORAGE_KEY_PHOTOS = 'amap_wm_wx_photos_v7_1';

const ACCENT_PALETTE = [
  { name: '天际蓝', hex: '#38bdf8' },
  { name: '翡翠绿', hex: '#34d399' },
  { name: '琥珀金', hex: '#fbbf24' },
  { name: '珊瑚红', hex: '#f87171' }
];

const BG_ALPHA_PRESETS = [
  'rgba(11, 19, 43, 0.56)',
  'rgba(11, 19, 43, 0.84)',
  'rgba(11, 19, 43, 0.94)'
];

function getTimeStrings(offsetMs) {
  const d = new Date(Date.now() + (Number(offsetMs) || 0));
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

function ellipsizeCanvasText(ctx, text, maxWidth) {
  const s = String(text || '');
  if (!s || maxWidth <= 0) return '';
  if (ctx.measureText(s).width <= maxWidth) return s;
  const ell = '...';
  const ellW = ctx.measureText(ell).width;
  let len = s.length;
  while (len > 1 && ctx.measureText(s.slice(0, len)).width + ellW > maxWidth) {
    len--;
  }
  return s.slice(0, len) + ell;
}

function wrapCanvasTextTwoLines(ctx, text, maxWidth) {
  const s = String(text || '');
  if (!s || maxWidth <= 0) return [];
  if (ctx.measureText(s).width <= maxWidth) return [s];
  let splitIdx = 1;
  while (splitIdx < s.length && ctx.measureText(s.slice(0, splitIdx + 1)).width <= maxWidth) {
    splitIdx++;
  }
  const line1 = s.slice(0, splitIdx);
  const rem = s.slice(splitIdx).trim();
  const line2 = ellipsizeCanvasText(ctx, rem, maxWidth);
  return [line1, line2];
}

function drawRoundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  if (ctx.roundRect) {
    ctx.roundRect(x, y, w, h, r);
  } else {
    ctx.rect(x, y, w, h);
  }
}

Page({
  data: {
    statusBarHeight: 44,
    captureMode: 'photo', // 'photo': 光学拍照模式, 'frame': 极速截屏模式
    devicePosition: 'back',
    flashMode: 'off',
    flashLabel: '⚡关',
    styleTemplate: 0,
    tplNames: ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'],
    quickTitleTags: ['现场点位核验', '工程巡检记录', '隐蔽工程验收', '外业测绘定桩', '安全隐患排查'],
    quickNoteTags: ['核验合格', '定位已校准', '施工进行中', '待整改复核', '初检通过'],
    accentColors: ACCENT_PALETTE,
    accentColorIndex: 0,
    accentHex: '#38bdf8',
    fontScale: 1, // 0.88 | 1 | 1.14
    bgAlphaMode: 1, // 0: 通透, 1: 标准, 2: 深邃
    cardBgRgba: 'rgba(11, 19, 43, 0.84)',
    customTitle: '现场点位核验',
    // 默认采用 WGS-84 坐标系，支持手动切换 WGS-84 / CGCS2000 / GCJ-02（不影响高德零偏差跳转）
    coordSys: 'WGS-84',
    useDms: false,
    hasRealFix: false,
    hasCustomPoi: false,
    isManualCoord: false,
    // 硬件真实 GPS 原始锚点（永远不被手动地图选点覆盖，开启防伪验真时用于强制回滚）
    rawGcjLat: 39.908823,
    rawGcjLon: 116.397470,
    rawWgsLat: 39.907419,
    rawWgsLon: 116.391228,
    rawAccuracy: '2.4',
    // 当前生效坐标（高德跳转恒定使用 gcjLat/gcjLon，水印显示根据 coordSys 决断）
    gcjLat: 39.908823,
    gcjLon: 116.397470,
    wgsLat: 39.907419,
    wgsLon: 116.391228,
    accuracy: '2.4',
    altitude: '46.5',
    azimuthDeg: 0,
    azimuthText: '正北 000°',
    // 默认简洁为“实拍点位”，绝不附带啰嗦的 () 括号内容
    shortPoi: '实拍点位',
    fullAddr: '',
    nearbyPois: ['实拍点位', '1号巡检点', '施工现场', '隐蔽工程验收点', '测绘控制点A', '外业打卡现场'],
    note: '',
    timeHm: '09:20',
    timeHms: '09:20:00',
    dateOnlyStr: '2026-11-17',
    weekStr: '星期二',
    dateWeek: '2026-11-17 星期二',
    fullTs: '2026-11-17 09:20:00',
    liveCoordOnlyStr: '39.907419°N, 116.391228°E',
    liveCoordStr: '39.907419°N, 116.391228°E [WGS-84] ±2.4m',
    liveAntiTamperShort: '000000000000',
    swipeBannerText: '',
    showQr: true,
    antiTamperMode: false,
    ntpSynced: false,
    ntpOffsetMs: 0,
    showTime: true,
    showLoc: true,
    showCoords: true,
    showCoordTag: true,
    showAccuracy: true,
    showAddress: false,
    showNote: true,
    showEnv: false,
    autoSaveAlbum: true,
    photos: [],
    latestPhotoPath: '',
    currentPhoto: null,
    editingPhotoId: null,
    showGallery: false,
    showSettings: false,
    showWmModal: false,
    showPoiModal: false,
    sharedBannerVisible: false,
    sharedPoint: null
  },

  onLoad(query) {
    try {
      const sysInfo = wx.getSystemInfoSync();
      this.setData({ statusBarHeight: sysInfo.statusBarHeight || 44 });
    } catch (e) {}

    // 1. 恢复本地持久化的用户水印配置与已拍相册
    this.loadPersistedConfigAndPhotos();

    // 2. 联网校准授时服务器时间
    this.syncTrustedNetworkTime();
    this.refreshClock();
    this.clockTimer = setInterval(() => this.refreshClock(), 1000);

    // 3. 检查是否由好友分享卡片或微信长按识别水印二维码进入小程序
    this.checkIncomingFriendLocation(query);

    // 4. 启动高精度定位、实时位置移动监听与电子罗盘
    this.startHighAccuracyLocation();
    this.startContinuousLocationTracking();
    this.startCompassTracking();
  },

  onShow() {
    if (!this.clockTimer) {
      this.clockTimer = setInterval(() => this.refreshClock(), 1000);
    }
  },

  onHide() {
    if (this.clockTimer) {
      clearInterval(this.clockTimer);
      this.clockTimer = null;
    }
  },

  onUnload() {
    if (this.clockTimer) clearInterval(this.clockTimer);
    try { wx.stopCompass(); } catch (e) {}
    try { wx.stopLocationUpdate(); } catch (e) {}
  },

  /**
   * 读取并恢复本地持久化的用户设置与历史照片
   */
  loadPersistedConfigAndPhotos() {
    try {
      const savedCfg = wx.getStorageSync(STORAGE_KEY_CFG);
      if (savedCfg && typeof savedCfg === 'object') {
        const idx = Number(savedCfg.accentColorIndex) || 0;
        const alphaIdx = savedCfg.bgAlphaMode !== undefined ? Number(savedCfg.bgAlphaMode) : 1;
        this.setData({
          captureMode: savedCfg.captureMode || 'photo',
          styleTemplate: savedCfg.styleTemplate !== undefined ? Number(savedCfg.styleTemplate) : 0,
          coordSys: savedCfg.coordSys || 'WGS-84',
          useDms: Boolean(savedCfg.useDms),
          customTitle: savedCfg.customTitle || '现场点位核验',
          note: savedCfg.note || '',
          accentColorIndex: idx,
          accentHex: (ACCENT_PALETTE[idx] || ACCENT_PALETTE[0]).hex,
          fontScale: Number(savedCfg.fontScale) || 1,
          bgAlphaMode: alphaIdx,
          cardBgRgba: BG_ALPHA_PRESETS[alphaIdx] || BG_ALPHA_PRESETS[1],
          showQr: savedCfg.showQr !== false,
          antiTamperMode: Boolean(savedCfg.antiTamperMode),
          showTime: savedCfg.showTime !== false,
          showLoc: savedCfg.showLoc !== false,
          showCoords: savedCfg.showCoords !== false,
          showCoordTag: savedCfg.showCoordTag !== false,
          showAccuracy: savedCfg.showAccuracy !== false,
          showAddress: Boolean(savedCfg.showAddress),
          showNote: savedCfg.showNote !== false,
          showEnv: Boolean(savedCfg.showEnv),
          autoSaveAlbum: savedCfg.autoSaveAlbum !== false
        }, () => this.updateCoordString());
      }
    } catch (e) {}

    try {
      const savedPhotos = wx.getStorageSync(STORAGE_KEY_PHOTOS);
      if (Array.isArray(savedPhotos) && savedPhotos.length > 0) {
        this.setData({
          photos: savedPhotos,
          latestPhotoPath: savedPhotos[0].wmPath,
          currentPhoto: savedPhotos[0]
        });
      }
    } catch (e) {}
  },

  persistUserConfig() {
    try {
      wx.setStorageSync(STORAGE_KEY_CFG, {
        captureMode: this.data.captureMode,
        styleTemplate: this.data.styleTemplate,
        coordSys: this.data.coordSys,
        useDms: this.data.useDms,
        customTitle: this.data.customTitle,
        note: this.data.note,
        accentColorIndex: this.data.accentColorIndex,
        fontScale: this.data.fontScale,
        bgAlphaMode: this.data.bgAlphaMode,
        showQr: this.data.showQr,
        antiTamperMode: this.data.antiTamperMode,
        showTime: this.data.showTime,
        showLoc: this.data.showLoc,
        showCoords: this.data.showCoords,
        showCoordTag: this.data.showCoordTag,
        showAccuracy: this.data.showAccuracy,
        showAddress: this.data.showAddress,
        showNote: this.data.showNote,
        showEnv: this.data.showEnv,
        autoSaveAlbum: this.data.autoSaveAlbum
      });
    } catch (e) {}
  },

  persistPhotosList(nextPhotos) {
    try {
      wx.setStorageSync(STORAGE_KEY_PHOTOS, (nextPhotos || []).slice(0, 25));
    } catch (e) {}
  },

  saveTempFilePermanently(tempPath, cb) {
    try {
      if (wx.getFileSystemManager && wx.env && wx.env.USER_DATA_PATH) {
        const fs = wx.getFileSystemManager();
        const ext = (tempPath.match(/\.[a-zA-Z0-9]+$/) || ['.jpg'])[0];
        const dest = `${wx.env.USER_DATA_PATH}/amap_wm_${Date.now()}_${Math.floor(Math.random() * 1000)}${ext}`;
        fs.saveFile({
          tempFilePath: tempPath,
          filePath: dest,
          success: res => cb(res.savedFilePath || dest),
          fail: () => cb(tempPath)
        });
        return;
      }
    } catch (e) {}
    cb(tempPath);
  },

  /**
   * 联网校准真实授时服务器时间
   */
  syncTrustedNetworkTime() {
    const t0 = Date.now();
    wx.request({
      url: 'https://uri.amap.com/marker',
      method: 'HEAD',
      timeout: 2500,
      success: res => {
        const dateHdr = res.header && (res.header.Date || res.header.date);
        if (dateHdr) {
          const srvTime = Date.parse(dateHdr);
          if (!isNaN(srvTime) && srvTime > 1700000000000) {
            const rttHalf = Math.round((Date.now() - t0) / 2);
            const offset = srvTime + rttHalf - Date.now();
            this.setData({
              ntpSynced: true,
              ntpOffsetMs: offset
            }, () => this.refreshClock());
          }
        }
      }
    });
  },

  startCompassTracking() {
    try {
      wx.onCompassChange(res => {
        const deg = Math.round(res.direction || 0);
        if (Math.abs(deg - this.data.azimuthDeg) >= 5) {
          this.setData({
            azimuthDeg: deg,
            azimuthText: formatAzimuthText(deg)
          });
        }
      });
      wx.startCompass();
    } catch (e) {}
  },

  /**
   * 启动实时位置移动跟随监听（当用户未处于手动选点状态时，边走边拍自动刷新真实 GPS）
   */
  startContinuousLocationTracking() {
    try {
      if (wx.startLocationUpdate && wx.onLocationChange) {
        wx.startLocationUpdate({
          success: () => {
            wx.onLocationChange(loc => {
              if (!loc || !loc.latitude || !loc.longitude) return;
              this.recordHardwareGpsFix(loc.latitude, loc.longitude, loc.accuracy, loc.altitude, false);
            });
          }
        });
      }
    } catch (e) {}
  },

  /**
   * 好友收图跳高德 & SHA-256 防伪核验核心入口
   */
  checkIncomingFriendLocation(query) {
    if (!query) return;
    let parsed = null;
    if (query.q) {
      parsed = parseLocationPayload(query.q);
    } else if (query.lat && query.lon) {
      const gcjLat = Number(parseFloat(query.lat).toFixed(6));
      const gcjLon = Number(parseFloat(query.lon).toFixed(6));
      const shortPoi = query.poi ? decodeURIComponent(query.poi) : '实拍点位';
      const ts = query.ts ? decodeURIComponent(query.ts) : '';
      const sig = query.sig || '';
      const expected = sig ? computeAntiTamperHash(gcjLat, gcjLon, ts, shortPoi) : '';
      parsed = {
        gcjLat,
        gcjLon,
        shortPoi,
        timestamp: ts,
        signature: sig,
        hasAntiTamper: Boolean(sig),
        verified: Boolean(sig && expected.startsWith(sig.toUpperCase()))
      };
    }
    if (parsed && !isNaN(parsed.gcjLat) && !isNaN(parsed.gcjLon)) {
      this.setData({
        sharedBannerVisible: true,
        sharedPoint: parsed
      });
      const verifyLine = parsed.hasAntiTamper
        ? (parsed.verified ? '\n🛡 SHA-256 防篡改签名核验通过！' : '\n⚠️ 防伪签名不匹配，坐标或时间可能被改动！')
        : '';
      wx.showModal({
        title: `📍 收到实拍位置：${parsed.shortPoi}`,
        content: `高德 GCJ-02 坐标：${parsed.gcjLat}°N, ${parsed.gcjLon}°E${verifyLine}\n点击确定立即打开地图并拉起高德导航！`,
        confirmText: '跳高德',
        success: res => {
          if (res.confirm) {
            this.openSharedLocationInMap();
          }
        }
      });
    }
  },

  openSharedLocationInMap() {
    const pt = this.data.sharedPoint;
    if (!pt) return;
    wx.openLocation({
      latitude: Number(pt.gcjLat),
      longitude: Number(pt.gcjLon),
      name: pt.shortPoi || '实拍点位',
      address: `高德 GCJ-02 零偏差坐标: ${pt.gcjLat}, ${pt.gcjLon}`,
      scale: 18
    });
  },

  closeSharedBanner() {
    this.setData({ sharedBannerVisible: false });
  },

  scanFriendPhotoQr() {
    wx.scanCode({
      scanType: ['qrCode'],
      onlyFromCamera: false,
      success: res => {
        const parsed = parseLocationPayload(res.result);
        if (parsed) {
          this.setData({
            sharedBannerVisible: true,
            sharedPoint: parsed
          });
          if (parsed.hasAntiTamper) {
            wx.showToast({
              title: parsed.verified ? '🛡 防伪验真通过' : '⚠️ 签名校验异常',
              icon: parsed.verified ? 'success' : 'none'
            });
          }
          wx.openLocation({
            latitude: Number(parsed.gcjLat),
            longitude: Number(parsed.gcjLon),
            name: parsed.shortPoi || '实拍点位',
            address: `高德 GCJ-02: ${parsed.gcjLat}, ${parsed.gcjLon}`,
            scale: 18
          });
        } else {
          wx.showToast({ title: '未在码中识别到高德坐标', icon: 'none' });
        }
      }
    });
  },

  startHighAccuracyLocation(forceOverwriteManual) {
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      highAccuracyExpireTime: 3500,
      success: res => {
        this.recordHardwareGpsFix(res.latitude, res.longitude, res.accuracy, res.altitude, Boolean(forceOverwriteManual));
      },
      fail: () => {
        const smart = inferSmartPoiFromCoords(this.data.gcjLat, this.data.gcjLon);
        this.setData({
          shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : '实拍点位',
          fullAddr: this.data.hasCustomPoi ? this.data.fullAddr : smart.fullAddr,
          nearbyPois: smart.nearbyPois
        }, () => this.updateCoordString());
      }
    });
  },

  /**
   * 记录硬件真实 GPS 定位结果，维护双轨隔离坐标：
   * - rawGcjLat / rawGcjLon / rawWgsLat / rawWgsLon 始终保存手机硬件真实卫星坐标；
   * - 若当前处于防篡改验真模式 (antiTamperMode) 或未手动偏移坐标 (!isManualCoord) 或强制刷新，则同步更新当前生效坐标。
   */
  recordHardwareGpsFix(lat, lon, accuracy, altitude, forceOverwriteManual) {
    const gcjLat = Number(Number(lat).toFixed(6));
    const gcjLon = Number(Number(lon).toFixed(6));
    const wgs = gcj02ToWgs84(gcjLat, gcjLon);
    const acc = Number(accuracy || 2.8).toFixed(1);
    const alt = altitude ? Number(altitude).toFixed(1) : this.data.altitude;
    const smart = inferSmartPoiFromCoords(gcjLat, gcjLon);

    const shouldApplyActive = forceOverwriteManual || this.data.antiTamperMode || !this.data.isManualCoord;
    const patch = {
      hasRealFix: true,
      rawGcjLat: gcjLat,
      rawGcjLon: gcjLon,
      rawWgsLat: wgs.lat,
      rawWgsLon: wgs.lon,
      rawAccuracy: acc,
      altitude: alt,
      nearbyPois: smart.nearbyPois
    };

    if (shouldApplyActive) {
      patch.isManualCoord = false;
      patch.gcjLat = gcjLat;
      patch.gcjLon = gcjLon;
      patch.wgsLat = wgs.lat;
      patch.wgsLon = wgs.lon;
      patch.accuracy = acc;
      if (!this.data.hasCustomPoi) {
        patch.shortPoi = '实拍点位';
        patch.fullAddr = smart.fullAddr;
      }
    }

    this.setData(patch, () => this.updateCoordString());

    // 在线逆地理编码增强（解析街道与周边候选名，未自定义时更新简洁地名）
    if (shouldApplyActive && !this.data.hasCustomPoi) {
      wx.request({
        url: `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${wgs.lat}&lon=${wgs.lon}&zoom=18&accept-language=zh-CN`,
        timeout: 2500,
        success: resp => {
          const d = resp.data;
          if (d && (d.name || d.address)) {
            const addr = d.address || {};
            const road = addr.road || '';
            const suburb = addr.suburb || addr.district || '';
            const city = addr.city || addr.state || '';
            const cleanName = (d.name && d.name.length <= 12) ? d.name : (road || suburb || '实拍点位');
            const full = `${city}${suburb !== city ? suburb : ''}${road}${d.name && d.name !== road ? d.name : ''}` || smart.fullAddr;
            const cands = ['实拍点位', cleanName, road, suburb ? `${suburb}巡检点` : '', ...smart.nearbyPois]
              .filter(Boolean)
              .filter((v, i, a) => a.indexOf(v) === i)
              .slice(0, 6);
            this.setData({
              shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : cleanName,
              fullAddr: full,
              nearbyPois: cands
            }, () => this.updateCoordString());
          }
        }
      });
    }
  },

  /**
   * 恢复真实硬件 GPS 坐标（清除手动地图选点偏移）
   */
  restoreRealGpsCoordinates() {
    this.setData({
      isManualCoord: false,
      gcjLat: this.data.rawGcjLat,
      gcjLon: this.data.rawGcjLon,
      wgsLat: this.data.rawWgsLat,
      wgsLon: this.data.rawWgsLon,
      accuracy: this.data.rawAccuracy
    }, () => {
      this.updateCoordString();
      this.startHighAccuracyLocation(true);
      wx.showToast({ title: '已恢复手机真实GPS坐标', icon: 'none' });
    });
  },

  refreshRealGpsNow() {
    this.setData({ hasCustomPoi: false, isManualCoord: false });
    this.startHighAccuracyLocation(true);
    wx.showToast({ title: '已刷新真实GPS坐标与点位', icon: 'none' });
  },

  /**
   * 模式 A：🏷 地图选名模式（仅从地图搜索/点选附近建筑名称填入地点简称，100% 保持真实 GPS 经纬度不变！）
   * 即使在防篡改验真模式下也可安全使用，彻底消除与“地图选点”的混淆
   */
  choosePoiNameOnlyOnMap() {
    wx.chooseLocation({
      latitude: this.data.gcjLat,
      longitude: this.data.gcjLon,
      success: res => {
        const pickedName = (res.name || res.address || '').trim();
        if (!pickedName) return;
        const nextCands = [pickedName, ...this.data.nearbyPois]
          .filter(Boolean)
          .filter((v, i, a) => a.indexOf(v) === i)
          .slice(0, 6);
        this.setData({
          hasCustomPoi: true,
          shortPoi: pickedName,
          fullAddr: res.address || this.data.fullAddr,
          nearbyPois: nextCands
        }, () => {
          this.updateCoordString();
          wx.showToast({
            title: '🏷 已借用地图名称(GPS坐标未变)',
            icon: 'none'
          });
        });
      },
      fail: err => this.handleChooseLocationFail(err)
    });
  },

  /**
   * 模式 B：📌 地图选点改坐标模式（连同经纬度一起迁移至所选位置，防篡改模式下严禁使用）
   */
  choosePoiOnMap() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防伪模式下已锁定真实GPS，请用上方“地图选名”', icon: 'none' });
      return;
    }
    wx.chooseLocation({
      latitude: this.data.gcjLat,
      longitude: this.data.gcjLon,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const wgs = gcj02ToWgs84(gcjLat, gcjLon);
        const pickedName = (res.name || '').trim() || this.data.shortPoi;
        this.setData({
          hasCustomPoi: true,
          isManualCoord: true,
          gcjLat,
          gcjLon,
          wgsLat: wgs.lat,
          wgsLon: wgs.lon,
          shortPoi: pickedName,
          fullAddr: res.address || this.data.fullAddr,
          showPoiModal: false
        }, () => {
          this.updateCoordString();
          wx.showToast({ title: '📌 已迁移至地图选点坐标', icon: 'none' });
        });
      },
      fail: err => this.handleChooseLocationFail(err)
    });
  },

  handleChooseLocationFail(err) {
    if (err && err.errMsg && err.errMsg.indexOf('auth deny') !== -1) {
      wx.showModal({
        title: '需要位置权限',
        content: '请在设置中开启位置信息权限以打开地图',
        confirmText: '去设置',
        success: r => { if (r.confirm) wx.openSetting(); }
      });
    }
  },

  /**
   * 取景器左右滑动手势切换 5 款水印样式
   */
  onViewfinderTouchStart(e) {
    if (!e.touches || e.touches.length !== 1) return;
    this.touchStartX = e.touches[0].clientX;
    this.touchStartY = e.touches[0].clientY;
  },

  onViewfinderTouchEnd(e) {
    if (this.touchStartX === undefined || !e.changedTouches || !e.changedTouches.length) return;
    const dx = e.changedTouches[0].clientX - this.touchStartX;
    const dy = e.changedTouches[0].clientY - this.touchStartY;
    this.touchStartX = undefined;
    if (Math.abs(dx) > 42 && Math.abs(dx) > Math.abs(dy) * 1.3) {
      const total = this.data.tplNames.length;
      const nextIdx = ((this.data.styleTemplate + (dx < 0 ? 1 : -1)) % total + total) % total;
      this.applyTemplateIndex(nextIdx, true);
    }
  },

  applyTemplateIndex(idx, showSwipeBanner) {
    const name = this.data.tplNames[idx] || '';
    if (wx.vibrateShort) {
      try { wx.vibrateShort({ type: 'light' }); } catch (e) {}
    }
    this.setData({
      styleTemplate: idx,
      swipeBannerText: showSwipeBanner ? `🎨 样式 ${idx + 1}/5 · ${name}` : ''
    }, () => {
      this.refreshClock();
      this.persistUserConfig();
    });
    if (showSwipeBanner) {
      if (this.bannerTimer) clearTimeout(this.bannerTimer);
      this.bannerTimer = setTimeout(() => {
        this.setData({ swipeBannerText: '' });
      }, 1500);
    }
  },

  refreshClock() {
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const sig = computeAntiTamperHash(this.data.gcjLat, this.data.gcjLon, t.fullTs, this.data.shortPoi).slice(0, 12);
    const nextPatch = {};
    if (t.hm !== this.data.timeHm) nextPatch.timeHm = t.hm;
    if (t.dateOnlyStr !== this.data.dateOnlyStr) nextPatch.dateOnlyStr = t.datePart;
    if (t.weekStr !== this.data.weekStr) nextPatch.weekStr = t.weekStr;
    if (t.dateWeek !== this.data.dateWeek) nextPatch.dateWeek = t.dateWeek;
    if (this.data.styleTemplate !== 0 || this.data.antiTamperMode) {
      nextPatch.timeHms = t.hms;
      nextPatch.fullTs = t.fullTs;
      nextPatch.liveAntiTamperShort = sig;
    }
    if (Object.keys(nextPatch).length > 0) {
      this.setData(nextPatch);
    }
  },

  updateCoordString() {
    const resolved = resolveDisplayCoordinates(
      this.data.coordSys,
      this.data.gcjLat,
      this.data.gcjLon,
      this.data.wgsLat,
      this.data.wgsLon
    );
    const coordOnly = formatCoords(resolved.lat, resolved.lon, this.data.useDms);
    const tagPart = this.data.showCoordTag ? ` [${resolved.tag}]` : '';
    const accPart = this.data.showAccuracy ? ` ±${this.data.accuracy}m` : '';
    const fullStr = `${coordOnly}${tagPart}${accPart}`;
    const sig = computeAntiTamperHash(this.data.gcjLat, this.data.gcjLon, this.data.fullTs, this.data.shortPoi).slice(0, 12);
    this.setData({
      liveCoordOnlyStr: coordOnly,
      liveCoordStr: fullStr,
      liveAntiTamperShort: sig
    });
  },

  toggleCaptureMode() {
    const next = this.data.captureMode === 'photo' ? 'frame' : 'photo';
    this.setData({ captureMode: next }, () => this.persistUserConfig());
    wx.showToast({
      title: next === 'photo' ? '📷 光学拍照模式' : '⚡ 极速截屏模式',
      icon: 'none'
    });
  },

  setCaptureMode(e) {
    const mode = e.currentTarget.dataset.mode;
    this.setData({ captureMode: mode }, () => this.persistUserConfig());
  },

  selectCoordSystem(e) {
    const sys = e.currentTarget.dataset.sys;
    if (!sys) return;
    this.setData({ coordSys: sys }, () => {
      this.updateCoordString();
      this.persistUserConfig();
      wx.showToast({
        title: `水印坐标：${sys} (高德跳转零偏差)`,
        icon: 'none'
      });
    });
  },

  setDmsFormat(e) {
    const useDms = e.currentTarget.dataset.dms === '1';
    this.setData({ useDms }, () => {
      this.updateCoordString();
      this.persistUserConfig();
    });
  },

  selectAccentColor(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    const hex = (ACCENT_PALETTE[idx] || ACCENT_PALETTE[0]).hex;
    this.setData({ accentColorIndex: idx, accentHex: hex }, () => this.persistUserConfig());
  },

  selectFontScale(e) {
    const sc = Number(e.currentTarget.dataset.scale) || 1;
    this.setData({ fontScale: sc }, () => this.persistUserConfig());
  },

  selectBgAlpha(e) {
    const idx = Number(e.currentTarget.dataset.alpha);
    const rgba = BG_ALPHA_PRESETS[idx] || BG_ALPHA_PRESETS[1];
    this.setData({ bgAlphaMode: idx, cardBgRgba: rgba }, () => this.persistUserConfig());
  },

  toggleWatermarkField(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    const nextVal = !this.data[field];
    this.setData({ [field]: nextVal }, () => {
      this.updateCoordString();
      this.persistUserConfig();
    });
  },

  cycleFlashMode() {
    const modes = ['off', 'on', 'auto', 'torch'];
    const labels = ['⚡关', '⚡开', '⚡自动', '⚡常亮'];
    const nextIdx = (modes.indexOf(this.data.flashMode) + 1) % modes.length;
    this.setData({
      flashMode: modes[nextIdx],
      flashLabel: labels[nextIdx]
    });
  },

  flipCamera() {
    this.setData({
      devicePosition: this.data.devicePosition === 'back' ? 'front' : 'back'
    });
  },

  selectTemplate(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    this.applyTemplateIndex(idx, false);
  },

  selectQuickPoi(e) {
    const poi = e.currentTarget.dataset.poi;
    if (poi) {
      this.setData({ shortPoi: poi, hasCustomPoi: true }, () => this.updateCoordString());
    }
  },

  selectQuickTitle(e) {
    const title = e.currentTarget.dataset.title;
    if (title) {
      this.setData({ customTitle: title }, () => this.persistUserConfig());
    }
  },

  appendQuickNote(e) {
    const tag = e.currentTarget.dataset.note;
    if (!tag) return;
    const next = this.data.note ? `${this.data.note} · ${tag}` : tag;
    this.setData({ note: next, showNote: true }, () => this.persistUserConfig());
  },

  clearNoteInput() {
    this.setData({ note: '' }, () => this.persistUserConfig());
  },

  handleShutterTap() {
    if (this.isCompositing) return;
    const camCtx = wx.createCameraContext();
    const quality = this.data.captureMode === 'photo' ? 'high' : 'normal';
    camCtx.takePhoto({
      quality,
      success: res => {
        const t = getTimeStrings(this.data.ntpOffsetMs);
        // 防篡改模式下强制使用硬件真实 GPS 坐标，杜绝任何潜在的选点残留
        const activeGcjLat = this.data.antiTamperMode ? this.data.rawGcjLat : this.data.gcjLat;
        const activeGcjLon = this.data.antiTamperMode ? this.data.rawGcjLon : this.data.gcjLon;
        const activeWgsLat = this.data.antiTamperMode ? this.data.rawWgsLat : this.data.wgsLat;
        const activeWgsLon = this.data.antiTamperMode ? this.data.rawWgsLon : this.data.wgsLon;
        const antiHash = computeAntiTamperHash(activeGcjLat, activeGcjLon, t.fullTs, this.data.shortPoi);

        const snapshotCfg = {
          styleTemplate: this.data.styleTemplate,
          customTitle: this.data.customTitle,
          coordSys: this.data.coordSys,
          useDms: this.data.useDms,
          accentColorIndex: this.data.accentColorIndex,
          accentHex: this.data.accentHex,
          fontScale: this.data.fontScale,
          bgAlphaMode: this.data.bgAlphaMode,
          cardBgRgba: this.data.cardBgRgba,
          gcjLat: activeGcjLat,
          gcjLon: activeGcjLon,
          wgsLat: activeWgsLat,
          wgsLon: activeWgsLon,
          accuracy: this.data.accuracy,
          altitude: this.data.altitude,
          azimuthDeg: this.data.azimuthDeg,
          azimuthText: this.data.azimuthText,
          shortPoi: this.data.shortPoi || '实拍点位',
          fullAddr: this.data.fullAddr,
          note: this.data.note,
          hm: t.hm,
          hms: t.hms,
          dateOnlyStr: t.datePart,
          weekStr: t.weekStr,
          dateWeek: t.dateWeek,
          fullTs: t.fullTs,
          showQr: this.data.showQr,
          antiTamperMode: this.data.antiTamperMode,
          ntpSynced: this.data.ntpSynced,
          antiTamperHash: antiHash,
          showTime: this.data.showTime,
          showLoc: this.data.showLoc,
          showCoords: this.data.showCoords,
          showCoordTag: this.data.showCoordTag,
          showAccuracy: this.data.showAccuracy,
          showAddress: this.data.showAddress,
          showNote: this.data.showNote,
          showEnv: this.data.showEnv
        };

        this.compositeWatermarkToTempFile(res.tempImagePath, snapshotCfg, null);
      },
      fail: () => {
        wx.showToast({ title: '拍照失败，请检查相机权限', icon: 'none' });
      }
    });
  },

  /**
   * 离屏高清 Canvas 2D 水印合成（支持新拍照片与相册已拍照片的无损重绘）
   */
  compositeWatermarkToTempFile(rawImagePath, cfg, existingPhotoId) {
    this.isCompositing = true;
    wx.showLoading({ title: existingPhotoId ? '无损重绘水印...' : '合成高清水印...' });
    const query = wx.createSelectorQuery();
    query.select('#compositeCanvas')
      .fields({ node: true, size: true })
      .exec(res => {
        if (!res || !res[0] || !res[0].node) {
          this.isCompositing = false;
          wx.hideLoading();
          return;
        }
        const canvas = res[0].node;
        const ctx = canvas.getContext('2d');
        const img = canvas.createImage();

        img.onload = () => {
          const naturalW = img.width || 1080;
          const naturalH = img.height || 1440;
          const w = 1080;
          const h = Math.round((naturalH / naturalW) * w) || 1440;
          canvas.width = w;
          canvas.height = h;

          ctx.drawImage(img, 0, 0, w, h);
          this.drawCanvasWatermarkAndQr(ctx, w, h, cfg);

          wx.canvasToTempFilePath({
            canvas,
            width: w,
            height: h,
            destWidth: w,
            destHeight: h,
            fileType: 'jpg',
            quality: 0.95,
            success: out => {
              this.isCompositing = false;
              wx.hideLoading();

              if (existingPhotoId) {
                this.saveTempFilePermanently(out.tempFilePath, savedWmPath => {
                  const updatedPhotos = this.data.photos.map(p => {
                    if (p.id === existingPhotoId) {
                      return { ...p, wmPath: savedWmPath, cfg };
                    }
                    return p;
                  });
                  const updatedCur = updatedPhotos.find(p => p.id === existingPhotoId) || updatedPhotos[0];
                  const restoreLive = this.preEditBackup || {};
                  this.preEditBackup = null;
                  this.setData({
                    ...restoreLive,
                    photos: updatedPhotos,
                    currentPhoto: updatedCur,
                    latestPhotoPath: updatedPhotos[0] ? updatedPhotos[0].wmPath : '',
                    editingPhotoId: null,
                    showWmModal: false
                  }, () => this.updateCoordString());
                  this.persistPhotosList(updatedPhotos);
                  wx.showToast({ title: '已无损更新照片水印', icon: 'success' });
                });
                return;
              }

              this.saveTempFilePermanently(rawImagePath, savedRawPath => {
                this.saveTempFilePermanently(out.tempFilePath, savedWmPath => {
                  const entry = {
                    id: 'wx_' + Date.now(),
                    rawPath: savedRawPath,
                    wmPath: savedWmPath,
                    cfg
                  };
                  const nextPhotos = [entry, ...this.data.photos];
                  this.setData({
                    photos: nextPhotos,
                    latestPhotoPath: savedWmPath,
                    currentPhoto: entry
                  });
                  this.persistPhotosList(nextPhotos);
                });
              });

              if (this.data.autoSaveAlbum) {
                wx.saveImageToPhotosAlbum({
                  filePath: out.tempFilePath,
                  success: () => {
                    wx.showToast({
                      title: `已存相册(${cfg.coordSys}水印·高德零偏差)`,
                      icon: 'none'
                    });
                  },
                  fail: () => {
                    wx.showToast({ title: '已存入应用内相册', icon: 'none' });
                  }
                });
              }
            },
            fail: () => {
              this.isCompositing = false;
              wx.hideLoading();
            }
          });
        };
        img.onerror = () => {
          this.isCompositing = false;
          wx.hideLoading();
          wx.showToast({ title: '底图读取失败', icon: 'none' });
        };
        img.src = rawImagePath;
      });
  },

  /**
   * 5 款差异化抗溢出 Canvas 水印 + 高德零偏差定位二维码渲染引擎
   * 解决：
   * 1. 胶片底栏采用上下双层全宽布局，经纬度 100% 完整显示，彻底根除日期/坐标重复显示
   * 2. 去除啰嗦括号与无法正常显示的缩略图，所有长地名/长经纬度支持自适应字号与双行折行
   * 3. 水印显示支持 WGS-84 / CGCS2000 / GCJ-02，而二维码恒定编码 GCJ-02 实现高德零偏差跳转
   */
  drawCanvasWatermarkAndQr(ctx, w, h, cfg) {
    const resolved = resolveDisplayCoordinates(cfg.coordSys, cfg.gcjLat, cfg.gcjLon, cfg.wgsLat, cfg.wgsLon);
    const coordOnly = formatCoords(resolved.lat, resolved.lon, cfg.useDms);
    const tagStr = cfg.showCoordTag !== false ? ` [${resolved.tag}]` : '';
    const accStr = cfg.showAccuracy !== false ? ` ±${cfg.accuracy}m` : '';
    const coordLine = `${coordOnly}${tagStr}${accStr}`;

    const envLine = cfg.showEnv ? `海拔 ${cfg.altitude}m · 方位 ${cfg.azimuthText}` : '';
    const sigHash = cfg.antiTamperHash || computeAntiTamperHash(cfg.gcjLat, cfg.gcjLon, cfg.fullTs, cfg.shortPoi);
    const antiLine = cfg.antiTamperMode ? `🛡 ${cfg.ntpSynced ? 'NTP真时已锁' : '防伪锁定'} · SHA256:${sigHash.slice(0, 12)}` : '';

    const fScale = Number(cfg.fontScale) || 1.0;
    const accentColor = cfg.accentHex || '#38bdf8';
    const cardBg = cfg.cardBgRgba || 'rgba(11, 19, 43, 0.84)';
    const cleanPoi = cfg.shortPoi || '实拍点位';
    const weekPart = cfg.weekStr || (cfg.dateWeek ? cfg.dateWeek.split(' ')[1] : '') || '';

    if (cfg.styleTemplate === 0) {
      // 模板 0: 经典大字时间 (严格扣除右侧二维码安全区，自适应 DMS 经纬度字号)
      const grad = ctx.createLinearGradient(0, h - 390, 0, h);
      grad.addColorStop(0, 'rgba(0,0,0,0)');
      grad.addColorStop(1, 'rgba(0,0,0,0.68)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, h - 390, w, 390);

      const qrReserveRight = cfg.showQr ? 186 : 28;
      const leftMargin = 28;
      const safeZoneW = w - leftMargin - qrReserveRight;
      const cx = cfg.showQr ? (leftMargin + safeZoneW / 2) : (w / 2);
      const maxLineW = safeZoneW - 16;
      let curY = h - 40;

      if (antiLine) {
        ctx.font = `bold ${Math.round(20 * fScale)}px monospace`;
        ctx.fillStyle = '#34d399';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, antiLine, maxLineW), cx, curY);
        curY -= Math.round(34 * fScale);
      }
      if (cfg.showNote && cfg.note) {
        ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
        ctx.fillStyle = '#fde68a';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, '备注：' + cfg.note, maxLineW), cx, curY);
        curY -= Math.round(35 * fScale);
      }
      if (envLine) {
        ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
        ctx.fillStyle = '#a7f3d0';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, envLine, maxLineW), cx, curY);
        curY -= Math.round(33 * fScale);
      }
      if (cfg.showAddress && cfg.fullAddr) {
        ctx.font = `normal ${Math.round(22 * fScale)}px sans-serif`;
        ctx.fillStyle = '#cbd5e1';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, cfg.fullAddr, maxLineW), cx, curY);
        curY -= Math.round(34 * fScale);
      }
      if (cfg.showCoords) {
        const coordFontPx = Math.round((cfg.useDms ? 20 : 23) * fScale);
        ctx.font = `bold ${coordFontPx}px monospace`;
        const clippedCoord = ellipsizeCanvasText(ctx, coordLine, maxLineW - 36);
        const tw = ctx.measureText(clippedCoord).width;
        ctx.fillStyle = cardBg;
        drawRoundedRect(ctx, cx - tw / 2 - 18, curY - 27, tw + 36, 38, 19);
        ctx.fill();
        ctx.strokeStyle = accentColor;
        ctx.lineWidth = 1.5;
        ctx.stroke();
        ctx.fillStyle = accentColor;
        ctx.textAlign = 'center';
        ctx.fillText(clippedCoord, cx, curY);
        curY -= Math.round(48 * fScale);
      }
      if (cfg.showTime || cfg.showLoc) {
        ctx.font = `bold ${Math.round(30 * fScale)}px sans-serif`;
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        const lineRaw = `${cfg.showTime ? cfg.dateWeek + '  ' : ''}${cfg.showLoc ? '📍' + cleanPoi : ''}`;
        ctx.fillText(ellipsizeCanvasText(ctx, lineRaw, maxLineW), cx, curY);
        curY -= Math.round(56 * fScale);
      }
      if (cfg.showTime) {
        ctx.font = `300 ${Math.round(108 * fScale)}px sans-serif`;
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        ctx.fillText(cfg.hm, cx, curY);
      }
    } else if (cfg.styleTemplate === 1) {
      // 模板 1: 工程巡检卡片 (无缩略图挤占，全宽舒展，地点与详细地址独立分行，绝无冗余括号)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const padX = 24;
      const padY = 18;
      const headerH = 44;
      const lineGap = Math.round(35 * fScale);
      const textStartX = boxLeft + padX;
      const maxTextW = boxW - padX * 2;

      const rowFontPx = Math.round((cfg.useDms ? 20 : 22) * fScale);
      ctx.font = `bold ${rowFontPx}px monospace`;
      const rows = [];
      if (cfg.showCoords) rows.push(['经纬', coordLine, accentColor]);
      if (cfg.showLoc) {
        const prefixW = ctx.measureText('点位：').width;
        const wrappedPoi = wrapCanvasTextTwoLines(ctx, cleanPoi, maxTextW - prefixW);
        if (wrappedPoi.length > 0) rows.push(['点位', wrappedPoi[0], '#ffffff']);
        if (wrappedPoi.length > 1 && wrappedPoi[1]) rows.push(['续名', wrappedPoi[1], '#e2e8f0']);
      }
      if (cfg.showAddress && cfg.fullAddr) {
        rows.push(['地址', cfg.fullAddr, '#cbd5e1']);
      }
      if (envLine) rows.push(['环境', envLine, '#a7f3d0']);
      if (cfg.showTime) rows.push(['时间', `${cfg.fullTs} ${weekPart}`, '#e2e8f0']);
      if (cfg.showNote && cfg.note) rows.push(['备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) rows.push(['验真', `SHA256:${sigHash.slice(0, 12)} (${cfg.ntpSynced ? 'NTP真时' : '已锁'})`, '#34d399']);

      const boxH = padY * 2 + headerH + rows.length * lineGap;
      const boxTop = h - boxH - 28;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 18);
      ctx.fill();
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2;
      ctx.stroke();

      ctx.fillStyle = accentColor;
      ctx.fillRect(boxLeft + padX, boxTop + padY, 8, 26);
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(25 * fScale)}px sans-serif`;
      ctx.textAlign = 'left';
      const titleStr = `${cfg.customTitle || '现场点位核验'} · ${cfg.antiTamperMode ? '🛡防伪已锁' : resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - padX * 2 - 24), boxLeft + padX + 18, boxTop + padY + 23);

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.16)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(boxLeft + padX, boxTop + padY + headerH - 8);
      ctx.lineTo(boxLeft + boxW - padX, boxTop + padY + headerH - 8);
      ctx.stroke();

      ctx.font = `bold ${rowFontPx}px monospace`;
      ctx.textAlign = 'left';
      rows.forEach((r, idx) => {
        const y = boxTop + padY + headerH + (idx + 0.78) * lineGap;
        const prefix = r[0] + '：';
        ctx.fillStyle = '#94a3b8';
        ctx.fillText(prefix, textStartX, y);
        const pw = ctx.measureText(prefix).width;
        ctx.fillStyle = r[2];
        ctx.fillText(ellipsizeCanvasText(ctx, r[1], maxTextW - pw), textStartX + pw, y);
      });
    } else if (cfg.styleTemplate === 2) {
      // 模板 2: 经纬网格测绘 (全宽双栏十字测绘网格)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const hasFooter = Boolean(antiLine || (cfg.showNote && cfg.note) || (cfg.showAddress && cfg.fullAddr) || envLine);
      const boxH = hasFooter ? 192 : 150;
      const boxTop = h - boxH - 28;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16);
      ctx.fill();

      ctx.fillStyle = 'rgba(56, 189, 248, 0.20)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, 52, 16);
      ctx.fill();

      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16);
      ctx.stroke();

      ctx.fillStyle = accentColor;
      const topFontPx = Math.round((cfg.useDms ? 20 : 22) * fScale);
      ctx.font = `bold ${topFontPx}px monospace`;
      ctx.textAlign = 'left';
      const topText = cfg.showCoords ? coordLine : `${cfg.customTitle || '经纬网格测绘'} · ${resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, topText, boxW - 32), boxLeft + 16, boxTop + 34);

      const midX = boxLeft + boxW * 0.48;
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.32)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(boxLeft, boxTop + 52); ctx.lineTo(boxLeft + boxW, boxTop + 52);
      ctx.moveTo(midX, boxTop + 52); ctx.lineTo(midX, boxTop + 140);
      if (hasFooter) {
        ctx.moveTo(boxLeft, boxTop + 140); ctx.lineTo(boxLeft + boxW, boxTop + 140);
      }
      ctx.stroke();

      ctx.fillStyle = '#94a3b8';
      ctx.font = 'normal 18px sans-serif';
      ctx.fillText(`测绘点位 (${resolved.tag})`, boxLeft + 16, boxTop + 80);
      ctx.fillText(`采样时间 (${weekPart})`, midX + 16, boxTop + 80);

      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
      ctx.fillText(ellipsizeCanvasText(ctx, cleanPoi, boxW * 0.48 - 28), boxLeft + 16, boxTop + 120);
      ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
      ctx.fillText(ellipsizeCanvasText(ctx, cfg.fullTs, boxW * 0.52 - 28), midX + 16, boxTop + 120);

      if (hasFooter) {
        ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : '#fde68a';
        ctx.font = 'normal 19.5px sans-serif';
        const footParts = [];
        if (antiLine) footParts.push(antiLine);
        if (cfg.showNote && cfg.note) footParts.push('备注：' + cfg.note);
        else if (cfg.showAddress && cfg.fullAddr) footParts.push(cfg.fullAddr);
        else if (envLine) footParts.push(envLine);
        ctx.fillText(ellipsizeCanvasText(ctx, footParts.join(' · '), boxW - 32), boxLeft + 16, boxTop + 172);
      }
    } else if (cfg.styleTemplate === 3) {
      // 模板 3: 极简胶片底栏 (全宽双层通栏布局：上层点位+不重复的时间星期，下层100%全宽完整展示经纬度！)
      const barH = 114;
      const barTop = h - barH;
      ctx.fillStyle = cardBg;
      ctx.fillRect(0, barTop, w, barH);
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.moveTo(0, barTop);
      ctx.lineTo(w, barTop);
      ctx.stroke();

      // 第一层：左侧 📍 点位名称，右侧 YYYY-MM-DD HH:mm:ss 星期X (绝不重复打印日期)
      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(27 * fScale)}px sans-serif`;
      ctx.fillText(ellipsizeCanvasText(ctx, '📍 ' + cleanPoi, w * 0.50), 26, barTop + 44);

      if (cfg.showTime) {
        ctx.textAlign = 'right';
        ctx.fillStyle = '#e2e8f0';
        ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
        const cleanTimeStr = `${cfg.fullTs} ${weekPart}`.trim();
        ctx.fillText(ellipsizeCanvasText(ctx, cleanTimeStr, w * 0.46), w - 26, barTop + 44);
      }

      // 第二层：左侧全宽完整显示经纬度（即使开启度分秒 DMS 也拥有充足宽度，100% 完整显示！）
      ctx.textAlign = 'left';
      const rightNoteRaw = antiLine || (cfg.showNote && cfg.note ? ('备注：' + cfg.note) : (cfg.showAddress && cfg.fullAddr ? cfg.fullAddr : envLine));
      const maxCoordW = rightNoteRaw ? (w * 0.64) : (w - 52);

      if (cfg.showCoords) {
        ctx.fillStyle = accentColor;
        ctx.font = `bold ${Math.round((cfg.useDms ? 20.5 : 22.5) * fScale)}px monospace`;
        ctx.fillText(ellipsizeCanvasText(ctx, coordLine, maxCoordW), 26, barTop + 88);
      }

      if (rightNoteRaw) {
        ctx.textAlign = 'right';
        ctx.fillStyle = antiLine ? '#34d399' : '#fde68a';
        ctx.font = `normal ${Math.round(20 * fScale)}px sans-serif`;
        ctx.fillText(ellipsizeCanvasText(ctx, rightNoteRaw, w * 0.34), w - 26, barTop + 88);
      }
    } else {
      // 模板 4: 外业标准台账 (工程双列边框表格，自适应 DMS 字号)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const rows = [];
      if (cfg.showCoords) rows.push(['经纬坐标', coordLine, accentColor]);
      if (cfg.showLoc) {
        const locVal = (cfg.showAddress && cfg.fullAddr) ? `${cleanPoi} · ${cfg.fullAddr}` : cleanPoi;
        rows.push(['实拍点位', locVal, '#ffffff']);
      }
      if (cfg.showTime) rows.push(['记录时间', `${cfg.fullTs} ${weekPart}`, '#ffffff']);
      if (envLine) rows.push(['高程方位', envLine, '#a7f3d0']);
      if (cfg.showNote && cfg.note) rows.push(['现场备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) rows.push(['防伪签名', `SHA256:${sigHash.slice(0, 16)} (${cfg.ntpSynced ? 'NTP' : '已锁'})`, '#34d399']);

      const headerH = 44;
      const rowH = Math.round(39 * fScale);
      const boxH = headerH + rows.length * rowH;
      const boxTop = h - boxH - 28;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14);
      ctx.fill();
      ctx.fillStyle = 'rgba(56, 189, 248, 0.24)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, headerH, 14);
      ctx.fill();
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14);
      ctx.stroke();

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
      const titleStr = `${cfg.customTitle || '外业定点巡检台账'} · ${cfg.antiTamperMode ? '🛡防伪台账' : resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - 28), boxLeft + 14, boxTop + 30);

      const colSplit = boxLeft + 130;
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.45)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(boxLeft, boxTop + headerH); ctx.lineTo(boxLeft + boxW, boxTop + headerH);
      ctx.moveTo(colSplit, boxTop + headerH); ctx.lineTo(colSplit, boxTop + boxH);
      ctx.stroke();

      const maxValW = boxLeft + boxW - colSplit - 22;
      const valFontPx = Math.round((cfg.useDms ? 19.5 : 21) * fScale);
      ctx.font = `bold ${valFontPx}px monospace`;
      rows.forEach((r, idx) => {
        const ty = boxTop + headerH + idx * rowH;
        if (idx > 0) {
          ctx.beginPath();
          ctx.moveTo(boxLeft, ty);
          ctx.lineTo(boxLeft + boxW, ty);
          ctx.stroke();
        }
        ctx.fillStyle = accentColor;
        ctx.fillText(r[0], boxLeft + 14, ty + Math.round(rowH * 0.68));
        ctx.fillStyle = r[2];
        ctx.fillText(ellipsizeCanvasText(ctx, r[1], maxValW), colSplit + 12, ty + Math.round(rowH * 0.68));
      });
    }

    // 绘制内嵌高德定位二维码角标 (始终使用 GCJ-02 坐标，确保扫码跳高德 100% 零偏差！)
    if (cfg.showQr) {
      const qrUrl = buildAmapJumpUrl(cfg.gcjLat, cfg.gcjLon, cleanPoi, {
        enabled: cfg.antiTamperMode,
        timestamp: cfg.fullTs,
        signature: sigHash,
        ntpSynced: cfg.ntpSynced
      });
      const mat = encodeQrMatrix(qrUrl);
      const modules = mat.length;
      const qrBoxSize = 152;
      const labelH = 26;
      const totalH = qrBoxSize + labelH;
      const boxLeft = w - qrBoxSize - 26;
      const bottomOffset = cfg.styleTemplate === 3 ? 126 : 26;
      const boxTop = h - totalH - bottomOffset;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.92)';
      drawRoundedRect(ctx, boxLeft, boxTop, qrBoxSize, totalH, 14);
      ctx.fill();
      ctx.strokeStyle = cfg.antiTamperMode ? '#34d399' : accentColor;
      ctx.lineWidth = 1.8;
      ctx.stroke();

      const pad = 8;
      const whiteSize = qrBoxSize - pad * 2;
      ctx.fillStyle = '#ffffff';
      drawRoundedRect(ctx, boxLeft + pad, boxTop + pad, whiteSize, whiteSize, 6);
      ctx.fill();

      const totalMods = modules + 8; // ISO 标准四周各 4 模块白色静区
      const cell = whiteSize / totalMods;
      const sx = boxLeft + pad + cell * 4;
      const sy = boxTop + pad + cell * 4;
      ctx.fillStyle = '#000000';
      for (let r = 0; r < modules; r++) {
        for (let c = 0; c < modules; c++) {
          if (mat[r][c]) {
            ctx.fillRect(sx + c * cell, sy + r * cell, cell + 0.35, cell + 0.35);
          }
        }
      }
      ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : accentColor;
      ctx.font = 'bold 15px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(cfg.antiTamperMode ? '扫码验真跳高德' : '长按跳高德', boxLeft + qrBoxSize / 2, boxTop + qrBoxSize + 18);
    }
  },

  /**
   * 相册内点击「✎ 无损改水印」：将该照片的配置载入定制面板，保存后基于原图无损重绘
   */
  openEditPhotoWatermark() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    const c = cur.cfg;
    this.preEditBackup = {
      styleTemplate: this.data.styleTemplate,
      coordSys: this.data.coordSys,
      useDms: this.data.useDms,
      customTitle: this.data.customTitle,
      shortPoi: this.data.shortPoi,
      fullAddr: this.data.fullAddr,
      note: this.data.note,
      accentColorIndex: this.data.accentColorIndex,
      accentHex: this.data.accentHex,
      fontScale: this.data.fontScale,
      bgAlphaMode: this.data.bgAlphaMode,
      cardBgRgba: this.data.cardBgRgba,
      showTime: this.data.showTime,
      showLoc: this.data.showLoc,
      showCoords: this.data.showCoords,
      showCoordTag: this.data.showCoordTag,
      showAccuracy: this.data.showAccuracy,
      showAddress: this.data.showAddress,
      showEnv: this.data.showEnv,
      showQr: this.data.showQr
    };
    this.setData({
      editingPhotoId: cur.id,
      styleTemplate: c.styleTemplate || 0,
      coordSys: c.coordSys || 'WGS-84',
      useDms: Boolean(c.useDms),
      customTitle: c.customTitle || '现场点位核验',
      shortPoi: c.shortPoi || '实拍点位',
      fullAddr: c.fullAddr || '',
      note: c.note || '',
      accentColorIndex: c.accentColorIndex || 0,
      accentHex: c.accentHex || '#38bdf8',
      fontScale: c.fontScale || 1,
      bgAlphaMode: c.bgAlphaMode !== undefined ? c.bgAlphaMode : 1,
      cardBgRgba: c.cardBgRgba || BG_ALPHA_PRESETS[1],
      showTime: c.showTime !== false,
      showLoc: c.showLoc !== false,
      showCoords: c.showCoords !== false,
      showCoordTag: c.showCoordTag !== false,
      showAccuracy: c.showAccuracy !== false,
      showAddress: Boolean(c.showAddress),
      showEnv: Boolean(c.showEnv),
      showQr: c.showQr !== false,
      showWmModal: true
    });
  },

  applyWatermarkCustomization() {
    if (this.data.editingPhotoId) {
      const target = this.data.photos.find(p => p.id === this.data.editingPhotoId);
      if (target) {
        const updatedCfg = {
          ...target.cfg,
          styleTemplate: this.data.styleTemplate,
          customTitle: this.data.customTitle,
          coordSys: this.data.coordSys,
          useDms: this.data.useDms,
          shortPoi: this.data.shortPoi || '实拍点位',
          fullAddr: this.data.fullAddr,
          note: this.data.note,
          accentColorIndex: this.data.accentColorIndex,
          accentHex: this.data.accentHex,
          fontScale: this.data.fontScale,
          bgAlphaMode: this.data.bgAlphaMode,
          cardBgRgba: this.data.cardBgRgba,
          showTime: this.data.showTime,
          showLoc: this.data.showLoc,
          showCoords: this.data.showCoords,
          showCoordTag: this.data.showCoordTag,
          showAccuracy: this.data.showAccuracy,
          showAddress: this.data.showAddress,
          showEnv: this.data.showEnv,
          showQr: this.data.showQr
        };
        updatedCfg.antiTamperHash = computeAntiTamperHash(
          updatedCfg.gcjLat,
          updatedCfg.gcjLon,
          updatedCfg.fullTs,
          updatedCfg.shortPoi
        );
        this.compositeWatermarkToTempFile(target.rawPath, updatedCfg, target.id);
        return;
      }
    }
    this.updateCoordString();
    this.persistUserConfig();
    this.setData({ showWmModal: false, editingPhotoId: null });
    wx.showToast({ title: '水印设置已实时生效', icon: 'none' });
  },

  selectGalleryPhoto(e) {
    const id = e.currentTarget.dataset.id;
    const found = this.data.photos.find(p => p.id === id);
    if (found) {
      this.setData({ currentPhoto: found });
    }
  },

  deleteCurrentPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    const remaining = this.data.photos.filter(p => p.id !== cur.id);
    this.setData({
      photos: remaining,
      currentPhoto: remaining[0] || null,
      latestPhotoPath: remaining[0] ? remaining[0].wmPath : ''
    });
    this.persistPhotosList(remaining);
    wx.showToast({ title: '已删除该照片', icon: 'none' });
  },

  /**
   * 跳高德导航始终固定使用 GCJ-02 坐标，与水印显示的 WGS-84 / CGCS2000 彻底解耦，确保零偏差！
   */
  openCurrentInWeChatMap() {
    wx.openLocation({
      latitude: Number(this.data.gcjLat),
      longitude: Number(this.data.gcjLon),
      name: this.data.shortPoi || '实拍点位',
      address: this.data.fullAddr || `高德 GCJ-02: ${this.data.gcjLat}, ${this.data.gcjLon}`,
      scale: 18
    });
  },

  navigateSelectedPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    wx.openLocation({
      latitude: Number(cur.cfg.gcjLat),
      longitude: Number(cur.cfg.gcjLon),
      name: cur.cfg.shortPoi || '实拍点位',
      address: cur.cfg.fullAddr || `高德 GCJ-02: ${cur.cfg.gcjLat}, ${cur.cfg.gcjLon}`,
      scale: 18
    });
  },

  sendRawImageToFriend() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    if (wx.showShareImageMenu) {
      wx.showShareImageMenu({ path: cur.wmPath });
    } else {
      wx.previewImage({ urls: [cur.wmPath] });
    }
  },

  saveCurrentToAlbum() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    wx.saveImageToPhotosAlbum({
      filePath: cur.wmPath,
      success: () => wx.showToast({ title: '已存入系统相册', icon: 'success' }),
      fail: () => {
        wx.showModal({
          title: '需要相册写入权限',
          content: '请允许保存图片到手机系统相册',
          confirmText: '去开启',
          success: r => { if (r.confirm) wx.openSetting(); }
        });
      }
    });
  },

  copyCurrentCoord() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    const resolved = resolveDisplayCoordinates(cur.cfg.coordSys, cur.cfg.gcjLat, cur.cfg.gcjLon, cur.cfg.wgsLat, cur.cfg.wgsLon);
    const txt = `${resolved.lon.toFixed(6)},${resolved.lat.toFixed(6)} [${resolved.tag}] (高德GCJ-02: ${cur.cfg.gcjLon.toFixed(6)},${cur.cfg.gcjLat.toFixed(6)})`;
    wx.setClipboardData({ data: txt });
  },

  previewFullImage() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    const urls = this.data.photos.map(p => p.wmPath);
    wx.previewImage({
      current: cur.wmPath,
      urls
    });
  },

  openGalleryModal() {
    this.setData({ showGallery: true });
  },
  closeGalleryModal() {
    this.setData({ showGallery: false, editingPhotoId: null });
  },
  openPoiQuickModal() {
    this.setData({ showPoiModal: true });
  },
  closePoiQuickModal() {
    this.setData({ showPoiModal: false });
  },
  openSettingsModal() {
    this.setData({ showSettings: true });
  },
  closeSettingsModal() {
    this.setData({ showSettings: false });
  },
  openWatermarkModal() {
    this.setData({ editingPhotoId: null, showWmModal: true });
  },
  closeWatermarkModal() {
    if (this.data.editingPhotoId && this.preEditBackup) {
      const b = this.preEditBackup;
      this.preEditBackup = null;
      this.setData({
        ...b,
        showWmModal: false,
        editingPhotoId: null
      }, () => this.updateCoordString());
      return;
    }
    this.setData({ showWmModal: false, editingPhotoId: null });
  },
  onToggleQr(e) {
    this.setData({ showQr: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleShowAddress(e) {
    this.setData({ showAddress: e.detail.value }, () => this.persistUserConfig());
  },

  /**
   * 开启外业防篡改验真模式：
   * 核心修复：若用户此前曾通过“地图选点”偏移过坐标 (isManualCoord === true)，
   * 开启验真模式的瞬间强制将生效坐标回滚为硬件真实 GPS 坐标 (rawGcjLat / rawGcjLon)，
   * 并立即重新请求一次高精度卫星定位，彻底杜绝“先地图选假点、再开验真洗白”的逻辑漏洞！
   */
  onToggleAntiTamper(e) {
    const enabled = e.detail.value;
    const hadManualOffset = this.data.isManualCoord;
    const patch = { antiTamperMode: enabled };

    if (enabled) {
      patch.isManualCoord = false;
      patch.gcjLat = this.data.rawGcjLat;
      patch.gcjLon = this.data.rawGcjLon;
      patch.wgsLat = this.data.rawWgsLat;
      patch.wgsLon = this.data.rawWgsLon;
      patch.accuracy = this.data.rawAccuracy;
    }

    this.setData(patch, () => {
      if (enabled) {
        this.syncTrustedNetworkTime();
        this.startHighAccuracyLocation(true);
        wx.showToast({
          title: hadManualOffset ? '🛡已开启防伪，坐标已强制回滚为真实GPS' : '🛡已开启防伪验真，真实GPS与授时已锁定',
          icon: 'none'
        });
      }
      this.refreshClock();
      this.updateCoordString();
      this.persistUserConfig();
    });
  },

  onToggleEnv(e) {
    this.setData({ showEnv: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleAutoSave(e) {
    this.setData({ autoSaveAlbum: e.detail.value }, () => this.persistUserConfig());
  },
  onInputTitle(e) {
    this.setData({ customTitle: e.detail.value });
  },
  onInputPoi(e) {
    this.setData({ shortPoi: e.detail.value, hasCustomPoi: true }, () => this.updateCoordString());
  },
  onInputFullAddr(e) {
    this.setData({ fullAddr: e.detail.value, hasCustomPoi: true });
  },
  onInputNote(e) {
    this.setData({ note: e.detail.value });
  },
  onCameraError() {
    wx.showToast({ title: '请授予小程序相机权限', icon: 'none' });
  },
  noop() {},

  onShareAppMessage() {
    const cur = this.data.currentPhoto;
    const lat = cur ? cur.cfg.gcjLat : this.data.gcjLat;
    const lon = cur ? cur.cfg.gcjLon : this.data.gcjLon;
    const poi = encodeURIComponent(cur ? cur.cfg.shortPoi : (this.data.shortPoi || '实拍点位'));
    const ts = encodeURIComponent(cur ? cur.cfg.fullTs : this.data.fullTs);
    const sig = cur ? (cur.cfg.antiTamperHash || '').slice(0, 12) : this.data.liveAntiTamperShort;
    const imgUrl = cur ? cur.wmPath : '';
    return {
      title: `📍 实拍定位：${decodeURIComponent(poi)} (${lat}°N, ${lon}°E)`,
      path: `/pages/index/index?act=jump&lat=${lat}&lon=${lon}&poi=${poi}&ts=${ts}&sig=${sig}`,
      imageUrl: imgUrl
    };
  }
});
