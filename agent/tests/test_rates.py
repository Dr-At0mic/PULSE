# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later

"""Unit tests for the agent's rate calculations and filters."""
import os
import sys
from types import SimpleNamespace as NS

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import agent  # noqa: E402
from agent import MultiRate, RateCounter, is_physical_disk, is_real_nic, pick_cpu_temp  # noqa: E402


def test_first_sample_is_zero():
    assert RateCounter().update(1_000_000, 10.0) == 0


def test_steady_rate():
    rc = RateCounter()
    rc.update(0, 0.0)
    assert rc.update(1_000_000, 1.0) == 1_000_000
    assert rc.update(3_000_000, 2.0) == 2_000_000


def test_rate_uses_real_elapsed_time():
    rc = RateCounter()
    rc.update(0, 0.0)
    assert rc.update(3_000_000, 3.0) == 1_000_000      # e.g. after a reconnect gap
    assert rc.update(3_500_000, 3.5) == 1_000_000      # half-second tick


def test_counter_reset_gives_zero_not_negative_spike():
    rc = RateCounter()
    rc.update(10_000_000, 0.0)
    assert rc.update(500, 1.0) == 0
    assert rc.update(1_500, 2.0) == 1_000                # recovers on the next tick


def test_zero_or_backwards_time_delta():
    rc = RateCounter()
    rc.update(0, 5.0)
    assert rc.update(100, 5.0) == 0
    assert rc.update(200, 4.0) == 0


def test_multirate_sums_per_counter():
    mr = MultiRate()
    mr.update({"eth0": 0, "wlan0": 0}, 0.0)
    assert mr.update({"eth0": 1000, "wlan0": 500}, 1.0) == 1500


def test_multirate_interface_appears_and_disappears_without_spikes():
    mr = MultiRate()
    mr.update({"eth0": 1000}, 0.0)
    # a new NIC with a huge existing counter must not add a spike
    assert mr.update({"eth0": 2000, "usb0": 9_999_999_999}, 1.0) == 1000
    # NIC vanishes: no negative total
    assert mr.update({"usb0": 9_999_999_999 + 4000}, 2.0) == 4000
    assert set(mr.counters) == {"usb0"}


def test_nic_filter():
    for real in ("eth0", "enp3s0", "wlan0", "wlp2s0", "Ethernet", "Wi-Fi", "Ethernet 2"):
        assert is_real_nic(real), real
    for virt in ("lo", "docker0", "veth1a2b", "br-12ab", "virbr0", "tailscale0", "wg0",
                 "vEthernet (WSL)", "Loopback Pseudo-Interface 1", "VMware Network Adapter VMnet8",
                 "VirtualBox Host-Only Network", "Bluetooth Network Connection"):
        assert not is_real_nic(virt), virt


def test_disk_filter():
    assert is_physical_disk("sda") and is_physical_disk("nvme0n1") and is_physical_disk("PhysicalDrive0")
    for v in ("loop3", "dm-0", "md0", "zram0", "sr0", "ram1"):
        assert not is_physical_disk(v)


def T(label, current):
    return NS(label=label, current=current)


def test_temp_prefers_package():
    s = {"acpitz": [T("", 40.0)],
         "coretemp": [T("Core 0", 50.0), T("Package id 0", 61.0)]}
    assert pick_cpu_temp(s) == 61.0


def test_temp_amd_tctl_and_fallbacks():
    assert pick_cpu_temp({"k10temp": [T("Tccd1", 55.0), T("Tctl", 63.5)]}) == 63.5
    assert pick_cpu_temp({"cpu_thermal": [T("", 48.2)]}) == 48.2
    assert pick_cpu_temp({"nvme": [T("Composite", 40.0)]}) is None
    assert pick_cpu_temp({"coretemp": [T("Package id 0", 0.0)]}) is None   # bogus reading


def test_round_one_decimal():
    assert agent.r1(23.456) == 23.5 and agent.r1(None) is None
