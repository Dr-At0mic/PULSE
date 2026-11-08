#!/usr/bin/env python3
# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later
"""PULSE mock agent: realistic fake telemetry for UI development and demos.

Simulates several machines, each on its own WebSocket connection to the hub
(exactly like real agents), with:
  * idle periods, normal load, and CPU spikes long enough to trigger alerts
  * network bursts (downloads / backups) and disk activity
  * temperatures that lag behind CPU load
  * process lists whose order changes over time
  * one device that periodically drops off the network and comes back
    (alternating between "silent but socket open" = unplugged cable, and
    "socket closed" = agent stopped), so both offline paths are exercised.

Usage:
    python mock_agent.py --hub ws://127.0.0.1:8765 --token devtoken
    python mock_agent.py --devices 4 --offline-every 60
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import random
import time

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

DUMPS = json.JSONEncoder(separators=(",", ":")).encode

PROFILES = [
    dict(id="pc", nm="BATTLESTATION", ac="#00F0FF", hn="ANANDHU-PC", os="Windows 11 Pro 24H2",
         cpu="AMD Ryzen 7 5800X 8-Core Processor", cores=16, rt=32.0, linux=False,
         disks=[("C:", 476.9, 0.71), ("D:", 1863.0, 0.48)], base=14.0, gpu=True,
         procs=["chrome", "code", "explorer", "discord", "steam", "obs64", "spotify", "msedge"]),
    dict(id="srv1", nm="CORE-01", ac="#FF2EC4", hn="core-01", os="Ubuntu 24.04.1 LTS",
         cpu="Intel(R) Core(TM) i5-8500T CPU @ 2.10GHz", cores=6, rt=16.0, linux=True,
         disks=[("/", 234.0, 0.42), ("/srv", 3726.0, 0.67)], base=8.0, gpu=False,
         procs=["dockerd", "postgres", "pulse-hub", "nginx", "jellyfin", "node", "redis-server"]),
    dict(id="srv2", nm="CORE-02", ac="#B6FF00", hn="core-02", os="Ubuntu 24.04.1 LTS",
         cpu="AMD Ryzen 5 5600G with Radeon Graphics", cores=12, rt=64.0, linux=True,
         disks=[("/", 476.0, 0.23)], base=5.0, gpu=False,
         procs=["containerd", "k3s-server", "python3", "minio", "prometheus", "grafana"]),
    dict(id="nas", nm="VAULT", ac="#FFB000", hn="vault", os="Debian 12",
         cpu="Intel(R) Celeron(R) N5105 @ 2.00GHz", cores=4, rt=8.0, linux=True,
         disks=[("/", 58.0, 0.31), ("/mnt/tank", 14901.0, 0.82)], base=3.0, gpu=False,
         procs=["smbd", "zfs", "rsync", "syncthing", "sshd"]),
]


def r1(x: float) -> float:
    return round(x, 1)


class Sim:
    """Generates one device's telemetry with a small state machine."""

    def __init__(self, p: dict, rng: random.Random):
        self.p = p
        self.rng = rng
        self.cpu = p["base"]
        self.cores = [p["base"]] * p["cores"]
        self.temp = 38.0
        self.ram_used = p["rt"] * 0.45
        self.mode = "normal"
        self.mode_left = 5.0
        self.net_burst = 0.0
        self.disk_burst = 0.0
        self.t = 0.0
        self.boot = time.time() - rng.randint(3600, 30 * 86400)
        self.proc_load = {n: rng.uniform(0.2, 6) for n in p["procs"]}
        self.proc_mem = {n: rng.uniform(40, 1400) for n in p["procs"]}

    def _next_mode(self):
        roll = self.rng.random()
        if roll < 0.18:
            self.mode, self.mode_left = "spike", self.rng.uniform(7, 14)   # > 5 s => alert
        elif roll < 0.45:
            self.mode, self.mode_left = "idle", self.rng.uniform(6, 15)
        else:
            self.mode, self.mode_left = "normal", self.rng.uniform(8, 20)
        if self.rng.random() < 0.25:
            self.net_burst = self.rng.uniform(4, 12)
        if self.rng.random() < 0.2:
            self.disk_burst = self.rng.uniform(3, 8)

    def info(self) -> dict:
        p = self.p
        return {"t": "i", "id": p["id"], "nm": p["nm"], "ac": p["ac"], "hn": p["hn"], "os": p["os"],
                "cpu": p["cpu"], "cores": p["cores"], "rt": p["rt"],
                "up": int(time.time() - self.boot), "v": "mock-1.0"}

    def metrics(self, dt: float = 1.0) -> dict:
        p, rng = self.p, self.rng
        self.t += dt
        self.mode_left -= dt
        if self.mode_left <= 0:
            self._next_mode()

        target = {"idle": p["base"] * 0.4, "normal": p["base"] + 18 * (0.5 + 0.5 * math.sin(self.t / 9)),
                  "spike": rng.uniform(92, 99)}[self.mode]
        self.cpu += (target - self.cpu) * (0.6 if self.mode == "spike" else 0.25) + rng.gauss(0, 2.2)
        self.cpu = min(100.0, max(0.3, self.cpu))
        cores = []
        for i in range(p["cores"]):
            v = self.cpu + rng.gauss(0, 12 if self.mode != "spike" else 4) + (8 if i % 4 == 0 else 0)
            self.cores[i] += (min(100, max(0, v)) - self.cores[i]) * 0.6
            cores.append(r1(self.cores[i]))

        # temperature lags CPU load
        t_target = 34 + self.cpu * 0.58
        self.temp += (t_target - self.temp) * 0.08 + rng.gauss(0, 0.3)

        ram_target = p["rt"] * (0.42 + (0.5 if self.mode == "spike" and p["id"] == "srv1" else 0.12 * math.sin(self.t / 40)))
        self.ram_used += (ram_target - self.ram_used) * 0.1
        self.ram_used = min(p["rt"] * 0.985, self.ram_used)

        nd = rng.uniform(2e3, 6e4)
        nu = rng.uniform(1e3, 2e4)
        if self.net_burst > 0:
            self.net_burst -= dt
            nd = rng.uniform(4e7, 1.1e8)
            nu = rng.uniform(5e5, 3e6)
        dr = rng.uniform(0, 2e5)
        dw = rng.uniform(1e4, 4e5)
        if self.disk_burst > 0:
            self.disk_burst -= dt
            dr = rng.uniform(1.5e8, 4.5e8)
            dw = rng.uniform(5e7, 2e8)

        msg = {
            "t": "m", "id": p["id"], "ts": r1(time.time()), "c": r1(self.cpu), "cc": cores,
            "cf": int(3600 + self.cpu * 10 + rng.uniform(-50, 50)),
            "r": r1(self.ram_used / p["rt"] * 100), "ru": r1(self.ram_used), "rt": p["rt"],
            "s": r1(0.4 if self.mode != "spike" else 1.9), "st": 8.0,
            "d": [{"m": m, "p": r1(pct * 100), "u": r1(tot * pct), "t": tot} for (m, tot, pct) in p["disks"]],
            "dr": int(dr), "dw": int(dw), "nu": int(nu), "nd": int(nd),
            "tp": r1(self.temp),  # the PC profile pretends LibreHardwareMonitor is installed
        }
        if p["linux"]:
            msg["rc"] = r1(p["rt"] * 0.18)
            la = self.cpu / 100 * p["cores"]
            msg["l"] = [r1(la), r1(la * 0.8), r1(la * 0.6)]
        if p["gpu"]:
            gu = min(100, max(0, self.cpu * 0.8 + rng.gauss(0, 6)))
            msg["g"] = {"u": r1(gu), "vm": r1(1.2 + gu / 100 * 6), "vt": 8.0, "tp": r1(40 + gu * 0.4)}
        return msg

    def procs(self) -> dict:
        rng = self.rng
        for n in self.proc_load:
            self.proc_load[n] = max(0.0, self.proc_load[n] * 0.7 + rng.uniform(0, 4) * 0.3
                                    + (rng.uniform(10, 40) if self.mode == "spike" and rng.random() < 0.3 else 0))
            self.proc_mem[n] = max(20.0, self.proc_mem[n] + rng.gauss(0, 15))
        top = sorted(self.proc_load.items(), key=lambda kv: -kv[1])[:5]
        return {"t": "p", "id": self.p["id"],
                "ps": [[n, r1(min(c, 100)), int(self.proc_mem[n])] for n, c in top]}


