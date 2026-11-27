/**
 * 极速高德水印相机 v1.0 · 实时水印预览独立组件 (<watermark-overlay>)
 * - 彻底移除 160px 假小图，拓宽卡片与网格排版宽度
 * - 未开启防伪模式时跳过无意义 SHA-256 计算
 * - 无真实定位或无真实海拔时优雅降级，绝不展示假天安门坐标或假 46.5m
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
    gcjLat: { type: Number, value: 0 },
    gcjLon: { type: Number, value: 0 },
    wgsLat: { type: Number, value: 0 },
    wgsLon: { type: Number, value: 0 },
    hasRealFix: { type: Boolean, value: false },
    isManualCoord: { type: Boolean, value: false },
    accuracy: { type: String, value: '' },
    altitude: { type: String, value: '' },
    azimuthText: { type: String, value: '' },
    shortPoi: { type: String, value: '实拍点位' },
    fullAddr: { type: String, value: '' },
    note: { type: String, value: '' },
    showQr: { type: Boolean, value: true },
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
    timeHm: '--:--',
    timeHms: '--:--:--',
    dateOnlyStr: '',
    weekStr: '',
    dateWeek: '',
    fullTs: '',
    hasValidCoord: false,
    liveCoordOnlyStr: '定位校准中...',
    liveCoordStr: '定位校准中...',
    liveEnvStr: '',
    liveAntiTamperShort: ''
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
    'coordSys, useDms, gcjLat, gcjLon, wgsLat, wgsLon, accuracy, altitude, azimuthText, showCoordTag, showAccuracy, showEnv, shortPoi, note, antiTamperMode, hasRealFix, isManualCoord, ntpOffsetMs'() {
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
      if (!this.data.antiTamperMode) return '';
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

    _buildEnvStr() {
      if (!this.data.showEnv) return '';
      const parts = [];
      if (this.data.altitude !== '' && this.data.altitude !== null && this.data.altitude !== undefined) {
        parts.push(`海拔 ${this.data.altitude}m`);
      }
      if (this.data.azimuthText) {
        parts.push(`方位 ${this.data.azimuthText}`);
      }
      return parts.join(' · ');
    },

    _refreshAll() {
      const t = getTimeStrings(this.data.ntpOffsetMs);
      const validCoord = Boolean(
        (this.data.hasRealFix || this.data.isManualCoord) &&
        !(Number(this.data.gcjLat) === 0 && Number(this.data.gcjLon) === 0)
      );

      let coordOnly = '定位校准中...';
      let coordFull = '定位校准中...';
      if (validCoord) {
        const resolved = resolveDisplayCoordinates(
          this.data.coordSys,
          this.data.gcjLat,
          this.data.gcjLon,
          this.data.wgsLat,
          this.data.wgsLon
        );
        coordOnly = formatCoords(resolved.lat, resolved.lon, this.data.useDms);
        const tagPart = this.data.showCoordTag ? ` [${resolved.tag}]` : '';
        const accPart = this.data.showAccuracy && this.data.accuracy ? ` ±${this.data.accuracy}m` : '';
        coordFull = `${coordOnly}${tagPart}${accPart}`;
      }

      this.setData({
        timeHm: t.hm,
        timeHms: t.hms,
        dateOnlyStr: t.datePart,
        weekStr: t.weekStr,
        dateWeek: t.dateWeek,
        fullTs: t.fullTs,
        hasValidCoord: validCoord,
        liveCoordOnlyStr: coordOnly,
        liveCoordStr: coordFull,
        liveEnvStr: this._buildEnvStr(),
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
