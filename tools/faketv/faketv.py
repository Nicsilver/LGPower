#!/usr/bin/env python3
"""Fake LG webOS TV server for exercising the LGPower remote app (com.nic.lgpower)
on an emulator with no real TV attached.

Every handler below is checked against the exact line in WebOsClient.kt that
parses the corresponding reply, so field names/shapes match what the app reads
rather than what a real webOS TV happens to return. File reference:
C:\\Programming\\LGPowerWidget\\app\\src\\main\\java\\com\\nic\\lgpower\\WebOsClient.kt

Usage:
    python faketv.py --host 10.0.2.2

--host controls only the hostname baked into icon URLs handed back from
listLaunchPoints (the emulator reaches the host machine at 10.0.2.2). The
pointer-socket URL is derived from the Host header of the connecting client,
so it works unmodified whether the app dials in via 10.0.2.2, localhost, or
a LAN IP.
"""
import argparse
import asyncio
import json
import os
import shutil
import ssl
import subprocess
import sys
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import websockets
from PIL import Image, ImageDraw, ImageFont

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CERT_FILE = os.path.join(SCRIPT_DIR, "cert.pem")
KEY_FILE = os.path.join(SCRIPT_DIR, "key.pem")
ICON_DIR = os.path.join(SCRIPT_DIR, "icons")

CLIENT_KEY = "faketv-key-123"
TV_PORT = 3001  # WebOsClient.kt:896 `const val TV_PORT = 3001`

GET_VOLUME_URI = "ssap://audio/getVolume"
GET_POWER_STATE_URI = "ssap://com.webos.service.tvpower/power/getPowerState"


# ── logging ──────────────────────────────────────────────────────────────────

def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def _compact(obj) -> str:
    s = json.dumps(obj, separators=(",", ":"))
    return s if len(s) <= 200 else s[:200] + "..."


# ── in-memory TV state ───────────────────────────────────────────────────────

class TvState:
    def __init__(self):
        self.volume = 18
        self.muted = False
        self.brightness = 70
        # WebOsClient.kt:1191 shows "expert1" ("Expert (Bright Room)") is a
        # real picture-mode id the app's picker offers, used as the initial value.
        self.picture_mode = "expert1"
        self.sound_mode = "standard"
        self.current_input = "HDMI_1"
        # "Screen Off" is a value of the *same* state field webOS reports for
        # power, not a separate flag — WebOsClient.kt:452 `val screenOff get() = state == "Screen Off"`.
        self.power_state = "Active"
        self.power_processing = None
        self.mac_address = "AA:BB:CC:DD:EE:FF"
        self.ip_address = "192.168.1.50"


STATE = TvState()
ALERTS: dict[str, dict] = {}
_alert_seq = 0

# uri -> {(websocket, sub_id)}; CONN_SUBS mirrors it per-connection so we can
# clean up on close/unsubscribe (unsubscribe messages carry no uri — WebOsClient.kt:285
# `ws?.send(JSONObject().put("id", id).put("type", "unsubscribe").toString())`).
SUBSCRIPTIONS: dict[str, set] = defaultdict(set)
CONN_SUBS: dict[object, dict] = defaultdict(dict)


class Config:
    def __init__(self, host, port, icon_port):
        self.host = host
        self.port = port
        self.icon_port = icon_port


CONFIG = Config("10.0.2.2", TV_PORT, 3002)


