/**
 * 极速高德水印相机 v9.0.0 · 微信隐私合规与系统权限统一管理模块
 * 兼容封装 wx.requirePrivacyAuthorize / wx.onNeedPrivacyAuthorization 及相机/定位/相册授权引导
 */

let _privacyListenerBound = false;
let _pendingPrivacyResolvers = [];

/**
 * 注册全局隐私授权拦截回调（基础库 >= 2.32.3 生效）
 */
function registerPrivacyListener(onNeedAuth) {
  if (_privacyListenerBound || typeof wx.onNeedPrivacyAuthorization !== 'function') {
    return;
  }
  _privacyListenerBound = true;
  try {
    wx.onNeedPrivacyAuthorization(resolve => {
      if (typeof onNeedAuth === 'function') {
        onNeedAuth(resolve);
        return;
      }
      _pendingPrivacyResolvers.push(resolve);
      wx.showModal({
        title: '隐私保护指引',
        content: '为使用相机拍摄、定位水印与保存相册功能，请先阅读并同意《用户隐私保护指引》。',
        confirmText: '同意并继续',
        cancelText: '暂不同意',
        success: res => {
          const event = res.confirm ? 'agree' : 'disagree';
          while (_pendingPrivacyResolvers.length > 0) {
            const fn = _pendingPrivacyResolvers.shift();
            try {
              fn({ buttonId: 'agree-btn', event });
            } catch (e) {}
          }
        },
        fail: () => {
          while (_pendingPrivacyResolvers.length > 0) {
            const fn = _pendingPrivacyResolvers.shift();
            try {
              fn({ event: 'disagree' });
            } catch (e) {}
          }
        }
      });
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

/**
 * 统一引导用户打开小程序设置页开启指定权限
 */
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

/**
 * 预检并申请系统相册写入权限（scope.writePhotosAlbum）
 * @param {boolean} interactive 拒绝后是否弹窗引导去设置
 */
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
        // status === false (曾拒绝)
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

/**
 * 静默预请求相册写入权限（仅在从未询问时触发）
 */
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

/**
 * 安全写入系统相册（带权限预检与二次设置引导）
 */
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
  registerPrivacyListener,
  ensurePrivacyAuthorized,
  promptOpenSetting,
  ensureAlbumWritePermission,
  preRequestAlbumPermissionSilently,
  saveImageToAlbumWithPermission
};
