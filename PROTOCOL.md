# PULSE wire protocol (v1)

All traffic is JSON text frames over WebSocket. Keys are deliberately short
because the tablet parses every frame at 1 Hz per device on a weak CPU.

```
agent ──/agent──► hub ──/client──► tablet
```

* Numbers: floats are rounded to **1 decimal** by the sender. Byte rates are integers (bytes/second).
* `null` means "this machine cannot report this value" (e.g. CPU temperature on Windows
  without LibreHardwareMonitor). A field that is **missing** means "not applicable"
  (e.g. `g` on a machine without an NVIDIA GPU, `l` on Windows).
* Device ids (`id`) are short slugs `[a-z0-9_-]{1,32}` chosen in the agent's `config.yaml`.
* Rates (network, disk) are computed **on the agent**. The tablet only draws.

---

## 1. Authentication

Both endpoints require the shared token. Send it as an HTTP header during the WebSocket
handshake:

```
Authorization: Bearer <token>
```

`?token=<token>` in the URL is accepted as a fallback for tools that cannot set headers.

A wrong or missing token is rejected **before** the WebSocket upgrade with
`HTTP 401 Unauthorized`. No frames are ever exchanged with an unauthenticated peer.

The hub may use different tokens for agents (`agent_token`) and the tablet (`client_token`);
by default they are the same.

---

## 2. Agent → hub (`/agent`)

The agent only ever **sends**. The hub never sends commands to agents (it sends nothing at all
apart from WebSocket control frames), so nothing can be executed on a monitored machine
through PULSE.

### 2.1 `i` — static info (once, first frame after connecting)

```json
{"t":"i","id":"pc","nm":"BATTLESTATION","ac":"#00F0FF","hn":"ANANDHU-PC",
 "os":"Windows 11","cpu":"AMD Ryzen 7 5800X 8-Core Processor","cores":16,
 "rt":32.0,"up":86400,"v":"1.0.0"}
```

| key   | type   | meaning                                             |
|-------|--------|-----------------------------------------------------|
| id    | string | device id (slug). Binds the connection to this id.  |
| nm    | string | display name for the dashboard                      |
| ac    | string | accent colour `#RRGGBB` (tablet can override)       |
| hn    | string | hostname                                            |
| os    | string | OS name + version                                   |
| cpu   | string | CPU model                                           |
| cores | int    | logical core count                                  |
| rt    | float  | total RAM, GB                                       |
| up    | int    | uptime in seconds **at the moment of sending**      |
| v     | string | agent version                                       |

The first frame **must** be `i`. Every later frame must carry the same `id`, otherwise
it is dropped.

### 2.2 `m` — metrics (every 1 s)

```json
{"t":"m","id":"pc","ts":1731080000.0,"c":23.4,"cc":[12.1,40.2,8.0,33.3],"cf":3600,
 "r":61.2,"ru":9.8,"rt":16.0,"rc":3.1,"s":2.1,"st":8.0,
 "d":[{"m":"C:","p":71.3,"u":340.2,"t":476.9}],"dr":1200000,"dw":340000,
 "nu":52000,"nd":1830000,"tp":58.0,"g":{"u":12.0,"vm":1.2,"vt":8.0,"tp":51.0},
 "l":[0.4,0.6,0.5]}
```

| key | type            | meaning                                                         |
|-----|-----------------|-----------------------------------------------------------------|
| ts  | float           | sample time, Unix seconds                                       |
| c   | float           | total CPU %                                                     |
| cc  | float[]         | per-core CPU %                                                  |
| cf  | int \| null     | current CPU frequency, MHz                                      |
| r   | float           | RAM used %                                                      |
| ru  | float           | RAM used, GB                                                    |
| rt  | float           | RAM total, GB                                                   |
| rc  | float           | RAM cached/buffers, GB (*missing on Windows*)                   |
| s   | float           | swap used, GB                                                   |
| st  | float           | swap total, GB                                                  |
| d   | object[]        | partitions: `m` mount, `p` used %, `u` used GB, `t` total GB    |
| dr  | int             | disk read, bytes/s (all physical disks)                         |
| dw  | int             | disk write, bytes/s                                             |
| nu  | int             | network upload, bytes/s (real interfaces only)                  |
| nd  | int             | network download, bytes/s                                       |
| tp  | float \| null   | CPU package temperature, °C                                     |
| g   | object          | GPU (*only if NVIDIA*): `u` util %, `vm` VRAM used GB, `vt` VRAM total GB, `tp` °C. Refreshed every 2 s. |
| l   | float[3]        | load average 1/5/15 min (*Linux only*)                          |

### 2.3 `p` — top processes (every 3 s)

```json
{"t":"p","id":"pc","ps":[["chrome",12.3,850],["code",4.1,620]]}
```

`ps` is up to 5 rows `[name, cpu_percent, rss_mb]`, sorted by CPU descending.
`cpu_percent` is normalised to the whole machine (0–100, like Task Manager), not per core.

---

## 3. Tablet → hub (`/client`)

### 3.1 `sub` — choose a subscription mode

```json
{"t":"sub","mode":"overview"}
{"t":"sub","mode":"detail:pc"}
```

| mode               | what the hub streams                                                                 |
|--------------------|--------------------------------------------------------------------------------------|
| `overview`         | `o` summaries for **every** device at 1 Hz (forwarded as each sample arrives), `st` |
| `detail:<id>`      | full `m` + `p` for `<id>` at 1 Hz / 0.33 Hz; `o` for the other devices every 3 s; `st` |

The default mode on connect is `overview`. Every `sub` triggers a fresh `hs` (history)
burst so graphs are filled instantly.

---

## 4. Hub → tablet (`/client`)

### 4.1 `w` — welcome (first frame)

```json
{"t":"w","v":1,"n":3,"hub":"pulse-hub"}
```

`n` = number of known devices. The boot screen prints `3 NODES DETECTED` from it.

### 4.2 `i` — device info

Same as §2.1, forwarded verbatim (plus `on`: current online flag). Sent for every known
device right after `w`, and again whenever an agent (re)connects.

### 4.3 `st` — online status (on change)

```json
{"t":"st","id":"srv2","on":false}
```

A device goes offline when **no frame has arrived for `offline_after` seconds**
(default 4.5 s, see README for why it is not 5) or when its socket closes.

### 4.4 `o` — summary

```json
{"t":"o","id":"pc","ts":1731080000.0,"c":23.4,"r":61.2,"nu":52000,"nd":1830000,"tp":58.0}
```

### 4.5 `m` / `p` — full metrics / processes

Forwarded verbatim from the agent (§2.2, §2.3), only for the device in `detail:<id>`.

### 4.6 `hs` — history burst

```json
{"t":"hs","id":"pc","c":[...],"r":[...],"nu":[...],"nd":[...],"tp":[...]}
```

Oldest first, one value per second. `overview` sends the last 60 `c` and `r` values for
each device. `detail:<id>` sends the last 120 of every series for `<id>`.
Missing temperatures are sent as `null`.

---

## 5. HTTP

`GET /health` (no token needed, returns no metrics):

```json
{"ok":true,"devices":3,"online":2,"clients":1,"uptime":5231}
```

---

## 6. Limits

* Max frame size: 64 KiB (larger frames close the connection).
* Max agents: 32. Max clients: 8.
* Agents that send malformed JSON are disconnected.
