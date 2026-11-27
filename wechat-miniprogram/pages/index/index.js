/**
 * 极速高德水印相机 v9.0.0 · 主取景页控制器
 * 架构升级：
 * - 实时预览交由 <watermark-overlay> 独立组件局部刷新
 * - 水印合成交由 utils/watermark_renderer.js 独立离屏引擎
 * - 识图验真交由 utils/photo_verifier.js（零假阳性）
 * - 相册浏览与批量加水印拆分至独立分包 packageAlbum / packageBatch，离页自动卸载 <camera> 释放显存
 */

const {
  DEFAULT_FALLBACK_LAT,
  DEFAULT_FALLBACK_LON,
  gcj02ToWgs84,
  canSignAntiTamper,
  formatAzimuthText,
  getTimeStrings,
  computeAntiTamperHash,
  verifyAntiTamperHash,
  parseLocationPayload,
  inferSmartPoiFromCoords
} = require('../../utils/coord_qr.js');

const {
  buildVerificationReport,
  formatVerifyReportText,
  verifyImageFile
} = require('../../utils/photo_verifier.js');

const { renderWatermarkToTempFile } = require('../../utils/watermark_renderer.js');

const {
  loadUserConfig,
  saveUserConfig,
  loadPhotos,
  addPhotoEntry,
  copyTempToUserData
} = require('../../utils/photo_store.js');

const {
  registerPrivacyListener,
  ensurePrivacyAuthorized,
  promptOpenSetting,
  preRequestAlbumPermissionSilently,
  saveImageToAlbumWithPermission
} = require('../../utils/privacy.js');

const {
  checkImageSecurity,
  checkTextsSecurity,
  showViolationModal
} = require('../../utils/sec_check.js');

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

