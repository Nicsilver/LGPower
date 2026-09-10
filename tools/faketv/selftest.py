#!/usr/bin/env python3
"""Starts faketv.py as a child process, drives it through the same sequence
the LGPower app uses (register -> getVolume -> listLaunchPoints ->
getPointerInputSocket -> pointer socket), fetches one icon over HTTP, then
terminates the child by PID (never by image name)."""
import asyncio
import json
import os
import socket
import ssl
import subprocess
import sys
import time
import urllib.request

import websockets

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
HOST = "127.0.0.1"
WS_PORT = 3001
HTTP_PORT = 3002


def wait_for_port(host, port, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return True
        except OSError:
            time.sleep(0.3)
    return False


def client_ssl_context():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


async def run_checks():
    ssl_ctx = client_ssl_context()
    uri = f"wss://{HOST}:{WS_PORT}"
    launch_points = None

    async with websockets.connect(uri, ssl=ssl_ctx) as ws:
        await ws.send(json.dumps({
            "id": "reg_0", "type": "register",
            "payload": {"forcePairing": False, "pairingType": "PROMPT", "manifest": {}},
        }))
        reg_reply = json.loads(await ws.recv())
        print("register       ->", reg_reply)
        assert reg_reply["type"] == "registered"
        assert reg_reply["payload"]["client-key"]

        await ws.send(json.dumps({
            "id": "c1", "type": "request", "uri": "ssap://audio/getVolume", "payload": {},
        }))
        vol_reply = json.loads(await ws.recv())
        print("getVolume      ->", vol_reply)
        assert vol_reply["payload"]["returnValue"] is True
        assert vol_reply["payload"]["volumeStatus"]["volume"] == 18

        await ws.send(json.dumps({
            "id": "c2", "type": "request",
            "uri": "ssap://com.webos.applicationManager/listLaunchPoints", "payload": {},
        }))
        apps_reply = json.loads(await ws.recv())
        launch_points = apps_reply["payload"]["launchPoints"]
        print(f"listLaunchPoints -> {len(launch_points)} apps, first={launch_points[0]}")
        assert len(launch_points) >= 10

        await ws.send(json.dumps({
            "id": "ptr_req", "type": "request",
            "uri": "ssap://com.webos.service.networkinput/getPointerInputSocket", "payload": {},
        }))
        ptr_reply = json.loads(await ws.recv())
        print("getPointerInputSocket ->", ptr_reply)
        socket_path = ptr_reply["payload"]["socketPath"]
        assert socket_path.startswith("wss://")

    async with websockets.connect(socket_path, ssl=ssl_ctx) as pws:
        await pws.send("type:button\nname:UP\n\n")
        print(f"pointer socket -> sent button UP to {socket_path}")

    icon_url = launch_points[0]["icon"]
    with urllib.request.urlopen(icon_url, timeout=5) as resp:
        data = resp.read()
        print(f"icon fetch     -> {icon_url} -> HTTP {resp.status}, {len(data)} bytes")
        assert resp.status == 200
        assert len(data) > 100


def main():
    server_path = os.path.join(SCRIPT_DIR, "faketv.py")
    proc = subprocess.Popen(
        [sys.executable, server_path, "--host", HOST],
        cwd=SCRIPT_DIR,
        stdout=sys.stdout, stderr=sys.stderr,
    )
    try:
        if not wait_for_port(HOST, WS_PORT, timeout=20):
            raise RuntimeError("faketv did not open the WSS port in time")
        # icon http server + icon generation finish just after the WSS port opens
        for _ in range(20):
            if wait_for_port(HOST, HTTP_PORT, timeout=1):
                break
        asyncio.run(run_checks())
        print("SELFTEST PASSED")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


if __name__ == "__main__":
    main()
