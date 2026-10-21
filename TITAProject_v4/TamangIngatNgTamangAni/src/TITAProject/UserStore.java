package TITAProject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * User accounts, saved in users.dat. Passwords are never stored: only a salted PBKDF2 hash.
 */
public class UserStore {

    public static final String ADMIN = "Admin";
    public static final String STAFF = "Staff";

    private static final String FILE_NAME = "users.dat";
    private static final int ITERATIONS = 120_000;

    public static class Account implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String username;
        private String role;
        private byte[] salt;
        private byte[] hash;
        // Added later; accounts saved by older versions load with 0 (not locked).
        private int failedAttempts;
        private long lockedUntil;

        Account(String username, String role) {
            this.username = username;
            this.role = role;
        }

        public String getUsername() { return username; }
        public String getRole() { return role; }
        public boolean isAdmin() { return ADMIN.equals(role); }
    }

    private final List<Account> accounts;

    @SuppressWarnings("unchecked")
    public UserStore() {
        List<Account> loaded = new ArrayList<>();
        File file = AppPaths.file(FILE_NAME);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file); ObjectInputStream in = new ObjectInputStream(fis)) {
                loaded = (List<Account>) in.readObject();
            } catch (Exception e) {
                throw new IllegalStateException("The user file (users.dat) could not be read: " + e.getMessage(), e);
            }
        }
        accounts = loaded;
    }

    public boolean isEmpty() {
        return accounts.isEmpty();
    }

    public List<Account> getAccounts() {
        return new ArrayList<>(accounts);
    }

    /**
     * Turns whatever was typed into a valid username: surrounding spaces removed, spaces inside replaced
     * with "_" (so "Juan Dela Cruz" becomes "Juan_Dela_Cruz"), and any other character that is not a letter,
     * number, dot, dash or underscore removed. Used both when an account is created and at sign-in, so
     * typing the name with spaces works both times.
     */
    public static String normalizeUsername(String typed) {
        if (typed == null) return "";
        return typed.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9._-]", "").replaceAll("_{2,}", "_");
    }

    public Account find(String username) {
        String wanted = normalizeUsername(username);
        for (Account a : accounts) {
            if (a.username.equalsIgnoreCase(wanted)) return a;
        }
        return null;
    }

    public long adminCount() {
        return accounts.stream().filter(Account::isAdmin).count();
    }

    /** Returns the account when the password is right, otherwise null. Ignores the lockout (used by tests). */
    public Account authenticate(String username, char[] password) {
        Account a = find(username);
        if (a == null) {
            hash(password, new byte[16]); // same amount of work, so timing doesn't reveal which usernames exist
            return null;
        }
        return MessageDigest.isEqual(a.hash, hash(password, a.salt)) ? a : null;
    }

    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCK_MILLIS = 5 * 60 * 1000L;

    public static class LoginResult {
        public final Account account;   // set when the sign-in succeeded
        public final String message;    // explanation when it failed
        public final boolean justLocked; // true when this attempt triggered a lockout

        LoginResult(Account account, String message, boolean justLocked) {
            this.account = account;
            this.message = message;
            this.justLocked = justLocked;
        }
    }

    /**
     * Sign-in with a lockout: after 5 wrong passwords the account is locked for 5 minutes. The counter is
     * saved in users.dat, so closing and reopening the app does not reset it.
     */
    public LoginResult login(String username, char[] password) {
        Account a = find(username);
        long now = System.currentTimeMillis();
        if (a != null && a.lockedUntil > now) {
            long minutes = Math.max(1, (a.lockedUntil - now + 59_999) / 60_000);
            return new LoginResult(null, "This account is locked after " + MAX_ATTEMPTS + " wrong passwords.\nTry again in about "
                    + minutes + (minutes == 1 ? " minute." : " minutes."), false);
        }

        Account ok = authenticate(username, password);
        if (ok != null) {
            if (ok.failedAttempts != 0 || ok.lockedUntil != 0) {
                ok.failedAttempts = 0;
                ok.lockedUntil = 0;
                trySave();
            }
            return new LoginResult(ok, null, false);
        }

        // The same message whether or not the username exists, so failed sign-ins don't reveal account names.
        String wrong = "Incorrect username or password.\nAfter " + MAX_ATTEMPTS + " wrong attempts the account is locked for 5 minutes.";
        if (a == null) return new LoginResult(null, wrong, false);

        a.failedAttempts++;
        if (a.failedAttempts >= MAX_ATTEMPTS) {
            a.failedAttempts = 0;
            a.lockedUntil = now + LOCK_MILLIS;
            trySave();
            return new LoginResult(null, "Too many wrong passwords. The account \"" + a.username + "\" is locked for 5 minutes.", true);
        }
        trySave();
        return new LoginResult(null, wrong, false);
    }

    /** Lets an admin lift a lockout early. */
    public void unlock(Account a) throws IOException {
        a.failedAttempts = 0;
        a.lockedUntil = 0;
        save();
    }

    public boolean isLocked(Account a) {
        return a.lockedUntil > System.currentTimeMillis();
    }

    private void trySave() {
        try {
            save();
        } catch (IOException ignored) {}
    }

    public Account add(String username, char[] password, String role) throws IOException {
        Account a = new Account(normalizeUsername(username), role);
        setPassword(a, password);
        accounts.add(a);
        save();
        return a;
    }

    public void remove(Account a) throws IOException {
        accounts.remove(a);
        save();
    }

    public void resetPassword(Account a, char[] password) throws IOException {
        setPassword(a, password);
        a.failedAttempts = 0;
        a.lockedUntil = 0;
        save();
    }

    private static void setPassword(Account a, char[] password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        a.salt = salt;
        a.hash = hash(password, salt);
    }

    private static byte[] hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is not available.", e);
        }
    }

    private void save() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(accounts));
        }
        FileUtil.writeAtomically(AppPaths.file(FILE_NAME), bytes.toByteArray(), true);
    }
}
