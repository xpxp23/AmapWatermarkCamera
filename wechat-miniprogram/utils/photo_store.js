/**
 * 极速高德水印相机 v9.0.0 · 双轨相册存储、分级回收与孤儿文件 GC 管理引擎
 * - 水印成品 (wmPath) + 240px 缩略图 (thumbPath)：长期保留最多 120 张
 * - 无损底图 (rawPath)：仅保留最近 25 张，超限时仅回收最旧的 rawPath 而不删整张照片
 * - 冷启动异步扫描 USER_DATA_PATH 清理孤儿文件与旧版残留
 */

const STORAGE_KEY_CFG_V9 = 'amap_wm_wx_cfg_v9_0';
const STORAGE_KEY_PHOTOS_V9 = 'amap_wm_wx_photos_v9_0';
const LEGACY_CFG_KEYS = ['amap_wm_wx_cfg_v8_0', 'amap_wm_wx_cfg_v7_1'];
const LEGACY_PHOTO_KEYS = ['amap_wm_wx_photos_v8_0', 'amap_wm_wx_photos_v7_1'];

const MAX_PHOTOS = 120;
const MAX_RAW_ORIGINALS = 25;
const STORAGE_QUOTA_BYTES = 200 * 1024 * 1024; // 微信 USER_DATA_PATH 200MB 上限

function getUserDataPath() {
  return (wx.env && wx.env.USER_DATA_PATH) || '';
}

function isManagedUserFile(filePath) {
  const root = getUserDataPath();
  return Boolean(root && filePath && typeof filePath === 'string' && filePath.indexOf(root) === 0);
}

function safeUnlinkFile(filePath) {
  if (!isManagedUserFile(filePath)) return;
  try {
    const fs = wx.getFileSystemManager();
    fs.unlink({ filePath, fail: () => {} });
  } catch (e) {}
}

/**
 * 使用 fs.copyFile 将临时文件持久化到 USER_DATA_PATH（避免 fs.saveFile 移动源文件）
 */
function copyTempToUserData(tempPath, prefix) {
  return new Promise(resolve => {
    if (!tempPath) {
      resolve('');
      return;
    }
    const root = getUserDataPath();
    if (!root || !wx.getFileSystemManager) {
      resolve(tempPath);
      return;
    }
    try {
      const fs = wx.getFileSystemManager();
      const extMatch = tempPath.match(/\.[a-zA-Z0-9]+$/);
      const ext = extMatch ? extMatch[0] : '.jpg';
      const tag = prefix || 'amap_wm';
      const dest = `${root}/${tag}_${Date.now()}_${Math.floor(Math.random() * 10000)}${ext}`;
      fs.copyFile({
        srcPath: tempPath,
        destPath: dest,
        success: () => resolve(dest),
        fail: () => resolve(tempPath)
      });
    } catch (e) {
      resolve(tempPath);
    }
  });
}

function loadUserConfig() {
  try {
    const v9 = wx.getStorageSync(STORAGE_KEY_CFG_V9);
    if (v9 && typeof v9 === 'object') return v9;
    for (let i = 0; i < LEGACY_CFG_KEYS.length; i++) {
      const legacy = wx.getStorageSync(LEGACY_CFG_KEYS[i]);
      if (legacy && typeof legacy === 'object') {
        const migrated = {
          ...legacy,
          showBottomScrim: false
        };
        wx.setStorageSync(STORAGE_KEY_CFG_V9, migrated);
        return migrated;
      }
    }
  } catch (e) {}
  return null;
}

function saveUserConfig(cfg) {
  try {
    wx.setStorageSync(STORAGE_KEY_CFG_V9, cfg);
  } catch (e) {}
}

