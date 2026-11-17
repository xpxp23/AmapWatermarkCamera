App({
  globalData: {
    version: '7.1.0',
    sharedLocationPayload: null
  },
  onLaunch(options) {
    if (options && options.query) {
      this.globalData.launchQuery = options.query;
    }
  }
});
