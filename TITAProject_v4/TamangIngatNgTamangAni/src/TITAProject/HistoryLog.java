package TITAProject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HistoryLog {

    private static final String FILE_NAME = "history.txt";
    private static final String SEPARATOR = "----------------------------------------";
    private static final String HASH_PREFIX = "Hash: ";

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // The signed-in user; written into every entry so each transaction shows who did it.
    private static String currentUser = "";

    public static void setCurrentUser(String username) {
        currentUser = username == null ? "" : username;
    }

    private static File historyFile() {
        return AppPaths.file(FILE_NAME);
    }

    public static File getFile() {
        return historyFile();
    }

    public static void log(
            String action,
            String batchId,
            String grainType,
            double quantity,
            String storageUnit
    ) {
        log(action, batchId, grainType, quantity, Batch.DEFAULT_SACK_SIZE, storageUnit, null, null);
    }

    public static void log(
            String action,
            String batchId,
            String grainType,
            double quantity,
            int sackSize,
            String storageUnit,
            String change
    ) {
        log(action, batchId, grainType, quantity, sackSize, storageUnit, change, null);
    }

    /**
     * @param change  optional description of how the stock changed, e.g. "Loss 20 kg (500 kg to 480 kg)".
     * @param details optional extra information, e.g. "Purpose: Sold | Recipient: ABC Store".
     *                Both are written as extra lines; pass null to leave them out.
     */
    public static synchronized void log(
            String action,
            String batchId,
            String grainType,
            double quantity,
            int sackSize,
            String storageUnit,
            String change,
            String details
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("[" + LocalDateTime.now().format(FORMAT) + "] " + oneLine(action));
        lines.add("Batch ID: " + oneLine(batchId));
        lines.add("Grain Type: " + oneLine(grainType));
        lines.add("Quantity: " + plainKg(quantity) + " kg");
        lines.add("Sacks: " + Batch.sacksFor(quantity, sackSize) + " sacks");
        if (change != null && !change.isEmpty()) lines.add("Change: " + oneLine(change));
        if (details != null && !details.isEmpty()) lines.add("Details: " + oneLine(details));
        lines.add("Storage Unit: " + oneLine(storageUnit));
        if (!currentUser.isEmpty()) lines.add("User: " + currentUser);

        try {
            File file = historyFile();
            if (!file.exists()) file.createNewFile();

            // Each entry carries a fingerprint of itself plus the previous entry's fingerprint (a hash chain).
            // Editing, inserting or deleting an entry in Notepad breaks the chain, and verify() reports it.
            String hash = chainHash(lastHash(file), lines);

            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8, true)) {
                for (String l : lines) writer.write(l + "\n");
                writer.write(HASH_PREFIX + hash + "\n");
                writer.write(SEPARATOR + "\n");
            }
            // The latest fingerprint is also kept in a separate file. Without it, deleting the last entries,
            // or removing every fingerprint line, would go unnoticed.
            writeCheckpoint(hash);
        } catch (IOException e) {
            System.out.println("Unable to save history.");
        }
    }

    private static final String CHECKPOINT_FILE = "history.chk";

    private static void writeCheckpoint(String hash) {
        try {
            FileUtil.writeAtomically(AppPaths.file(CHECKPOINT_FILE), hash.getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException ignored) {}
    }

    private static String readCheckpoint() {
        File f = AppPaths.file(CHECKPOINT_FILE);
        if (!f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String oneLine(String s) {
        // One line only, and no long runs of dashes, so typed text can never look like an entry separator.
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").replaceAll("-{5,}", "---").trim();
    }

    private static String chainHash(String previous, List<String> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((previous + "\n" + String.join("\n", lines)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The fingerprint the next entry is chained to. This is the CHECKPOINT (where TITA itself last wrote),
     * not simply the last fingerprint in the file. If someone deletes the newest entries in Notepad, new
     * entries still chain to the true end, so the gap stays detectable forever instead of being "healed"
     * by the next login. (Only an archived Clear history resets the checkpoint.)
     */
    private static String lastHash(File file) throws IOException {
        String checkpoint = readCheckpoint();
        List<String> lastBody = null;
        String lastInFile = null;
        if (file.exists()) {
            for (List<String> entry : readEntries(file)) {
                String h = hashOf(entry);
                if (h != null) {
                    lastInFile = h;
                    lastBody = bodyOf(entry);
                }
            }
        }

        // Before checkpoints existed (upgrade from an older version): continue from the file.
        if (checkpoint == null) return lastInFile == null ? "" : lastInFile;

        // One case where the file is legitimately ahead of the checkpoint: the app stopped right after
        // writing an entry but before updating the checkpoint. Accept it only if that entry chains
        // exactly from the checkpoint.
        if (lastInFile != null && !lastInFile.equals(checkpoint) && lastInFile.equals(chainHash(checkpoint, lastBody))) {
            return lastInFile;
        }
        return checkpoint;
    }

    private static String hashOf(List<String> entry) {
        for (String l : entry) if (l.startsWith(HASH_PREFIX)) return l.substring(HASH_PREFIX.length()).trim();
        return null;
    }

    private static List<String> bodyOf(List<String> entry) {
        List<String> body = new ArrayList<>();
        for (String l : entry) if (!l.startsWith(HASH_PREFIX)) body.add(l);
        return body;
    }

    /** Splits the file into entries (lines between separators). */
    private static List<List<String>> readEntries(File file) throws IOException {
        List<List<String>> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            List<String> entry = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(SEPARATOR)) {
                    entries.add(entry);
                    entry = new ArrayList<>();
                } else {
                    entry.add(line);
                }
            }
            if (!entry.isEmpty() && entry.stream().anyMatch(l -> !l.trim().isEmpty())) entries.add(entry);
        }
        return entries;
    }

    /**
     * Checks the hash chain. Returns null when the file is intact, otherwise a message naming the first
     * entry that was changed, inserted or deleted outside the app. Entries written by older versions
     * (before fingerprints existed) at the start of the file are accepted as they are.
     */
    public static String verify() {
        Object[] r = check();
        return r == null ? null : (String) r[1];
    }

    /** Returns null when intact, otherwise {index of the first bad entry (or the entry count), message}. */
    private static Object[] check() {
        File file = historyFile();
        String checkpoint = readCheckpoint();
        if (!file.exists()) {
            return checkpoint == null || checkpoint.isEmpty() ? null : new Object[]{0, "The history file was deleted."};
        }
        try {
            List<List<String>> entries = readEntries(file);
            String previous = "";
            boolean chainStarted = false;
            for (int i = 0; i < entries.size(); i++) {
                String hash = hashOf(entries.get(i));
                List<String> body = bodyOf(entries.get(i));
                String title = body.isEmpty() ? "(empty entry)" : body.get(0);
                if (hash == null) {
                    if (chainStarted) return new Object[]{i, "An entry without a fingerprint was added: " + title};
                    continue;
                }
                if (!hash.equals(chainHash(previous, body))) {
                    return new Object[]{i, "This entry was edited, or entries just before it were deleted: " + title};
                }
                previous = hash;
                chainStarted = true;
            }
            if (checkpoint != null && !checkpoint.equals(previous)) {
                return new Object[]{entries.size(),
                        "The newest entries were deleted, or fingerprints were removed (the log no longer ends where TITA last wrote it)."};
            }
            return null;
        } catch (IOException e) {
            return new Object[]{0, "The history file could not be read: " + e.getMessage()};
        }
    }

    /**
     * Runs the check and, if the history was tampered with, records a "HISTORY TAMPERING DETECTED" entry,
     * but only once per problem (not again if such an entry already follows the damaged spot).
     * Called at sign-in, before anything else is logged, and whenever the History page is shown.
     * Returns the problem message, or null when the history is intact.
     */
    public static synchronized String reportTampering() {
        Object[] r = check();
        if (r == null) return null;
        int from = (Integer) r[0];
        String message = (String) r[1];
        try {
            List<List<String>> entries = readEntries(historyFile());
            for (int i = Math.max(0, from); i < entries.size(); i++) {
                List<String> body = bodyOf(entries.get(i));
                if (!body.isEmpty() && body.get(0).contains("HISTORY TAMPERING DETECTED")) return message;
            }
        } catch (IOException ignored) {}
        log("HISTORY TAMPERING DETECTED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, message);
        return message;
    }

    // Plain number without grouping commas or scientific notation, so the file stays easy to parse.
    static String plainKg(double kg) {
        BigDecimal value = BigDecimal.valueOf(Batch.round(kg)).stripTrailingZeros();
        if (value.scale() < 1) value = value.setScale(1);
        return value.toPlainString();
    }

    public static synchronized String readHistory() {
        File file = historyFile();
        if (!file.exists()) {
            return "No history available.";
        }

        StringBuilder history = new StringBuilder();
        boolean needsMigration = false;

        try {
            for (List<String> entry : readEntries(file)) {
                needsMigration |= migrateEntry(entry);
                for (String l : entry) history.append(l).append("\n");
                history.append(SEPARATOR).append("\n");
            }
        } catch (IOException e) {
            return "Unable to read history.";
        }

        // Only rewrite the file when old-format entries were found, and never overwrite it in place.
        if (needsMigration) {
            try {
                FileUtil.writeAtomically(file, history.toString().getBytes(StandardCharsets.UTF_8), false);
            } catch (IOException ignored) {}
        }

        return history.length() > 0 ? history.toString() : "No history available.";
    }

    /**
     * Brings an entry written by an older version up to the current format: "Quantity: 120 kg (3 sacks)"
     * is split into separate Quantity and Sacks lines. Every other line is kept exactly as it was, and
     * entries that already have a fingerprint are never touched. Returns true when something changed.
     */
    private static boolean migrateEntry(List<String> entry) {
        for (String l : entry) if (l.startsWith(HASH_PREFIX)) return false;

        boolean changed = false;
        boolean hasSacks = false;
        int quantityIndex = -1;
        double qty = 0;

        for (int i = 0; i < entry.size(); i++) {
            String line = entry.get(i);
            if (line.startsWith("Sacks:")) hasSacks = true;
            if (!line.startsWith("Quantity:")) continue;

            quantityIndex = i;
            String value = line.substring("Quantity:".length()).trim();
            String sacks = null;
            if (value.contains("(") && value.contains(")")) {
                sacks = value.substring(value.indexOf("(") + 1, value.indexOf(")")).trim();
                value = value.substring(0, value.indexOf("(")).trim();
            }
            String cleanNum = value.replaceAll("(?i)\\bkg\\b", "").replaceAll("[^0-9.]", "");
            try {
                if (!cleanNum.isEmpty()) qty = Double.parseDouble(cleanNum);
            } catch (NumberFormatException ignored) {}

            String rebuilt = "Quantity: " + plainKg(qty) + " kg";
            if (!rebuilt.equals(line)) {
                entry.set(i, rebuilt);
                changed = true;
            }
            if (sacks != null && !hasSacks) {
                entry.add(i + 1, "Sacks: " + sacks);
                hasSacks = true;
                changed = true;
            }
        }

        if (quantityIndex >= 0 && !hasSacks) {
            entry.add(quantityIndex + 1, "Sacks: " + Batch.sacksFor(qty, Batch.DEFAULT_SACK_SIZE) + " sacks");
            changed = true;
        }
        return changed;
    }

    /** Every value of the given field ("Batch ID:", "Grain Type:", ...) that appears in the history file. */
    public static List<String> loggedValues(String field) {
        List<String> values = new ArrayList<>();
        File file = historyFile();
        if (!file.exists()) return values;
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(field)) values.add(line.substring(field.length()).trim());
            }
        } catch (IOException ignored) {}
        return values;
    }

    /** Every batch ID that appears in the history file, used so new IDs never reuse old ones. */
    public static List<String> loggedBatchIds() {
        return loggedValues("Batch ID:");
    }

    /**
     * Clears the on-screen log, but first copies the whole file to history_archive_<date>.txt so the
     * audit trail is never actually destroyed. Returns the archive file name.
     */
    public static synchronized String clearHistory() throws IOException {
        File file = historyFile();
        String archiveName = "history_archive_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt";
        File archive = AppPaths.file(archiveName);

        if (file.exists()) {
            Files.copy(file.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            FileUtil.writeAtomically(file, new byte[0], false);
        }
        writeCheckpoint(""); // a deliberate, archived clear starts a new chain

        log("HISTORY CLEARED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null,
                "Previous log archived to " + archiveName);
        return archive.getPath();
    }
}
