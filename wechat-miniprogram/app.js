/**
 * 极速高德水印相机 v1.0 · 小程序入口
 * 支持热启动参数消费、冷启动异步孤儿文件 GC、离线拍摄队列监听与权威授时
 */

const { runOrphanFileGC } = require('./utils/photo_store.js');
const { initCloudSecurity } = require('./utils/sec_check.js');

App({
  globalData: {
    version: '1.0',
    sharedLocationPayload: null,
    launchQuery: null,
    photosDirty: false,
    liveConfigSnapshot: null,
    // 如部署了自有 HTTPS 授时接口（需在微信公众平台配置 request 合法域名），可在此填入；
    // 留空时自动通过云函数 regeoPoi 获取服务端权威授时，无需额外域名配置
    ntpConfig: {
      endpoint: ''
    },
    // 可选：如暂未部署 regeoPoi 云函数且已在公众平台配置 request 合法域名，可在此直填 Key 兜底
    poiConfig: {
      qqMapKey: '',
      amapKey: ''
    }
  },

  onLaunch(options) {
    initCloudSecurity();
    if (options && options.query && Object.keys(options.query).length > 0) {
      this.globalData.launchQuery = options.query;
    }
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
