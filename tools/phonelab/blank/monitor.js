// Never draws anything. Events go to the PC listener at once (moves within 25 ms); failures are dropped silently.
// Changed from the tv skill's copy: the 500 ms batching delayed pc_recv by up to half a second per event.
(function () {
  var URL = 'http://192.168.1.178:9931/blank';
  var buf = [], FLUSH_MS = 500, MAX = 200, MOVE_MS = 25, moveTimer = null;
  function rec(type, d) {
    d.type = type; d.t = performance.now(); d.wall = Date.now();
    if (buf.length < 2000) buf.push(d);
    if (buf.length >= MAX) { flush(); return; }
    // pointer moves arrive in the hundreds per second during a drag, so they share one short timer; everything else goes out now
    if (type === 'mousemove' || type === 'wheel') {
      if (!moveTimer) moveTimer = setTimeout(function () { moveTimer = null; flush(); }, MOVE_MS);
    } else {
      flush();
    }
  }
  function flush() {
    if (!buf.length) return;
    var body = JSON.stringify(buf); buf = [];
    try {
      // text/plain + no-cors avoids a CORS preflight; the response is never read.
      fetch(URL, { method: 'POST', mode: 'no-cors', headers: { 'Content-Type': 'text/plain' }, body: body })
        .catch(function () {});
    } catch (e) {}
  }
  function key(e) { return { keyCode: e.keyCode, key: e.key, code: e.code, repeat: e.repeat }; }
  window.addEventListener('keydown', function (e) {
    rec('keydown', key(e));
    if (e.keyCode === 461) e.preventDefault();
  }, true);
  window.addEventListener('keyup', function (e) { rec('keyup', key(e)); }, true);
  window.addEventListener('mousemove', function (e) { rec('mousemove', { x: e.clientX, y: e.clientY }); }, true);
  ['mousedown', 'mouseup', 'click'].forEach(function (n) {
    window.addEventListener(n, function (e) { rec(n, { x: e.clientX, y: e.clientY, button: e.button }); }, true);
  });
  window.addEventListener('wheel', function (e) { rec('wheel', { dx: e.deltaX, dy: e.deltaY }); }, true);
  document.addEventListener('visibilitychange', function () { rec('visibility', { state: document.visibilityState }); flush(); });
  document.addEventListener('webOSRelaunch', function () { rec('relaunch', {}); });
  window.addEventListener('focus', function () { rec('focus', {}); });
  window.addEventListener('blur', function () { rec('blur', {}); });
  rec('start', { w: innerWidth, h: innerHeight });
  setInterval(flush, FLUSH_MS);
})();
