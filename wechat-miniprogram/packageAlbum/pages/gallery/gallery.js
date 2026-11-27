/**
 * 极速高德水印相机 v1.0 · 独立相册分包 · 网格相册页 (packageAlbum/pages/gallery)
 * 特性：
 * - 仅加载 240px 缩略图 thumbPath + lazy-load，100 张显存 < 8MB
 * - 按日期吸顶分组的 3 列正方形网格，极简角标（左下防伪盾牌/可编辑标记，右下时间）
 * - 顶部系统相册融合双入口（批量加水印 + 系统相册选图验真）
 * - 存储配额可视化进度条 + 一键清理无损底图 + 多选批量存相册/导出台账/删除
 */

const {
  loadPhotos,
  deletePhotosByIds,
  clearAllRawOriginals,
  estimateStorageUsage
} = require('../../../utils/photo_store.js');

const {
  verifyImageFile,
  formatVerifyReportText
} = require('../../../utils/photo_verifier.js');

const {
  ensureAlbumWritePermission
} = require('../../../utils/privacy.js');

const {
  getOfflinePendingCount,
  removeOfflinePhoto,
  reconcileOfflineQueue
} = require('../../../utils/offline_queue.js');

function saveOneToAlbum(filePath) {
  return new Promise((resolve, reject) => {
    wx.saveImageToPhotosAlbum({
      filePath,
      success: () => resolve(true),
      fail: err => reject(err)
    });
  });
}

function groupPhotosByDate(photos) {
  const map = {};
  const order = [];
  (photos || []).forEach(p => {
    if (!p) return;
    const cfg = p.cfg || {};
    const dateKey =
      cfg.dateOnlyStr ||
      (cfg.fullTs ? String(cfg.fullTs).slice(0, 10) : '未归类日期');
    const hm = cfg.hm || (cfg.fullTs ? String(cfg.fullTs).slice(11, 16) : '');
    if (!map[dateKey]) {
      map[dateKey] = [];
      order.push(dateKey);
    }
    map[dateKey].push({
      id: p.id,
      thumbPath: p.thumbPath || p.wmPath || '',
      wmPath: p.wmPath || '',
      hasRaw: Boolean(p.rawPath),
      antiTamper: Boolean(cfg.antiTamperMode),
      offlinePending: Boolean(cfg.offlinePending),
      hm,
      shortPoi: cfg.shortPoi || '实拍点位'
    });
  });
  return order.map(date => ({
    date,
    count: map[date].length,
    items: map[date]
  }));
}

