"""TV volume and OLED light for a run (tv skill rule 5): note both right after the wake, set both to 0, restore the noted
values just before `tv.py off`. tv.py has no command for it, so this uses the helper key's SSAP path the way tv.py does
(keys are read by tv.Session from their usual file and never printed).

The noted values live in a small file outside the run folder and survive a crashed run, like the phone borrow note: the next
note keeps the older originals instead of recording the zeros as if they were Nic's settings.

    python -m pl.tvlevels note      # from tools/phonelab
    python -m pl.tvlevels restore
"""
import asyncio, json, os, sys, time

from .truth import SKILLS_TV
from .util import log

NOTE = os.path.expanduser("~/.phonelab-tv-levels.json")
VOL_GET, VOL_SET = "ssap://audio/getVolume", "ssap://audio/setVolume"
SET_GET, SET_SET = "ssap://settings/getSystemSettings", "ssap://settings/setSystemSettings"


def _tv():
    if SKILLS_TV not in sys.path:
        sys.path.insert(0, SKILLS_TV)
    import tv
    return tv


def _ok(r):
    return r.get("type") != "error" and r.get("payload", {}).get("returnValue", True) is not False


def _parse_volume(r):
    payload = r.get("payload", {})
    nested = payload.get("volumeStatus") or payload
    v = nested.get("volume")
    return int(v) if v is not None else None


def _parse_backlight(r):
    v = (r.get("payload", {}).get("settings") or {}).get("backlight")
    return int(v) if v is not None else None


async def _levels(s):
    return _parse_volume(await s.call(VOL_GET)), _parse_backlight(await s.call(SET_GET, {"category": "picture", "keys": ["backlight"]}))


async def _apply(s, volume, backlight):
    r1 = await s.call(VOL_SET, {"volume": int(volume)})
    r2 = await s.call(SET_SET, {"category": "picture", "settings": {"backlight": str(int(backlight))}})
    if not (_ok(r1) and _ok(r2)):
        raise RuntimeError(f"the TV refused the change (volume {r1.get('type')}, light {r2.get('type')})")
    got = await _levels(s)
    if got != (int(volume), int(backlight)):
        raise RuntimeError(f"read back {got}, expected {(int(volume), int(backlight))}")


async def _session():
    tv = _tv()
    return tv.Session(tv.HELPER_KEY, tv.HELPER_PERMS, "PIN")


async def _note_zero(note):
    async with await _session() as s:
        vol, light = await _levels(s)
        if os.path.exists(note):
            with open(note) as f:
                kept = json.load(f)
            log(f"tv levels: an older note exists (a crashed run?); keeping its originals {kept}")
        else:
            if vol is None or light is None:
                raise RuntimeError(f"could not read the TV volume/OLED light (got {vol}, {light})")
            kept = dict(volume=vol, backlight=light)
            with open(note, "w") as f:
                json.dump(kept, f)
        await _apply(s, 0, 0)
        return kept


async def _restore(note):
    with open(note) as f:
        kept = json.load(f)
    async with await _session() as s:
        await _apply(s, kept["volume"], kept["backlight"])
    os.remove(note)
    return kept


def note_and_zero(note=NOTE):
    return asyncio.run(_note_zero(note))


def restore(note=NOTE, tries=3):
    """True when nothing was noted or the values are back; False after the last failed try (the note stays for a retry)."""
    if not os.path.exists(note):
        return True
    for i in range(tries):
        try:
            log(f"tv levels restored to {asyncio.run(_restore(note))}")
            return True
        except Exception as e:
            log(f"tv levels restore attempt {i + 1} failed: {e!r:.160}")
            time.sleep(5)
    return False


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("note", "restore"):
        sys.exit(__doc__)
    if sys.argv[1] == "note":
        print(note_and_zero())
    else:
        sys.exit(0 if restore() else 1)
