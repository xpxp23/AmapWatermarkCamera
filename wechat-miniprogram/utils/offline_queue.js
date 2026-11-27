/**
 * 极速高德水印相机 v1.0 · 离线拍摄队列与联网补签授时引擎
 * 功能：
 * - 在地下室/工地/山区等无网场景拍摄时，自动记录待补签条目（保留原图 rawPath）
 * - 联网恢复或用户手动点击「一键补签」时，自动调用云端权威授时 + 周边 POI 逆地理编码
 * - 使用原图无损重绘水印并更新相册记录，支持防伪签名同步补签
 */

const {
  formatTimeFromDate,
  computeAntiTamperHash
} = require('./coord_qr.js');

const {
  resolveNearbyPoi,
  syncCloudServerTime
} = require('./poi_service.js');

const {
  loadPhotos,
  updatePhotoEntry,
  copyTempToUserData,
  safeUnlinkFile
} = require('./photo_store.js');

const { renderWatermarkToTempFile } = require('./watermark_renderer.js');

const STORAGE_KEY_OFFLINE_QUEUE = 'amap_wm_wx_offline_queue_v1_0';

let _isReconciling = false;
let _networkListenerBound = false;

function getOfflineQueue() {
  try {
    const raw = wx.getStorageSync(STORAGE_KEY_OFFLINE_QUEUE);
    return Array.isArray(raw) ? raw : [];
  } catch (e) {
    return [];
  }
}

function saveOfflineQueue(list) {
  const clean = Array.isArray(list) ? list.slice(0, 60) : [];
  try {
    wx.setStorage({
      key: STORAGE_KEY_OFFLINE_QUEUE,
      data: clean
    });
  } catch (e) {}
  return clean;
}

function getOfflinePendingCount() {
  return getOfflineQueue().length;
}

function enqueueOfflinePhoto(task) {
  if (!task || !task.photoId) return 0;
  const list = getOfflineQueue().filter(item => item && item.photoId !== task.photoId);
  list.unshift({
    photoId: task.photoId,
    gcjLat: Number(task.gcjLat) || 0,
    gcjLon: Number(task.gcjLon) || 0,
    localCapturedAt: Number(task.localCapturedAt) || Date.now(),
    needPoi: Boolean(task.needPoi),
    needTime: Boolean(task.needTime),
    enqueuedAt: Date.now()
  });
  saveOfflineQueue(list);
  return list.length;
}

function removeOfflinePhoto(photoId) {
  const list = getOfflineQueue().filter(item => item && item.photoId !== photoId);
  saveOfflineQueue(list);
  return list.length;
}

function checkIsOnline() {
  return new Promise(resolve => {
    if (typeof wx.getNetworkType !== 'function') {
      resolve(true);
      return;
    }
    wx.getNetworkType({
      success: res => resolve(Boolean(res && res.networkType && res.networkType !== 'none')),
      fail: () => resolve(true)
    });
  });
}

/**
 * 执行离线队列补签授时与周边 POI 自动补齐
 * @param {object} [options] { fallbackCanvas, onProgress }
 */
