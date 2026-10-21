package TITAProject;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Decides where the data files (batches.dat, users.dat, history.txt, ...) live, so the app finds the same
 * data no matter which folder it is started from.
 *
 *  - Run as a .jar: the folder that contains the .jar.
 *  - Run from Eclipse: the project folder (the parent of the "bin" output folder), which is where the
 *    data files have always been.
 *  - Otherwise: the current working folder, as before.
 *
 * Starting Java with -Dtita.data=SOME_FOLDER overrides all of this.
 */
public class AppPaths {

    private static File dataDir;

    public static synchronized File dataDir() {
        if (dataDir != null) return dataDir;

        String override = System.getProperty("tita.data");
        if (override != null && !override.isEmpty()) {
            dataDir = new File(override);
        } else {
            dataDir = detect();
        }
        dataDir.mkdirs();
        return dataDir;
    }

    private static File detect() {
        try {
            Path code = Paths.get(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File f = code.toFile();
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) return f.getAbsoluteFile().getParentFile();
            if (f.isDirectory() && f.getName().equalsIgnoreCase("bin") && f.getParentFile() != null) return f.getAbsoluteFile().getParentFile();
        } catch (Exception ignored) {
            // fall through to the working folder
        }
        return new File(System.getProperty("user.dir"));
    }

    public static File file(String name) {
        return new File(dataDir(), name);
    }
}
