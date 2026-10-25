"""Where faketv listens and how the device reaches it. Pure decisions, no I/O beyond the injected port probe, so the
refusals can be unit tested without a device."""

APP_PORT = 3001  # both apps always dial this; only an emulator can be given another by adb reverse


def plan_faketv(emulator, fake_port, device_host, taken):
    """Returns dict(bind, probe, device_host, faketv_args, reverse). `taken(host, port)` says whether something already
    listens there. Exits with an explanation instead of falling back to another address: binding the PC's LAN address
    while another faketv holds 0.0.0.0:3001 is refused by Windows, so the old fallback never worked there."""
    if fake_port == APP_PORT:
        if taken("127.0.0.1", APP_PORT):
            if emulator:
                raise SystemExit(f"port {APP_PORT} is taken on this PC (another faketv?). Rerun with --fake-port 3101 "
                                 f"(or any free port): the emulator reaches it through adb reverse.")
            raise SystemExit(f"port {APP_PORT} is taken on this PC (another faketv?). A real phone always dials the PC's "
                             f"LAN address on {APP_PORT}, so it cannot be redirected: wait for the other run or stop that faketv.")
        return dict(bind="0.0.0.0", probe="127.0.0.1", device_host=device_host, faketv_args=[], reverse=[])
    if not emulator:
        raise SystemExit(f"--fake-port {fake_port} only works on an emulator (adb reverse). A real phone dials the PC's LAN "
                         f"address on {APP_PORT}; free that port or run on an emulator.")
    if not 1024 <= fake_port <= 65534 or fake_port in (APP_PORT, APP_PORT + 1):
        raise SystemExit(f"--fake-port {fake_port} is not usable (pick 1024-65534, not {APP_PORT} or {APP_PORT + 1})")
    icon = fake_port + 1
    for p in (fake_port, icon):
        if taken("127.0.0.1", p):
            raise SystemExit(f"port {p} is taken on this PC; pick another --fake-port (it also uses {icon})")
    return dict(bind="127.0.0.1", probe="127.0.0.1", device_host="127.0.0.1",
                faketv_args=["--port", str(fake_port), "--icon-port", str(icon)],
                reverse=[(APP_PORT, fake_port), (icon, icon)])
