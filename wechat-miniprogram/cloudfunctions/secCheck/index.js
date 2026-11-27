const cloud = require('wx-server-sdk');

cloud.init({
  env: cloud.DYNAMIC_CURRENT_ENV
});

function isRiskyError(err) {
  if (!err) return false;
  if (err.errCode === 87014 || err.code === 87014) return true;
  const msg = String(err.errMsg || err.message || '');
  return msg.indexOf('87014') !== -1 || msg.toLowerCase().indexOf('risky') !== -1;
}

async function handleImageCheck(event) {
  let fileBuffer = null;
  let tempFileIdToDelete = '';

  try {
    if (event.fileID) {
      tempFileIdToDelete = String(event.fileID);
      const downRes = await cloud.downloadFile({
        fileID: tempFileIdToDelete
      });
      fileBuffer = downRes && downRes.fileContent;
    } else if (event.imageBase64) {
      fileBuffer = Buffer.from(String(event.imageBase64), 'base64');
    }

    if (!fileBuffer || fileBuffer.length === 0) {
      return {
        pass: false,
        risky: false,
        code: -1,
        message: 'empty image buffer'
      };
    }

    const res = await cloud.openapi.security.imgSecCheck({
      media: {
        contentType: event.contentType || 'image/jpeg',
        value: fileBuffer
      }
    });

    if (res && res.errCode === 87014) {
      return { pass: false, risky: true, code: 87014 };
    }
    if (res && res.errCode && res.errCode !== 0) {
      return {
        pass: false,
        risky: false,
        code: res.errCode,
        message: res.errMsg || 'imgSecCheck failed'
      };
    }
    return { pass: true, risky: false, code: 0 };
  } catch (err) {
    if (isRiskyError(err)) {
      return { pass: false, risky: true, code: 87014 };
    }
    return {
      pass: false,
      risky: false,
      code: (err && err.errCode) || -1,
      message: (err && (err.errMsg || err.message)) || 'imgSecCheck error'
    };
  } finally {
    if (tempFileIdToDelete) {
      try {
        await cloud.deleteFile({ fileList: [tempFileIdToDelete] });
      } catch (cleanupErr) {}
    }
  }
}

async function handleMsgCheck(event) {
  const content = String((event && event.content) || '').trim();
  if (!content) {
    return { pass: true, risky: false, suggest: 'pass', code: 0 };
  }

  const wxContext = cloud.getWXContext();
  const openid = (wxContext && wxContext.OPENID) || event.openid || '';
  const scene = Number(event.scene) || 3;

  try {
    const res = await cloud.openapi.security.msgSecCheck({
      openid,
      scene,
      version: 2,
      content
    });

    const suggest = res && res.result && res.result.suggest;
    if (res && res.errCode === 87014) {
      return { pass: false, risky: true, suggest: 'risky', code: 87014 };
    }
    if (suggest && suggest !== 'pass') {
      return {
        pass: false,
        risky: true,
        suggest,
        label: (res.result && res.result.label) || 0,
        code: 87014
      };
    }
    if (res && res.errCode && res.errCode !== 0) {
      return {
        pass: false,
        risky: false,
        code: res.errCode,
        message: res.errMsg || 'msgSecCheck failed'
      };
    }
    return { pass: true, risky: false, suggest: 'pass', code: 0 };
  } catch (err) {
    if (isRiskyError(err)) {
      return { pass: false, risky: true, suggest: 'risky', code: 87014 };
    }
    return {
      pass: false,
      risky: false,
      code: (err && err.errCode) || -1,
      message: (err && (err.errMsg || err.message)) || 'msgSecCheck error'
    };
  }
}

exports.main = async event => {
  const action = (event && event.action) || '';
  if (action === 'img') {
    return handleImageCheck(event || {});
  }
  if (action === 'msg') {
    return handleMsgCheck(event || {});
  }
  return {
    pass: false,
    risky: false,
    code: -1,
    message: 'unknown action'
  };
};