Page({
  data: {
    statusBarHeight: 44,
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    pageActive: true,
    cameraReady: false,
    captureMode: 'photo',
    devicePosition: 'back',
    flashMode: 'off',
    flashLabel: '⚡关',
    timerSeconds: 0,
    countdownRemaining: 0,
    shutterFlashVisible: false,
    zoomRatio: 1.0,
    zoomRatioStr: '1.0X',
    showGridLines: false,
    styleTemplate: 0,
    showBottomScrim: false,
    tplNames: ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'],
    quickTitleTags: ['现场点位核验', '工程巡检记录', '隐蔽工程验收', '外业测绘定桩', '安全隐患排查'],
    quickNoteTags: ['核验合格', '定位已校准', '施工进行中', '待整改复核', '初检通过'],
    accentColors: ACCENT_PALETTE,
    accentColorIndex: 0,
    accentHex: '#38bdf8',
    fontScale: 1,
    bgAlphaMode: 1,
    cardBgRgba: BG_ALPHA_PRESETS[1],
    customTitle: '现场点位核验',
    coordSys: 'WGS-84',
    useDms: false,
    hasRealFix: false,
    locationDenied: false,
    hasCustomPoi: false,
    isManualCoord: false,
    rawGcjLat: DEFAULT_FALLBACK_LAT,
    rawGcjLon: DEFAULT_FALLBACK_LON,
    rawWgsLat: 39.907419,
    rawWgsLon: 116.391228,
    rawAccuracy: '2.4',
    gcjLat: DEFAULT_FALLBACK_LAT,
    gcjLon: DEFAULT_FALLBACK_LON,
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
    showQr: true,
    showMiniMap: true,
    miniMapPosition: 0,
    bottomRight: false,
    antiTamperMode: false,
    ntpSynced: false,
    ntpOffsetMs: 0,
    ntpStatusText: '本地单调时钟校准',
    showTime: true,
    showLoc: true,
    showCoords: true,
    showCoordTag: true,
    showAccuracy: true,
    showAddress: false,
    showNote: true,
    showEnv: false,
    autoSaveAlbum: true,
    photoCount: 0,
    latestPhotoThumb: '',
    showSettings: false,
    showWmModal: false,
    showPoiModal: false,
    showVerifyModal: false,
    verifyReport: null,
    manualVerifyInput: '',
    sharedBannerVisible: false,
    sharedPoint: null
  },

  onLoad(query) {
    this._isFirstShow = true;
    this._deferredDone = false;
    this._locationDenied = false;
    this._compassBound = false;
    this._lastCompassTs = 0;
    this._inputBuffers = {};
    this._inputTimers = {};

    registerPrivacyListener();

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

    const mergedInit = this.buildMergedStartupPatch(statusBarHeight, menuBtnTop, menuBtnHeight, menuBtnRightSpace);
    this.setData(mergedInit);
    this._safeTextSnapshot = {
      customTitle: mergedInit.customTitle !== undefined ? mergedInit.customTitle : this.data.customTitle,
      shortPoi: this.data.shortPoi,
      fullAddr: this.data.fullAddr,
      note: mergedInit.note !== undefined ? mergedInit.note : this.data.note
    };

    this.consumeLaunchQuery(query);

    ensurePrivacyAuthorized().then(ok => {
      if (ok) {
        this.startHighAccuracyLocation(false, false);
      }
    });

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

    this.refreshPhotoSummary();
    this.syncTrustedNetworkTime();
    this.startCompassTracking();
    this.startContinuousLocationTracking();
    if (this.data.autoSaveAlbum) {
      preRequestAlbumPermissionSilently();
    }
  },

  onShow() {
    if (!this.data.pageActive) {
      this.setData({ pageActive: true });
    }

    // 热启动丢参修复：消费 app.globalData.launchQuery
    const app = getApp();
    if (app && app.globalData && app.globalData.launchQuery) {
      const q = app.globalData.launchQuery;
      app.globalData.launchQuery = null;
      this.consumeLaunchQuery(q);
    }

    // 从相册/批量分包返回时同步最新相册缩略图和数量
    this.refreshPhotoSummary();

    if (this._deferredDone) {
      this.startCompassTracking();
      if (!this._locationDenied && !this.locRefreshTimer) {
        this.startContinuousLocationTracking();
      }
    }

    if (this._isFirstShow) {
      this._isFirstShow = false;
      return;
    }
    if (!this._locationDenied) {
      this.startHighAccuracyLocation(false, false);
    }
  },

  onHide() {
    this.flushInputBuffers();
    // 离页时立即卸载 <camera> 并停止罗盘与定位轮询，释放 100MB+ 显存
    this.setData({ pageActive: false });
    this.stopLocationPolling();
    this.stopCompassTracking();
  },

  onUnload() {
    this.flushInputBuffers();
    this.clearAllInputTimers();
    this.stopLocationPolling();
    this.stopCompassTracking();
    if (this._countdownTimer) clearInterval(this._countdownTimer);
    if (this._bootFallbackTimer) clearTimeout(this._bootFallbackTimer);
  },

  clearAllInputTimers() {
    if (!this._inputTimers) return;
    Object.keys(this._inputTimers).forEach(k => {
      if (this._inputTimers[k]) {
        clearTimeout(this._inputTimers[k]);
        this._inputTimers[k] = null;
      }
    });
  },

  /**
   * 立即刷入所有未防抖的输入框缓冲值，杜绝点击保存/拍照时丢失最后 120ms 文本
   */
  flushInputBuffers() {
    this.clearAllInputTimers();
    if (!this._inputBuffers) return;
    const keys = Object.keys(this._inputBuffers);
    if (keys.length === 0) return;
    const patch = {};
    keys.forEach(k => {
      patch[k] = this._inputBuffers[k];
    });
    this._inputBuffers = {};
    if (patch.shortPoi !== undefined || patch.fullAddr !== undefined) {
      patch.hasCustomPoi = true;
    }
    this.setData(patch);
  },

  onInterceptBackGesture() {
    if (this.data.showVerifyModal) {
      this.closeVerifyModal();
    } else if (this.data.showWmModal) {
      this.closeWatermarkModal();
    } else if (this.data.showPoiModal) {
      this.closePoiQuickModal();
    } else if (this.data.showSettings) {
      this.closeSettingsModal();
    }
  },

  buildMergedStartupPatch(statusBarHeight, menuBtnTop, menuBtnHeight, menuBtnRightSpace) {
    const patch = {
      statusBarHeight,
      menuBtnTop,
      menuBtnHeight,
      menuBtnRightSpace
    };
    const savedCfg = loadUserConfig();
    if (savedCfg && typeof savedCfg === 'object') {
      const idx = Number(savedCfg.accentColorIndex) || 0;
      const alphaIdx = savedCfg.bgAlphaMode !== undefined ? Number(savedCfg.bgAlphaMode) : 1;
      patch.captureMode = savedCfg.captureMode || 'photo';
      patch.styleTemplate = savedCfg.styleTemplate !== undefined ? Number(savedCfg.styleTemplate) : 0;
      patch.showBottomScrim = Boolean(savedCfg.showBottomScrim);
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
    return patch;
  },

  refreshPhotoSummary() {
    const list = loadPhotos();
    const first = list[0] || null;
    const latestThumb = first ? first.thumbPath || first.wmPath || '' : '';
    if (list.length !== this.data.photoCount || latestThumb !== this.data.latestPhotoThumb) {
      this.setData({
        photoCount: list.length,
        latestPhotoThumb: latestThumb
      });
    }
  },

  persistUserConfig() {
    saveUserConfig({
      captureMode: this.data.captureMode,
      styleTemplate: this.data.styleTemplate,
      showBottomScrim: this.data.showBottomScrim,
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
  },

  /**
   * 导出当前相机水印配置快照，供批量加水印分包与相册分包读取
   */
  syncLiveConfigToGlobal() {
    this.flushInputBuffers();
    const app = getApp();
    if (!app || !app.globalData) return;
    app.globalData.liveConfigSnapshot = {
      styleTemplate: this.data.styleTemplate,
      showBottomScrim: this.data.showBottomScrim,
      customTitle: this.data.customTitle,
      coordSys: this.data.coordSys,
      useDms: this.data.useDms,
      accentColorIndex: this.data.accentColorIndex,
      accentHex: this.data.accentHex,
      fontScale: this.data.fontScale,
      bgAlphaMode: this.data.bgAlphaMode,
      cardBgRgba: this.data.cardBgRgba,
      gcjLat: this.data.gcjLat,
      gcjLon: this.data.gcjLon,
      wgsLat: this.data.wgsLat,
      wgsLon: this.data.wgsLon,
      accuracy: this.data.accuracy,
      altitude: this.data.altitude,
      azimuthDeg: this.data.azimuthDeg,
      azimuthText: this.data.azimuthText,
      shortPoi: this.data.shortPoi || '实拍点位',
      fullAddr: this.data.fullAddr || '',
      note: this.data.note || '',
      showQr: this.data.showQr,
      showMiniMap: this.data.showMiniMap,
      miniMapPosition: this.data.miniMapPosition,
      bottomRight: this.data.bottomRight,
      showTime: this.data.showTime,
      showLoc: this.data.showLoc,
      showCoords: this.data.showCoords,
      showCoordTag: this.data.showCoordTag,
      showAccuracy: this.data.showAccuracy,
      showAddress: this.data.showAddress,
      showNote: this.data.showNote,
      showEnv: this.data.showEnv,
      ntpOffsetMs: this.data.ntpOffsetMs,
      ntpSynced: this.data.ntpSynced
    };
  },

  /**
   * 线上授时兼容修复：
   * - 若 app.globalData.ntpConfig.endpoint 配置了自有合法域名授时端点，则发 HEAD 获取权威 Date 头
   * - 否则优雅降级为本地单调时钟校准，绝不硬连正式版会被拦截的 uri.amap.com
   */
  syncTrustedNetworkTime() {
    const app = getApp();
    const endpoint =
      app && app.globalData && app.globalData.ntpConfig && app.globalData.ntpConfig.endpoint
        ? String(app.globalData.ntpConfig.endpoint).trim()
        : '';

    const applyLocalMonotonicFallback = reasonLabel => {
      try {
        if (typeof wx.getPerformance === 'function') {
          const perf = wx.getPerformance();
          if (perf && typeof perf.now === 'function') {
            this._monoAnchorPerf = perf.now();
            this._monoAnchorWall = Date.now();
          }
        }
      } catch (e) {}
      this.setData({
        ntpSynced: false,
        ntpOffsetMs: 0,
        ntpStatusText: reasonLabel || '本地单调时钟校准'
      });
    };

    if (!endpoint || endpoint.indexOf('https://') !== 0) {
      applyLocalMonotonicFallback('本地单调时钟校准（未配置自有授时域名）');
      return;
    }

    const t0 = Date.now();
    wx.request({
      url: endpoint,
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
              ntpOffsetMs: offset,
              ntpStatusText: `权威授时已同步 (${offset >= 0 ? '+' : ''}${offset}ms)`
            });
            return;
          }
        }
        applyLocalMonotonicFallback('本地单调时钟校准（授时响应无 Date 头）');
      },
      fail: () => {
        applyLocalMonotonicFallback('本地单调时钟校准（授时端点暂不可达）');
      }
    });
  },

  startCompassTracking() {
    try {
      if (!this._compassBound) {
        this._compassBound = true;
        wx.onCompassChange(res => {
          if (!this.data.pageActive) return;
          const now = Date.now();
          // 250ms 节流，降低高频罗盘回调引发的通信开销
          if (now - this._lastCompassTs < 250) return;
          this._lastCompassTs = now;
          const deg = Math.round(res.direction || 0);
          if (Math.abs(deg - this.data.azimuthDeg) >= 5) {
            this.setData({
              azimuthDeg: deg,
              azimuthText: formatAzimuthText(deg)
            });
          }
        });
      }
      wx.startCompass();
    } catch (e) {}
  },

  stopCompassTracking() {
    try {
      wx.stopCompass();
    } catch (e) {}
  },

  startContinuousLocationTracking() {
    this.stopLocationPolling();
    if (this._locationDenied) return;
    this.locRefreshTimer = setInterval(() => {
      if (!this._locationDenied && this.data.pageActive && !this.data.isManualCoord) {
        this.startHighAccuracyLocation(false, false);
      }
    }, 15000);
  },

  stopLocationPolling() {
    if (this.locRefreshTimer) {
      clearInterval(this.locRefreshTimer);
      this.locRefreshTimer = null;
    }
  },

  consumeLaunchQuery(query) {
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
      if (!isNaN(gcjLat) && !isNaN(gcjLon)) {
        parsed = {
          gcjLat,
          gcjLon,
          shortPoi,
          timestamp: ts,
          signature: sig,
          note,
          hasAntiTamper: Boolean(sig),
          verified: sig ? verifyAntiTamperHash(gcjLat, gcjLon, ts, shortPoi, sig, note) : false
        };
      }
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

  // ==================== 独立分包路由入口 ====================
  openAlbumGalleryPage() {
    this.syncLiveConfigToGlobal();
    wx.navigateTo({
      url: '/packageAlbum/pages/gallery/gallery'
    });
  },

  openBatchWatermarkPage() {
    this.syncLiveConfigToGlobal();
    wx.navigateTo({
      url: '/packageBatch/pages/batch/batch'
    });
  },

  // ==================== 识图验真（彻底修复假阳性漏洞） ====================
  openVerifyEntrySheet() {
    this.setData({
      showPoiModal: false,
      showSettings: false,
      showWmModal: false,
      showVerifyModal: true
    });
  },

  closeVerifyModal() {
    this.setData({ showVerifyModal: false });
  },

  verifyByLiveCameraScan() {
    wx.scanCode({
      scanType: ['qrCode'],
      onlyFromCamera: false,
      success: async res => {
        const parsed = parseLocationPayload(res.result);
        const report = buildVerificationReport(parsed, '', '镜头扫码核验');
        if (report) {
          const sec = await checkTextsSecurity([report.shortPoi, parsed && parsed.note], 3);
          if (!sec.pass) {
            showViolationModal(sec.message);
            return;
          }
          this.setData({
            showVerifyModal: true,
            verifyReport: report,
            sharedBannerVisible: true,
            sharedPoint: parsed
          });
        } else {
          wx.showToast({ title: '未检测到有效防伪定位码', icon: 'none' });
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
        wx.showLoading({ title: '内容安全检测中...' });

        this.getFallbackCanvasNode(async fallbackCanvas => {
          try {
            const sec = await checkImageSecurity(path, fallbackCanvas);
            if (!sec.pass) {
              wx.hideLoading();
              showViolationModal(sec.message);
              return;
            }
            wx.showLoading({ title: '1:1双角识码验真中...' });
            const result = await verifyImageFile(path, fallbackCanvas);
            wx.hideLoading();
            if (result && result.ok && result.report) {
              this.setData({
                showVerifyModal: true,
                verifyReport: result.report
              });
            } else {
              wx.showToast({
                title: (result && result.reason) || '未检测到有效防伪定位码',
                icon: 'none',
                duration: 2400
              });
            }
          } catch (err) {
            wx.hideLoading();
            wx.showToast({ title: '未检测到有效防伪定位码', icon: 'none' });
          }
        });
      }
    });
  },

  onInputManualVerify(e) {
    this.setData({ manualVerifyInput: e.detail.value });
  },

  async runManualTextVerify() {
    const raw = (this.data.manualVerifyInput || '').trim();
    if (!raw) {
      wx.showToast({ title: '请粘贴链接或二维码文本', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '内容安全检测中...' });
    const sec = await checkTextsSecurity([raw], 3);
    wx.hideLoading();
    if (!sec.pass) {
      if (sec.risky) {
        this.setData({ manualVerifyInput: '' });
      }
      showViolationModal(sec.message);
      return;
    }
    const parsed = parseLocationPayload(raw);
    const report = buildVerificationReport(parsed, '', '链接/文本解析核验');
    if (report) {
      this.setData({ verifyReport: report });
    } else {
      wx.showToast({ title: '未检测到有效防伪定位码或坐标', icon: 'none' });
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
    const text = formatVerifyReportText(this.data.verifyReport);
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  // ==================== 定位管理与拒绝后停轮询 ====================
  startHighAccuracyLocation(forceOverwriteManual, interactive) {
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      highAccuracyExpireTime: 3200,
      success: res => {
        if (this._locationDenied) {
          this._locationDenied = false;
          this.setData({ locationDenied: false });
          this.startContinuousLocationTracking();
        }
        this.recordHardwareGpsFix(
          res.latitude,
          res.longitude,
          res.accuracy,
          res.altitude,
          Boolean(forceOverwriteManual)
        );
      },
      fail: err => {
        const msg = (err && err.errMsg) || '';
        const isDenied =
          msg.indexOf('auth deny') !== -1 ||
          msg.indexOf('authorize') !== -1 ||
          msg.indexOf('denied') !== -1;
        if (isDenied) {
          // 定位权限被拒：立即停止 15 秒无意义轮询
          this._locationDenied = true;
          this.stopLocationPolling();
          this.setData({ locationDenied: true });
          if (interactive) {
            promptOpenSetting(
              'scope.userLocation',
              '需要位置信息权限',
              '定位权限已关闭，无法获取真实 GPS 经纬度。请在设置中开启「位置信息」权限。'
            ).then(granted => {
              if (granted) {
                this._locationDenied = false;
                this.setData({ locationDenied: false });
                this.startHighAccuracyLocation(true, false);
                this.startContinuousLocationTracking();
              }
            });
          }
          return;
        }
        const smart = inferSmartPoiFromCoords(this.data.gcjLat, this.data.gcjLon);
        this.setData({
          shortPoi: this.data.hasCustomPoi ? this.data.shortPoi : smart.shortPoi,
          fullAddr: this.data.hasCustomPoi ? this.data.fullAddr : smart.fullAddr,
          nearbyPois: smart.nearbyPois
        });
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
      locationDenied: false,
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
      if (!this.data.hasCustomPoi || this.data.antiTamperMode) {
        patch.shortPoi = smart.shortPoi;
        patch.fullAddr = smart.fullAddr;
      }
    }

    this.setData(patch);
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
        this.startHighAccuracyLocation(true, true);
        wx.showToast({ title: '已恢复手机真实GPS坐标', icon: 'none' });
      }
    );
  },

  refreshRealGpsNow() {
    this.setData({ hasCustomPoi: false, isManualCoord: false });
    this._locationDenied = false;
    this.startHighAccuracyLocation(true, true);
    wx.showToast({ title: '正在刷新真实GPS定位...', icon: 'none' });
  },

  choosePoiNameOnlyOnMap() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '🛡 防伪模式下点位名称已锁定，不可篡改', icon: 'none' });
      return;
    }
    const applyPoiName = (name, address) => {
      const pickedName = (name || address || '').trim();
      if (!pickedName) return;
      const nextCands = [pickedName, ...this.data.nearbyPois]
        .filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i)
        .slice(0, 6);
      this.setData({
        hasCustomPoi: true,
        shortPoi: pickedName,
        fullAddr: address || this.data.fullAddr,
        nearbyPois: nextCands
      });
      wx.showToast({ title: '🏷 已选用地名(GPS坐标未变)', icon: 'none' });
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
      wx.showToast({ title: '🛡 防伪模式下坐标与点位均已锁定', icon: 'none' });
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
        });
        wx.showToast({ title: '📌 已迁移至地图选点坐标', icon: 'none' });
      },
      fail: err => this.handleChooseLocationFail(err)
    });
  },

  handleChooseLocationFail(err) {
    if (err && err.errMsg && err.errMsg.indexOf('auth deny') !== -1) {
      promptOpenSetting(
        'scope.userLocation',
        '需要位置权限',
        '请在设置中开启位置信息权限以打开地图选点。'
      );
    }
  },

  // ==================== 相机控制与样式切换 ====================
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

  selectTimerSeconds(e) {
    const sec = Number(e.currentTarget.dataset.sec) || 0;
    this.setData({ timerSeconds: sec });
  },

  cycleCameraZoom() {
    const zooms = [1.0, 1.5, 2.0, 3.0];
    const curIdx = zooms.indexOf(this.data.zoomRatio);
    const nextZoom = zooms[(curIdx + 1) % zooms.length];
    try {
      const camCtx = wx.createCameraContext();
      if (camCtx && typeof camCtx.setZoom === 'function') {
        camCtx.setZoom({
          zoom: nextZoom,
          success: () => {
            this.setData({ zoomRatio: nextZoom, zoomRatioStr: nextZoom.toFixed(1) + 'X' });
          },
          fail: () => {
            // 修复：setZoom 失败时绝不假更新倍率
            wx.showToast({ title: '当前镜头不支持该变焦倍率', icon: 'none' });
          }
        });
        return;
      }
    } catch (e) {}
    wx.showToast({ title: '当前设备暂不支持变焦调节', icon: 'none' });
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
      devicePosition: this.data.devicePosition === 'back' ? 'front' : 'back',
      zoomRatio: 1.0,
      zoomRatioStr: '1.0X'
    });
  },

  selectTemplate(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    if (wx.vibrateShort) {
      try {
        wx.vibrateShort({ type: 'light' });
      } catch (err) {}
    }
    this.setData({ styleTemplate: idx }, () => this.persistUserConfig());
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
      this.persistUserConfig();
      wx.showToast({ title: '⚡ 已应用预设方案', icon: 'none' });
    });
  },

  selectCoordSystem(e) {
    const sys = e.currentTarget.dataset.sys;
    if (!sys) return;
    this.setData({ coordSys: sys }, () => {
      this.persistUserConfig();
      wx.showToast({ title: `水印坐标：${sys} (高德跳转零偏差)`, icon: 'none' });
    });
  },

  setDmsFormat(e) {
    const useDms = e.currentTarget.dataset.dms === '1';
    this.setData({ useDms }, () => this.persistUserConfig());
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

  selectMiniMapPos(e) {
    const pos = Number(e.currentTarget.dataset.pos) || 0;
    this.setData({ miniMapPosition: pos }, () => this.persistUserConfig());
  },

  toggleWatermarkField(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    const nextVal = !this.data[field];
    this.setData({ [field]: nextVal }, () => this.persistUserConfig());
  },

  selectQuickPoi(e) {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '🛡 防伪模式下点位名称已锁定', icon: 'none' });
      return;
    }
    const poi = e.currentTarget.dataset.poi;
    if (poi) {
      this.setData({ shortPoi: poi, hasCustomPoi: true });
    }
  },

  selectQuickTitle(e) {
    const title = e.currentTarget.dataset.title;
    if (title) {
      this.setData({ customTitle: title }, () => this.persistUserConfig());
    }
  },

  appendQuickNote(e) {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '🛡 防伪模式下备注已锁定，不可篡改', icon: 'none' });
      return;
    }
    const tag = e.currentTarget.dataset.note;
    if (!tag) return;
    const next = this.data.note ? `${this.data.note} · ${tag}` : tag;
    this.setData({ note: next, showNote: true }, () => this.persistUserConfig());
  },

  clearNoteInput() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '🛡 防伪模式下备注已锁定', icon: 'none' });
      return;
    }
    this.setData({ note: '' }, () => this.persistUserConfig());
  },

  // ==================== 快门拍摄与离屏双轨保存 ====================
  async validateLiveTextsAndGuard(revertOnAnyFailure) {
    this.flushInputBuffers();
    const res = await checkTextsSecurity(
      [this.data.customTitle, this.data.shortPoi, this.data.fullAddr, this.data.note],
      3
    );
    if (res && res.pass) {
      this._safeTextSnapshot = {
        customTitle: this.data.customTitle,
        shortPoi: this.data.shortPoi,
        fullAddr: this.data.fullAddr,
        note: this.data.note
      };
      return true;
    }
    if ((revertOnAnyFailure || (res && res.risky)) && this._safeTextSnapshot) {
      this.setData(this._safeTextSnapshot);
    }
    showViolationModal(res && res.message);
    return false;
  },

  async handleShutterTap() {
    if (this.data.countdownRemaining > 0) {
      if (this._countdownTimer) clearInterval(this._countdownTimer);
      this._countdownTimer = null;
      this.setData({ countdownRemaining: 0 });
      wx.showToast({ title: '已取消延时拍摄', icon: 'none' });
      return;
    }
    if (this.isCompositing || this._checkingBeforeShutter) return;

    this.flushInputBuffers();

    // 防伪模式安全门禁：未获得真实定位（或仍为默认天安门占位坐标）严禁签发防伪哈希
    if (this.data.antiTamperMode && !canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon)) {
      wx.showModal({
        title: '🛡 防伪模式等待真实 GPS',
        content: '当前尚未获取到手机真实 GPS 定位，防伪模式严禁使用默认坐标签发防伪码。请前往室外空旷处或先刷新定位后再拍摄。',
        confirmText: '刷新定位',
        cancelText: '暂不拍摄',
        success: r => {
          if (r.confirm) {
            this.refreshRealGpsNow();
          }
        }
      });
      return;
    }

    this._checkingBeforeShutter = true;
    let safeOk = false;
    try {
      safeOk = await this.validateLiveTextsAndGuard();
    } finally {
      this._checkingBeforeShutter = false;
    }
    if (!safeOk) return;

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
        const canSign = this.data.antiTamperMode && canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon);
        const activeGcjLat = canSign ? this.data.rawGcjLat : this.data.gcjLat;
        const activeGcjLon = canSign ? this.data.rawGcjLon : this.data.gcjLon;
        const activeWgsLat = canSign ? this.data.rawWgsLat : this.data.wgsLat;
        const activeWgsLon = canSign ? this.data.rawWgsLon : this.data.wgsLon;
        const cleanPoi = this.data.shortPoi || '实拍点位';
        const antiHash = canSign
          ? computeAntiTamperHash(activeGcjLat, activeGcjLon, t.fullTs, cleanPoi, this.data.note)
          : '';

        const snapshotCfg = {
          styleTemplate: this.data.styleTemplate,
          showBottomScrim: this.data.showBottomScrim,
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
          antiTamperMode: canSign,
          ntpSynced: this.data.ntpSynced,
          antiTamperHash: antiHash,
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

        this.compositeAndPersistPhoto(res.tempImagePath, snapshotCfg);
      },
      fail: () => {
        promptOpenSetting(
          'scope.camera',
          '无法调用相机拍照',
          '请确认已授予小程序摄像头权限，或点击下方去设置开启。'
        );
      }
    });
  },

  getFallbackCanvasNode(cb) {
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

  compositeAndPersistPhoto(rawImagePath, cfg) {
    this.isCompositing = true;
    wx.showLoading({ title: '合成高清水印...' });

    this.getFallbackCanvasNode(fallbackCanvas => {
      renderWatermarkToTempFile(rawImagePath, cfg, {
        fallbackCanvas,
        generateThumb: true
      })
        .then(async out => {
          const savedRawPath = await copyTempToUserData(rawImagePath, 'amap_raw');
          const savedWmPath = await copyTempToUserData(out.tempFilePath, 'amap_wm');
          const savedThumbPath = out.thumbTempFilePath
            ? await copyTempToUserData(out.thumbTempFilePath, 'amap_thumb')
            : savedWmPath;

          const entry = {
            id: 'wx_' + Date.now(),
            createdAt: Date.now(),
            rawPath: savedRawPath,
            wmPath: savedWmPath,
            thumbPath: savedThumbPath,
            cfg
          };

          const savedList = addPhotoEntry(entry);
          this.isCompositing = false;
          wx.hideLoading();

          this.setData({
            photoCount: savedList.length,
            latestPhotoThumb: savedThumbPath
          });

          if (this.data.autoSaveAlbum) {
            saveImageToAlbumWithPermission(
              savedWmPath,
              `已存相册(${cfg.coordSys}水印·高德零偏差)`,
              false
            ).catch(() => {
              wx.showToast({ title: '已存入应用内相册', icon: 'none' });
            });
          } else {
            wx.showToast({ title: '已存入应用内相册', icon: 'none' });
          }
        })
        .catch(err => {
          this.isCompositing = false;
          wx.hideLoading();
          wx.showToast({
            title: (err && err.message) || '水印合成导出失败，请重试',
            icon: 'none',
            duration: 2200
          });
        });
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

  // ==================== 弹窗开关（互斥防嵌套冲突） ====================
  openPoiQuickModal() {
    this.setData({
      showSettings: false,
      showWmModal: false,
      showVerifyModal: false,
      showPoiModal: true
    });
  },
  async closePoiQuickModal() {
    const ok = await this.validateLiveTextsAndGuard(true);
    if (ok) this.persistUserConfig();
    this.setData({ showPoiModal: false });
  },
  openSettingsModal() {
    this.setData({
      showPoiModal: false,
      showWmModal: false,
      showVerifyModal: false,
      showSettings: true
    });
  },
  closeSettingsModal() {
    this.setData({ showSettings: false });
  },
  openWatermarkModal() {
    this.setData({
      showPoiModal: false,
      showSettings: false,
      showVerifyModal: false,
      showWmModal: true
    });
  },
  async closeWatermarkModal() {
    const ok = await this.validateLiveTextsAndGuard(true);
    if (ok) this.persistUserConfig();
    this.setData({ showWmModal: false });
  },
  async applyWatermarkCustomization() {
    wx.showLoading({ title: '内容安全检测中...' });
    const ok = await this.validateLiveTextsAndGuard(false);
    wx.hideLoading();
    if (!ok) return;
    this.persistUserConfig();
    this.setData({ showWmModal: false });
    wx.showToast({ title: '水印设置已实时生效', icon: 'none' });
  },

  // ==================== 开关回调 ====================
  onToggleGridLines(e) {
    this.setData({ showGridLines: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleShowAddress(e) {
    this.setData({ showAddress: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleBottomScrim(e) {
    this.setData({ showBottomScrim: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleAutoSave(e) {
    const enabled = e.detail.value;
    this.setData({ autoSaveAlbum: enabled }, () => {
      this.persistUserConfig();
      if (enabled) preRequestAlbumPermissionSilently();
    });
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
        this.startHighAccuracyLocation(true, true);
        wx.showToast({
          title: hadManualOffset
            ? '🛡 已开启防伪：已强制回滚为真实GPS并锁定点位与备注'
            : '🛡 已开启防伪：真实GPS、点位与备注已锁定',
          icon: 'none'
        });
      }
      this.persistUserConfig();
    });
  },

  // ==================== 防抖输入 + 退出前刷入缓冲 ====================
  _scheduleBufferedInput(field, val) {
    this._inputBuffers[field] = val;
    if (this._inputTimers[field]) clearTimeout(this._inputTimers[field]);
    this._inputTimers[field] = setTimeout(() => {
      const latest = this._inputBuffers[field];
      delete this._inputBuffers[field];
      this._inputTimers[field] = null;
      if (latest === undefined) return;
      const patch = { [field]: latest };
      if (field === 'shortPoi' || field === 'fullAddr') patch.hasCustomPoi = true;
      this.setData(patch);
    }, 120);
  },

  onInputTitle(e) {
    this._scheduleBufferedInput('customTitle', e.detail.value);
  },
  onInputPoi(e) {
    if (this.data.antiTamperMode) return;
    this._scheduleBufferedInput('shortPoi', e.detail.value);
  },
  onInputFullAddr(e) {
    this._scheduleBufferedInput('fullAddr', e.detail.value);
  },
  onInputNote(e) {
    if (this.data.antiTamperMode) return;
    this._scheduleBufferedInput('note', e.detail.value);
  },

  onCameraError() {
    promptOpenSetting(
      'scope.camera',
      '需要开启摄像头权限',
      '极速高德水印相机需要使用摄像头进行实景取景与拍摄，请在设置中开启「摄像头」权限。'
    );
  },

  noop() {},

  /**
   * 转发分享修复：取景页仅分享当前实时位置，绝不误发旧照片
   */
  onShareAppMessage() {
    this.flushInputBuffers();
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const canSign =
      this.data.antiTamperMode &&
      canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon);
    const lat = canSign ? this.data.rawGcjLat : this.data.gcjLat;
    const lon = canSign ? this.data.rawGcjLon : this.data.gcjLon;
    const rawPoi = this.data.shortPoi || '实拍点位';
    const poi = encodeURIComponent(rawPoi);
    const ts = encodeURIComponent(t.fullTs);
    const note = encodeURIComponent(this.data.note || '');
    const sig = canSign
      ? computeAntiTamperHash(lat, lon, t.fullTs, rawPoi, this.data.note || '').slice(0, 12)
      : '';
    const sigParam = sig ? `&sig=${sig}&note=${note}` : '';
    return {
      title: `📍 实时定位：${rawPoi} (${lat}°N, ${lon}°E)`,
      path: `/pages/index/index?act=jump&lat=${lat}&lon=${lon}&poi=${poi}&ts=${ts}${sigParam}`
    };
  }
});
