/**
 * 极速高德水印相机 v9.0.0 · 微信云开发内容安全合规校验模块
 * 对接微信官方 openapi.security.imgSecCheck 与 openapi.security.msgSecCheck (v2)
 * 环境 ID：cloud1-d1gmv7jr01aacf3cc
 */

const {
  createCanvasInstance,
  loadCanvasImage,
  exportCanvasToJpg
} = require('./watermark_renderer.js');

const CLOUD_ENV_ID = 'cloud1-d1gmv7jr01aacf3cc';
const VIOLATION_MSG = '所发布内容含违规信息';
const SERVICE_ERROR_MSG = '内容安全校验未通过，请检查网络或稍后重试';
const MAX_CHECK_EDGE = 480;
const MAX_BASE64_LEN = 650000;
const MAX_MSG_CHUNK_CHARS = 650;

let _cloudInited = false;
const _safeTextCache = new Set();

function initCloudSecurity() {
  if (_cloudInited || typeof wx.cloud === 'undefined' || typeof wx.cloud.init !== 'function') {
    return _cloudInited;
  }
  try {
    wx.cloud.init({
      env: CLOUD_ENV_ID,
      traceUser: true
    });
    _cloudInited = true;
  } catch (e) {}
  return _cloudInited;
}

function unlinkTempQuietly(filePath) {
  if (!filePath || typeof wx.getFileSystemManager !== 'function') return;
  try {
    const fs = wx.getFileSystemManager();
    fs.unlink({ filePath, fail: () => {} });
  } catch (e) {}
}

function readFileBase64(filePath) {
  return new Promise((resolve, reject) => {
    try {
      const fs = wx.getFileSystemManager();
      fs.readFile({
        filePath,
        encoding: 'base64',
        success: res => resolve((res && res.data) || ''),
        fail: err => reject(err || new Error('readFile failed'))
      });
    } catch (e) {
      reject(e);
    }
  });
}

function compressImageFallback(src) {
  return new Promise((resolve, reject) => {
    if (typeof wx.compressImage !== 'function') {
      reject(new Error('compressImage unavailable'));
      return;
    }
    wx.compressImage({
      src,
      quality: 35,
      success: res => resolve(res && res.tempFilePath ? res.tempFilePath : src),
      fail: err => reject(err || new Error('compressImage failed'))
    });
  });
}

/**
 * 将任意分辨率图片安全缩绘为 <= 480px 的轻量 JPEG（符合 imgSecCheck <= 1MB / 750x1334 规范）
 */
async function buildCompressedThumbForCheck(imagePath, fallbackCanvas) {
  let thumbPath = '';
  let createdTemp = false;
  let canvas = null;
  let isOffscreen = false;
  let img = null;

  try {
    const wrap = createCanvasInstance(MAX_CHECK_EDGE, MAX_CHECK_EDGE, fallbackCanvas);
    canvas = wrap.canvas;
    isOffscreen = wrap.isOffscreen;
    if (canvas) {
      img = await loadCanvasImage(canvas, imagePath);
      const iw = img.width || MAX_CHECK_EDGE;
      const ih = img.height || MAX_CHECK_EDGE;
      const ratio = Math.min(1, MAX_CHECK_EDGE / Math.max(iw, ih));
      const tw = Math.max(1, Math.round(iw * ratio));
      const th = Math.max(1, Math.round(ih * ratio));
      canvas.width = tw;
      canvas.height = th;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, tw, th);
      ctx.drawImage(img, 0, 0, tw, th);
      thumbPath = await exportCanvasToJpg(canvas, tw, th, 0.6);
      createdTemp = Boolean(thumbPath && thumbPath !== imagePath);
    }
  } catch (e) {
    thumbPath = '';
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

  if (!thumbPath) {
    try {
      const compPath = await compressImageFallback(imagePath);
      thumbPath = compPath;
      createdTemp = Boolean(compPath && compPath !== imagePath);
    } catch (e2) {
      thumbPath = imagePath;
      createdTemp = false;
    }
  }

  return { thumbPath, createdTemp };
}

function showViolationModal(message) {
  wx.showModal({
    title: '安全提示',
    content: message || VIOLATION_MSG,
    showCancel: false,
    confirmText: '我知道了'
  });
}

/**
 * 调用云函数 secCheck 校验图片内容安全
 */
