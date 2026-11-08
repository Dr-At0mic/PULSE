#!/usr/bin/env python3
# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later
"""PULSE hub: receives agent telemetry and streams it to the tablet.

    agents --/agent--> hub --/client--> tablet

* Keeps only the latest snapshot per device plus a 120-sample ring buffer of the
  graphed series, so memory is bounded no matter how long it runs.
* Serialises each outgoing frame once and fans it out with websockets.broadcast(),
  which never blocks on a slow client.
* Marks devices offline after `offline_after` seconds of silence.

Single dependency: `websockets` (>= 13). `PyYAML` is optional (config file).
"""
from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import logging
import os
import re
import signal
import time
from collections import deque
from http import HTTPStatus
from urllib.parse import parse_qs, urlsplit

from websockets.asyncio.server import ServerConnection, broadcast, serve
from websockets.exceptions import ConnectionClosed

__version__ = "1.0.0"
log = logging.getLogger("pulse-hub")
DUMPS = json.JSONEncoder(separators=(",", ":"), allow_nan=False).encode
ID_RE = re.compile(r"^[a-z0-9_-]{1,32}$")

HISTORY = 120          # samples kept per device (2 minutes at 1 Hz)
OVERVIEW_HISTORY = 60  # samples sent to the overview sparklines
DETAIL_OTHERS_EVERY = 3.0
MAX_AGENTS = 32
MAX_CLIENTS = 8
MAX_FRAME = 64 * 1024
SERIES = ("c", "r", "nu", "nd", "tp")


# --------------------------------------------------------------------------- state

class Device:
    __slots__ = ("id", "info", "online", "last_seen", "conn", "hist", "last_o", "last_m", "last_p")

    def __init__(self, dev_id: str):
        self.id = dev_id
        self.info: dict = {}
        self.online = False
        self.last_seen = 0.0
        self.conn: ServerConnection | None = None
        self.hist = {k: deque(maxlen=HISTORY) for k in SERIES}
        self.last_o: str | None = None   # serialised overview summary
        self.last_m: str | None = None   # serialised full metrics
        self.last_p: str | None = None   # serialised processes

    def info_frame(self) -> str:
        return DUMPS({**self.info, "on": self.online})

    def history_frame(self, overview: bool) -> str:
        if overview:
            n = OVERVIEW_HISTORY
            data = {k: list(self.hist[k])[-n:] for k in ("c", "r")}
        else:
            data = {k: list(self.hist[k]) for k in SERIES}
        return DUMPS({"t": "hs", "id": self.id, **data})


class Client:
    __slots__ = ("ws", "mode", "detail_id", "last_other")

    def __init__(self, ws):
        self.ws = ws
        self.mode = "overview"
        self.detail_id: str | None = None
        self.last_other: dict[str, float] = {}


