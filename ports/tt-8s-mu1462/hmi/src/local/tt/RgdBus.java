package local.tt;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Hashtable;

import de.audi.tghu.navi.app.cluster.TtRgi;

/**
 * Java end of the route-guidance bus for the Audi TT MU1462 (logging stage).
 *
 * The route-guidance hook inside mm-ipod connects to 127.0.0.1:19810 and only
 * asks the iPhone for turn-by-turn data while that connection is up. This
 * accepts it, reads the frames (16-byte big-endian header: magic 'CPHB', seq,
 * u16 type, u8 flags, u8 reserved, u32 length; then the payload) and records
 * every changed route-guidance update (type 0x0020, "key:type:value" lines):
 *   /tmp/tt-rgd.txt  the latest update, as received
 *   /tmp/tt-rgd.log  one line per change, capped at 64 KB
 * Nothing is sent back to the hook. Each replug starts a new mm-ipod, whose
 * connection replaces the previous one.
 *
 * v10: the accumulated route state also goes to the stock cockpit channel
 * through TtRgi (route on/off, distance to the turn, street names, distance
 * and arrival time). v12: it goes to the HMI's own cockpit turn display
 * through ClusterService (TtRgi), with the turn icon worked out by
 * ManeuverMapper. touch /tmp/tt-rgi-off stops that and clears it.
 * v13 modes, read live: /tmp/tt-rgi-noactive never reports "route active"
 * (which is what swaps the map tab for the arrow screen); /tmp/tt-rgi-near
 * reports it only within 400 m of the turn (or the metres written in the
 * file). Junction icons are sent in the stock shape {main, dir, -1, 0} unless
 * /tmp/tt-rgi-nopad exists; follow-road is sent as {11, 255} like stock.
 * Guidance ends only after the iPhone has reported no route (or arrival) for
 * OFF_DELAY ms, because iOS sends a short route_state 0 about every 30 s.
 * v15: the turn is sent in stock navigation's pattern (see TtRgi): follow,
 * prepare, approach with bargraph, at the turn; junction icons carry the
 * side-street arms stock sends. /tmp/tt-rgi-near is gone (the phases replace
 * it); /tmp/tt-rgi-nopad sends bare {main,dir} icons for A/B tests. The
 * 2-second tick re-sends whatever stock navigation overwrote. The log keeps
 * 256 KB plus one older file (/tmp/tt-rgd.log.1).
 *
 * Java 1.4 class library: no StringBuilder, no autoboxing, no generics.
 */
public final class RgdBus implements Runnable {
    private static final int PORT = 19810;
    private static final int MAGIC = 0x43504842;
    private static final int EVT_RGD_UPDATE = 0x0020;
    private static final int MAX_PAYLOAD = 128 * 1024;
    private static final File LOG = new File("/tmp/tt-rgd.log");
    private static final File LOG_OLD = new File("/tmp/tt-rgd.log.1");
    private static final long LOG_LIMIT = 262144L;
    private static final File LAST = new File("/tmp/tt-rgd.txt");
    private static final Object LOCK = new Object();
    private static final File RGI_OFF = new File("/tmp/tt-rgi-off");
    private static final File RGI_NOACTIVE = new File("/tmp/tt-rgi-noactive");
    private static final File RGI_NOPAD = new File("/tmp/tt-rgi-nopad");
    private static final long OFF_DELAY = 6000L;
    private static boolean started;
    private static Socket current;
    /** Accumulated key -> value of the current hook connection (deltas arrive). */
    private static final Hashtable STATE = new Hashtable();
    private static long offSince = -1L;
    private static boolean wantOn;
    private static boolean lastOn;
    private static String lastSummary = "";

    private final Socket socket;
    private final boolean tick;

    private RgdBus(Socket socket, boolean tick) {
        this.socket = socket;
        this.tick = tick;
    }

