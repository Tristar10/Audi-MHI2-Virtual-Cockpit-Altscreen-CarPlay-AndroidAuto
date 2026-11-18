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
    static String onHmiThread(EALManager m, String arg) {
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
            TextureDescription d = m.createTextureDescription(buf, bw, bh, 6, null, "ttPanelTex");
            box = m.createImage3D(root, "ttPanelBox", d, 0, true, "ttPanel");
            StringBuffer b = new StringBuffer();
            if (box == null) {
                b.append("image null");
            } else {
                box.setSize((float) bw, (float) bh);
                b.append("image ok");
            }
            if (text.length() > 0) {
                Object font = HMITerminalImpl.fontLoader == null ? null : HMITerminalImpl.fontLoader.getFont(28, 0);
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
        TextureDescription d = m.createTextureDescription(buf, bw, bh, 6, null, "ttPanelTex");
        return m.createImage3D(parent, "ttPanelBox", d, 0, true, "ttPanel");
    }

    private static IWrappedNode3DText text(EALManager m, IWrappedNode3D parent, String t, int size) {
        if (t.length() == 0) {
            return null;
        }
        Object font = HMITerminalImpl.fontLoader == null ? null : HMITerminalImpl.fontLoader.getFont(size, 0);
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
