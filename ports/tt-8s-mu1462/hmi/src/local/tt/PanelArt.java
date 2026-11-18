package local.tt;

/**
 * Software drawing of the cockpit turn panel into an RGBA byte array
 * (no AWT on the head unit). Pure Java 1.4, so it can be previewed on a PC.
 *
 * Coordinates are pixels, origin top-left. Shapes are antialiased by
 * computing per-pixel coverage against thick line segments (distance to the
 * segment) and filled triangles (4x4 supersampling).
 *
 * Maneuvers use the BAP/RGI codes the rest of tt-zoom uses: main element
 * (11 follow, 12 prepare, 13/14 turn, 15/16 exit right/left, 19/20 fork,
 * 21-24 roundabout, 25 U-turn, 3/4 arrived/near destination) and direction
 * 0-255 counter-clockwise from straight ahead (64 left, 128 back, 192 right).
 * Side streets are drawn as grey arms, like the stock junction icon.
 */
public final class PanelArt {
    public static final int BG = 0xE0101418;
    public static final int ROUTE = 0xFFFFFFFF;
    public static final int ARM = 0xFF6E757D;
    public static final int BAR_ON = 0xFFFFFFFF;
    public static final int BAR_OFF = 0xFF3A4048;

    private final int w;
    private final int h;
    /** Premultiplied-free ARGB working buffer. */
    private final int[] px;

