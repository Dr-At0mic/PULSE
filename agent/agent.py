#!/usr/bin/env python3
# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later
"""PULSE agent: samples local system metrics and pushes them to the hub.

Design for a < 0.5 % CPU / < 40 MB RAM budget:
  * one asyncio loop, a drift-free 1 Hz schedule, no threads (unless the
    optional LibreHardwareMonitor reader is enabled), no busy waiting
  * expensive things run less often: process scan every 3 s, temperatures
    and disk usage every 2-5 s, partition/interface discovery every 60 s
  * the top-process scan reads only CPU times for every process and
    asks for name + memory only for the 5 winners
  * GPU via a one-shot nvidia-smi every 2 s (NVML in-process is optional:
    it is cheaper on CPU but its driver DLL adds ~23 MB of RAM)
  * all rates are computed here, so the tablet does no math

The agent only sends. It opens no ports and accepts no commands.
"""
from __future__ import annotations

import argparse
import asyncio
import gc
import heapq
import json
import logging
import os
import platform
import random
import re
import shutil
import socket
import struct
import sys
import time

# Windows installs keep their packages in a private "lib" folder next to this file.
_LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib")
if os.path.isdir(_LIB):
    sys.path.insert(0, _LIB)

import psutil  # noqa: E402

__version__ = "1.0.0"
log = logging.getLogger("pulse-agent")
DUMPS = json.JSONEncoder(separators=(",", ":"), allow_nan=False).encode
IS_WIN = sys.platform == "win32"
IS_LINUX = sys.platform.startswith("linux")
MB = 1024 * 1024
GB = 1024 ** 3


def r1(x):
    return None if x is None else round(float(x), 1)


# --------------------------------------------------------------------------- rates

class RateCounter:
    """Turns a monotonically increasing byte counter into bytes/second.

    Handles the first sample (no rate yet -> 0), counter resets / wraps
    (negative delta -> 0 instead of a huge spike) and zero time deltas.
    """
    __slots__ = ("prev", "prev_t")

    def __init__(self):
        self.prev = None
        self.prev_t = None

    def update(self, value: int, now: float) -> int:
        prev, prev_t = self.prev, self.prev_t
        self.prev, self.prev_t = value, now
        if prev is None or now <= prev_t:
            return 0
        delta = value - prev
        if delta < 0:            # counter reset (driver reload, 32-bit wrap, NIC replaced)
            return 0
        return int(delta / (now - prev_t))


class MultiRate:
    """Sums the rates of several named counters (e.g. one per NIC or disk).

    Rates are computed per counter and then summed, so a counter that appears
    or disappears never produces a negative or bogus spike in the total.
    """

    def __init__(self):
        self.counters: dict[str, RateCounter] = {}

    def update(self, values: dict[str, int], now: float) -> int:
        total = 0
        for name, v in values.items():
            rc = self.counters.get(name)
            if rc is None:
                rc = self.counters[name] = RateCounter()
            total += rc.update(v, now)
        for gone in self.counters.keys() - values.keys():
            del self.counters[gone]
        return total


_VIRTUAL_NIC = re.compile(
    r"^(lo|docker|veth|br-|virbr|vnet|tun|tap|wg|zt|tailscale|cni|flannel|cali|kube|lxc|lxd|"
    r"vmnet|vboxnet|dummy|bond_slave|ifb|sit|ip6tnl|gre)|"
    r"(loopback|vethernet|virtualbox|vmware|hyper-v|pseudo|teredo|isatap|6to4|bluetooth|npcap|"
    r"wan miniport|wsl|nordlynx|zerotier|tailscale)", re.I)


def is_real_nic(name: str) -> bool:
    return not _VIRTUAL_NIC.search(name)


_VIRTUAL_DISK = re.compile(r"^(loop|ram|zram|dm-|md|sr|fd|nbd)", re.I)


def is_physical_disk(name: str) -> bool:
    return not _VIRTUAL_DISK.match(name)