class Hub:
    """All hub logic, free of networking so it can be unit-tested with fake clocks."""

    def __init__(self, offline_after: float = 4.5, clock=time.monotonic, send=None):
        self.offline_after = offline_after
        self.clock = clock
        self.devices: dict[str, Device] = {}
        self.clients: dict[object, Client] = {}
        self.started = clock()
        # send(list_of_ws, frame). Defaults to websockets.broadcast (non-blocking).
        self._send = send or (lambda conns, frame: broadcast(conns, frame))

    # ---- fan-out helpers
    def _to(self, client: Client, frame: str):
        self._send([client.ws], frame)

    def _to_all(self, frame: str):
        if self.clients:
            self._send([c.ws for c in self.clients.values()], frame)

    # ---- agents
    def agent_hello(self, msg: dict, conn) -> Device:
        dev_id = msg.get("id")
        if msg.get("t") != "i" or not isinstance(dev_id, str) or not ID_RE.match(dev_id):
            raise ValueError("first frame must be a valid 'i' message")
        dev = self.devices.get(dev_id)
        if dev is None:
            if len(self.devices) >= MAX_AGENTS:
                raise ValueError("too many devices")
            dev = self.devices[dev_id] = Device(dev_id)
        old = dev.conn
        dev.conn = conn
        dev.info = {k: v for k, v in msg.items() if k in
                    ("t", "id", "nm", "ac", "hn", "os", "cpu", "cores", "rt", "up", "v")}
        dev.last_seen = self.clock()
        self._to_all(dev.info_frame())
        self._set_online(dev, True)
        log.info("agent %s connected (%s)", dev_id, dev.info.get("hn"))
        return old  # caller closes a stale connection for the same id, if any

    def agent_frame(self, dev: Device, msg: dict, raw: str):
        if msg.get("id") != dev.id:
            return  # an agent may only speak for itself
        t = msg.get("t")
        dev.last_seen = self.clock()
        if not dev.online:
            self._set_online(dev, True)
        if t == "m":
            for k in SERIES:
                v = msg.get(k)
                dev.hist[k].append(v if isinstance(v, (int, float)) else None)
            dev.last_m = raw
            dev.last_o = DUMPS({"t": "o", "id": dev.id, "ts": msg.get("ts"), "c": msg.get("c"),
                                "r": msg.get("r"), "nu": msg.get("nu"), "nd": msg.get("nd"),
                                "tp": msg.get("tp")})
            self._fan_metrics(dev, raw)
        elif t == "p":
            dev.last_p = raw
            targets = [c.ws for c in self.clients.values() if c.mode == "detail" and c.detail_id == dev.id]
            if targets:
                self._send(targets, raw)
        elif t == "i":
            self.agent_hello(msg, dev.conn)

    def _fan_metrics(self, dev: Device, raw: str):
        now = self.clock()
        overview, detail = [], []
        for c in self.clients.values():
            if c.mode == "overview":
                overview.append(c.ws)
            elif c.detail_id == dev.id:
                detail.append(c.ws)
            elif now - c.last_other.get(dev.id, 0.0) >= DETAIL_OTHERS_EVERY - 0.05:
                c.last_other[dev.id] = now
                overview.append(c.ws)
        if overview:
            self._send(overview, dev.last_o)
        if detail:
            self._send(detail, raw)

    def agent_gone(self, dev: Device, conn):
        if dev.conn is conn:          # ignore a stale socket closing after a reconnect
            dev.conn = None
            self._set_online(dev, False)
            log.info("agent %s disconnected", dev.id)

    def _set_online(self, dev: Device, on: bool):
        if dev.online != on:
            dev.online = on
            self._to_all(DUMPS({"t": "st", "id": dev.id, "on": on}))

    def check_offline(self) -> list[str]:
        """Mark silent devices offline. Returns ids that changed. Called every 250 ms."""
        now = self.clock()
        changed = []
        for dev in self.devices.values():
            if dev.online and now - dev.last_seen > self.offline_after:
                self._set_online(dev, False)
                changed.append(dev.id)
                log.info("device %s offline (silent for %.1fs)", dev.id, now - dev.last_seen)
        return changed

    # ---- tablet clients
    def client_join(self, ws) -> Client:
        c = self.clients[ws] = Client(ws)
        self._to(c, DUMPS({"t": "w", "v": 1, "n": len(self.devices), "hub": "pulse-hub"}))
        for dev in self.devices.values():
            if dev.info:
                self._to(c, dev.info_frame())
        self.client_subscribe(c, "overview")
        return c

    def client_leave(self, ws):
        self.clients.pop(ws, None)

    def client_subscribe(self, c: Client, mode: str):
        if mode.startswith("detail:") and mode[7:] in self.devices:
            c.mode, c.detail_id = "detail", mode[7:]
            c.last_other.clear()
            dev = self.devices[c.detail_id]
            self._to(c, dev.history_frame(overview=False))
            if dev.last_m:
                self._to(c, dev.last_m)
            if dev.last_p:
                self._to(c, dev.last_p)
        else:
            c.mode, c.detail_id = "overview", None
            for dev in self.devices.values():
                self._to(c, dev.history_frame(overview=True))
                if dev.last_o:
                    self._to(c, dev.last_o)

    def health(self) -> dict:
        return {"ok": True, "devices": len(self.devices),
                "online": sum(d.online for d in self.devices.values()),
                "clients": len(self.clients), "uptime": int(self.clock() - self.started)}


# --------------------------------------------------------------------------- network

def _token_ok(request, expected: str) -> bool:
    got = ""
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        got = auth[7:].strip()
    else:
        got = (parse_qs(urlsplit(request.path).query).get("token") or [""])[0]
    return bool(expected) and hmac.compare_digest(got.encode(), expected.encode())


