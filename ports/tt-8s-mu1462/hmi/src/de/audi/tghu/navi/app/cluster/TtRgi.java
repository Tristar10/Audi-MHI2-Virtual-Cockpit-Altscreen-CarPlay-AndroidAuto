package de.audi.tghu.navi.app.cluster;

import java.io.File;

import org.dsi.ifc.komoview.ManeuverElement;
import org.dsi.ifc.komoview.RouteInfoElement;
import org.dsi.ifc.navigation.DistanceToNextManeuver;

/**
 * Feeds iPhone route guidance into the TT's own cockpit turn display (v15).
 *
 * On the TT (an "MMI-Kombi" car) the head unit's HMI draws the cockpit turn
 * icon, distance, bargraph, ETA and streets from the COMBI_NAV_* models,
 * which stock navigation fills through ClusterService. This calls the same
 * ClusterService methods, in the same pattern stock navigation used on a
 * recorded route (tt-logs run3, 2026-11-17):
 *   state 1 follow road    icon {11,255}, distance, no bargraph
 *   state 2 prepare        icon {12,dir}
 *   state 3 approach       full icon {main,dir,-1,arms...}, bargraph 255..0
 *   state 4 at the turn    same, last ~100 m
 * updateBapManeuverState gets the state; refreshDistanceToNextManeuver gets
 * the distance and bargraph (with the bargraph on, ClusterService also shows
 * the turn-to street instead of the current one).
 *
 * v15 parked test (2026-11-17): the COMBI_NAV_* models alone draw nothing on
 * the TT. What the TT shows is the nav engine's KOMO guidance view (style 3,
 * shown through the KDK displayable), fed by ClusterService through
 * KOMOService: streets, distances, ETA and a "follow info" RouteInfoElement.
 * On US cars ClusterService never hands it the next maneuver (that branch is
 * Asia/Porsche only), hence the plain straight arrow seen with v12.
 * v16: the KOMO view is allowed again (touch /tmp/tt-rgi-nokdk hides it for a
 * CarPlay-only route, the v15 behaviour), and the playback command "rie"
 * pushes maneuver RouteInfoElements into it to find what it draws.
 * v16 parked test: the elements reached KOMOService exactly like stock's,
 * but nothing drew: the nav engine reports its view as not visible
 * (KOMOService.updateVisibility false) whenever it has no route of its own,
 * and the KDK handler shows the view only when it is visible.
 * v17: while a CarPlay route is declared, komoVisible() reports the view as
 * visible, and declaring a route also asks the nav engine to enable and show
 * it (enableKomoView / notifyVisibility).
 *
 * Stock navigation without a route still pushes "route off" now and then,
 * which clears the models. The NavSpy hooks report such stock calls through
 * touched(); the next update (at most 2 s later) then sends everything again.
 *
 * Lives in the stock cluster package for its protected members. Every call
 * is guarded; failures come back as text for /tmp/tt-rgd.log.
 * Java 1.4 class library: StringBuffer only, no autoboxing.
 */
public final class TtRgi {
    private static final Object LOCK = new Object();
    /** v21: the stock KOMO banner is off by default (our own panel draws instead); this marker brings it back. */
    private static final File KDK_ALLOW = new File("/tmp/tt-rgi-kdk");
    /** v22: in the small view the banner spot otherwise shows the car's own page over our panel. */
    private static final File KDK_NOSMALL = new File("/tmp/tt-rgi-nokdk-small");
    private static CombiBAPListener listener;
    private static volatile boolean carplayRg;
    /** Whether a CarPlay route is reported as "route active" right now. */
    private static volatile boolean declared;
    /** The thread inside one of our ClusterService calls, if any. */
    private static volatile Thread caller;
    /** Stock navigation changed the cockpit models since our last update. */
    private static volatile boolean dirty;
    private static int kdkBlocked;
    private static String iconKey;
    private static int state = Integer.MIN_VALUE;
    private static int distManeuver = Integer.MIN_VALUE;
    private static int bargraph = Integer.MIN_VALUE;
    private static int distDest = Integer.MIN_VALUE;
    private static long eta = Long.MIN_VALUE;
    private static String street;
    private static String turnTo;

    private TtRgi() {
    }