# --------------------------------------------------------------------------- static info

def os_name() -> str:
    if IS_WIN:
        build = int((platform.version().split(".") + ["0", "0", "0"])[2] or 0)
        return f"Windows {'11' if build >= 22000 else platform.release()} (build {build})"
    try:
        with open("/etc/os-release", encoding="utf-8") as f:
            for line in f:
                if line.startswith("PRETTY_NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return f"{platform.system()} {platform.release()}"


def cpu_model() -> str:
    try:
        if IS_WIN:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as k:
                return winreg.QueryValueEx(k, "ProcessorNameString")[0].strip()
        if IS_LINUX:
            with open("/proc/cpuinfo", encoding="utf-8") as f:
                for line in f:
                    if line.startswith(("model name", "Hardware", "Model")):
                        return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "unknown CPU"


# --------------------------------------------------------------------------- GPU

class Gpu:
    """NVIDIA GPU stats, refreshed every 2 s. `latest` stays None when there is no GPU.

    Modes (config `gpu:`), measured on a Ryzen 9 9900X + RTX 5070 Ti:
      smi  (default) one-shot `nvidia-smi` every `gpu_interval` s (2). The agent stays
           ~27 MB private; each call costs ~22 ms CPU in the short-lived child plus ~5 ms
           in the agent: ~1.3 % of one core at 2 s, ~0.5 % at 5 s.
      nvml in-process NVML via nvidia-ml-py. Near-zero CPU but the NVIDIA driver
           library adds ~23 MB to the agent, which breaks the 40 MB budget.
      off  no GPU field at all.
    """

    def __init__(self, mode: str, interval: float = 2.0):
        self.latest: dict | None = None
        self.interval = max(1.0, interval)
        self.mode = mode if mode in ("smi", "nvml", "off") else "smi"
        self._nv = self._handle = None
        self._smi = shutil.which("nvidia-smi") if self.mode == "smi" else None
        if self.mode == "nvml":
            try:
                import pynvml
                pynvml.nvmlInit()
                self._handle = pynvml.nvmlDeviceGetHandleByIndex(0)
                self._nv = pynvml
            except Exception as e:
                log.warning("NVML unavailable (%s); GPU stats disabled", e)

    @property
    def available(self) -> bool:
        return self._nv is not None or self._smi is not None

    def _read_nvml(self) -> dict:
        nv, h = self._nv, self._handle
        mem = nv.nvmlDeviceGetMemoryInfo(h)
        return {"u": r1(nv.nvmlDeviceGetUtilizationRates(h).gpu), "vm": r1(mem.used / GB),
                "vt": r1(mem.total / GB), "tp": r1(nv.nvmlDeviceGetTemperature(h, nv.NVML_TEMPERATURE_GPU))}

    async def _read_smi(self) -> dict:
        kw = {"creationflags": 0x08000000} if IS_WIN else {}  # CREATE_NO_WINDOW
        proc = await asyncio.create_subprocess_exec(
            self._smi, "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu",
            "--format=csv,noheader,nounits", "--id=0",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, **kw)
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=5)
        u, used, total, tp = (float(x) for x in out.decode().split(",")[:4])
        return {"u": r1(u), "vm": r1(used / 1024), "vt": r1(total / 1024), "tp": r1(tp)}

    async def run(self):
        log.info("GPU: %s", self.mode)
        failures = 0
        while True:
            try:
                self.latest = self._read_nvml() if self._nv is not None else await self._read_smi()
                failures = 0
            except Exception as e:  # driver reset, GPU asleep, parse error
                log.debug("GPU read failed: %s", e)
                self.latest = None
                failures += 1
            # back off to 30 s if the GPU keeps failing, so a broken driver costs nothing
            await asyncio.sleep(self.interval if failures < 5 else 30)


# --------------------------------------------------------------------------- temperatures

_TEMP_PREF = ("coretemp", "k10temp", "zenpower", "cpu_thermal", "cpu-thermal", "soc_thermal", "acpitz")
_LABEL_PREF = ("package id 0", "tctl", "tdie", "cpu", "")