def make_server(hub: Hub, cfg: dict):
    agent_token, client_token = cfg["agent_token"], cfg["client_token"]

    def process_request(conn: ServerConnection, request):
        path = urlsplit(request.path).path
        if path == "/health":
            resp = conn.respond(HTTPStatus.OK, DUMPS(hub.health()) + "\n")
            resp.headers["Content-Type"] = "application/json"
            return resp
        if path == "/agent":
            if not _token_ok(request, agent_token):
                log.warning("rejected agent from %s: bad token", conn.remote_address)
                return conn.respond(HTTPStatus.UNAUTHORIZED, "bad token\n")
            return None
        if path == "/client":
            if not _token_ok(request, client_token):
                log.warning("rejected client from %s: bad token", conn.remote_address)
                return conn.respond(HTTPStatus.UNAUTHORIZED, "bad token\n")
            if len(hub.clients) >= MAX_CLIENTS:
                return conn.respond(HTTPStatus.SERVICE_UNAVAILABLE, "too many clients\n")
            return None
        return conn.respond(HTTPStatus.NOT_FOUND, "not found\n")

    async def handler(ws: ServerConnection):
        path = urlsplit(ws.request.path).path
        if path == "/agent":
            await _agent(ws)
        elif path == "/client":
            await _client(ws)

    async def _agent(ws: ServerConnection):
        dev = None
        try:
            first = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            old = hub.agent_hello(first, ws)
            dev = hub.devices[first["id"]]
            if old is not None and old is not ws:
                await old.close(4000, "replaced by new connection")
            async for raw in ws:
                if not isinstance(raw, str):
                    raise ValueError("binary frame")
                msg = json.loads(raw)
                if not isinstance(msg, dict):
                    raise ValueError("not an object")
                hub.agent_frame(dev, msg, raw)
        except (ValueError, TypeError, KeyError, asyncio.TimeoutError) as e:
            log.warning("dropping agent %s: %s", ws.remote_address, e)
            await ws.close(4400, "protocol error")
        except ConnectionClosed:
            pass
        finally:
            if dev is not None:
                hub.agent_gone(dev, ws)

    async def _client(ws: ServerConnection):
        c = hub.client_join(ws)
        log.info("tablet connected from %s", ws.remote_address)
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    if msg.get("t") == "sub" and isinstance(msg.get("mode"), str):
                        hub.client_subscribe(c, msg["mode"])
                except (ValueError, AttributeError):
                    pass
        except ConnectionClosed:
            pass
        finally:
            hub.client_leave(ws)
            log.info("tablet disconnected")

    return serve(handler, cfg["host"], cfg["port"], process_request=process_request,
                 max_size=MAX_FRAME, max_queue=16, compression=None,
                 ping_interval=10, ping_timeout=10, server_header=None)


def load_config(path: str | None) -> dict:
    cfg = {"host": "0.0.0.0", "port": 8765, "agent_token": "", "client_token": "",
           "offline_after": 4.5, "log_level": "INFO"}
    if path and os.path.exists(path):
        import yaml  # optional dependency, only needed with a config file
        with open(path, encoding="utf-8") as f:
            cfg.update({k: v for k, v in (yaml.safe_load(f) or {}).items() if v is not None})
    cfg["agent_token"] = os.environ.get("PULSE_AGENT_TOKEN", cfg.get("agent_token") or cfg.get("token", ""))
    cfg["client_token"] = os.environ.get("PULSE_CLIENT_TOKEN", cfg.get("client_token") or cfg["agent_token"])
    if len(cfg["agent_token"]) < 8 or len(cfg["client_token"]) < 8:
        raise SystemExit("config error: tokens must be at least 8 characters")
    return cfg


async def main_async(cfg: dict):
    hub = Hub(offline_after=float(cfg["offline_after"]))
    stop = asyncio.get_running_loop().create_future()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            asyncio.get_running_loop().add_signal_handler(sig, stop.set_result, None)
        except (NotImplementedError, RuntimeError):
            pass  # Windows dev runs: Ctrl+C raises KeyboardInterrupt instead

    async def watchdog():
        while True:
            await asyncio.sleep(0.25)
            hub.check_offline()

    async with make_server(hub, cfg):
        log.info("PULSE hub %s listening on %s:%s", __version__, cfg["host"], cfg["port"])
        task = asyncio.create_task(watchdog())
        await stop
        task.cancel()


def main():
    ap = argparse.ArgumentParser(description="PULSE hub")
    ap.add_argument("-c", "--config", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml"))
    args = ap.parse_args()
    cfg = load_config(args.config)
    logging.basicConfig(level=cfg["log_level"], format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("websockets").setLevel(logging.WARNING)
    try:
        asyncio.run(main_async(cfg))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
