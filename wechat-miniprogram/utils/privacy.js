/**
 * 极速高德水印相机 v1.0 · 微信隐私合规与系统权限统一管理模块
 * 严格对接 wx.onNeedPrivacyAuthorization + 原生 <button open-type="agreePrivacyAuthorization"> 组件
 */

let _privacyListenerBound = false;
let _privacyUiHandler = null;
let _pendingPrivacyResolvers = [];

/**
 * 供 <privacy-popup> 组件注册展示回调
 */
function setPrivacyUiHandler(handler) {
  _privacyUiHandler = typeof handler === 'function' ? handler : null;
  if (_privacyUiHandler && _pendingPrivacyResolvers.length > 0) {
    _privacyUiHandler(true);
  }
}

/**
 * 供 <privacy-popup> 组件在用户点击官方 agreePrivacyAuthorization 按钮或拒绝按钮后结算
 */
function resolvePendingPrivacy(agreed, buttonId) {
  const event = agreed ? 'agree' : 'disagree';
  while (_pendingPrivacyResolvers.length > 0) {
    const fn = _pendingPrivacyResolvers.shift();
    try {
      fn(agreed ? { buttonId: buttonId || 'agree-privacy-btn', event } : { event });
    } catch (e) {}
  }
}

/**
 * 注册全局隐私授权拦截回调（基础库 >= 2.32.3 生效）
 */
function registerPrivacyListener(onNeedAuth) {
  if (typeof onNeedAuth === 'function') {
    _privacyUiHandler = onNeedAuth;
  }
  if (_privacyListenerBound || typeof wx.onNeedPrivacyAuthorization !== 'function') {
    return;
  }
  _privacyListenerBound = true;
  try {
    wx.onNeedPrivacyAuthorization(resolve => {
      _pendingPrivacyResolvers.push(resolve);
      if (typeof _privacyUiHandler === 'function') {
        _privacyUiHandler(true);
      }
    });
  } catch (e) {}
}

/**
 * 主动检查/触发隐私保护协议授权
 */
function ensurePrivacyAuthorized() {
  return new Promise(resolve => {
    if (typeof wx.requirePrivacyAuthorize !== 'function') {
      resolve(true);
      return;
    }
    try {
      wx.requirePrivacyAuthorize({
        success: () => resolve(true),
        fail: () => resolve(false)
      });
    } catch (e) {
      resolve(true);
    }
  });
}

function promptOpenSetting(scope, title, content) {
  return new Promise(resolve => {
    wx.showModal({
      title: title || '需要开启系统权限',
      content: content || '请在设置页面中开启对应权限后继续使用。',
      confirmText: '去设置',
      cancelText: '取消',
      success: res => {
        if (!res.confirm) {
          resolve(false);
          return;
        }
        wx.openSetting({
          success: setRes => {
            const granted = Boolean(setRes && setRes.authSetting && setRes.authSetting[scope]);
            resolve(granted);
          },
          fail: () => resolve(false)
        });
      },
      fail: () => resolve(false)
    });
  });
}

function ensureAlbumWritePermission(interactive) {
  return new Promise(resolve => {
    wx.getSetting({
      success: res => {
        const status = res.authSetting ? res.authSetting['scope.writePhotosAlbum'] : undefined;
        if (status === true) {
          resolve(true);
          return;
        }
        if (status === undefined) {
          wx.authorize({
            scope: 'scope.writePhotosAlbum',
            success: () => resolve(true),
            fail: () => {
              if (!interactive) {
                resolve(false);
                return;
              }
              promptOpenSetting(
                'scope.writePhotosAlbum',
                '需要系统相册权限',
                '开启「添加到相册」权限后，即可将高清水印照片直接保存至手机系统相册。'
              ).then(resolve);
            }
          });
          return;
        }
        if (!interactive) {
          resolve(false);
          return;
        }
        promptOpenSetting(
          'scope.writePhotosAlbum',
          '需要系统相册权限',
          '您之前拒绝了相册写入权限，请在设置中开启「添加到相册」后重试。'
        ).then(resolve);
      },
      fail: () => resolve(true)
    });
  });
}

function preRequestAlbumPermissionSilently() {
  try {
    wx.getSetting({
      success: res => {
        if (res.authSetting && res.authSetting['scope.writePhotosAlbum'] === undefined) {
          wx.authorize({
            scope: 'scope.writePhotosAlbum',
            fail: () => {}
          });
        }
      }
    });
  } catch (e) {}
}

function saveImageToAlbumWithPermission(filePath, successToastTitle, silentSuccess) {
  return new Promise((resolve, reject) => {
    if (!filePath) {
      reject(new Error('无效的图片路径'));
      return;
    }
    const doSave = () => {
      wx.saveImageToPhotosAlbum({
        filePath,
        success: () => {
          if (!silentSuccess) {
            wx.showToast({
              title: successToastTitle || '已存入系统相册',
              icon: 'none'
            });
          }
          resolve(true);
        },
        fail: err => {
          const msg = (err && err.errMsg) || '';
          if (msg.indexOf('auth deny') !== -1 || msg.indexOf('authorize') !== -1) {
            promptOpenSetting(
              'scope.writePhotosAlbum',
              '需要系统相册权限',
              '开启「添加到相册」权限后即可保存至手机系统相册。'
            ).then(granted => {
              if (granted) {
                wx.saveImageToPhotosAlbum({
                  filePath,
                  success: () => {
                    if (!silentSuccess) {
                      wx.showToast({ title: '已补存至系统相册', icon: 'success' });
                    }
                    resolve(true);
                  },
                  fail: e2 => reject(e2)
                });
              } else {
                reject(err);
              }
            });
          } else {
            reject(err);
          }
        }
      });
    };

    ensureAlbumWritePermission(true).then(ok => {
      if (ok) {
        doSave();
      } else {
        reject(new Error('未授予系统相册写入权限'));
      }
    });
  });
}

module.exports = {
  setPrivacyUiHandler,
  resolvePendingPrivacy,
  registerPrivacyListener,
  ensurePrivacyAuthorized,
  promptOpenSetting,
  ensureAlbumWritePermission,
  preRequestAlbumPermissionSilently,
  saveImageToAlbumWithPermission
};