def pick_cpu_temp(sensors: dict) -> float | None:
    """Choose the CPU package temperature out of psutil.sensors_temperatures()."""
    for chip in _TEMP_PREF:
        entries = sensors.get(chip)
        if not entries:
            continue
        for want in _LABEL_PREF:
            for e in entries:
                if (e.label or "").lower().startswith(want) and e.current and 0 < e.current < 130:
                    return e.current
    return None


class LhmReader:
    """Optional Windows CPU temperature from LibreHardwareMonitor's built-in web server
    (Options -> Remote Web Server -> Run, default http://127.0.0.1:8085/data.json).
    One local HTTP GET every 3 s in a worker thread; much cheaper than WMI/COM."""

    def __init__(self, url: str):
        self.url = url
        self.latest: float | None = None

    def _fetch(self) -> float | None:
        import urllib.request
        with urllib.request.urlopen(self.url, timeout=2) as r:
            tree = json.load(r)
        best = None
        stack = [tree]
        while stack:
            node = stack.pop()
            text = str(node.get("Text", ""))
            if text in ("CPU Package", "Core (Tctl/Tdie)", "Core Average", "CPU Cores"):
                m = re.match(r"\s*([\d.,]+)", str(node.get("Value", "")))
                if m:
                    v = float(m.group(1).replace(",", "."))
                    if text in ("CPU Package", "Core (Tctl/Tdie)"):
                        return v
                    best = best or v
            stack.extend(node.get("Children", []))
        return best

    async def run(self):
        loop = asyncio.get_running_loop()
        while True:
            try:
                self.latest = await loop.run_in_executor(None, self._fetch)
            except Exception as e:
                log.debug("LHM read failed: %s", e)
                self.latest = None
            await asyncio.sleep(3)


# --------------------------------------------------------------------------- Windows NIC counters

class WinNet:
    """Byte counters for just the real NICs via GetIfEntry2.

    psutil.net_io_counters() calls GetIfTable2, which enumerates every pseudo-interface
    Windows has (WFP filters, Hyper-V, Teredo...): measured ~2 ms CPU per call. Reading the
    1-2 real NICs by index is ~50x cheaper. Offsets (x64 MIB_IF_ROW2) were verified
    byte-for-byte against psutil: InterfaceIndex@8, Alias@28, InOctets@1208, OutOctets@1280.
    """
    ROW = 1352

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self._ct = ctypes
        lib = ctypes.WinDLL("iphlpapi")
        self._table = lib.GetIfTable2
        self._table.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        self._table.restype = wintypes.ULONG
        self._free = lib.FreeMibTable
        self._free.argtypes = [ctypes.c_void_p]
        self._entry = lib.GetIfEntry2
        self._entry.argtypes = [ctypes.c_void_p]
        self._entry.restype = wintypes.ULONG
        self._buf = ctypes.create_string_buffer(self.ROW)
        self.index: dict[str, int] = {}

    def map_names(self, names: set[str]):
        """alias -> interface index, refreshed with interface discovery (every 30 s)."""
        ct = self._ct
        p = ct.c_void_p()
        if self._table(ct.byref(p)) != 0:
            raise OSError("GetIfTable2 failed")
        try:
            n = ct.cast(p, ct.POINTER(ct.c_ulong))[0]
            out = {}
            for i in range(n):
                row = ct.string_at(p.value + 8 + i * self.ROW, 28 + 514)
                alias = row[28:].decode("utf-16-le", "ignore").split("\0", 1)[0]
                if alias in names:
                    out[alias] = struct.unpack_from("<I", row, 8)[0]
            self.index = out
        finally:
            self._free(p)

    def read(self) -> dict[str, tuple[int, int]]:
        buf, out = self._buf, {}
        for name, idx in self.index.items():
            ct = self._ct
            ct.memset(buf, 0, 16)
            struct.pack_into("<I", buf, 8, idx)
            if self._entry(buf) == 0:
                out[name] = struct.unpack_from("<Q", buf, 1208)[0], struct.unpack_from("<Q", buf, 1280)[0]
        return out