    /** Patched CombiBAPListener.setCombiService(service) calls this first. */
    public static void combi(Object listener, Object service) {
        try {
            log(TtRgi.attach(listener, service));
        } catch (Throwable t) {
            log(new StringBuffer("combi attach failed ").append(String.valueOf(t)).toString());
        }
    }

    /** Starts the listener once per HMI lifetime; later calls do nothing. */
    public static void start() {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
        }
        try {
            Thread t = new Thread(new RgdBus(null, false), "tt-rgd-bus");
            t.setDaemon(true);
            t.start();
            Thread k = new Thread(new RgdBus(null, true), "tt-rgi-tick");
            k.setDaemon(true);
            k.start();
            RgiPlayer.start();
        } catch (Throwable t) {
            log(new StringBuffer("start failed ").append(String.valueOf(t)).toString());
        }
    }

    public void run() {
        if (tick) {
            while (true) {
                sleep(2000);
                feed(false);
            }
        } else if (socket == null) {
            acceptLoop();
        } else {
            readLoop(socket);
        }
    }

    private static void acceptLoop() {
        ServerSocket server = null;
        int delay = 500;
        while (server == null) {
            try {
                server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
                log("listening on 127.0.0.1:19810");
            } catch (Throwable t) {
                log(new StringBuffer("bind failed ").append(String.valueOf(t)).toString());
                sleep(delay);
                if (delay < 10000) {
                    delay = delay * 2;
                }
            }
        }
        while (true) {
            try {
                Socket s = server.accept();
                Socket old;
                synchronized (LOCK) {
                    old = current;
                    current = s;
                }
                if (old != null) {
                    close(old);
                }
                synchronized (STATE) {
                    STATE.clear();
                }
                log("hook connected");
                Thread t = new Thread(new RgdBus(s, false), "tt-rgd-reader");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                log(new StringBuffer("accept failed ").append(String.valueOf(t)).toString());
                sleep(1000);
            }
        }
    }

    private static void readLoop(Socket s) {
        int frames = 0;
        int updates = 0;
        byte[] last = null;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            while (true) {
                int magic = in.readInt();
                if (magic != MAGIC) {
                    log(new StringBuffer("bad magic 0x").append(Integer.toHexString(magic)).toString());
                    break;
                }
                in.readInt();
                int type = in.readUnsignedShort();
                in.readUnsignedByte();
                in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > MAX_PAYLOAD) {
                    log(new StringBuffer("bad length ").append(len).toString());
                    break;
                }
                byte[] payload = new byte[len];
                in.readFully(payload);
                frames++;
                if (type == EVT_RGD_UPDATE) {
                    if (last == null || !same(last, payload)) {
                        last = payload;
                        updates++;
                        writeLast(payload);
                        logUpdate(updates, payload);
                        merge(payload);
                        feed(true);
                    }
                } else if (frames <= 30) {
                    log(new StringBuffer("frame type 0x").append(Integer.toHexString(type))
                        .append(" len ").append(len).toString());
                }
            }
        } catch (EOFException e) {
            log(new StringBuffer("hook disconnected after ").append(frames).append(" frames").toString());
        } catch (Throwable t) {
            log(new StringBuffer("reader stopped after ").append(frames).append(" frames: ")
                .append(String.valueOf(t)).toString());
        } finally {
            close(s);
            boolean mine;
            synchronized (LOCK) {
                mine = current == s;
            }
            if (mine) {
                synchronized (STATE) {
                    STATE.clear();
                }
                feed(true);
            }
        }
    }

    /** Applies one update: "@routeguidance" then "key:type:value" lines. */
    private static void merge(byte[] payload) {
        String text;
        try {
            text = new String(payload, "UTF-8");
        } catch (Throwable t) {
            text = new String(payload);
        }
        synchronized (STATE) {
            int start = 0;
            int n = text.length();
            while (start < n) {
                int end = text.indexOf('\n', start);
                if (end < 0) {
                    end = n;
                }
                String line = text.substring(start, end);
                start = end + 1;
                int a = line.indexOf(':');
                if (a <= 0) {
                    continue;
                }
                int b = line.indexOf(':', a + 1);
                if (b < 0) {
                    continue;
                }
                STATE.put(line.substring(0, a), line.substring(b + 1));
            }
        }
    }

    private static String str(String key) {
        Object v = STATE.get(key);
        return v == null ? "" : (String) v;
    }

    private static long num(String key, long dflt) {
        Object v = STATE.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Long.parseLong(((String) v).trim());
        } catch (Throwable t) {
            return dflt;
        }
    }

    /**
     * Works out the wanted cockpit state from STATE and hands it to TtRgi.
     * fromBus is false for the 2-second tick, which only finishes a pending off.
     */
    private static void feed(boolean fromBus) {
        boolean on;
        int turn;
        int dest;
        long eta;
        String road;
        String next;
        int main = -1;
        int dir = 0;
        boolean pad = false;
        boolean declare = true;
        short[] icon = null;
        int mState = 0;
        int bar = -1;
        String lanes = "-";
        long now = System.currentTimeMillis();
        synchronized (STATE) {
            if (!fromBus && offSince < 0L && !wantOn && !lastOn && !RgiPlayer.playing()) {
                return;
            }
            long state = num("route_state", 0);
            boolean route = !STATE.isEmpty() && num("source_supports_rg", 1) != 0
                && state != 0 && state != 2;
            if (route) {
                offSince = -1L;
                wantOn = true;
            } else if (wantOn) {
                if (offSince < 0L) {
                    offSince = now;
                }
                if (now - offSince >= OFF_DELAY || STATE.isEmpty()) {
                    wantOn = false;
                    offSince = -1L;
                }
            }
            on = wantOn && !RGI_OFF.exists();
            lastOn = on;
            turn = (int) num("dist_maneuver_m", -1);
            dest = (int) num("dist_dest_m", -1);
            eta = num("eta_seconds", -1);
            road = str("current_road");
            next = "";
            String order = str("maneuver_list");
            int comma = order.indexOf(',');
            String first = (comma < 0 ? order : order.substring(0, comma)).trim();
            if (first.length() > 0) {
                StringBuffer p = new StringBuffer("m").append(first).append('_');
                int plen = p.length();
                next = str(p.append("after_road").toString());
                if (next.length() == 0) {
                    p.setLength(plen);
                    next = str(p.append("name").toString());
                }
                p.setLength(plen);
                int type = (int) num(p.append("type").toString(), -1);
                if (type >= 0) {
                    p.setLength(plen);
                    String exitKey = p.append("exit_angle").toString();
                    p.setLength(plen);
                    String turnKey = p.append("turn_angle").toString();
                    boolean anglePresent = STATE.get(exitKey) != null || STATE.get(turnKey) != null;
                    int angle = (int) num(STATE.get(exitKey) != null ? exitKey : turnKey, 1000);
                    p.setLength(plen);
                    int junction = (int) num(p.append("junction_type").toString(), -1);
                    p.setLength(plen);
                    int side = (int) num(p.append("driving_side").toString(), -1);
                    try {
                        int[] m = ManeuverMapper.map(type, angle, junction, side, anglePresent);
                        main = m[0];
                        dir = m[1];
                        int[] o = stockCode(type, angle);
                        if (o != null) {
                            main = o[0];
                            dir = o[1];
                        }
                    } catch (Throwable t) {
                        main = -1;
                    }
                }
            }
            if (main == 11 && dir == 0) {
                dir = 255;
            }
            if (first.length() > 0) {
                lanes = laneSpec(first);
            }
            pad = !RGI_NOPAD.exists();
            declare = !RGI_NOACTIVE.exists();
            if (main >= 0) {
                int[] ph = phase(main, turn);
                mState = ph[0];
                bar = ph[1];
                if (mState == 1) {
                    icon = new short[] { 11, 255 };
                } else if (mState == 2) {
                    icon = main >= 13 && main <= 20
                        ? new short[] { 12, (short) (dir & 0xff) } : new short[] { 11, 255 };
                } else {
                    icon = icon(main, dir, pad);
                }
            }
            StringBuffer sum = new StringBuffer("rgi on=");
            sum.append(on).append(" declare=").append(declare).append(" state=").append(state).append(" icon=").append(main).append(',').append(dir)
                .append(" phase=").append(mState).append(" bar=").append(bar)
                .append(" turn=").append(turn)
                .append(" dest=").append(dest).append(" eta=").append(eta)
                .append(" road=").append(road).append(" next=").append(next);
            String summary = sum.toString();
            if (!summary.equals(lastSummary)) {
                lastSummary = summary;
                log(summary);
            }
        }
        if (RgiPlayer.playing()) {
            return;
        }
        try {
            String result = TtRgi.update(on, declare, icon, mState, turn, bar, dest, eta, road, next);
            // Lanes only while approaching the turn, like stock.
            TtPanel.live(on, icon, turn, bar, mState >= 3 ? lanes : "-", next);
            if (result != null) {
                log(result);
            }
        } catch (Throwable t) {
            log(new StringBuffer("cockpit update failed ").append(String.valueOf(t)).toString());
        }
    }

    /**
     * Where the TT's stock navigation uses a different code than luka's
     * mapper (run3): motorway exits are EXIT_RIGHT/LEFT {15,192}/{16,64}
     * rather than a slight turn, and keep left/right is FORK_2 {19,64}/{19,192}.
     * iAP2 types: 8 off ramp (side from the angle, right when unknown),
     * 13 keep left, 14 keep right, 22/23 motorway off ramp left/right.
     * Returns null to keep the mapper's code.
     */
    static int[] stockCode(int type, int angle) {
        switch (type) {
            case 8:
                return angle < 0 && angle > -180 ? new int[] { 16, 64 } : new int[] { 15, 192 };
            case 13:
                return new int[] { 19, 64 };
            case 14:
                return new int[] { 19, 192 };
            case 22:
                return new int[] { 16, 64 };
            case 23:
                return new int[] { 15, 192 };
            default:
                return null;
        }
    }

    /**
     * {BAP maneuver state, bargraph} for a turn of icon code main at turn
     * metres, using the distances stock navigation used on the TT (run3):
     * city turns prepare from about 1250 m, approach (bargraph, full icon)
     * from 430 m, state 4 from 100 m; motorway exits and forks 3200 / 1750 /
     * 150 m. The bargraph runs 255 -> 0 over the approach. Codes that are not
     * turns (follow, off-road, calculating) stay in state 1; an unknown
     * distance shows the full icon without a bargraph.
     */
    static int[] phase(int main, int turn) {
        boolean turnLike = main >= 12 && main != 28 && main != 29 && main != 30 && main != 31;
        boolean arrival = main >= 2 && main <= 5 || main == 38;
        if (main == 11 || (!turnLike && !arrival)) {
            return new int[] { 1, -1 };
        }
        if (turn < 0) {
            return new int[] { 3, -1 };
        }
        boolean motorway = main >= 14 && main <= 20;
        int prepare = motorway ? 3200 : 1250;
        int approach = motorway ? 1750 : 430;
        int last = motorway ? 150 : 100;
        if (turn > prepare) {
            return new int[] { 1, -1 };
        }
        if (turn > approach) {
            return new int[] { 2, -1 };
        }
        int bar = (int) ((turn * 255L) / approach);
        if (bar > 255) {
            bar = 255;
        }
        return new int[] { turn <= last ? 4 : 3, bar };
    }

    /**
     * The cockpit icon stream in stock's shape: {main, dir 0..255, side
     * streets as signed bytes}. Stock sends a plain turn as {13, dir, -1,
     * other arms of the crossing} (left: -1,0,-64; right: -1,64,0), a
     * motorway exit as {15|16, dir, -1}, forks and everything else as
     * {main, dir}. With pad false only {main, dir} is sent.
     */
    static short[] icon(int main, int dir, boolean pad) {
        short d = (short) (dir & 0xff);
        if (!pad) {
            return new short[] { (short) main, d };
        }
        if (main == 13 || main == 14) {
            int a = dir & 0xff;
            if (a > 32 && a < 128) {
                return new short[] { (short) main, d, -1, 0, -64 };
            }
            if (a > 128 && a < 224) {
                return new short[] { (short) main, d, -1, 64, 0 };
            }
            return new short[] { (short) main, d, -1, 0 };
        }
        if (main == 15 || main == 16) {
            return new short[] { (short) main, d, -1 };
        }
        return new short[] { (short) main, d };
    }

    /**
     * Lane guidance for maneuver index idx as the panel's LANES token: lanes separated by
     * '/', each ANGLE[,ANGLE...]:STATUS. From lgN_index / lgN_lane_directions (one angle per
     * lane) / lgN_lane_angles ('|' per lane, ',' between extra angles) / lgN_lane_status.
     * "-" when there is none. Call with STATE locked.
     */
    static String laneSpec(String idx) {
        for (int k = 0; k < 32; k++) {
            StringBuffer p = new StringBuffer("lg").append(k).append('_');
            int plen = p.length();
            if (!idx.equals(str(p.append("index").toString()).trim())) {
                continue;
            }
            p.setLength(plen);
            String[] dirs = split(str(p.append("lane_directions").toString()), ',');
            p.setLength(plen);
            String[] st = split(str(p.append("lane_status").toString()), ',');
            p.setLength(plen);
            String[] ang = split(str(p.append("lane_angles").toString()), '|');
            if (dirs.length == 0 || (dirs.length == 1 && dirs[0].trim().length() == 0)) {
                return "-";
            }
            StringBuffer b = new StringBuffer();
            for (int i = 0; i < dirs.length && i < 12; i++) {
                if (i > 0) {
                    b.append('/');
                }
                String main = dirs[i].trim();
                b.append(main.length() == 0 ? "0" : main);
                if (i < ang.length) {
                    String[] extra = split(ang[i], ',');
                    for (int j = 0; j < extra.length; j++) {
                        String e = extra[j].trim();
                        if (e.length() > 0 && !e.equals(main)) {
                            b.append(',').append(e);
                        }
                    }
                }
                b.append(':').append(i < st.length && st[i].trim().length() > 0 ? st[i].trim() : "0");
            }
            return b.toString();
        }
        return "-";
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

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static void writeLast(byte[] payload) {
        try {
            FileOutputStream out = new FileOutputStream(LAST.getPath(), false);
            try {
                out.write(payload);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** One log line per update: newlines shown as " | ", cut at 700 characters. */
    private static void logUpdate(int n, byte[] payload) {
        StringBuffer b = new StringBuffer("rgd #");
        b.append(n).append(' ');
        int limit = payload.length < 700 ? payload.length : 700;
        for (int i = 0; i < limit; i++) {
            int c = payload[i] & 0xff;
            if (c == 10) {
                b.append(" | ");
            } else if (c >= 32 && c < 127) {
                b.append((char) c);
            } else {
                b.append('?');
            }
        }
        if (limit < payload.length) {
            b.append(" ...");
        }
        log(b.toString());
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    /** Log line from the playback thread. */
    static void note(String line) {
        log(line);
    }

    private static void log(String line) {
        try {
            if (LOG.length() >= LOG_LIMIT) {
                LOG_OLD.delete();
                LOG.renameTo(LOG_OLD);
            }
            FileOutputStream out = new FileOutputStream(LOG.getPath(), true);
            try {
                StringBuffer b = new StringBuffer();
                b.append(System.currentTimeMillis() % 100000000L).append(' ').append(line).append('\n');
                out.write(b.toString().getBytes());
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
