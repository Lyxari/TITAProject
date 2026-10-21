package TITAProject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class FileUtil {

    /**
     * Writes the data to a temp file first and only then swaps it in, so a crash or power loss
     * in the middle of saving leaves the previous file intact instead of a half-written one.
     * When keepBackup is true the previous version is also copied to "<name>.bak".
     */
    public static void writeAtomically(File target, byte[] data, boolean keepBackup) throws IOException {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }

        if (keepBackup && target.exists()) {
            Files.copy(target.toPath(), new File(target.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
