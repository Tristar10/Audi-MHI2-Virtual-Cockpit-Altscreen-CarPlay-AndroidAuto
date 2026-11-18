package local.tt;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Hashtable;

import de.audi.tghu.navi.app.cluster.TtRgi;
import org.dsi.ifc.komoview.RouteInfoElement;
import org.dsi.ifc.navigation.BapManeuverDescriptor;
import org.dsi.ifc.navigation.BapTurnToInfo;
import org.dsi.ifc.navigation.DistanceToNextManeuver;
import org.dsi.ifc.navigation.NavLaneGuidanceData;
import org.dsi.ifc.navigation.RgInfoForNextDestination;

/**
 * Logging-only hooks on the stock navigation's cockpit inputs (tt-zoom v14).
 *
 * ClusterService receives everything stock navigation knows about the next
 * maneuver; on the TT it feeds the HMI's COMBI_NAV_* models (the cockpit turn
 * display). These hooks record each input while stock navigation (or its
 * presentation/demo mode) guides, so the cockpit display can be fed the same
 * way from the iPhone. Nothing is changed.
 *
 * Log: /tmp/tt-nav.log, up to 512 KB (then logging stops, nothing is lost by
 * rotation). A line is skipped when the same kind of input repeats unchanged.
 *
 * v15: every hook is fully guarded (a failing toString can never break the
 * stock method), and the cockpit-turn inputs report to TtRgi.touched() so a
 * stock call that overwrites the CarPlay turn display gets re-sent.
 *
 * Java 1.4 class library: StringBuffer only, no autoboxing.
 */
public final class NavSpy {
    private static final File LOG = new File("/tmp/tt-nav.log");
    private static final long LIMIT = 524288L;
    private static final Object LOCK = new Object();
    private static final Hashtable LAST = new Hashtable();

    private NavSpy() {
    }