    /** Patched DSIResponseContainer.isRgActive() call sites: stock flag OR a CarPlay route. */
    public static boolean rgActive(boolean stock) {
        return stock || (carplayRg && declared);
    }

    /**
     * Patched ClusterService.updateRgActive: the value passed on to the KDK
     * handler. Our own calls pass on only the real stock route state.
     */
    public static boolean kdkRg(boolean on) {
        if (!on || caller != Thread.currentThread() || kdkWanted()) {
            return on;
        }
        ClusterService cs = service();
        boolean stock = false;
        try {
            stock = cs != null && cs.env.getContainer().isRgActive();
        } catch (Throwable t) {
            stock = false;
        }
        if (!stock) {
            kdkBlocked++;
        }
        return stock;
    }

    /** Patched KOMOService.updateVisibility: the nav engine's value, or true during a CarPlay route. */
    public static boolean komoVisible(boolean v) {
        return v || (carplayRg && declared && kdkWanted());
    }

    /** Shows (or hides) the KOMO guidance view for a CarPlay route. Returns a log note. */
    private static String showKomo(ClusterService cs, boolean on) {
        KOMOService k = cs.komoService;
        if (k == null) {
            return " (no KOMO service)";
        }
        if (on && !kdkWanted()) {
            on = false;
        }
        try {
            if (on) {
                k.enableKomoView(true);
                k.notifyVisibility(true);
            }
            k.updateVisibility(on, 1);
            return on ? " (komo shown)" : " (komo released)";
        } catch (Throwable t) {
            return new StringBuffer(" (komo error ").append(String.valueOf(t)).append(')').toString();
        }
    }

    /** Show the stock banner for a CarPlay route: always with /tmp/tt-rgi-kdk, else in the small view. */
    private static boolean kdkWanted() {
        return KDK_ALLOW.exists() || (local.tt.ZoomGate.small() && !KDK_NOSMALL.exists());
    }

    /** ZoomGate: the cockpit view changed; the next update re-declares the route (banner on/off). */
    public static void viewChanged() {
        if (!carplayRg || !declared) {
            return;
        }
        synchronized (LOCK) {
            ClusterService cs = service();
            if (cs == null) {
                return;
            }
            caller = Thread.currentThread();
            try {
                // Re-declare: kdkRg/komoVisible now answer for the new view.
                cs.updateRgActive(true);
                showKomo(cs, true);
            } catch (Throwable ignored) {
                dirty = true;
            } finally {
                caller = null;
            }
        }
    }

    /** NavSpy hook: a cockpit input was called. Marks stock calls made during a CarPlay route. */
    public static void touched() {
        if (carplayRg && caller != Thread.currentThread()) {
            dirty = true;
        }
    }

    /** CombiBAPListener.setCombiService(service): remembers the stock listener. */
    public static String attach(Object who, Object service) {
        synchronized (LOCK) {
            if (who instanceof CombiBAPListener) {
                listener = (CombiBAPListener) who;
            }
        }
        StringBuffer b = new StringBuffer("combi service ");
        b.append(service == null ? "null" : service.getClass().getName());
        b.append(who instanceof CombiBAPListener ? " (listener kept)" : " (unexpected listener)");
        return b.toString();
    }

    private static ClusterService service() {
        CombiBAPListener l = listener;
        return l == null ? null : l.clusterservice;
    }

