/**
 * 极速高德水印相机 v1.0 · 独立相册分包 · 照片详情与无损重绘页 (packageAlbum/pages/detail)
 * 特性：
 * - 全屏 <swiper> 左右滑切，点击大图调用 wx.previewImage 双指缩放
 * - 底部极简悬浮 4 键栏：导航 | 转发 | 改水印/已锁 | 详情
 * - 半屏详情抽屉：双坐标系一键复制、SHA-256 验真报告、保存相册与删除
 * - 独立无损重绘编辑器：使用深拷贝 editDraft，绝不污染全局相机配置
 */

const {
  gcj02ToWgs84,
  resolveDisplayCoordinates,
  formatAzimuthText,
  computeAntiTamperHash,
  verifyAntiTamperHash
} = require('../../../utils/coord_qr.js');

const {
  buildVerificationReport,
  formatVerifyReportText
} = require('../../../utils/photo_verifier.js');

const { renderWatermarkToTempFile } = require('../../../utils/watermark_renderer.js');

const {
  loadPhotos,
  updatePhotoEntry,
  deletePhotosByIds,
  copyTempToUserData,
  safeUnlinkFile
} = require('../../../utils/photo_store.js');

const { saveImageToAlbumWithPermission } = require('../../../utils/privacy.js');
const { removeOfflinePhoto } = require('../../../utils/offline_queue.js');

const TPL_NAMES = ['大字时间', '巡检卡片', '网格测绘', '胶片底栏', '标准台账'];