    /** ClusterService.updateRGIString(short[]): the cockpit turn icon stream. */
    public static void rgi(short[] data) {
        try {
            TtRgi.touched();
            StringBuffer b = new StringBuffer();
            if (data == null) {
                b.append("null");
            } else {
                b.append("n=").append(data.length).append(" [");
                for (int i = 0; i < data.length && i < 400; i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    b.append(data[i]);
                }
                if (data.length > 400) {
                    b.append(",...");
                }
                b.append(']');
            }
            log("rgi", b.toString());
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateRgActive(boolean). */
    public static void rgActive(boolean on) {
        try {
            TtRgi.touched();
            log("rgActive", on ? "true" : "false");
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateTurnToStreet(String, boolean). */
    public static void turnTo(String street, boolean valid) {
        try {
            TtRgi.touched();
            StringBuffer b = new StringBuffer();
            log("turnTo", b.append(street).append(" valid=").append(valid).toString());
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateCurrentStreet(String). */
    public static void street(String street) {
        try {
            log("street", String.valueOf(street));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.refreshViewMode(int). */
    public static void viewMode(int mode) {
        try {
            log("refreshViewMode", String.valueOf(mode));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateManeuverDescriptor(BapManeuverDescriptor[]). */
    public static void maneuvers(BapManeuverDescriptor[] a) {
        try {
            log("maneuvers", join(a));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateManeuverDescriptor(BapManeuverDescriptor[], int). */
    public static void maneuversN(BapManeuverDescriptor[] a, int n) {
        try {
            StringBuffer b = new StringBuffer("n=");
            log("maneuversN", b.append(n).append(' ').append(join(a)).toString());
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateLaneGuidance(NavLaneGuidanceData[], boolean). */
    public static void lanes(NavLaneGuidanceData[] a, boolean show) {
        try {
            StringBuffer b = new StringBuffer("show=");
            log("lanes", b.append(show).append(' ').append(join(a)).toString());
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateBapTurnToInfo(BapTurnToInfo[]). */
    public static void turnToInfo(BapTurnToInfo[] a) {
        try {
            log("turnToInfo", join(a));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateRgInfoForNextDestination(RgInfoForNextDestination). */
    public static void nextDest(RgInfoForNextDestination r) {
        try {
            log("nextDest", String.valueOf(r));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateDistanceToNextManeuver(DistanceToNextManeuver). */
    public static void dtm(DistanceToNextManeuver d) {
        try {
            TtRgi.touched();
            log("dtm", String.valueOf(d));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateExitView(int). */
    public static void exitView(int v) {
        try {
            log("exitView", String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateBapManeuverState(int). */
    public static void maneuverState(int v) {
        try {
            TtRgi.touched();
            log("maneuverState", String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateNextManeuver(RouteInfoElement): the KOMO next-maneuver element. */
    public static void nextManeuver(RouteInfoElement e) {
        try {
            log("nextManeuver", String.valueOf(e));
        } catch (Throwable ignored) {
        }
    }

    /** ClusterService.updateRgDirectionToNextDestination(short). */
    public static void direction(short d) {
        try {
            log("direction", String.valueOf(d));
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.setRouteInfo(RouteInfoElement[]): elements for the KOMO guidance view. */
    public static void komoInfo(RouteInfoElement[] a) {
        try {
            log("komoInfo", join(a));
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.setRouteInfoElement(RouteInfoElement). */
    public static void komoElem(RouteInfoElement e) {
        try {
            log("komoElem", String.valueOf(e));
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.setTurnToStreet(turnToInfo, signPost). */
    public static void komoTurnTo(String t, String s) {
        try {
            StringBuffer b = new StringBuffer();
            log("komoTurnTo", b.append(t).append(" | ").append(s).toString());
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.setRgSelect(int). */
    public static void komoRgSelect(int v) {
        try {
            log("komoRgSelect", String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.updateKomoViewEnabled(boolean, int): nav engine reports the view enabled. */
    public static void komoEnabled(boolean on, int status) {
        try {
            StringBuffer b = new StringBuffer();
            log("komoEnabled", b.append(on).append(" status=").append(status).toString());
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.updateVisibility(boolean, int). */
    public static void komoVisible(boolean on, int status) {
        try {
            StringBuffer b = new StringBuffer();
            log("komoVisible", b.append(on).append(" status=").append(status).toString());
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.updateCurrentKomoViewType(int, int): 1 maneuver view, 2 route info. */
    public static void komoType(int type, int status) {
        try {
            StringBuffer b = new StringBuffer();
            log("komoType", b.append(type).append(" status=").append(status).toString());
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.komoViewResult(int): 0 ok, 1 not operable, 2 error, 3 unsupported. */
    public static void komoResult(int r) {
        try {
            StringBuffer b = new StringBuffer();
            // Not deduplicated: every answer matters.
            LAST.remove("komoResult");
            log("komoResult", b.append(r).toString());
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.setRgSelectResult(int): 0 ok, 1 error, 2 unsupported. */
    public static void komoRgSelResult(int r) {
        try {
            LAST.remove("komoRgSelResult");
            log("komoRgSelResult", String.valueOf(r));
        } catch (Throwable ignored) {
        }
    }

    /** KOMOService.updateGfxState(int, int). */
    public static void komoGfx(int state, int status) {
        try {
            StringBuffer b = new StringBuffer();
            log("komoGfx", b.append(state).append(" status=").append(status).toString());
        } catch (Throwable ignored) {
        }
    }

    private static String join(Object[] a) {
        if (a == null) {
            return "null";
        }
        StringBuffer b = new StringBuffer("n=");
        b.append(a.length).append(" [");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(" | ");
            }
            try {
                b.append(String.valueOf(a[i]));
            } catch (Throwable t) {
                b.append("?");
            }
        }
        return b.append(']').toString();
    }

    private static void log(String tag, String value) {
        try {
            synchronized (LOCK) {
                if (value.equals(LAST.get(tag))) {
                    return;
                }
                LAST.put(tag, value);
                if (LOG.length() >= LIMIT) {
                    return;
                }
                FileOutputStream out = new FileOutputStream(LOG.getPath(), true);
                try {
                    StringBuffer b = new StringBuffer();
                    b.append(System.currentTimeMillis() % 100000000L).append(' ').append(tag).append(' ')
                        .append(value).append('\n');
                    out.write(b.toString().getBytes());
                } finally {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