    /**
     * One playback command from /tmp/tt-rgi-play.txt (see local.tt.RgiPlayer):
     *   active 1|0          report a route as active or not
     *   icon 13,64,-1,0,-64 raw icon stream for COMBI_NAV_R_G_I_DATA; "icon none" clears it
     *   state 3             updateBapManeuverState (1 follow, 2 prepare, 3 approach, 4 at turn)
     *   turn 250 [bar]      distance to the turn in metres; optional bargraph 0..255
     *   dest 5300           distance to the destination in metres
     *   eta 600             arrival in that many seconds from now; "eta none" clears it
     *   road Main St        current street
     *   next Elm St         street after the turn ("next" alone clears it)
     *   exitview 5          ClusterService.updateExitView
 *   rgsel N             KOMOService.setRgSelect: 0 RGI, 1 KDK, 2 compass, 3 map
 *   style N             KOMOCaller.setKomoViewStyle (TT default 3; 255 = HMI-controlled RGI)
 *   rie MODE e,d;dist text;turn-to street;road icon text;exit number
 *                       maneuver RouteInfoElement into the KOMO guidance view.
 *                       MODE info: setRouteInfo({follow info, element});
 *                       elem: setRouteInfoElement(element); only: setRouteInfo({element});
 *                       follow: back to the stock follow info.
     *   clear               end playback: everything off, back to the iPhone feed
     * Returns a line for the log.
     */
    public static String raw(String cmd, String arg) {
        synchronized (LOCK) {
            ClusterService cs = service();
            if (cs == null) {
                return "play: no cluster service yet";
            }
            StringBuffer b = new StringBuffer("play ");
            b.append(cmd).append(' ').append(arg);
            caller = Thread.currentThread();
            try {
                reset();
                if (cmd.equals("active")) {
                    boolean v = arg.startsWith("1");
                    carplayRg = true;
                    declared = v;
                    if (v || !stockActive(cs, b)) {
                        cs.updateRgActive(v);
                    }
                    b.append(showKomo(cs, v));
                } else if (cmd.equals("icon")) {
                    if (arg.equals("none") || arg.length() == 0) {
                        cs.updateRGIString(null);
                    } else {
                        cs.updateRGIString(shorts(arg));
                    }
                } else if (cmd.equals("state")) {
                    int[] v = ints(arg);
                    cs.updateBapManeuverState(v.length > 0 ? v[0] : 0);
                } else if (cmd.equals("turn")) {
                    int[] v = ints(arg);
                    int m = v.length > 0 ? v[0] : -1;
                    boolean bar = v.length > 1;
                    cs.refreshDistanceToNextManeuver(
                        new DistanceToNextManeuver(m >= 0, m >= 0 ? m : 0, bar, bar ? v[1] : 0));
                } else if (cmd.equals("dest")) {
                    int[] v = ints(arg);
                    cs.updateDistanceToDestination(v.length > 0 ? v[0] : -1, false);
                } else if (cmd.equals("eta")) {
                    int[] v = ints(arg);
                    if (v.length == 0) {
                        cs.updateArrivalTime(false, 0L, false);
                    } else {
                        long t = System.currentTimeMillis() + v[0] * 1000L;
                        try {
                            t = cs.env.getFramework().convertUTCTimeToLocalTime(t);
                        } catch (Throwable e) {
                            b.append(" (utc)");
                        }
                        cs.updateArrivalTime(true, t, true);
                    }
                } else if (cmd.equals("road")) {
                    cs.updateCurrentStreet(arg);
                } else if (cmd.equals("next")) {
                    cs.updateTurnToStreet(arg, arg.length() > 0);
                } else if (cmd.equals("exitview")) {
                    int[] v = ints(arg);
                    cs.updateExitView(v.length > 0 ? v[0] : 0);
                } else if (cmd.equals("rgsel")) {
                    int[] v = ints(arg);
                    KOMOService k = cs.komoService;
                    if (k == null) {
                        b.append(" (no KOMO service)");
                    } else {
                        k.setRgSelect(v.length > 0 ? v[0] : 0);
                    }
                } else if (cmd.equals("style")) {
                    int[] v = ints(arg);
                    b.append(style(cs, v.length > 0 ? v[0] : 3));
                } else if (cmd.equals("rie")) {
                    b.append(rie(cs, arg));
                } else if (cmd.equals("clear")) {
                    boolean was = declared;
                    carplayRg = false;
                    declared = false;
                    if (!stockActive(cs, b)) {
                        cs.updateRGIString(null);
                        if (was) {
                            cs.updateRgActive(false);
                            b.append(showKomo(cs, false));
                        }
                    }
                } else {
                    b.append(" (unknown command)");
                }
                if (kdkBlocked > 0) {
                    b.append(" (map kept: kdk off)");
                    kdkBlocked = 0;
                }
            } catch (Throwable t) {
                b.append(" error ").append(String.valueOf(t));
            } finally {
                caller = null;
                dirty = false;
            }
            return b.toString();
        }
    }

