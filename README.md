# CarPlay on the Audi TT 8S Virtual Cockpit

> **This repository has moved to [github.com/Tristar10/audi-tt-8s-altscreen](https://github.com/Tristar10/audi-tt-8s-altscreen).**
> This copy is archived and no longer updated.

Apple Maps and Google Maps from CarPlay on the Audi TT 8S Virtual Cockpit, with
turn-by-turn guidance drawn in the cockpit's guidance banner.

Target: **MHI2_US_AU43x_P5180 / MU1462 (G24)**, Nvidia Tegra MHI2, QNX 6.5.
Fork of [aberbic's MU1438 AltScreen](https://github.com/aberbic/Audi-MHI2-Virtual-Cockpit-Altscreen-CarPlay-AndroidAuto).

> **Works on one car (US TT, MU1462).** It modifies system files on the head unit
> and can stop CarPlay working. Do not install it on any other firmware. You need
> root SSH or M.I.B. access, a parked car and stable power.

## What works

| Feature | State |
|---|---|
| CarPlay map on the cockpit map tab, switched automatically | Works |
| Large and small (tach-centred) cockpit views | Works |
| Steering-wheel scroll zoom on the CarPlay map | Works |
| Turn-by-turn panel: 3D arrows, lanes, exit badge, distance, ETA | Works parked; live drive pending |
| Android Auto | Not ported to the TT |

Details, the dead ends and what is next: **[the TT wiki](ports/tt-8s-mu1462/wiki/Home.md)**.
Built with Claude Code; how, and how to start on your own car: [Working with Claude](ports/tt-8s-mu1462/wiki/Working-with-Claude.md).

![Turn panel pictures](ports/tt-8s-mu1462/docs/panel-preview-v32.png)

## Where things are

| Path | Contents |
|---|---|
| [`ports/tt-8s-mu1462/`](ports/tt-8s-mu1462/README.md) | The TT port: installers, HMI source, test kit, tools, wiki |
| `carplay/`, `hmi/`, `scripts/`, `toolbox/`, ... | The upstream MU1438 project the port builds on |
| [`UPSTREAM-README`](UPSTREAM-README), `INSTALL` | Upstream documentation (MU1438, CarPlay and Android Auto) |

## Installing

There is no one-click package yet. The current installers are in
`ports/tt-8s-mu1462/package/` (see its `README.txt`), and the newest HMI jar is
installed with the `kit/tt.sh` toolkit. An SD-card (M.I.B.) installer is planned.

No firmware, patched stock files or converted HMI jars are in this repository;
the port's `scripts/` rebuild them from your own firmware copy.

## Credits and licence

GPL-3.0 (see LICENSE and NOTICE). Built on aberbic's AltScreen and the work it
credits (harman-f, luka-dev, f-io/LIVI, binarybase), luka-dev's
mib2q-carplay-rgi, yuedizhibo's MHI2Q AltScreen, and chefranov's route-guidance
hook (used, not included). See [Credits](ports/tt-8s-mu1462/wiki/Credits.md).