async def run_device(sim: Sim, hub: str, token: str, flaky: bool, offline_every: float, offline_for: float):
    headers = {"Authorization": f"Bearer {token}"}
    url = hub.rstrip("/") + "/agent"
    backoff = 1.0
    drop_style = 0
    next_drop = time.monotonic() + offline_every
    while True:
        try:
            async with connect(url, additional_headers=headers, max_size=2 ** 16) as ws:
                backoff = 1.0
                await ws.send(DUMPS(sim.info()))
                print(f"[{sim.p['id']}] connected")
                tick = 0
                start = time.monotonic()
                while True:
                    if flaky and time.monotonic() >= next_drop:
                        next_drop = time.monotonic() + offline_every + offline_for
                        drop_style ^= 1
                        if drop_style:
                            print(f"[{sim.p['id']}] simulating unplugged cable for {offline_for:.0f}s (silent socket)")
                            await asyncio.sleep(offline_for)   # hub must time out on its own
                            continue
                        print(f"[{sim.p['id']}] simulating agent stop for {offline_for:.0f}s (socket closed)")
                        await ws.close()
                        await asyncio.sleep(offline_for)
                        break
                    await ws.send(DUMPS(sim.metrics()))
                    if tick % 3 == 0:
                        await ws.send(DUMPS(sim.procs()))
                    tick += 1
                    # drift-free 1 Hz schedule
                    await asyncio.sleep(max(0.0, start + tick - time.monotonic()))
        except (OSError, ConnectionClosed) as e:
            print(f"[{sim.p['id']}] link down ({type(e).__name__}); retry in {backoff:.0f}s")
            await asyncio.sleep(backoff)
            backoff = min(30.0, backoff * 2)


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hub", default="ws://127.0.0.1:8765")
    ap.add_argument("--token", default="devtoken")
    ap.add_argument("--devices", type=int, default=3, help=f"1..{len(PROFILES)}")
    ap.add_argument("--offline-every", type=float, default=70.0, help="seconds between simulated outages of the last device")
    ap.add_argument("--offline-for", type=float, default=14.0)
    ap.add_argument("--no-outages", action="store_true")
    ap.add_argument("--seed", type=int, default=None)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    n = max(1, min(len(PROFILES), a.devices))
    tasks = []
    for i, prof in enumerate(PROFILES[:n]):
        flaky = (not a.no_outages) and i == n - 1 and n > 1
        tasks.append(run_device(Sim(prof, random.Random(rng.random())), a.hub, a.token, flaky,
                                a.offline_every, a.offline_for))
    await asyncio.gather(*tasks)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