    /** Playback "style": KOMOCaller.setKomoViewStyle through reflection (the caller is private). */
    private static String style(ClusterService cs, int v) {
        KOMOService k = cs.komoService;
        if (k == null) {
            return " (no KOMO service)";
        }
        try {
            // No class literals: they need class version 49; the HMI runs 46.
            java.lang.reflect.Field f = Class.forName("de.audi.tghu.navi.app.cluster.KOMOService")
                .getDeclaredField("komoCaller");
            f.setAccessible(true);
            KOMOCaller c = (KOMOCaller) f.get(k);
            c.setKomoViewStyle(v);
            return " -> sent";
        } catch (Throwable t) {
            return new StringBuffer(" error ").append(String.valueOf(t)).toString();
        }
    }

    /** Playback "rie": see raw(). */
    private static String rie(ClusterService cs, String arg) {
        KOMOService k = cs.komoService;
        if (k == null) {
            return " (no KOMO service)";
        }
        int sp = arg.indexOf(' ');
        String mode = sp < 0 ? arg : arg.substring(0, sp);
        if (mode.equals("follow")) {
            cs.updateKOMOFollowInfo();
            return " -> follow info";
        }
        String rest = sp < 0 ? "" : arg.substring(sp + 1);
        String[] f = new String[] { "", "", "", "", "" };
        int n = 0;
        int start = 0;
        while (n < 5) {
            int semi = rest.indexOf(';', start);
            if (semi < 0) {
                f[n] = rest.substring(start).trim();
                break;
            }
            f[n] = rest.substring(start, semi).trim();
            n++;
            start = semi + 1;
        }
        int[] ed = ints(f[0]);
        ManeuverElement[] md = new ManeuverElement[] {
            new ManeuverElement(ed.length > 0 ? ed[0] : 13, (short) (ed.length > 1 ? ed[1] : 0), 0) };
        boolean shield = f[3].length() > 0;
        RouteInfoElement e = new RouteInfoElement(f[1], null, 3, null, null, f[3], shield ? 10050 : 9999,
            f[4], f[2], null, md, null, 0, null, null, null, "", -1);
        if (mode.equals("info")) {
            k.setRouteInfo(new RouteInfoElement[] { cs.followInfoRIE, e });
        } else if (mode.equals("only")) {
            k.setRouteInfo(new RouteInfoElement[] { e });
        } else {
            k.setRouteInfoElement(e);
        }
        return " -> sent";
    }

