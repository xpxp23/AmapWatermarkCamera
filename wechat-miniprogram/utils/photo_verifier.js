/**
 * 极速高德水印相机 v9.0.0 · 独立离屏识图验真核心引擎
 * 修复要点：
 * 1. 使用独立 wx.createOffscreenCanvas 彻底与水印渲染隔离
 * 2. VKSession 放入 try-finally 确保任何异常或超时均调用 session.destroy() 防止内存泄漏
 * 3. 裁切区域严格保持 1:1 等比缩放（不拉伸变形），自动扫描右下角/左下角/全图
 * 4. 超时放宽至 1800ms，严禁假阳性伪报通过
 */

const {
  gcj02ToWgs84,
  computeAntiTamperHash,
  verifyAntiTamperHash,
  parseLocationPayload
} = require('./coord_qr.js');

const VK_REGION_TIMEOUT_MS = 1800;
const VK_CANVAS_SIZE = 520;

/**
 * 构建标准化《识图验真核验报告》数据模型 (v9.0.0)
 */
function buildVerificationReport(parsed, imagePath, sourceLabel) {
  if (!parsed || isNaN(parsed.gcjLat) || isNaN(parsed.gcjLon)) return null;
  const gcjLat = Number(Number(parsed.gcjLat).toFixed(6));
  const gcjLon = Number(Number(parsed.gcjLon).toFixed(6));
  const wgs = gcj02ToWgs84(gcjLat, gcjLon);
  const shortPoi = parsed.shortPoi || '实拍点位';
  const timestamp = parsed.timestamp || '未记录时间戳';
  const note = parsed.note || '';
  const expectedFull = parsed.timestamp
    ? computeAntiTamperHash(gcjLat, gcjLon, parsed.timestamp, shortPoi, note)
    : '';
  const actualSig = String(parsed.signature || '').toUpperCase().trim();
  const expectedSig = expectedFull ? expectedFull.slice(0, 12) : '';

  let status = 'NORMAL'; // 'VERIFIED' | 'TAMPERED' | 'NORMAL'
  let statusBadge = 'ℹ️ 普通高德定位照片';
  let statusTitle = '未开启外业防篡改签名';
  let statusDesc = '已成功解析照片内嵌的高德 GCJ-02 零偏差坐标，但该照片在拍摄时未开启 SHA-256 防篡改验真锁定。';

  if (parsed.hasAntiTamper && actualSig) {
    const isMatched = verifyAntiTamperHash(gcjLat, gcjLon, parsed.timestamp, shortPoi, actualSig, note);
    if (isMatched) {
      status = 'VERIFIED';
      statusBadge = '🛡 防篡改核验通过';
      statusTitle = '真实实拍原片 · 坐标与授时未篡改';
      statusDesc = parsed.ntpSynced
        ? '密码学签名 100% 匹配：高德 GCJ-02 真实卫星坐标、点位名称、备注与授时时间戳均未经过任何二次修改。'
        : '密码学签名 100% 匹配：高德 GCJ-02 真实卫星坐标、点位名称、备注与拍摄时间戳均未经过任何二次修改。';
    } else {
      status = 'TAMPERED';
      statusBadge = '⚠️ 签名校验异常';
      statusTitle = '警告：检测到坐标、时间或点位篡改';
      statusDesc = '照片内嵌签名摘要与当前坐标/时间戳重算结果不一致（或签名长度非法），该照片可能存在伪造或后期篡改！';
    }
  }

  return {
    version: '9.0.0',
    imagePath: imagePath || '',
    sourceLabel: sourceLabel || '二维码核验',
    status,
    statusBadge,
    statusTitle,
    statusDesc,
    shortPoi,
    gcjLat: gcjLat.toFixed(6),
    gcjLon: gcjLon.toFixed(6),
    wgsLat: wgs.lat.toFixed(6),
    wgsLon: wgs.lon.toFixed(6),
    timestamp,
    note,
    ntpSynced: Boolean(parsed.ntpSynced),
    hasAntiTamper: Boolean(parsed.hasAntiTamper),
    actualSig: actualSig ? actualSig.slice(0, 12) : '未附带签名',
    expectedSig: expectedSig || '无'
  };
}

/**
 * 格式化验真报告纯文本（v9.0.0）
 */
function formatVerifyReportText(r) {
  if (!r) return '';
  return [
    '【极速高德水印相机 v9.0.0 · 识图验真报告】',
    `核验结论：${r.statusBadge} (${r.statusTitle})`,
    `核验来源：${r.sourceLabel}`,
    `实拍点位：${r.shortPoi}`,
    `高德GCJ-02：${r.gcjLat}°N, ${r.gcjLon}°E`,
    `大地WGS-84：${r.wgsLat}°N, ${r.wgsLon}°E`,
    `记录时间：${r.timestamp}`,
    r.note ? `内嵌备注：${r.note}` : '',
    `内嵌签名：${r.actualSig}`,
    `重算签名：${r.expectedSig}`
  ]
    .filter(Boolean)
    .join('\n');
}

