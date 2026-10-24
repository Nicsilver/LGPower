"""PC-side registerRemoteKeyboard subscriber. The TV's own view of the text field (focus, surroundingTextLength) is
the truth for keyboard trials, so typing is scored from numbers instead of screenshots."""
import asyncio, json, ssl, threading, time

from .util import log, now_ms

KBD_URI = "ssap://com.webos.service.ime/registerRemoteKeyboard"


class KbdWatch:
    def __init__(self, host, out, key="phonelab-watch", perms=None, pairing="PROMPT"):
        self.host, self.out, self.key, self.perms, self.pairing = host, out, key, perms or [], pairing
        self.frames = []          # (pc_ms, focus, length)
        self.lock = threading.Lock()
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.error = None
        self._stop = None

    def start(self, wait=10):
        threading.Thread(target=self._run, daemon=True).start()
        self.ready.wait(wait)
        return self.error is None and self.ready.is_set()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._main())
        except Exception as e:
            self.error = repr(e)
            log(f"kbd watcher ended: {e!r:.120}")
            self.ready.set()

    async def _main(self):
        import websockets
        c = ssl.create_default_context()
        c.check_hostname = False
        c.verify_mode = ssl.CERT_NONE
        self._stop = asyncio.Event()
        async with websockets.connect(f"wss://{self.host}:3001", ssl=c, open_timeout=8) as ws:
            await ws.send(json.dumps({"id": "r", "type": "register", "payload": {
                "client-key": self.key, "pairingType": self.pairing,
                "manifest": {"manifestVersion": 1, "appVersion": "1.1", "permissions": self.perms}}}))
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if m.get("type") == "registered":
                    break
                if m.get("type") == "error":
                    raise RuntimeError(m.get("error"))
            await ws.send(json.dumps({"id": "kbd", "type": "subscribe", "uri": KBD_URI}))
            self.ready.set()
            while not self._stop.is_set():
                try:
                    raw = await asyncio.wait_for(ws.recv(), 1.0)
                except asyncio.TimeoutError:
                    continue
                t = now_ms()
                m = json.loads(raw)
                if m.get("id") != "kbd":
                    continue
                if m.get("type") == "error":
                    self._record(t, None, None, err=m.get("error"))
                    continue
                p = m.get("payload") or {}
                w = p.get("currentWidget")
                if w is not None:
                    self._record(t, bool(w.get("focus")), w.get("surroundingTextLength") if w.get("focus") else None,
                                 cur=w.get("cursorPosition"))
                elif "subscribed" in p:
                    self._record(t, False, None, note="subscribed-without-widget")

    def _record(self, t, focus, length, **extra):
        with self.lock:
            self.frames.append((t, focus, length))
        self.out.write(dict(pc=round(t, 1), focus=focus, len=length, **extra))

    def last(self):
        with self.lock:
            for t, f, n in reversed(self.frames):
                if f is not None:
                    return t, f, n
        return None

    def since(self, t0):
        with self.lock:
            return [f for f in self.frames if f[0] >= t0]

    def stop(self):
        if self._stop is not None:
            self.loop.call_soon_threadsafe(self._stop.set)
