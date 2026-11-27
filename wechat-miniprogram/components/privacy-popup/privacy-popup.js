/**
 * 极速高德水印相机 v1.0 · 微信官方合规隐私授权弹窗组件
 */
const {
  setPrivacyUiHandler,
  resolvePendingPrivacy
} = require('../../utils/privacy.js');

Component({
  data: {
    visible: false
  },

  lifetimes: {
    attached() {
      setPrivacyUiHandler(show => {
        this.setData({ visible: Boolean(show) });
      });
    },
    detached() {
      setPrivacyUiHandler(null);
    }
  },

  methods: {
    openPrivacyContract() {
      if (typeof wx.openPrivacyContract === 'function') {
        wx.openPrivacyContract({
          fail: () => {
            wx.showToast({ title: '暂无法打开隐私指引', icon: 'none' });
          }
        });
      }
    },

    handleAgree() {
      this.setData({ visible: false });
      resolvePendingPrivacy(true, 'agree-privacy-btn');
    },

    handleDisagree() {
      this.setData({ visible: false });
      resolvePendingPrivacy(false);
    },

    noop() {}
  }
});