    public PanelArt(int w, int h) {
        this.w = w;
        this.h = h;
        this.px = new int[w * h];
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int[] pixels() {
        return px;
    }

    /** RGBA bytes (texture format 6). */
    public byte[] rgba() {
        byte[] out = new byte[w * h * 4];
        for (int i = 0, j = 0; i < px.length; i++) {
            int c = px[i];
            out[j++] = (byte) (c >> 16);
            out[j++] = (byte) (c >> 8);
            out[j++] = (byte) c;
            out[j++] = (byte) (c >>> 24);
        }
        return out;
    }

    /**
     * The whole panel: rounded background, maneuver picture in the box
     * (ax, ay, as) and, when bar >= 0, a vertical progress bar (255 = far, 0 = at the turn).
     */
    public void panel(int main, int dir, int[] arms, int bar) {
        panel(main, dir, arms, bar, null, null);
    }

    /**
     * With lanes: laneAngles[i] = the arrow angles of lane i in degrees (0 straight,
     * negative left, positive right), laneStatus[i] = 0 not recommended, 1 good, 2 preferred.
     */
    public void panel(int main, int dir, int[] arms, int bar, int[][] laneAngles, int[] laneStatus) {
        java.util.Arrays.fill(px, 0);
        roundRect(0, 0, w, h, 18, BG);
        boolean lanes = laneAngles != null && laneAngles.length > 0;
        int as = Math.min(w - 40, h - (lanes ? 178 : 120));
        int ax = (w - as) / 2 + 10;
        int ay = 14;
        maneuver(main, dir, arms, ax, ay, as);
        if (lanes) {
            lanes(laneAngles, laneStatus, 30, ay + as + 2, w - 44, 58);
        }
        if (bar >= 0) {
            int bx = 14;
            int by = ay + 8;
            int bh = as - 16;
            int filled = (int) ((long) bh * Math.min(255, bar) / 255);
            roundRect(bx, by, 10, bh, 5, BAR_OFF);
            if (filled > 0) {
                roundRect(bx, by + bh - filled, 10, filled, 5, BAR_ON);
            }
        }
    }

    /** Maneuver picture inside the square (x, y, s). */
    public void maneuver(int main, int dir, int[] arms, int x, int y, int s) {
        float cx = x + s / 2f;
        float jy = y + s * 0.52f;
        float bottom = y + s * 0.97f;
        float len = s * 0.38f;
        float route = s * 0.11f;
        float arm = s * 0.07f;
        if (main == 3 || main == 4 || main == 5 || main == 38) {
            flag(cx, y + s * 0.5f, s);
            return;
        }
        if (main >= 21 && main <= 24) {
            roundabout(cx, jy, s, dir, main == 22 || main == 24);
            return;
        }
        if (main == 25) {
            uturn(cx, bottom, s, (dir & 0xff) < 128 || (dir & 0xff) == 128);
            return;
        }
        // Side streets first, so the route is drawn on top of them.
        if (arms != null) {
            for (int i = 0; i < arms.length; i++) {
                int a = arms[i] & 0xff;
                if (a == 255) {
                    continue;
                }
                float[] e = endpoint(cx, jy, a, len);
                thick(cx, jy, e[0], e[1], arm, ARM);
            }
        }
        int d = dir & 0xff;
        if (main == 11 || main == 6 || main == 9 || main == 10 || d == 255) {
            d = 0;
        }
        if (main == 19 || main == 20) {
            // Fork: a Y; the route takes one branch, the other is grey.
            boolean left = d > 0 && d < 128;
            d = left ? 26 : 230;
            float[] o = endpoint(cx, jy, left ? 230 : 26, len);
            thick(cx, jy, o[0], o[1], route * 0.9f, ARM);
        }
        if (main == 15 || main == 16) {
            // Motorway exit: the main road continues straight in grey, the route peels off.
            boolean left = main == 16 || (d > 0 && d < 128);
            d = left ? 30 : 226;
            thick(cx, jy, cx, y + s * 0.04f, route * 0.9f, ARM);
        }
        thick(cx, bottom, cx, jy, route, ROUTE);
        float[] e = endpoint(cx, jy, d, len * 0.78f);
        thick(cx, jy, e[0], e[1], route, ROUTE);
        head(e[0], e[1], d, s * 0.20f);
        disc(cx, jy, route / 2f, ROUTE);
    }

    public static final int LANE_PREFERRED = 0xFFFFFFFF;
    public static final int LANE_GOOD = 0xFFC8CDD2;
    public static final int LANE_OTHER = 0xFF5A6068;

    /** A row of lane arrows in (x, y, rw, rh), with dashed separators. */
    public void lanes(int[][] angles, int[] status, int x, int y, int rw, int rh) {
        int n = angles.length;
        int cell = Math.min(54, rw / n);
        int start = x + (rw - n * cell) / 2;
        float base = y + rh - 3;
        float mid = y + rh * 0.55f;
        for (int i = 1; i < n; i++) {
            float sx = start + cell * i;
            for (float yy = y + 4; yy < y + rh - 4; yy += 10f) {
                thick(sx, yy, sx, Math.min(yy + 5f, y + rh - 4), 2f, 0x90FFFFFF);
            }
        }
        for (int i = 0; i < n; i++) {
            int st = status == null || i >= status.length ? 0 : status[i];
            int c = st >= 2 ? LANE_PREFERRED : (st == 1 ? LANE_GOOD : LANE_OTHER);
            float cx = start + cell * i + cell / 2f;
            float t = Math.max(3f, cell * 0.13f);
            int[] a = angles[i];
            if (a == null || a.length == 0) {
                a = new int[] { 0 };
            }
            thick(cx, base, cx, mid, t, c);
            for (int k = 0; k < a.length; k++) {
                int d = laneDir(a[k]);
                float[] e = endpoint(cx, mid, d, rh * 0.22f);
                thick(cx, mid, e[0], e[1], t, c);
                laneHead(e[0], e[1], d, rh * 0.26f, c);
            }
        }
    }

    /** Lane angle in degrees (positive = right) to the BAP direction (0-255, 64 left, 192 right). */
    static int laneDir(int degrees) {
        int d = (int) Math.floor(-degrees * 256.0 / 360.0 + 0.5);
        return ((d % 256) + 256) % 256;
    }

    private void laneHead(float x, float y, int dir, float size, int c) {
        double a = dir * Math.PI * 2.0 / 256.0;
        float fx = (float) -Math.sin(a);
        float fy = (float) -Math.cos(a);
        float px1 = -fy;
        float py1 = fx;
        triangle(x + fx * size * 0.8f, y + fy * size * 0.8f,
            x - fx * size * 0.1f + px1 * size * 0.6f, y - fy * size * 0.1f + py1 * size * 0.6f,
            x - fx * size * 0.1f - px1 * size * 0.6f, y - fy * size * 0.1f - py1 * size * 0.6f, c);
    }

    private static float[] endpoint(float cx, float cy, int dir, float len) {
        double a = dir * Math.PI * 2.0 / 256.0;
        return new float[] { (float) (cx - Math.sin(a) * len), (float) (cy - Math.cos(a) * len) };
    }

    /** Arrow head with its tip a little beyond (x, y), pointing along dir. */
    private void head(float x, float y, int dir, float size) {
        double a = dir * Math.PI * 2.0 / 256.0;
        float fx = (float) -Math.sin(a);
        float fy = (float) -Math.cos(a);
        float tipx = x + fx * size * 0.9f;
        float tipy = y + fy * size * 0.9f;
        float bx = x - fx * size * 0.15f;
        float by = y - fy * size * 0.15f;
        float px1 = -fy;
        float py1 = fx;
        triangle(tipx, tipy, bx + px1 * size * 0.75f, by + py1 * size * 0.75f,
            bx - px1 * size * 0.75f, by - py1 * size * 0.75f, ROUTE);
    }

    private void uturn(float cx, float bottom, float s, boolean left) {
        float r = s * 0.17f;
        float route = s * 0.11f;
        float sign = left ? -1f : 1f;
        float x1 = cx - sign * r;
        float x2 = cx + sign * r;
        float top = bottom - s * 0.62f;
        thick(x1, bottom, x1, top, route, ROUTE);
        int steps = 16;
        float lx = x1;
        float ly = top;
        for (int i = 1; i <= steps; i++) {
            double t = Math.PI * i / steps;
            float nx = (float) (cx - sign * r * Math.cos(t));
            float ny = (float) (top - r * Math.sin(t));
            thick(lx, ly, nx, ny, route, ROUTE);
            lx = nx;
            ly = ny;
        }
        float endy = top + s * 0.18f;
        thick(x2, top, x2, endy, route, ROUTE);
        head(x2, endy, 128, s * 0.20f);
    }

    private void roundabout(float cx, float cy, float s, int dir, boolean clockwise) {
        float r = s * 0.21f;
        float route = s * 0.10f;
        float bottom = y0(cy, s);
        ring(cx, cy, r, s * 0.07f, ARM);
        thick(cx, bottom, cx, cy + r, route, ROUTE);
        int d = dir & 0xff;
        // Right-hand traffic goes round anti-clockwise on screen (bottom -> right -> top);
        // the ring position at angle a matches endpoint(), so travel from pi to the exit angle.
        double a = d * Math.PI * 2.0 / 256.0;
        double start = Math.PI;
        double end;
        if (!clockwise) {
            end = a <= start + 0.01 ? a + Math.PI * 2.0 : a;
        } else {
            end = a >= start - 0.01 ? a - Math.PI * 2.0 : a;
        }
        int steps = 32;
        float lx = cx;
        float ly = cy + r;
        for (int i = 1; i <= steps; i++) {
            double t = start + (end - start) * i / steps;
            float nx = (float) (cx - Math.sin(t) * r);
            float ny = (float) (cy - Math.cos(t) * r);
            thick(lx, ly, nx, ny, route, ROUTE);
            lx = nx;
            ly = ny;
        }
        float[] e = endpoint(lx, ly, d, s * 0.12f);
        thick(lx, ly, e[0], e[1], route, ROUTE);
        head(e[0], e[1], d, s * 0.18f);
    }

    private static float y0(float cy, float s) {
        return cy + s * 0.45f;
    }

    private void flag(float cx, float cy, float s) {
        float pole = s * 0.05f;
        thick(cx - s * 0.12f, cy + s * 0.32f, cx - s * 0.12f, cy - s * 0.30f, pole, ROUTE);
        triangle(cx - s * 0.12f, cy - s * 0.30f, cx + s * 0.25f, cy - s * 0.18f, cx - s * 0.12f, cy - s * 0.04f, ROUTE);
    }

    private void ring(float cx, float cy, float r, float t, int c) {
        int x0 = (int) (cx - r - t);
        int x1 = (int) (cx + r + t) + 1;
        int y0 = (int) (cy - r - t);
        int y1 = (int) (cy + r + t) + 1;
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                float dx = x + 0.5f - cx;
                float dy = y + 0.5f - cy;
                float d = (float) Math.abs(Math.sqrt(dx * dx + dy * dy) - r);
                blend(x, y, c, clamp(t / 2f - d + 0.5f));
            }
        }
    }

    /** Round-capped thick line. */
    public void thick(float x0, float y0, float x1, float y1, float width, int c) {
        float r = width / 2f;
        int minx = (int) Math.floor(Math.min(x0, x1) - r - 1);
        int maxx = (int) Math.ceil(Math.max(x0, x1) + r + 1);
        int miny = (int) Math.floor(Math.min(y0, y1) - r - 1);
        int maxy = (int) Math.ceil(Math.max(y0, y1) + r + 1);
        float dx = x1 - x0;
        float dy = y1 - y0;
        float l2 = dx * dx + dy * dy;
        for (int y = Math.max(0, miny); y < Math.min(h, maxy); y++) {
            for (int x = Math.max(0, minx); x < Math.min(w, maxx); x++) {
                float qx = x + 0.5f - x0;
                float qy = y + 0.5f - y0;
                float t = l2 == 0f ? 0f : (qx * dx + qy * dy) / l2;
                if (t < 0f) {
                    t = 0f;
                } else if (t > 1f) {
                    t = 1f;
                }
                float ex = qx - t * dx;
                float ey = qy - t * dy;
                float d = (float) Math.sqrt(ex * ex + ey * ey);
                blend(x, y, c, clamp(r - d + 0.5f));
            }
        }
    }

    public void disc(float cx, float cy, float r, int c) {
        thick(cx, cy, cx, cy, r * 2f, c);
    }

    public void triangle(float ax, float ay, float bx, float by, float cx, float cy, int c) {
        int minx = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
        int maxx = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
        int miny = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
        int maxy = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
        for (int y = Math.max(0, miny); y <= Math.min(h - 1, maxy); y++) {
            for (int x = Math.max(0, minx); x <= Math.min(w - 1, maxx); x++) {
                int hit = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        float px0 = x + (sx + 0.5f) / 4f;
                        float py0 = y + (sy + 0.5f) / 4f;
                        float d1 = (px0 - bx) * (ay - by) - (ax - bx) * (py0 - by);
                        float d2 = (px0 - cx) * (by - cy) - (bx - cx) * (py0 - cy);
                        float d3 = (px0 - ax) * (cy - ay) - (cx - ax) * (py0 - ay);
                        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
                        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
                        if (!(neg && pos)) {
                            hit++;
                        }
                    }
                }
                if (hit > 0) {
                    blend(x, y, c, hit / 16f);
                }
            }
        }
    }

    public void roundRect(int x, int y, int rw, int rh, int rad, int c) {
        for (int yy = Math.max(0, y); yy < Math.min(h, y + rh); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(w, x + rw); xx++) {
                float dx = 0f;
                float dy = 0f;
                if (xx < x + rad) {
                    dx = x + rad - (xx + 0.5f);
                } else if (xx >= x + rw - rad) {
                    dx = xx + 0.5f - (x + rw - rad);
                }
                if (yy < y + rad) {
                    dy = y + rad - (yy + 0.5f);
                } else if (yy >= y + rh - rad) {
                    dy = yy + 0.5f - (y + rh - rad);
                }
                float cov = 1f;
                if (dx > 0f && dy > 0f) {
                    cov = clamp(rad - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f);
                }
                blend(xx, yy, c, cov);
            }
        }
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Source-over blend of colour c (ARGB) with extra coverage a. */
    private void blend(int x, int y, int c, float a) {
        if (a <= 0f) {
            return;
        }
        int i = y * w + x;
        float sa = ((c >>> 24) / 255f) * a;
        int d = px[i];
        float da = (d >>> 24) / 255f;
        float oa = sa + da * (1f - sa);
        if (oa <= 0f) {
            px[i] = 0;
            return;
        }
        int r = mix((c >> 16) & 0xff, (d >> 16) & 0xff, sa, da, oa);
        int g = mix((c >> 8) & 0xff, (d >> 8) & 0xff, sa, da, oa);
        int b = mix(c & 0xff, d & 0xff, sa, da, oa);
        px[i] = ((int) (oa * 255f + 0.5f) << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int s, int d, float sa, float da, float oa) {
        int v = (int) ((s * sa + d * da * (1f - sa)) / oa + 0.5f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
