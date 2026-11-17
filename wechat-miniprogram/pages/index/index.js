const {
  wgs84ToGcj02,
  gcj02ToWgs84,
  formatCoords,
  formatAzimuthText,
  computeAntiTamperHash,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload,
  drawVectorMiniMap,
  inferSmartPoiFromCoords
} = require('../../utils/coord_qr.js');

function getTimeStrings(offsetMs) {
  const d = new Date(Date.now() + (Number(offsetMs) || 0));
  const pad = n => (n < 10 ? '0' + n : '' + n);
  const weeks = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const datePart = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const dateWeek = `${datePart} ${weeks[d.getDay()]}`;
  const fullTs = `${datePart} ${hm}:${pad(d.getSeconds())}`;
  return { hm, dateWeek, fullTs };
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
    captureMode: 'photo', // 'photo': 光学拍照模式 (takePhoto), 'frame': 极速截屏模式 (onCameraFrame)
    devicePosition: 'back',
    flashMode: 'off',
    flashLabel: '⚡关',
    styleTemplate: 0,
    tplNames: ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'],
    quickTitleTags: ['现场点位核验', '工程巡检记录', '隐蔽工程验收', '外业测绘定桩', '安全隐患排查'],
    quickNoteTags: ['核验合格', '定位已校准', '施工进行中', '待整改复核', '初检通过'],
    customTitle: '现场点位核验',
    coordSys: 'GCJ-02',
    useDms: false,
    hasRealFix: false,
    hasCustomPoi: false,
    gcjLat: 39.908823,
    gcjLon: 116.397470,
    wgsLat: 39.907419,
    wgsLon: 116.391228,
    accuracy: '2.4',
    altitude: '46.5',
    azimuthDeg: 0,
    azimuthText: '正北 000°',
    shortPoi: '定位中...',
    fullAddr: '正在获取高德 GCJ-02 实时坐标与街道点位...',
    nearbyPois: ['1号巡检点位', '主干道施工段', '隐蔽工程核验点', '外业测绘基准桩'],
    note: '',
    timeHm: '09:20',
    dateWeek: '2026-11-17 星期二',
    fullTs: '2026-11-17 09:20:00',
    liveCoordStr: '39.908823°N, 116.397470°E [GCJ-02] ±2.4m',
    liveAntiTamperShort: '000000000000',
    showQr: true,
    showMiniMap: true,
    miniMapPosition: 0, // 0: 卡片左侧内嵌 (160x160), 1: 左上角独立悬浮
    antiTamperMode: false,
    ntpSynced: false,
    ntpOffsetMs: 0,
    showTime: true,
    showLoc: true,
    showCoords: true,
    showNote: true,
    showEnv: false,
    autoSaveAlbum: true,
    photos: [],
    latestPhotoPath: '',
    currentPhoto: null,
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

    this.syncTrustedNetworkTime();
    this.refreshClock();
    this.clockTimer = setInterval(() => this.refreshClock(), 1000);

    // 1. 检查是否由好友分享卡片或微信长按识别水印定位二维码进入小程序
    this.checkIncomingFriendLocation(query);

    // 2. 启动高频高德 GCJ-02 原生定位与罗盘监听
    this.startHighAccuracyLocation();
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
    if (this.frameListener) {
      try { this.frameListener.stop(); } catch (e) {}
    }
    try { wx.stopCompass(); } catch (e) {}
  },

  /**
   * 联网校准真实授时服务器时间 (防篡改模式核心支撑)
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
      const shortPoi = query.poi ? decodeURIComponent(query.poi) : '好友水印实拍点';
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
      name: pt.shortPoi || '水印相机实拍点位',
      address: `高德 GCJ-02 零偏差坐标: ${pt.gcjLat}, ${pt.gcjLon}`,
      scale: 18
    });
  },

  closeSharedBanner() {
    this.setData({ sharedBannerVisible: false });
  },

  /**
   * 识图验真跳高德：从相册或相机扫描水印二维码，自动校验 SHA-256 防伪签名并跳高德
   */
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
            name: parsed.shortPoi || '好友水印实拍点',
            address: `高德 GCJ-02: ${parsed.gcjLat}, ${parsed.gcjLon}`,
            scale: 18
          });
        } else {
          wx.showToast({ title: '未在码中识别到高德坐标', icon: 'none' });
        }
      }
    });
  },

  startHighAccuracyLocation() {
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      highAccuracyExpireTime: 3500,
      success: res => {
        this.applyResolvedLocation(res.latitude, res.longitude, res.accuracy, res.altitude);
      },
      fail: () => {
        // 即使未授权或室内无信号，也立即根据当前坐标推演合理点位，绝不写死卡在“小庄村”
        const smart = inferSmartPoiFromCoords(this.data.gcjLat, this.data.gcjLon);
        this.setData({
          shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : smart.shortPoi,
          fullAddr: this.data.hasCustomPoi ? this.data.fullAddr : smart.fullAddr,
          nearbyPois: smart.nearbyPois
        }, () => this.updateCoordString());
      }
    });
  },

  applyResolvedLocation(lat, lon, accuracy, altitude) {
    const gcjLat = Number(Number(lat).toFixed(6));
    const gcjLon = Number(Number(lon).toFixed(6));
    const wgs = gcj02ToWgs84(gcjLat, gcjLon);
    const acc = Number(accuracy || 2.8).toFixed(1);
    const alt = altitude ? Number(altitude).toFixed(1) : this.data.altitude;
    const smart = inferSmartPoiFromCoords(gcjLat, gcjLon);

    this.setData({
      hasRealFix: true,
      gcjLat,
      gcjLon,
      wgsLat: wgs.lat,
      wgsLon: wgs.lon,
      accuracy: acc,
      altitude: alt,
      shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : smart.shortPoi,
      fullAddr: this.data.hasCustomPoi ? this.data.fullAddr : smart.fullAddr,
      nearbyPois: smart.nearbyPois
    }, () => this.updateCoordString());

    // 在线逆地理编码增强（自动解析真实街道与周边 POI，失败时平滑保留离线网格地名）
    if (!this.data.hasCustomPoi) {
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
            const poi = (d.name && d.name.length <= 14) ? d.name : (road || suburb || smart.shortPoi);
            const full = `${city}${suburb !== city ? suburb : ''}${road}${d.name && d.name !== road ? d.name : ''}` || smart.fullAddr;
            const cands = [poi, road ? `${road}巡检点` : '', suburb ? `${suburb}核验点` : '', ...smart.nearbyPois]
              .filter(Boolean)
              .slice(0, 6);
            this.setData({
              shortPoi: poi,
              fullAddr: full,
              nearbyPois: cands
            }, () => this.updateCoordString());
          }
        }
      });
    }
  },

  refreshRealGpsNow() {
    this.setData({ hasCustomPoi: false });
    this.startHighAccuracyLocation();
    wx.showToast({ title: '已刷新GPS并同步逆地理点位', icon: 'none' });
  },

  choosePoiOnMap() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防篡改模式下禁止手动偏移坐标', icon: 'none' });
      return;
    }
    wx.chooseLocation({
      latitude: this.data.gcjLat,
      longitude: this.data.gcjLon,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const wgs = gcj02ToWgs84(gcjLat, gcjLon);
        this.setData({
          hasCustomPoi: true,
          gcjLat,
          gcjLon,
          wgsLat: wgs.lat,
          wgsLon: wgs.lon,
          shortPoi: res.name || this.data.shortPoi,
          fullAddr: res.address || this.data.fullAddr,
          showPoiModal: false
        }, () => this.updateCoordString());
      },
      fail: err => {
        if (err && err.errMsg && err.errMsg.indexOf('auth deny') !== -1) {
          wx.showModal({
            title: '需要位置权限',
            content: '请在设置中开启位置信息权限以使用地图选点校准',
            confirmText: '去设置',
            success: r => { if (r.confirm) wx.openSetting(); }
          });
        }
      }
    });
  },

  refreshClock() {
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const sig = computeAntiTamperHash(this.data.gcjLat, this.data.gcjLon, t.fullTs, this.data.shortPoi).slice(0, 12);
    const nextPatch = {};
    if (t.hm !== this.data.timeHm) nextPatch.timeHm = t.hm;
    if (t.dateWeek !== this.data.dateWeek) nextPatch.dateWeek = t.dateWeek;
    if (this.data.styleTemplate !== 0 || this.data.antiTamperMode) {
      nextPatch.fullTs = t.fullTs;
      nextPatch.liveAntiTamperShort = sig;
    }
    if (Object.keys(nextPatch).length > 0) {
      this.setData(nextPatch);
    }
  },

  updateCoordString() {
    const lat = this.data.coordSys === 'WGS-84' ? this.data.wgsLat : this.data.gcjLat;
    const lon = this.data.coordSys === 'WGS-84' ? this.data.wgsLon : this.data.gcjLon;
    const str = `${formatCoords(lat, lon, this.data.useDms)} [${this.data.coordSys}] ±${this.data.accuracy}m`;
    const sig = computeAntiTamperHash(this.data.gcjLat, this.data.gcjLon, this.data.fullTs, this.data.shortPoi).slice(0, 12);
    this.setData({
      liveCoordStr: str,
      liveAntiTamperShort: sig
    });
  },

  toggleCaptureMode() {
    const next = this.data.captureMode === 'photo' ? 'frame' : 'photo';
    this.setData({ captureMode: next });
    wx.showToast({
      title: next === 'photo' ? '📷 光学拍照模式' : '⚡ 极速截屏模式',
      icon: 'none'
    });
  },

  setCaptureMode(e) {
    const mode = e.currentTarget.dataset.mode;
    this.setData({ captureMode: mode });
  },

  setMiniMapPosition(e) {
    const pos = Number(e.currentTarget.dataset.pos);
    this.setData({ miniMapPosition: pos });
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
    this.setData({ styleTemplate: idx }, () => this.refreshClock());
  },

  selectQuickPoi(e) {
    const poi = e.currentTarget.dataset.poi;
    if (poi) {
      this.setData({ shortPoi: poi, hasCustomPoi: true }, () => this.updateCoordString());
    }
  },

  selectQuickTitle(e) {
    const title = e.currentTarget.dataset.title;
    if (title) this.setData({ customTitle: title });
  },

  appendQuickNote(e) {
    const tag = e.currentTarget.dataset.note;
    if (!tag) return;
    const next = this.data.note ? `${this.data.note} · ${tag}` : tag;
    this.setData({ note: next, showNote: true });
  },

  handleShutterTap() {
    const camCtx = wx.createCameraContext();
    const quality = this.data.captureMode === 'photo' ? 'high' : 'normal';
    camCtx.takePhoto({
      quality,
      success: res => {
        this.compositeWatermarkToTempFile(res.tempImagePath);
      },
      fail: () => {
        wx.showToast({ title: '拍照失败，请检查相机权限', icon: 'none' });
      }
    });
  },

  compositeWatermarkToTempFile(rawImagePath) {
    wx.showLoading({ title: '合成高德水印...' });
    const query = wx.createSelectorQuery();
    query.select('#compositeCanvas')
      .fields({ node: true, size: true })
      .exec(res => {
        if (!res || !res[0] || !res[0].node) {
          wx.hideLoading();
          return;
        }
        const canvas = res[0].node;
        const ctx = canvas.getContext('2d');
        const img = canvas.createImage();

        img.onload = () => {
          // 保持原图真实长宽比，拒绝生硬拉伸变形
          const naturalW = img.width || 1080;
          const naturalH = img.height || 1440;
          const w = 1080;
          const h = Math.round((naturalH / naturalW) * w) || 1440;
          canvas.width = w;
          canvas.height = h;

          ctx.drawImage(img, 0, 0, w, h);
          const t = getTimeStrings(this.data.ntpOffsetMs);
          const antiHash = computeAntiTamperHash(this.data.gcjLat, this.data.gcjLon, t.fullTs, this.data.shortPoi);

          const cfg = {
            styleTemplate: this.data.styleTemplate,
            customTitle: this.data.customTitle,
            coordSys: this.data.coordSys,
            useDms: this.data.useDms,
            gcjLat: this.data.gcjLat,
            gcjLon: this.data.gcjLon,
            wgsLat: this.data.wgsLat,
            wgsLon: this.data.wgsLon,
            accuracy: this.data.accuracy,
            altitude: this.data.altitude,
            azimuthDeg: this.data.azimuthDeg,
            azimuthText: this.data.azimuthText,
            shortPoi: this.data.shortPoi,
            fullAddr: this.data.fullAddr,
            note: this.data.note,
            hm: t.hm,
            dateWeek: t.dateWeek,
            fullTs: t.fullTs,
            showQr: this.data.showQr,
            showMiniMap: this.data.showMiniMap,
            miniMapPosition: this.data.miniMapPosition,
            antiTamperMode: this.data.antiTamperMode,
            ntpSynced: this.data.ntpSynced,
            antiTamperHash: antiHash,
            showTime: this.data.showTime,
            showLoc: this.data.showLoc,
            showCoords: this.data.showCoords,
            showNote: this.data.showNote,
            showEnv: this.data.showEnv
          };

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
              wx.hideLoading();
              const entry = {
                id: 'wx_' + Date.now(),
                rawPath: rawImagePath,
                wmPath: out.tempFilePath,
                cfg
              };
              const nextPhotos = [entry, ...this.data.photos];
              this.setData({
                photos: nextPhotos,
                latestPhotoPath: out.tempFilePath,
                currentPhoto: entry
              });
              if (this.data.autoSaveAlbum) {
                wx.saveImageToPhotosAlbum({
                  filePath: out.tempFilePath,
                  success: () => {
                    wx.showToast({
                      title: cfg.antiTamperMode ? '已存相册(含防伪码与缩略图)' : '已存入相册(含跳高德定位码)',
                      icon: 'none'
                    });
                  },
                  fail: () => {
                    wx.showToast({ title: '已存入应用相册', icon: 'none' });
                  }
                });
              }
            },
            fail: () => {
              wx.hideLoading();
            }
          });
        };
        img.src = rawImagePath;
      });
  },

  /**
   * 完整实现 5 款差异化水印模板 + 160x160 高德矢量缩略地图 + 长地名折行防遮挡 + 标准 4模块静区二维码
   */
  drawCanvasWatermarkAndQr(ctx, w, h, cfg) {
    const lat = cfg.coordSys === 'WGS-84' ? cfg.wgsLat : cfg.gcjLat;
    const lon = cfg.coordSys === 'WGS-84' ? cfg.wgsLon : cfg.gcjLon;
    const coordLine = `${formatCoords(lat, lon, cfg.useDms)} [${cfg.coordSys}] ±${cfg.accuracy}m`;
    const envLine = cfg.showEnv ? `海拔 ${cfg.altitude}m · 方位 ${cfg.azimuthText}` : '';
    const antiLine = cfg.antiTamperMode ? `🛡 ${cfg.ntpSynced ? 'NTP真时已锁' : '防伪锁定'} · SHA256:${cfg.antiTamperHash.slice(0, 12)}` : '';

    // 左上角独立悬浮 160x160 Mini-Map 模式
    if (cfg.showMiniMap && cfg.miniMapPosition === 1 && (cfg.styleTemplate === 1 || cfg.styleTemplate === 2)) {
      drawVectorMiniMap(ctx, 28, 86, 160, cfg.gcjLat, cfg.gcjLon, cfg.azimuthDeg, cfg.shortPoi, 1.0);
    }

    if (cfg.styleTemplate === 0) {
      // 模板 0: 经典大字时间 (严格扣除右侧二维码宽度，绝不碰撞遮挡)
      const grad = ctx.createLinearGradient(0, h - 390, 0, h);
      grad.addColorStop(0, 'rgba(0,0,0,0)');
      grad.addColorStop(1, 'rgba(0,0,0,0.65)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, h - 390, w, 390);

      const qrReserveRight = cfg.showQr ? 184 : 28;
      const leftMargin = 28;
      const safeZoneW = w - leftMargin - qrReserveRight;
      const cx = cfg.showQr ? (leftMargin + safeZoneW / 2) : (w / 2);
      const maxLineW = safeZoneW - 16;
      let curY = h - 42;

      if (antiLine) {
        ctx.font = 'bold 20px monospace';
        ctx.fillStyle = '#34d399';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, antiLine, maxLineW), cx, curY);
        curY -= 34;
      }
      if (cfg.showNote && cfg.note) {
        ctx.font = 'bold 24px sans-serif';
        ctx.fillStyle = '#fde68a';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, '备注：' + cfg.note, maxLineW), cx, curY);
        curY -= 36;
      }
      if (envLine) {
        ctx.font = 'bold 22px monospace';
        ctx.fillStyle = '#a7f3d0';
        ctx.textAlign = 'center';
        ctx.fillText(ellipsizeCanvasText(ctx, envLine, maxLineW), cx, curY);
        curY -= 34;
      }
      if (cfg.showCoords) {
        ctx.font = 'bold 24px monospace';
        const clippedCoord = ellipsizeCanvasText(ctx, coordLine, maxLineW - 44);
        const tw = ctx.measureText(clippedCoord).width;
        ctx.fillStyle = 'rgba(11, 19, 43, 0.78)';
        drawRoundedRect(ctx, cx - tw / 2 - 20, curY - 28, tw + 40, 38, 19);
        ctx.fill();
        ctx.fillStyle = '#38bdf8';
        ctx.textAlign = 'center';
        ctx.fillText(clippedCoord, cx, curY);
        curY -= 48;
      }
      if (cfg.showTime || cfg.showLoc) {
        ctx.font = 'normal 32px sans-serif';
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        const lineRaw = `${cfg.showTime ? cfg.dateWeek + ' ' : ''}${cfg.showLoc ? '📍' + cfg.shortPoi : ''}`;
        ctx.fillText(ellipsizeCanvasText(ctx, lineRaw, maxLineW), cx, curY);
        curY -= 58;
      }
      if (cfg.showTime) {
        ctx.font = '300 112px sans-serif';
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        ctx.fillText(cfg.hm, cx, curY);
      }
    } else if (cfg.styleTemplate === 1) {
      // 模板 1: 工程巡检卡片 (支持内嵌 160x160 高德 Mini-Map + 双行长地址折行)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const embedMap = cfg.showMiniMap && cfg.miniMapPosition === 0;
      const mapSize = embedMap ? 160 : 0;
      const mapGap = embedMap ? 18 : 0;
      const padX = 22;
      const padY = 18;
      const headerH = 44;
      const lineGap = 35;
      const textStartX = boxLeft + padX + mapSize + mapGap;
      const maxTextW = boxLeft + boxW - textStartX - padX;

      ctx.font = 'bold 22px monospace';
      const rows = [];
      if (cfg.showCoords) rows.push(['经纬', coordLine, '#38bdf8']);
      if (envLine) rows.push(['环境', envLine, '#a7f3d0']);
      if (cfg.showLoc) {
        const locRaw = `${cfg.shortPoi} (${cfg.fullAddr})`;
        const prefixW = ctx.measureText('地点：').width;
        const wrapped = wrapCanvasTextTwoLines(ctx, locRaw, maxTextW - prefixW);
        if (wrapped.length > 0) rows.push(['地点', wrapped[0], '#ffffff']);
        if (wrapped.length > 1 && wrapped[1]) rows.push(['地址', wrapped[1], '#e2e8f0']);
      }
      if (cfg.showTime) rows.push(['时间', `${cfg.fullTs} ${cfg.dateWeek.split(' ')[1] || ''}`, '#cbd5e1']);
      if (cfg.showNote && cfg.note) rows.push(['备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) rows.push(['验真', `SHA256:${cfg.antiTamperHash.slice(0, 12)} (${cfg.ntpSynced ? 'NTP真时' : '已锁'})`, '#34d399']);

      const contentH = headerH + rows.length * lineGap;
      const boxH = padY * 2 + Math.max(contentH, mapSize + (embedMap ? headerH : 0));
      const boxTop = h - boxH - 28;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.84)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 18);
      ctx.fill();
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.55)';
      ctx.lineWidth = 2;
      ctx.stroke();

      ctx.fillStyle = '#38bdf8';
      ctx.fillRect(boxLeft + padX, boxTop + padY, 7, 26);
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 26px sans-serif';
      ctx.textAlign = 'left';
      const titleStr = `${cfg.customTitle || '现场点位核验'} · ${cfg.antiTamperMode ? '🛡防伪已锁' : cfg.coordSys}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - padX * 2 - 24), boxLeft + padX + 16, boxTop + padY + 23);

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.16)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(boxLeft + padX, boxTop + padY + headerH - 8);
      ctx.lineTo(boxLeft + boxW - padX, boxTop + padY + headerH - 8);
      ctx.stroke();

      if (embedMap) {
        drawVectorMiniMap(ctx, boxLeft + padX, boxTop + padY + headerH, mapSize, cfg.gcjLat, cfg.gcjLon, cfg.azimuthDeg, cfg.shortPoi, 1.0);
      }

      ctx.font = 'bold 22px monospace';
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
      // 模板 2: 经纬网格测绘 (双栏十字网格 + 可选内嵌 160x160 高德 Mini-Map)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const embedMap = cfg.showMiniMap && cfg.miniMapPosition === 0;
      const mapSize = embedMap ? 160 : 0;
      const boxH = 192;
      const boxTop = h - boxH - 28;

      ctx.fillStyle = 'rgba(9, 14, 26, 0.86)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16);
      ctx.fill();

      const gridLeft = embedMap ? (boxLeft + mapSize + 22) : boxLeft;
      const gridW = (boxLeft + boxW) - gridLeft;

      if (embedMap) {
        drawVectorMiniMap(ctx, boxLeft + 12, boxTop + (boxH - mapSize) / 2, mapSize, cfg.gcjLat, cfg.gcjLon, cfg.azimuthDeg, cfg.shortPoi, 1.0);
      }

      ctx.fillStyle = 'rgba(56, 189, 248, 0.22)';
      drawRoundedRect(ctx, gridLeft, boxTop, gridW, 52, 14);
      ctx.fill();

      ctx.strokeStyle = 'rgba(56, 189, 248, 0.58)';
      ctx.lineWidth = 2;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16);
      ctx.stroke();

      ctx.fillStyle = '#38bdf8';
      ctx.font = 'bold 22px monospace';
      ctx.textAlign = 'left';
      const topText = cfg.showCoords ? coordLine : `${cfg.customTitle} · ${cfg.coordSys}`;
      ctx.fillText(ellipsizeCanvasText(ctx, topText, gridW - 28), gridLeft + 14, boxTop + 34);

      const midX = gridLeft + gridW * 0.48;
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.32)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(gridLeft, boxTop + 52); ctx.lineTo(boxLeft + boxW, boxTop + 52);
      ctx.moveTo(midX, boxTop + 52); ctx.lineTo(midX, boxTop + 138);
      ctx.moveTo(gridLeft, boxTop + 138); ctx.lineTo(boxLeft + boxW, boxTop + 138);
      ctx.stroke();

      ctx.fillStyle = '#94a3b8';
      ctx.font = 'normal 18px sans-serif';
      ctx.fillText('测绘点位', gridLeft + 14, boxTop + 80);
      ctx.fillText('采样时间', midX + 14, boxTop + 80);

      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 23px sans-serif';
      ctx.fillText(ellipsizeCanvasText(ctx, cfg.shortPoi, gridW * 0.48 - 24), gridLeft + 14, boxTop + 118);
      ctx.font = 'bold 20px monospace';
      ctx.fillText(ellipsizeCanvasText(ctx, cfg.fullTs, gridW * 0.52 - 24), midX + 14, boxTop + 118);

      ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : '#fde68a';
      ctx.font = 'normal 19px sans-serif';
      const footStr = `${antiLine ? antiLine + ' · ' : ''}${cfg.note || cfg.fullAddr}`;
      ctx.fillText(ellipsizeCanvasText(ctx, footStr, gridW - 28), gridLeft + 14, boxTop + 170);
    } else if (cfg.styleTemplate === 3) {
      // 模板 3: 极简胶片底栏 (全宽电影级底栏，左右严格限宽防撞车)
      const barH = 108;
      const barTop = h - barH;
      ctx.fillStyle = 'rgba(8, 12, 20, 0.90)';
      ctx.fillRect(0, barTop, w, barH);
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.moveTo(0, barTop);
      ctx.lineTo(w, barTop);
      ctx.stroke();

      const maxLeftW = w * 0.44;
      const maxRightW = w * 0.50;

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 27px sans-serif';
      ctx.fillText(ellipsizeCanvasText(ctx, '📍 ' + cfg.shortPoi, maxLeftW), 26, barTop + 42);

      ctx.fillStyle = antiLine ? '#34d399' : '#94a3b8';
      ctx.font = 'normal 20px sans-serif';
      const subLeft = antiLine || cfg.note || cfg.fullAddr || cfg.dateWeek;
      ctx.fillText(ellipsizeCanvasText(ctx, subLeft, maxLeftW), 26, barTop + 78);

      ctx.textAlign = 'right';
      if (cfg.showCoords) {
        ctx.fillStyle = '#38bdf8';
        ctx.font = 'bold 22px monospace';
        ctx.fillText(ellipsizeCanvasText(ctx, coordLine, maxRightW), w - 26, barTop + 42);
      }
      if (cfg.showTime) {
        ctx.fillStyle = '#e2e8f0';
        ctx.font = 'normal 20px monospace';
        ctx.fillText(ellipsizeCanvasText(ctx, `${cfg.fullTs} ${cfg.dateWeek.split(' ')[1] || ''}`, maxRightW), w - 26, barTop + 78);
      }
    } else {
      // 模板 4: 外业标准台账 (工程双列边框表格)
      const boxLeft = 28;
      const boxW = cfg.showQr ? 856 : 1024;
      const rows = [];
      if (cfg.showCoords) rows.push(['经纬坐标', coordLine, '#ffffff']);
      if (envLine) rows.push(['高程方位', envLine, '#a7f3d0']);
      if (cfg.showLoc) rows.push(['点位地址', `${cfg.shortPoi} · ${cfg.fullAddr}`, '#ffffff']);
      if (cfg.showTime) rows.push(['记录时间', `${cfg.fullTs} ${cfg.dateWeek.split(' ')[1] || ''}`, '#ffffff']);
      if (cfg.showNote && cfg.note) rows.push(['现场备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) rows.push(['防伪签名', `SHA256:${cfg.antiTamperHash.slice(0, 16)} (${cfg.ntpSynced ? 'NTP' : '已锁'})`, '#34d399']);

      const headerH = 44;
      const rowH = 39;
      const boxH = headerH + rows.length * rowH;
      const boxTop = h - boxH - 28;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.86)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14);
      ctx.fill();
      ctx.fillStyle = 'rgba(56, 189, 248, 0.25)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, headerH, 14);
      ctx.fill();
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 2;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14);
      ctx.stroke();

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 23px sans-serif';
      const titleStr = `${cfg.customTitle || '外业定点巡检台账'} · ${cfg.antiTamperMode ? '🛡防伪台账' : cfg.coordSys}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - 28), boxLeft + 14, boxTop + 30);

      const colSplit = boxLeft + 132;
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.45)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(boxLeft, boxTop + headerH); ctx.lineTo(boxLeft + boxW, boxTop + headerH);
      ctx.moveTo(colSplit, boxTop + headerH); ctx.lineTo(colSplit, boxTop + boxH);
      ctx.stroke();

      const maxValW = boxLeft + boxW - colSplit - 24;
      ctx.font = 'bold 21px monospace';
      rows.forEach((r, idx) => {
        const ty = boxTop + headerH + idx * rowH;
        if (idx > 0) {
          ctx.beginPath();
          ctx.moveTo(boxLeft, ty);
          ctx.lineTo(boxLeft + boxW, ty);
          ctx.stroke();
        }
        ctx.fillStyle = '#38bdf8';
        ctx.fillText(r[0], boxLeft + 14, ty + 27);
        ctx.fillStyle = r[2];
        ctx.fillText(ellipsizeCanvasText(ctx, r[1], maxValW), colSplit + 12, ty + 27);
      });
    }

    // 绘制内嵌高德定位二维码角标 (含 ISO 标准 4模块静区 + 防伪验真参数)
    if (cfg.showQr) {
      const qrUrl = buildAmapJumpUrl(cfg.gcjLat, cfg.gcjLon, cfg.shortPoi, {
        enabled: cfg.antiTamperMode,
        timestamp: cfg.fullTs,
        signature: cfg.antiTamperHash,
        ntpSynced: cfg.ntpSynced
      });
      const mat = encodeQrMatrix(qrUrl);
      const modules = mat.length;
      const qrBoxSize = 152;
      const labelH = 26;
      const totalH = qrBoxSize + labelH;
      const boxLeft = w - qrBoxSize - 26;
      const bottomOffset = cfg.styleTemplate === 3 ? 118 : 26;
      const boxTop = h - totalH - bottomOffset;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.90)';
      drawRoundedRect(ctx, boxLeft, boxTop, qrBoxSize, totalH, 14);
      ctx.fill();
      ctx.strokeStyle = cfg.antiTamperMode ? '#34d399' : '#38bdf8';
      ctx.lineWidth = 1.8;
      ctx.stroke();

      const pad = 8;
      const whiteSize = qrBoxSize - pad * 2;
      ctx.fillStyle = '#ffffff';
      drawRoundedRect(ctx, boxLeft + pad, boxTop + pad, whiteSize, whiteSize, 6);
      ctx.fill();

      // 严格预留四周 4 个模块标准静区 (modules + 8)
      const totalMods = modules + 8;
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
      ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : '#38bdf8';
      ctx.font = 'bold 15px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(cfg.antiTamperMode ? '扫码验真跳高德' : '长按跳高德', boxLeft + qrBoxSize / 2, boxTop + qrBoxSize + 18);
    }
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
    wx.showToast({ title: '已删除该照片', icon: 'none' });
  },

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
      name: cur.cfg.shortPoi || '水印实拍点',
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
    const txt = `${cur.cfg.gcjLon.toFixed(6)},${cur.cfg.gcjLat.toFixed(6)}`;
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
    this.setData({ showGallery: false });
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
    this.setData({ showWmModal: true });
  },
  closeWatermarkModal() {
    this.setData({ showWmModal: false });
  },
  onToggleQr(e) {
    this.setData({ showQr: e.detail.value });
  },
  onToggleMiniMap(e) {
    this.setData({ showMiniMap: e.detail.value });
  },
  onToggleAntiTamper(e) {
    const enabled = e.detail.value;
    this.setData({ antiTamperMode: enabled }, () => {
      if (enabled) this.syncTrustedNetworkTime();
      this.refreshClock();
      this.updateCoordString();
    });
  },
  onToggleEnv(e) {
    this.setData({ showEnv: e.detail.value });
  },
  onToggleCoordSys(e) {
    this.setData({ coordSys: e.detail.value ? 'GCJ-02' : 'WGS-84' }, () => this.updateCoordString());
  },
  onToggleAutoSave(e) {
    this.setData({ autoSaveAlbum: e.detail.value });
  },
  onToggleDms(e) {
    this.setData({ useDms: e.detail.value }, () => this.updateCoordString());
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
    const poi = encodeURIComponent(cur ? cur.cfg.shortPoi : this.data.shortPoi);
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
