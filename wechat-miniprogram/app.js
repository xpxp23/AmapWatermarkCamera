/**
 * 极速高德水印相机 v9.0.0 · 小程序入口
 * 支持热启动参数消费、冷启动异步孤儿文件 GC 与可配置授时端点
 */

const { runOrphanFileGC } = require('./utils/photo_store.js');
const { initCloudSecurity } = require('./utils/sec_check.js');

App({
  globalData: {
    version: '9.0.0',
    sharedLocationPayload: null,
    launchQuery: null,
    photosDirty: false,
    liveConfigSnapshot: null,
    // 如部署了自有 HTTPS 授时接口（需在微信公众平台配置 request 合法域名），可在此填入
    ntpConfig: {
      endpoint: ''
    }
  },

  onLaunch(options) {
    initCloudSecurity();
    if (options && options.query && Object.keys(options.query).length > 0) {
      this.globalData.launchQuery = options.query;
    }
    // 延后异步执行孤儿文件垃圾回收，不阻塞首帧相机渲染
    setTimeout(() => {
      try {
        runOrphanFileGC();
      } catch (e) {}
    }, 1800);
  },

  onShow(options) {
    if (options && options.query && Object.keys(options.query).length > 0) {
      this.globalData.launchQuery = options.query;
    }
  }
});