function loadPhotos() {
  try {
    const v9 = wx.getStorageSync(STORAGE_KEY_PHOTOS_V9);
    if (Array.isArray(v9)) return v9;
    for (let i = 0; i < LEGACY_PHOTO_KEYS.length; i++) {
      const legacy = wx.getStorageSync(LEGACY_PHOTO_KEYS[i]);
      if (Array.isArray(legacy) && legacy.length > 0) {
        const migrated = legacy.slice(0, MAX_PHOTOS).map(item => ({
          ...item,
          thumbPath: item.thumbPath || item.wmPath || '',
          createdAt: item.createdAt || Date.now()
        }));
        wx.setStorageSync(STORAGE_KEY_PHOTOS_V9, migrated);
        return migrated;
      }
    }
  } catch (e) {}
  return [];
}

/**
 * 分级持久化相册列表：
 * 1. 超出 MAX_PHOTOS(120) 的最旧记录彻底回收 wmPath / thumbPath / rawPath
 * 2. 超出 MAX_RAW_ORIGINALS(25) 的记录仅回收 rawPath，保留 wmPath / thumbPath
 * 3. 返回与磁盘 100% 一致的裁剪后数组，彻底杜绝内存与磁盘不一致导致的裂图
 */
function savePhotos(nextPhotos) {
  const input = Array.isArray(nextPhotos) ? nextPhotos : [];
  const kept = input.slice(0, MAX_PHOTOS).map(item => ({ ...item }));
  const evicted = input.slice(MAX_PHOTOS);

  evicted.forEach(p => {
    if (!p) return;
    safeUnlinkFile(p.rawPath);
    safeUnlinkFile(p.wmPath);
    safeUnlinkFile(p.thumbPath);
  });

  // 仅保留最新 25 张的无损底图 rawPath
  let rawCount = 0;
  for (let i = 0; i < kept.length; i++) {
    const p = kept[i];
    if (p && p.rawPath) {
      rawCount++;
      if (rawCount > MAX_RAW_ORIGINALS) {
        if (p.rawPath !== p.wmPath) {
          safeUnlinkFile(p.rawPath);
        }
        p.rawPath = '';
      }
    }
  }

  try {
    wx.setStorageSync(STORAGE_KEY_PHOTOS_V9, kept);
  } catch (e) {}
  return kept;
}

function addPhotoEntry(entry) {
  const list = loadPhotos();
  const next = savePhotos([entry, ...list]);
  return next;
}

function updatePhotoEntry(photoId, updater) {
  const list = loadPhotos();
  const next = list.map(item => {
    if (item && item.id === photoId) {
      return typeof updater === 'function' ? updater(item) : { ...item, ...updater };
    }
    return item;
  });
  return savePhotos(next);
}

function deletePhotosByIds(ids) {
  const idSet = new Set(Array.isArray(ids) ? ids : [ids]);
  const list = loadPhotos();
  const remaining = [];
  list.forEach(p => {
    if (p && idSet.has(p.id)) {
      safeUnlinkFile(p.rawPath);
      safeUnlinkFile(p.wmPath);
      safeUnlinkFile(p.thumbPath);
    } else if (p) {
      remaining.push(p);
    }
  });
  return savePhotos(remaining);
}

/**
 * 一键清理全部无损底图（释放空间，保留全部 120 张水印成品与缩略图）
 */
function clearAllRawOriginals() {
  const list = loadPhotos();
  let freedCount = 0;
  const next = list.map(p => {
    if (p && p.rawPath) {
      if (p.rawPath !== p.wmPath) {
        safeUnlinkFile(p.rawPath);
      }
      freedCount++;
      return { ...p, rawPath: '' };
    }
    return p;
  });
  const saved = savePhotos(next);
  return { photos: saved, freedCount };
}

/**
 * 冷启动异步孤儿文件 GC：扫描 USER_DATA_PATH，清理未被当前相册引用的 amap_* 残留文件
 */
