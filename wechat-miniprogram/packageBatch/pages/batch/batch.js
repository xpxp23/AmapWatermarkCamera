/**
 * 极速高德水印相机 v1.0 · 批量相册加水印分包页面
 * v1.0 核心升级：
 * - 选图瞬间解析 64KB EXIF 并立即展示队列，彻底消除选图期 20 次串行云函数阻塞
 * - 优先 EXIF 模式下自动按每张照片自身 EXIF 坐标调用 regeoPoi 解析真实周边地名（网格缓存防重复请求）
 * - 修复部分完成后按钮数量显示偏差（精准展示待处理张数 pendingCount）
 * - 移除 160px 假小图与假默认坐标/海拔
 */

const {
  getTimeStrings
} = require('../../../utils/coord_qr.js');

const { resolveNearbyPoi } = require('../../../utils/poi_service.js');
const { readImageExifLite } = require('../../utils/exif_lite.js');
const { renderWatermarkToTempFile } = require('../../../utils/watermark_renderer.js');
const { ensureAlbumWritePermission } = require('../../../utils/privacy.js');
const { loadUserConfig } = require('../../../utils/photo_store.js');

const TPL_NAMES = ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'];

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function saveTempToSystemAlbum(filePath) {
  return new Promise((resolve, reject) => {
    wx.saveImageToPhotosAlbum({
      filePath,
      success: () => resolve(true),
      fail: err => reject(err || new Error('写入系统相册失败'))
    });
  });
}

function tryUnlinkTemp(filePath) {
  if (!filePath || !wx.getFileSystemManager) return;
  try {
    const fs = wx.getFileSystemManager();
    fs.unlink({ filePath, fail: () => {} });
  } catch (e) {}
}