    /** Integers separated by commas or spaces; anything else is skipped. */
    private static int[] ints(String s) {
        int[] tmp = new int[64];
        int n = 0;
        int i = 0;
        int len = s.length();
        while (i < len && n < tmp.length) {
            char c = s.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                int j = i + 1;
                while (j < len && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    j++;
                }
                try {
                    int v = Integer.parseInt(s.substring(i, j));
                    tmp[n] = v;
                    n++;
                } catch (Throwable t) {
                    // a lone "-": skip it
                }
                i = j;
            } else {
                i++;
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static short[] shorts(String s) {
        int[] v = ints(s);
        short[] out = new short[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (short) v[i];
        }
        return out;
    }

    private static String key(short[] icon) {
        if (icon == null) {
            return "none";
        }
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < icon.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(icon[i]);
        }
        return b.toString();
    }

    private static boolean stockActive(ClusterService cs, StringBuffer b) {
        try {
            return cs.env.getContainer().isRgActive();
        } catch (Throwable t) {
            b.append(" (stock state unknown)");
            return false;
        }
    }

    private static void reset() {
        iconKey = null;
        state = Integer.MIN_VALUE;
        distManeuver = Integer.MIN_VALUE;
        bargraph = Integer.MIN_VALUE;
        distDest = Integer.MIN_VALUE;
        eta = Long.MIN_VALUE;
        street = null;
        turnTo = null;
    }

    /**
     * Sends whatever changed. icon is the COMBI_NAV_R_G_I_DATA stream (null:
     * none yet), mState the BAP maneuver state (<= 0: leave it), bar the
     * bargraph 0..255 (< 0: off). Negative distances and times mean unknown;
     * an empty current road leaves stock's map-matched street alone. Returns a
     * short description for the log, or null when nothing was sent.
     */
    public static String update(boolean on, boolean declare, short[] icon, int mState,
                                int maneuverM, int bar, int destM, long etaUtcSeconds,
                                String currentRoad, String nextRoad) {
        synchronized (LOCK) {
            ClusterService cs = service();
            if (cs == null) {
                return carplayRg == on ? null : "no cluster service yet";
            }
            StringBuffer b = new StringBuffer();
            caller = Thread.currentThread();
            try {
                if (!on) {
                    if (!carplayRg) {
                        return null;
                    }
                    carplayRg = false;
                    boolean wasDeclared = declared;
                    declared = false;
                    reset();
                    if (!stockActive(cs, b)) {
                        cs.updateRGIString(null);
                        if (wasDeclared) {
                            cs.updateRgActive(false);
                            b.append(showKomo(cs, false));
                        }
                    }
                    return b.insert(0, "hmi guidance off").toString();
                }
                if (!carplayRg) {
                    carplayRg = true;
                    reset();
                    b.append(" on");
                }
                if (dirty) {
                    // Stock navigation touched the models: send everything again.
                    dirty = false;
                    reset();
                    if (declared && !stockActive(cs, b)) {
                        declared = false;
                    }
                    b.append(" resend");
                    if (declared) {
                        cs.updateRgActive(true);
                        b.append(showKomo(cs, true));
                    }
                }
                if (declare != declared) {
                    declared = declare;
                    if (declare) {
                        cs.updateRgActive(true);
                        b.append(" active").append(showKomo(cs, true));
                    } else {
                        // updateRgActive(false) clears the turn models: resend everything below.
                        if (!stockActive(cs, b)) {
                            cs.updateRgActive(false);
                        }
                        reset();
                        b.append(" inactive");
                    }
                }
                String k = key(icon);
                if (icon != null && !k.equals(iconKey)) {
                    iconKey = k;
                    cs.updateRGIString(icon);
                    b.append(" icon=").append(k);
                }
                if (mState > 0 && mState != state) {
                    state = mState;
                    cs.updateBapManeuverState(mState);
                    b.append(" state=").append(mState);
                }
                if (maneuverM != distManeuver || bar != bargraph) {
                    distManeuver = maneuverM;
                    bargraph = bar;
                    boolean show = bar >= 0;
                    cs.refreshDistanceToNextManeuver(new DistanceToNextManeuver(
                        maneuverM >= 0, maneuverM >= 0 ? maneuverM : 0, show, show ? bar : 0));
                    b.append(" turn=").append(maneuverM).append('m');
                    if (show) {
                        b.append(" bar=").append(bar);
                    }
                }
                if (destM != distDest) {
                    distDest = destM;
                    cs.updateDistanceToDestination(destM >= 0 ? destM : -1, false);
                    b.append(" dest=").append(destM).append('m');
                }
                if (etaUtcSeconds != eta) {
                    eta = etaUtcSeconds;
                    if (etaUtcSeconds > 0L) {
                        long local = etaUtcSeconds * 1000L;
                        try {
                            local = cs.env.getFramework().convertUTCTimeToLocalTime(local);
                        } catch (Throwable t) {
                            b.append(" (utc)");
                        }
                        cs.updateArrivalTime(true, local, true);
                    } else {
                        cs.updateArrivalTime(false, 0L, false);
                    }
                    b.append(" eta=").append(etaUtcSeconds);
                }
                String road = currentRoad == null ? "" : currentRoad;
                if (road.length() > 0 && !road.equals(street)) {
                    street = road;
                    cs.updateCurrentStreet(road);
                    b.append(" road=").append(road);
                }
                String next = nextRoad == null ? "" : nextRoad;
                if (!next.equals(turnTo)) {
                    turnTo = next;
                    cs.updateTurnToStreet(next, next.length() > 0);
                    b.append(" next=").append(next);
                }
                if (kdkBlocked > 0) {
                    b.append(" (map kept: kdk off)");
                    kdkBlocked = 0;
                }
            } catch (Throwable t) {
                b.append(" error ").append(String.valueOf(t));
            } finally {
                caller = null;
            }
            return b.length() == 0 ? null : b.insert(0, "hmi").toString();
        }
    }
}
