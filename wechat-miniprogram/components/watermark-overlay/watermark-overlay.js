/**
 * 极速高德水印相机 v9.0.0 · 实时水印预览独立组件 (<watermark-overlay>)
 * 承载 5 套水印模板的实时所见即所得渲染，1s 时钟刷新仅在组件内局部 setData，彻底避免主页面整页 Diff
 */

const {
  resolveDisplayCoordinates,
  formatCoords,
  getTimeStrings,
  computeAntiTamperHash,
  canSignAntiTamper
} = require('../../utils/coord_qr.js');

Component({
  properties: {
    active: { type: Boolean, value: true },
    styleTemplate: { type: Number, value: 0 },
    showBottomScrim: { type: Boolean, value: false },
    customTitle: { type: String, value: '现场点位核验' },
    coordSys: { type: String, value: 'WGS-84' },
    useDms: { type: Boolean, value: false },
    accentHex: { type: String, value: '#38bdf8' },
    fontScale: { type: Number, value: 1 },
    cardBgRgba: { type: String, value: 'rgba(11, 19, 43, 0.84)' },
    gcjLat: { type: Number, value: 39.908823 },
    gcjLon: { type: Number, value: 116.397470 },
    wgsLat: { type: Number, value: 39.907419 },
    wgsLon: { type: Number, value: 116.391228 },
    hasRealFix: { type: Boolean, value: false },
    accuracy: { type: String, value: '2.4' },
    altitude: { type: String, value: '46.5' },
    azimuthText: { type: String, value: '正北 000°' },
    shortPoi: { type: String, value: '实拍点位' },
    fullAddr: { type: String, value: '' },
    note: { type: String, value: '' },
    showQr: { type: Boolean, value: true },
    showMiniMap: { type: Boolean, value: true },
    miniMapPosition: { type: Number, value: 0 },
    bottomRight: { type: Boolean, value: false },
    antiTamperMode: { type: Boolean, value: false },
    ntpSynced: { type: Boolean, value: false },
    ntpOffsetMs: { type: Number, value: 0 },
    showTime: { type: Boolean, value: true },
    showLoc: { type: Boolean, value: true },
    showCoords: { type: Boolean, value: true },
    showCoordTag: { type: Boolean, value: true },
    showAccuracy: { type: Boolean, value: true },
    showAddress: { type: Boolean, value: false },
    showNote: { type: Boolean, value: true },
    showEnv: { type: Boolean, value: false }
  },

  data: {
    timeHm: '09:20',
    timeHms: '09:20:00',
    dateOnlyStr: '2026-11-24',
    weekStr: '星期二',
    dateWeek: '2026-11-24 星期二',
    fullTs: '2026-11-24 09:20:00',
    liveCoordOnlyStr: '39.907419°N, 116.391228°E',
    liveCoordStr: '39.907419°N, 116.391228°E [WGS-84] ±2.4m',
    liveAntiTamperShort: '待真实GPS定位'
  },

  lifetimes: {
    attached() {
      this._refreshAll();
      if (this.data.active) {
        this._startClock();
      }
    },
    detached() {
      this._stopClock();
    }
  },

  observers: {
    active(val) {
      if (val) {
        this._refreshClock(true);
        this._startClock();
      } else {
        this._stopClock();
      }
    },
    'coordSys, useDms, gcjLat, gcjLon, wgsLat, wgsLon, accuracy, showCoordTag, showAccuracy, shortPoi, note, antiTamperMode, hasRealFix, ntpOffsetMs'() {
      this._refreshAll();
    }
  },

  methods: {
    _startClock() {
      if (this._timer) return;
      this._timer = setInterval(() => {
        this._refreshClock(false);
      }, 1000);
    },

    _stopClock() {
      if (this._timer) {
        clearInterval(this._timer);
        this._timer = null;
      }
    },

    _computeSig(fullTs) {
      if (!canSignAntiTamper(this.data.hasRealFix, this.data.gcjLat, this.data.gcjLon)) {
        return '待真实GPS定位';
      }
      return computeAntiTamperHash(
        this.data.gcjLat,
        this.data.gcjLon,
        fullTs,
        this.data.shortPoi,
        this.data.note
      ).slice(0, 12);
    },

    _refreshAll() {
      const t = getTimeStrings(this.data.ntpOffsetMs);
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
      this.setData({
        timeHm: t.hm,
        timeHms: t.hms,
        dateOnlyStr: t.datePart,
        weekStr: t.weekStr,
        dateWeek: t.dateWeek,
        fullTs: t.fullTs,
        liveCoordOnlyStr: coordOnly,
        liveCoordStr: `${coordOnly}${tagPart}${accPart}`,
        liveAntiTamperShort: this._computeSig(t.fullTs)
      });
    },

    _refreshClock(force) {
      if (!this.data.active && !force) return;
      const t = getTimeStrings(this.data.ntpOffsetMs);
      const patch = {};
      if (t.hm !== this.data.timeHm) patch.timeHm = t.hm;
      if (t.datePart !== this.data.dateOnlyStr) patch.dateOnlyStr = t.datePart;
      if (t.weekStr !== this.data.weekStr) patch.weekStr = t.weekStr;
      if (t.dateWeek !== this.data.dateWeek) patch.dateWeek = t.dateWeek;
      if (force || this.data.styleTemplate !== 0 || this.data.antiTamperMode) {
        if (t.hms !== this.data.timeHms) patch.timeHms = t.hms;
        if (t.fullTs !== this.data.fullTs) patch.fullTs = t.fullTs;
        if (this.data.antiTamperMode) {
          patch.liveAntiTamperShort = this._computeSig(t.fullTs);
        }
      }
      if (Object.keys(patch).length > 0) {
        this.setData(patch);
      }
    },

    onTapWatermark() {
      this.triggerEvent('tapwatermark');
    },

    onTapPoi() {
      this.triggerEvent('tappoi');
    },

    onTapMap() {
      this.triggerEvent('tapmap');
    }
  }
});