Page({
  data: {
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    tplNames: TPL_NAMES,
    styleTemplate: 0,
    showBottomScrim: false,
    useExifPriority: true,
    shortPoi: '',
    note: '',
    coordSys: 'WGS-84',
    items: [],
    isRunning: false,
    isCancelling: false,
    pendingCount: 0,
    doneCount: 0,
    successCount: 0,
    failedCount: 0,
    progressPercent: 0
  },

  onLoad(query) {
    this._cancelRequested = false;
    this._userEditedPoi = false;
    this._baseCfg = this.loadBaseWatermarkConfig();

    let menuBtnTop = 48;
    let menuBtnHeight = 32;
    let menuBtnRightSpace = 104;
    try {
      const winInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
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

    this.setData({
      menuBtnTop,
      menuBtnHeight,
      menuBtnRightSpace,
      styleTemplate: this._baseCfg.styleTemplate || 0,
      showBottomScrim: Boolean(this._baseCfg.showBottomScrim),
      shortPoi: this._baseCfg.shortPoi || '',
      note: this._baseCfg.note || '',
      coordSys: this._baseCfg.coordSys || 'WGS-84'
    });

    if (query && query.autoPick === '1') {
      setTimeout(() => this.chooseAlbumImages(), 160);
    }
  },

  onUnload() {
    this._cancelRequested = true;
    try {
      wx.setKeepScreenOn({ keepScreenOn: false });
    } catch (e) {}
  },

  loadBaseWatermarkConfig() {
    const app = getApp();
    if (app && app.globalData && app.globalData.liveConfigSnapshot) {
      return { ...app.globalData.liveConfigSnapshot };
    }
    const saved = loadUserConfig() || {};
    return {
      styleTemplate: saved.styleTemplate !== undefined ? Number(saved.styleTemplate) : 0,
      showBottomScrim: Boolean(saved.showBottomScrim),
      customTitle: saved.customTitle || '现场点位核验',
      coordSys: saved.coordSys || 'WGS-84',
      useDms: Boolean(saved.useDms),
      accentHex: '#38bdf8',
      fontScale: Number(saved.fontScale) || 1,
      cardBgRgba: 'rgba(11, 19, 43, 0.84)',
      hasRealFix: false,
      isManualCoord: false,
      gcjLat: 0,
      gcjLon: 0,
      wgsLat: 0,
      wgsLon: 0,
      accuracy: '',
      altitude: '',
      azimuthDeg: 0,
      azimuthText: '',
      shortPoi: '实拍点位',
      fullAddr: '',
      note: saved.note || '',
      showQr: saved.showQr !== false,
      bottomRight: Boolean(saved.bottomRight),
      showTime: saved.showTime !== false,
      showLoc: saved.showLoc !== false,
      showCoords: saved.showCoords !== false,
      showCoordTag: saved.showCoordTag !== false,
      showAccuracy: saved.showAccuracy !== false,
      showAddress: Boolean(saved.showAddress),
      showNote: saved.showNote !== false,
      showEnv: Boolean(saved.showEnv),
      ntpOffsetMs: 0,
      ntpSynced: false
    };
  },

  goBack() {
    if (this.data.isRunning) {
      wx.showModal({
        title: '任务正在执行中',
        content: '返回将取消剩余队列，已保存至系统相册的照片不受影响，是否继续返回？',
        confirmText: '确认返回',
        success: r => {
          if (r.confirm) {
            this._cancelRequested = true;
            wx.navigateBack();
          }
        }
      });
      return;
    }
    wx.navigateBack();
  },

  selectTemplate(e) {
    if (this.data.isRunning) return;
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    this.setData({ styleTemplate: idx });
  },

  toggleExifMode() {
    if (this.data.isRunning) return;
    this.setData({ useExifPriority: !this.data.useExifPriority });
  },

  onToggleBottomScrim(e) {
    if (this.data.isRunning) return;
    this.setData({ showBottomScrim: e.detail.value });
  },

  onInputPoi(e) {
    this._userEditedPoi = true;
    this.setData({ shortPoi: e.detail.value });
  },

  onInputNote(e) {
    this.setData({ note: e.detail.value });
  },

  chooseAlbumImages() {
    if (this.data.isRunning) {
      wx.showToast({ title: '请等待当前队列完成或取消', icon: 'none' });
      return;
    }
    wx.chooseMedia({
      count: 20,
      mediaType: ['image'],
      sourceType: ['album'],
      sizeType: ['original'],
      success: async res => {
        const files = (res && res.tempFiles) || [];
        if (files.length === 0) return;

        wx.showLoading({ title: `解析 EXIF (${files.length}张)...` });
        let newItems = [];
        try {
          newItems = await Promise.all(
            files.map(async (f, i) => {
              const exif = await readImageExifLite(f.tempFilePath);
              let exifLabel = '无EXIF·用当前';
              if (exif.hasTime && exif.hasGps) exifLabel = 'EXIF 时间+GPS';
              else if (exif.hasTime) exifLabel = 'EXIF 拍摄时间';
              else if (exif.hasGps) exifLabel = 'EXIF GPS坐标';

              return {
                id: `batch_${Date.now()}_${i}_${Math.floor(Math.random() * 1000)}`,
                path: f.tempFilePath,
                status: 'pending',
                errorMsg: '',
                exif,
                exifLabel
              };
            })
          );
        } finally {
          wx.hideLoading();
        }

        if (newItems.length > 0) {
          const merged = [...this.data.items, ...newItems];
          this.setData({ items: merged }, () => this.recomputeStats());
        }
      }
    });
  },

  removeItem(e) {
    if (this.data.isRunning) return;
    const id = e.currentTarget.dataset.id;
    const next = this.data.items.filter(item => item.id !== id);
    this.setData({ items: next }, () => this.recomputeStats());
  },

  clearAllItems() {
    if (this.data.isRunning) return;
    this.setData({
      items: [],
      pendingCount: 0,
      doneCount: 0,
      successCount: 0,
      failedCount: 0,
      progressPercent: 0
    });
  },

  recomputeStats() {
    const items = this.data.items;
    const total = items.length;
    let successCount = 0;
    let failedCount = 0;
    let pendingCount = 0;
    items.forEach(it => {
      if (it.status === 'success') successCount++;
      else if (it.status === 'failed') {
        failedCount++;
        pendingCount++;
      } else {
        pendingCount++;
      }
    });
    const doneCount = successCount + failedCount;
    const progressPercent = total > 0 ? Math.round((doneCount / total) * 100) : 0;
    this.setData({ pendingCount, doneCount, successCount, failedCount, progressPercent });
  },

  async buildCfgForItem(item) {
    const base = this._baseCfg;
    const nowT = getTimeStrings(base.ntpOffsetMs || 0);
    const useExif = this.data.useExifPriority && item && item.exif;

    const useTimeFromExif = Boolean(useExif && item.exif.hasTime);
    const useGpsFromExif = Boolean(useExif && item.exif.hasGps);

    const gcjLat = useGpsFromExif ? item.exif.gcjLat : base.gcjLat;
    const gcjLon = useGpsFromExif ? item.exif.gcjLon : base.gcjLon;
    const wgsLat = useGpsFromExif ? item.exif.wgsLat : base.wgsLat;
    const wgsLon = useGpsFromExif ? item.exif.wgsLon : base.wgsLon;
    const hasRealFix = useGpsFromExif ? true : Boolean(base.hasRealFix);
    const altitude =
      useGpsFromExif && item.exif.altitude !== null ? String(item.exif.altitude) : base.altitude || '';

    let shortPoi = (this.data.shortPoi || '').trim() || base.shortPoi || '实拍点位';
    let fullAddr = base.fullAddr || '';

    // 修复 EXIF 地名覆盖 Bug：当优先 EXIF 且照片自带 GPS 且用户未手动锁定统一地名时，自动识别该照片自身坐标的周边 POI
    if (useGpsFromExif && !this._userEditedPoi) {
      const poiRes = await resolveNearbyPoi(gcjLat, gcjLon);
      if (poiRes && poiRes.ok && poiRes.resolved && poiRes.shortPoi) {
        shortPoi = poiRes.shortPoi;
        if (poiRes.fullAddr) fullAddr = poiRes.fullAddr;
      }
    }

    return {
      ...base,
      styleTemplate: this.data.styleTemplate,
      showBottomScrim: this.data.showBottomScrim,
      shortPoi,
      fullAddr,
      note: (this.data.note || '').trim(),
      hasRealFix,
      gcjLat,
      gcjLon,
      wgsLat,
      wgsLon,
      altitude,
      hm: useTimeFromExif ? item.exif.hm : nowT.hm,
      hms: useTimeFromExif ? item.exif.hms : nowT.hms,
      dateOnlyStr: useTimeFromExif ? item.exif.dateOnlyStr : nowT.datePart,
      weekStr: useTimeFromExif ? item.exif.weekStr : nowT.weekStr,
      dateWeek: useTimeFromExif ? item.exif.dateWeek : nowT.dateWeek,
      fullTs: useTimeFromExif ? item.exif.timestamp : nowT.fullTs,
      antiTamperMode: false,
      antiTamperHash: ''
    };
  },

  getFallbackCanvasNode() {
    return new Promise(resolve => {
      if (this._fallbackCanvas) {
        resolve(this._fallbackCanvas);
        return;
      }
      const query = wx.createSelectorQuery();
      query
        .select('#batchFallbackCanvas')
        .fields({ node: true, size: true })
        .exec(res => {
          const node = res && res[0] && res[0].node;
          if (node) this._fallbackCanvas = node;
          resolve(node || null);
        });
    });
  },

  cancelBatchQueue() {
    if (!this.data.isRunning) return;
    this._cancelRequested = true;
    this.setData({ isCancelling: true });
    wx.showToast({ title: '正在停止剩余队列...', icon: 'none' });
  },

  retryFailedItems() {
    if (this.data.isRunning) return;
    const hasFailed = this.data.items.some(it => it.status === 'failed');
    if (!hasFailed) return;
    const reset = this.data.items.map(it =>
      it.status === 'failed' ? { ...it, status: 'pending', errorMsg: '' } : it
    );
    this.setData({ items: reset }, () => {
      this.recomputeStats();
      this.startBatchQueue();
    });
  },

  async startBatchQueue() {
    if (this.data.isRunning) return;
    const pendingIndices = [];
    this.data.items.forEach((it, idx) => {
      if (it.status === 'pending' || it.status === 'failed') {
        pendingIndices.push(idx);
      }
    });
    if (pendingIndices.length === 0) {
      wx.showToast({ title: '没有待处理的照片', icon: 'none' });
      return;
    }

    const granted = await ensureAlbumWritePermission(true);
    if (!granted) {
      wx.showToast({ title: '未开启相册写入权限，无法导出', icon: 'none' });
      return;
    }

    this._cancelRequested = false;
    this.setData({ isRunning: true, isCancelling: false });

    try {
      wx.setKeepScreenOn({ keepScreenOn: true });
    } catch (e) {}

    const fallbackCanvas = await this.getFallbackCanvasNode();

    try {
      for (let k = 0; k < pendingIndices.length; k++) {
        if (this._cancelRequested) break;
        const idx = pendingIndices[k];
        const currentItem = this.data.items[idx];
        if (!currentItem) continue;

        this.setData({ [`items[${idx}].status`]: 'processing', [`items[${idx}].errorMsg`]: '' });

        let exportedTempPath = '';
        try {
          const cfg = await this.buildCfgForItem(currentItem);
          const out = await renderWatermarkToTempFile(currentItem.path, cfg, {
            fallbackCanvas,
            generateThumb: false
          });
          exportedTempPath = out.tempFilePath;
          await saveTempToSystemAlbum(exportedTempPath);

          this.setData({ [`items[${idx}].status`]: 'success', [`items[${idx}].errorMsg`]: '' });
        } catch (err) {
          const msg = (err && (err.message || err.errMsg)) || '处理失败';
          this.setData({
            [`items[${idx}].status`]: 'failed',
            [`items[${idx}].errorMsg`]: msg.slice(0, 24)
          });
        } finally {
          if (exportedTempPath) {
            tryUnlinkTemp(exportedTempPath);
          }
          this.recomputeStats();
          await delay(50);
        }
      }
    } finally {
      try {
        wx.setKeepScreenOn({ keepScreenOn: false });
      } catch (e) {}
      this.setData({ isRunning: false, isCancelling: false }, () => {
        this.recomputeStats();
        if (this._cancelRequested) {
          wx.showToast({ title: `已停止，成功保存 ${this.data.successCount} 张`, icon: 'none' });
        } else if (this.data.failedCount === 0) {
          wx.showToast({ title: `全部 ${this.data.successCount} 张已存入系统相册`, icon: 'success' });
        } else {
          wx.showToast({
            title: `完成：成功 ${this.data.successCount} 张，失败 ${this.data.failedCount} 张`,
            icon: 'none'
          });
        }
      });
    }
  }
});
