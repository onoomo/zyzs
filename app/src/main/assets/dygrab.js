// 抖音直播间地址抓取：在页面上下文内扫真实 DOM，避免 HttpURLConnection 被验证页拦住
(function () {
  if (window.__dyGrab) return;
  window.__dyGrab = 1;
  var timer = null;

  function push(u) {
    try {
      if (!u || u.charAt(0) !== 'h') return;
      AndroidPlayer.onDyUrl(String(u));
    } catch (e) {}
  }

  function scan() {
    try {
      var h = document.documentElement ? document.documentElement.innerHTML : '';
      if (!h || h.length < 200) return;
      // 归一化：先还原 JSON/HTML 转义，否则正则在 \u0026(&) 处截断，
      // sign 等签名参数全丢，地址必然 403
      h = h.replace(/\\u002F/gi, '/')
           .replace(/\\u0026/gi, '&')
           .replace(/\\u003D/gi, '=')
           .replace(/\\u003F/gi, '?')
           .replace(/\\\//g, '/')
           .replace(/&amp;/g, '&');
      var re = /https?:\/\/[^"'\\\s<>]+?\.(?:flv|m3u8)[^"'\\\s<>]*/g, m, n = 0;
      while ((m = re.exec(h)) !== null && n < 40) {
        var u = m[0];
        if (u.indexOf('_sd') >= 0 || u.indexOf('_ld') >= 0) continue;
        push(u);
        n++;
      }
      // 不停扫：页面播放器会不断续期流地址(sign 几分钟过期)，
      // 持续上报让 Or4Helper 一直持有最新地址，录制/播放热替换才有效
    } catch (e) {}
  }

  scan();
  timer = setInterval(scan, 2000);
})();
