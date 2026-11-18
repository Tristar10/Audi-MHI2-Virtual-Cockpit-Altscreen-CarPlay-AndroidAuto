package local.tt;

import java.nio.ByteBuffer;

import de.audi.atip.hmi.event.RunnableEvent;
import de.esolutions.hmi.widgets.audi.base.HMITerminalImpl;
import de.esolutions.hmi.widgets.audi.base.Layout;
import de.esolutions.hmi.widgets.audi.base.eal.EALManager;
import de.esolutions.hmi.widgets.audi.base.eal.IWrappedNode3D;
import de.esolutions.hmi.widgets.audi.base.eal.IWrappedNode3DImage;
import de.esolutions.hmi.widgets.audi.base.eal.IWrappedNode3DText;
import de.esolutions.hmi.widgets.audi.base.eal.TextureDescription;

/**
 * Our own drawing on the TT cockpit (tt-zoom v19, first stage: "hello panel").
 *
 * The TT's built-in guidance view only draws the nav engine's own route
 * (v15-v18 parked tests), so iPhone guidance has to be drawn by us. The
 * cockpit is the HMI's main terminal; its scene graph is built through
 * EALManager (the e.solutions engine layer): image nodes from RGBA byte
 * buffers, text nodes in the HMI's fonts, plain group nodes. Every EAL call
 * runs on the HMI event thread, posted as a RunnableEvent.
 *
 * The EALManager comes from the patched AnimationMIB2HighSport.setViewSize
 * (called at start-up and on every big/small stage change); if that never
 * fired, CombiMapController's static hmiService is used instead.
 *
 * Playback commands (local.tt.RgiPlayer, "panel ..."):
 *   panel info                          log screen size, layer nodes and KDK layout slots
 *   panel show PARENT X Y W H COLOR [TEXT]   draw a test box: PARENT front|screens|root,
 *                                       COLOR rrggbb, TEXT optional (white, HMI font)
 *   panel hide                          remove our nodes
 *   panel try MODE X Y W H COLOR [TEXT] v20 variants (front layer), each logs native transforms:
 *       group  image + text inside a group node (v19 way, solid colour, no border)
 *       direct image straight under the layer, positioned and scaled like the stock IconRendererHigh
 *       text   text node only, straight under the layer
 *       lines  QuickDraw outline rectangle
 * v19 car result: nodes drawn at the right place but squashed to a thin strip.
 * v20 found why: on the car createTextureDescription takes (buffer, FORMAT, width, height)
 * and getFont takes (style 1 plain / 2 bold, SIZE). Both fixed in v21.
 *
 * v21 turn panel (PanelArt picture + HMI-font text), drawn at the stock banner spot
 * (layout constants: small view 991,152, large view 1105,110) unless "panel at" moved it:
 *   panel turn MAIN,DIR[,-1,ARM...] METRES BAR LANES DEST ETA EXIT STREET...  (v23)
 *       DEST metres to the destination (-1 none); ETA UTC epoch seconds, or seconds from now
 *       when small (playback), -1 none; EXIT exit number or -
 *       BAR -1: no bar; LANES - for none, else lanes separated by '/', each
 *       ANGLE[,ANGLE...]:STATUS (degrees, positive right; status 0/1/2), e.g. -90:2/0:0/0:0/90:0
 *   panel at X Y | panel at auto | panel off
 * live(): the iPhone feed (RgdBus) calls this on every change; /tmp/tt-panel-off disables it.
 *
 * v22 (car photos): the 300x340 panel was too big in the large view (half behind the speedometer,
 * cut at the right edge) and invisible in the small view, where the car's own page covers the
 * banner spot. Large view: compact 260x140 panel above the speedometer. Small view: the stock banner
 * is switched on (TtRgi) and the tall panel drawn at its spot. Position and scale per view are read
 * from /tmp/tt-panel-pos (lines: large X Y SCALE / small X Y SCALE), so they can be tuned live.
 *
 * v23 (user's layout): large view = two banners like stock nav, LEFT (maneuver: picture, distance,
 * exit number or street, lanes, bar) and RIGHT (destination flag, distance, arrival time); the maps
 * already label roads. Small view = one FULL panel on the right with everything. pos-file keys:
 * left / right / small.
 * Results go to /tmp/tt-rgd.log ("panel ..."). Java 1.4 library only.
 */
public final class TtPanel {
    private static final Object LOCK = new Object();
    private static EALManager eal;
    private static IWrappedNode3D root;
    private static IWrappedNode3DImage box;
    private static IWrappedNode3DText label;
    private static int serial;

