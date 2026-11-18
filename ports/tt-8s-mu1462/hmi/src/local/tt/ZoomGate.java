package local.tt;

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Scroll-wheel zoom for the CarPlay map on the Audi TT MU1462 cluster.
 *
 * Called at the start of the stock AbstractGUI.increment/decrement (the main
 * map's wheel path on the TT) and CombiBAPListener.setMapScale. While the
 * CarPlay map renderer is drawing (its heartbeat file is fresh), magnification
 * steps go to the AltScreen hook as an "MZ01" datagram and the stock map is
 * left alone. Otherwise this returns false and the stock code runs unchanged.
 * Every decision is written to /tmp/tt-zoom.log (capped at 32 KB).
 *
 * Java 1.4 class library: no StringBuilder, no autoboxing.
 */
public final class ZoomGate {
    private static final int MAGNIFICATION = 400476;
    private static final File ACTIVE = new File("/tmp/mu1438-cluster-active");
    private static final File DISABLE = new File("/tmp/mu1438-cluster-disable");
    private static final File LOG = new File("/tmp/tt-zoom.log");
    private static final Object LOCK = new Object();
    private static final int GESTURE_SCALE = 400898;
    private static final File INVERT = new File("/tmp/tt-zoom-invert");
    private static final File SPORT = new File("/tmp/mu1438-cluster-sport");
    private static DatagramSocket socket;
    private static int lastScale;
    private static int lastDirection = -1;
    private static int skinState = -1;
    private static int smallState = -1;

    private ZoomGate() {
    }

    /** True while the cockpit shows the small view (1), false for large (0) or unknown. */
    public static boolean small() {
        return smallState == 1;
    }

    /** CombiBAPListener.setMapScale(steps). */
    public static boolean zoom(int steps) {
        return send("bap", steps);
    }

    /**
     * AbstractGUI.increment(id, value, x) passes +value, decrement passes -value.
     *
     * On the TT the wheel arrives as id 400898 with the map scale it wants next
     * (centimetres, e.g. 160900 = 1 mile), not as a step count. A larger scale is
     * zoomed out; CarPlay takes -1 to zoom in (tested on the car). The stock map is always allowed to follow (return false), so
     * the next value can be compared with this one; at a scale limit the value
     * repeats and the last direction is kept. The first wheel click after an MMI
     * start only records the scale.
     * touch /tmp/tt-zoom-invert reverses the direction for testing.
     */
    public static boolean gui(Object who, int id, int steps) {
        if (id == GESTURE_SCALE) {
            int scale = steps < 0 ? -steps : steps;
            int previous;
            synchronized (LOCK) {
                previous = lastScale;
                lastScale = scale;
                if (previous > 0 && scale != previous) {
                    lastDirection = scale < previous ? -1 : 1;
                }
            }
            if (previous <= 0) {
                StringBuffer b = new StringBuffer("scale ");
                log(b.append(scale).append(" first value, recorded").toString());
                return false;
            }
            int direction = lastDirection;
            if (INVERT.exists()) {
                direction = -direction;
            }
            StringBuffer b = new StringBuffer("scale ");
            b.append(previous).append("->").append(scale).append(scale < previous ? " in" : scale > previous ? " out" : " repeat");
            send(b.toString(), direction);
            return false;
        }
        if (id != MAGNIFICATION) {
            StringBuffer b = new StringBuffer("gui ");
            b.append(name(who)).append(" id=").append(id).append(" steps=").append(steps).append(" not zoom");
            log(b.toString());
            return false;
        }
        return send(name(who), steps);
    }

    /**
     * DisplayManagerEvo.setupKDKBackground(skin, ...): runs once at MMI start
     * with the stored skin. Skin 1 is sport. The cluster renderer checks SPORT
     * on every frame and moves the CarPlay picture into the sport window.
     */
    public static void skin(int skin) {
        synchronized (LOCK) {
            skinState = skin;
        }
        apply("skin ", skin);
        RgdBus.start();
    }

    /**
     * AnimationMIB2HighSport.setViewSize(eal, layout, smallStage): runs at MMI
     * start and on every large/small view change. The CarPlay picture moves only
     * in the Sport skin's small stage, where the big dial covers the right half.
     */
    public static void stage(boolean small) {
        int v = small ? 1 : 0;
        synchronized (LOCK) {
            if (smallState == v) {
                return;
            }
            smallState = v;
        }
        apply("stage small=", v);
        try {
            TtPanel.viewChanged();
            de.audi.tghu.navi.app.cluster.TtRgi.viewChanged();
        } catch (Throwable ignored) {
        }
    }

    /**
     * DSICarTimeUnitsLanguageManager.updateSkin(skin, validity): the car reports
     * a classic/sport change while the MMI runs. Only valid values (1) count.
     */
    public static void skinUpdate(int skin, int validity) {
        if (validity != 1) {
            StringBuffer b = new StringBuffer("car skin ");
            log(b.append(skin).append(" validity ").append(validity).append(" ignored").toString());
            return;
        }
        synchronized (LOCK) {
            skinState = skin;
        }
        apply("car skin ", skin);
    }

    private static void apply(String source, int value) {
        StringBuffer b = new StringBuffer(source);
        b.append(value);
        boolean sport;
        synchronized (LOCK) {
            sport = skinState != 0 && smallState == 1;
            b.append(" (skin=").append(skinState).append(" small=").append(smallState).append(")");
        }
        try {
            if (sport) {
                new FileOutputStream(SPORT.getPath(), false).close();
                b.append(" sport marker set");
            } else {
                SPORT.delete();
                b.append(" sport marker cleared");
            }
        } catch (Throwable t) {
            b.append(" error ").append(String.valueOf(t));
        }
        log(b.toString());
    }

    private static String name(Object who) {
        try {
            return who == null ? "null" : who.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean send(String source, int steps) {
        StringBuffer b = new StringBuffer(source);
        b.append(" steps=").append(steps);
        try {
            if (steps == 0) {
                log(b.append(" zero").toString());
                return false;
            }
            if (DISABLE.exists()) {
                log(b.append(" disabled").toString());
                return false;
            }
            long stamp = ACTIVE.lastModified();
            long now = System.currentTimeMillis();
            if (stamp <= 0 || now < stamp || now - stamp >= 3000) {
                log(b.append(" stock (carplay map not drawing, age=").append(now - stamp).append(")").toString());
                return false;
            }
            if (steps > 32) {
                steps = 32;
            }
            if (steps < -32) {
                steps = -32;
            }
            byte[] data = new byte[] {
                77, 90, 48, 49,
                (byte) (steps >> 24), (byte) (steps >> 16), (byte) (steps >> 8), (byte) steps
            };
            synchronized (LOCK) {
                if (socket == null) {
                    socket = new DatagramSocket();
                }
                socket.send(new DatagramPacket(data, 8, InetAddress.getByName("127.0.0.1"), 19822));
            }
            log(b.append(" sent to carplay").toString());
            return true;
        } catch (Throwable t) {
            log(b.append(" error ").append(String.valueOf(t)).toString());
            return false;
        }
    }

    private static void log(String line) {
        try {
            boolean append = LOG.length() < 32768;
            FileOutputStream out = new FileOutputStream(LOG.getPath(), append);
            try {
                out.write(line.getBytes());
                out.write(10);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
