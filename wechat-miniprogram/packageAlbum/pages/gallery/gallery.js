/**
 * 极速高德水印相机 v2.0 · 独立相册分包 · 网格相册页 (packageAlbum/pages/gallery)
 * - 仅加载 240px 缩略图 thumbPath + lazy-load
 * - 顶部系统相册批量加水印入口 + 离线补签横幅 + 存储空间进度条
 */

const {
  loadPhotos,
  deletePhotosByIds,
  clearAllRawOriginals,
  estimateStorageUsage
} = require('../../../utils/photo_store.js');

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
      noWatermark: Boolean(cfg.noWatermark),
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
    isReconcilingOffline: false
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

  openSystemAlbumBatch() {
    wx.navigateTo({
      url: '/packageBatch/pages/batch/batch?autoPick=1'
    });
  },

  handleClearRawOriginals() {
    if (!this.data.storage || this.data.storage.rawCount === 0) {
      wx.showToast({ title: '当前没有可清理的底图', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '清理无损底图',
      content: `将清理 ${this.data.storage.rawCount} 张可编辑无损底图以释放空间，已生成的高清成品图与缩略图仍会完整保留。`,
      confirmColor: '#2563eb',
      success: res => {
        if (!res.confirm) return;
        const cleared = clearAllRawOriginals();
        this.reloadGalleryData();
        wx.showToast({ title: `已清理 ${cleared} 张无损底图`, icon: 'success' });
      }
    });
  },

  getSelectedPhotos() {
    const map = this.data.selectedMap || {};
    return (this._allPhotos || []).filter(p => p && map[p.id]);
  },

  async batchSaveToSystemAlbum() {
    const selected = this.getSelectedPhotos();
    if (selected.length === 0) {
      wx.showToast({ title: '请先勾选照片', icon: 'none' });
      return;
    }
    const granted = await ensureAlbumWritePermission(true);
    if (!granted) {
      wx.showToast({ title: '请先授权相册写入权限', icon: 'none' });
      return;
    }
    wx.showLoading({ title: `正在保存 0/${selected.length}...` });
    let ok = 0;
    for (let i = 0; i < selected.length; i++) {
      wx.showLoading({ title: `正在保存 ${i + 1}/${selected.length}...` });
      try {
        if (selected[i].wmPath) {
          await saveOneToAlbum(selected[i].wmPath);
          ok++;
        }
      } catch (e) {}
    }
    wx.hideLoading();
    wx.showToast({ title: `成功保存 ${ok} 张至系统相册`, icon: 'success' });
  },

  batchExportLedgerText() {
    const selected = this.getSelectedPhotos();
    if (selected.length === 0) {
      wx.showToast({ title: '请先勾选照片', icon: 'none' });
      return;
    }
    const lines = [`【极速高德水印相机 v2.0 · 批量外业台账 (${selected.length}条)】`];
    selected.forEach((p, idx) => {
      const c = p.cfg || {};
      const lockTag = c.antiTamperMode
        ? `防伪(${(c.antiTamperHash || '').slice(0, 12)})`
        : (c.noWatermark ? '无水印原图' : '普通水印');
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

  getOfflineFallbackCanvas() {
    return new Promise(resolve => {
      if (this._offlineCanvas) {
        resolve(this._offlineCanvas);
        return;
      }
      const query = wx.createSelectorQuery();
      query
        .select('#galleryOfflineCanvas')
        .fields({ node: true, size: true })
        .exec(res => {
          const node = res && res[0] && res[0].node;
          if (node) this._offlineCanvas = node;
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
      const fc = await this.getOfflineFallbackCanvas();
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