Page({
  data: {
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    photoCount: 0,
    dateGroups: [],
    storage: {
      usedMB: '0.0',
      quotaMB: 200,
      percent: 0,
      photoCount: 0,
      maxPhotos: 120,
      rawCount: 0,
      maxRaw: 25
    },
    multiSelectMode: false,
    selectedMap: {},
    selectedCount: 0,
    offlinePendingCount: 0,
    isReconcilingOffline: false,
    showVerifyModal: false,
    verifyReport: null
  },

  onLoad() {
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
    this.setData({ menuBtnTop, menuBtnHeight, menuBtnRightSpace });
  },

  onShow() {
    this.reloadGalleryData();
  },

  reloadGalleryData() {
    const photos = loadPhotos();
    this._allPhotos = photos;
    const dateGroups = groupPhotosByDate(photos);
    this.setData({
      photoCount: photos.length,
      dateGroups,
      selectedMap: {},
      selectedCount: 0,
      multiSelectMode: photos.length === 0 ? false : this.data.multiSelectMode,
      offlinePendingCount: getOfflinePendingCount()
    });
    estimateStorageUsage().then(storage => {
      this.setData({ storage });
    });
  },

  goBackCamera() {
    wx.navigateBack();
  },

  toggleMultiSelect() {
    if (this.data.photoCount === 0) return;
    const next = !this.data.multiSelectMode;
    this.setData({
      multiSelectMode: next,
      selectedMap: {},
      selectedCount: 0
    });
  },

  selectAllPhotos() {
    if (!this._allPhotos || this._allPhotos.length === 0) return;
    if (this.data.selectedCount === this._allPhotos.length) {
      this.setData({ selectedMap: {}, selectedCount: 0 });
      return;
    }
    const map = {};
    this._allPhotos.forEach(p => {
      if (p && p.id) map[p.id] = true;
    });
    this.setData({
      selectedMap: map,
      selectedCount: this._allPhotos.length
    });
  },

  onTapGridPhoto(e) {
    const id = e.currentTarget.dataset.id;
    if (!id) return;
    if (this.data.multiSelectMode) {
      const nextMap = { ...this.data.selectedMap };
      if (nextMap[id]) {
        delete nextMap[id];
      } else {
        nextMap[id] = true;
      }
      this.setData({
        selectedMap: nextMap,
        selectedCount: Object.keys(nextMap).length
      });
      return;
    }
    wx.navigateTo({
      url: `/packageAlbum/pages/detail/detail?id=${encodeURIComponent(id)}`
    });
  },

  // 顶部系统相册融合入口 1：批量加水印
  openSystemAlbumBatch() {
    wx.navigateTo({
      url: '/packageBatch/pages/batch/batch?autoPick=1'
    });
  },

  // 顶部系统相册融合入口 2：系统相册选图验真（零假阳性）
  verifyFromSystemAlbum() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album'],
      success: async res => {
        const file = res && res.tempFiles && res.tempFiles[0];
        if (!file) return;
        wx.showLoading({ title: '1:1双角识码验真中...' });
        try {
          const fc = await this.getVerifyFallbackCanvas();
          const result = await verifyImageFile(file.tempFilePath, fc);
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
      }
    });
  },

  closeVerifyModal() {
    this.setData({ showVerifyModal: false });
  },

  copyVerifyReport() {
    const text = formatVerifyReportText(this.data.verifyReport);
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  openVerifiedInMap() {
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

  // 一键清理无损底图（保留 120 张水印成品与缩略图）
  handleClearRawOriginals() {
    if (!this.data.storage || this.data.storage.rawCount === 0) {
      wx.showToast({ title: '当前没有可清理的底图', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '一键清理无损底图',
      content: `将清理 ${this.data.storage.rawCount} 张无损底图以释放本地空间。所有已合成的高清水印照片与缩略图将 100% 保留，清理后旧照片仅不再支持重绘水印。`,
      confirmText: '确认清理',
      success: res => {
        if (!res.confirm) return;
        const result = clearAllRawOriginals();
        this.reloadGalleryData();
        wx.showToast({
          title: `已释放 ${result.freedCount} 张无损底图空间`,
          icon: 'success'
        });
      }
    });
  },

  getSelectedPhotos() {
    const map = this.data.selectedMap || {};
    return (this._allPhotos || []).filter(p => p && map[p.id]);
  },

  // 多选操作 1：批量存系统相册
  async batchSaveToSystemAlbum() {
    const selected = this.getSelectedPhotos();
    if (selected.length === 0) {
      wx.showToast({ title: '请先勾选照片', icon: 'none' });
      return;
    }
    const ok = await ensureAlbumWritePermission(true);
    if (!ok) {
      wx.showToast({ title: '未授予系统相册写入权限', icon: 'none' });
      return;
    }
    wx.showLoading({ title: `正在保存 0/${selected.length}...` });
    let saved = 0;
    for (let i = 0; i < selected.length; i++) {
      wx.showLoading({ title: `正在保存 ${i + 1}/${selected.length}...` });
      try {
        await saveOneToAlbum(selected[i].wmPath);
        saved++;
      } catch (e) {}
    }
    wx.hideLoading();
    wx.showToast({ title: `已保存 ${saved}/${selected.length} 张至系统相册`, icon: 'none' });
  },

  // 多选操作 2：批量导出台账文本（复制到剪贴板）
  batchExportLedgerText() {
    const selected = this.getSelectedPhotos();
    if (selected.length === 0) {
      wx.showToast({ title: '请先勾选照片', icon: 'none' });
      return;
    }
    const lines = [`【极速高德水印相机 v1.0 · 批量外业台账 (${selected.length}条)】`];
    selected.forEach((p, idx) => {
      const c = p.cfg || {};
      const lockTag = c.antiTamperMode ? `🛡防伪(${(c.antiTamperHash || '').slice(0, 12)})` : '普通水印';
      lines.push(
        `${idx + 1}. [${c.fullTs || ''}] ${c.shortPoi || '实拍点位'} | GCJ-02:${c.gcjLat},${c.gcjLon} | WGS-84:${c.wgsLat},${c.wgsLon} | ${lockTag}${c.note ? ' | 备注:' + c.note : ''}`
      );
    });
    wx.setClipboardData({
      data: lines.join('\n'),
      success: () => {
        wx.showToast({ title: `已复制 ${selected.length} 条台账文本`, icon: 'success' });
      }
    });
  },

  // 多选操作 3：批量删除
  batchDeleteSelected() {
    const selected = this.getSelectedPhotos();
    if (selected.length === 0) {
      wx.showToast({ title: '请先勾选照片', icon: 'none' });
      return;
    }
    wx.showModal({
      title: `确认删除 ${selected.length} 张照片`,
      content: '删除后将从应用内相册永久移除所选照片及对应底图，不可恢复。',
      confirmColor: '#ef4444',
      confirmText: '确认删除',
      success: res => {
        if (!res.confirm) return;
        const ids = selected.map(p => p.id);
        ids.forEach(id => removeOfflinePhoto(id));
        deletePhotosByIds(ids);
        this.setData({ multiSelectMode: false });
        this.reloadGalleryData();
        wx.showToast({ title: `已删除 ${selected.length} 张照片`, icon: 'none' });
      }
    });
  },

  getVerifyFallbackCanvas() {
    return new Promise(resolve => {
      if (this._verifyCanvas) {
        resolve(this._verifyCanvas);
        return;
      }
      const query = wx.createSelectorQuery();
      query
        .select('#galleryVerifyCanvas')
        .fields({ node: true, size: true })
        .exec(res => {
          const node = res && res[0] && res[0].node;
          if (node) this._verifyCanvas = node;
          resolve(node || null);
        });
    });
  },

  async handleReconcileOffline() {
    if (this.data.isReconcilingOffline) return;
    const cnt = getOfflinePendingCount();
    if (cnt === 0) return;
    this.setData({ isReconcilingOffline: true });
    wx.showLoading({ title: `正在补签授时 (0/${cnt})...` });
    try {
      const fc = await this.getVerifyFallbackCanvas();
      const res = await reconcileOfflineQueue({
        fallbackCanvas: fc,
        onProgress: ({ current, total }) => {
          wx.showLoading({ title: `正在补签 (${current}/${total})...` });
        }
      });
      wx.hideLoading();
      this.reloadGalleryData();
      if (res.offline) {
        wx.showToast({ title: '当前无网络，联网后可自动补签', icon: 'none' });
      } else if (res.reconciled > 0) {
        wx.showToast({ title: `已完成 ${res.reconciled} 张离线照片补签`, icon: 'success' });
      } else {
        wx.showToast({ title: '暂无需补签项', icon: 'none' });
      }
    } catch (e) {
      wx.hideLoading();
      wx.showToast({ title: '补签失败，请稍后重试', icon: 'none' });
    } finally {
      this.setData({ isReconcilingOffline: false });
    }
  },

  noop() {}
});