    private TtPanel() {
    }

    /** Patched AnimationMIB2HighSport.setViewSize(eal, layout, small): remembers the cockpit EALManager. */
    public static void eal(Object o) {
        try {
            if (o instanceof EALManager) {
                boolean first;
                synchronized (LOCK) {
                    first = eal == null;
                    eal = (EALManager) o;
                }
                if (first) {
                    RgdBus.note("panel eal from setViewSize");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static EALManager manager() {
        synchronized (LOCK) {
            if (eal != null) {
                return eal;
            }
        }
        try {
            java.lang.reflect.Field f = Class.forName(
                "de.esolutions.hmi.widgets.audi.evo.high.widgets.CombiMapController").getDeclaredField("hmiService");
            f.setAccessible(true);
            Object svc = f.get(null);
            if (svc instanceof de.audi.tghu.hmi.evo.IHMIServiceEvo) {
                Object t = ((de.audi.tghu.hmi.evo.IHMIServiceEvo) svc).getHMITerminal(0);
                if (t instanceof de.esolutions.hmi.widgets.audi.base.eal.HMITerminalEAL) {
                    EALManager m = ((de.esolutions.hmi.widgets.audi.base.eal.HMITerminalEAL) t).getEALManager();
                    synchronized (LOCK) {
                        eal = m;
                    }
                    RgdBus.note("panel eal from hmiService");
                    return m;
                }
            }
        } catch (Throwable t) {
            RgdBus.note(new StringBuffer("panel no eal: ").append(String.valueOf(t)).toString());
        }
        return null;
    }

    /** One "panel" playback command; the work is queued on the HMI thread. */
    public static String cmd(String arg) {
        EALManager m = manager();
        if (m == null) {
            return "panel: no EALManager yet";
        }
        try {
            HMITerminalImpl term = m.getTerminal();
            int id = ++serial;
            term.getFramework().getHMIService().getEventDispatcher().postEvent(
                new RunnableEvent(new PanelJob(m, arg, id)));
            return new StringBuffer("panel #").append(id).append(" queued: ").append(arg).toString();
        } catch (Throwable t) {
            return new StringBuffer("panel post failed ").append(String.valueOf(t)).toString();
        }
    }

    /** Runs on the HMI event thread (PanelJob). */
    static String onHmiThread(EALManager m, String arg, int id) {
        String[] w = words(arg);
        String op = w.length > 0 ? w[0] : "";
        if (op.equals("info")) {
            StringBuffer b = new StringBuffer("screen ");
            b.append(m.getScreenWidth()).append('x').append(m.getScreenHeight());
            b.append(" front=").append(describe(m.getPartialPopupsFrontNode()));
            b.append(" screens=").append(describe(m.getScreensNode()));
            try {
                Layout lay = m.getTerminal().getLayout();
                b.append(" layout=").append(lay == null ? "null" : lay.getClass().getName());
                int[] ids = { 58, 59, 60, 61, 118, 119, 120, 121, 122, 123, 124, 125 };
                for (int i = 0; i < ids.length && lay != null; i++) {
                    b.append(' ').append(ids[i]).append('=');
                    try {
                        b.append(lay.getIntegerConstant(ids[i]));
                    } catch (Throwable t) {
                        b.append('?');
                    }
                }
            } catch (Throwable t) {
                b.append(" layout error ").append(String.valueOf(t));
            }
            return b.toString();
        }
        if (op.equals("hide")) {
            clear(m);
            m.fireRenderEvent();
            return "hidden";
        }
        if (op.equals("show") && w.length >= 7) {
            clear(m);
            IWrappedNode3D parent = w[1].equals("screens") ? m.getScreensNode()
                : w[1].equals("root") ? (IWrappedNode3D) m.getMasterRoot() : m.getPartialPopupsFrontNode();
            if (parent == null) {
                return new StringBuffer("no parent node ").append(w[1]).toString();
            }
            int x = num(w[2]);
            int y = num(w[3]);
            int bw = Math.max(8, Math.min(1024, num(w[4])));
            int bh = Math.max(8, Math.min(1024, num(w[5])));
            int rgb = hex(w[6]);
            StringBuffer text = new StringBuffer();
            for (int i = 7; i < w.length; i++) {
                if (i > 7) {
                    text.append(' ');
                }
                text.append(w[i]);
            }
            root = m.createNode3D(parent, "ttPanel", (float) bw, (float) bh);
            if (root == null) {
                return "createNode3D returned null";
            }
            root.setPosition((float) x, (float) y, 0f);
            ByteBuffer buf = ByteBuffer.allocateDirect(bw * bh * 4);
            paintBox(buf, bw, bh, rgb);
            TextureDescription d = m.createTextureDescription(buf, 6, bw, bh, null, new Object());
            box = m.createImage3D(root, "ttPanelBox", d, 0, true, "ttPanel");
            StringBuffer b = new StringBuffer();
            if (box == null) {
                b.append("image null");
            } else {
                box.setSize((float) bw, (float) bh);
                b.append("image ok");
            }
            if (text.length() > 0) {
                Object font = HMITerminalImpl.fontLoader == null ? null : HMITerminalImpl.fontLoader.getFont(1, 28);
                if (font instanceof de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont) {
                    label = m.createText3D(root, "ttPanelText", text.toString(),
                        (de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont) font);
                    if (label != null) {
                        label.setColor(0xFFFFFFFF);
                        label.setPosition(12f, 12f, 0f);
                        b.append(", text ok");
                    } else {
                        b.append(", text null");
                    }
                } else {
                    b.append(", font ").append(String.valueOf(font));
                }
            }
            root.setVisible(true);
            m.fireRenderEvent();
            b.append(" at ").append(x).append(',').append(y).append(' ').append(bw).append('x').append(bh)
                .append(" parent ").append(describe(parent));
            return b.toString();
        }
        if (op.equals("at") && w.length >= 2) {
            if (w[1].equals("auto")) {
                posX = Integer.MIN_VALUE;
            } else if (w.length >= 3) {
                posX = num(w[1]);
                posY = num(w[2]);
            }
            if (mainRoot != null) {
                place(mainRoot, ZoomGate.small() ? "small" : "left");
            }
            m.fireRenderEvent();
            return "moved";
        }
        if (op.equals("turn") && w.length >= 8) {
            return turnCmd(m, w, id >= 0);
        }
        if (op.equals("off")) {
            clearTurn(m);
            lastTurn = null;
            m.fireRenderEvent();
            return "turn panel off";
        }
        if (op.equals("try") && w.length >= 7) {
            return tryMode(m, w);
        }
        return new StringBuffer("unknown: ").append(arg).toString();
    }

    private static String tryMode(EALManager m, String[] w) {
        clear(m);
        IWrappedNode3D parent = m.getPartialPopupsFrontNode();
        String mode = w[1];
        int x = num(w[2]);
        int y = num(w[3]);
        int bw = Math.max(8, Math.min(1024, num(w[4])));
        int bh = Math.max(8, Math.min(1024, num(w[5])));
        int rgb = hex(w[6]);
        StringBuffer text = new StringBuffer();
        for (int i = 7; i < w.length; i++) {
            if (i > 7) {
                text.append(' ');
            }
            text.append(w[i]);
        }
        StringBuffer b = new StringBuffer(mode);
        if (mode.equals("group")) {
            root = m.createNode3D(parent, "ttPanel", (float) bw, (float) bh);
            root.setPosition((float) x, (float) y, 0f);
            box = image(m, root, bw, bh, rgb);
            if (box != null) {
                box.setPosition(0f, 0f, 0f);
                box.setScale((float) bw / box.getUnscaledWidth(), (float) bh / box.getUnscaledHeight(), 1f);
            }
            label = text(m, root, text.toString(), 40);
            if (label != null) {
                label.setPosition(10f, 10f, 0f);
            }
        } else if (mode.equals("direct")) {
            box = image(m, parent, bw, bh, rgb);
            if (box != null) {
                box.setScale((float) bw / box.getUnscaledWidth(), (float) bh / box.getUnscaledHeight(), 1f);
                box.setPosition((float) x, (float) y, 0f);
            }
        } else if (mode.equals("text")) {
            label = text(m, parent, text.toString(), 40);
            if (label != null) {
                label.setColor(0xFF000000 | rgb);
                label.setPosition((float) x, (float) y, 0f);
            }
        } else if (mode.equals("lines")) {
            de.esolutions.hmi.widgets.audi.base.eal.IWrappedNode3DQuickDraw q =
                m.createQuickDrawNode3D(parent, "ttPanelLines", bw, bh, 2);
            if (q == null) {
                b.append(" quickdraw null");
            } else {
                q.setLineWidth(6f);
                q.setLineColor(0xFF000000 | rgb);
                q.add(0f, 0f);
                q.add((float) bw, 0f);
                q.add((float) bw, (float) bh);
                q.add(0f, (float) bh);
                q.add(0f, 0f);
                q.setPosition((float) x, (float) y, 0f);
                root = q;
            }
        }
        m.fireRenderEvent();
        b.append(" | root ").append(dump(root)).append(" | box ").append(dump(box)).append(" | text ").append(dump(label));
        b.append(" | parent ").append(dump(parent));
        IWrappedNode3D p = parent == null ? null : parent.getParent();
        for (int i = 0; i < 4 && p != null; i++) {
            b.append(" | up").append(i + 1).append(' ').append(dump(p));
            p = p.getParent();
        }
        return b.toString();
    }

    private static IWrappedNode3DImage image(EALManager m, IWrappedNode3D parent, int bw, int bh, int rgb) {
        ByteBuffer buf = ByteBuffer.allocateDirect(bw * bh * 4);
        byte r = (byte) (rgb >> 16);
        byte g = (byte) (rgb >> 8);
        byte bl = (byte) rgb;
        for (int i = 0; i < bw * bh; i++) {
            buf.put(r).put(g).put(bl).put((byte) -1);
        }
        buf.rewind();
        TextureDescription d = m.createTextureDescription(buf, 6, bw, bh, null, new Object());
        return m.createImage3D(parent, "ttPanelBox", d, 0, true, "ttPanel");
    }

    private static IWrappedNode3DText text(EALManager m, IWrappedNode3D parent, String t, int size) {
        if (t.length() == 0) {
            return null;
        }
        Object font = HMITerminalImpl.fontLoader == null ? null : HMITerminalImpl.fontLoader.getFont(1, size);
        if (!(font instanceof de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont)) {
            return null;
        }
        IWrappedNode3DText n = m.createText3D(parent, "ttPanelText", t,
            (de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont) font);
        if (n != null) {
            n.setColor(0xFFFFFFFF);
        }
        return n;
    }

    /** Wrapper and native transform of a node, for the log. */
    private static String dump(IWrappedNode3D n) {
        if (n == null) {
            return "-";
        }
        StringBuffer b = new StringBuffer();
        try {
            b.append("w[").append(n.getX()).append(',').append(n.getY()).append(' ').append(n.getWidth()).append('x')
                .append(n.getHeight()).append(" u ").append(n.getUnscaledWidth()).append('x').append(n.getUnscaledHeight())
                .append(" o ").append(n.getOriginX()).append(',').append(n.getOriginY()).append(']');
            de.esolutions.graphics.eal.api.INode3D e = n.getNode();
            b.append(" n[").append(e.getName()).append(" t ").append(e.getTranslationX()).append(',')
                .append(e.getTranslationY()).append(',').append(e.getTranslationZ()).append(" s ").append(e.getScaleX())
                .append(',').append(e.getScaleY()).append(',').append(e.getScaleZ()).append(']');
            if (n instanceof IWrappedNode3DImage) {
                de.esolutions.graphics.eal.api.INode3DImage im = ((IWrappedNode3DImage) n).getImageNode();
                b.append(" img ").append(im.getWidth()).append('x').append(im.getHeight());
            }
        } catch (Throwable t) {
            b.append(" dump error ").append(String.valueOf(t));
        }
        return b.toString();
    }

    // ---- turn panels (v23 layouts) ---------------------------------------------------
    // Large view (map tab): LEFT banner = maneuver (picture, distance, exit or street, lanes, bar),
    // RIGHT banner = destination (flag, distance, arrival time), like stock nav's two boxes.
    // Small view (tach in the centre): one FULL panel on the right with everything.
    private static final int LEFT_W = 270;
    private static final int LEFT_H = 118;
    private static final int LEFT_H_LANES = 168;
    private static final int RIGHT_W = 210;
    private static final int RIGHT_H = 78;
    private static final int FULL_W = 300;
    private static final int FULL_H = 380;
    private static final java.io.File LIVE_OFF = new java.io.File("/tmp/tt-panel-off");
    private static final java.io.File POS = new java.io.File("/tmp/tt-panel-pos");
    private static int posX = Integer.MIN_VALUE;
    private static int posY;
    private static IWrappedNode3D mainRoot;
    private static IWrappedNode3DImage mainImage;
    private static IWrappedNode3DText text1;
    private static IWrappedNode3DText text2;
    private static IWrappedNode3DText text3;
    private static IWrappedNode3D destRoot;
    private static IWrappedNode3DImage destImage;
    private static IWrappedNode3DText dest1;
    private static IWrappedNode3DText dest2;
    private static String artKey;
    private static String liveKey;
    private static String lastTurn;
    private static boolean liveShown;
    private static int shownMode = -1;

    /**
     * The iPhone feed: called by RgdBus after every update (any thread). Queues a redraw only
     * when what is shown changes; the bar is quantised to 16 steps to limit picture redraws.
     */
    public static void live(boolean on, short[] icon, int metres, int bar, String lanes, int destM, long etaUtc,
                            String exit, String street) {
        try {
            if (!on || icon == null || icon.length < 2 || LIVE_OFF.exists()) {
                if (liveShown) {
                    liveShown = false;
                    liveKey = null;
                    post("off", false);
                }
                return;
            }
            int[] ic = new int[icon.length];
            for (int i = 0; i < icon.length; i++) {
                ic[i] = icon[i];
            }
            int q = bar < 0 ? -1 : (bar / 16) * 16;
            // Destination distance in 0.1 mi steps, so it does not redraw every metre.
            int dq = destM < 0 ? -1 : (int) ((destM / 160) * 160);
            String k = rebuildArg(ic, metres, q, lanes, dq, etaUtc, exit, street);
            if (!k.equals(liveKey)) {
                liveKey = k;
                liveShown = true;
                post(k, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** ZoomGate: large/small view changed; rebuild in the other layout. */
    public static void viewChanged() {
        String t = lastTurn;
        if (t != null) {
            post(t, false);
        }
    }

    private static void post(String arg, boolean log) {
        EALManager m = manager();
        if (m == null) {
            return;
        }
        try {
            m.getTerminal().getFramework().getHMIService().getEventDispatcher().postEvent(
                new RunnableEvent(new PanelJob(m, arg, log ? ++serial : -1)));
        } catch (Throwable ignored) {
        }
    }

    private static String rebuildArg(int[] icon, int metres, int bar, String lanes, int destM, long eta,
                                     String exit, String street) {
        StringBuffer a = new StringBuffer("turn ");
        for (int i = 0; i < icon.length; i++) {
            if (i > 0) {
                a.append(',');
            }
            a.append(icon[i]);
        }
        return a.append(' ').append(metres).append(' ').append(bar).append(' ')
            .append(lanes == null || lanes.length() == 0 ? "-" : lanes).append(' ')
            .append(destM).append(' ').append(eta).append(' ')
            .append(exit == null || exit.length() == 0 ? "-" : exit).append(' ')
            .append(street == null ? "" : street).toString();
    }

    /** Playback/live "turn ICON METRES BAR LANES DEST ETA EXIT STREET...". */
    private static String turnCmd(EALManager m, String[] w, boolean verbose) {
        StringBuffer street = new StringBuffer();
        for (int i = 8; i < w.length; i++) {
            if (i > 8) {
                street.append(' ');
            }
            street.append(w[i]);
        }
        long eta = -1L;
        try {
            eta = Long.parseLong(w[6]);
        } catch (Throwable ignored) {
            eta = -1L;
        }
        if (eta > 0L && eta < 100000000L) {
            // Playback: seconds from now.
            eta = System.currentTimeMillis() / 1000L + eta;
        }
        String exit = w[7].equals("-") ? "" : w[7];
        return turn(m, ints(w[1]), num(w[2]), num(w[3]), w[4], num(w[5]), eta, exit, street.toString(), verbose);
    }

    private static String turn(EALManager m, int[] icon, int metres, int bar, String lanes, int destM, long eta,
                               String exit, String street, boolean verbose) {
        if (icon.length < 2) {
            return "turn needs MAIN,DIR";
        }
        lastTurn = rebuildArg(icon, metres, bar, lanes, destM, eta, exit, street);
        boolean small = ZoomGate.small();
        int mode = small ? 1 : 0;
        if (mode != shownMode) {
            clearTurn(m);
            shownMode = mode;
        }
        int main = icon[0];
        int dir = icon[1] & 0xff;
        int[] arms = null;
        if (icon.length > 3 && icon[2] == -1) {
            arms = new int[icon.length - 3];
            for (int i = 3; i < icon.length; i++) {
                arms[i - 3] = icon[i] & 0xff;
            }
        }
        int[][] laneAngles = null;
        int[] laneStatus = null;
        if (lanes != null && !lanes.equals("-") && lanes.length() > 0) {
            String[] ls = split(lanes, '/');
            laneAngles = new int[ls.length][];
            laneStatus = new int[ls.length];
            for (int i = 0; i < ls.length; i++) {
                int c = ls[i].indexOf(':');
                laneAngles[i] = ints(c < 0 ? ls[i] : ls[i].substring(0, c));
                laneStatus[i] = c < 0 ? 0 : num(ls[i].substring(c + 1));
            }
        }
        boolean hasLanes = laneAngles != null;
        int pw = small ? FULL_W : LEFT_W;
        int ph = small ? FULL_H : (hasLanes ? LEFT_H_LANES : LEFT_H);
        StringBuffer b = new StringBuffer(small ? "small " : "large ");
        IWrappedNode3D front = m.getPartialPopupsFrontNode();
        if (mainRoot == null) {
            mainRoot = m.createNode3D(front, "ttTurnPanel", (float) pw, (float) ph);
            if (mainRoot == null) {
                return "createNode3D null";
            }
        }
        place(mainRoot, small ? "small" : "left");
        String k = new StringBuffer().append(mode).append(',').append(main).append(',').append(dir).append(',')
            .append(bar).append(',').append(lanes == null ? "-" : lanes).append(keyOf(arms)).toString();
        if (!k.equals(artKey) || mainImage == null) {
            long t0 = System.currentTimeMillis();
            PanelArt art = new PanelArt(pw, ph);
            if (small) {
                art.full(main, dir, arms, bar, laneAngles, laneStatus);
            } else {
                art.banner(main, dir, arms, bar, laneAngles, laneStatus);
            }
            IWrappedNode3DImage img = image(m, mainRoot, art);
            if (img == null) {
                return "image null";
            }
            if (mainImage != null) {
                try {
                    mainRoot.remove(mainImage);
                    m.destroy(mainImage);
                } catch (Throwable ignored) {
                }
            }
            mainImage = img;
            artKey = k;
            raise(mainRoot, text1);
            raise(mainRoot, text2);
            raise(mainRoot, text3);
            if (verbose) {
                b.append("art ").append(System.currentTimeMillis() - t0).append(" ms ");
            }
        }
        String sub = exit.length() > 0 ? new StringBuffer("Exit ").append(exit).toString() : street;
        String destLine = destText(m, destM, eta);
        if (small) {
            if (exit.length() > 0 && street.length() > 0) {
                sub = new StringBuffer("Exit ").append(exit).append("  ").append(street).toString();
            }
            text1 = setText(m, mainRoot, text1, "ttTurnDist", distance(metres), 2, 40, 40f, (float) (ph - 158), pw - 56);
            text2 = setText(m, mainRoot, text2, "ttTurnSub", sub, 1, 24, 40f, (float) (ph - 106), pw - 56);
            text3 = setText(m, mainRoot, text3, "ttTurnDest", destLine, 1, 22, 60f, (float) (ph - 44), pw - 76);
        } else {
            int s = hasLanes ? ph - 62 : ph - 14;
            int tx = s + 14;
            int tw = pw - tx - 20;
            text1 = setText(m, mainRoot, text1, "ttTurnDist", distance(metres), 2, 34, (float) tx, 10f, tw);
            text2 = setText(m, mainRoot, text2, "ttTurnSub", sub, 1, 21, (float) tx, 56f, tw);
            // Right banner: destination.
            if (destLine.length() > 0) {
                if (destRoot == null) {
                    destRoot = m.createNode3D(front, "ttDestPanel", (float) RIGHT_W, (float) RIGHT_H);
                    PanelArt da = new PanelArt(RIGHT_W, RIGHT_H);
                    da.destination();
                    destImage = destRoot == null ? null : image(m, destRoot, da);
                }
                if (destRoot != null) {
                    place(destRoot, "right");
                    dest1 = setText(m, destRoot, dest1, "ttDestDist", distance(destM), 2, 28, 76f, 6f, RIGHT_W - 90);
                    dest2 = setText(m, destRoot, dest2, "ttDestEta", clock(m, eta), 1, 22, 76f, 42f, RIGHT_W - 90);
                    destRoot.setVisible(true);
                }
            } else if (destRoot != null) {
                destRoot.setVisible(false);
            }
        }
        mainRoot.setVisible(true);
        m.fireRenderEvent();
        if (verbose) {
            b.append("at ").append((int) mainRoot.getX()).append(',').append((int) mainRoot.getY())
                .append(" t1 ").append(dump(text1));
        }
        return b.toString();
    }

    private static IWrappedNode3DImage image(EALManager m, IWrappedNode3D parent, PanelArt art) {
        byte[] rgba = art.rgba();
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length);
        buf.put(rgba);
        buf.rewind();
        TextureDescription d = m.createTextureDescription(buf, 6, art.width(), art.height(), null, new Object());
        IWrappedNode3DImage img = m.createImage3D(parent, "ttArt", d, 0, true, "ttPanel");
        if (img != null) {
            img.setScale((float) art.width() / img.getUnscaledWidth(), (float) art.height() / img.getUnscaledHeight(), 1f);
            img.setPosition(0f, 0f, 0f);
        }
        return img;
    }

    /** Re-adds a text node so it is drawn after (above) a newly created picture. */
    private static void raise(IWrappedNode3D root, IWrappedNode3D n) {
        if (root != null && n != null) {
            try {
                root.remove(n);
                root.add(n);
            } catch (Throwable ignored) {
            }
        }
    }

    /** "3.0 mi  3:43 PM", or "" when neither is known. */
    private static String destText(EALManager m, int destM, long eta) {
        StringBuffer b = new StringBuffer();
        if (destM >= 0) {
            b.append(distance(destM));
        }
        String c = clock(m, eta);
        if (c.length() > 0) {
            if (b.length() > 0) {
                b.append("   ");
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Local clock time (12 h) of a UTC epoch-seconds value, e.g. "3:43 PM". */
    private static String clock(EALManager m, long etaUtc) {
        if (etaUtc <= 0L) {
            return "";
        }
        long ms = etaUtc * 1000L;
        try {
            ms = m.getTerminal().getFramework().convertUTCTimeToLocalTime(ms);
        } catch (Throwable ignored) {
            // keep UTC
        }
        int mins = (int) ((ms / 60000L) % 1440L);
        if (mins < 0) {
            mins += 1440;
        }
        int hh = mins / 60;
        int mm = mins % 60;
        StringBuffer b = new StringBuffer();
        b.append(hh % 12 == 0 ? 12 : hh % 12).append(':');
        if (mm < 10) {
            b.append('0');
        }
        return b.append(mm).append(hh < 12 ? " AM" : " PM").toString();
    }

    private static String keyOf(int[] a) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; a != null && i < a.length; i++) {
            b.append(',').append(a[i]);
        }
        return b.toString();
    }

    private static IWrappedNode3DText setText(EALManager m, IWrappedNode3D root, IWrappedNode3DText n, String name,
                                              String t, int style, int size, float x, float y, int maxWidth) {
        Object font = HMITerminalImpl.fontLoader == null ? null : HMITerminalImpl.fontLoader.getFont(style, size);
        if (!(font instanceof de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont)) {
            return n;
        }
        de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont f = (de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont) font;
        String text = fit(m, t == null || t.length() == 0 ? " " : t, f, maxWidth);
        if (n == null) {
            n = m.createText3D(root, name, text, f);
            if (n == null) {
                return null;
            }
            n.setColor(0xFFFFFFFF);
        } else if (!text.equals(n.getText())) {
            n.setText(text, f);
        }
        n.setPosition(x, y, 0f);
        return n;
    }

    /** Shortens t with "..." until it fits maxWidth pixels in font f. */
    private static String fit(EALManager m, String t, de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont f,
                              int maxWidth) {
        try {
            if (maxWidth <= 0 || m.getTextWidth(t, f) <= maxWidth) {
                return t;
            }
            int n = t.length();
            while (n > 1) {
                n--;
                String c = new StringBuffer(t.substring(0, n)).append("...").toString();
                if (m.getTextWidth(c, f) <= maxWidth) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    /**
     * Position and scale of one panel: "panel at" overrides the main panel; else /tmp/tt-panel-pos
     * lines "left X Y SCALE" / "right X Y SCALE" / "small X Y SCALE"; else the defaults
     * (left 30,64 top-left of the map; right 1180,70 above the speedometer; small 991,152 banner spot).
     */
    private static void place(IWrappedNode3D n, String which) {
        int x;
        int y;
        if (which.equals("small")) {
            x = 991;
            y = 152;
        } else if (which.equals("right")) {
            x = 1180;
            y = 70;
        } else {
            x = 30;
            y = 64;
        }
        float scale = 1f;
        String[] v = posLine(which);
        if (v != null) {
            x = num(v[1]);
            y = num(v[2]);
            if (v.length > 3) {
                try {
                    scale = Float.parseFloat(v[3]);
                } catch (Throwable ignored) {
                    scale = 1f;
                }
            }
        }
        if (posX != Integer.MIN_VALUE && !which.equals("right")) {
            x = posX;
            y = posY;
        }
        if (scale < 0.3f || scale > 2f) {
            scale = 1f;
        }
        n.setScale(scale, scale, 1f);
        n.setPosition((float) x, (float) y, 0f);
    }

    private static String[] posLine(String which) {
        if (!POS.exists()) {
            return null;
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(POS.getPath());
            byte[] buf = new byte[512];
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            if (n <= 0) {
                return null;
            }
            String[] lines = split(new String(buf, 0, n), '\n');
            for (int i = 0; i < lines.length; i++) {
                String[] w = words(lines[i].trim());
                if (w.length >= 3 && w[0].equals(which)) {
                    return w;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** US units: feet below 1000 ft (50 ft steps), then miles. */
    static String distance(int metres) {
        if (metres < 0) {
            return "";
        }
        int feet = (int) (metres * 3.28084 + 0.5);
        StringBuffer b = new StringBuffer();
        if (feet < 1000) {
            b.append(((feet + 25) / 50) * 50).append(" ft");
        } else {
            int tenths = (int) (metres / 160.9344 + 0.5);
            if (tenths >= 100) {
                b.append(tenths / 10).append(" mi");
            } else {
                b.append(tenths / 10).append('.').append(tenths % 10).append(" mi");
            }
        }
        return b.toString();
    }

    private static void clearTurn(EALManager m) {
        IWrappedNode3D[] roots = { mainRoot, destRoot };
        for (int i = 0; i < roots.length; i++) {
            if (roots[i] != null) {
                try {
                    IWrappedNode3D p = roots[i].getParent();
                    if (p != null) {
                        p.remove(roots[i]);
                    }
                    m.destroy(roots[i]);
                } catch (Throwable ignored) {
                }
            }
        }
        mainRoot = null;
        mainImage = null;
        text1 = null;
        text2 = null;
        text3 = null;
        destRoot = null;
        destImage = null;
        dest1 = null;
        dest2 = null;
        artKey = null;
    }

    private static String[] split(String s, char sep) {
        java.util.Vector v = new java.util.Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                v.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private static int[] ints(String s) {
        java.util.Vector v = new java.util.Vector();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                int j = i + 1;
                while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    j++;
                }
                try {
                    v.addElement(new Integer(Integer.parseInt(s.substring(i, j))));
                } catch (Throwable ignored) {
                }
                i = j;
            } else {
                i++;
            }
        }
        int[] out = new int[v.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = ((Integer) v.elementAt(k)).intValue();
        }
        return out;
    }

    private static void clear(EALManager m) {
        if (label != null) {
            try {
                m.destroy(label);
            } catch (Throwable ignored) {
            }
            label = null;
        }
        if (box != null) {
            try {
                m.destroy(box);
            } catch (Throwable ignored) {
            }
            box = null;
        }
        if (root != null) {
            try {
                IWrappedNode3D p = root.getParent();
                if (p != null) {
                    p.remove(root);
                }
                m.destroy(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
    }

    /** Filled box with a 4 px white border and rounded corners; RGBA bytes. */
    private static void paintBox(ByteBuffer buf, int w, int h, int rgb) {
        byte r = (byte) (rgb >> 16);
        byte g = (byte) (rgb >> 8);
        byte bl = (byte) rgb;
        int rad = Math.min(16, Math.min(w, h) / 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x < rad ? rad - x : (x >= w - rad ? x - (w - rad - 1) : 0);
                int dy = y < rad ? rad - y : (y >= h - rad ? y - (h - rad - 1) : 0);
                boolean inside = dx * dx + dy * dy <= rad * rad;
                boolean border = x < 4 || y < 4 || x >= w - 4 || y >= h - 4
                    || (dx > 0 && dy > 0 && dx * dx + dy * dy > (rad - 4) * (rad - 4));
                if (!inside) {
                    buf.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
                } else if (border) {
                    buf.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1);
                } else {
                    buf.put(r).put(g).put(bl).put((byte) 0xE0);
                }
            }
        }
        buf.rewind();
    }

    private static String describe(Object n) {
        if (n == null) {
            return "null";
        }
        if (n instanceof IWrappedNode3D) {
            IWrappedNode3D d = (IWrappedNode3D) n;
            StringBuffer b = new StringBuffer();
            try {
                b.append(d.getNode().getName()).append(" pos ").append((int) d.getX()).append(',').append((int) d.getY())
                    .append(" size ").append((int) d.getWidth()).append('x').append((int) d.getHeight())
                    .append(d.isVisible() ? " visible" : " hidden");
            } catch (Throwable t) {
                b.append(n.getClass().getName());
            }
            return b.toString();
        }
        return n.getClass().getName();
    }

    private static String[] words(String s) {
        java.util.Vector v = new java.util.Vector();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
            int j = i;
            while (j < n && s.charAt(j) != ' ') {
                j++;
            }
            if (j > i) {
                v.addElement(s.substring(i, j));
            }
            i = j;
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hex(String s) {
        try {
            return Integer.parseInt(s, 16);
        } catch (Throwable t) {
            return 0x202020;
        }
    }
}
