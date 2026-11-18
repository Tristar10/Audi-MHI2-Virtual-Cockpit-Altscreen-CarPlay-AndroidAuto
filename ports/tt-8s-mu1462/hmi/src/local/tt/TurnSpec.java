package local.tt;

/**
 * One parsed "turn" command for the cockpit panels (v25), and the panel picture for it.
 *
 * Format (words): turn ICON METRES BAR LANES DEST ETA EXIT STREET...
 *   ICON   MAIN,DIR[,-1,ARM...] (RGI codes); METRES to the maneuver; BAR 0-255 or -1;
 *   LANES  "-" or lanes separated by '/', each ANGLE[,ANGLE...]:STATUS;
 *   DEST   metres to the destination or -1; ETA UTC epoch seconds, or seconds from now when
 *          below 1e8 (playback), or -1; EXIT exit number or "-"; STREET free text.
 *
 * The picture can be drawn on any thread (TtPanel pre-renders it on the iPhone-feed thread so
 * the HMI thread only uploads the texture). Plain class, Java 1.4 library.
 */
final class TurnSpec {
    /** Panel modes: large-view left banner, small-view full panel, large-view full panel (flap). */
    static final int BANNER = 0;
    static final int FULL = 1;
    static final int FLAP = 2;
    /** v27: one panel inside the stock banner window, every view. */
    static final int KDK = 3;
    static final int KDK_W = 210;
    static final int KDK_H = 150;
    static final int LEFT_W = 270;
    static final int LEFT_H = 118;
    static final int LEFT_H_LANES = 168;
    static final int FULL_W = 300;
    static final int FULL_H = 380;

    int[] icon;
    int main;
    int dir;
    int[] arms;
    int metres;
    int bar;
    String lanes;
    int[][] laneAngles;
    int[] laneStatus;
    int destM;
    long eta;
    String exit;
    String street;

    static TurnSpec parse(String[] w) {
        if (w.length < 8) {
            return null;
        }
        TurnSpec t = new TurnSpec();
        t.icon = ints(w[1]);
        if (t.icon.length < 2) {
            return null;
        }
        t.main = t.icon[0];
        t.dir = t.icon[1] & 0xff;
        if (t.icon.length > 3 && t.icon[2] == -1) {
            t.arms = new int[t.icon.length - 3];
            for (int i = 3; i < t.icon.length; i++) {
                t.arms[i - 3] = t.icon[i] & 0xff;
            }
        }
        t.metres = num(w[2]);
        t.bar = num(w[3]);
        t.lanes = w[4];
        if (!t.lanes.equals("-") && t.lanes.length() > 0) {
            String[] ls = split(t.lanes, '/');
            t.laneAngles = new int[ls.length][];
            t.laneStatus = new int[ls.length];
            for (int i = 0; i < ls.length; i++) {
                int c = ls[i].indexOf(':');
                t.laneAngles[i] = ints(c < 0 ? ls[i] : ls[i].substring(0, c));
                t.laneStatus[i] = c < 0 ? 0 : num(ls[i].substring(c + 1));
            }
        }
        t.destM = num(w[5]);
        long eta = -1L;
        try {
            eta = Long.parseLong(w[6]);
        } catch (Throwable ignored) {
            eta = -1L;
        }
        if (eta > 0L && eta < 100000000L) {
            eta = System.currentTimeMillis() / 1000L + eta;
        }
        t.eta = eta;
        t.exit = w[7].equals("-") ? "" : w[7];
        StringBuffer s = new StringBuffer();
        for (int i = 8; i < w.length; i++) {
            if (i > 8) {
                s.append(' ');
            }
            s.append(w[i]);
        }
        t.street = s.toString();
        return t;
    }

    boolean hasLanes() {
        return laneAngles != null;
    }

    int width(int mode) {
        if (mode == KDK) {
            return KDK_W;
        }
        return mode == BANNER ? LEFT_W : FULL_W;
    }

    int height(int mode) {
        if (mode == KDK) {
            return KDK_H;
        }
        return mode == BANNER ? (hasLanes() ? LEFT_H_LANES : LEFT_H) : FULL_H;
    }

    /** What the picture depends on (texts are separate HMI nodes). */
    String artKey(int mode) {
        StringBuffer b = new StringBuffer();
        b.append(mode == FLAP ? FULL : mode).append(',').append(main).append(',').append(dir).append(',')
            .append(bar).append(',').append(lanes);
        for (int i = 0; arms != null && i < arms.length; i++) {
            b.append(',').append(arms[i]);
        }
        return b.toString();
    }

    PanelArt render(int mode) {
        PanelArt art = new PanelArt(width(mode), height(mode));
        if (mode == KDK) {
            art.kdk(main, dir, arms, bar, laneAngles, laneStatus);
        } else if (mode == BANNER) {
            art.banner(main, dir, arms, bar, laneAngles, laneStatus);
        } else {
            art.full(main, dir, arms, bar, laneAngles, laneStatus);
        }
        return art;
    }

    static String[] split(String s, char sep) {
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

    static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    static int[] ints(String s) {
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
}
