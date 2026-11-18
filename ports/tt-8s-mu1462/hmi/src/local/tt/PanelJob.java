package local.tt;

import de.esolutions.hmi.widgets.audi.base.eal.EALManager;

/** One TtPanel command, run on the HMI event thread (a plain class: no synthetic accessors). */
final class PanelJob implements Runnable {
    private final EALManager eal;
    private final String arg;
    private final int id;

    PanelJob(EALManager eal, String arg, int id) {
        this.eal = eal;
        this.arg = arg;
        this.id = id;
    }

    public void run() {
        String r;
        try {
            r = TtPanel.onHmiThread(eal, arg);
        } catch (Throwable t) {
            r = new StringBuffer("error ").append(String.valueOf(t)).toString();
        }
        RgdBus.note(new StringBuffer("panel #").append(id).append(' ').append(arg).append(" -> ").append(r).toString());
    }
}
