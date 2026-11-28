/**
 * 极速高德水印相机 v2.0 · 主取景页控制器
 * - 清爽日间 UI + 双列可视化水印选择 + 开关清单编辑与单页下钻
 * - 支持「无水印原图拍摄」（保留 rawPath，随时在相册详情补加水印）
 * - 支持各模板独立记忆配色/字号/透明度，并可收藏自定义方案至「我的常用」
 * - 快门零云函数阻塞（纯离线秒拍 + 后台异步队列），文本编辑按规范接入云安全校验
 */

const {
  gcj02ToWgs84,
  canSignAntiTamper,
  formatAzimuthText,
  resolveDisplayCoordinates,
  formatCoords,
  getTimeStrings,
  computeAntiTamperHash,
  verifyAntiTamperHash,
  parseLocationPayload
} = require('../../utils/coord_qr.js');

const {
  FALLBACK_QUICK_POIS,
  resolveNearbyPoi,
  syncCloudServerTime
} = require('../../utils/poi_service.js');

const {
  getOfflinePendingCount,
  enqueueOfflinePhoto,
  reconcileOfflineQueue,
  initOfflineNetworkListener
} = require('../../utils/offline_queue.js');

const { checkTextsSecurity, showViolationModal } = require('../../utils/sec_check.js');

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

const TEMPLATE_CATALOG = [
  { id: 0, name: '大字时间', category: 'daily', tag: '通用', keywords: '大字时间打卡极简天气日常' },
  { id: 1, name: '巡检卡片', category: 'work', tag: '推荐', keywords: '工程巡检卡片现场外业记录' },
  { id: 2, name: '网格测绘', category: 'gis', tag: '测绘', keywords: '经纬网格测绘坐标定桩GIS' },
  { id: 3, name: '胶片底栏', category: 'daily', tag: '极简', keywords: '极简胶片底栏风景通透打卡' },
  { id: 4, name: '标准台账', category: 'work', tag: '台账', keywords: '外业标准台账清单验收记录' }
];

const WM_FIELD_TITLES = {
  time: '真实时间与授时',
  poi: '地点简称与位置',
  coords: '经纬度与坐标系',
  address: '详细地址',
  note: '现场备注',
  env: '海拔与方位',
  qr: '高德定位二维码',
  style: '卡片标题与样式'
};

function getFontScaleLabel(scale) {
  const n = Number(scale);
  if (n <= 0.9) return '小字号';
  if (n >= 1.1) return '大字号';
  return '标准';
}

function getDefaultTemplateStyle() {
  return {
    accentColorIndex: 0,
    fontScale: 1,
    bgAlphaMode: 1,
    bottomRight: false,
    showBottomScrim: false
  };
}

