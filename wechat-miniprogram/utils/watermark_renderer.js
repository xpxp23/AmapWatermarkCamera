/**
 * 极速高德水印相机 v1.0 · 独立离屏高清 Canvas 2D 水印渲染引擎
 * v1.0 核心重构：
 * 1. 先测原图尺寸再一次性按目标尺寸创建离屏画布，导出时省略逻辑裁切框，彻底根治裁切与丢水印 Bug
 * 2. 彻底移除伪造的 160px 矢量假小图，大幅拓宽巡检卡片与网格测绘文字排版空间
 * 3. 无真实 GPS 或无海拔时优雅展示占位/隐藏，绝不印出假天安门坐标或假 46.5m 海拔
 * 4. 串行并发互斥锁 + 240px 缩略图导出 + 长边钳制 <= 2400px，杜绝 iOS Jetsam 闪退
 */

const {
  resolveDisplayCoordinates,
  formatCoords,
  computeAntiTamperHash,
  encodeQrMatrix,
  buildAmapJumpUrl
} = require('./coord_qr.js');

const MAX_EXPORT_EDGE = 2400;
const MIN_SHORT_EDGE = 1080;
const THUMB_WIDTH = 240;

let _renderLock = Promise.resolve();

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
    const radius = Math.min(r, w / 2, h / 2);
    ctx.moveTo(x + radius, y);
    ctx.arcTo(x + w, y, x + w, y + h, radius);
    ctx.arcTo(x + w, y + h, x, y + h, radius);
    ctx.arcTo(x, y + h, x, y, radius);
    ctx.arcTo(x, y, x + w, y, radius);
    ctx.closePath();
  }
}

function hasValidCoords(cfg) {
  const lat = Number(cfg && cfg.gcjLat);
  const lon = Number(cfg && cfg.gcjLon);
  return !isNaN(lat) && !isNaN(lon) && !(lat === 0 && lon === 0) && Boolean(cfg.hasRealFix || cfg.isManualCoord || Math.abs(lat) > 0.0001 || Math.abs(lon) > 0.0001);
}

/**
 * 在 Canvas 2D 上绘制 5 套水印模板与高德零偏差定位二维码
 */
