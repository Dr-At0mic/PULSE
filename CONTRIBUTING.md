# Contributing to PULSE

Thanks for taking the time to help. Bug reports, ideas, documentation fixes and code are all
welcome.

## Ways to contribute

* **Report a bug:** [open an issue](https://github.com/Dr-At0mic/PULSE/issues) with what you
  expected, what happened, and how to reproduce it. Include the OS, the component (agent, hub
  or tablet app) and the relevant log lines:
  * agent: `journalctl -u pulse-agent -n 50` (Linux) or `D:\Applications\PulseAgent\logs\` (Windows)
  * hub: `journalctl -u pulse-hub -n 50`
  * one sample of what an agent sends: `python agent/agent.py -c config.yaml --print`
* **Suggest a feature:** open an issue first, so we can agree on the approach before you
  spend time on it.
* **Send a pull request:** fixes and improvements from small to large.

**Never post your hub token, passwords, or public IP addresses** in issues, logs or screenshots.
Replace them with placeholders.

## Development setup

You don't need real machines: the mock agent simulates a whole fleet, including spikes,
alerts and devices that drop off and come back.

```bash
git clone https://github.com/Dr-At0mic/PULSE.git
cd PULSE
python -m venv .venv
. .venv/bin/activate                       # Windows: .venv\Scripts\activate
pip install websockets psutil pyyaml pytest

PULSE_AGENT_TOKEN=devtoken123 python hub/hub.py -c none.yaml    # hub on :8765
python tools/mock_agent.py --token devtoken123 --devices 4       # 4 fake machines
```

Android app: open `android/` in Android Studio, or build from the command line with JDK 17+:

```bash
cd android
./gradlew assembleRelease
```

In the Android emulator the host PC is `10.0.2.2`, so point the app at `10.0.2.2:8765`.

## Before you open a pull request

1. **Run the tests.** Everything must pass:
   ```bash
   python -m pytest agent/tests hub/tests
   cd android && ./gradlew testReleaseUnitTest assembleRelease
   ```
   Add tests for new logic where it can be tested (rate maths, hub state, formatters, rings).
2. **Respect the performance budgets.** They are hard requirements, not targets (see the
   README's *Performance budgets* section):
   * agent < 0.5 % CPU and < 40 MB RAM; hub < 50 MB RAM
   * tablet app: smooth 60 fps on a Galaxy Tab A7 Lite class device, memory that stays flat
   * **no allocations inside `onDraw()`**: pre-allocate `Paint`, `Path`, `RectF` and shaders,
     format numbers with `Fmt` into `char[]` buffers, and use index loops instead of iterators
   * continuous animation goes through the single `FrameDriver`, not per-view animators
   * agents stay **send-only**: no listening ports, no commands accepted

   If a change touches drawing or sampling, include before/after numbers
   (`dumpsys gfxinfo` / `dumpsys meminfo` on a device, or CPU/RSS for the agent).
3. **Keep the protocol documented.** Any wire-format change must update
   [PROTOCOL.md](PROTOCOL.md) and stay backward compatible where possible (old agents
   talking to a new hub).
4. **Match the existing style.** Python: standard library first, type hints, small
   functions. Kotlin: plain framework Views, no AndroidX or extra dependencies without a
   discussion first. New source files get the same three-line licence header as the others.
5. **One topic per pull request**, with a clear description of what changed and why.

## Licence of contributions

PULSE is licensed under the **GNU General Public License v3.0 or later**. By submitting a
contribution you agree that it is released under the same licence. Only submit code you wrote
yourself or have the right to contribute.

## Contact

Anandhu Suresh · [anandhu2suresh797@gmail.com](mailto:anandhu2suresh797@gmail.com) ·
GitHub [@Dr-At0mic](https://github.com/Dr-At0mic)