# ── app / icon catalog ───────────────────────────────────────────────────────
# id/title pairs per the task spec; icon files generated at startup with Pillow.
LAUNCH_POINTS_SRC = [
    ("netflix", "Netflix", "netflix", "N", "#E50914"),
    ("youtube.leanback.v4", "YouTube", "youtube", "YT", "#FF0000"),
    ("com.disney.disneyplus-prod", "Disney+", "disneyplus", "D+", "#113CCF"),
    ("amazon", "Prime Video", "primevideo", "PV", "#00A8E1"),
    ("com.hbo.hbomax", "HBO Max", "hbomax", "HM", "#5B2A8C"),
    ("com.apple.appletv", "Apple TV", "appletv", "TV", "#2B2B2B"),
    ("spotify-beehive", "Spotify", "spotify", "SP", "#1DB954"),
    ("cdp-30", "Plex", "plex", "PX", "#E5A00D"),
    ("com.stremio.tv", "Stremio", "stremio", "ST", "#7B5BF5"),
    ("twitch", "Twitch", "twitch", "TW", "#9146FF"),
    ("com.webos.app.browser", "Browser", "browser", "BR", "#4C8BF5"),
    ("com.webos.app.lgchannels", "LG Channels", "lgchannels", "LG", "#A50034"),
]


def icon_url(icon_file: str) -> str:
    return f"http://{CONFIG.host}:{CONFIG.icon_port}/icon/{icon_file}.png"


def build_launch_points():
    points = []
    for app_id, title, icon_file, _label, _color in LAUNCH_POINTS_SRC:
        url = icon_url(icon_file)
        points.append({
            "id": app_id,
            "title": title,
            "icon": url,
            "largeIcon": url,
            "visible": True,
        })
    return points


LAUNCH_POINTS = build_launch_points()


# ── icon generation (Pillow) ─────────────────────────────────────────────────

def _load_font(size: int):
    candidates = [
        r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arial.ttf",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def generate_icons():
    os.makedirs(ICON_DIR, exist_ok=True)
    font = _load_font(48)
    for _app_id, _title, icon_file, label, color in LAUNCH_POINTS_SRC:
        path = os.path.join(ICON_DIR, f"{icon_file}.png")
        if os.path.isfile(path):
            continue
        size = 128
        img = Image.new("RGB", (size, size), color)
        draw = ImageDraw.Draw(img)
        draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=26, fill=color)
        bbox = draw.textbbox((0, 0), label, font=font)
        w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
        draw.text(((size - w) / 2 - bbox[0], (size - h) / 2 - bbox[1]),
                   label, font=font, fill="#FFFFFF")
        img.save(path, "PNG")
    log(f"icons ready in {ICON_DIR}")


# ── icon HTTP server (port 3002) ─────────────────────────────────────────────

class IconHTTPHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if not self.path.startswith("/icon/"):
            self.send_response(404)
            self.end_headers()
            return
        filename = os.path.basename(self.path[len("/icon/"):].split("?", 1)[0])
        filepath = os.path.join(ICON_DIR, filename)
        if not os.path.isfile(filepath):
            self.send_response(404)
            self.end_headers()
            return
        with open(filepath, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        log(f"[http] {self.address_string()} {fmt % args}")


def start_icon_server(port: int):
    server = ThreadingHTTPServer(("0.0.0.0", port), IconHTTPHandler)
    import threading
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    log(f"icon http server on 0.0.0.0:{port}")
    return server


# ── self-signed cert ─────────────────────────────────────────────────────────

def ensure_cert():
    if os.path.isfile(CERT_FILE) and os.path.isfile(KEY_FILE):
        return
    openssl = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
    if not (openssl == "openssl" or os.path.isfile(openssl)):
        raise RuntimeError("openssl not found on PATH or at the Git Bash fallback path")
    cmd = [
        openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", KEY_FILE, "-out", CERT_FILE, "-days", "365", "-subj", "/CN=faketv",
    ]
    log(f"generating self-signed cert via {openssl}")
    subprocess.run(cmd, check=True, capture_output=True)


# ── settings mutation shared by ssap://settings/setSystemSettings and the
#    luna://com.webos.settingsservice/setSystemSettings alert trick ──────────

def apply_settings(category: str, settings: dict):
    if category == "picture":
        if "backlight" in settings:
            try:
                STATE.brightness = max(0, min(100, int(settings["backlight"])))
            except (TypeError, ValueError):
                pass
        if "pictureMode" in settings:
            STATE.picture_mode = settings["pictureMode"]
    elif category == "sound":
        if "soundMode" in settings:
            STATE.sound_mode = settings["soundMode"]


# ── SSAP / luna handlers ──────────────────────────────────────────────────────
# Each handler: (payload: dict, ws) -> dict merged into {"returnValue": True, ...}

def h_connectionmanager_getinfo(payload, ws):
    # WebOsClient.kt:41-42 `payload.optJSONObject("wifiInfo")?.optString("macAddress")`
    # (falls back to "wiredInfo" — we only need to satisfy the wifiInfo branch)
    return {
        "wifiInfo": {"macAddress": STATE.mac_address, "method": "wifi"},
        "ipAddress": STATE.ip_address,
    }


def h_turn_off_screen(payload, ws):
    # WebOsClient.kt:529 `fun turnOffScreen() = execute(...turnOffScreen)`
    STATE.power_state = "Screen Off"
    STATE.power_processing = None
    asyncio.create_task(push_subscribers(GET_POWER_STATE_URI))
    return {}


def h_turn_on_screen(payload, ws):
    # WebOsClient.kt:530 `fun turnOnScreen() = execute(...turnOnScreen)`
    STATE.power_state = "Active"
    STATE.power_processing = None
    asyncio.create_task(push_subscribers(GET_POWER_STATE_URI))
    return {}


async def _simulate_turn_off():
    # Mirrors the observed transition documented at WebOsClient.kt:901-904:
    # "Active"+processing "Request Power Off" -> "Active Standby"+processing
    # "Request/Prepare Active Standby" -> lands on "Active Standby" ~3s later.
    STATE.power_state = "Active"
    STATE.power_processing = "Request Power Off"
    await push_subscribers(GET_POWER_STATE_URI)
    await asyncio.sleep(0.3)
    STATE.power_state = "Active Standby"
    STATE.power_processing = "Request Active Standby"
    await push_subscribers(GET_POWER_STATE_URI)
    await asyncio.sleep(1.3)
    STATE.power_processing = "Prepare Active Standby"
    await push_subscribers(GET_POWER_STATE_URI)
    await asyncio.sleep(1.3)
    STATE.power_processing = None
    await push_subscribers(GET_POWER_STATE_URI)


def h_system_turn_off(payload, ws):
    # WebOsClient.kt:531 `fun turnOff() = execute("ssap://system/turnOff")`
    asyncio.create_task(_simulate_turn_off())
    return {}


def h_get_power_state(payload, ws):
    # WebOsClient.kt:499-503 reads payload.optString("state") and
    # payload.optString("processing") directly (not nested).
    out = {"state": STATE.power_state}
    if STATE.power_processing:
        out["processing"] = STATE.power_processing
    return out


def _volume_payload():
    # WebOsClient.kt:550-556: nested "volumeStatus" wins when present, else
    # falls back to flat "volume"/"muted" — we send both so either path works.
    return {
        "volumeStatus": {
            "volume": STATE.volume,
            "muteStatus": STATE.muted,
            "soundOutput": "tv_speaker",
        },
        "volume": STATE.volume,
        "muted": STATE.muted,
    }


def h_get_volume(payload, ws):
    return _volume_payload()


def h_set_volume(payload, ws):
    # WebOsClient.kt:574-577 `execute("ssap://audio/setVolume", {"volume": level})`
    try:
        STATE.volume = max(0, min(100, int(payload.get("volume", STATE.volume))))
    except (TypeError, ValueError):
        pass
    asyncio.create_task(push_subscribers(GET_VOLUME_URI))
    return {}


def h_volume_up(payload, ws):
    # Not called directly by the app (it uses the pointer-socket VOLUMEUP key,
    # WebOsClient.kt:539), included per spec for completeness/extension.
    STATE.volume = min(100, STATE.volume + 1)
    asyncio.create_task(push_subscribers(GET_VOLUME_URI))
    return {}


def h_volume_down(payload, ws):
    STATE.volume = max(0, STATE.volume - 1)
    asyncio.create_task(push_subscribers(GET_VOLUME_URI))
    return {}


def h_set_mute(payload, ws):
    # Not called directly (muteToggle() at WebOsClient.kt:541 uses pointer key
    # "MUTE" instead), included per spec for completeness/extension.
    STATE.muted = bool(payload.get("mute", not STATE.muted))
    asyncio.create_task(push_subscribers(GET_VOLUME_URI))
    return {}


def h_get_system_settings(payload, ws):
    # WebOsClient.kt:582-586 (backlight), :672-677 (pictureMode), :722-726
    # (soundMode) all read payload.optJSONObject("settings")?.optString(key).
    category = payload.get("category", "")
    keys = payload.get("keys", []) or []
    settings = {}
    for k in keys:
        if k == "backlight":
            settings[k] = str(STATE.brightness)
        elif k == "pictureMode":
            settings[k] = STATE.picture_mode
        elif k == "soundMode":
            settings[k] = STATE.sound_mode
        elif k == "energySaving":
            settings[k] = "off"
        else:
            settings[k] = ""
    return {"category": category, "settings": settings}


def h_set_system_settings(payload, ws):
    # Not called directly by the app (WRITE_SETTINGS isn't granted to the flat
    # manifest — see the comment above buildRegistration at WebOsClient.kt:89-92
    # and lunaRequest at :687-717 for the alert-based workaround it uses
    # instead). Included as a plain ssap:// handler per spec for completeness.
    category = payload.get("category", "")
    settings = payload.get("settings", {}) or {}
    apply_settings(category, settings)
    return {"category": category, "settings": settings}


def h_get_external_input_list(payload, ws):
    # WebOsClient.kt:645-653 reads payload.optJSONArray("devices"), each with
    # optString("id") and optString("label").
    devices = [
        {"id": "HDMI_1", "label": "PlayStation 5", "port": 1},
        {"id": "HDMI_2", "label": "Apple TV", "port": 2},
        {"id": "HDMI_3", "label": "HDMI 3", "port": 3},
        {"id": "HDMI_4", "label": "HDMI 4", "port": 4},
    ]
    return {"devices": devices}


def h_switch_input(payload, ws):
    # WebOsClient.kt:658-661 `execute("ssap://tv/switchInput", {"inputId": id})`
    input_id = payload.get("inputId")
    if input_id:
        STATE.current_input = input_id
    return {}


def h_launch(payload, ws):
    # WebOsClient.kt:729-733; execute() only checks returnValue, no fields parsed.
    app_id = payload.get("id", "")
    log(f"LAUNCH app={app_id!r}")
    return {"id": app_id}


def h_insert_text(payload, ws):
    # WebOsClient.kt:737-740 `execute(..., {"text": text, "replace": 0})`
    log(f"INSERT TEXT text={payload.get('text', '')!r} replace={payload.get('replace')}")
    return {}


def h_list_launch_points(payload, ws):
    # WebOsClient.kt:754-768: reads payload.optJSONArray("launchPoints"); each
    # obj.optBoolean("visible", true), optString("appId") ?: optString("id"),
    # title, and largeIcon ?: icon.
    return {"launchPoints": LAUNCH_POINTS}


def h_get_pointer_input_socket(payload, ws):
    # WebOsClient.kt:352-353 `json.optJSONObject("payload")?.optString("socketPath")`
    # Reuse whatever host:port the client dialed in on so this works whether
    # it connected via 10.0.2.2 (emulator), localhost, or a LAN IP.
    host_header = None
    try:
        if ws is not None and ws.request is not None:
            host_header = ws.request.headers.get("Host")
    except Exception:
        host_header = None
    if not host_header:
        host_header = f"{CONFIG.host}:{CONFIG.port}"
    return {"socketPath": f"wss://{host_header}/pointer"}


def h_create_alert(payload, ws):
    # WebOsClient.kt:692-701 (inside lunaRequest): creates an alert whose
    # "onclose"/"onfail" reference a luna:// URI + params; the app immediately
    # calls closeAlert next, which is expected to trigger that luna call.
    global _alert_seq
    _alert_seq += 1
    alert_id = f"alert_{_alert_seq}"
    action = payload.get("onclose") or payload.get("onfail")
    if action:
        ALERTS[alert_id] = action
    return {"alertId": alert_id}


def h_close_alert(payload, ws):
    # WebOsClient.kt:709-712 `session.send(..., {"alertId": alertId})`
    alert_id = payload.get("alertId")
    action = ALERTS.pop(alert_id, None) if alert_id else None
    if action:
        uri = action.get("uri", "")
        params = action.get("params", {}) or {}
        if uri == "luna://com.webos.settingsservice/setSystemSettings":
            apply_settings(params.get("category", ""), params.get("settings", {}) or {})
    return {}


HANDLERS = {
    "ssap://com.webos.service.connectionmanager/getinfo": h_connectionmanager_getinfo,
    "ssap://com.webos.service.tvpower/power/turnOffScreen": h_turn_off_screen,
    "ssap://com.webos.service.tvpower/power/turnOnScreen": h_turn_on_screen,
    "ssap://system/turnOff": h_system_turn_off,
    GET_POWER_STATE_URI: h_get_power_state,
    GET_VOLUME_URI: h_get_volume,
    "ssap://audio/setVolume": h_set_volume,
    "ssap://audio/volumeUp": h_volume_up,
    "ssap://audio/volumeDown": h_volume_down,
    "ssap://audio/setMute": h_set_mute,
    "ssap://settings/getSystemSettings": h_get_system_settings,
    "ssap://settings/setSystemSettings": h_set_system_settings,
    "ssap://tv/getExternalInputList": h_get_external_input_list,
    "ssap://tv/switchInput": h_switch_input,
    "ssap://system.launcher/launch": h_launch,
    "ssap://com.webos.service.ime/insertText": h_insert_text,
    "ssap://com.webos.applicationManager/listLaunchPoints": h_list_launch_points,
    "ssap://com.webos.service.networkinput/getPointerInputSocket": h_get_pointer_input_socket,
    "ssap://system.notifications/createAlert": h_create_alert,
    "ssap://system.notifications/closeAlert": h_close_alert,
}


async def push_subscribers(uri: str):
    handler = HANDLERS.get(uri)
    if handler is None:
        return
    resp_payload = {"returnValue": True, **(handler({}, None) or {})}
    dead = []
    for entry in list(SUBSCRIPTIONS.get(uri, ())):
        ws, sub_id = entry
        try:
            await ws.send(json.dumps({"type": "response", "id": sub_id, "payload": resp_payload}))
        except Exception:
            dead.append(entry)
    for entry in dead:
        SUBSCRIPTIONS[uri].discard(entry)


def cleanup_connection(ws):
    subs = CONN_SUBS.pop(ws, {})
    for sub_id, uri in subs.items():
        SUBSCRIPTIONS[uri].discard((ws, sub_id))


def cleanup_one_subscription(ws, sub_id):
    uri = CONN_SUBS.get(ws, {}).pop(sub_id, None)
    if uri:
        SUBSCRIPTIONS[uri].discard((ws, sub_id))


# ── command (SSAP) connection ─────────────────────────────────────────────────

async def handle_command_message(ws, raw: str):
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        log(f"<- invalid json: {raw[:200]!r}")
        return

    mtype = msg.get("type")
    mid = msg.get("id")
    uri = msg.get("uri")
    payload = msg.get("payload") or {}
    log(f"<- type={mtype} id={mid} uri={uri} payload={_compact(payload)}")

    if mtype == "register":
        # Always reply "registered" immediately, whether or not the incoming
        # payload carries a saved "client-key" — never challenge/prompt, so a
        # returning client doesn't get stuck waiting on a pairing accept that
        # will never come. WebOsClient.kt:176 reads
        # `json.optJSONObject("payload")?.optString("client-key")` from this reply.
        await ws.send(json.dumps({
            "type": "registered", "id": mid, "payload": {"client-key": CLIENT_KEY},
        }))
        return

    if mtype == "unsubscribe":
        cleanup_one_subscription(ws, mid)
        return

    if mtype not in ("request", "subscribe"):
        log(f"UNHANDLED message type: {mtype!r}")
        return

    handler = HANDLERS.get(uri)
    if handler is None:
        log(f"UNHANDLED URI: {uri!r} (payload={_compact(payload)})")
        resp_payload = {"returnValue": True}
    else:
        resp_payload = {"returnValue": True, **(handler(payload, ws) or {})}

    # WebOsClient.kt:187,199-200: both plain responses and the first subscribe
    # reply are type "response" carrying the same "id" the request/subscribe used.
    await ws.send(json.dumps({"type": "response", "id": mid, "payload": resp_payload}))

    if mtype == "subscribe" and uri:
        SUBSCRIPTIONS[uri].add((ws, mid))
        CONN_SUBS[ws][mid] = uri


async def command_connection(ws):
    peer = ws.remote_address
    log(f"[cmd] connected {peer}")
    try:
        async for raw in ws:
            await handle_command_message(ws, raw)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        cleanup_connection(ws)
        log(f"[cmd] closed {peer}")


# ── pointer connection ────────────────────────────────────────────────────────
# WebOsClient.kt:398-401: plain-text frames "type:button\nname:X\n\n" etc, no
# JSON, no registration on this socket (registration happened on the socket
# that requested getPointerInputSocket, which is then closed).

def _parse_pointer_frame(text: str) -> dict:
    fields = {}
    for line in text.strip("\n").split("\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            fields[k.strip()] = v.strip()
    return fields


async def handle_pointer_message(raw: str):
    fields = _parse_pointer_frame(raw)
    log(f"[pointer] <- {fields}")
    if fields.get("type") == "button":
        name = fields.get("name", "")
        if name == "VOLUMEUP":
            STATE.volume = min(100, STATE.volume + 1)
            await push_subscribers(GET_VOLUME_URI)
        elif name == "VOLUMEDOWN":
            STATE.volume = max(0, STATE.volume - 1)
            await push_subscribers(GET_VOLUME_URI)
        elif name == "MUTE":
            STATE.muted = not STATE.muted
            await push_subscribers(GET_VOLUME_URI)


async def pointer_connection(ws):
    peer = ws.remote_address
    log(f"[pointer] connected {peer}")
    try:
        async for raw in ws:
            await handle_pointer_message(raw)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        log(f"[pointer] closed {peer}")


# ── router ────────────────────────────────────────────────────────────────────

async def router(ws):
    path = ws.request.path if ws.request is not None else "/"
    if path.startswith("/pointer"):
        await pointer_connection(ws)
    else:
        await command_connection(ws)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Fake LG webOS TV for LGPower app testing")
    parser.add_argument("--host", default="10.0.2.2",
                         help="host baked into icon URLs (emulator reaches the host machine at 10.0.2.2)")
    parser.add_argument("--port", type=int, default=TV_PORT, help="WSS port for SSAP (default 3001)")
    parser.add_argument("--icon-port", type=int, default=3002, help="HTTP port for app icons (default 3002)")
    args = parser.parse_args()

    CONFIG.host = args.host
    CONFIG.port = args.port
    CONFIG.icon_port = args.icon_port
    global LAUNCH_POINTS
    LAUNCH_POINTS = build_launch_points()

    ensure_cert()
    generate_icons()
    start_icon_server(CONFIG.icon_port)

    ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_ctx.load_cert_chain(CERT_FILE, KEY_FILE)

    async def run():
        async with websockets.serve(router, "0.0.0.0", CONFIG.port, ssl=ssl_ctx):
            log(f"faketv listening wss://0.0.0.0:{CONFIG.port} "
                f"(icons at http://{CONFIG.host}:{CONFIG.icon_port}/icon/<id>.png)")
            await asyncio.Future()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
