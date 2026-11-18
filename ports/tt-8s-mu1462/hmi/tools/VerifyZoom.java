import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Bytecode verification of the built add-on classes against the stock HMI.
 *
 * usage: VerifyZoom stock.jar outdir
 */
public final class VerifyZoom {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        URLClassLoader loader = new URLClassLoader(
            new URL[] { out.toUri().toURL(), Path.of(args[0]).toUri().toURL() }, null);
        String[] classes = {
            "de/audi/tghu/navi/app/cluster/CombiBAPListener.class",
            "de/audi/tghu/navi/app/map/gui/AbstractGUI.class",
            "de/audi/tghu/fwhmi/evo/DisplayManagerEvo.class",
            "de/audi/app/settings/timeunitslang/dsi/DSICarTimeUnitsLanguageManager.class",
            "de/esolutions/hmi/widgets/audi/evo/high/AnimationMIB2HighSport.class",
            "local/tt/ZoomGate.class",
            "local/tt/RgdBus.class",
            "de/audi/tghu/navi/app/cluster/TtRgi.class",
            "de/audi/tghu/navi/app/cluster/ClusterService.class",
            "local/tt/NavSpy.class",
            "local/tt/ManeuverMapper.class",
            "local/tt/RgiPlayer.class",
            "local/tt/TtPanel.class",
            "local/tt/TurnSpec.class",
            "de/audi/tghu/navi/app/cluster/ClusterViewMode.class",
            "de/audi/tghu/navi/app/cluster/KOMOService.class"
        };
        boolean ok = true;
        for (String c : classes) {
            byte[] b = Files.readAllBytes(out.resolve(c));
            java.io.StringWriter sw = new java.io.StringWriter();
            CheckClassAdapter.verify(new ClassReader(b), loader, false, new PrintWriter(sw));
            String report = sw.toString().trim();
            int version = ((b[6] & 0xff) << 8) | (b[7] & 0xff);
            if (report.isEmpty()) {
                System.out.println("verified " + c + " (class version " + version + ")");
            } else {
                ok = false;
                System.out.println("VERIFY FAILED " + c + ":\n" + report);
            }
        }
        if (!ok) System.exit(1);
    }
}
