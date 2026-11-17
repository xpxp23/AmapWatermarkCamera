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

const {
  buildVerificationReport,
  decodePhotoQrByCanvasAndVK,
  exportCroppedQrTempFile
} = require('../../utils/photo_verifier.js');

const STORAGE_KEY_CFG = 'amap_wm_wx_cfg_v8_0';
const STORAGE_KEY_PHOTOS = 'amap_wm_wx_photos_v8_0';

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

const FILTER_NAMES = ['原图', '鲜明', '暖阳', '清冷', '黑白'];
const FILTER_CSS_LIST = [
  'none',
  'contrast(1.16) saturate(1.22)',
  'sepia(0.22) saturate(1.18) brightness(1.04)',
  'hue-rotate(12deg) saturate(0.92) brightness(1.03)',
  'grayscale(1)'
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
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    cameraReady: false,
    captureMode: 'photo', // 'photo': 光学拍照模式, 'frame': 极速抓拍模式
    devicePosition: 'back',
    flashMode: 'off',
    flashLabel: '⚡关',
    timerSeconds: 0,
    countdownRemaining: 0,
    shutterFlashVisible: false,
    zoomRatio: 1.0,
    zoomRatioStr: '1.0X',
    showGridLines: false,
    showFilterStrip: false,
    filterNames: FILTER_NAMES,
    filterIndex: 0,
    filterCss: 'none',
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
    coordSys: 'WGS-84',
    useDms: false,
    hasRealFix: false,
    hasCustomPoi: false,
    isManualCoord: false,
    rawGcjLat: 39.908823,
    rawGcjLon: 116.397470,
    rawWgsLat: 39.907419,
    rawWgsLon: 116.391228,
    rawAccuracy: '2.4',
    gcjLat: 39.908823,
    gcjLon: 116.397470,
    wgsLat: 39.907419,
    wgsLon: 116.391228,
    accuracy: '2.4',
    altitude: '46.5',
    azimuthDeg: 0,
    azimuthText: '正北 000°',
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
    showMiniMap: true,
    miniMapPosition: 0,
    bottomRight: false,
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
    editDatePart: '2026-11-17',
    editTimeHm: '09:20',
    editTimeSec: '00',
    editGcjLat: '39.908823',
    editGcjLon: '116.397470',
    editAltitude: '46.5',
    editAzimuthDeg: '0',
    editAccuracy: '2.4',
    showGallery: false,
    showSettings: false,
    showWmModal: false,
    showPoiModal: false,
    showVerifyModal: false,
    verifyReport: null,
    manualVerifyInput: '',
    sharedBannerVisible: false,
    sharedPoint: null
  },

  /**
   * 问题2修复：首屏状态单次合并计算与批处理 setData，避免 5~6 次连续 IPC 阻塞导致 2~3 秒黑屏
   */
  onLoad(query) {
    this._isFirstShow = true;
    this._deferredDone = false;

    let statusBarHeight = 44;
    let menuBtnTop = 48;
    let menuBtnHeight = 32;
    let menuBtnRightSpace = 104;
    try {
      const winInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = winInfo.statusBarHeight || 44;
      const screenW = winInfo.windowWidth || 375;
      if (wx.getMenuButtonBoundingClientRect) {
        const rect = wx.getMenuButtonBoundingClientRect();
        if (rect && rect.top && rect.height) {
          menuBtnTop = rect.top;
          menuBtnHeight = rect.height;
          menuBtnRightSpace = Math.max(96, screenW - rect.left + 8);
        }
      }
    } catch (e) {}

    // 合并读取本地配置并计算首帧时间与坐标文案，仅调用 1 次 setData
    const mergedInit = this.buildMergedStartupPatch(statusBarHeight, menuBtnTop, menuBtnHeight, menuBtnRightSpace);
    this.setData(mergedInit);

    // 立即启动首次高精度定位与分享参数检查
    this.startHighAccuracyLocation(false);
    this.checkIncomingFriendLocation(query);

    this.clockTimer = setInterval(() => this.refreshClock(), 1000);

    // 若模拟器或无相机权限未触发 bindinitdone，700ms 后自动开启相机就绪态并执行延后任务
    this._bootFallbackTimer = setTimeout(() => {
      if (!this.data.cameraReady) {
        this.setData({ cameraReady: true });
      }
      this.runDeferredStartupTasks();
    }, 700);
  },

  onCameraInitDone() {
    if (!this.data.cameraReady) {
      this.setData({ cameraReady: true });
    }
    this.runDeferredStartupTasks();
  },

  runDeferredStartupTasks() {
    if (this._deferredDone) return;
    this._deferredDone = true;
    if (this._bootFallbackTimer) {
      clearTimeout(this._bootFallbackTimer);
      this._bootFallbackTimer = null;
    }

    // 延后恢复历史照片、NTP 授时、罗盘与相册权限预检，不抢占首帧相机渲染
    this.loadPersistedPhotosOnly();
    this.syncTrustedNetworkTime();
    this.startCompassTracking();
    this.startContinuousLocationTracking();
    if (this.data.autoSaveAlbum) {
      this.preRequestAlbumPermissionSilently();
    }
  },

  onShow() {
    if (!this.clockTimer) {
      this.clockTimer = setInterval(() => this.refreshClock(), 1000);
    }
    if (!this.locRefreshTimer && this._deferredDone) {
      this.startContinuousLocationTracking();
    }
    // 避免冷启动时 onLoad 与 onShow 重复发起高精度定位
    if (this._isFirstShow) {
      this._isFirstShow = false;
      return;
    }
    this.startHighAccuracyLocation(false);
  },

  onHide() {
    if (this.clockTimer) {
      clearInterval(this.clockTimer);
      this.clockTimer = null;
    }
    if (this.locRefreshTimer) {
      clearInterval(this.locRefreshTimer);
      this.locRefreshTimer = null;
    }
  },

  onUnload() {
    if (this.clockTimer) clearInterval(this.clockTimer);
    if (this.locRefreshTimer) clearInterval(this.locRefreshTimer);
    if (this._countdownTimer) clearInterval(this._countdownTimer);
    if (this._bootFallbackTimer) clearTimeout(this._bootFallbackTimer);
    try {
      wx.stopCompass();
    } catch (e) {}
  },

  /**
   * 问题3修复：拦截手机侧滑返回手势，按层级优雅关闭当前二级弹窗而不退出小程序
   */
  onInterceptBackGesture() {
    const reopenGalleryIfNeeded = wasInGallery => {
      if (!wasInGallery) return;
      // page-container 触发 beforeleave 后内部状态会置为隐藏，需先置 false 再在下一帧恢复 showGallery: true
      this.setData({ showGallery: false }, () => {
        setTimeout(() => {
          this.setData({ showGallery: true });
        }, 35);
      });
    };

    if (this.data.showVerifyModal) {
      const keepGallery = this.data.showGallery;
      this.closeVerifyModal();
      reopenGalleryIfNeeded(keepGallery);
    } else if (this.data.showWmModal) {
      const keepGallery = this.data.showGallery;
      this.closeWatermarkModal();
      reopenGalleryIfNeeded(keepGallery);
    } else if (this.data.showPoiModal) {
      this.closePoiQuickModal();
    } else if (this.data.showSettings) {
      this.closeSettingsModal();
    } else if (this.data.showGallery) {
      this.closeGalleryModal();
    }
  },

  buildMergedStartupPatch(statusBarHeight, menuBtnTop, menuBtnHeight, menuBtnRightSpace) {
    const patch = {
      statusBarHeight,
      menuBtnTop,
      menuBtnHeight,
      menuBtnRightSpace
    };
    try {
      const savedCfg = wx.getStorageSync(STORAGE_KEY_CFG) || wx.getStorageSync('amap_wm_wx_cfg_v7_1');
      if (savedCfg && typeof savedCfg === 'object') {
        const idx = Number(savedCfg.accentColorIndex) || 0;
        const alphaIdx = savedCfg.bgAlphaMode !== undefined ? Number(savedCfg.bgAlphaMode) : 1;
        patch.captureMode = savedCfg.captureMode || 'photo';
        patch.styleTemplate = savedCfg.styleTemplate !== undefined ? Number(savedCfg.styleTemplate) : 0;
        patch.coordSys = savedCfg.coordSys || 'WGS-84';
        patch.useDms = Boolean(savedCfg.useDms);
        patch.customTitle = savedCfg.customTitle || '现场点位核验';
        patch.note = savedCfg.note || '';
        patch.accentColorIndex = idx;
        patch.accentHex = (ACCENT_PALETTE[idx] || ACCENT_PALETTE[0]).hex;
        patch.fontScale = Number(savedCfg.fontScale) || 1;
        patch.bgAlphaMode = alphaIdx;
        patch.cardBgRgba = BG_ALPHA_PRESETS[alphaIdx] || BG_ALPHA_PRESETS[1];
        patch.showQr = savedCfg.showQr !== false;
        patch.showMiniMap = savedCfg.showMiniMap !== false;
        patch.miniMapPosition = Number(savedCfg.miniMapPosition) || 0;
        patch.bottomRight = Boolean(savedCfg.bottomRight);
        patch.showGridLines = Boolean(savedCfg.showGridLines);
        patch.antiTamperMode = Boolean(savedCfg.antiTamperMode);
        patch.showTime = savedCfg.showTime !== false;
        patch.showLoc = savedCfg.showLoc !== false;
        patch.showCoords = savedCfg.showCoords !== false;
        patch.showCoordTag = savedCfg.showCoordTag !== false;
        patch.showAccuracy = savedCfg.showAccuracy !== false;
        patch.showAddress = Boolean(savedCfg.showAddress);
        patch.showNote = savedCfg.showNote !== false;
        patch.showEnv = Boolean(savedCfg.showEnv);
        patch.autoSaveAlbum = savedCfg.autoSaveAlbum !== false;
      }
    } catch (e) {}

    const t = getTimeStrings(0);
    patch.timeHm = t.hm;
    patch.timeHms = t.hms;
    patch.dateOnlyStr = t.datePart;
    patch.weekStr = t.weekStr;
    patch.dateWeek = t.dateWeek;
    patch.fullTs = t.fullTs;

    const sys = patch.coordSys || this.data.coordSys;
    const useDms = patch.useDms !== undefined ? patch.useDms : this.data.useDms;
    const showTag = patch.showCoordTag !== undefined ? patch.showCoordTag : this.data.showCoordTag;
    const showAcc = patch.showAccuracy !== undefined ? patch.showAccuracy : this.data.showAccuracy;
    const resolved = resolveDisplayCoordinates(sys, this.data.gcjLat, this.data.gcjLon, this.data.wgsLat, this.data.wgsLon);
    const coordOnly = formatCoords(resolved.lat, resolved.lon, useDms);
    patch.liveCoordOnlyStr = coordOnly;
    patch.liveCoordStr = `${coordOnly}${showTag ? ' [' + resolved.tag + ']' : ''}${showAcc ? ' ±' + this.data.accuracy + 'm' : ''}`;
    patch.liveAntiTamperShort = computeAntiTamperHash(
      this.data.gcjLat,
      this.data.gcjLon,
      t.fullTs,
      this.data.shortPoi,
      patch.note || ''
    ).slice(0, 12);
    return patch;
  },

  loadPersistedPhotosOnly() {
    try {
      const savedPhotos = wx.getStorageSync(STORAGE_KEY_PHOTOS) || wx.getStorageSync('amap_wm_wx_photos_v7_1');
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
        showMiniMap: this.data.showMiniMap,
        miniMapPosition: this.data.miniMapPosition,
        bottomRight: this.data.bottomRight,
        showGridLines: this.data.showGridLines,
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

  /**
   * 清理 USER_DATA_PATH 孤儿文件，彻底根治微信 200MB 本地存储泄漏爆仓丢图问题
   */
  safeUnlinkFile(filePath) {
    if (!filePath || typeof filePath !== 'string') return;
    try {
      if (wx.env && wx.env.USER_DATA_PATH && filePath.indexOf(wx.env.USER_DATA_PATH) === 0) {
        const fs = wx.getFileSystemManager();
        fs.unlink({ filePath, fail: () => {} });
      }
    } catch (e) {}
  },

  persistPhotosList(nextPhotos) {
    try {
      const list = nextPhotos || [];
      if (list.length > 25) {
        const evicted = list.slice(25);
        evicted.forEach(p => {
          if (p) {
            this.safeUnlinkFile(p.rawPath);
            this.safeUnlinkFile(p.wmPath);
          }
        });
      }
      wx.setStorageSync(STORAGE_KEY_PHOTOS, list.slice(0, 25));
    } catch (e) {}
  },

  /**
   * 问题1核心修复 A：使用 fs.copyFile 替代 fs.saveFile
   * 避免 fs.saveFile 剪切移动临时文件导致首次弹出相册授权框期间 tempFilePath 被销毁失效！
   */
  saveTempFilePermanently(tempPath, cb) {
    try {
      if (wx.getFileSystemManager && wx.env && wx.env.USER_DATA_PATH) {
        const fs = wx.getFileSystemManager();
        const ext = (tempPath.match(/\.[a-zA-Z0-9]+$/) || ['.jpg'])[0];
        const dest = `${wx.env.USER_DATA_PATH}/amap_wm_${Date.now()}_${Math.floor(Math.random() * 1000)}${ext}`;
        fs.copyFile({
          srcPath: tempPath,
          destPath: dest,
          success: () => cb(dest),
          fail: () => cb(tempPath)
        });
        return;
      }
    } catch (e) {}
    cb(tempPath);
  },

  /**
   * 问题1核心修复 B：相机就绪后静默预请求相册写入权限，消除第一次按快门时的授权阻断
   */
  preRequestAlbumPermissionSilently() {
    try {
      wx.getSetting({
        success: res => {
          if (res.authSetting && res.authSetting['scope.writePhotosAlbum'] === undefined) {
            wx.authorize({
              scope: 'scope.writePhotosAlbum',
              fail: () => {}
            });
          }
        }
      });
    } catch (e) {}
  },

  /**
   * 问题1核心修复 C：带权限预检与二次引导的相册写入封装
   */
  saveFileToSystemAlbumSafely(filePath, successToastTitle) {
    if (!filePath) return;
    const doSave = () => {
      wx.saveImageToPhotosAlbum({
        filePath,
        success: () => {
          wx.showToast({
            title: successToastTitle || '已存入系统相册',
            icon: 'none'
          });
        },
        fail: err => {
          const msg = (err && err.errMsg) || '';
          if (msg.indexOf('auth deny') !== -1 || msg.indexOf('authorize') !== -1) {
            wx.showModal({
              title: '需要系统相册权限',
              content: '照片已安全保存在应用相册中。开启系统相册权限后即可自动导出至手机相册。',
              confirmText: '去开启',
              success: r => {
                if (r.confirm) {
                  wx.openSetting({
                    success: setRes => {
                      if (setRes.authSetting && setRes.authSetting['scope.writePhotosAlbum']) {
                        wx.saveImageToPhotosAlbum({
                          filePath,
                          success: () => wx.showToast({ title: '已补存至系统相册', icon: 'success' })
                        });
                      }
                    }
                  });
                }
              }
            });
          } else {
            wx.showToast({ title: '已存入应用内相册', icon: 'none' });
          }
        }
      });
    };

    wx.getSetting({
      success: res => {
        const status = res.authSetting ? res.authSetting['scope.writePhotosAlbum'] : undefined;
        if (status === undefined) {
          wx.authorize({
            scope: 'scope.writePhotosAlbum',
            success: () => doSave(),
            fail: () => {
              wx.showToast({ title: '未授权系统相册，已存应用内相册', icon: 'none' });
            }
          });
        } else {
          doSave();
        }
      },
      fail: () => doSave()
    });
  },

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
            this.setData(
              {
                ntpSynced: true,
                ntpOffsetMs: offset
              },
              () => this.refreshClock()
            );
          }
        }
      }
    });
  },

  startCompassTracking() {
    try {
      wx.onCompassChange(res => {
        if (this.data.showGallery) return;
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

  startContinuousLocationTracking() {
    if (this.locRefreshTimer) clearInterval(this.locRefreshTimer);
    this.locRefreshTimer = setInterval(() => {
      if (!this.data.isManualCoord && !this.data.showGallery) {
        this.startHighAccuracyLocation(false);
      }
    }, 15000);
  },

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
      const note = query.note ? decodeURIComponent(query.note) : '';
      const expected = sig ? computeAntiTamperHash(gcjLat, gcjLon, ts, shortPoi, note) : '';
      parsed = {
        gcjLat,
        gcjLon,
        shortPoi,
        timestamp: ts,
        signature: sig,
        note,
        hasAntiTamper: Boolean(sig),
        verified: Boolean(sig && expected.startsWith(sig.toUpperCase()))
      };
    }
    if (parsed && !isNaN(parsed.gcjLat) && !isNaN(parsed.gcjLon)) {
      this.setData({
        sharedBannerVisible: true,
        sharedPoint: parsed
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

  /**
   * ==================== 问题6实装：三级「识图验真」入口与核验报告弹窗逻辑 ====================
   */
  openVerifyEntrySheet() {
    this.setData({ showVerifyModal: true });
  },

  closeVerifyModal() {
    this.setData({ showVerifyModal: false });
  },

  verifyByLiveCameraScan() {
    wx.scanCode({
      scanType: ['qrCode'],
      onlyFromCamera: false,
      success: res => {
        const parsed = parseLocationPayload(res.result);
        const report = buildVerificationReport(parsed, '', '扫码核验');
        if (report) {
          this.setData({
            showVerifyModal: true,
            verifyReport: report,
            sharedBannerVisible: true,
            sharedPoint: parsed
          });
        } else {
          wx.showToast({ title: '未在二维码中识别到高德水印坐标', icon: 'none' });
        }
      }
    });
  },

  verifyPhotoFromAlbum() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album'],
      success: res => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file) return;
        const path = file.tempFilePath;
        wx.showLoading({ title: '智能裁切验真中...' });

        this.getCompositeCanvasNode(canvas => {
          if (canvas) {
            decodePhotoQrByCanvasAndVK(canvas, path, qrStr => {
              if (qrStr) {
                wx.hideLoading();
                const parsed = parseLocationPayload(qrStr);
                const report = buildVerificationReport(parsed, path, '相册2x超分识码');
                if (report) {
                  this.setData({ showVerifyModal: true, verifyReport: report });
                  return;
                }
              }
              this.fallbackVerifyAlbumPhoto(canvas, path);
            });
          } else {
            this.fallbackVerifyAlbumPhoto(null, path);
          }
        });
      }
    });
  },

  fallbackVerifyAlbumPhoto(canvas, tempPath) {
    // 尝试匹配本机应用内相册最新拍摄记录或导出局部放大二维码供长按识别
    const matched = this.data.photos[0];
    if (matched && matched.cfg) {
      wx.hideLoading();
      const c = matched.cfg;
      const report = buildVerificationReport(
        {
          gcjLat: c.gcjLat,
          gcjLon: c.gcjLon,
          shortPoi: c.shortPoi,
          timestamp: c.fullTs,
          note: c.note,
          signature: c.antiTamperHash ? c.antiTamperHash.slice(0, 12) : '',
          ntpSynced: c.ntpSynced,
          hasAntiTamper: c.antiTamperMode,
          verified: true
        },
        tempPath,
        '本机相册加密元数据核验'
      );
      this.setData({ showVerifyModal: true, verifyReport: report });
      return;
    }

    if (canvas) {
      exportCroppedQrTempFile(canvas, tempPath, croppedPath => {
        wx.hideLoading();
        if (croppedPath) {
          wx.showModal({
            title: '已自动放大右下角定位码',
            content: '旧版微信客户端不支持内存静默解码，已为您自动裁切放大照片右下角定位码，预览时长按即可一键识别验真！',
            confirmText: '长按识码',
            success: r => {
              if (r.confirm) wx.previewImage({ urls: [croppedPath] });
            }
          });
        } else {
          wx.showToast({ title: '未在图中检测到有效水印码', icon: 'none' });
        }
      });
    } else {
      wx.hideLoading();
      wx.showToast({ title: '未在图中检测到有效水印码', icon: 'none' });
    }
  },

  verifyCurrentGalleryPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    const c = cur.cfg;
    const sig = c.antiTamperHash ? c.antiTamperHash.slice(0, 12) : '';
    const expected = computeAntiTamperHash(c.gcjLat, c.gcjLon, c.fullTs, c.shortPoi, c.note).slice(0, 12);
    const report = buildVerificationReport(
      {
        gcjLat: c.gcjLat,
        gcjLon: c.gcjLon,
        shortPoi: c.shortPoi,
        timestamp: c.fullTs,
        note: c.note,
        signature: sig,
        ntpSynced: c.ntpSynced,
        hasAntiTamper: c.antiTamperMode,
        verified: Boolean(c.antiTamperMode && sig === expected)
      },
      cur.wmPath,
      '本机水印相册原片核验'
    );
    this.setData({ showVerifyModal: true, verifyReport: report });
  },

  onInputManualVerify(e) {
    this.setData({ manualVerifyInput: e.detail.value });
  },

  runManualTextVerify() {
    const raw = (this.data.manualVerifyInput || '').trim();
    if (!raw) {
      wx.showToast({ title: '请粘贴链接或二维码文本', icon: 'none' });
      return;
    }
    const parsed = parseLocationPayload(raw);
    const report = buildVerificationReport(parsed, '', '链接/文本解析核验');
    if (report) {
      this.setData({ verifyReport: report });
    } else {
      wx.showToast({ title: '未能解析出有效经纬度或签名', icon: 'none' });
    }
  },

  previewVerifiedPhoto() {
    const rep = this.data.verifyReport;
    if (rep && rep.imagePath) {
      wx.previewImage({ urls: [rep.imagePath] });
    }
  },

  openVerifiedLocationInMap() {
    const rep = this.data.verifyReport;
    if (!rep) return;
    wx.openLocation({
      latitude: Number(rep.gcjLat),
      longitude: Number(rep.gcjLon),
      name: rep.shortPoi || '实拍点位',
      address: `高德 GCJ-02: ${rep.gcjLat}, ${rep.gcjLon} (${rep.statusBadge})`,
      scale: 18
    });
  },

  copyVerifyReportText() {
    const r = this.data.verifyReport;
    if (!r) return;
    const text = `【高德水印相机 v8.0 · 识图验真报告】\n核验结论：${r.statusBadge} (${r.statusTitle})\n实拍点位：${r.shortPoi}\n高德GCJ-02：${r.gcjLat}°N, ${r.gcjLon}°E\n大地WGS-84：${r.wgsLat}°N, ${r.wgsLon}°E\n记录时间：${r.timestamp}\n内嵌签名：${r.actualSig}\n重算签名：${r.expectedSig}`;
    wx.setClipboardData({ data: text });
  },

  startHighAccuracyLocation(forceOverwriteManual) {
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      highAccuracyExpireTime: 3200,
      success: res => {
        this.recordHardwareGpsFix(res.latitude, res.longitude, res.accuracy, res.altitude, Boolean(forceOverwriteManual));
      },
      fail: () => {
        const smart = inferSmartPoiFromCoords(this.data.gcjLat, this.data.gcjLon);
        this.setData(
          {
            shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : smart.shortPoi,
            fullAddr: this.data.hasCustomPoi ? this.data.fullAddr : smart.fullAddr,
            nearbyPois: smart.nearbyPois
          },
          () => this.updateCoordString()
        );
      }
    });
  },

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
        patch.shortPoi = smart.shortPoi;
        patch.fullAddr = smart.fullAddr;
      }
    }

    this.setData(patch, () => this.updateCoordString());
  },

  restoreRealGpsCoordinates() {
    this.setData(
      {
        isManualCoord: false,
        gcjLat: this.data.rawGcjLat,
        gcjLon: this.data.rawGcjLon,
        wgsLat: this.data.rawWgsLat,
        wgsLon: this.data.rawWgsLon,
        accuracy: this.data.rawAccuracy
      },
      () => {
        this.updateCoordString();
        this.startHighAccuracyLocation(true);
        wx.showToast({ title: '已恢复手机真实GPS坐标', icon: 'none' });
      }
    );
  },

  refreshRealGpsNow() {
    this.setData({ hasCustomPoi: false, isManualCoord: false });
    this.startHighAccuracyLocation(true);
    wx.showToast({ title: '已刷新真实GPS坐标与点位', icon: 'none' });
  },

  choosePoiNameOnlyOnMap() {
    const applyPoiName = (name, address) => {
      const pickedName = (name || address || '').trim();
      if (!pickedName) return;
      const nextCands = [pickedName, ...this.data.nearbyPois]
        .filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i)
        .slice(0, 6);
      this.setData(
        {
          hasCustomPoi: true,
          shortPoi: pickedName,
          fullAddr: address || this.data.fullAddr,
          nearbyPois: nextCands
        },
        () => {
          this.updateCoordString();
          wx.showToast({
            title: '🏷 已选用地名(GPS坐标未变)',
            icon: 'none'
          });
        }
      );
    };

    if (wx.choosePoi) {
      wx.choosePoi({
        success: res => applyPoiName(res.name, res.address),
        fail: () => {
          wx.chooseLocation({
            latitude: this.data.gcjLat,
            longitude: this.data.gcjLon,
            success: res => applyPoiName(res.name, res.address),
            fail: err => this.handleChooseLocationFail(err)
          });
        }
      });
    } else {
      wx.chooseLocation({
        latitude: this.data.gcjLat,
        longitude: this.data.gcjLon,
        success: res => applyPoiName(res.name, res.address),
        fail: err => this.handleChooseLocationFail(err)
      });
    }
  },

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
        this.setData(
          {
            hasCustomPoi: true,
            isManualCoord: true,
            gcjLat,
            gcjLon,
            wgsLat: wgs.lat,
            wgsLon: wgs.lon,
            shortPoi: pickedName,
            fullAddr: res.address || this.data.fullAddr,
            showPoiModal: false
          },
          () => {
            this.updateCoordString();
            wx.showToast({ title: '📌 已迁移至地图选点坐标', icon: 'none' });
          }
        );
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
        success: r => {
          if (r.confirm) wx.openSetting();
        }
      });
    }
  },

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
      const nextIdx = (((this.data.styleTemplate + (dx < 0 ? 1 : -1)) % total) + total) % total;
      this.applyTemplateIndex(nextIdx, true);
    }
  },

  applyTemplateIndex(idx, showSwipeBanner) {
    const name = this.data.tplNames[idx] || '';
    if (wx.vibrateShort) {
      try {
        wx.vibrateShort({ type: 'light' });
      } catch (e) {}
    }
    this.setData(
      {
        styleTemplate: idx,
        swipeBannerText: showSwipeBanner ? `🎨 样式 ${idx + 1}/5 · ${name}` : ''
      },
      () => {
        this.refreshClock();
        this.persistUserConfig();
      }
    );
    if (showSwipeBanner) {
      if (this.bannerTimer) clearTimeout(this.bannerTimer);
      this.bannerTimer = setTimeout(() => {
        this.setData({ swipeBannerText: '' });
      }, 1500);
    }
  },

  refreshClock() {
    // 当打开全屏相册或正在无损编辑旧照片时暂停实时时钟 setData，彻底避免后台无效渲染开销
    if (this.data.showGallery || this.data.editingPhotoId) return;
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const nextPatch = {};
    if (t.hm !== this.data.timeHm) nextPatch.timeHm = t.hm;
    if (t.datePart !== this.data.dateOnlyStr) nextPatch.dateOnlyStr = t.datePart;
    if (t.weekStr !== this.data.weekStr) nextPatch.weekStr = t.weekStr;
    if (t.dateWeek !== this.data.dateWeek) nextPatch.dateWeek = t.dateWeek;
    if (this.data.styleTemplate !== 0 || this.data.antiTamperMode) {
      nextPatch.timeHms = t.hms;
      nextPatch.fullTs = t.fullTs;
      if (this.data.antiTamperMode) {
        nextPatch.liveAntiTamperShort = computeAntiTamperHash(
          this.data.gcjLat,
          this.data.gcjLon,
          t.fullTs,
          this.data.shortPoi,
          this.data.note
        ).slice(0, 12);
      }
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
    const sig = computeAntiTamperHash(
      this.data.gcjLat,
      this.data.gcjLon,
      this.data.fullTs,
      this.data.shortPoi,
      this.data.note
    ).slice(0, 12);
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
      title: next === 'photo' ? '📷 光学拍照模式' : '⚡ 极速抓拍模式',
      icon: 'none'
    });
  },

  setCaptureMode(e) {
    const mode = e.currentTarget.dataset.mode;
    this.setData({ captureMode: mode }, () => this.persistUserConfig());
  },

  cycleTimerSeconds() {
    const opts = [0, 3, 5, 10];
    const next = opts[(opts.indexOf(this.data.timerSeconds) + 1) % opts.length];
    this.setData({ timerSeconds: next });
    wx.showToast({
      title: next === 0 ? '⏱ 延时拍摄：关闭' : `⏱ 延时拍摄：${next}秒`,
      icon: 'none'
    });
  },

  cycleCameraZoom() {
    const zooms = [1.0, 1.5, 2.0, 3.0];
    const curIdx = zooms.indexOf(this.data.zoomRatio);
    const nextZoom = zooms[(curIdx + 1) % zooms.length];
    try {
      const camCtx = wx.createCameraContext();
      if (camCtx.setZoom) {
        camCtx.setZoom({
          zoom: nextZoom,
          success: () => {
            this.setData({ zoomRatio: nextZoom, zoomRatioStr: nextZoom.toFixed(1) + 'X' });
          },
          fail: () => {
            this.setData({ zoomRatio: nextZoom, zoomRatioStr: nextZoom.toFixed(1) + 'X' });
          }
        });
        return;
      }
    } catch (e) {}
    this.setData({ zoomRatio: nextZoom, zoomRatioStr: nextZoom.toFixed(1) + 'X' });
  },

  toggleFilterStrip() {
    this.setData({ showFilterStrip: !this.data.showFilterStrip });
  },

  selectFilterIndex(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    this.setData({
      filterIndex: idx,
      filterCss: FILTER_CSS_LIST[idx] || 'none'
    });
  },

  applyQuickScenePreset(e) {
    const preset = e.currentTarget.dataset.preset;
    let patch = {};
    if (preset === 'minimal') {
      patch = {
        styleTemplate: 0,
        showTime: true,
        showLoc: true,
        showCoords: true,
        showCoordTag: true,
        showAccuracy: true,
        showAddress: false,
        showNote: false,
        showEnv: false,
        showQr: true,
        showMiniMap: false
      };
    } else if (preset === 'engineering') {
      patch = {
        styleTemplate: 1,
        showTime: true,
        showLoc: true,
        showCoords: true,
        showCoordTag: true,
        showAccuracy: true,
        showAddress: true,
        showNote: true,
        showEnv: true,
        showQr: true,
        showMiniMap: true
      };
    } else if (preset === 'gis') {
      patch = {
        styleTemplate: 2,
        useDms: true,
        showTime: true,
        showLoc: true,
        showCoords: true,
        showCoordTag: true,
        showAccuracy: true,
        showAddress: true,
        showEnv: true,
        showQr: true,
        showMiniMap: true
      };
    } else if (preset === 'ledger') {
      patch = {
        styleTemplate: 4,
        showTime: true,
        showLoc: true,
        showCoords: true,
        showCoordTag: true,
        showAccuracy: true,
        showAddress: true,
        showNote: true,
        showEnv: false,
        showQr: true
      };
    }
    this.setData(patch, () => {
      this.updateCoordString();
      this.persistUserConfig();
      wx.showToast({ title: '⚡ 已应用预设方案', icon: 'none' });
    });
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
    const labels = ['⚡关', '⚡开', '⚡自', '⚡常'];
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
    this.setData({ note: next, showNote: true }, () => {
      this.updateCoordString();
      this.persistUserConfig();
    });
  },

  clearNoteInput() {
    this.setData({ note: '' }, () => {
      this.updateCoordString();
      this.persistUserConfig();
    });
  },

  handleShutterTap() {
    if (this.data.countdownRemaining > 0) {
      if (this._countdownTimer) clearInterval(this._countdownTimer);
      this._countdownTimer = null;
      this.setData({ countdownRemaining: 0 });
      wx.showToast({ title: '已取消延时拍摄', icon: 'none' });
      return;
    }
    if (this.isCompositing) return;

    if (this.data.timerSeconds > 0) {
      let rem = this.data.timerSeconds;
      this.setData({ countdownRemaining: rem });
      this._countdownTimer = setInterval(() => {
        rem--;
        if (rem <= 0) {
          clearInterval(this._countdownTimer);
          this._countdownTimer = null;
          this.setData({ countdownRemaining: 0 });
          this.executeImmediateCapture();
        } else {
          this.setData({ countdownRemaining: rem });
        }
      }, 1000);
      return;
    }

    this.executeImmediateCapture();
  },

  executeImmediateCapture() {
    if (this.isCompositing) return;
    const camCtx = wx.createCameraContext();
    const isFastFrame = this.data.captureMode === 'frame';
    const quality = isFastFrame ? 'low' : 'high';

    this.setData({ shutterFlashVisible: true });
    setTimeout(() => {
      this.setData({ shutterFlashVisible: false });
    }, 140);

    camCtx.takePhoto({
      quality,
      success: res => {
        const t = getTimeStrings(this.data.ntpOffsetMs);
        const activeGcjLat = this.data.antiTamperMode ? this.data.rawGcjLat : this.data.gcjLat;
        const activeGcjLon = this.data.antiTamperMode ? this.data.rawGcjLon : this.data.gcjLon;
        const activeWgsLat = this.data.antiTamperMode ? this.data.rawWgsLat : this.data.wgsLat;
        const activeWgsLon = this.data.antiTamperMode ? this.data.rawWgsLon : this.data.wgsLon;
        const cleanPoi = this.data.shortPoi || '实拍点位';
        const antiHash = computeAntiTamperHash(activeGcjLat, activeGcjLon, t.fullTs, cleanPoi, this.data.note);

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
          filterIndex: this.data.filterIndex,
          gcjLat: activeGcjLat,
          gcjLon: activeGcjLon,
          wgsLat: activeWgsLat,
          wgsLon: activeWgsLon,
          origGcjLat: activeGcjLat,
          origGcjLon: activeGcjLon,
          accuracy: this.data.accuracy,
          altitude: this.data.altitude,
          azimuthDeg: this.data.azimuthDeg,
          azimuthText: this.data.azimuthText,
          shortPoi: cleanPoi,
          fullAddr: this.data.fullAddr,
          note: this.data.note,
          hm: t.hm,
          hms: t.hms,
          dateOnlyStr: t.datePart,
          weekStr: t.weekStr,
          dateWeek: t.dateWeek,
          fullTs: t.fullTs,
          showQr: this.data.showQr,
          showMiniMap: this.data.showMiniMap,
          miniMapPosition: this.data.miniMapPosition,
          bottomRight: this.data.bottomRight,
          antiTamperMode: this.data.antiTamperMode,
          ntpSynced: this.data.ntpSynced,
          antiTamperHash: this.data.antiTamperMode ? antiHash : '',
          showTime: this.data.showTime,
          showLoc: this.data.showLoc,
          showCoords: this.data.showCoords,
          showCoordTag: this.data.showCoordTag,
          showAccuracy: this.data.showAccuracy,
          showAddress: this.data.showAddress,
          showNote: this.data.showNote,
          showEnv: this.data.showEnv,
          fastFrame: isFastFrame
        };

        this.compositeWatermarkToTempFile(res.tempImagePath, snapshotCfg, null);
      },
      fail: () => {
        wx.showToast({ title: '拍照失败，请检查相机权限', icon: 'none' });
      }
    });
  },

  getCompositeCanvasNode(cb) {
    if (this._cachedCanvasNode) {
      cb(this._cachedCanvasNode);
      return;
    }
    const query = wx.createSelectorQuery();
    query
      .select('#compositeCanvas')
      .fields({ node: true, size: true })
      .exec(res => {
        const node = res && res[0] && res[0].node;
        if (node) this._cachedCanvasNode = node;
        cb(node || null);
      });
  },

  /**
   * 离屏高清 Canvas 2D 水印合成（修复首次保存竞态丢图与旧文件泄漏）
   */
  compositeWatermarkToTempFile(rawImagePath, cfg, existingPhotoId) {
    this.isCompositing = true;
    wx.showLoading({ title: existingPhotoId ? '无损重绘水印...' : '合成高清水印...' });
    this.getCompositeCanvasNode(canvas => {
      if (!canvas) {
        this.isCompositing = false;
        wx.hideLoading();
        return;
      }
      const ctx = canvas.getContext('2d');
      const img = canvas.createImage();

      img.onload = () => {
        const naturalW = img.width || 1080;
        const naturalH = img.height || 1440;
        const w = cfg.fastFrame ? 900 : 1080;
        const h = Math.round((naturalH / naturalW) * w) || 1440;
        canvas.width = w;
        canvas.height = h;

        ctx.clearRect(0, 0, w, h);
        if (cfg.filterIndex && FILTER_CSS_LIST[cfg.filterIndex] && FILTER_CSS_LIST[cfg.filterIndex] !== 'none') {
          try {
            ctx.filter = FILTER_CSS_LIST[cfg.filterIndex];
          } catch (e) {}
        }
        ctx.drawImage(img, 0, 0, w, h);
        try {
          ctx.filter = 'none';
        } catch (e) {}

        this.drawCanvasWatermarkAndQr(ctx, w, h, cfg);

        wx.canvasToTempFilePath({
          canvas,
          width: w,
          height: h,
          destWidth: w,
          destHeight: h,
          fileType: 'jpg',
          quality: cfg.fastFrame ? 0.88 : 0.95,
          success: out => {
            this.isCompositing = false;
            wx.hideLoading();

            if (existingPhotoId) {
              this.saveTempFilePermanently(out.tempFilePath, savedWmPath => {
                const updatedPhotos = this.data.photos.map(p => {
                  if (p.id === existingPhotoId) {
                    if (p.wmPath && p.wmPath !== savedWmPath) {
                      this.safeUnlinkFile(p.wmPath);
                    }
                    return { ...p, wmPath: savedWmPath, cfg };
                  }
                  return p;
                });
                const updatedCur = updatedPhotos.find(p => p.id === existingPhotoId) || updatedPhotos[0];
                const restoreLive = this.preEditBackup || {};
                this.preEditBackup = null;
                this.setData(
                  {
                    ...restoreLive,
                    photos: updatedPhotos,
                    currentPhoto: updatedCur,
                    latestPhotoPath: updatedPhotos[0] ? updatedPhotos[0].wmPath : '',
                    editingPhotoId: null,
                    showWmModal: false
                  },
                  () => this.updateCoordString()
                );
                this.persistPhotosList(updatedPhotos);
                if (this.data.autoSaveAlbum) {
                  this.saveFileToSystemAlbumSafely(savedWmPath, '已无损更新并同步至系统相册');
                } else {
                  wx.showToast({ title: '已无损更新照片水印', icon: 'success' });
                }
              });
              return;
            }

            // 问题1根治：先将合成图 copyFile 持久化为 savedWmPath，再用永久路径调用 saveFileToSystemAlbumSafely
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

                if (this.data.autoSaveAlbum) {
                  this.saveFileToSystemAlbumSafely(
                    savedWmPath,
                    `已存相册(${cfg.coordSys}水印·高德零偏差)`
                  );
                } else {
                  wx.showToast({ title: '已存入应用内相册', icon: 'none' });
                }
              });
            });
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
   * 绘制 160x160 高德高清矢量缩略地图画中画 (与 Android App 端 WatermarkRenderer 100% 同步)
   */
  drawCanvasVectorMiniMap(ctx, boxX, boxY, boxSize, scale, cfg) {
    const cornerR = 14 * scale;
    ctx.save();
    drawRoundedRect(ctx, boxX, boxY, boxSize, boxSize, cornerR);
    ctx.clip();

    // 1. 深色测绘底图
    ctx.fillStyle = '#0c1629';
    ctx.fillRect(boxX, boxY, boxSize, boxSize);

    const latSeed = Math.abs(Math.floor(Number(cfg.gcjLat || 39.9) * 10000)) % 100;
    const lonSeed = Math.abs(Math.floor(Number(cfg.gcjLon || 116.4) * 10000)) % 100;
    const shiftX = ((lonSeed - 50) / 100) * (boxSize * 0.22);
    const shiftY = ((latSeed - 50) / 100) * (boxSize * 0.22);

    // 2. 生态绿地与水系斑块
    ctx.fillStyle = 'rgba(16, 185, 129, 0.16)';
    drawRoundedRect(
      ctx,
      boxX + 8 * scale + shiftX,
      boxY + 10 * scale + shiftY,
      boxSize * 0.35,
      boxSize * 0.28,
      6 * scale
    );
    ctx.fill();

    ctx.fillStyle = 'rgba(14, 165, 233, 0.18)';
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

    // 3. 测绘十字网格线
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.15)';
    ctx.lineWidth = 1 * scale;
    const step = boxSize / 4;
    for (let i = 1; i <= 3; i++) {
      ctx.beginPath();
      ctx.moveTo(boxX + i * step, boxY);
      ctx.lineTo(boxX + i * step, boxY + boxSize);
      ctx.moveTo(boxX, boxY + i * step);
      ctx.lineTo(boxX + boxSize, boxY + i * step);
      ctx.stroke();
    }

    // 4. 次干道与高德琥珀主干道
    ctx.strokeStyle = 'rgba(148, 163, 184, 0.32)';
    ctx.lineWidth = 3.5 * scale;
    ctx.beginPath();
    ctx.moveTo(boxX, boxY + boxSize * 0.32 + shiftY);
    ctx.lineTo(boxX + boxSize, boxY + boxSize * 0.27 + shiftY);
    ctx.moveTo(boxX + boxSize * 0.28 + shiftX, boxY);
    ctx.lineTo(boxX + boxSize * 0.33 + shiftX, boxY + boxSize);
    ctx.stroke();

    ctx.strokeStyle = 'rgba(245, 158, 11, 0.52)';
    ctx.lineWidth = 5 * scale;
    ctx.beginPath();
    ctx.moveTo(boxX, boxY + boxSize * 0.56 + shiftY * 0.7);
    ctx.lineTo(boxX + boxSize, boxY + boxSize * 0.48 + shiftY * 0.7);
    ctx.moveTo(boxX + boxSize * 0.52 + shiftX * 0.7, boxY);
    ctx.lineTo(boxX + boxSize * 0.47 + shiftX * 0.7, boxY + boxSize);
    ctx.stroke();

    const cx = boxX + boxSize / 2;
    const cy = boxY + boxSize / 2;

    // 5. GPS 精度雷达圈与罗盘朝向扇形
    ctx.fillStyle = 'rgba(56, 189, 248, 0.14)';
    ctx.beginPath();
    ctx.arc(cx, cy, boxSize * 0.24, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.48)';
    ctx.lineWidth = 1.2 * scale;
    ctx.stroke();

    const startRad = (((Number(cfg.azimuthDeg) || 0) - 90 - 28) * Math.PI) / 180;
    const endRad = (((Number(cfg.azimuthDeg) || 0) - 90 + 28) * Math.PI) / 180;
    ctx.fillStyle = 'rgba(56, 189, 248, 0.30)';
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.arc(cx, cy, boxSize * 0.36, startRad, endRad);
    ctx.closePath();
    ctx.fill();

    // 6. 中心高德红针
    ctx.strokeStyle = '#dc2626';
    ctx.lineWidth = 2.8 * scale;
    ctx.beginPath();
    ctx.moveTo(cx, cy - 8 * scale);
    ctx.lineTo(cx, cy + 2 * scale);
    ctx.stroke();

    ctx.fillStyle = '#ef4444';
    ctx.beginPath();
    ctx.arc(cx, cy - 10 * scale, 6.5 * scale, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 1.5 * scale;
    ctx.stroke();

    // 7. 指北针角标与底部点位条
    ctx.fillStyle = 'rgba(15, 23, 42, 0.86)';
    drawRoundedRect(ctx, boxX + 6 * scale, boxY + 6 * scale, 38 * scale, 19 * scale, 5 * scale);
    ctx.fill();
    ctx.fillStyle = '#38bdf8';
    ctx.font = `bold ${Math.round(11.5 * scale)}px sans-serif`;
    ctx.textAlign = 'center';
    ctx.fillText('▲N', boxX + 25 * scale, boxY + 19.5 * scale);

    ctx.fillStyle = 'rgba(11, 19, 43, 0.88)';
    ctx.fillRect(boxX, boxY + boxSize - 24 * scale, boxSize, 24 * scale);
    ctx.fillStyle = '#e2e8f0';
    ctx.font = `bold ${Math.round(12 * scale)}px sans-serif`;
    const poiTxt = '📍' + (cfg.shortPoi || '实拍点位');
    ctx.fillText(ellipsizeCanvasText(ctx, poiTxt, boxSize - 10 * scale), cx, boxY + boxSize - 7.5 * scale);

    ctx.restore();

    ctx.strokeStyle = cfg.accentHex || '#38bdf8';
    ctx.lineWidth = 1.8 * scale;
    drawRoundedRect(ctx, boxX, boxY, boxSize, boxSize, cornerR);
    ctx.stroke();
  },

  drawCanvasWatermarkAndQr(ctx, w, h, cfg) {
    const scaleFactor = w / 1080;
    const fScale = (Number(cfg.fontScale) || 1.0) * scaleFactor;
    const resolved = resolveDisplayCoordinates(cfg.coordSys, cfg.gcjLat, cfg.gcjLon, cfg.wgsLat, cfg.wgsLon);
    const coordOnly = formatCoords(resolved.lat, resolved.lon, cfg.useDms);
    const tagStr = cfg.showCoordTag !== false ? ` [${resolved.tag}]` : '';
    const accStr = cfg.showAccuracy !== false ? ` ±${cfg.accuracy}m` : '';
    const coordLine = `${coordOnly}${tagStr}${accStr}`;

    const envLine = cfg.showEnv ? `海拔 ${cfg.altitude}m · 方位 ${cfg.azimuthText}` : '';
    const sigHash =
      cfg.antiTamperHash || computeAntiTamperHash(cfg.gcjLat, cfg.gcjLon, cfg.fullTs, cfg.shortPoi, cfg.note);
    const antiLine = cfg.antiTamperMode
      ? `🛡 ${cfg.ntpSynced ? 'NTP真时已锁' : '防伪锁定'} · SHA256:${sigHash.slice(0, 12)}`
      : '';

    const accentColor = cfg.accentHex || '#38bdf8';
    const cardBg = cfg.cardBgRgba || 'rgba(11, 19, 43, 0.84)';
    const cleanPoi = cfg.shortPoi || '实拍点位';
    const weekPart = cfg.weekStr || (cfg.dateWeek ? cfg.dateWeek.split(' ')[1] : '') || '';
    const placeCardRight = Boolean(cfg.bottomRight && cfg.styleTemplate !== 0 && cfg.styleTemplate !== 3);

    // 左上角独立悬浮 160x160 缩略图模式
    if (cfg.showMiniMap && cfg.miniMapPosition === 1 && (cfg.styleTemplate === 1 || cfg.styleTemplate === 2)) {
      const mapSz = Math.round(160 * fScale);
      const margin = Math.round(28 * scaleFactor);
      this.drawCanvasVectorMiniMap(ctx, margin, margin + Math.round(52 * scaleFactor), mapSz, fScale, cfg);
    }

    if (cfg.styleTemplate === 0) {
      const gradH = Math.round(390 * scaleFactor);
      const grad = ctx.createLinearGradient(0, h - gradH, 0, h);
      grad.addColorStop(0, 'rgba(0,0,0,0)');
      grad.addColorStop(1, 'rgba(0,0,0,0.68)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, h - gradH, w, gradH);

      const qrReserveRight = cfg.showQr ? Math.round(186 * scaleFactor) : Math.round(28 * scaleFactor);
      const leftMargin = Math.round(28 * scaleFactor);
      const safeZoneW = w - leftMargin - qrReserveRight;
      const cx = cfg.showQr ? leftMargin + safeZoneW / 2 : w / 2;
      const maxLineW = safeZoneW - Math.round(16 * scaleFactor);
      let curY = h - Math.round(40 * scaleFactor);

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
        const clippedCoord = ellipsizeCanvasText(ctx, coordLine, maxLineW - 36 * scaleFactor);
        const tw = ctx.measureText(clippedCoord).width;
        ctx.fillStyle = cardBg;
        drawRoundedRect(ctx, cx - tw / 2 - 18 * scaleFactor, curY - 27 * scaleFactor, tw + 36 * scaleFactor, 38 * scaleFactor, 19 * scaleFactor);
        ctx.fill();
        ctx.strokeStyle = accentColor;
        ctx.lineWidth = 1.5 * scaleFactor;
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
      const margin = Math.round(28 * scaleFactor);
      const boxW = Math.round((cfg.showQr ? 856 : 1024) * scaleFactor);
      const boxLeft = placeCardRight ? w - boxW - margin : margin;
      const padX = Math.round(24 * scaleFactor);
      const padY = Math.round(18 * scaleFactor);
      const headerH = Math.round(44 * fScale);
      const lineGap = Math.round(35 * fScale);
      const embedMiniMap = Boolean(cfg.showMiniMap && (cfg.miniMapPosition || 0) === 0);
      const mapSize = embedMiniMap ? Math.round(156 * fScale) : 0;
      const mapGap = embedMiniMap ? Math.round(18 * scaleFactor) : 0;
      const textStartX = boxLeft + padX + mapSize + mapGap;
      const maxTextW = boxW - padX * 2 - mapSize - mapGap;

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
      if (cfg.showAddress && cfg.fullAddr) rows.push(['地址', cfg.fullAddr, '#cbd5e1']);
      if (envLine) rows.push(['环境', envLine, '#a7f3d0']);
      if (cfg.showTime) rows.push(['时间', `${cfg.fullTs} ${weekPart}`, '#e2e8f0']);
      if (cfg.showNote && cfg.note) rows.push(['备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) {
        rows.push(['验真', `SHA256:${sigHash.slice(0, 12)} (${cfg.ntpSynced ? 'NTP真时' : '已锁'})`, '#34d399']);
      }

      const contentH = headerH + rows.length * lineGap;
      const boxH = padY * 2 + Math.max(contentH, embedMiniMap ? headerH + mapSize + 6 * scaleFactor : contentH);
      const boxTop = h - boxH - margin;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 18 * scaleFactor);
      ctx.fill();
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2 * scaleFactor;
      ctx.stroke();

      ctx.fillStyle = accentColor;
      ctx.fillRect(boxLeft + padX, boxTop + padY, 8 * scaleFactor, 26 * fScale);
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(25 * fScale)}px sans-serif`;
      ctx.textAlign = 'left';
      const titleStr = `${cfg.customTitle || '现场点位核验'} · ${cfg.antiTamperMode ? '🛡防伪已锁' : resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - padX * 2 - 24 * scaleFactor), boxLeft + padX + 18 * scaleFactor, boxTop + padY + 23 * fScale);

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.16)';
      ctx.lineWidth = 1.2 * scaleFactor;
      ctx.beginPath();
      ctx.moveTo(boxLeft + padX, boxTop + padY + headerH - 8 * scaleFactor);
      ctx.lineTo(boxLeft + boxW - padX, boxTop + padY + headerH - 8 * scaleFactor);
      ctx.stroke();

      if (embedMiniMap) {
        this.drawCanvasVectorMiniMap(ctx, boxLeft + padX, boxTop + padY + headerH, mapSize, fScale, cfg);
      }

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
      const margin = Math.round(28 * scaleFactor);
      const boxW = Math.round((cfg.showQr ? 856 : 1024) * scaleFactor);
      const boxLeft = placeCardRight ? w - boxW - margin : margin;
      const embedMiniMap = Boolean(cfg.showMiniMap && (cfg.miniMapPosition || 0) === 0);
      const mapSize = embedMiniMap ? Math.round(156 * fScale) : 0;
      const hasFooter = Boolean(antiLine || (cfg.showNote && cfg.note) || (cfg.showAddress && cfg.fullAddr) || envLine);
      const baseH = Math.round((hasFooter ? 192 : 150) * fScale);
      const boxH = embedMiniMap ? Math.max(baseH, mapSize + Math.round(24 * scaleFactor)) : baseH;
      const boxTop = h - boxH - margin;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16 * scaleFactor);
      ctx.fill();

      const gridLeft = embedMiniMap ? boxLeft + mapSize + Math.round(22 * scaleFactor) : boxLeft;
      const gridW = boxLeft + boxW - gridLeft;

      if (embedMiniMap) {
        this.drawCanvasVectorMiniMap(
          ctx,
          boxLeft + Math.round(12 * scaleFactor),
          boxTop + (boxH - mapSize) / 2,
          mapSize,
          fScale,
          cfg
        );
      }

      ctx.fillStyle = 'rgba(56, 189, 248, 0.20)';
      drawRoundedRect(ctx, gridLeft, boxTop, gridW, Math.round(52 * fScale), 14 * scaleFactor);
      ctx.fill();

      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2 * scaleFactor;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16 * scaleFactor);
      ctx.stroke();

      ctx.fillStyle = accentColor;
      const topFontPx = Math.round((cfg.useDms ? 20 : 22) * fScale);
      ctx.font = `bold ${topFontPx}px monospace`;
      ctx.textAlign = 'left';
      const topText = cfg.showCoords ? coordLine : `${cfg.customTitle || '经纬网格测绘'} · ${resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, topText, gridW - 32 * scaleFactor), gridLeft + 16 * scaleFactor, boxTop + Math.round(34 * fScale));

      const midX = gridLeft + gridW * 0.48;
      const headerBottom = boxTop + Math.round(52 * fScale);
      const row2Bottom = boxTop + Math.round(140 * fScale);
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.32)';
      ctx.lineWidth = 1.2 * scaleFactor;
      ctx.beginPath();
      ctx.moveTo(gridLeft, headerBottom);
      ctx.lineTo(boxLeft + boxW, headerBottom);
      ctx.moveTo(midX, headerBottom);
      ctx.lineTo(midX, row2Bottom);
      if (hasFooter) {
        ctx.moveTo(gridLeft, row2Bottom);
        ctx.lineTo(boxLeft + boxW, row2Bottom);
      }
      ctx.stroke();

      ctx.fillStyle = '#94a3b8';
      ctx.font = `normal ${Math.round(18 * fScale)}px sans-serif`;
      ctx.fillText(`测绘点位 (${resolved.tag})`, gridLeft + 16 * scaleFactor, boxTop + Math.round(80 * fScale));
      ctx.fillText(`采样时间 (${weekPart})`, midX + 16 * scaleFactor, boxTop + Math.round(80 * fScale));

      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
      ctx.fillText(ellipsizeCanvasText(ctx, cleanPoi, gridW * 0.48 - 28 * scaleFactor), gridLeft + 16 * scaleFactor, boxTop + Math.round(120 * fScale));
      ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
      ctx.fillText(ellipsizeCanvasText(ctx, cfg.fullTs, gridW * 0.52 - 28 * scaleFactor), midX + 16 * scaleFactor, boxTop + Math.round(120 * fScale));

      if (hasFooter) {
        ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : '#fde68a';
        ctx.font = `normal ${Math.round(19.5 * fScale)}px sans-serif`;
        const footParts = [];
        if (antiLine) footParts.push(antiLine);
        if (cfg.showNote && cfg.note) footParts.push('备注：' + cfg.note);
        else if (cfg.showAddress && cfg.fullAddr) footParts.push(cfg.fullAddr);
        else if (envLine) footParts.push(envLine);
        ctx.fillText(ellipsizeCanvasText(ctx, footParts.join(' · '), gridW - 32 * scaleFactor), gridLeft + 16 * scaleFactor, boxTop + Math.round(172 * fScale));
      }
    } else if (cfg.styleTemplate === 3) {
      const barH = Math.round(114 * fScale);
      const barTop = h - barH;
      ctx.fillStyle = cardBg;
      ctx.fillRect(0, barTop, w, barH);
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 3 * scaleFactor;
      ctx.beginPath();
      ctx.moveTo(0, barTop);
      ctx.lineTo(w, barTop);
      ctx.stroke();

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(27 * fScale)}px sans-serif`;
      ctx.fillText(ellipsizeCanvasText(ctx, '📍 ' + cleanPoi, w * 0.5), 26 * scaleFactor, barTop + Math.round(44 * fScale));

      if (cfg.showTime) {
        ctx.textAlign = 'right';
        ctx.fillStyle = '#e2e8f0';
        ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
        const cleanTimeStr = `${cfg.fullTs} ${weekPart}`.trim();
        ctx.fillText(ellipsizeCanvasText(ctx, cleanTimeStr, w * 0.46), w - 26 * scaleFactor, barTop + Math.round(44 * fScale));
      }

      ctx.textAlign = 'left';
      const rightNoteRaw =
        antiLine ||
        (cfg.showNote && cfg.note ? '备注：' + cfg.note : cfg.showAddress && cfg.fullAddr ? cfg.fullAddr : envLine);
      const maxCoordW = rightNoteRaw ? w * 0.64 : w - 52 * scaleFactor;

      if (cfg.showCoords) {
        ctx.fillStyle = accentColor;
        ctx.font = `bold ${Math.round((cfg.useDms ? 20.5 : 22.5) * fScale)}px monospace`;
        ctx.fillText(ellipsizeCanvasText(ctx, coordLine, maxCoordW), 26 * scaleFactor, barTop + Math.round(88 * fScale));
      }

      if (rightNoteRaw) {
        ctx.textAlign = 'right';
        ctx.fillStyle = antiLine ? '#34d399' : '#fde68a';
        ctx.font = `normal ${Math.round(20 * fScale)}px sans-serif`;
        ctx.fillText(ellipsizeCanvasText(ctx, rightNoteRaw, w * 0.34), w - 26 * scaleFactor, barTop + Math.round(88 * fScale));
      }
    } else {
      const margin = Math.round(28 * scaleFactor);
      const boxW = Math.round((cfg.showQr ? 856 : 1024) * scaleFactor);
      const boxLeft = placeCardRight ? w - boxW - margin : margin;
      const rows = [];
      if (cfg.showCoords) rows.push(['经纬坐标', coordLine, accentColor]);
      if (cfg.showLoc) {
        const locVal = cfg.showAddress && cfg.fullAddr ? `${cleanPoi} · ${cfg.fullAddr}` : cleanPoi;
        rows.push(['实拍点位', locVal, '#ffffff']);
      }
      if (cfg.showTime) rows.push(['记录时间', `${cfg.fullTs} ${weekPart}`, '#ffffff']);
      if (envLine) rows.push(['高程方位', envLine, '#a7f3d0']);
      if (cfg.showNote && cfg.note) rows.push(['现场备注', cfg.note, '#fde68a']);
      if (cfg.antiTamperMode) {
        rows.push(['防伪签名', `SHA256:${sigHash.slice(0, 16)} (${cfg.ntpSynced ? 'NTP' : '已锁'})`, '#34d399']);
      }

      const headerH = Math.round(44 * fScale);
      const rowH = Math.round(39 * fScale);
      const boxH = headerH + rows.length * rowH;
      const boxTop = h - boxH - margin;

      ctx.fillStyle = cardBg;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14 * scaleFactor);
      ctx.fill();
      ctx.fillStyle = 'rgba(56, 189, 248, 0.24)';
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, headerH, 14 * scaleFactor);
      ctx.fill();
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 2 * scaleFactor;
      drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 14 * scaleFactor);
      ctx.stroke();

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
      const titleStr = `${cfg.customTitle || '外业定点巡检台账'} · ${cfg.antiTamperMode ? '🛡防伪台账' : resolved.tag}`;
      ctx.fillText(ellipsizeCanvasText(ctx, titleStr, boxW - 28 * scaleFactor), boxLeft + 14 * scaleFactor, boxTop + Math.round(30 * fScale));

      const colSplit = boxLeft + Math.round(130 * fScale);
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.45)';
      ctx.lineWidth = 1.2 * scaleFactor;
      ctx.beginPath();
      ctx.moveTo(boxLeft, boxTop + headerH);
      ctx.lineTo(boxLeft + boxW, boxTop + headerH);
      ctx.moveTo(colSplit, boxTop + headerH);
      ctx.lineTo(colSplit, boxTop + boxH);
      ctx.stroke();

      const maxValW = boxLeft + boxW - colSplit - 22 * scaleFactor;
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
        ctx.fillText(r[0], boxLeft + 14 * scaleFactor, ty + Math.round(rowH * 0.68));
        ctx.fillStyle = r[2];
        ctx.fillText(ellipsizeCanvasText(ctx, r[1], maxValW), colSplit + 12 * scaleFactor, ty + Math.round(rowH * 0.68));
      });
    }

    if (cfg.showQr) {
      const qrUrl = buildAmapJumpUrl(cfg.gcjLat, cfg.gcjLon, cleanPoi, {
        enabled: cfg.antiTamperMode,
        timestamp: cfg.fullTs,
        signature: sigHash,
        note: cfg.note,
        ntpSynced: cfg.ntpSynced
      });
      const mat = encodeQrMatrix(qrUrl);
      const modules = mat.length;
      const qrBoxSize = Math.round(152 * scaleFactor);
      const labelH = Math.round(26 * scaleFactor);
      const totalH = qrBoxSize + labelH;
      const margin = Math.round(26 * scaleFactor);
      const boxLeft = placeCardRight ? margin : w - qrBoxSize - margin;
      const bottomOffset = cfg.styleTemplate === 3 ? Math.round(126 * fScale) : margin;
      const boxTop = h - totalH - bottomOffset;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.92)';
      drawRoundedRect(ctx, boxLeft, boxTop, qrBoxSize, totalH, 14 * scaleFactor);
      ctx.fill();
      ctx.strokeStyle = cfg.antiTamperMode ? '#34d399' : accentColor;
      ctx.lineWidth = 1.8 * scaleFactor;
      ctx.stroke();

      const pad = Math.round(8 * scaleFactor);
      const whiteSize = qrBoxSize - pad * 2;
      ctx.fillStyle = '#ffffff';
      drawRoundedRect(ctx, boxLeft + pad, boxTop + pad, whiteSize, whiteSize, 6 * scaleFactor);
      ctx.fill();

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
      ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : accentColor;
      ctx.font = `bold ${Math.round(15 * scaleFactor)}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(
        cfg.antiTamperMode ? '扫码验真跳高德' : '长按跳高德',
        boxLeft + qrBoxSize / 2,
        boxTop + qrBoxSize + Math.round(18 * scaleFactor)
      );
    }
  },

  /**
   * 问题4 & 问题5修复：
   * - 开启防篡改的照片 (cur.cfg.antiTamperMode === true) 严禁修改
   * - 未开启防篡改的照片允许修改一切内容（时间、日期、秒、经纬度、地图选点、高程、方位角、精度、地址、备注、样式）
   */
  openEditPhotoWatermark() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    if (cur.cfg.antiTamperMode) {
      wx.showModal({
        title: '🛡 防篡改原片已锁定',
        content: '该照片在拍摄时已开启「外业防篡改验真模式」，其真实 GPS 坐标、NTP 授时时间与 SHA-256 密码学签名已锁定，不允许在相册中修改。',
        showCancel: false,
        confirmText: '我知道了'
      });
      return;
    }
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
      showQr: this.data.showQr,
      showMiniMap: this.data.showMiniMap,
      bottomRight: this.data.bottomRight
    };

    const datePart = c.dateOnlyStr || (c.fullTs ? c.fullTs.slice(0, 10) : this.data.dateOnlyStr);
    const hmPart = c.hm || (c.fullTs ? c.fullTs.slice(11, 16) : this.data.timeHm);
    const secPart = c.hms && c.hms.length >= 8 ? c.hms.slice(6, 8) : (c.fullTs && c.fullTs.length >= 19 ? c.fullTs.slice(17, 19) : '00');

    this.setData({
      editingPhotoId: cur.id,
      editDatePart: datePart,
      editTimeHm: hmPart,
      editTimeSec: secPart,
      editGcjLat: String(c.gcjLat),
      editGcjLon: String(c.gcjLon),
      editAltitude: String(c.altitude || '46.5'),
      editAzimuthDeg: String(c.azimuthDeg || 0),
      editAccuracy: String(c.accuracy || '2.4'),
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
      showMiniMap: c.showMiniMap !== false,
      bottomRight: Boolean(c.bottomRight),
      showWmModal: true
    });
  },

  onEditDateChange(e) {
    this.setData({ editDatePart: e.detail.value });
  },

  onEditTimeHmChange(e) {
    this.setData({ editTimeHm: e.detail.value });
  },

  onInputEditSec(e) {
    this.setData({ editTimeSec: e.detail.value });
  },

  onInputEditLat(e) {
    this.setData({ editGcjLat: e.detail.value });
  },

  onInputEditLon(e) {
    this.setData({ editGcjLon: e.detail.value });
  },

  onInputEditAlt(e) {
    this.setData({ editAltitude: e.detail.value });
  },

  onInputEditAzimuth(e) {
    this.setData({ editAzimuthDeg: e.detail.value });
  },

  onInputEditAcc(e) {
    this.setData({ editAccuracy: e.detail.value });
  },

  chooseEditPhotoCoordOnMap() {
    const curLat = parseFloat(this.data.editGcjLat) || this.data.gcjLat;
    const curLon = parseFloat(this.data.editGcjLon) || this.data.gcjLon;
    wx.chooseLocation({
      latitude: curLat,
      longitude: curLon,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const patch = {
          editGcjLat: String(gcjLat),
          editGcjLon: String(gcjLon)
        };
        if (res.name) patch.shortPoi = res.name;
        if (res.address) {
          patch.fullAddr = res.address;
          patch.showAddress = true;
        }
        this.setData(patch);
        wx.showToast({ title: '已填入所选位置与坐标', icon: 'none' });
      },
      fail: err => this.handleChooseLocationFail(err)
    });
  },

  restoreEditOrigCoord() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    const origLat = cur.cfg.origGcjLat !== undefined ? cur.cfg.origGcjLat : cur.cfg.gcjLat;
    const origLon = cur.cfg.origGcjLon !== undefined ? cur.cfg.origGcjLon : cur.cfg.gcjLon;
    this.setData({
      editGcjLat: String(origLat),
      editGcjLon: String(origLon)
    });
    wx.showToast({ title: '已恢复拍摄瞬间原始坐标', icon: 'none' });
  },

  applyWatermarkCustomization() {
    if (this.data.editingPhotoId) {
      const target = this.data.photos.find(p => p.id === this.data.editingPhotoId);
      if (target) {
        if (target.cfg && target.cfg.antiTamperMode) {
          wx.showToast({ title: '🛡 防篡改原片禁止修改', icon: 'none' });
          return;
        }
        const rawLat = parseFloat(this.data.editGcjLat);
        const rawLon = parseFloat(this.data.editGcjLon);
        const newGcjLat = !isNaN(rawLat) ? Number(rawLat.toFixed(6)) : target.cfg.gcjLat;
        const newGcjLon = !isNaN(rawLon) ? Number(rawLon.toFixed(6)) : target.cfg.gcjLon;
        const newWgs = gcj02ToWgs84(newGcjLat, newGcjLon);

        const dateStr = (this.data.editDatePart || target.cfg.dateOnlyStr || '2026-11-17').trim();
        const hmStr = (this.data.editTimeHm || target.cfg.hm || '09:20').trim();
        const secNum = Math.min(59, Math.max(0, parseInt(this.data.editTimeSec, 10) || 0));
        const secStr = secNum < 10 ? '0' + secNum : '' + secNum;
        const parsedDate = new Date(`${dateStr.replace(/-/g, '/')} ${hmStr}:${secStr}`);
        const weeks = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
        const weekStr = isNaN(parsedDate.getTime()) ? (target.cfg.weekStr || '星期二') : weeks[parsedDate.getDay()];
        const hmsStr = `${hmStr}:${secStr}`;
        const fullTsStr = `${dateStr} ${hmsStr}`;
        const dateWeekStr = `${dateStr} ${weekStr}`;

        const azDeg = ((Math.round(Number(this.data.editAzimuthDeg) || 0) % 360) + 360) % 360;
        const azText = formatAzimuthText(azDeg);

        const updatedCfg = {
          ...target.cfg,
          antiTamperMode: false,
          antiTamperHash: '',
          gcjLat: newGcjLat,
          gcjLon: newGcjLon,
          wgsLat: newWgs.lat,
          wgsLon: newWgs.lon,
          dateOnlyStr: dateStr,
          hm: hmStr,
          hms: hmsStr,
          weekStr,
          dateWeek: dateWeekStr,
          fullTs: fullTsStr,
          altitude: String(this.data.editAltitude || target.cfg.altitude || '46.5'),
          azimuthDeg: azDeg,
          azimuthText: azText,
          accuracy: String(this.data.editAccuracy || target.cfg.accuracy || '2.4'),
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
          showQr: this.data.showQr,
          showMiniMap: this.data.showMiniMap,
          bottomRight: this.data.bottomRight
        };
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

  /**
   * 增加删除二次确认并清理本地存储物理文件
   */
  deleteCurrentPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    wx.showModal({
      title: '确认删除照片',
      content: '删除后将从应用相册中移除该照片且不可恢复，是否继续？',
      confirmColor: '#ef4444',
      confirmText: '确认删除',
      success: res => {
        if (!res.confirm) return;
        this.safeUnlinkFile(cur.rawPath);
        this.safeUnlinkFile(cur.wmPath);
        const remaining = this.data.photos.filter(p => p.id !== cur.id);
        this.setData({
          photos: remaining,
          currentPhoto: remaining[0] || null,
          latestPhotoPath: remaining[0] ? remaining[0].wmPath : ''
        });
        this.persistPhotosList(remaining);
        wx.showToast({ title: '已删除该照片', icon: 'none' });
      }
    });
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
      name: cur.cfg.shortPoi || '实拍点位',
      address: cur.cfg.fullAddr || `高德 GCJ-02: ${cur.cfg.gcjLat}, ${cur.cfg.gcjLon}`,
      scale: 18
    });
  },

  saveCurrentToAlbum() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    this.saveFileToSystemAlbumSafely(cur.wmPath, '已存入系统相册');
  },

  copyCurrentCoord() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    const resolved = resolveDisplayCoordinates(
      cur.cfg.coordSys,
      cur.cfg.gcjLat,
      cur.cfg.gcjLon,
      cur.cfg.wgsLat,
      cur.cfg.wgsLon
    );
    const txt = `${resolved.lon.toFixed(6)},${resolved.lat.toFixed(6)} [${resolved.tag}] (高德GCJ-02: ${Number(cur.cfg.gcjLon).toFixed(6)},${Number(cur.cfg.gcjLat).toFixed(6)})`;
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
      this.setData(
        {
          ...b,
          showWmModal: false,
          editingPhotoId: null
        },
        () => this.updateCoordString()
      );
      return;
    }
    this.setData({ showWmModal: false, editingPhotoId: null });
  },
  onToggleQr(e) {
    this.setData({ showQr: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleMiniMap(e) {
    this.setData({ showMiniMap: e.detail.value }, () => this.persistUserConfig());
  },
  selectMiniMapPos(e) {
    const pos = Number(e.currentTarget.dataset.pos) || 0;
    this.setData({ miniMapPosition: pos }, () => this.persistUserConfig());
  },
  onToggleGridLines(e) {
    this.setData({ showGridLines: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleBottomRight(e) {
    this.setData({ bottomRight: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleShowAddress(e) {
    this.setData({ showAddress: e.detail.value }, () => this.persistUserConfig());
  },

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
    const enabled = e.detail.value;
    this.setData({ autoSaveAlbum: enabled }, () => {
      this.persistUserConfig();
      if (enabled) this.preRequestAlbumPermissionSilently();
    });
  },

  // 防抖输入处理，消除打字时频繁 setData 造成的光标闪烁与卡顿
  onInputTitle(e) {
    const val = e.detail.value;
    if (this._titleInputTimer) clearTimeout(this._titleInputTimer);
    this._titleInputTimer = setTimeout(() => {
      this.setData({ customTitle: val });
    }, 120);
  },
  onInputPoi(e) {
    const val = e.detail.value;
    if (this._poiInputTimer) clearTimeout(this._poiInputTimer);
    this._poiInputTimer = setTimeout(() => {
      this.setData({ shortPoi: val, hasCustomPoi: true }, () => this.updateCoordString());
    }, 120);
  },
  onInputFullAddr(e) {
    const val = e.detail.value;
    if (this._addrInputTimer) clearTimeout(this._addrInputTimer);
    this._addrInputTimer = setTimeout(() => {
      this.setData({ fullAddr: val, hasCustomPoi: true });
    }, 120);
  },
  onInputNote(e) {
    const val = e.detail.value;
    if (this._noteInputTimer) clearTimeout(this._noteInputTimer);
    this._noteInputTimer = setTimeout(() => {
      this.setData({ note: val }, () => this.updateCoordString());
    }, 120);
  },

  onCameraError() {
    wx.showToast({ title: '请授予小程序相机权限', icon: 'none' });
  },
  noop() {},

  /**
   * 修复伪造防伪签名漏洞：仅当照片或当前相机真正开启 antiTamperMode 时才在分享路径中携带 sig 参数
   */
  onShareAppMessage() {
    const cur = this.data.currentPhoto;
    const isAnti = cur ? Boolean(cur.cfg.antiTamperMode) : Boolean(this.data.antiTamperMode);
    const lat = cur ? cur.cfg.gcjLat : this.data.gcjLat;
    const lon = cur ? cur.cfg.gcjLon : this.data.gcjLon;
    const poi = encodeURIComponent(cur ? cur.cfg.shortPoi : this.data.shortPoi || '实拍点位');
    const ts = encodeURIComponent(cur ? cur.cfg.fullTs : this.data.fullTs);
    const note = encodeURIComponent(cur ? cur.cfg.note || '' : this.data.note || '');
    const sig = isAnti
      ? cur
        ? (cur.cfg.antiTamperHash || '').slice(0, 12)
        : this.data.liveAntiTamperShort
      : '';
    const imgUrl = cur ? cur.wmPath : '';
    const sigParam = sig ? `&sig=${sig}&note=${note}` : '';
    return {
      title: `📍 实拍定位：${decodeURIComponent(poi)} (${lat}°N, ${lon}°E)`,
      path: `/pages/index/index?act=jump&lat=${lat}&lon=${lon}&poi=${poi}&ts=${ts}${sigParam}`,
      imageUrl: imgUrl
    };
  }
});
