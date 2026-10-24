"""Delaying TCP relay: per-chunk one-way latency with optional jitter, delivery kept in order
like TCP. The first client chunk of a connection pays one extra round trip for the SYN exchange."""
import asyncio, random, sys, time, argparse

ap = argparse.ArgumentParser()
ap.add_argument("--listen", type=int, default=3001)
ap.add_argument("--target", type=int, default=3101)
ap.add_argument("--delay", type=float, default=100)       # ms one-way
ap.add_argument("--jitter", default="")                   # "min:max" ms one-way, overrides --delay
a = ap.parse_args()
if a.jitter:
    lo, hi = (float(x) for x in a.jitter.split(":"))
else:
    lo = hi = a.delay
mean = (lo + hi) / 2000.0


async def pump(r, w, first_extra, tag):
    q = asyncio.Queue()
    last = [0.0]

    async def sender():
        while True:
            t, data = await q.get()
            if data is None:
                break
            d = t - time.monotonic()
            if d > 0:
                await asyncio.sleep(d)
            w.write(data)
            await w.drain()
        try:
            w.close()
        except Exception:
            pass

    st = asyncio.create_task(sender())
    first = True
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            t = time.monotonic() + random.uniform(lo, hi) / 1000.0 + (first_extra * 2 * mean if first else 0)
            first = False
            t = max(t, last[0])
            last[0] = t
            q.put_nowait((t, data))
    except Exception:
        pass
    q.put_nowait((last[0], None))
    await st


async def handle(cr, cw):
    try:
        sr, sw = await asyncio.open_connection("127.0.0.1", a.target)
    except Exception:
        cw.close(); return
    await asyncio.gather(pump(cr, sw, 1, "c2s"), pump(sr, cw, 0, "s2c"))


async def main():
    srv = await asyncio.start_server(handle, "0.0.0.0", a.listen)
    async with srv:
        await srv.serve_forever()

asyncio.run(main())