async function reconcileOfflineQueue(options) {
  const opts = options || {};
  if (_isReconciling) {
    return { skipped: true, reconciled: 0, remaining: getOfflinePendingCount() };
  }

  const queue = getOfflineQueue();
  if (queue.length === 0) {
    return { skipped: false, reconciled: 0, remaining: 0 };
  }

  const online = await checkIsOnline();
  if (!online) {
    return { skipped: true, offline: true, reconciled: 0, remaining: queue.length };
  }

  _isReconciling = true;
  let reconciled = 0;
  const remaining = [];

  try {
    const timeRes = await syncCloudServerTime();
    const serverOffsetMs = timeRes.ok ? timeRes.offsetMs : 0;
    const hasServerTime = timeRes.ok;

    const allPhotos = loadPhotos();
    const photoMap = {};
    allPhotos.forEach(p => {
      if (p && p.id) photoMap[p.id] = p;
    });

    for (let i = 0; i < queue.length; i++) {
      const task = queue[i];
      const photo = task && photoMap[task.photoId];
      // 若照片已被删除或无损底图已回收，移出待补签队列
      if (!photo || !photo.rawPath || !photo.cfg) {
        continue;
      }

      if (typeof opts.onProgress === 'function') {
        opts.onProgress({ current: i + 1, total: queue.length });
      }

      try {
        const nextCfg = { ...photo.cfg };
        let changed = false;

        // 1. 补齐真实周边 POI 名称
        if (task.needPoi && nextCfg.hasRealFix && nextCfg.gcjLat && nextCfg.gcjLon) {
          const poiRes = await resolveNearbyPoi(nextCfg.gcjLat, nextCfg.gcjLon);
          if (poiRes.ok && poiRes.resolved && poiRes.shortPoi) {
            nextCfg.shortPoi = poiRes.shortPoi;
            if (poiRes.fullAddr && !nextCfg.fullAddr) {
              nextCfg.fullAddr = poiRes.fullAddr;
            }
            nextCfg.poiResolved = true;
            changed = true;
          }
        }

        // 2. 补签权威服务端授时与防篡改摘要
        if (task.needTime && hasServerTime) {
          const calibratedMs = (Number(task.localCapturedAt) || photo.createdAt || Date.now()) + serverOffsetMs;
          const t = formatTimeFromDate(new Date(calibratedMs));
          nextCfg.hm = t.hm;
          nextCfg.hms = t.hms;
          nextCfg.dateOnlyStr = t.datePart;
          nextCfg.weekStr = t.weekStr;
          nextCfg.dateWeek = t.dateWeek;
          nextCfg.fullTs = t.fullTs;
          nextCfg.ntpSynced = true;
          nextCfg.retroSynced = true;
          nextCfg.retroSyncedAt = Date.now();
          if (nextCfg.antiTamperMode) {
            nextCfg.antiTamperHash = computeAntiTamperHash(
              nextCfg.gcjLat,
              nextCfg.gcjLon,
              nextCfg.fullTs,
              nextCfg.shortPoi,
              nextCfg.note
            );
          }
          changed = true;
        }

        if (!changed) {
          // 仍未获取到有效网络数据时保留在队列
          remaining.push(task);
          continue;
        }

        nextCfg.offlinePending = false;
        const out = await renderWatermarkToTempFile(photo.rawPath, nextCfg, {
          fallbackCanvas: opts.fallbackCanvas || null,
          generateThumb: true
        });
        const newWmPath = await copyTempToUserData(out.tempFilePath, 'amap_wm');
        const newThumbPath = out.thumbTempFilePath
          ? await copyTempToUserData(out.thumbTempFilePath, 'amap_thumb')
          : newWmPath;

        const oldWmPath = photo.wmPath;
        const oldThumbPath = photo.thumbPath;

        updatePhotoEntry(photo.id, item => ({
          ...item,
          wmPath: newWmPath,
          thumbPath: newThumbPath,
          cfg: nextCfg
        }));

        if (oldWmPath && oldWmPath !== newWmPath) safeUnlinkFile(oldWmPath);
        if (oldThumbPath && oldThumbPath !== newThumbPath && oldThumbPath !== oldWmPath) {
          safeUnlinkFile(oldThumbPath);
        }
        reconciled++;
      } catch (err) {
        remaining.push(task);
      }
    }

    saveOfflineQueue(remaining);
    return {
      skipped: false,
      reconciled,
      remaining: remaining.length
    };
  } finally {
    _isReconciling = false;
  }
}

/**
 * 监听网络恢复事件，自动静默触发离线补签回调
 */
function initOfflineNetworkListener(onNetworkRestored) {
  if (_networkListenerBound || typeof wx.onNetworkStatusChange !== 'function') {
    return;
  }
  _networkListenerBound = true;
  wx.onNetworkStatusChange(res => {
    if (res && res.isConnected && getOfflinePendingCount() > 0) {
      if (typeof onNetworkRestored === 'function') {
        onNetworkRestored();
      }
    }
  });
}

module.exports = {
  getOfflineQueue,
  getOfflinePendingCount,
  enqueueOfflinePhoto,
  removeOfflinePhoto,
  checkIsOnline,
  reconcileOfflineQueue,
  initOfflineNetworkListener
};
