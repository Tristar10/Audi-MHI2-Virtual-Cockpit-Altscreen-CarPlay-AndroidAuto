# TT 8S (MU1462) port: how it works, and what did not

This is a record of how the AltScreen CarPlay map was brought to the Audi TT 8S
Virtual Cockpit (MHI2_US_AU43x_P5180, MU1462, G24), and of the turn-by-turn
(RGI) work that followed. It lists the dead ends too, with the evidence for
each, so nobody has to repeat them.

Written November 2026. Everything described as "works" was checked on one car
(US TT, MU1462). Nothing here has been tried on other firmware.

## Contents

1. [Status](#1-status)
2. [How the TT differs from the MU1438 cars](#2-how-the-tt-differs-from-the-mu1438-cars)
3. [Components](#3-components)
4. [CarPlay map on the cockpit](#4-carplay-map-on-the-cockpit)
5. [Sport layout (small view)](#5-sport-layout-small-view)
6. [Steering-wheel zoom](#6-steering-wheel-zoom)
7. [HMI patching (tt-zoom.jar)](#7-hmi-patching-tt-zoomjar)
8. [Turn-by-turn: getting the iPhone data](#8-turn-by-turn-getting-the-iphone-data)
9. [Turn-by-turn: getting it on screen](#9-turn-by-turn-getting-it-on-screen)
10. [Drawing our own guidance (current work)](#10-drawing-our-own-guidance-current-work)
11. [Build, test kit and workflow](#11-build-test-kit-and-workflow)
12. [Third-party code and what is not in this repository](#12-third-party-code-and-what-is-not-in-this-repository)
13. [Open items](#13-open-items)

## 1. Status

| Feature | State | Notes |
|---|---|---|
| CarPlay map (Apple/Google Maps) on the cockpit map tab | Works | Switches automatically with the map tab |
| Map window tuned for the TT | Works | Hook safe-area preset `h320` |
| Sport layout (small view) re-centring | Works | Picture shifted 453 px in `cluster_runtime` |
| Steering-wheel scroll zoom on the CarPlay map | Works | `tt-zoom.jar` (ZoomGate) |
| iPhone turn-by-turn data reaching the head unit | Works | chefranov hook + our `RgdBus` listener |
| Turn-by-turn shown on the cockpit | In progress | Stock displays ruled out (section 9); own panel with arrows, bar and lanes (section 10) |

## 2. How the TT differs from the MU1438 cars

- **One display.** The TT has no centre screen: everything is drawn on display 0,
  HMI terminal `main`, 1440x540 (1440x542 in some places). The cockpit is the
  head unit's own picture.
- **"MMI-Kombi" variant.** `Util.isClusterMMI()` is true (`getScreenRes() == 4`).
  Much of the navigation code takes MMI-Kombi branches:
  - `FunctionListNaviEvo.initVariantMMIKombi` switches off BAP NavSD functions
    23 (ManeuverDescriptor), 24 (LaneGuidance), 39 (ActiveRGType) and 49 (ExitView).
    The cockpit therefore cannot receive a turn arrow or lanes over BAP the way
    MHI2Q cluster cars do.
  - `ClusterViewMode.refreshViewMode` returns early; map-tab switching is decided
    in the HMI screens.
- **Contexts.** The stock map is context 10 on terminal 0 (classic and sport);
  CarPlay full screen is context 78.
- **Video output.** NvSS output device 0 (`NVSS_VIDEO_OUTPUT_DEVICE=0`), not 1.
- **Firewall.** TCP 6031 on `carplay0` is blocked by the stock rules.

## 3. Components

| Piece | Where | Role |
|---|---|---|
| `libaltscreen111.so` | project hook, loaded first by `dio_manager` | CarPlay alternate-screen session, safe area, zoom datagrams |
| `cluster_runtime` | patched build of the project's renderer | Draws the CarPlay cluster stream with NvSS on output 0; Sport crop shift |
| `cluster_supervisor.sh` | generated from the stock script | Declares context 901 (layers 16 and 60) so the MMI layer stays above the map |
| `tt-zoom.jar` | HMI jar directory | Our Java: zoom, Sport marker, turn-by-turn feed, logging, drawing (built from `hmi/`) |
| `librgd_hook.so` | chefranov, not distributed | LD_PRELOAD into `mm-ipod`; asks the iPhone for route guidance |
| `package/` | this repo | Installers and helper scripts |
| `kit/` | this repo | `tt.sh` toolkit and parked-test scripts |

## 4. CarPlay map on the cockpit

Stage 1 (native hook only) is described in [README.md](README.md). On top of it:

- **Safe area.** iOS lays the map out inside the safe area the hook reports.
  Several presets were built and compared on the car (`package/safe-preset.sh`
  switches between them). `h320` fits the TT classic window best.
- **Supervisor.** The final supervisor is the 4776-byte version
  (`package/sup-update.sh` installs it alone).
- **Package.** `package/altscreen-mu1462-install.sh` (`--check`, `--install`,
  `--restore`, pinned to MU1462 hashes) installs hook, renderer and supervisor
  and keeps a backup under `/mnt/app/root/altscreen-backups/`.
  `package/status.sh` shows what is on flash without changing anything.

## 5. Sport layout (small view)

What the user calls switching Classic/Sport is the cockpit's large/small view
size; the skin itself stays 1 (Sport) on this car.

| Attempt | Result |
|---|---|
| Hook preset `auto`: switch only the safe area when the view changes | Failed. iOS accepted `updateViewArea` (status 0) but Apple and Google Maps did not re-layout |
| Hook preset `auto2`: switch to view 2 with a 720-wide view area | Failed, same reason |
| Hook `DisplayManagerEvo.setupKDKBackground` / terminal `setSkin` to detect the change | Runs only once at start-up |
| Hook `DSICarTimeUnitsLanguageManager.updateSkin` | Also fires only once (skin never changes) |
| **Leave iOS alone; shift the picture in `cluster_runtime`** (as yuedizhibo's MHI2Q AltScreen does for B9 Sport) | **Works** |
| **Detect the size change via static `AnimationMIB2HighSport.setViewSize(eal, layout, small)`** | **Works** (Apple and Google Maps) |

How it works: `tt-zoom.jar` creates `/tmp/mu1438-cluster-sport` in the small
view and deletes it in the large view. The patched renderer (built by
`scripts/mkrt.py`) re-reads the decoder attributes every 8 frames and, while the
file exists, crops the source at x = 453: crop (453,0,987,540) drawn at
(0,0,987,540). NvSSVideoSetAttribs layout used: byte 8 = 1; bytes 12–24 source
crop x,y,w,h; bytes 28–40 screen rectangle; byte 60 = 1, byte 61 = 0.

## 6. Steering-wheel zoom

On the TT the wheel's map zoom arrives as `AbstractGUI.increment/decrement`
(gesture scale id 400898) and `CombiBAPListener.setMapScale`. `ZoomGate` checks
whether the CarPlay renderer is drawing (its heartbeat file
`/tmp/mu1438-cluster-active` is fresh). If so, the step goes to the hook as an
`MZ01` UDP datagram and the stock map is left alone; otherwise the stock code
runs. CarPlay's zoom direction is the reverse of the stock scale direction
(`/tmp/tt-zoom-invert` flips it). Every decision is logged to `/tmp/tt-zoom.log`.

## 7. HMI patching (tt-zoom.jar)

The jar goes into `/mnt/app/eso/hmi/lsd/jars/`, which the HMI puts ahead of
`lsd.jxe`, so classes in it override stock ones. Nothing stock on flash is
replaced, and removing the jar restores stock behaviour.

- Stock classes are taken from `lsd.jxe` converted with jxe2jar, patched with
  ASM (`hmi/tools/PatchZoom.java`: small hooks inserted at method starts or
  around single calls) and rewritten to class version 46.
- **What survived conversion on the car:** CombiBAPListener, AbstractGUI,
  DisplayManagerEvo, DSICarTimeUnitsLanguageManager, AnimationMIB2HighSport,
  ClusterService, ClusterViewMode, KOMOService (all small, version 46).
- **What broke:** overriding `map/Context` (version 49, 57 KB) together with
  `map/instances/MapMain` left navigation stuck at 11 % on start-up. Removing
  the jar recovered it. Lesson: ASM verification does not prove a converted
  class runs on J9. Add one override at a time.
- **Rules for our own classes** (enforced by PatchZoom and `checklib.py`):
  Java 1.4 class library only (StringBuffer, no StringBuilder, no autoboxing,
  no generics); no string `+` concatenation (it compiles to StringBuilder); no
  class literals (`Foo.class` needs version 49); no anonymous or inner classes
  that capture variables (synthetic members); every `java.*` call must exist in
  the car's library.

## 8. Turn-by-turn: getting the iPhone data

- chefranov's `librgd_hook.so` (unmodified, his licence does not allow
  redistribution) is LD_PRELOADed into `mm-ipod` (`package/rgd-test.sh`).
  `mm-ipod` restarts on every iPhone plug-in, so the hook reloads each time.
- The hook patches iAP2 Identify (adds RouteGuidance 0x001E, component 0x0010),
  but it only sends StartRouteGuidanceUpdates (0x5200) while a Java listener is
  connected on 127.0.0.1:19810.
- Our listener `RgdBus` speaks luka's CPHB protocol: 16-byte big-endian header
  (magic `CPHB`, seq, u16 type, u8 flags, u8 reserved, u32 length), then
  `key:type:value` text lines for `EVT_RGD_UPDATE` (0x0020). Updates are deltas;
  `RgdBus` keeps the merged state and writes the latest to `/tmp/tt-rgd.txt`.
- Fields seen: route state, distances (destination and maneuver), ETA,
  destination name, maneuver list (`mN_type`, angles, junction type, driving
  side, road names) and, from Google Maps, full lane guidance
  (`lgN_lane_directions`, `lgN_lane_status`). iOS sends a short route_state 0
  about every 30 s, so "route off" is only accepted after 6 s.
- iAP2 maneuver types are mapped with luka's `ManeuverMapper` (GPL-3.0, copied
  unchanged except the package), plus TT stock overrides: motorway exits are
  EXIT_RIGHT/LEFT `{15,192}`/`{16,64}`, keep left/right is FORK_2 `{19,64}`/`{19,192}`.

## 9. Turn-by-turn: getting it on screen

### 9.1 Attempts

| Version | What it did | Result on the car | Lesson |
|---|---|---|---|
| v10 | Fed the stock BAP NavSD path (CombiBAPListener setters) | BAP received everything; cockpit showed nothing | The TT cockpit does not draw BAP navigation |
| chefranov / tehfalcon stack | Installed his full stack (`mhi2-nar-carplay-maps`) | Ran, showed nothing; installer gate also fails on pdksh (`"$@"` under `set -u`) | It draws into cluster context 80 / displayable 98, which G24 does not have. Removed |
| v12 | Fed ClusterService (COMBI_NAV_* models: RGI icon, distance, ETA, streets); reported "route active" | A guidance banner appeared on every tab with a straight arrow and the correct distance; the map tab lost the CarPlay map | Explained later (9.3): the banner is the nav engine's view; distance came from HMI data, the arrow from its (empty) route |
| v13/v14 | Live modes (near/noactive/pad), stock-shaped icons, NavSpy logging, parked playback | Nothing new on screen | Logging made the next steps possible |
| v15 | Exact stock sequence (states 1–4, bargraph, junction arms) into the models; KDK blocked to keep the map | Parked replay of a recorded stock-nav stream showed **nothing** | The COMBI_NAV_* models are not drawn on the TT at all |
| v16 | KDK allowed again; pushed maneuver RouteInfoElements into KOMOService four ways; logged the nav engine's answers | Nothing | Nav engine reports its view not visible without its own route |
| v17 | Forced the KOMO view visible while a CarPlay route is active | **Blank banner appeared** on the cockpit, no content | Visibility can be forced; content cannot |
| v18 | Cycled the nav engine's RG modes (RGI, KDK, compass, map) and the HMI-controlled view style | Banner stayed blank; the engine never answered setRgSelect | The nav engine draws the arrow/junction only from its own route |

### 9.2 Recorded stock behaviour (presentation mode and a real drive, NavSpy)

- RGI icon stream: `{mainElement, direction 0–255 unsigned, side streets as signed bytes}`.
  Examples: left turn `{13,64,-1,0,-64}`, right turn `{13,192,-1,64,0}`,
  motorway exit `{15,192,-1}`, fork `{19,64}`, follow road `{11,255}`,
  prepare `{12,dir}`, calculating `{9,x}`, off-road `{6,dir}`, recalculating `{10,dir}`.
- Maneuver states (updateBapManeuverState): 1 follow, 2 prepare (~1250 m city,
  ~3200 m motorway), 3 approach (~430 m / ~1750 m, bargraph starts), 4 at the
  turn (~100 m / ~150 m).
- Bargraph: 255 × distance / approach-start distance, counting down to 0.
- `updateTurnToStreet` ignores its boolean; the turn-to label shows only when the
  street is non-empty **and** the bargraph is on.
- Stock nav without a route still calls `updateRgActive(false)` / `updateRGIString(null)`
  now and then; anything we feed must be re-sent (v15 re-sends within 2 s).

### 9.3 What the TT actually shows, and why iPhone turns cannot use it

- The cockpit guidance banner (and the junction picture near turns) is the nav
  engine's KOMO/KDK view, a separate displayable (20) that the HMI shows when
  `ClusterKDKHandler` sees `komoViewEnabled && komoViewVisible && rgActive`.
  View style on MMI-Kombi: `KOMOCaller.getKomoViewStyle()` = 3. Layout files:
  `guidanceview_*_293x323.xml` and `KDKConfig_293x323.kdx` on `app.img`.
- On the map tab this view replaces the CarPlay map. That was the v12 "map
  disappears" effect.
- The HMI feeds the view "nav info" (`DSIKOMONavInfo`: distance to maneuver,
  ETA, current and turn-to street, distance to destination) and RouteInfoElements.
  The distance text does come from the HMI. That is why v12 showed the right
  distance. `ClusterService.refreshDistanceToNextManeuverMOST` marks it invalid
  while the bargraph is on, which is why the v17/v18 tests (always sent with a
  bargraph) showed none.
- The arrow and junction picture come from the nav engine's own route: it zooms
  into its own map data. It reports the view as not visible whenever it has no
  route, and it ignored every maneuver element and mode switch we sent.
- MHI2Q cluster cars are different: their cluster is a separate computer that
  draws the turn display from BAP data, which is why luka's approach works there.

## 10. Drawing our own guidance (current work)

The cockpit is the HMI's main terminal, and its scene graph is built through
`EALManager` (e.solutions engine layer). We can add our own nodes there:

- Group nodes: `createNode3D(parent, name, w, h)`, then `setPosition(x, y, 0)`.
- Images from our own pixels:
  `createTextureDescription(ByteBuffer.allocateDirect(w*h*4) RGBA, w, h, 6 /*RGBA8888*/, null, key)`
  then `createImage3D(parent, name, desc, 0, true, owner)`.
- Text in the HMI fonts: `HMITerminalImpl.fontLoader.getFont(size, style)` and
  `createText3D(parent, name, text, font)`.
- Line drawing: `createQuickDrawNode3D` (polyline nodes).
- Parents: `getPartialPopupsFrontNode()` (above all screens) or `getScreensNode()`.
  Call `fireRenderEvent()` after changes.
- All EAL work must run on the HMI event thread:
  `terminal.getFramework().getHMIService().getEventDispatcher().postEvent(new RunnableEvent(job))`.
- The EALManager is captured in the patched `AnimationMIB2HighSport.setViewSize`
  (fallback: `CombiMapController.hmiService` via reflection).
- Caution: an HMI class dump from a newer build differs from the car (no
  `HMITerminal.getEventDispatcher`, no `EALManager.getFontLoader` on MU1462).
  Always check APIs against your own converted `lsd.jxe`.

**v19 result (parked):** our node is drawn on the cockpit, at each requested
position, with our text label inside, but squashed to a thin white strip:
width right, height a few pixels. Every EAL call reported success. Log facts:

- Screen 1440x540; `popupsUnboundFrontLTRNode` and `screensLTRNode` both at 0,0, size 1440x540.
- Layout class on this car: `LayoutMIB2MMICombiTTSport`.
- Stock guidance banner (KDK displayable) position from layout constants
  58/59 (small view) = 991,152 and 60/61 (large view) = 1105,110;
  constants 118–121 = 59,27,210,153 and 122–125 = 0,0,328,180.
- `WrappedNode3D.setSize` only stores the size; the car's own `IconRendererHigh`
  sizes an image by `setScale(targetW / unscaledW, targetH / unscaledH, 1)`.

**v20 result (parked):** all four variants drew, the right size. The logs
showed why v19 was a strip: the image came out 200x6. On the car
`createTextureDescription` takes `(buffer, FORMAT, width, height)`, so our
`(400, 200, 6)` meant format 400, width 200, height 6. Stock-style scaling then
stretched that strip to the requested size. Text did not show because the car's
`getFont` takes `(style, size)` (style 1 plain, 2 bold): our `getFont(40, 0)`
asked for size 0.

**v21 (parked test, pending):** both fixes, plus the real turn panel:

- `PanelArt` draws the panel in plain Java (no AWT on the car): rounded
  background, the maneuver picture (turn arrows with the junction's side
  streets in grey, slight turns, motorway exits, forks, U-turn, roundabouts,
  arrival flag), a vertical progress bar and a lane row. Lanes come from Google
  Maps (`lgN_*`): preferred lanes white, good lanes light grey, others dark,
  shown during the approach (state 3+) like stock.
- Distance (bold) and street (plain) are HMI-font text nodes.
- Drawn at the stock banner spot, following the large/small view. The iPhone
  feed drives it live (`TtPanel.live`); `kit/panel-test.txt` is a parked demo.
- The stock KOMO banner is now off by default, so the CarPlay map stays on the
  map tab (`sh tt.sh mode kdk` brings it back for comparison).

**v21 result (car photos, parked demo):** the panel draws, arrows and text
included. In the large view the 300x340 panel was too big: its lower half (distance,
street) was behind the speedometer, which is drawn above our layer, and it ran
off the right edge. In the small view it did not show at all: the car's own page
(Consumption) covers that spot unless the stock guidance banner is active.

**v22 (parked test, pending):**
- Large view: compact 260x140 panel at 1150,96, above the speedometer (picture
  left; distance, street and lanes right; thin bar at the edge). Street names are
  shortened with "..." to fit (`EALManager.getTextWidth`).
- Small view: the stock banner is switched on for a CarPlay route (`TtRgi.kdkWanted`)
  and the tall panel drawn at its spot. The view switch re-declares the route at
  once (`ZoomGate.stage` → `TtRgi.viewChanged` / `TtPanel.viewChanged`).
- `sh tt.sh panel large|small X Y [SCALE]` writes `/tmp/tt-panel-pos`; position
  and scale apply at the next redraw, with no rebuild or restart.

**v23 (user's layout, parked test pending):** before testing v22, the layout
was changed to follow stock nav's two boxes:
- Large view (map tab): a LEFT banner (top-left of the map: picture, distance,
  exit number or street, lanes, bar) and a RIGHT banner (above the speedometer:
  flag, distance to destination, arrival time). Google/Apple Maps label roads
  on the map, so the banners stay small.
- Small view (tach in the centre): one FULL panel on the right (stock banner spot,
  banner enabled under it) with all of it: picture, bar, lanes, distance, exit and
  street, destination row.
- Exit numbers are not a separate iAP2 field here; `RgdBus.exitNumber` takes
  the token after "exit" in the maneuver text ("Take exit 12A toward …" → 12A).
- Live tuning keys: `sh tt.sh panel left|right|small X Y [SCALE]`.
- Preview: [docs/panel-preview-v23.png](docs/panel-preview-v23.png).

**v23 result (car photos):** in the large view (map tab) both banners draw,
with "Exit 12A", lanes and "6:15 PM", but both are clipped by the screen's
rounded top corners. In the small view the enabled stock banner (the nav
engine's placeholder: blue arrow in an ellipse, small chequered flag) covers our
panel. On the car tab nothing of ours shows.

Reading: the TT cluster draws its own gauges and pages (car tab, Consumption)
and shows only parts of the head unit's picture: the map area, and the banner
spot while the stock banner (KDK displayable 20, composited by the display
manager above the HMI's EAL layers) is on. The EAL also has a
`VIEWPORT_G24_KDK_BACKGROUND` (220) above the main viewport (210).

**v24 (parked test, pending):** `kit/kdk-test.txt` forces the stock banner on
in every view, then makes displayable 20 transparent
(`HMIService.getDisplayManager().setOpacity(20, 0, alpha)`, re-applied on every
redraw), then tries the full panel at the banner spot in the large view (for the
car tab). `panel kdk info` logs displayable 20's opacity and position, the
current display context and the displayable list. This should show whether our
layer appears wherever the cluster shows the banner spot. Banner defaults moved
inward: left 120,96 and right 1110,100. Also found:
`IDisplayManagerKombiControl.createImageDisplayable(ResourceLocator, id)`, a
possible way to put our own image layer on the cockpit picture if this fails.

Offline previews of the panel: [docs/panel-preview.png](docs/panel-preview.png),
[docs/panel-preview-lanes.png](docs/panel-preview-lanes.png), [docs/panel-preview-compact.png](docs/panel-preview-compact.png) (text there is
rendered by the PC; the car uses its own fonts).

Plan: a hybrid. The stock banner keeps showing distance, ETA and streets (fed
without the bargraph); our panel draws the turn arrow, and later lanes, from
iPhone data on top of it.

## 11. Build, test kit and workflow

- **Build:** `hmi/tools/build.sh <tag>`: javac (`--release 8`), PatchZoom, VerifyZoom
  (ASM verifier), `checklib.py` (every `java.*` call checked against the car's
  class library), deterministic jar (`mkjar.py`). Paths to your own converted
  jar, ASM and the car's java library are given as environment variables. The
  resulting jar contains patched stock classes, so it is never committed.
- **Toolkit:** `kit/tt.sh`. Copy the kit to `/tmp` and run `sh /tmp/tt.sh setup`
  once; afterwards from the SSH login folder:
  - `sh tt.sh report`: what is installed and running
  - `sh tt.sh use vNN`: install a stored jar (sha-checked); restart the MMI
  - `sh tt.sh play panel|komo|stock|arrows`, `sh tt.sh stop`: parked tests
  - `sh tt.sh mode ...`, `sh tt.sh save`, `sh tt.sh remove`
- **Parked tests instead of drives.** `RgiPlayer` plays `/tmp/tt-rgi-play.txt`
  (wait / loop / say / feed commands / `panel ...`) and pauses the iPhone feed.
  `kit/stock-replay.txt` was generated from a recorded stock drive
  (`scripts/mkreplay.py`; `scripts/sumnav.py` summarises a NavSpy log).
  Stock nav's presentation mode simulates a route while parked.
- **Logs:** `/tmp/tt-rgd.log` (bus, feed, playback, panel; 256 KB + one old file),
  `/tmp/tt-nav.log` (NavSpy: every ClusterService/KOMOService input and the nav
  engine's answers), `/tmp/tt-zoom.log`, `/tmp/tt-rgd.txt`. `sh tt.sh save`
  keeps them across MMI restarts in `/mnt/app/root/tt/logs/runN`.
- SSH needs `-o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa`;
  scp needs `-O`. `/tmp` on the car is flat and wiped on MMI restart.

## 12. Third-party code and what is not in this repository

- `hmi/src/local/tt/ManeuverMapper.java`: luka-dev/mib2q-carplay-rgi, GPL-3.0, unchanged apart from the package.
- CPHB bus protocol: from yuedizhibo/MHI2Q-CarPlay-AltScreen (GPL-3.0); `RgdBus` is our own implementation.
- chefranov's `librgd_hook.so`: used unmodified on the car, **not** included (no redistribution rights).
- Not included, by rule: firmware, converted HMI jars, `tt-zoom.jar` (it contains patched stock classes),
  the patched `cluster_runtime`, the generated `cluster_supervisor.sh`, `dio_manager`.
  Scripts here rebuild them from your own firmware copy.

## 13. Open items

- Car-test the v21 turn panel (parked demo, then a live Google/Apple Maps route); tune size, position and lane look.
- Banner field test: which nav-info fields (distance, ETA, streets) the stock banner shows when fed without the bargraph.
- Decide how our panel and the stock banner share the map tab with the CarPlay map.
- Refresh the installer package (`package/`) with the final jar.
