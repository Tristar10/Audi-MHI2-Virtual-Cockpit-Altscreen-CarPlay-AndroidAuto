# Audi TT 8S port: MHI2_US_AU43x_P5180 / MU1462

Work-in-progress port of this project's CarPlay AltScreen to an Audi TT 8S.
Modified from the MU1438 code in this repository (GPL-3.0), November 2026.

> **Status: not tested on a car.** Nothing here has been installed or run on a
> head unit yet. So far it has only been checked offline against one firmware
> image. It modifies system files on the head unit and can stop CarPlay working.
> Do not install it on any other firmware.

No firmware is included. Stock Audi/Harman files, converted HMI jars and patched
copies of stock executables must never be committed to this repository.

## Findings on MU1462

| Item | MU1438 (this project) | TT MU1462 |
|---|---|---|
| Displays | Centre screen and cluster | One: display 0, terminal `main`, 1440x542 |
| Stock map context | 72-77 on terminal 1 | 10 on terminal 0 (classic and sport) |
| CarPlay full screen | Separate screen | Context 78 |
| Cluster type | Not checked | Contexts up to 157, so type 4 (`+79` KDK remap) |
| NvSS video output | 1 | 0 (`NVSS_VIDEO_OUTPUT_DEVICE=0`) |
| TCP 6031 on `carplay0` | Not changed by this project's installer | Blocked by the stock firewall |

Offline checks: all 47 symbols the hook resolves exist in the MU1462
`libairplay.so`, and all 5 hooked function prologues match what it expects.

## Stage 1 (native hook only, no HMI change)

- `dio_manager`: hook added as the first `NEEDED`, stock library order kept.
- `cluster_runtime`: output device changed from 1 to 0.
- `cluster_supervisor.sh`: declares context 901 as layers 16 and 60, so the MMI
  layer stays above the map as in stock context 10.
- Firewall: `pass` rule for TCP 6031 inserted before the `carplay0` block rule.
- `install.template.sh`: CarPlay-only installer with `--check`, `--install` and
  `--restore`, pinned to MU1462 hashes.

## Still to do

- Run the installer's check mode, then the native hook, on a car.
- Rewrite `ClusterGate` for terminal 0 and the TT map contexts, including the
  type 4 `+79` variants, and switch to context 901.
- Tune the map's safe area for the TT layouts.

## Rebuilding from your own firmware

The scripts in `scripts/` read your own firmware copy. Run them with paths to
your files:

| Script | Purpose |
|---|---|
| `unlzo1z.py` | Decompress `mifs-stage2.img` (LZO1Z) |
| `ifsx.py` | Extract `lsd.jxe` from the IFS |
| `qnx6x.py` | Extract files from `app.img` (QNX6) |
| `elfinfo.py`, `hookcheck.py`, `armcalls.py`, `pltname.py` | Inspect ELF imports, hook targets and call sites |
| `contexts.py` | Turn `javap` output of `defineContexts` into a context table |
| `patch_dio_mu1462.py` | Create the hook-first `dio_manager` |
| `assemble.py` | Assemble the stage 1 payload |

`assemble.py` expects the scratch layout used during development, so adjust its
paths for your setup.
