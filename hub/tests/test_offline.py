# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later

"""Unit tests for the hub's offline detection and fan-out, using a fake clock."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from hub import Hub  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class Recorder:
    def __init__(self):
        self.frames = []          # (ws, decoded frame)

    def __call__(self, conns, frame):
        for ws in conns:
            self.frames.append((ws, json.loads(frame)))

    def of(self, ws, t=None):
        return [f for w, f in self.frames if w is ws and (t is None or f["t"] == t)]


INFO = {"t": "i", "id": "srv2", "nm": "CORE-02", "ac": "#B6FF00", "hn": "core-02",
        "os": "Ubuntu", "cpu": "x", "cores": 4, "rt": 8.0, "up": 1}


def metric(dev="srv2", c=10.0):
    m = {"t": "m", "id": dev, "ts": 1.0, "c": c, "r": 20.0, "nu": 1, "nd": 2, "tp": None}
    return m, json.dumps(m)


def setup(offline_after=4.5):
    clock, rec = Clock(), Recorder()
    hub = Hub(offline_after=offline_after, clock=clock, send=rec)
    agent_ws, tablet = object(), object()
    hub.agent_hello(dict(INFO), agent_ws)
    hub.client_join(tablet)
    return hub, clock, rec, agent_ws, tablet


def test_device_online_after_hello():
    hub, *_ = setup()
    assert hub.devices["srv2"].online


def test_not_offline_before_timeout():
    hub, clock, rec, _, tablet = setup()
    clock.t += 4.4
    assert hub.check_offline() == []
    assert hub.devices["srv2"].online


def test_offline_after_timeout_and_broadcast():
    hub, clock, rec, _, tablet = setup()
    clock.t += 4.6
    assert hub.check_offline() == ["srv2"]
    assert rec.of(tablet, "st")[-1] == {"t": "st", "id": "srv2", "on": False}
    # reported once only
    clock.t += 10
    assert hub.check_offline() == []
    assert len([f for f in rec.of(tablet, "st") if not f["on"]]) == 1


def test_data_keeps_device_alive():
    hub, clock, rec, _, _ = setup()
    dev = hub.devices["srv2"]
    for _ in range(20):
        clock.t += 1.0
        hub.agent_frame(dev, *metric())
        assert hub.check_offline() == []


def test_comes_back_online_on_data():
    hub, clock, rec, _, tablet = setup()
    clock.t += 5
    hub.check_offline()
    hub.agent_frame(hub.devices["srv2"], *metric())
    assert hub.devices["srv2"].online
    assert rec.of(tablet, "st")[-1]["on"] is True


def test_socket_close_is_immediate_offline_but_stale_socket_ignored():
    hub, clock, rec, agent_ws, tablet = setup()
    new_ws = object()
    hub.agent_hello(dict(INFO), new_ws)          # agent reconnected; old socket still closing
    hub.agent_gone(hub.devices["srv2"], agent_ws)
    assert hub.devices["srv2"].online            # stale close must not flip status
    hub.agent_gone(hub.devices["srv2"], new_ws)
    assert not hub.devices["srv2"].online


def test_agent_cannot_speak_for_another_device():
    hub, clock, rec, _, tablet = setup()
    before = len(rec.frames)
    hub.agent_frame(hub.devices["srv2"], *metric(dev="pc"))
    assert len(rec.frames) == before and "pc" not in hub.devices


def test_history_ring_is_bounded():
    hub, clock, *_ = setup()
    dev = hub.devices["srv2"]
    for i in range(500):
        hub.agent_frame(dev, *metric(c=float(i)))
    assert len(dev.hist["c"]) == 120 and dev.hist["c"][-1] == 499.0


def test_overview_vs_detail_fanout():
    hub, clock, rec, _, tablet = setup()
    other = object()
    hub.agent_hello({**INFO, "id": "pc"}, other)
    c = hub.clients[tablet]
    hub.client_subscribe(c, "detail:srv2")
    rec.frames.clear()
    hub.agent_frame(hub.devices["srv2"], *metric())          # selected: full m
    for _ in range(3):                                        # other device: o every 3 s
        hub.agent_frame(hub.devices["pc"], *metric(dev="pc"))
        clock.t += 1.0
    kinds = [f["t"] + ":" + f["id"] for f in rec.of(tablet)]
    assert kinds[0] == "m:srv2"
    assert kinds.count("o:pc") == 1


def test_history_burst_on_subscribe():
    hub, clock, rec, _, tablet = setup()
    for i in range(70):
        hub.agent_frame(hub.devices["srv2"], *metric(c=float(i)))
    rec.frames.clear()
    hub.client_subscribe(hub.clients[tablet], "overview")
    hs = rec.of(tablet, "hs")[0]
    assert len(hs["c"]) == 60 and set(hs) == {"t", "id", "c", "r"}
    hub.client_subscribe(hub.clients[tablet], "detail:srv2")
    hs = rec.of(tablet, "hs")[-1]
    assert len(hs["c"]) == 70 and "tp" in hs and hs["tp"][0] is None