function detectSingleRegionWithVK(frameBuffer, width, height) {
  return new Promise(resolve => {
    if (!wx.createVKSession) {
      resolve(null);
      return;
    }
    let session = null;
    let settled = false;
    let timer = null;

    const cleanupAndResolve = val => {
      if (settled) return;
      settled = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      if (session) {
        try {
          session.destroy();
        } catch (e) {}
        session = null;
      }
      resolve(val);
    };

    try {
      session = wx.createVKSession({
        track: { QRCode: { mode: 2 } },
        version: 'v1'
      });

      timer = setTimeout(() => cleanupAndResolve(null), VK_REGION_TIMEOUT_MS);

      session.start(err => {
        if (err) {
          cleanupAndResolve(null);
          return;
        }
        try {
          session.on('updateAnchors', anchors => {
            if (anchors && anchors.length > 0 && anchors[0].codeString) {
              cleanupAndResolve(anchors[0].codeString);
            } else {
              cleanupAndResolve(null);
            }
          });
          session.detectQRCode({
            frameBuffer,
            width,
            height
          });
        } catch (innerErr) {
          cleanupAndResolve(null);
        }
      });
    } catch (outerErr) {
      cleanupAndResolve(null);
    }
  });
}

/**
 * 计算 1:1 等比居中放置目标矩形（杜绝拉伸变形导致二维码无法识别）
 */
function drawAspectFitInSquare(ctx, img, reg, canvasSize, pad) {
  const avail = canvasSize - pad * 2;
  const scale = Math.min(avail / Math.max(1, reg.sw), avail / Math.max(1, reg.sh));
  const dw = Math.max(1, Math.round(reg.sw * scale));
  const dh = Math.max(1, Math.round(reg.sh * scale));
  const dx = Math.round((canvasSize - dw) / 2);
  const dy = Math.round((canvasSize - dh) / 2);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvasSize, canvasSize);
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(img, reg.sx, reg.sy, reg.sw, reg.sh, dx, dy, dw, dh);
}

/**
 * 独立离屏 Canvas + VisionKit 静态图像二维码检测
 * 依次等比扫描：右下角正方形区 -> 左下角正方形区 -> 全图居中缩放兜底
 * @param {string} tempImagePath 待核验图片路径
 * @param {object} [fallbackCanvas] 兼容旧基础库的备用 canvas
 * @returns {Promise<string|null>}
 */
async function decodePhotoQrByVisionKit(tempImagePath, fallbackCanvas) {
  if (!tempImagePath || !wx.createVKSession) return null;

  let canvas = null;
  let isOffscreen = false;
  if (typeof wx.createOffscreenCanvas === 'function') {
    try {
      canvas = wx.createOffscreenCanvas({ type: '2d', width: VK_CANVAS_SIZE, height: VK_CANVAS_SIZE });
      isOffscreen = true;
    } catch (e) {}
  }
  if (!canvas && fallbackCanvas) {
    canvas = fallbackCanvas;
  }
  if (!canvas) return null;

  let img = null;
  try {
    img = await new Promise((resolve, reject) => {
      const i = canvas.createImage();
      i.onload = () => resolve(i);
      i.onerror = () => reject(new Error('图片加载失败'));
      i.src = tempImagePath;
    });

    const iw = img.width || 1080;
    const ih = img.height || 1440;
    const shortSide = Math.min(iw, ih);
    // 1:1 正方形裁切边长：覆盖右下角与左下角 42% 短边区域（兼顾模板 0/1/2/3/4）
    const cornerBox = Math.min(iw, ih, Math.max(200, Math.round(shortSide * 0.46)));

    const cropRegions = [
      // 区域 0: 右下角 1:1 正方形（默认二维码位置）
      { sx: Math.max(0, iw - cornerBox), sy: Math.max(0, ih - cornerBox), sw: cornerBox, sh: cornerBox },
      // 区域 1: 左下角 1:1 正方形（卡片居右时二维码位置）
      { sx: 0, sy: Math.max(0, ih - cornerBox), sw: cornerBox, sh: cornerBox },
      // 区域 2: 右下角偏上 1:1（覆盖模板 3 胶片底栏上方二维码）
      {
        sx: Math.max(0, iw - cornerBox),
        sy: Math.max(0, ih - Math.round(cornerBox * 1.35)),
        sw: cornerBox,
        sh: cornerBox
      },
      // 区域 3: 全图 1:1 等比缩放兜底
      { sx: 0, sy: 0, sw: iw, sh: ih }
    ];

    const ctx = canvas.getContext('2d');
    canvas.width = VK_CANVAS_SIZE;
    canvas.height = VK_CANVAS_SIZE;

    for (let i = 0; i < cropRegions.length; i++) {
      drawAspectFitInSquare(ctx, img, cropRegions[i], VK_CANVAS_SIZE, 16);
      const imgData = ctx.getImageData(0, 0, VK_CANVAS_SIZE, VK_CANVAS_SIZE);
      const codeStr = await detectSingleRegionWithVK(imgData.data.buffer, VK_CANVAS_SIZE, VK_CANVAS_SIZE);
      if (codeStr) {
        return codeStr;
      }
    }
    return null;
  } catch (e) {
    return null;
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
  }
}

/**
 * 一步式相册识图验真：绝不伪报通过，无有效码时返回 { ok: false, reason }
 */
async function verifyImageFile(tempImagePath, fallbackCanvas) {
  const qrStr = await decodePhotoQrByVisionKit(tempImagePath, fallbackCanvas);
  if (!qrStr) {
    return {
      ok: false,
      reason: '未检测到有效防伪定位码'
    };
  }
  const parsed = parseLocationPayload(qrStr);
  const report = buildVerificationReport(parsed, tempImagePath, 'VisionKit 1:1 等比双角识码');
  if (!report) {
    return {
      ok: false,
      reason: '未检测到有效防伪定位码'
    };
  }
  return {
    ok: true,
    parsed,
    report
  };
}

module.exports = {
  buildVerificationReport,
  formatVerifyReportText,
  decodePhotoQrByVisionKit,
  verifyImageFile
};
