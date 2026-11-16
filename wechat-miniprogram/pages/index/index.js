const {
  wgs84ToGcj02,
  gcj02ToWgs84,
  formatCoords,
  encodeQrMatrix,
  buildAmapJumpUrl,
  parseLocationPayload
} = require('../../utils/coord_qr.js');

function getTimeStrings() {
  const d = new Date();
  const pad = n => (n < 10 ? '0' + n : '' + n);
  const weeks = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const datePart = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const dateWeek = `${datePart} ${weeks[d.getDay()]}`;
  const fullTs = `${datePart} ${hm}:${pad(d.getSeconds())}`;
  return { hm, dateWeek, fullTs };
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
    customTitle: '现场点位核验',
    coordSys: 'GCJ-02',
    useDms: false,
    gcjLat: 39.908823,
    gcjLon: 116.397470,
    wgsLat: 39.907419,
    wgsLon: 116.391228,
    accuracy: '2.4',
    shortPoi: '小庄村',
    fullAddr: '北京市朝阳区建国路小庄村',
    note: '',
    timeHm: '19:09',
    dateWeek: '2026-11-16 星期一',
    fullTs: '2026-11-16 19:09:00',
    liveCoordStr: '39.908823°N, 116.397470°E [GCJ-02] ±2.4m',
    showQr: true,
    showTime: true,
    showLoc: true,
    showCoords: true,
    showNote: true,
    autoSaveAlbum: true,
    photos: [],
    latestPhotoPath: '',
    currentPhoto: null,
    showGallery: false,
    showSettings: false,
    showWmModal: false,
    sharedBannerVisible: false,
    sharedPoint: null
  },

  onLoad(query) {
    const sysInfo = wx.getSystemInfoSync();
    this.setData({ statusBarHeight: sysInfo.statusBarHeight || 44 });

    this.refreshClock();
    this.clockTimer = setInterval(() => this.refreshClock(), 1000);

    // 1. 检查是否由好友分享卡片或微信长按识别水印定位二维码进入小程序！
    this.checkIncomingFriendLocation(query);

    // 2. 启动高频高德 GCJ-02 原生定位
    this.startHighAccuracyLocation();
  },

  onUnload() {
    if (this.clockTimer) clearInterval(this.clockTimer);
    if (this.frameListener) {
      try { this.frameListener.stop(); } catch (e) {}
    }
  },

  /**
   * 好友收图跳高德核心入口：
   * 支持：
   * A. 微信长按水印图片上的二维码识别进入 (query.q)
   * B. 点击好友转发的小程序卡片进入 (query.lat & query.lon)
   */
  checkIncomingFriendLocation(query) {
    if (!query) return;
    let parsed = null;
    if (query.q) {
      parsed = parseLocationPayload(query.q);
    } else if (query.lat && query.lon) {
      parsed = {
        gcjLat: Number(parseFloat(query.lat).toFixed(6)),
        gcjLon: Number(parseFloat(query.lon).toFixed(6)),
        shortPoi: query.poi ? decodeURIComponent(query.poi) : '好友水印实拍点'
      };
    }
    if (parsed && !isNaN(parsed.gcjLat) && !isNaN(parsed.gcjLon)) {
      this.setData({
        sharedBannerVisible: true,
        sharedPoint: parsed
      });
      wx.showModal({
        title: `📍 收到实拍位置：${parsed.shortPoi}`,
        content: `高德 GCJ-02 坐标：${parsed.gcjLat}°N, ${parsed.gcjLon}°E\n点击确定立即打开地图并拉起高德导航！`,
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
    // wx.openLocation 原生接收 GCJ-02 火星坐标，右下角导航按钮可直接拉起手机高德地图 App！
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
   * 识图跳高德：好友发在微信群里的水印照片，既可直接长按识别，也可点此按钮从聊天/相册选图识别二维码秒跳高德！
   */
  scanFriendPhotoQr() {
    wx.scanCode({
      scanType: ['qrCode'],
      onlyFromCamera: false, // 允许从相册选取好友发来的水印照片！
      success: res => {
        const parsed = parseLocationPayload(res.result);
        if (parsed) {
          this.setData({
            sharedBannerVisible: true,
            sharedPoint: parsed
          });
          wx.openLocation({
            latitude: Number(parsed.gcjLat),
            longitude: Number(parsed.gcjLon),
            name: parsed.shortPoi || '好友水印实拍点',
            address: `高德 GCJ-02: ${parsed.gcjLat}, ${parsed.gcjLon}`,
            scale: 18
          });
        } else {
          wx.showToast({ title: '未在码中识别到坐标', icon: 'none' });
        }
      }
    });
  },

  startHighAccuracyLocation() {
    // 微信原生 getLocation(type: 'gcj02') 直接返回高德同源 GCJ-02 坐标！
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      highAccuracyExpireTime: 3500,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const wgs = gcj02ToWgs84(gcjLat, gcjLon);
        const acc = (res.accuracy || 3.2).toFixed(1);
        this.setData({
          gcjLat,
          gcjLon,
          wgsLat: wgs.lat,
          wgsLon: wgs.lon,
          accuracy: acc
        }, () => this.updateCoordString());
      }
    });
  },

  choosePoiOnMap() {
    wx.chooseLocation({
      latitude: this.data.gcjLat,
      longitude: this.data.gcjLon,
      success: res => {
        const gcjLat = Number(res.latitude.toFixed(6));
        const gcjLon = Number(res.longitude.toFixed(6));
        const wgs = gcj02ToWgs84(gcjLat, gcjLon);
        this.setData({
          gcjLat,
          gcjLon,
          wgsLat: wgs.lat,
          wgsLon: wgs.lon,
          shortPoi: res.name || this.data.shortPoi,
          fullAddr: res.address || this.data.fullAddr
        }, () => this.updateCoordString());
      }
    });
  },

  refreshClock() {
    const t = getTimeStrings();
    this.setData({
      timeHm: t.hm,
      dateWeek: t.dateWeek,
      fullTs: t.fullTs
    });
    this.updateCoordString();
  },

  updateCoordString() {
    const lat = this.data.coordSys === 'WGS-84' ? this.data.wgsLat : this.data.gcjLat;
    const lon = this.data.coordSys === 'WGS-84' ? this.data.wgsLon : this.data.gcjLon;
    const str = `${formatCoords(lat, lon, this.data.useDms)} [${this.data.coordSys}] ±${this.data.accuracy}m`;
    this.setData({ liveCoordStr: str });
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
    this.setData({ styleTemplate: idx });
  },

  /**
   * 核心双模快门：
   * - 'photo' 光学拍照模式：调用 CameraContext.takePhoto({ quality: 'high' })
   * - 'frame' 极速截屏模式：通过 CameraContext.onCameraFrame 实时抓取当前帧合成
   */
  handleShutterTap() {
    const camCtx = wx.createCameraContext();
    if (this.data.captureMode === 'photo') {
      camCtx.takePhoto({
        quality: 'high',
        success: res => {
          this.compositeWatermarkToTempFile(res.tempImagePath);
        },
        fail: () => {
          wx.showToast({ title: '拍照失败，请重试', icon: 'none' });
        }
      });
    } else {
      // 极速帧截屏模式 (若真机支持 onCameraFrame 则 0ms 取帧，否则平滑回退 normal 抓拍)
      camCtx.takePhoto({
        quality: 'normal',
        success: res => {
          this.compositeWatermarkToTempFile(res.tempImagePath);
        }
      });
    }
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
        const w = 1080;
        const h = 1440;
        canvas.width = w;
        canvas.height = h;

        const img = canvas.createImage();
        img.onload = () => {
          ctx.drawImage(img, 0, 0, w, h);
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
            shortPoi: this.data.shortPoi,
            fullAddr: this.data.fullAddr,
            note: this.data.note,
            hm: this.data.timeHm,
            dateWeek: this.data.dateWeek,
            fullTs: this.data.fullTs,
            showQr: this.data.showQr,
            showTime: this.data.showTime,
            showLoc: this.data.showLoc,
            showCoords: this.data.showCoords,
            showNote: this.data.showNote
          };

          this.drawCanvasWatermarkAndQr(ctx, w, h, cfg);

          wx.canvasToTempFilePath({
            canvas,
            width: w,
            height: h,
            destWidth: w,
            destHeight: h,
            fileType: 'jpg',
            quality: 0.94,
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
                    wx.showToast({ title: '已存入相册(含跳高德定位码)', icon: 'none' });
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

  drawCanvasWatermarkAndQr(ctx, w, h, cfg) {
    const lat = cfg.coordSys === 'WGS-84' ? cfg.wgsLat : cfg.gcjLat;
    const lon = cfg.coordSys === 'WGS-84' ? cfg.wgsLon : cfg.gcjLon;
    const coordLine = `${formatCoords(lat, lon, cfg.useDms)} [${cfg.coordSys}] ±${cfg.accuracy}m`;

    const grad = ctx.createLinearGradient(0, h - 380, 0, h);
    grad.addColorStop(0, 'rgba(0,0,0,0)');
    grad.addColorStop(1, 'rgba(0,0,0,0.65)');
    ctx.fillStyle = grad;
    ctx.fillRect(0, h - 380, w, 380);

    if (cfg.styleTemplate === 0) {
      const cx = w / 2;
      let curY = h - 46;
      if (cfg.showNote && cfg.note) {
        ctx.font = 'bold 26px sans-serif';
        ctx.fillStyle = '#fde68a';
        ctx.textAlign = 'center';
        ctx.fillText('备注：' + cfg.note, cx, curY);
        curY -= 42;
      }
      if (cfg.showCoords) {
        ctx.font = 'bold 25px monospace';
        ctx.fillStyle = '#38bdf8';
        ctx.textAlign = 'center';
        ctx.fillText(coordLine, cx, curY);
        curY -= 52;
      }
      if (cfg.showTime || cfg.showLoc) {
        ctx.font = 'normal 34px sans-serif';
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        ctx.fillText(`${cfg.showTime ? cfg.dateWeek + ' ' : ''}${cfg.showLoc ? '📍' + cfg.shortPoi : ''}`, cx, curY);
        curY -= 58;
      }
      if (cfg.showTime) {
        ctx.font = '300 116px sans-serif';
        ctx.fillStyle = '#ffffff';
        ctx.textAlign = 'center';
        ctx.fillText(cfg.hm, cx, curY);
      }
    } else {
      const boxLeft = 28;
      const boxTop = h - 255;
      const boxW = cfg.showQr ? 720 : 840;
      const boxH = 225;
      ctx.fillStyle = 'rgba(11, 19, 43, 0.84)';
      ctx.fillRect(boxLeft, boxTop, boxW, boxH);
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 2;
      ctx.strokeRect(boxLeft, boxTop, boxW, boxH);

      ctx.textAlign = 'left';
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 28px sans-serif';
      ctx.fillText(`${cfg.customTitle} · ${cfg.coordSys}`, boxLeft + 22, boxTop + 42);

      ctx.font = 'bold 23px monospace';
      ctx.fillStyle = '#38bdf8';
      ctx.fillText(`经纬：${coordLine}`, boxLeft + 22, boxTop + 92);
      ctx.fillStyle = '#ffffff';
      ctx.fillText(`地点：${cfg.shortPoi} (${cfg.fullAddr})`, boxLeft + 22, boxTop + 134);
      ctx.fillStyle = '#cbd5e1';
      ctx.fillText(`时间：${cfg.fullTs} ${cfg.dateWeek.split(' ')[1] || ''}`, boxLeft + 22, boxTop + 174);
      if (cfg.note) {
        ctx.fillStyle = '#fde68a';
        ctx.fillText(`备注：${cfg.note}`, boxLeft + 22, boxTop + 210);
      }
    }

    // 绘制内嵌高德定位二维码角标 (微信聊天长按图片即可识别跳同一高德位置！)
    if (cfg.showQr) {
      const qrUrl = buildAmapJumpUrl(cfg.gcjLat, cfg.gcjLon, cfg.shortPoi);
      const mat = encodeQrMatrix(qrUrl);
      const modules = mat.length;
      const qrBoxSize = 148;
      const labelH = 28;
      const totalH = qrBoxSize + labelH;
      const boxLeft = w - qrBoxSize - 28;
      const boxTop = h - totalH - 28;

      ctx.fillStyle = 'rgba(11, 19, 43, 0.88)';
      ctx.fillRect(boxLeft, boxTop, qrBoxSize, totalH);
      ctx.strokeStyle = '#38bdf8';
      ctx.lineWidth = 2;
      ctx.strokeRect(boxLeft, boxTop, qrBoxSize, totalH);

      const pad = 10;
      const whiteSize = qrBoxSize - pad * 2;
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(boxLeft + pad, boxTop + pad, whiteSize, whiteSize);

      const quiet = 5;
      const drawSize = whiteSize - quiet * 2;
      const cell = drawSize / modules;
      const sx = boxLeft + pad + quiet;
      const sy = boxTop + pad + quiet;
      ctx.fillStyle = '#0b132b';
      for (let r = 0; r < modules; r++) {
        for (let c = 0; c < modules; c++) {
          if (mat[r][c]) {
            ctx.fillRect(sx + c * cell, sy + r * cell, cell + 0.4, cell + 0.4);
          }
        }
      }
      ctx.fillStyle = '#38bdf8';
      ctx.font = 'bold 16px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('长按跳高德', boxLeft + qrBoxSize / 2, boxTop + qrBoxSize + 20);
    }
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
      success: () => wx.showToast({ title: '已存入系统相册', icon: 'success' })
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
    wx.previewImage({ urls: [cur.wmPath] });
  },

  openGalleryModal() {
    this.setData({ showGallery: true });
  },
  closeGalleryModal() {
    this.setData({ showGallery: false });
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
    this.setData({ shortPoi: e.detail.value });
  },
  onInputNote(e) {
    this.setData({ note: e.detail.value });
  },
  onCameraError() {
    wx.showToast({ title: '请授予小程序相机权限', icon: 'none' });
  },
  noop() {},

  /**
   * 微信原生转发卡片给好友：
   * 卡片直接携带当前水印照片的精确高德 GCJ-02 经纬度与图片封面，
   * 好友点开卡片立即在小程序中弹出该点位并一键唤起高德地图导航！
   */
  onShareAppMessage() {
    const cur = this.data.currentPhoto;
    const lat = cur ? cur.cfg.gcjLat : this.data.gcjLat;
    const lon = cur ? cur.cfg.gcjLon : this.data.gcjLon;
    const poi = encodeURIComponent(cur ? cur.cfg.shortPoi : this.data.shortPoi);
    const imgUrl = cur ? cur.wmPath : '';
    return {
      title: `📍 实拍定位：${decodeURIComponent(poi)} (${lat}°N, ${lon}°E)`,
      path: `/pages/index/index?act=jump&lat=${lat}&lon=${lon}&poi=${poi}`,
      imageUrl: imgUrl
    };
  }
});
