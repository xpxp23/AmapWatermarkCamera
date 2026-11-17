App({
  globalData: {
    version: '8.0.0',
    sharedLocationPayload: null
  },
  onLaunch(options) {
    if (options && options.query) {
      this.globalData.launchQuery = options.query;
    }
  }
});