# --------------------------------------------------------------------------- collector

class Collector:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.id = cfg["id"]
        self.ncpu = psutil.cpu_count(logical=True) or 1
        self.net_up = MultiRate()
        self.net_down = MultiRate()
        self.disk_r = MultiRate()
        self.disk_w = MultiRate()
        self.gpu = Gpu(cfg["gpu"], cfg["gpu_interval"]) if cfg["gpu"] != "off" else None
        self.lhm = LhmReader(cfg["lhm_url"]) if IS_WIN and cfg.get("lhm_url") else None
        self._parts: list[tuple[str, str]] = []
        self._parts_at = -1e9
        self._disk_cache: list[dict] = []
        self._disk_at = -1e9
        self._nics: set[str] = set()
        self._nics_at = -1e9
        self._temp: float | None = None
        self._temp_at = -1e9
        self._freq: int | None = None
        self._freq_at = -1e9
        self._proc_prev: dict = {}
        self._proc_prev_t = None
        self._scanner = PsutilScanner()
        if IS_WIN and struct.calcsize("P") == 8:
            try:
                self._scanner = WinScanner()
            except OSError as e:
                log.warning("fast process scan unavailable (%s); using psutil", e)
        self._swap = None
        self._swap_at = -1e9
        self._winnet = None
        if IS_WIN and struct.calcsize("P") == 8:
            try:
                self._winnet = WinNet()
            except OSError as e:
                log.warning("fast NIC counters unavailable (%s); using psutil", e)
        psutil.cpu_percent(percpu=True)  # prime: the first call always returns 0.0

    # ---- static
    def info(self) -> str:
        vm = psutil.virtual_memory()
        return DUMPS({"t": "i", "id": self.id, "nm": self.cfg["name"], "ac": self.cfg["accent"],
                      "hn": socket.gethostname(), "os": os_name(), "cpu": cpu_model(),
                      "cores": self.ncpu, "rt": r1(vm.total / GB),
                      "up": int(time.time() - psutil.boot_time()), "v": __version__})

    # ---- slow-changing caches
    def _partitions(self, now):
        if now - self._parts_at > 60:
            self._parts_at = now
            parts = []
            seen = set()
            for p in psutil.disk_partitions(all=False):
                mp = p.mountpoint
                if IS_WIN:
                    if "cdrom" in p.opts or not p.fstype:
                        continue
                    label = mp.rstrip("\\")
                else:
                    if p.fstype in ("squashfs", "tmpfs", "devtmpfs", "overlay", "vfat", "iso9660") or \
                            mp.startswith(("/snap", "/boot", "/var/lib/docker", "/run", "/sys", "/proc")):
                        continue
                    label = mp
                if p.device in seen:      # bind mounts of the same device
                    continue
                seen.add(p.device)
                parts.append((label, mp))
            self._parts = parts[:8]
        return self._parts

    def _disks(self, now):
        if now - self._disk_at > 5:
            self._disk_at = now
            out = []
            for label, mp in self._partitions(now):
                try:
                    u = psutil.disk_usage(mp)
                except (OSError, PermissionError):
                    continue
                out.append({"m": label, "p": r1(u.percent), "u": r1(u.used / GB), "t": r1(u.total / GB)})
            self._disk_cache = out
        return self._disk_cache

    def _real_nics(self, now):
        if now - self._nics_at > 30:
            self._nics_at = now
            try:
                stats = psutil.net_if_stats()
            except OSError:
                stats = {}
            self._nics = {n for n, s in stats.items() if s.isup and is_real_nic(n)}
            if self._winnet is not None:
                try:
                    self._winnet.map_names(self._nics)
                except OSError:
                    self._winnet = None
        return self._nics

    def _cpu_temp(self, now):
        if self.lhm is not None:
            return self.lhm.latest
        if not IS_LINUX:
            return None
        if now - self._temp_at > 2:
            self._temp_at = now
            try:
                self._temp = pick_cpu_temp(psutil.sensors_temperatures())
            except (OSError, AttributeError):
                self._temp = None
        return self._temp

    def _cpu_freq(self, now):
        if now - self._freq_at > 2:
            self._freq_at = now
            try:
                f = psutil.cpu_freq()
                self._freq = int(f.current) if f and f.current else None
            except (OSError, NotImplementedError, AttributeError):
                self._freq = None
        return self._freq

    # ---- every second
    def sample(self) -> str:
        now = time.monotonic()
        cores = psutil.cpu_percent(percpu=True)
        vm = psutil.virtual_memory()
        if now - self._swap_at > 30:    # ~30 ms per call on Windows (PDH counters); swap moves slowly
            self._swap_at = now
            self._swap = psutil.swap_memory()
        sw = self._swap

        nics = self._real_nics(now)
        if self._winnet is not None:
            counters = self._winnet.read()                       # {name: (recv, sent)}
            nd = self.net_down.update({n: c[0] for n, c in counters.items()}, now)
            nu = self.net_up.update({n: c[1] for n, c in counters.items()}, now)
        else:
            try:
                per_nic = psutil.net_io_counters(pernic=True)
            except OSError:
                per_nic = {}
            nu = self.net_up.update({n: c.bytes_sent for n, c in per_nic.items() if n in nics}, now)
            nd = self.net_down.update({n: c.bytes_recv for n, c in per_nic.items() if n in nics}, now)

        dr = dw = 0
        try:
            per_disk = psutil.disk_io_counters(perdisk=True) or {}
            phys = {k: v for k, v in per_disk.items() if is_physical_disk(k)}
            dr = self.disk_r.update({k: v.read_bytes for k, v in phys.items()}, now)
            dw = self.disk_w.update({k: v.write_bytes for k, v in phys.items()}, now)
        except (OSError, RuntimeError):
            pass  # Windows without disk perf counters

        msg = {"t": "m", "id": self.id, "ts": round(time.time(), 1),
               "c": r1(sum(cores) / len(cores)), "cc": [r1(c) for c in cores],
               "cf": self._cpu_freq(now),
               "r": r1(vm.percent), "ru": r1((vm.total - vm.available) / GB), "rt": r1(vm.total / GB),
               "s": r1(sw.used / GB), "st": r1(sw.total / GB),
               "d": self._disks(now), "dr": dr, "dw": dw, "nu": nu, "nd": nd,
               "tp": r1(self._cpu_temp(now))}
        if IS_LINUX:
            msg["rc"] = r1((getattr(vm, "cached", 0) + getattr(vm, "buffers", 0)) / GB)
            msg["l"] = [r1(x) for x in os.getloadavg()]
        if self.gpu is not None and self.gpu.latest is not None:
            msg["g"] = self.gpu.latest
        return DUMPS(msg)

    # ---- every 3 seconds
    def processes(self) -> str:
        """Top 5 processes by CPU, as % of the whole machine (like Task Manager)."""
        now = time.monotonic()
        prev, prev_t = self._proc_prev, self._proc_prev_t
        cur, rows = {}, []
        for key, cpu_s, handle in self._scanner.scan():
            cur[key] = cpu_s
            before = prev.get(key)
            if before is not None and cpu_s > before:
                rows.append((cpu_s - before, handle))
        self._proc_prev, self._proc_prev_t = cur, now
        out = []
        if prev_t is not None and now > prev_t:
            scale = 100.0 / ((now - prev_t) * self.ncpu)
            for d, handle in heapq.nlargest(5, rows, key=lambda r: r[0]):
                desc = self._scanner.describe(handle)     # name + RSS only for the winners
                if desc:
                    out.append([desc[0][:24], r1(min(100.0, d * scale)), desc[1]])
        return DUMPS({"t": "p", "id": self.id, "ps": out})