Page({
  data: {
    menuBtnTop: 48,
    menuBtnHeight: 32,
    menuBtnRightSpace: 104,
    tplNames: TPL_NAMES,
    photos: [],
    currentIndex: 0,
    currentPhoto: null,
    verifyReport: null,
    showDetailDrawer: false,
    showEditDrawer: false,
    editDraft: null
  },

  onLoad(query) {
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

    const photos = loadPhotos();
    const targetId = query && query.id ? decodeURIComponent(query.id) : '';
    let idx = 0;
    if (targetId) {
      const foundIdx = photos.findIndex(p => p && p.id === targetId);
      if (foundIdx >= 0) idx = foundIdx;
    }

    const currentPhoto = photos[idx] || null;
    this.setData({
      menuBtnTop,
      menuBtnHeight,
      menuBtnRightSpace,
      photos,
      currentIndex: idx,
      currentPhoto,
      verifyReport: this.computePhotoReport(currentPhoto)
    });
  },

  computePhotoReport(photo) {
    if (!photo || !photo.cfg) return null;
    const c = photo.cfg;
    const sig = c.antiTamperHash ? String(c.antiTamperHash).slice(0, 12) : '';
    const isVerified = c.antiTamperMode
      ? verifyAntiTamperHash(c.gcjLat, c.gcjLon, c.fullTs, c.shortPoi, sig, c.note)
      : false;
    return buildVerificationReport(
      {
        gcjLat: c.gcjLat,
        gcjLon: c.gcjLon,
        shortPoi: c.shortPoi,
        timestamp: c.fullTs,
        note: c.note,
        signature: sig,
        ntpSynced: c.ntpSynced,
        hasAntiTamper: Boolean(c.antiTamperMode),
        verified: isVerified
      },
      photo.wmPath,
      '本机水印相册原片核验'
    );
  },

  goBack() {
    wx.navigateBack();
  },

  onSwiperChange(e) {
    const idx = Number(e.detail.current) || 0;
    const currentPhoto = this.data.photos[idx] || null;
    this.setData({
      currentIndex: idx,
      currentPhoto,
      verifyReport: this.computePhotoReport(currentPhoto),
      showDetailDrawer: false,
      showEditDrawer: false
    });
  },

  previewCurrentFullImage() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.wmPath) return;
    const urls = this.data.photos.map(p => p.wmPath).filter(Boolean);
    wx.previewImage({
      current: cur.wmPath,
      urls: urls.length > 0 ? urls : [cur.wmPath]
    });
  },

  navigateCurrentPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    wx.openLocation({
      latitude: Number(cur.cfg.gcjLat),
      longitude: Number(cur.cfg.gcjLon),
      name: cur.cfg.shortPoi || '实拍点位',
      address: cur.cfg.fullAddr || `高德 GCJ-02: ${cur.cfg.gcjLat}, ${cur.cfg.gcjLon}`,
      scale: 18
    });
  },

  openDetailDrawer() {
    this.setData({ showEditDrawer: false, showDetailDrawer: true });
  },

  closeDetailDrawer() {
    this.setData({ showDetailDrawer: false });
  },

  copyCurrentCoord() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    const c = cur.cfg;
    const resolved = resolveDisplayCoordinates(c.coordSys, c.gcjLat, c.gcjLon, c.wgsLat, c.wgsLon);
    const txt = `${resolved.lon.toFixed(6)},${resolved.lat.toFixed(6)} [${resolved.tag}] (高德GCJ-02: ${Number(c.gcjLon).toFixed(6)},${Number(c.gcjLat).toFixed(6)})`;
    wx.setClipboardData({ data: txt });
  },

  copyVerifyReport() {
    const text = formatVerifyReportText(this.data.verifyReport);
    if (!text) return;
    wx.setClipboardData({ data: text });
  },

  saveCurrentToAlbum() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.wmPath) return;
    saveImageToAlbumWithPermission(cur.wmPath, '已存入系统相册', false).catch(() => {
      wx.showToast({ title: '保存失败，请检查相册权限', icon: 'none' });
    });
  },

  deleteCurrentPhoto() {
    const cur = this.data.currentPhoto;
    if (!cur) return;
    wx.showModal({
      title: '确认删除照片',
      content: '删除后将从应用内相册永久移除该照片及对应底图，是否继续？',
      confirmColor: '#ef4444',
      confirmText: '确认删除',
      success: res => {
        if (!res.confirm) return;
        removeOfflinePhoto(cur.id);
        const remaining = deletePhotosByIds([cur.id]);
        if (remaining.length === 0) {
          wx.navigateBack();
          return;
        }
        const nextIdx = Math.min(this.data.currentIndex, remaining.length - 1);
        const nextPhoto = remaining[nextIdx];
        this.setData({
          photos: remaining,
          currentIndex: nextIdx,
          currentPhoto: nextPhoto,
          verifyReport: this.computePhotoReport(nextPhoto),
          showDetailDrawer: false,
          showEditDrawer: false
        });
        wx.showToast({ title: '已删除该照片', icon: 'none' });
      }
    });
  },

  // ==================== 独立无损重绘（深拷贝草稿，绝不污染全局相机配置） ====================
  openEditDrawer() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    if (cur.cfg.antiTamperMode) {
      wx.showModal({
        title: '🛡 防篡改原片已锁定',
        content: '该照片在拍摄时已开启「外业防篡改验真模式」，真实 GPS 坐标、授时时间、点位、备注与 SHA-256 签名均已锁定，不允许二次修改。',
        showCancel: false,
        confirmText: '我知道了'
      });
      return;
    }
    if (!cur.rawPath) {
      wx.showModal({
        title: '无损底图已回收',
        content: '该照片的无损底图已超出最近 25 张保留窗口或已被手动清理，当前仅保留高清水印成品，暂不支持无损重绘。',
        showCancel: false,
        confirmText: '我知道了'
      });
      return;
    }

    const cloned = JSON.parse(JSON.stringify(cur.cfg));
    const nowObj = new Date();
    const pad2 = n => (n < 10 ? '0' + n : '' + n);
    const fallbackDate = `${nowObj.getFullYear()}-${pad2(nowObj.getMonth() + 1)}-${pad2(nowObj.getDate())}`;
    const datePart = cloned.dateOnlyStr || (cloned.fullTs ? cloned.fullTs.slice(0, 10) : fallbackDate);
    const hmPart = cloned.hm || (cloned.fullTs ? cloned.fullTs.slice(11, 16) : '09:20');
    const secPart =
      cloned.hms && cloned.hms.length >= 8
        ? cloned.hms.slice(6, 8)
        : cloned.fullTs && cloned.fullTs.length >= 19
          ? cloned.fullTs.slice(17, 19)
          : '00';

    const editDraft = {
      ...cloned,
      editDatePart: datePart,
      editTimeHm: hmPart,
      editTimeSec: secPart,
      editGcjLat: String(cloned.gcjLat),
      editGcjLon: String(cloned.gcjLon),
      editAltitude: String(cloned.altitude || ''),
      editAzimuthDeg: String(cloned.azimuthDeg || 0),
      editAccuracy: String(cloned.accuracy || ''),
      showBottomScrim: Boolean(cloned.showBottomScrim)
    };

    this.setData({
      showDetailDrawer: false,
      showEditDrawer: true,
      editDraft
    });
  },

  closeEditDrawer() {
    this.setData({ showEditDrawer: false, editDraft: null });
  },

  onDraftFieldInput(e) {
    const field = e.currentTarget.dataset.field;
    if (!field || !this.data.editDraft) return;
    this.setData({ [`editDraft.${field}`]: e.detail.value });
  },

  onDraftSelectTemplate(e) {
    const idx = Number(e.currentTarget.dataset.idx) || 0;
    this.setData({ 'editDraft.styleTemplate': idx });
  },

  onDraftSelectCoordSys(e) {
    const sys = e.currentTarget.dataset.sys;
    if (!sys) return;
    this.setData({ 'editDraft.coordSys': sys });
  },

  onDraftToggleBool(e) {
    const field = e.currentTarget.dataset.field;
    if (!field || !this.data.editDraft) return;
    const val = e.detail && typeof e.detail.value === 'boolean' ? e.detail.value : !this.data.editDraft[field];
    this.setData({ [`editDraft.${field}`]: val });
  },

  chooseDraftCoordOnMap() {
    const d = this.data.editDraft;
    if (!d) return;
    wx.chooseLocation({
      latitude: parseFloat(d.editGcjLat) || Number(d.gcjLat) || 39.908823,
      longitude: parseFloat(d.editGcjLon) || Number(d.gcjLon) || 116.39747,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const patch = {
          'editDraft.editGcjLat': String(gcjLat),
          'editDraft.editGcjLon': String(gcjLon)
        };
        if (res.name) patch['editDraft.shortPoi'] = res.name;
        if (res.address) {
          patch['editDraft.fullAddr'] = res.address;
          patch['editDraft.showAddress'] = true;
        }
        this.setData(patch);
        wx.showToast({ title: '已填入地图坐标', icon: 'none' });
      }
    });
  },

  restoreDraftOrigCoord() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) return;
    const origLat = cur.cfg.origGcjLat !== undefined ? cur.cfg.origGcjLat : cur.cfg.gcjLat;
    const origLon = cur.cfg.origGcjLon !== undefined ? cur.cfg.origGcjLon : cur.cfg.gcjLon;
    this.setData({
      'editDraft.editGcjLat': String(origLat),
      'editDraft.editGcjLon': String(origLon)
    });
    wx.showToast({ title: '已恢复拍摄瞬间原始坐标', icon: 'none' });
  },

  getFallbackCanvasNode() {
    return new Promise(resolve => {
      if (this._fallbackCanvas) {
        resolve(this._fallbackCanvas);
        return;
      }
      const query = wx.createSelectorQuery();
      query
        .select('#detailFallbackCanvas')
        .fields({ node: true, size: true })
        .exec(res => {
          const node = res && res[0] && res[0].node;
          if (node) this._fallbackCanvas = node;
          resolve(node || null);
        });
    });
  },

  async confirmRedrawWatermark() {
    const cur = this.data.currentPhoto;
    const d = this.data.editDraft;
    if (!cur || !d || !cur.rawPath) return;
    if (cur.cfg && cur.cfg.antiTamperMode) {
      wx.showToast({ title: '🛡 防篡改原片禁止修改', icon: 'none' });
      return;
    }



    const rawLat = parseFloat(d.editGcjLat);
    const rawLon = parseFloat(d.editGcjLon);
    const newGcjLat = !isNaN(rawLat) ? Number(rawLat.toFixed(6)) : cur.cfg.gcjLat;
    const newGcjLon = !isNaN(rawLon) ? Number(rawLon.toFixed(6)) : cur.cfg.gcjLon;
    const newWgs = gcj02ToWgs84(newGcjLat, newGcjLon);

    const nowObj2 = new Date();
    const pad2b = n => (n < 10 ? '0' + n : '' + n);
    const todayStr = `${nowObj2.getFullYear()}-${pad2b(nowObj2.getMonth() + 1)}-${pad2b(nowObj2.getDate())}`;
    const dateStr = (d.editDatePart || cur.cfg.dateOnlyStr || todayStr).trim();
    const hmStr = (d.editTimeHm || cur.cfg.hm || '09:20').trim();
    const secNum = Math.min(59, Math.max(0, parseInt(d.editTimeSec, 10) || 0));
    const secStr = secNum < 10 ? '0' + secNum : '' + secNum;
    const parsedDate = new Date(`${dateStr.replace(/-/g, '/')} ${hmStr}:${secStr}`);
    const weeks = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    const weekStr = isNaN(parsedDate.getTime()) ? cur.cfg.weekStr || '星期二' : weeks[parsedDate.getDay()];
    const hmsStr = `${hmStr}:${secStr}`;
    const fullTsStr = `${dateStr} ${hmsStr}`;
    const dateWeekStr = `${dateStr} ${weekStr}`;

    const azDeg = ((Math.round(Number(d.editAzimuthDeg) || 0) % 360) + 360) % 360;
    const updatedCfg = {
      ...cur.cfg,
      antiTamperMode: false,
      antiTamperHash: '',
      styleTemplate: d.styleTemplate,
      showBottomScrim: Boolean(d.showBottomScrim),
      coordSys: d.coordSys || 'WGS-84',
      useDms: Boolean(d.useDms),
      customTitle: d.customTitle || '现场点位核验',
      shortPoi: d.shortPoi || '实拍点位',
      fullAddr: d.fullAddr || '',
      note: d.note || '',
      hasRealFix: Boolean(cur.cfg.hasRealFix || (newGcjLat !== 0 || newGcjLon !== 0)),
      isManualCoord: true,
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
      altitude: String(d.editAltitude !== undefined ? d.editAltitude : (cur.cfg.altitude || '')),
      azimuthDeg: azDeg,
      azimuthText: formatAzimuthText(azDeg),
      accuracy: String(d.editAccuracy !== undefined ? d.editAccuracy : (cur.cfg.accuracy || '')),
      showAddress: Boolean(d.showAddress),
      showEnv: Boolean(d.showEnv),
      showQr: d.showQr !== false,
      bottomRight: Boolean(d.bottomRight)
    };

    wx.showLoading({ title: '无损重绘高清水印...' });
    try {
      const fallbackCanvas = await this.getFallbackCanvasNode();
      const out = await renderWatermarkToTempFile(cur.rawPath, updatedCfg, {
        fallbackCanvas,
        generateThumb: true
      });
      const newWmPath = await copyTempToUserData(out.tempFilePath, 'amap_wm');
      const newThumbPath = out.thumbTempFilePath
        ? await copyTempToUserData(out.thumbTempFilePath, 'amap_thumb')
        : newWmPath;

      const oldWmPath = cur.wmPath;
      const oldThumbPath = cur.thumbPath;

      const updatedList = updatePhotoEntry(cur.id, item => ({
        ...item,
        wmPath: newWmPath,
        thumbPath: newThumbPath,
        cfg: updatedCfg
      }));

      if (oldWmPath && oldWmPath !== newWmPath) safeUnlinkFile(oldWmPath);
      if (oldThumbPath && oldThumbPath !== newThumbPath && oldThumbPath !== oldWmPath) {
        safeUnlinkFile(oldThumbPath);
      }

      const nextPhoto = updatedList[this.data.currentIndex] || updatedList[0];
      wx.hideLoading();
      this.setData({
        photos: updatedList,
        currentPhoto: nextPhoto,
        verifyReport: this.computePhotoReport(nextPhoto),
        showEditDrawer: false,
        editDraft: null
      });
      wx.showToast({ title: '已无损更新照片水印', icon: 'success' });
    } catch (err) {
      wx.hideLoading();
      wx.showToast({ title: (err && err.message) || '重绘失败，请重试', icon: 'none' });
    }
  },

  noop() {},

  onShareAppMessage() {
    const cur = this.data.currentPhoto;
    if (!cur || !cur.cfg) {
      return {
        title: '极速高德水印相机 v1.0',
        path: '/pages/index/index'
      };
    }
    const c = cur.cfg;
    const isAnti = Boolean(c.antiTamperMode && c.antiTamperHash);
    const poi = encodeURIComponent(c.shortPoi || '实拍点位');
    const ts = encodeURIComponent(c.fullTs || '');
    const note = encodeURIComponent(c.note || '');
    const sig = isAnti
      ? String(c.antiTamperHash || computeAntiTamperHash(c.gcjLat, c.gcjLon, c.fullTs, c.shortPoi, c.note)).slice(0, 12)
      : '';
    const ntpFlag = c.ntpSynced ? '1' : '0';
    const extraParam = `&ntp=${ntpFlag}${note ? `&note=${note}` : ''}${sig ? `&sig=${sig}` : ''}`;
    return {
      title: `实拍定位：${c.shortPoi || '实拍点位'} (${c.gcjLat}°N, ${c.gcjLon}°E)`,
      path: `/pages/index/index?act=jump&lat=${c.gcjLat}&lon=${c.gcjLon}&poi=${poi}&ts=${ts}${extraParam}`,
      imageUrl: cur.thumbPath || cur.wmPath || ''
    };
  }
});
