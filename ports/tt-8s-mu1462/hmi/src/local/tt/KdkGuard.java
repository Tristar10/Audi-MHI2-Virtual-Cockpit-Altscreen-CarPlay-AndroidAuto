package local.tt;

/** v27: the watcher thread that keeps the nav engine's banner picture transparent (TtPanel.guardLoop). */
final class KdkGuard implements Runnable {
    public void run() {
        TtPanel.guardLoop();
    }
}