# --------------------------------------------------------------------------- process scanners

class PsutilScanner:
    """Linux/macOS: psutil reads one small /proc/<pid>/stat file per process (~20 us each)."""

    def scan(self):
        for p in psutil.process_iter():          # psutil caches Process objects between calls
            if p.pid == 0:
                continue
            try:
                t = p.cpu_times()
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess, OSError):
                continue
            yield p.pid, t.user + t.system, p

    @staticmethod
    def describe(p):
        try:
            return p.name(), int(p.memory_info().rss / MB)
        except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
            return None


class WinScanner:
    """Windows: ONE NtQuerySystemInformation(SystemProcessInformation) call returns CPU
    times, names and working sets for every process. psutil instead opens each process
    and, for the ones that deny access, takes a full system snapshot *per process*:
    measured 334 ms CPU per scan of 352 processes vs ~2 ms here."""

    # x64 SYSTEM_PROCESS_INFORMATION: NextEntryOffset@0, CreateTime@32, UserTime@40,
    # KernelTime@48, ImageName{Length@56, Buffer@64}, UniqueProcessId@80, WorkingSetSize@144
    import struct as _struct
    _REC = _struct.Struct("<I28xqqqH6xQ8xQ56xQ")

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self._ct = ctypes
        fn = ctypes.WinDLL("ntdll").NtQuerySystemInformation
        fn.restype = ctypes.c_ulong
        fn.argtypes = [ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(wintypes.ULONG)]
        self._fn = fn
        self._size = 1 << 20
        self._buf = ctypes.create_string_buffer(self._size)   # reused: no per-scan allocation
        self._ret = wintypes.ULONG()

    def _query(self):
        while True:
            st = self._fn(5, self._buf, self._size, self._ct.byref(self._ret))
            if st == 0xC0000004:                                 # STATUS_INFO_LENGTH_MISMATCH
                self._size = max(self._size * 2, self._ret.value + (64 << 10))
                self._buf = self._ct.create_string_buffer(self._size)
                continue
            if st != 0:
                raise OSError(f"NtQuerySystemInformation failed: 0x{st:08X}")
            return

    def scan(self):
        self._query()
        buf, rec, off = self._buf, self._REC, 0
        while True:
            nxt, created, user, kern, nlen, nptr, pid, ws = rec.unpack_from(buf, off)
            if pid:
                # (pid, create time) survives pid reuse; times are in 100 ns units
                yield (pid, created), (user + kern) / 1e7, (nlen, nptr, ws)
            if not nxt:
                break
            off += nxt

    def describe(self, h):
        nlen, nptr, ws = h
        name = self._ct.wstring_at(nptr, nlen // 2) if nptr else "?"   # points into our buffer
        if name.lower().endswith(".exe"):
            name = name[:-4]
        return name, int(ws / MB)


# --------------------------------------------------------------------------- link

class Link:
    """One WebSocket to the hub with exponential backoff (1 s -> 30 s, jittered)."""

    def __init__(self, url: str, token: str, info_fn):
        self.url = url.rstrip("/") + "/agent"
        self.headers = {"Authorization": f"Bearer {token}"}
        self.info_fn = info_fn
        self.ws = None

    async def run(self):
        from websockets.asyncio.client import connect
        from websockets.exceptions import InvalidStatus, WebSocketException
        backoff = 1.0
        while True:
            try:
                async with connect(self.url, additional_headers=self.headers, open_timeout=10,
                                   ping_interval=20, ping_timeout=20, close_timeout=2,
                                   max_queue=4, compression=None, user_agent_header="pulse-agent") as ws:
                    await ws.send(self.info_fn())
                    self.ws = ws
                    backoff = 1.0
                    log.info("connected to %s", self.url)
                    await ws.wait_closed()
                    log.warning("hub closed the connection (%s)", ws.close_reason or ws.close_code)
            except InvalidStatus as e:
                code = e.response.status_code
                log.error("hub refused connection: HTTP %s%s", code, " (wrong token?)" if code == 401 else "")
            except (OSError, asyncio.TimeoutError, WebSocketException) as e:
                log.warning("hub unreachable: %s", e or type(e).__name__)
            finally:
                self.ws = None
            delay = backoff * random.uniform(0.8, 1.2)
            await asyncio.sleep(delay)
            backoff = min(30.0, backoff * 2)

    async def send(self, frame: str):
        ws = self.ws
        if ws is None:
            return            # drop: stale telemetry is worthless, never queue it
        try:
            # Plain send (no wait_for wrapper: that allocates a task + timer per frame,
            # which showed up as a 1.8 MB/min garbage sawtooth). A stalled hub is caught
            # by the 20 s keepalive ping, which closes the socket and makes send() raise.
            await ws.send(frame)
        except Exception:
            pass               # run() notices the closed socket and reconnects


# --------------------------------------------------------------------------- main

def load_config(path: str) -> dict:
    import yaml
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    host = socket.gethostname().lower()
    cfg = {"hub_url": raw.get("hub_url", "ws://127.0.0.1:8765"),
           "token": str(raw.get("token", "")),
           "id": re.sub(r"[^a-z0-9_-]", "-", str(raw.get("id") or host).lower())[:32],
           "name": str(raw.get("name") or host.upper())[:24],
           "accent": str(raw.get("accent", "#00F0FF")),
           "lhm_url": raw.get("lhm_url"),
           "gpu": {True: "smi", False: "off", None: "smi"}.get(raw.get("gpu"), str(raw.get("gpu")).lower()),
           "gpu_interval": float(raw.get("gpu_interval", 2.0)),
           "log_level": raw.get("log_level", "INFO"), "log_file": raw.get("log_file")}
    if not re.match(r"^#[0-9A-Fa-f]{6}$", cfg["accent"]):
        raise SystemExit("config error: accent must look like #00F0FF")
    if len(cfg["token"]) < 8:
        raise SystemExit("config error: token missing or too short")
    return cfg


async def run(cfg: dict):
    col = Collector(cfg)
    link = Link(cfg["hub_url"], cfg["token"], col.info)
    bg = [asyncio.create_task(link.run())]
    if col.gpu is not None and col.gpu.available:
        bg.append(asyncio.create_task(col.gpu.run()))
    if col.lhm is not None:
        bg.append(asyncio.create_task(col.lhm.run()))
    col.processes()                       # prime per-process CPU times
    gc.collect()
    gc.freeze()                           # startup objects never need scanning again
    start = time.monotonic()
    tick = 0
    while True:
        tick += 1
        # drift-free 1 Hz: sleep until the next whole tick instead of "sleep(1)"
        await asyncio.sleep(max(0.0, start + tick - time.monotonic()))
        if link.ws is None:
            continue                      # nothing to send to; rates stay correct (delta / elapsed)
        await link.send(col.sample())
        if tick % 3 == 0:
            await link.send(col.processes())
        if tick % 30 == 0:
            gc.collect()                  # keeps RSS flat instead of a slow sawtooth (~1 ms)


def main():
    ap = argparse.ArgumentParser(description="PULSE agent")
    ap.add_argument("-c", "--config", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.yaml"))
    ap.add_argument("--print", action="store_true", help="print one sample and exit (diagnostics)")
    args = ap.parse_args()
    cfg = load_config(args.config)
    handlers = None
    if cfg["log_file"] and not args.print:
        from logging.handlers import RotatingFileHandler
        handlers = [RotatingFileHandler(cfg["log_file"], maxBytes=1_000_000, backupCount=2, encoding="utf-8")]
    logging.basicConfig(level=cfg["log_level"], format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)
    logging.getLogger("websockets").setLevel(logging.WARNING)
    if args.print:
        col = Collector(cfg)
        col.processes()
        time.sleep(1.5)
        print(col.info())
        print(col.sample())
        print(col.processes())
        return
    try:
        asyncio.run(run(cfg))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