function drawCanvasWatermarkAndQr(ctx, w, h, cfg) {
  const scaleFactor = Math.min(w, h) / 1080;
  const fScale = (Number(cfg.fontScale) || 1.0) * scaleFactor;
  const validCoord = hasValidCoords(cfg);
  const resolved = validCoord
    ? resolveDisplayCoordinates(cfg.coordSys, cfg.gcjLat, cfg.gcjLon, cfg.wgsLat, cfg.wgsLon)
    : { lat: 0, lon: 0, tag: cfg.coordSys || 'WGS-84' };

  const coordOnly = validCoord ? formatCoords(resolved.lat, resolved.lon, cfg.useDms) : '定位校准中';
  const tagStr = validCoord && cfg.showCoordTag !== false ? ` [${resolved.tag}]` : '';
  const accStr = validCoord && cfg.showAccuracy !== false && cfg.accuracy ? ` ±${cfg.accuracy}m` : '';
  const coordLine = `${coordOnly}${tagStr}${accStr}`;

  const envParts = [];
  if (cfg.showEnv) {
    if (cfg.altitude !== undefined && cfg.altitude !== null && String(cfg.altitude).trim() !== '') {
      envParts.push(`海拔 ${cfg.altitude}m`);
    }
    if (cfg.azimuthText) {
      envParts.push(`方位 ${cfg.azimuthText}`);
    }
  }
  const envLine = envParts.join(' · ');

  const sigHash =
    cfg.antiTamperHash ||
    (validCoord ? computeAntiTamperHash(cfg.gcjLat, cfg.gcjLon, cfg.fullTs, cfg.shortPoi, cfg.note) : '');
  const timeBadge = cfg.retroSynced ? '补签授时' : (cfg.ntpSynced ? '权威授时' : '防伪锁定');
  const antiLine = cfg.antiTamperMode && sigHash
    ? `🛡 ${timeBadge} · SHA256:${sigHash.slice(0, 12)}`
    : (cfg.offlinePending ? '⏳ 离线拍摄 · 待联网补签授时' : '');

  const accentColor = cfg.accentHex || '#38bdf8';
  const cardBg = cfg.cardBgRgba || 'rgba(11, 19, 43, 0.84)';
  const cleanPoi = cfg.shortPoi || '实拍点位';
  const weekPart = cfg.weekStr || (cfg.dateWeek ? cfg.dateWeek.split(' ')[1] : '') || '';
  const placeCardRight = Boolean(cfg.bottomRight && cfg.styleTemplate !== 0 && cfg.styleTemplate !== 3);
  const showQrNow = Boolean(cfg.showQr && validCoord);

  if (cfg.styleTemplate === 0) {
    if (cfg.showBottomScrim === true) {
      const gradH = Math.round(360 * scaleFactor);
      const grad = ctx.createLinearGradient(0, h - gradH, 0, h);
      grad.addColorStop(0, 'rgba(0,0,0,0)');
      grad.addColorStop(1, 'rgba(0,0,0,0.58)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, h - gradH, w, gradH);
    }

    const qrReserveRight = showQrNow ? Math.round(186 * scaleFactor) : Math.round(28 * scaleFactor);
    const leftMargin = Math.round(28 * scaleFactor);
    const safeZoneW = w - leftMargin - qrReserveRight;
    const cx = showQrNow ? leftMargin + safeZoneW / 2 : w / 2;
    const maxLineW = safeZoneW - Math.round(16 * scaleFactor);
    let curY = h - Math.round(40 * scaleFactor);

    const applyTextShadow = () => {
      ctx.shadowColor = 'rgba(0, 0, 0, 0.78)';
      ctx.shadowBlur = Math.round(10 * scaleFactor);
      ctx.shadowOffsetX = 0;
      ctx.shadowOffsetY = Math.round(2 * scaleFactor);
    };
    const clearTextShadow = () => {
      ctx.shadowColor = 'transparent';
      ctx.shadowBlur = 0;
      ctx.shadowOffsetX = 0;
      ctx.shadowOffsetY = 0;
    };

    ctx.save();
    applyTextShadow();

    if (antiLine) {
      ctx.font = `bold ${Math.round(20 * fScale)}px monospace`;
      ctx.fillStyle = cfg.offlinePending && !cfg.antiTamperMode ? '#fbbf24' : '#34d399';
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
      ctx.fillStyle = '#e2e8f0';
      ctx.textAlign = 'center';
      ctx.fillText(ellipsizeCanvasText(ctx, cfg.fullAddr, maxLineW), cx, curY);
      curY -= Math.round(34 * fScale);
    }
    if (cfg.showCoords) {
      clearTextShadow();
      const coordFontPx = Math.round((cfg.useDms ? 20 : 23) * fScale);
      ctx.font = `bold ${coordFontPx}px monospace`;
      const clippedCoord = ellipsizeCanvasText(ctx, coordLine, maxLineW - 36 * scaleFactor);
      const tw = ctx.measureText(clippedCoord).width;
      ctx.fillStyle = cardBg;
      drawRoundedRect(
        ctx,
        cx - tw / 2 - 18 * scaleFactor,
        curY - 27 * scaleFactor,
        tw + 36 * scaleFactor,
        38 * scaleFactor,
        19 * scaleFactor
      );
      ctx.fill();
      ctx.strokeStyle = accentColor;
      ctx.lineWidth = 1.5 * scaleFactor;
      ctx.stroke();
      ctx.fillStyle = accentColor;
      ctx.textAlign = 'center';
      ctx.fillText(clippedCoord, cx, curY);
      curY -= Math.round(48 * fScale);
      applyTextShadow();
    }
    if (cfg.showTime || cfg.showLoc) {
      ctx.font = `bold ${Math.round(30 * fScale)}px sans-serif`;
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      const lineRaw = `${cfg.showTime ? (cfg.dateWeek || '') + '  ' : ''}${cfg.showLoc ? cleanPoi : ''}`;
      ctx.fillText(ellipsizeCanvasText(ctx, lineRaw.trim(), maxLineW), cx, curY);
      curY -= Math.round(56 * fScale);
    }
    if (cfg.showTime) {
      ctx.font = `300 ${Math.round(108 * fScale)}px sans-serif`;
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.fillText(cfg.hm || '', cx, curY);
    }
    ctx.restore();
  } else if (cfg.styleTemplate === 1) {
    const margin = Math.round(28 * scaleFactor);
    const boxW = Math.min(
      w - margin * 2 - (showQrNow ? Math.round(170 * scaleFactor) : 0),
      Math.round((showQrNow ? 856 : 1024) * scaleFactor)
    );
    const boxLeft = placeCardRight ? w - boxW - margin : margin;
    const padX = Math.round(24 * scaleFactor);
    const padY = Math.round(18 * scaleFactor);
    const headerH = Math.round(44 * fScale);
    const lineGap = Math.round(36 * fScale);
    const textStartX = boxLeft + padX;
    const maxTextW = Math.max(120, boxW - padX * 2);

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
    if (cfg.antiTamperMode && sigHash) {
      rows.push(['防伪', `SHA256:${sigHash.slice(0, 12)} (${timeBadge})`, '#34d399']);
    } else if (cfg.offlinePending) {
      rows.push(['状态', '离线拍摄 · 待联网补签授时', '#fbbf24']);
    }

    const contentH = headerH + rows.length * lineGap;
    const boxH = padY * 2 + contentH;
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
    const titleStr = `${cfg.customTitle || '现场点位核验'} · ${cfg.antiTamperMode ? timeBadge : resolved.tag}`;
    ctx.fillText(
      ellipsizeCanvasText(ctx, titleStr, boxW - padX * 2 - 24 * scaleFactor),
      boxLeft + padX + 18 * scaleFactor,
      boxTop + padY + 23 * fScale
    );

    ctx.strokeStyle = 'rgba(255, 255, 255, 0.16)';
    ctx.lineWidth = 1.2 * scaleFactor;
    ctx.beginPath();
    ctx.moveTo(boxLeft + padX, boxTop + padY + headerH - 8 * scaleFactor);
    ctx.lineTo(boxLeft + boxW - padX, boxTop + padY + headerH - 8 * scaleFactor);
    ctx.stroke();

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
    const boxW = Math.min(
      w - margin * 2 - (showQrNow ? Math.round(170 * scaleFactor) : 0),
      Math.round((showQrNow ? 856 : 1024) * scaleFactor)
    );
    const boxLeft = placeCardRight ? w - boxW - margin : margin;
    const hasFooter = Boolean(antiLine || (cfg.showNote && cfg.note) || (cfg.showAddress && cfg.fullAddr) || envLine);
    const boxH = Math.round((hasFooter ? 192 : 150) * fScale);
    const boxTop = h - boxH - margin;

    ctx.fillStyle = cardBg;
    drawRoundedRect(ctx, boxLeft, boxTop, boxW, boxH, 16 * scaleFactor);
    ctx.fill();

    ctx.fillStyle = 'rgba(56, 189, 248, 0.20)';
    drawRoundedRect(ctx, boxLeft, boxTop, boxW, Math.round(52 * fScale), 14 * scaleFactor);
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
    ctx.fillText(
      ellipsizeCanvasText(ctx, topText, boxW - 32 * scaleFactor),
      boxLeft + 16 * scaleFactor,
      boxTop + Math.round(34 * fScale)
    );

    const midX = boxLeft + boxW * 0.48;
    const headerBottom = boxTop + Math.round(52 * fScale);
    const row2Bottom = boxTop + Math.round(140 * fScale);
    ctx.strokeStyle = 'rgba(148, 163, 184, 0.32)';
    ctx.lineWidth = 1.2 * scaleFactor;
    ctx.beginPath();
    ctx.moveTo(boxLeft, headerBottom);
    ctx.lineTo(boxLeft + boxW, headerBottom);
    ctx.moveTo(midX, headerBottom);
    ctx.lineTo(midX, row2Bottom);
    if (hasFooter) {
      ctx.moveTo(boxLeft, row2Bottom);
      ctx.lineTo(boxLeft + boxW, row2Bottom);
    }
    ctx.stroke();

    ctx.fillStyle = '#94a3b8';
    ctx.font = `normal ${Math.round(18 * fScale)}px sans-serif`;
    ctx.fillText(`测绘点位 (${resolved.tag})`, boxLeft + 16 * scaleFactor, boxTop + Math.round(80 * fScale));
    ctx.fillText(`采样时间 (${weekPart})`, midX + 16 * scaleFactor, boxTop + Math.round(80 * fScale));

    ctx.fillStyle = '#ffffff';
    ctx.font = `bold ${Math.round(23 * fScale)}px sans-serif`;
    ctx.fillText(
      ellipsizeCanvasText(ctx, cleanPoi, boxW * 0.48 - 28 * scaleFactor),
      boxLeft + 16 * scaleFactor,
      boxTop + Math.round(120 * fScale)
    );
    ctx.font = `bold ${Math.round(21 * fScale)}px monospace`;
    ctx.fillText(
      ellipsizeCanvasText(ctx, cfg.fullTs, boxW * 0.52 - 28 * scaleFactor),
      midX + 16 * scaleFactor,
      boxTop + Math.round(120 * fScale)
    );

    if (hasFooter) {
      ctx.fillStyle = cfg.antiTamperMode ? '#34d399' : '#fde68a';
      ctx.font = `normal ${Math.round(19.5 * fScale)}px sans-serif`;
      const footParts = [];
      if (antiLine) footParts.push(antiLine);
      if (cfg.showNote && cfg.note) footParts.push('备注：' + cfg.note);
      else if (cfg.showAddress && cfg.fullAddr) footParts.push(cfg.fullAddr);
      else if (envLine) footParts.push(envLine);
      ctx.fillText(
        ellipsizeCanvasText(ctx, footParts.join(' · '), boxW - 32 * scaleFactor),
        boxLeft + 16 * scaleFactor,
        boxTop + Math.round(172 * fScale)
      );
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
    ctx.fillText(ellipsizeCanvasText(ctx, cleanPoi, w * 0.5), 26 * scaleFactor, barTop + Math.round(44 * fScale));

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
    const boxW = Math.min(
      w - margin * 2 - (showQrNow ? Math.round(170 * scaleFactor) : 0),
      Math.round((showQrNow ? 856 : 1024) * scaleFactor)
    );
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
    if (cfg.antiTamperMode && sigHash) {
      rows.push(['防伪签名', `SHA256:${sigHash.slice(0, 16)} (${timeBadge})`, '#34d399']);
    } else if (cfg.offlinePending) {
      rows.push(['授时状态', '离线待补签', '#fbbf24']);
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
    const titleStr = `${cfg.customTitle || '外业定点巡检台账'} · ${cfg.antiTamperMode ? timeBadge : resolved.tag}`;
    ctx.fillText(
      ellipsizeCanvasText(ctx, titleStr, boxW - 28 * scaleFactor),
      boxLeft + 14 * scaleFactor,
      boxTop + Math.round(30 * fScale)
    );

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

  if (showQrNow) {
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
      cfg.antiTamperMode ? '防伪定位码' : '长按跳高德',
      boxLeft + qrBoxSize / 2,
      boxTop + qrBoxSize + Math.round(18 * scaleFactor)
    );
  }
}

/**
 * 预读取图片真实尺寸（避免先建固定 1080x1440 画布再动态改尺寸触发裁切）
 */
function getImageInfoAsync(src) {
  return new Promise(resolve => {
    if (!src || typeof wx.getImageInfo !== 'function') {
      resolve(null);
      return;
    }
    wx.getImageInfo({
      src,
      success: res => {
        if (res && res.width > 0 && res.height > 0) {
          resolve({ width: res.width, height: res.height });
        } else {
          resolve(null);
        }
      },
      fail: () => resolve(null)
    });
  });
}

/**
 * 计算钳制后的安全输出尺寸：
 * - 长边严格 <= 2400px，防止 iOS 4096px 纹理崩溃
 * - 短边保底 >= 1080px（不超过长边上限），彻底杜绝低分辨率照片导致裁切与二维码糊码
 */
function computeSafeExportDimensions(naturalW, naturalH) {
  const nw = Math.max(1, Math.round(Number(naturalW) || 1080));
  const nh = Math.max(1, Math.round(Number(naturalH) || 1440));
  let w = nw;
  let h = nh;
  const longEdge = Math.max(w, h);
  if (longEdge > MAX_EXPORT_EDGE) {
    const ratio = MAX_EXPORT_EDGE / longEdge;
    w = Math.max(1, Math.round(w * ratio));
    h = Math.max(1, Math.round(h * ratio));
  } else if (Math.min(w, h) < MIN_SHORT_EDGE && longEdge < MAX_EXPORT_EDGE) {
    const upRatio = Math.min(MIN_SHORT_EDGE / Math.min(w, h), MAX_EXPORT_EDGE / longEdge);
    w = Math.max(1, Math.round(w * upRatio));
    h = Math.max(1, Math.round(h * upRatio));
  }
  return { w, h };
}

function createCanvasInstance(width, height, fallbackCanvas) {
  if (typeof wx.createOffscreenCanvas === 'function') {
    try {
      const offscreen = wx.createOffscreenCanvas({ type: '2d', width, height });
      if (offscreen && offscreen.getContext) {
        return { canvas: offscreen, isOffscreen: true };
      }
    } catch (e) {}
  }
  if (fallbackCanvas) {
    fallbackCanvas.width = width;
    fallbackCanvas.height = height;
    return { canvas: fallbackCanvas, isOffscreen: false };
  }
  return { canvas: null, isOffscreen: false };
}

/**
 * 全画幅安全导出 JPG：
 * 省略 x/y/width/height 逻辑裁切矩形，仅指定 destWidth/destHeight，彻底杜绝左上角裁切与丢水印
 */
function exportCanvasToJpg(canvas, width, height, quality) {
  return new Promise((resolve, reject) => {
    wx.canvasToTempFilePath({
      canvas,
      destWidth: width,
      destHeight: height,
      fileType: 'jpg',
      quality,
      success: res => {
        if (res && res.tempFilePath) {
          resolve(res.tempFilePath);
        } else {
          reject(new Error('导出图片路径为空'));
        }
      },
      fail: err => reject(new Error((err && err.errMsg) || '图片导出失败'))
    });
  });
}

function loadCanvasImage(canvas, src) {
  return new Promise((resolve, reject) => {
    const img = canvas.createImage();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('底图读取失败'));
    img.src = src;
  });
}

/**
 * 串行互斥执行水印合成，可选同步导出 240px 缩略图
 * @param {string} rawImagePath 原图路径
 * @param {object} cfg 水印配置快照
 * @param {object} [options] { fallbackCanvas, generateThumb, quality }
 */
function renderWatermarkToTempFile(rawImagePath, cfg, options) {
  const opts = options || {};
  const task = () =>
    (async () => {
      if (!rawImagePath) {
        throw new Error('缺少待合成原图路径');
      }

      // 关键修复：先读取原图真实宽高并算好目标尺寸，再按精确尺寸一次性创建离屏画布
      const info = await getImageInfoAsync(rawImagePath);
      const initialDims = info
        ? computeSafeExportDimensions(info.width, info.height)
        : { w: 1080, h: 1440 };

      let { canvas, isOffscreen } = createCanvasInstance(initialDims.w, initialDims.h, opts.fallbackCanvas);
      if (!canvas) {
        throw new Error('当前环境无法创建离屏画布');
      }

      let img = null;
      let thumbCanvas = null;
      try {
        img = await loadCanvasImage(canvas, rawImagePath);
        const { w, h } = computeSafeExportDimensions(img.width || initialDims.w, img.height || initialDims.h);

        // 若 getImageInfo 缺失导致预估尺寸不符，重新按精确尺寸新建 OffscreenCanvas（禁止二次缩放旧离屏表面）
        if (w !== canvas.width || h !== canvas.height) {
          if (isOffscreen) {
            try {
              canvas.width = 1;
              canvas.height = 1;
            } catch (e) {}
            const rebuilt = createCanvasInstance(w, h, opts.fallbackCanvas);
            canvas = rebuilt.canvas;
            isOffscreen = rebuilt.isOffscreen;
            img = await loadCanvasImage(canvas, rawImagePath);
          } else {
            canvas.width = w;
            canvas.height = h;
          }
        }

        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, w, h);
        ctx.drawImage(img, 0, 0, w, h);
        if (!cfg || !cfg.noWatermark) {
          drawCanvasWatermarkAndQr(ctx, w, h, cfg || {});
        }

        const quality = opts.quality || 0.92;
        const tempFilePath = await exportCanvasToJpg(canvas, w, h, quality);

        let thumbTempFilePath = '';
        if (opts.generateThumb) {
          try {
            const tw = THUMB_WIDTH;
            const th = Math.max(1, Math.round((h / w) * tw));
            const tWrap = createCanvasInstance(tw, th, null);
            thumbCanvas = tWrap.canvas;
            if (thumbCanvas) {
              const tCtx = thumbCanvas.getContext('2d');
              tCtx.clearRect(0, 0, tw, th);
              tCtx.drawImage(canvas, 0, 0, w, h, 0, 0, tw, th);
              thumbTempFilePath = await exportCanvasToJpg(thumbCanvas, tw, th, 0.78);
            } else {
              thumbTempFilePath = tempFilePath;
            }
          } catch (thumbErr) {
            thumbTempFilePath = tempFilePath;
          }
        }

        return {
          tempFilePath,
          thumbTempFilePath,
          width: w,
          height: h
        };
      } finally {
        if (img) {
          try {
            img.src = '';
          } catch (e) {}
        }
        if (isOffscreen && canvas) {
          try {
            canvas.width = 1;
            canvas.height = 1;
          } catch (e) {}
        }
        if (thumbCanvas) {
          try {
            thumbCanvas.width = 1;
            thumbCanvas.height = 1;
          } catch (e) {}
        }
      }
    })();

  const run = _renderLock.then(task, task);
  _renderLock = run.catch(() => {});
  return run;
}

module.exports = {
  MAX_EXPORT_EDGE,
  THUMB_WIDTH,
  getImageInfoAsync,
  computeSafeExportDimensions,
  createCanvasInstance,
  loadCanvasImage,
  exportCanvasToJpg,
  drawCanvasWatermarkAndQr,
  renderWatermarkToTempFile
};