Page({
  data: {
    statusBarHeight: 44,
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    pageActive: true,
    cameraReady: false,
    devicePosition: 'back',
    flashMode: 'off',
    flashLabel: '关',
    timerSeconds: 0,
    countdownRemaining: 0,
    shutterFlashVisible: false,
    zoomRatio: 1.0,
    zoomSteps: [
      { val: 1.0, label: '1x' },
      { val: 1.5, label: '1.5x' },
      { val: 2.0, label: '2x' },
      { val: 3.0, label: '3x' }
    ],
    showGridLines: false,
    focusRingVisible: false,
    focusX: 0,
    focusY: 0,
    templateToastText: '',
    styleTemplate: 0,
    noWatermark: false,
    showBottomScrim: false,
    tplNames: ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'],
    tplCards: TEMPLATE_CATALOG,
    filteredTplCards: TEMPLATE_CATALOG,
    customPresets: [],
    filteredPresets: [],
    activePresetId: '',
    templateStyles: {},
    quickTitleTags: ['现场点位核验', '工程巡检记录', '隐蔽工程验收', '外业测绘定桩', '安全隐患排查'],
    quickNoteTags: ['核验合格', '定位已校准', '施工进行中', '待整改复核', '初检通过'],
    accentColors: ACCENT_PALETTE,
    accentColorIndex: 0,
    accentHex: '#38bdf8',
    accentName: '天际蓝',
    fontScale: 1,
    fontScaleLabel: '标准',
    bgAlphaMode: 1,
    cardBgRgba: BG_ALPHA_PRESETS[1],
    customTitle: '现场点位核验',
    coordSys: 'WGS-84',
    useDms: false,
    hasRealFix: false,
    locationDenied: false,
    hasCustomPoi: false,
    poiResolved: false,
    poiLoading: false,
    poiStatusHint: '',
    isManualCoord: false,
    rawGcjLat: 0,
    rawGcjLon: 0,
    rawWgsLat: 0,
    rawWgsLon: 0,
    rawAccuracy: '',
    gcjLat: 0,
    gcjLon: 0,
    wgsLat: 0,
    wgsLon: 0,
    accuracy: '',
    altitude: '',
    azimuthDeg: 0,
    azimuthText: '',
    shortPoi: '定位中...',
    fullAddr: '',
    nearbyPois: FALLBACK_QUICK_POIS,
    nearbyPoiDetails: [],
    note: '',
    showQr: true,
    bottomRight: false,
    antiTamperMode: false,
    ntpSynced: false,
    ntpOffsetMs: 0,
    ntpStatusText: '待授时校准',
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
    pendingComposeCount: 0,
    offlinePendingCount: 0,
    isReconcilingOffline: false,
    wmStep: 'picker', // 'picker' | 'editor' | 'field'
    wmPickerTab: 'all', // 'all' | 'presets'
    wmSearchOpen: false,
    wmSearchQuery: '',
    wmCategory: 'all',
    wmEditField: 'poi',
    wmFieldTitle: '地点简称与位置',
    wmOverlayLiftPx: 180,
    liveTimeHm: '--:--',
    liveDateStr: '',
    liveCoordShort: '定位校准中...',
    livePreviewTime: '',
    livePreviewCoord: '定位校准中...',
    livePreviewEnv: '开启后显示实时海拔与方位角',
    showSettings: false,
    showWmModal: false,
    showPoiModal: false,
    sharedBannerVisible: false,
    sharedPoint: null
  },

  onLoad(query) {
    this._isFirstShow = true;
    this._deferredDone = false;
    this._locationDenied = false;
    this._compassBound = false;
    this._compassRunning = false;
    this._lastCompassTs = 0;
    this._inputBuffers = {};
    this._inputTimers = {};
    this._dirtyTextFields = new Set();
    this._captureQueue = [];
    this._drainingQueue = false;
    this._touchStartX = 0;
    this._touchStartY = 0;
    this._poiReqSeq = 0;

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
    mergedInit.offlinePendingCount = getOfflinePendingCount();
    this.setData(mergedInit);

    this.consumeLaunchQuery(query);

    ensurePrivacyAuthorized().then(ok => {
      if (ok) {
        this.startHighAccuracyLocation(false, false);
      }
    });

    initOfflineNetworkListener(() => {
      if (this.data.pageActive) {
        this.triggerOfflineReconcile(true);
      }
    });

    this._bootFallbackTimer = setTimeout(() => {
      if (!this.data.cameraReady) {
        this.setData({ cameraReady: true });
      }
      this.runDeferredStartupTasks();
    }, 650);
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
    if (this.data.showEnv) {
      this.startCompassTracking();
    }
    this.startContinuousLocationTracking();
    if (this.data.autoSaveAlbum) {
      preRequestAlbumPermissionSilently();
    }
    if (getOfflinePendingCount() > 0) {
      this.triggerOfflineReconcile(true);
    }
  },

  onShow() {
    if (!this.data.pageActive) {
      this.setData({ pageActive: true });
    }

    const app = getApp();
    if (app && app.globalData && app.globalData.launchQuery) {
      const q = app.globalData.launchQuery;
      app.globalData.launchQuery = null;
      this.consumeLaunchQuery(q);
    }

    this.refreshPhotoSummary();

    if (this._deferredDone) {
      if (this.data.showEnv) {
        this.startCompassTracking();
      }
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
    if (this._focusTimer) clearTimeout(this._focusTimer);
    if (this._tplToastTimer) clearTimeout(this._tplToastTimer);
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
    this.setData(patch, () => this.syncDrawerPreviewStrings());
  },

  onInterceptBackGesture() {
    if (this.data.showWmModal) {
      this.closeWatermarkModal();
    } else if (this.data.showPoiModal) {
      this.closePoiQuickModal();
    } else if (this.data.showSettings) {
      this.closeSettingsModal();
    }
  },

  normalizeTemplateStyles(rawMap) {
    const out = {};
    for (let i = 0; i < TEMPLATE_CATALOG.length; i++) {
      const k = String(i);
      const src = rawMap && rawMap[k] && typeof rawMap[k] === 'object' ? rawMap[k] : {};
      const def = getDefaultTemplateStyle();
      out[k] = {
        accentColorIndex: src.accentColorIndex !== undefined ? Number(src.accentColorIndex) : def.accentColorIndex,
        fontScale: Number(src.fontScale) || def.fontScale,
        bgAlphaMode: src.bgAlphaMode !== undefined ? Number(src.bgAlphaMode) : def.bgAlphaMode,
        bottomRight: src.bottomRight !== undefined ? Boolean(src.bottomRight) : def.bottomRight,
        showBottomScrim: src.showBottomScrim !== undefined ? Boolean(src.showBottomScrim) : def.showBottomScrim
      };
    }
    return out;
  },

  filterTplCardsList(category, query) {
    const cat = category || 'all';
    const q = String(query || '').trim().toLowerCase();
    return TEMPLATE_CATALOG.filter(item => {
      if (cat !== 'all' && item.category !== cat) return false;
      if (!q) return true;
      return (
        item.name.toLowerCase().indexOf(q) !== -1 ||
        item.tag.toLowerCase().indexOf(q) !== -1 ||
        item.keywords.toLowerCase().indexOf(q) !== -1
      );
    });
  },

  filterCustomPresetsList(presets, query) {
    const list = Array.isArray(presets) ? presets : [];
    const q = String(query || '').trim().toLowerCase();
    if (!q) return list;
    return list.filter(p => {
      const text = `${p.name || ''} ${p.customTitle || ''} ${p.note || ''} ${p.coordSys || ''}`.toLowerCase();
      return text.indexOf(q) !== -1;
    });
  },

  syncDrawerPreviewStrings() {
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const hasCoord = this.data.hasRealFix || this.data.isManualCoord;
    let coordShort = '定位校准中...';
    let coordFull = '定位校准中...';
    if (hasCoord) {
      const res = resolveDisplayCoordinates(
        this.data.coordSys,
        this.data.gcjLat,
        this.data.gcjLon,
        this.data.wgsLat,
        this.data.wgsLon
      );
      coordShort = formatCoords(res.lat, res.lon, false);
      const formatted = formatCoords(res.lat, res.lon, this.data.useDms);
      const acc = this.data.showAccuracy && this.data.accuracy ? ` ±${this.data.accuracy}m` : '';
      coordFull = this.data.showCoordTag ? `${formatted} (${res.tag})${acc}` : `${formatted}${acc}`;
    }
    const envParts = [];
    if (this.data.altitude) envParts.push(`海拔 ${this.data.altitude}m`);
    if (this.data.azimuthText) envParts.push(this.data.azimuthText);
    const livePreviewEnv = envParts.length > 0
      ? envParts.join(' · ')
      : (this.data.showEnv ? '待获取传感器数据' : '开启后显示实时海拔与方位');

    this.setData({
      liveTimeHm: t.hm,
      liveDateStr: `${t.datePart} ${t.weekStr}`,
      liveCoordShort: coordShort,
      livePreviewTime: `${t.fullTs} ${t.weekStr}`,
      livePreviewCoord: coordFull,
      livePreviewEnv
    });
  },

  buildMergedStartupPatch(statusBarHeight, menuBtnTop, menuBtnHeight, menuBtnRightSpace) {
    const t = getTimeStrings(0);
    const patch = {
      statusBarHeight,
      menuBtnTop,
      menuBtnHeight,
      menuBtnRightSpace,
      liveTimeHm: t.hm,
      liveDateStr: `${t.datePart} ${t.weekStr}`,
      livePreviewTime: `${t.fullTs} ${t.weekStr}`
    };
    const savedCfg = loadUserConfig();
    if (savedCfg && typeof savedCfg === 'object') {
      const tpl = savedCfg.styleTemplate !== undefined ? Number(savedCfg.styleTemplate) : 0;
      const normalizedStyles = this.normalizeTemplateStyles(savedCfg.templateStyles);
      if (savedCfg.accentColorIndex !== undefined && !savedCfg.templateStyles) {
        normalizedStyles[String(tpl)] = {
          accentColorIndex: Number(savedCfg.accentColorIndex) || 0,
          fontScale: Number(savedCfg.fontScale) || 1,
          bgAlphaMode: savedCfg.bgAlphaMode !== undefined ? Number(savedCfg.bgAlphaMode) : 1,
          bottomRight: Boolean(savedCfg.bottomRight),
          showBottomScrim: Boolean(savedCfg.showBottomScrim)
        };
      }
      const activeStyle = normalizedStyles[String(tpl)] || getDefaultTemplateStyle();
      const idx = savedCfg.accentColorIndex !== undefined ? Number(savedCfg.accentColorIndex) : activeStyle.accentColorIndex;
      const alphaIdx = savedCfg.bgAlphaMode !== undefined ? Number(savedCfg.bgAlphaMode) : activeStyle.bgAlphaMode;
      const fScale = Number(savedCfg.fontScale) || activeStyle.fontScale || 1;
      const pal = ACCENT_PALETTE[idx] || ACCENT_PALETTE[0];
      const presets = Array.isArray(savedCfg.customPresets) ? savedCfg.customPresets : [];

      patch.styleTemplate = tpl;
      patch.noWatermark = Boolean(savedCfg.noWatermark);
      patch.templateStyles = normalizedStyles;
      patch.customPresets = presets;
      patch.filteredPresets = presets;
      patch.activePresetId = savedCfg.activePresetId || '';
      patch.showBottomScrim = savedCfg.showBottomScrim !== undefined ? Boolean(savedCfg.showBottomScrim) : activeStyle.showBottomScrim;
      patch.coordSys = savedCfg.coordSys || 'WGS-84';
      patch.useDms = Boolean(savedCfg.useDms);
      patch.customTitle = savedCfg.customTitle || '现场点位核验';
      patch.note = savedCfg.note || '';
      patch.accentColorIndex = idx;
      patch.accentHex = pal.hex;
      patch.accentName = pal.name;
      patch.fontScale = fScale;
      patch.fontScaleLabel = getFontScaleLabel(fScale);
      patch.bgAlphaMode = alphaIdx;
      patch.cardBgRgba = BG_ALPHA_PRESETS[alphaIdx] || BG_ALPHA_PRESETS[1];
      patch.showQr = savedCfg.showQr !== false;
      patch.bottomRight = savedCfg.bottomRight !== undefined ? Boolean(savedCfg.bottomRight) : activeStyle.bottomRight;
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
    } else {
      patch.templateStyles = this.normalizeTemplateStyles(null);
    }
    return patch;
  },

  refreshPhotoSummary() {
    const list = loadPhotos();
    const first = list[0] || null;
    const latestThumb = first ? first.thumbPath || first.wmPath || '' : '';
    const offlinePendingCount = getOfflinePendingCount();
    if (
      list.length !== this.data.photoCount ||
      latestThumb !== this.data.latestPhotoThumb ||
      offlinePendingCount !== this.data.offlinePendingCount
    ) {
      this.setData({
        photoCount: list.length,
        latestPhotoThumb: latestThumb,
        offlinePendingCount
      });
    }
  },

  persistUserConfig() {
    const tplKey = String(this.data.styleTemplate);
    const nextStyles = {
      ...(this.data.templateStyles || {}),
      [tplKey]: {
        accentColorIndex: this.data.accentColorIndex,
        fontScale: this.data.fontScale,
        bgAlphaMode: this.data.bgAlphaMode,
        bottomRight: this.data.bottomRight,
        showBottomScrim: this.data.showBottomScrim
      }
    };
    saveUserConfig({
      styleTemplate: this.data.styleTemplate,
      noWatermark: this.data.noWatermark,
      templateStyles: nextStyles,
      customPresets: this.data.customPresets || [],
      activePresetId: this.data.activePresetId || '',
      showBottomScrim: this.data.showBottomScrim,
      coordSys: this.data.coordSys,
      useDms: this.data.useDms,
      customTitle: this.data.customTitle,
      note: this.data.note,
      accentColorIndex: this.data.accentColorIndex,
      fontScale: this.data.fontScale,
      bgAlphaMode: this.data.bgAlphaMode,
      showQr: this.data.showQr,
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

  syncLiveConfigToGlobal() {
    this.flushInputBuffers();
    const app = getApp();
    if (!app || !app.globalData) return;
    app.globalData.liveConfigSnapshot = {
      styleTemplate: this.data.styleTemplate,
      noWatermark: this.data.noWatermark,
      showBottomScrim: this.data.showBottomScrim,
      customTitle: this.data.customTitle,
      coordSys: this.data.coordSys,
      useDms: this.data.useDms,
      accentColorIndex: this.data.accentColorIndex,
      accentHex: this.data.accentHex,
      fontScale: this.data.fontScale,
      bgAlphaMode: this.data.bgAlphaMode,
      cardBgRgba: this.data.cardBgRgba,
      hasRealFix: this.data.hasRealFix,
      isManualCoord: this.data.isManualCoord,
      gcjLat: this.data.gcjLat,
      gcjLon: this.data.gcjLon,
      wgsLat: this.data.wgsLat,
      wgsLon: this.data.wgsLon,
      accuracy: this.data.accuracy,
      altitude: this.data.altitude,
      azimuthDeg: this.data.azimuthDeg,
      azimuthText: this.data.azimuthText,
      shortPoi: this.data.shortPoi === '定位中...' ? '实拍点位' : this.data.shortPoi || '实拍点位',
      fullAddr: this.data.fullAddr || '',
      note: this.data.note || '',
      showQr: this.data.showQr,
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
   * 权威授时同步：
   * 1. 若配置了自有 HTTPS endpoint 则发 HEAD 校准
   * 2. 否则自动通过云函数 regeoPoi 获取服务端权威时间（无需额外合法域名）
   * 3. 离线时优雅降级为本地时钟
   */
  async syncTrustedNetworkTime() {
    const app = getApp();
    const endpoint =
      app && app.globalData && app.globalData.ntpConfig && app.globalData.ntpConfig.endpoint
        ? String(app.globalData.ntpConfig.endpoint).trim()
        : '';

    if (endpoint && endpoint.indexOf('https://') === 0) {
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
          this._fallbackCloudServerTime();
        },
        fail: () => this._fallbackCloudServerTime()
      });
      return;
    }

    await this._fallbackCloudServerTime();
  },

  async _fallbackCloudServerTime() {
    const cloudTime = await syncCloudServerTime();
    if (cloudTime.ok) {
      this.setData({
        ntpSynced: true,
        ntpOffsetMs: cloudTime.offsetMs,
        ntpStatusText: `云端权威授时 (${cloudTime.offsetMs >= 0 ? '+' : ''}${cloudTime.offsetMs}ms)`
      });
    } else {
      this.setData({
        ntpSynced: false,
        ntpOffsetMs: 0,
        ntpStatusText: '离线本地时钟（联网自动补签）'
      });
    }
  },

  // ==================== 仅在需要方位角时按需启停罗盘 ====================
  startCompassTracking() {
    if (!this.data.showEnv || this._compassRunning) return;
    try {
      if (!this._compassBound) {
        this._compassBound = true;
        wx.onCompassChange(res => {
          if (!this.data.pageActive || !this.data.showEnv) return;
          const now = Date.now();
          if (now - this._lastCompassTs < 280) return;
          this._lastCompassTs = now;
          const deg = Math.round(res.direction || 0);
          if (Math.abs(deg - this.data.azimuthDeg) >= 5 || !this.data.azimuthText) {
            this.setData({
              azimuthDeg: deg,
              azimuthText: formatAzimuthText(deg)
            });
          }
        });
      }
      wx.startCompass();
      this._compassRunning = true;
    } catch (e) {}
  },

  stopCompassTracking() {
    if (!this._compassRunning) return;
    try {
      wx.stopCompass();
    } catch (e) {}
    this._compassRunning = false;
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
      const ntpSynced = query.ntp === '1';
      if (!isNaN(gcjLat) && !isNaN(gcjLon)) {
        parsed = {
          gcjLat,
          gcjLon,
          shortPoi,
          timestamp: ts,
          signature: sig,
          note,
          ntpSynced,
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
    this.setData({ showSettings: false });
    this.syncLiveConfigToGlobal();
    wx.navigateTo({
      url: '/packageBatch/pages/batch/batch'
    });
  },

  // ==================== 取景器手势：点击对焦 & 左右轻滑切换模板 ====================
  onViewfinderTouchStart(e) {
    const touch = e.touches && e.touches[0];
    if (!touch) return;
    this._touchStartX = touch.clientX;
    this._touchStartY = touch.clientY;
    this._touchStartTime = Date.now();
  },

  onViewfinderTouchEnd(e) {
    if (
      this.data.showPoiModal ||
      this.data.showWmModal ||
      this.data.showSettings ||
      this.data.sharedBannerVisible
    ) {
      return;
    }
    const touch = e.changedTouches && e.changedTouches[0];
    if (!touch) return;
    const dx = touch.clientX - this._touchStartX;
    const dy = touch.clientY - this._touchStartY;
    const dt = Date.now() - (this._touchStartTime || 0);

    // 水平轻滑切换水印模板（无水印模式下不误触发切换）
    if (!this.data.noWatermark && dt < 450 && Math.abs(dx) > 58 && Math.abs(dx) > Math.abs(dy) * 1.4) {
      const count = this.data.tplNames.length;
      const next = dx < 0 ? (this.data.styleTemplate + 1) % count : (this.data.styleTemplate - 1 + count) % count;
      this.applyTemplateIndex(next, true);
      return;
    }

    // 轻点显示对焦框反馈
    if (dt < 260 && Math.abs(dx) < 12 && Math.abs(dy) < 12) {
      this.showFocusReticle(touch.clientX, touch.clientY);
    }
  },

  showFocusReticle(x, y) {
    if (this._focusTimer) clearTimeout(this._focusTimer);
    this.setData({
      focusRingVisible: true,
      focusX: Math.round(x),
      focusY: Math.round(y)
    });
    this._focusTimer = setTimeout(() => {
      this.setData({ focusRingVisible: false });
    }, 850);
  },

  applyTemplateIndex(idx, showHudToast) {
    if (wx.vibrateShort) {
      try {
        wx.vibrateShort({ type: 'light' });
      } catch (err) {}
    }
    const curKey = String(this.data.styleTemplate);
    const updatedStyles = {
      ...(this.data.templateStyles || {}),
      [curKey]: {
        accentColorIndex: this.data.accentColorIndex,
        fontScale: this.data.fontScale,
        bgAlphaMode: this.data.bgAlphaMode,
        bottomRight: this.data.bottomRight,
        showBottomScrim: this.data.showBottomScrim
      }
    };
    const nextKey = String(idx);
    const savedNextStyle = updatedStyles[nextKey] || getDefaultTemplateStyle();
    const colorIdx = savedNextStyle.accentColorIndex !== undefined ? Number(savedNextStyle.accentColorIndex) : 0;
    const pal = ACCENT_PALETTE[colorIdx] || ACCENT_PALETTE[0];
    const alphaIdx = savedNextStyle.bgAlphaMode !== undefined ? Number(savedNextStyle.bgAlphaMode) : 1;
    const fScale = Number(savedNextStyle.fontScale) || 1;

    const patch = {
      noWatermark: false,
      styleTemplate: idx,
      activePresetId: '',
      templateStyles: updatedStyles,
      accentColorIndex: colorIdx,
      accentHex: pal.hex,
      accentName: pal.name,
      fontScale: fScale,
      fontScaleLabel: getFontScaleLabel(fScale),
      bgAlphaMode: alphaIdx,
      cardBgRgba: BG_ALPHA_PRESETS[alphaIdx] || BG_ALPHA_PRESETS[1],
      bottomRight: Boolean(savedNextStyle.bottomRight),
      showBottomScrim: Boolean(savedNextStyle.showBottomScrim)
    };
    if (showHudToast) {
      patch.templateToastText = this.data.tplNames[idx] || '';
    }
    this.setData(patch, () => {
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
    if (showHudToast) {
      if (this._tplToastTimer) clearTimeout(this._tplToastTimer);
      this._tplToastTimer = setTimeout(() => {
        this.setData({ templateToastText: '' });
      }, 1100);
    }
  },

  // ==================== 定位与自动周边微信地点 (POI) 识别 ====================
  startHighAccuracyLocation(forceOverwriteManual, interactive) {
    const onSuccess = res => {
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
    };

    const handleFinalFail = err => {
      const msg = (err && err.errMsg) || '';
      const isDenied =
        msg.indexOf('auth deny') !== -1 ||
        msg.indexOf('authorize') !== -1 ||
        msg.indexOf('denied') !== -1;
      if (isDenied) {
        this._locationDenied = true;
        this.stopLocationPolling();
        this.setData({
          locationDenied: true,
          shortPoi: '未开启定位',
          poiStatusHint: '请在小程序设置中开启「位置信息」权限'
        });
        if (interactive) {
          promptOpenSetting(
            'scope.userLocation',
            '需要位置信息权限',
            '定位权限已关闭，无法获取真实 GPS 经纬度与周边地点。请在设置中开启「位置信息」权限。'
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
      if (!this.data.hasRealFix && this.data.shortPoi === '定位中...') {
        this.setData({
          shortPoi: '实拍点位',
          poiStatusHint: '定位暂未返回，请确认手机定位服务已开启'
        });
      }
    };

    wx.getLocation({
      type: 'gcj02',
      altitude: true,
      isHighAccuracy: true,
      highAccuracyExpireTime: 3200,
      success: onSuccess,
      fail: err => {
        const msg = (err && err.errMsg) || '';
        const isDenied =
          msg.indexOf('auth deny') !== -1 ||
          msg.indexOf('authorize') !== -1 ||
          msg.indexOf('denied') !== -1;
        if (isDenied) {
          handleFinalFail(err);
          return;
        }
        // 室内/弱信号高精度或海拔超时时，自动降级为标准 GCJ-02 定位，确保一定拿到坐标
        wx.getLocation({
          type: 'gcj02',
          success: onSuccess,
          fail: handleFinalFail
        });
      }
    });
  },

  recordHardwareGpsFix(lat, lon, accuracy, altitude, forceOverwriteManual) {
    const gcjLat = Number(Number(lat).toFixed(6));
    const gcjLon = Number(Number(lon).toFixed(6));
    const wgs = gcj02ToWgs84(gcjLat, gcjLon);
    const acc = accuracy && Number(accuracy) > 0 ? Number(accuracy).toFixed(1) : '';
    const alt =
      altitude !== undefined && altitude !== null && Number(altitude) !== 0 && !isNaN(Number(altitude))
        ? Number(altitude).toFixed(1)
        : this.data.altitude;

    const shouldApplyActive = forceOverwriteManual || this.data.antiTamperMode || !this.data.isManualCoord;
    const patch = {
      hasRealFix: true,
      locationDenied: false,
      rawGcjLat: gcjLat,
      rawGcjLon: gcjLon,
      rawWgsLat: wgs.lat,
      rawWgsLon: wgs.lon,
      rawAccuracy: acc,
      altitude: alt
    };

    if (shouldApplyActive) {
      patch.isManualCoord = false;
      patch.gcjLat = gcjLat;
      patch.gcjLon = gcjLon;
      patch.wgsLat = wgs.lat;
      patch.wgsLon = wgs.lon;
      patch.accuracy = acc;
      if (this.data.shortPoi === '定位中...' || this.data.shortPoi === '未开启定位') {
        patch.shortPoi = '识别周边地点...';
      }
    }

    this.setData(patch);

    if (shouldApplyActive) {
      this.autoResolveNearbyPoi(gcjLat, gcjLon, forceOverwriteManual);
    }
  },

  /**
   * 自动调用云函数逆地理编码识别最近商铺/小区/地标（带 50m 空间缓存）
   */
  async autoResolveNearbyPoi(gcjLat, gcjLon, forceRefresh) {
    const reqId = ++this._poiReqSeq;
    this.setData({ poiLoading: true });
    const res = await resolveNearbyPoi(gcjLat, gcjLon, { forceRefresh: Boolean(forceRefresh) });
    if (reqId !== this._poiReqSeq) return;

    const patch = {
      poiLoading: false,
      nearbyPois: res.nearbyPois && res.nearbyPois.length > 0 ? res.nearbyPois : FALLBACK_QUICK_POIS,
      nearbyPoiDetails: res.nearbyPoiDetails || []
    };

    if (typeof res.serverOffsetMs === 'number') {
      patch.ntpSynced = true;
      patch.ntpOffsetMs = res.serverOffsetMs;
      patch.ntpStatusText = `云端权威授时 (${res.serverOffsetMs >= 0 ? '+' : ''}${res.serverOffsetMs}ms)`;
    }

    if (res.ok && res.resolved && res.shortPoi) {
      patch.poiResolved = true;
      patch.poiStatusHint = res.unconfigured
        ? '已用免费逆地理自动识别（配置 QQMAP_KEY 可识别精确商铺/小区）'
        : '';
      if (!this.data.hasCustomPoi || forceRefresh) {
        patch.shortPoi = res.shortPoi;
        patch.fullAddr = res.fullAddr || this.data.fullAddr;
      }
    } else {
      patch.poiResolved = false;
      patch.poiStatusHint = res.debugReason || '暂未识别到周边地点，请点击刷新定位或检查网络';
      if (this.data.shortPoi === '识别周边地点...' || this.data.shortPoi === '定位中...') {
        patch.shortPoi = '实拍点位';
      }
    }

    this.setData(patch);
  },

  restoreRealGpsCoordinates() {
    this.setData(
      {
        isManualCoord: false,
        hasCustomPoi: false,
        gcjLat: this.data.rawGcjLat,
        gcjLon: this.data.rawGcjLon,
        wgsLat: this.data.rawWgsLat,
        wgsLon: this.data.rawWgsLon,
        accuracy: this.data.rawAccuracy
      },
      () => {
        this.startHighAccuracyLocation(true, true);
        wx.showToast({ title: '已恢复真实 GPS 定位', icon: 'none' });
      }
    );
  },

  refreshRealGpsNow() {
    this.setData({ hasCustomPoi: false, isManualCoord: false });
    this._locationDenied = false;
    this.startHighAccuracyLocation(true, true);
    wx.showToast({ title: '正在刷新定位与周边地点...', icon: 'none' });
  },

  choosePoiNameOnlyOnMap() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防伪模式请直接点选下方周边真实地点', icon: 'none' });
      return;
    }
    const applyPoiName = (name, address) => {
      const pickedName = (name || address || '').trim();
      if (!pickedName) return;
      const nextCands = [pickedName, ...this.data.nearbyPois]
        .filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i)
        .slice(0, 8);
      this.setData({
        hasCustomPoi: true,
        poiResolved: true,
        shortPoi: pickedName,
        fullAddr: address || this.data.fullAddr,
        nearbyPois: nextCands
      });
      wx.showToast({ title: '已选用地名（GPS坐标不变）', icon: 'none' });
    };

    if (wx.choosePoi) {
      wx.choosePoi({
        success: res => applyPoiName(res.name, res.address),
        fail: () => {
          wx.chooseLocation({
            latitude: this.data.gcjLat || undefined,
            longitude: this.data.gcjLon || undefined,
            success: res => applyPoiName(res.name, res.address),
            fail: err => this.handleChooseLocationFail(err)
          });
        }
      });
    } else {
      wx.chooseLocation({
        latitude: this.data.gcjLat || undefined,
        longitude: this.data.gcjLon || undefined,
        success: res => applyPoiName(res.name, res.address),
        fail: err => this.handleChooseLocationFail(err)
      });
    }
  },

  choosePoiOnMap() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防伪模式下已锁定真实 GPS 坐标', icon: 'none' });
      return;
    }
    wx.chooseLocation({
      latitude: this.data.gcjLat || undefined,
      longitude: this.data.gcjLon || undefined,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const wgs = gcj02ToWgs84(gcjLat, gcjLon);
        const pickedName = (res.name || '').trim() || '实拍点位';
        this.setData({
          hasCustomPoi: true,
          poiResolved: true,
          isManualCoord: true,
          gcjLat,
          gcjLon,
          wgsLat: wgs.lat,
          wgsLon: wgs.lon,
          shortPoi: pickedName,
          fullAddr: res.address || this.data.fullAddr,
          showPoiModal: false
        });
        wx.showToast({ title: '已切换至所选地图点位', icon: 'none' });
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

  // ==================== 相机控制 ====================
  selectTimerSeconds(e) {
    const sec = Number(e.currentTarget.dataset.sec) || 0;
    this.setData({ timerSeconds: sec });
  },

  selectZoomRatio(e) {
    const nextZoom = Number(e.currentTarget.dataset.zoom) || 1.0;
    if (nextZoom === this.data.zoomRatio) return;
    try {
      const camCtx = wx.createCameraContext();
      if (camCtx && typeof camCtx.setZoom === 'function') {
        camCtx.setZoom({
          zoom: nextZoom,
          success: () => {
            this.setData({ zoomRatio: nextZoom });
          },
          fail: () => {
            wx.showToast({ title: '当前镜头不支持该变焦倍率', icon: 'none' });
          }
        });
        return;
      }
    } catch (err) {}
    wx.showToast({ title: '当前设备暂不支持变焦调节', icon: 'none' });
  },

  cycleFlashMode() {
    const modes = ['off', 'on', 'auto', 'torch'];
    const labels = ['关', '开', '自动', '常亮'];
    const nextIdx = (modes.indexOf(this.data.flashMode) + 1) % modes.length;
    this.setData({
      flashMode: modes[nextIdx],
      flashLabel: labels[nextIdx]
    });
  },

  flipCamera() {
    this.setData({
      devicePosition: this.data.devicePosition === 'back' ? 'front' : 'back',
      zoomRatio: 1.0
    });
  },

  selectTemplate(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    this.applyTemplateIndex(idx, false);
  },

  selectCoordSystem(e) {
    const sys = e.currentTarget.dataset.sys;
    if (!sys) return;
    this.setData({ coordSys: sys }, () => {
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
      wx.showToast({ title: `坐标系已切换：${sys}`, icon: 'none' });
    });
  },

  setDmsFormat(e) {
    const useDms = e.currentTarget.dataset.dms === '1';
    this.setData({ useDms }, () => {
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
  },

  selectAccentColor(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    const pal = ACCENT_PALETTE[idx] || ACCENT_PALETTE[0];
    this.setData(
      { accentColorIndex: idx, accentHex: pal.hex, accentName: pal.name },
      () => this.persistUserConfig()
    );
  },

  selectFontScale(e) {
    const sc = Number(e.currentTarget.dataset.scale) || 1;
    this.setData(
      { fontScale: sc, fontScaleLabel: getFontScaleLabel(sc) },
      () => this.persistUserConfig()
    );
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
      if (field === 'showEnv') {
        if (nextVal) this.startCompassTracking();
        else this.stopCompassTracking();
      }
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
  },

  onSwitchWatermarkField(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    const nextVal = Boolean(e.detail && e.detail.value);
    this.setData({ [field]: nextVal }, () => {
      if (field === 'showEnv') {
        if (nextVal) this.startCompassTracking();
        else this.stopCompassTracking();
      }
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
  },

  selectQuickPoi(e) {
    const poi = e.currentTarget.dataset.poi;
    if (!poi) return;
    // 防伪模式下仅允许从真实周边 250m 内的官方 POI 列表中点选，兼顾真实性与防篡改
    const detail = (this.data.nearbyPoiDetails || []).find(d => d && d.name === poi);
    if (this.data.antiTamperMode) {
      if (!detail || (detail.distance !== null && detail.distance > 250)) {
        wx.showToast({ title: '防伪模式仅允许选择周边 250m 内真实地点', icon: 'none' });
        return;
      }
    }
    const patch = { shortPoi: poi, hasCustomPoi: true, poiResolved: true };
    if (detail && detail.address) {
      patch.fullAddr = detail.address;
    }
    this.setData(patch, () => this.syncDrawerPreviewStrings());
  },

  selectQuickTitle(e) {
    const title = e.currentTarget.dataset.title;
    if (title) {
      this.setData({ customTitle: title }, () => this.persistUserConfig());
    }
  },

  appendQuickNote(e) {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防伪模式下备注已锁定', icon: 'none' });
      return;
    }
    const tag = e.currentTarget.dataset.note;
    if (!tag) return;
    const next = this.data.note ? `${this.data.note} · ${tag}` : tag;
    this.setData({ note: next, showNote: true }, () => this.persistUserConfig());
  },

  clearNoteInput() {
    if (this.data.antiTamperMode) {
      wx.showToast({ title: '防伪模式下备注已锁定', icon: 'none' });
      return;
    }
    if (this._dirtyTextFields) this._dirtyTextFields.delete('note');
    this.setData({ note: '' }, () => this.persistUserConfig());
  },

  // ==================== 快门拍摄（零云函数检测 + 离线秒拍 + 后台异步合成队列） ====================
  handleShutterTap() {
    if (this.data.countdownRemaining > 0) {
      if (this._countdownTimer) clearInterval(this._countdownTimer);
      this._countdownTimer = null;
      this.setData({ countdownRemaining: 0 });
      wx.showToast({ title: '已取消延时拍摄', icon: 'none' });
      return;
    }
    if (this._takingRawPhoto) return;

    this.flushInputBuffers();

    // 防伪模式门禁：未获真实 GPS 严禁签发防伪哈希
    if (this.data.antiTamperMode && !canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon)) {
      wx.showModal({
        title: '防伪模式等待真实 GPS',
        content: '当前尚未获取到手机真实 GPS 定位，防伪模式需等待定位成功后再签发防伪码。',
        confirmText: '刷新定位',
        cancelText: '暂不拍摄',
        success: r => {
          if (r.confirm) this.refreshRealGpsNow();
        }
      });
      return;
    }

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
    if (this._takingRawPhoto) return;
    this._takingRawPhoto = true;

    const camCtx = wx.createCameraContext();
    this.setData({ shutterFlashVisible: true });
    if (wx.vibrateShort) {
      try {
        wx.vibrateShort({ type: 'medium' });
      } catch (e) {}
    }
    setTimeout(() => {
      this.setData({ shutterFlashVisible: false });
    }, 120);

    camCtx.takePhoto({
      quality: 'high',
      success: res => {
        this._takingRawPhoto = false;
        const localCapturedAt = Date.now();
        const t = getTimeStrings(this.data.ntpOffsetMs);
        const canSign =
          this.data.antiTamperMode &&
          canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon);
        const activeGcjLat = canSign ? this.data.rawGcjLat : this.data.gcjLat;
        const activeGcjLon = canSign ? this.data.rawGcjLon : this.data.gcjLon;
        const activeWgsLat = canSign ? this.data.rawWgsLat : this.data.wgsLat;
        const activeWgsLon = canSign ? this.data.rawWgsLon : this.data.wgsLon;
        const rawPoiText =
          this.data.shortPoi === '定位中...' || this.data.shortPoi === '识别周边地点...' || this.data.shortPoi === '未开启定位'
            ? '实拍点位'
            : this.data.shortPoi || '实拍点位';

        const needPoi = Boolean(this.data.hasRealFix && !this.data.poiResolved && !this.data.hasCustomPoi);
        const needTime = !this.data.ntpSynced;
        const offlinePending = needPoi || needTime;

        const antiHash = canSign
          ? computeAntiTamperHash(activeGcjLat, activeGcjLon, t.fullTs, rawPoiText, this.data.note)
          : '';

        const snapshotCfg = {
          styleTemplate: this.data.styleTemplate,
          noWatermark: Boolean(this.data.noWatermark),
          showBottomScrim: this.data.showBottomScrim,
          customTitle: this.data.customTitle,
          coordSys: this.data.coordSys,
          useDms: this.data.useDms,
          accentColorIndex: this.data.accentColorIndex,
          accentHex: this.data.accentHex,
          fontScale: this.data.fontScale,
          bgAlphaMode: this.data.bgAlphaMode,
          cardBgRgba: this.data.cardBgRgba,
          hasRealFix: this.data.hasRealFix,
          isManualCoord: this.data.isManualCoord,
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
          shortPoi: rawPoiText,
          fullAddr: this.data.fullAddr,
          note: this.data.note,
          hm: t.hm,
          hms: t.hms,
          dateOnlyStr: t.datePart,
          weekStr: t.weekStr,
          dateWeek: t.dateWeek,
          fullTs: t.fullTs,
          showQr: this.data.showQr,
          bottomRight: this.data.bottomRight,
          antiTamperMode: canSign,
          ntpSynced: this.data.ntpSynced,
          retroSynced: false,
          offlinePending,
          poiResolved: this.data.poiResolved,
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

        // 立即入后台合成队列，不阻塞连续拍照
        this._captureQueue.push({
          rawImagePath: res.tempImagePath,
          cfg: snapshotCfg,
          localCapturedAt,
          needPoi,
          needTime
        });
        this.setData({ pendingComposeCount: this._captureQueue.length });
        this.drainCaptureQueue();
      },
      fail: () => {
        this._takingRawPhoto = false;
        promptOpenSetting(
          'scope.camera',
          '无法调用相机拍照',
          '请确认已授予小程序摄像头权限，或点击下方去设置开启。'
        );
      }
    });
  },

  async drainCaptureQueue() {
    if (this._drainingQueue) return;
    this._drainingQueue = true;

    try {
      const fallbackCanvas = await this.getFallbackCanvasNodeAsync();
      while (this._captureQueue.length > 0) {
        const job = this._captureQueue.shift();
        try {
          const out = await renderWatermarkToTempFile(job.rawImagePath, job.cfg, {
            fallbackCanvas,
            generateThumb: true
          });

          const savedRawPath = await copyTempToUserData(job.rawImagePath, 'amap_raw');
          const savedWmPath = await copyTempToUserData(out.tempFilePath, 'amap_wm');
          const savedThumbPath = out.thumbTempFilePath
            ? await copyTempToUserData(out.thumbTempFilePath, 'amap_thumb')
            : savedWmPath;

          const photoId = 'wx_' + Date.now() + '_' + Math.floor(Math.random() * 1000);
          const entry = {
            id: photoId,
            createdAt: job.localCapturedAt,
            rawPath: savedRawPath,
            wmPath: savedWmPath,
            thumbPath: savedThumbPath,
            cfg: job.cfg
          };

          const savedList = addPhotoEntry(entry);

          // 离线/未授时/未解析地点自动加入离线补签队列
          let offlinePendingCount = this.data.offlinePendingCount;
          if (job.needPoi || job.needTime) {
            offlinePendingCount = enqueueOfflinePhoto({
              photoId,
              gcjLat: job.cfg.gcjLat,
              gcjLon: job.cfg.gcjLon,
              localCapturedAt: job.localCapturedAt,
              needPoi: job.needPoi,
              needTime: job.needTime
            });
          }

          this.setData({
            photoCount: savedList.length,
            latestPhotoThumb: savedThumbPath,
            pendingComposeCount: this._captureQueue.length,
            offlinePendingCount
          });

          if (this.data.autoSaveAlbum) {
            saveImageToAlbumWithPermission(savedWmPath, '', true).catch(() => {});
          }
        } catch (err) {
          this.setData({ pendingComposeCount: this._captureQueue.length });
          wx.showToast({
            title: (err && err.message) || '水印合成失败，请重试',
            icon: 'none'
          });
        }
      }
    } finally {
      this._drainingQueue = false;
      this.setData({ pendingComposeCount: 0 });
    }
  },

  // ==================== 离线队列一键补签授时与补齐地名 ====================
  async triggerOfflineReconcile(silent) {
    if (this.data.isReconcilingOffline) return;
    const count = getOfflinePendingCount();
    if (count === 0) return;

    this.setData({ isReconcilingOffline: true });
    if (!silent) {
      wx.showLoading({ title: `正在补签授时 (0/${count})...` });
    }

    try {
      const fallbackCanvas = await this.getFallbackCanvasNodeAsync();
      const res = await reconcileOfflineQueue({
        fallbackCanvas,
        onProgress: ({ current, total }) => {
          if (!silent) {
            wx.showLoading({ title: `正在补签 (${current}/${total})...` });
          }
        }
      });
      if (!silent) wx.hideLoading();

      this.refreshPhotoSummary();
      if (!silent) {
        if (res.offline) {
          wx.showToast({ title: '当前仍无网络，联网后将自动补签', icon: 'none' });
        } else if (res.reconciled > 0) {
          wx.showToast({ title: `已补签授时并更新 ${res.reconciled} 张照片`, icon: 'success' });
        } else {
          wx.showToast({ title: '暂无需更新或云服务未就绪', icon: 'none' });
        }
      }
    } catch (e) {
      if (!silent) {
        wx.hideLoading();
        wx.showToast({ title: '补签失败，请稍后重试', icon: 'none' });
      }
    } finally {
      this.setData({
        isReconcilingOffline: false,
        offlinePendingCount: getOfflinePendingCount()
      });
    }
  },

  onTapOfflineReconcile() {
    this.triggerOfflineReconcile(false);
  },

  getFallbackCanvasNodeAsync() {
    return new Promise(resolve => {
      if (this._cachedCanvasNode) {
        resolve(this._cachedCanvasNode);
        return;
      }
      const query = wx.createSelectorQuery();
      query
        .select('#compositeCanvas')
        .fields({ node: true, size: true })
        .exec(res => {
          const node = res && res[0] && res[0].node;
          if (node) this._cachedCanvasNode = node;
          resolve(node || null);
        });
    });
  },

  openCurrentInWeChatMap() {
    if (!this.data.hasRealFix && !this.data.isManualCoord) {
      wx.showToast({ title: '正在等待定位完成', icon: 'none' });
      return;
    }
    wx.openLocation({
      latitude: Number(this.data.gcjLat),
      longitude: Number(this.data.gcjLon),
      name: this.data.shortPoi || '实拍点位',
      address: this.data.fullAddr || `高德 GCJ-02: ${this.data.gcjLat}, ${this.data.gcjLon}`,
      scale: 18
    });
  },

  // ==================== 弹窗开关（互斥平滑切换 & 2-Step 水印抽屉） ====================
  computeWmOverlayLift() {
    const run = () => {
      try {
        const q = wx.createSelectorQuery();
        q.select('.viewfinder-wrap').boundingClientRect();
        q.select('#wmBottomSheet').boundingClientRect();
        q.exec(res => {
          if (!res || !res[0] || !res[1]) return;
          const overlap = Math.max(0, Math.round(res[0].bottom - res[1].top + 8));
          if (overlap > 0 && overlap !== this.data.wmOverlayLiftPx) {
            this.setData({ wmOverlayLiftPx: overlap });
          }
        });
      } catch (e) {}
    };
    if (typeof wx.nextTick === 'function') {
      wx.nextTick(run);
    } else {
      setTimeout(run, 40);
    }
  },

  refreshFilteredCatalogs() {
    this.setData({
      filteredTplCards: this.filterTplCardsList(this.data.wmCategory, this.data.wmSearchQuery),
      filteredPresets: this.filterCustomPresetsList(this.data.customPresets, this.data.wmSearchQuery)
    });
  },

  async verifyDirtyTextsBeforeSave(targetFields) {
    if (!this._dirtyTextFields || this._dirtyTextFields.size === 0) {
      return true;
    }
    const fields = Array.isArray(targetFields) ? targetFields : [];
    const texts = [];
    const checkedKeys = [];
    fields.forEach(k => {
      if (this._dirtyTextFields.has(k)) {
        const v = String(this.data[k] || '').trim();
        if (v) texts.push(v);
        checkedKeys.push(k);
      }
    });
    if (texts.length === 0) {
      checkedKeys.forEach(k => this._dirtyTextFields.delete(k));
      return true;
    }
    wx.showLoading({ title: '内容合规校验中...' });
    const secRes = await checkTextsSecurity(texts, 3);
    wx.hideLoading();
    if (!secRes.pass) {
      showViolationModal(secRes.message);
      return false;
    }
    checkedKeys.forEach(k => this._dirtyTextFields.delete(k));
    return true;
  },

  openPoiQuickModal() {
    if (this.data.locationDenied) {
      this.startHighAccuracyLocation(true, true);
      return;
    }
    if (!this.data.hasRealFix && !this.data.isManualCoord) {
      this.startHighAccuracyLocation(false, true);
    } else if (this.data.hasRealFix && !this.data.poiResolved && !this.data.poiLoading) {
      this.autoResolveNearbyPoi(this.data.gcjLat, this.data.gcjLon, true);
    }
    this.setData({
      showSettings: false,
      showWmModal: false,
      showPoiModal: true
    });
  },

  async closePoiQuickModal() {
    this.flushInputBuffers();
    const ok = await this.verifyDirtyTextsBeforeSave(['shortPoi', 'fullAddr']);
    if (!ok) return;
    this.persistUserConfig();
    this.setData({ showPoiModal: false });
  },

  openSettingsModal() {
    this.setData({
      showPoiModal: false,
      showWmModal: false,
      showSettings: true
    });
  },

  closeSettingsModal() {
    this.setData({ showSettings: false });
  },

  openWatermarkModal() {
    this.syncDrawerPreviewStrings();
    this.refreshFilteredCatalogs();
    this.setData(
      {
        showPoiModal: false,
        showSettings: false,
        showWmModal: true,
        wmStep: 'picker'
      },
      () => this.computeWmOverlayLift()
    );
  },

  async closeWatermarkModal() {
    this.flushInputBuffers();
    const ok = await this.verifyDirtyTextsBeforeSave(['customTitle', 'shortPoi', 'fullAddr', 'note']);
    if (!ok) return;
    this.persistUserConfig();
    this.setData({
      showWmModal: false,
      wmStep: 'picker',
      wmSearchOpen: false
    });
  },

  async applyWatermarkCustomization() {
    this.flushInputBuffers();
    const ok = await this.verifyDirtyTextsBeforeSave(['customTitle', 'shortPoi', 'fullAddr', 'note']);
    if (!ok) return;
    this.setData(
      {
        noWatermark: false,
        showWmModal: false,
        wmStep: 'picker',
        wmSearchOpen: false
      },
      () => {
        this.persistUserConfig();
        wx.showToast({ title: '水印设置已生效', icon: 'none' });
      }
    );
  },

  toggleNoWatermark() {
    const next = !this.data.noWatermark;
    this.setData(
      {
        noWatermark: next,
        activePresetId: ''
      },
      () => {
        this.persistUserConfig();
        wx.showToast({
          title: next ? '已切换无水印原图拍摄' : '已启用水印显示',
          icon: 'none'
        });
      }
    );
  },

  switchWmPickerTab(e) {
    const tab = e.currentTarget.dataset.tab || 'all';
    if (tab === this.data.wmPickerTab) return;
    this.setData({ wmPickerTab: tab }, () => this.refreshFilteredCatalogs());
  },

  toggleWmSearch() {
    const next = !this.data.wmSearchOpen;
    this.setData(
      {
        wmSearchOpen: next,
        wmSearchQuery: next ? this.data.wmSearchQuery : ''
      },
      () => this.refreshFilteredCatalogs()
    );
  },

  onInputWmSearch(e) {
    this.setData(
      { wmSearchQuery: e.detail.value || '' },
      () => this.refreshFilteredCatalogs()
    );
  },

  clearWmSearch() {
    this.setData(
      { wmSearchOpen: false, wmSearchQuery: '' },
      () => this.refreshFilteredCatalogs()
    );
  },

  selectWmCategory(e) {
    const cat = e.currentTarget.dataset.cat || 'all';
    this.setData({ wmCategory: cat }, () => this.refreshFilteredCatalogs());
  },

  onTapTplCard(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    if (isNaN(idx)) return;
    if (!this.data.noWatermark && this.data.styleTemplate === idx) {
      this.enterWmEditor();
      return;
    }
    this.applyTemplateIndex(idx, false);
  },

  enterWmEditor() {
    this.syncDrawerPreviewStrings();
    this.setData({
      noWatermark: false,
      wmStep: 'editor'
    });
  },

  backToWmPicker() {
    this.refreshFilteredCatalogs();
    this.setData({ wmStep: 'picker' });
  },

  openWmFieldEditor(e) {
    const field = e.currentTarget.dataset.field || 'poi';
    this.syncDrawerPreviewStrings();
    this.setData({
      wmStep: 'field',
      wmEditField: field,
      wmFieldTitle: WM_FIELD_TITLES[field] || '编辑水印项'
    });
  },

  async confirmWmFieldAndReturn() {
    this.flushInputBuffers();
    const ok = await this.verifyDirtyTextsBeforeSave(['customTitle', 'shortPoi', 'fullAddr', 'note']);
    if (!ok) return;
    this.syncDrawerPreviewStrings();
    this.persistUserConfig();
    this.setData({ wmStep: 'editor' });
  },

  promptSaveCurrentPreset() {
    this.flushInputBuffers();
    const tplName = this.data.tplNames[this.data.styleTemplate] || '水印';
    const suggested = `${tplName}·${this.data.customTitle || '常用'}`.slice(0, 14);
    wx.showModal({
      title: '存为常用水印',
      editable: true,
      content: suggested,
      placeholderText: '输入常用水印名称（如：外业巡检）',
      confirmColor: '#2563eb',
      success: async res => {
        if (!res.confirm) return;
        const name = String(res.content || suggested).trim().slice(0, 18);
        if (!name) return;

        const texts = [name, this.data.customTitle, this.data.note].filter(Boolean);
        if (texts.length > 0) {
          wx.showLoading({ title: '安全合规校验中...' });
          const secRes = await checkTextsSecurity(texts, 3);
          wx.hideLoading();
          if (!secRes.pass) {
            showViolationModal(secRes.message);
            return;
          }
        }

        const presetId = `preset_${Date.now()}`;
        const newPreset = {
          id: presetId,
          name,
          styleTemplate: this.data.styleTemplate,
          accentColorIndex: this.data.accentColorIndex,
          accentHex: this.data.accentHex,
          fontScale: this.data.fontScale,
          bgAlphaMode: this.data.bgAlphaMode,
          cardBgRgba: this.data.cardBgRgba,
          bottomRight: this.data.bottomRight,
          showBottomScrim: this.data.showBottomScrim,
          customTitle: this.data.customTitle,
          coordSys: this.data.coordSys,
          useDms: this.data.useDms,
          showTime: this.data.showTime,
          showLoc: this.data.showLoc,
          showCoords: this.data.showCoords,
          showCoordTag: this.data.showCoordTag,
          showAccuracy: this.data.showAccuracy,
          showAddress: this.data.showAddress,
          showNote: this.data.showNote,
          showEnv: this.data.showEnv,
          showQr: this.data.showQr,
          note: this.data.antiTamperMode ? '' : (this.data.note || '')
        };

        const nextPresets = [newPreset, ...(this.data.customPresets || [])].slice(0, 20);
        this.setData(
          {
            customPresets: nextPresets,
            filteredPresets: this.filterCustomPresetsList(nextPresets, this.data.wmSearchQuery),
            activePresetId: presetId,
            noWatermark: false
          },
          () => {
            this.persistUserConfig();
            wx.showToast({ title: `已保存「${name}」`, icon: 'success' });
          }
        );
      }
    });
  },

  onTapPresetCard(e) {
    const id = e.currentTarget.dataset.id;
    const preset = (this.data.customPresets || []).find(p => p && p.id === id);
    if (!preset) return;

    if (!this.data.noWatermark && this.data.activePresetId === id) {
      this.enterWmEditor();
      return;
    }

    const colorIdx = Number(preset.accentColorIndex) || 0;
    const pal = ACCENT_PALETTE[colorIdx] || ACCENT_PALETTE[0];
    const alphaIdx = preset.bgAlphaMode !== undefined ? Number(preset.bgAlphaMode) : 1;
    const fScale = Number(preset.fontScale) || 1;

    const patch = {
      noWatermark: false,
      activePresetId: preset.id,
      styleTemplate: Number(preset.styleTemplate) || 0,
      accentColorIndex: colorIdx,
      accentHex: preset.accentHex || pal.hex,
      accentName: pal.name,
      fontScale: fScale,
      fontScaleLabel: getFontScaleLabel(fScale),
      bgAlphaMode: alphaIdx,
      cardBgRgba: preset.cardBgRgba || BG_ALPHA_PRESETS[alphaIdx] || BG_ALPHA_PRESETS[1],
      bottomRight: Boolean(preset.bottomRight),
      showBottomScrim: Boolean(preset.showBottomScrim),
      customTitle: preset.customTitle || '现场点位核验',
      coordSys: preset.coordSys || 'WGS-84',
      useDms: Boolean(preset.useDms),
      showTime: preset.showTime !== false,
      showLoc: preset.showLoc !== false,
      showCoords: preset.showCoords !== false,
      showCoordTag: preset.showCoordTag !== false,
      showAccuracy: preset.showAccuracy !== false,
      showAddress: Boolean(preset.showAddress),
      showNote: preset.showNote !== false,
      showEnv: Boolean(preset.showEnv),
      showQr: preset.showQr !== false
    };
    if (!this.data.antiTamperMode && preset.note !== undefined) {
      patch.note = preset.note;
    }

    this.setData(patch, () => {
      if (this.data.showEnv) this.startCompassTracking();
      else this.stopCompassTracking();
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
      wx.showToast({ title: `已应用：${preset.name}`, icon: 'none' });
    });
  },

  deleteCustomPreset(e) {
    const id = e.currentTarget.dataset.id;
    const nextPresets = (this.data.customPresets || []).filter(p => p && p.id !== id);
    this.setData(
      {
        customPresets: nextPresets,
        filteredPresets: this.filterCustomPresetsList(nextPresets, this.data.wmSearchQuery),
        activePresetId: this.data.activePresetId === id ? '' : this.data.activePresetId
      },
      () => {
        this.persistUserConfig();
        wx.showToast({ title: '已删除该常用方案', icon: 'none' });
      }
    );
  },

  // ==================== 开关回调 ====================
  onToggleGridLines(e) {
    this.setData({ showGridLines: e.detail.value }, () => this.persistUserConfig());
  },
  onToggleShowAddress(e) {
    this.setData({ showAddress: e.detail.value }, () => {
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
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

    if (enabled && this.data.hasRealFix) {
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
            ? '防伪已开启：已回滚为真实 GPS 并锁定'
            : '防伪已开启：真实 GPS 与备注已锁定',
          icon: 'none'
        });
      }
      this.syncDrawerPreviewStrings();
      this.persistUserConfig();
    });
  },

  // ==================== 防抖输入（记录变更以触发云安全校验） ====================
  _scheduleBufferedInput(field, val) {
    if (this._dirtyTextFields) {
      this._dirtyTextFields.add(field);
    }
    this._inputBuffers[field] = val;
    if (this._inputTimers[field]) clearTimeout(this._inputTimers[field]);
    this._inputTimers[field] = setTimeout(() => {
      const latest = this._inputBuffers[field];
      delete this._inputBuffers[field];
      this._inputTimers[field] = null;
      if (latest === undefined) return;
      const patch = { [field]: latest };
      if (field === 'shortPoi' || field === 'fullAddr') patch.hasCustomPoi = true;
      this.setData(patch, () => this.syncDrawerPreviewStrings());
    }, 100);
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

  onShareAppMessage() {
    this.flushInputBuffers();
    const t = getTimeStrings(this.data.ntpOffsetMs);
    const canSign =
      this.data.antiTamperMode &&
      canSignAntiTamper(this.data.hasRealFix, this.data.rawGcjLat, this.data.rawGcjLon);
    const lat = canSign ? this.data.rawGcjLat : this.data.gcjLat;
    const lon = canSign ? this.data.rawGcjLon : this.data.gcjLon;
    const rawPoi = this.data.shortPoi === '定位中...' ? '实拍点位' : this.data.shortPoi || '实拍点位';
    const poi = encodeURIComponent(rawPoi);
    const ts = encodeURIComponent(t.fullTs);
    const note = encodeURIComponent(this.data.note || '');
    const ntpFlag = this.data.ntpSynced ? '1' : '0';
    const sig = canSign
      ? computeAntiTamperHash(lat, lon, t.fullTs, rawPoi, this.data.note || '').slice(0, 12)
      : '';
    const extra = `&ntp=${ntpFlag}${note ? `&note=${note}` : ''}${sig ? `&sig=${sig}` : ''}`;
    if (!lat && !lon) {
      return {
        title: '极速高德水印相机 v2.0',
        path: '/pages/index/index'
      };
    }
    return {
      title: `实拍定位：${rawPoi} (${lat}°N, ${lon}°E)`,
      path: `/pages/index/index?act=jump&lat=${lat}&lon=${lon}&poi=${poi}&ts=${ts}${extra}`
    };
  }
});