function runOrphanFileGC() {
  return new Promise(resolve => {
    const root = getUserDataPath();
    if (!root || !wx.getFileSystemManager) {
      resolve({ removed: 0 });
      return;
    }
    const list = loadPhotos();
    const activeFiles = new Set();
    list.forEach(p => {
      if (!p) return;
      if (p.wmPath) activeFiles.add(p.wmPath);
      if (p.thumbPath) activeFiles.add(p.thumbPath);
      if (p.rawPath) activeFiles.add(p.rawPath);
    });

    const fs = wx.getFileSystemManager();
    fs.readdir({
      dirPath: root,
      success: res => {
        const files = (res && res.files) || [];
        let removed = 0;
        files.forEach(name => {
          if (
            name.indexOf('amap_wm_') === 0 ||
            name.indexOf('amap_raw_') === 0 ||
            name.indexOf('amap_thumb_') === 0
          ) {
            const fullPath = `${root}/${name}`;
            if (!activeFiles.has(fullPath)) {
              fs.unlink({ filePath: fullPath, fail: () => {} });
              removed++;
            }
          }
        });
        resolve({ removed });
      },
      fail: () => resolve({ removed: 0 })
    });
  });
}

/**
 * 异步估算已用本地存储空间（扫描 USER_DATA_PATH 下 amap_* 文件体积）
 */
function estimateStorageUsage() {
  return new Promise(resolve => {
    const list = loadPhotos();
    const rawCount = list.filter(p => p && p.rawPath).length;
    const root = getUserDataPath();
    if (!root || !wx.getFileSystemManager) {
      resolve({
        totalBytes: 0,
        usedMB: '0.0',
        quotaMB: 200,
        percent: 0,
        photoCount: list.length,
        maxPhotos: MAX_PHOTOS,
        rawCount,
        maxRaw: MAX_RAW_ORIGINALS
      });
      return;
    }

    const fs = wx.getFileSystemManager();
    fs.readdir({
      dirPath: root,
      success: res => {
        const files = ((res && res.files) || []).filter(
          n => n.indexOf('amap_wm_') === 0 || n.indexOf('amap_raw_') === 0 || n.indexOf('amap_thumb_') === 0
        );
        if (files.length === 0) {
          resolve({
            totalBytes: 0,
            usedMB: '0.0',
            quotaMB: 200,
            percent: 0,
            photoCount: list.length,
            maxPhotos: MAX_PHOTOS,
            rawCount,
            maxRaw: MAX_RAW_ORIGINALS
          });
          return;
        }
        let totalBytes = 0;
        let done = 0;
        files.forEach(name => {
          fs.stat({
            path: `${root}/${name}`,
            success: st => {
              if (st && st.stats && typeof st.stats.size === 'number') {
                totalBytes += st.stats.size;
              }
            },
            complete: () => {
              done++;
              if (done === files.length) {
                const usedMB = (totalBytes / (1024 * 1024)).toFixed(1);
                const percent = Math.min(100, Math.round((totalBytes / STORAGE_QUOTA_BYTES) * 100));
                resolve({
                  totalBytes,
                  usedMB,
                  quotaMB: 200,
                  percent,
                  photoCount: list.length,
                  maxPhotos: MAX_PHOTOS,
                  rawCount,
                  maxRaw: MAX_RAW_ORIGINALS
                });
              }
            }
          });
        });
      },
      fail: () => {
        resolve({
          totalBytes: 0,
          usedMB: '0.0',
          quotaMB: 200,
          percent: 0,
          photoCount: list.length,
          maxPhotos: MAX_PHOTOS,
          rawCount,
          maxRaw: MAX_RAW_ORIGINALS
        });
      }
    });
  });
}

module.exports = {
  MAX_PHOTOS,
  MAX_RAW_ORIGINALS,
  STORAGE_KEY_CFG_V9,
  STORAGE_KEY_PHOTOS_V9,
  safeUnlinkFile,
  copyTempToUserData,
  loadUserConfig,
  saveUserConfig,
  loadPhotos,
  savePhotos,
  addPhotoEntry,
  updatePhotoEntry,
  deletePhotosByIds,
  clearAllRawOriginals,
  runOrphanFileGC,
  estimateStorageUsage
};