async function checkImageSecurity(imagePath, fallbackCanvas) {
  if (!imagePath) return { pass: true };
  initCloudSecurity();
  if (typeof wx.cloud === 'undefined' || typeof wx.cloud.callFunction !== 'function') {
    return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
  }

  let thumbInfo = { thumbPath: '', createdTemp: false };
  try {
    thumbInfo = await buildCompressedThumbForCheck(imagePath, fallbackCanvas);
    const b64 = await readFileBase64(thumbInfo.thumbPath);

    let payload = null;
    if (b64 && b64.length <= MAX_BASE64_LEN) {
      if (thumbInfo.createdTemp) {
        unlinkTempQuietly(thumbInfo.thumbPath);
        thumbInfo.createdTemp = false;
      }
      payload = {
        action: 'img',
        imageBase64: b64,
        contentType: 'image/jpeg'
      };
    } else {
      // 超限降级通过临时云存储传 fileID，云函数侧检测完毕立即删除
      const cloudPath = `sec_tmp/${Date.now()}_${Math.floor(Math.random() * 100000)}.jpg`;
      const upRes = await wx.cloud.uploadFile({
        cloudPath,
        filePath: thumbInfo.thumbPath
      });
      if (thumbInfo.createdTemp) {
        unlinkTempQuietly(thumbInfo.thumbPath);
        thumbInfo.createdTemp = false;
      }
      payload = {
        action: 'img',
        fileID: upRes.fileID,
        contentType: 'image/jpeg'
      };
    }

    const res = await wx.cloud.callFunction({
      name: 'secCheck',
      data: payload
    });
    const r = res && res.result;
    if (r && r.pass === true) {
      return { pass: true, risky: false };
    }
    if (r && r.risky) {
      return { pass: false, risky: true, message: VIOLATION_MSG };
    }
    return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
  } catch (err) {
    return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
  } finally {
    if (thumbInfo.createdTemp && thumbInfo.thumbPath) {
      unlinkTempQuietly(thumbInfo.thumbPath);
    }
  }
}

function normalizeTextList(texts) {
  const arr = Array.isArray(texts) ? texts : [texts];
  const seen = new Set();
  const out = [];
  arr.forEach(item => {
    const s = String(item || '').trim();
    if (!s || seen.has(s)) return;
    seen.add(s);
    out.push(s);
  });
  return out;
}

function splitIntoChunks(items, maxChars) {
  const chunks = [];
  let current = '';
  items.forEach(str => {
    if (str.length > maxChars) {
      if (current) {
        chunks.push(current);
        current = '';
      }
      for (let i = 0; i < str.length; i += maxChars) {
        chunks.push(str.slice(i, i + maxChars));
      }
      return;
    }
    const candidate = current ? `${current}\n${str}` : str;
    if (candidate.length > maxChars) {
      chunks.push(current);
      current = str;
    } else {
      current = candidate;
    }
  });
  if (current) chunks.push(current);
  return chunks;
}

/**
 * 调用云函数 secCheck 校验一组用户文本内容安全 (msgSecCheck v2)
 */
async function checkTextsSecurity(texts, scene) {
  const unique = normalizeTextList(texts);
  const uncached = unique.filter(s => !_safeTextCache.has(s));
  if (uncached.length === 0) {
    return { pass: true, risky: false };
  }

  initCloudSecurity();
  if (typeof wx.cloud === 'undefined' || typeof wx.cloud.callFunction !== 'function') {
    return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
  }

  const chunks = splitIntoChunks(uncached, MAX_MSG_CHUNK_CHARS);
  try {
    for (let i = 0; i < chunks.length; i++) {
      const res = await wx.cloud.callFunction({
        name: 'secCheck',
        data: {
          action: 'msg',
          content: chunks[i],
          scene: scene || 3
        }
      });
      const r = res && res.result;
      if (r && r.risky) {
        return { pass: false, risky: true, message: VIOLATION_MSG };
      }
      if (!r || r.pass !== true) {
        return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
      }
    }

    if (_safeTextCache.size > 300) {
      _safeTextCache.clear();
    }
    uncached.forEach(s => _safeTextCache.add(s));
    return { pass: true, risky: false };
  } catch (err) {
    return { pass: false, risky: false, message: SERVICE_ERROR_MSG };
  }
}

module.exports = {
  CLOUD_ENV_ID,
  VIOLATION_MSG,
  initCloudSecurity,
  checkImageSecurity,
  checkTextsSecurity,
  showViolationModal
};
