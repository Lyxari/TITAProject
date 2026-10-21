package TITAProject;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

public class TITA extends JFrame {

    private CardLayout cardLayout;
    private JPanel mainPanel;

    private ArrayList<Batch> batches;
    private ArrayList<StorageUnit> storageUnits;

    private DefaultTableModel inventoryModel;
    private JTable inventoryTable;
    private DefaultTableModel shelfModel;
    private JTable shelfTable;
    private DefaultTableModel historyModel;
    private JTable historyTable;
    private DefaultTableModel warningModel;
    private JScrollPane warningScrollPane;

    private JLabel totalGrainLabel;
    private JLabel totalBatchLabel;
    private JLabel expiringLabel;

    private JPanel warehouseContainerPanel;

    private JLabel totalSacksLabel;
    private UI.Card expiringCard;
    private JLabel expiredLabel;
    private UI.Card expiredCard;
    private JPanel stockPanel;
    private JPanel warningCardPanel;
    private CardLayout warningCards;

    private JPanel varietyPanel;

    private static final String DATA_FILE = "batches.dat";

    // Starting capacity of each warehouse. An admin can change it on the Warehouses page.
    private static final double DEFAULT_CAPACITY_KG = 50_000;

    // The rice types most commonly known and sold in the Philippines, offered in the Add Batch form.
    // A fixed list keeps "Dinorado" and "dinorado" from becoming two different varieties; anything else
    // goes through "Other". "Unspecified" is for stock whose variety is not known.
    private static final String[] RICE_VARIETIES = {
            "Sinandomeng", "Dinorado", "Jasmine", "Well-milled", "Regular-milled", "Unspecified"
    };
    private static final String OTHER_VARIETY = "Other (type it in)";

    private static final String[] RELEASE_PURPOSES = {
            "Sold", "Distributed / Relief", "Delivered to branch / store", "Milling / Processing", "Other"
    };

    private static UserStore userStore;
    private static UserStore.Account currentUser;

    private int wh1Counter = 1;
    private int wh2Counter = 1;
    private int wh3Counter = 1;

    private LocalDate lastRefreshDate = LocalDate.now();

    // Set when batches.dat was unreadable and could not be moved aside, so it is never copied over the good backup.
    private boolean skipNextBackup = false;

    public TITA() {
        UI.install();
        setIconImage(UI.appIcon());

        setTitle("TITA - Grain Storage Management System (FEFO Enabled) - " + currentUser.getUsername() + " (" + currentUser.getRole() + ")");
        setSize(1280, 800);
        setMinimumSize(new Dimension(1140, 660));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(UI.BG);

        batches = new ArrayList<Batch>();
        storageUnits = new ArrayList<StorageUnit>();

        storageUnits.add(new StorageUnit("Warehouse 1", DEFAULT_CAPACITY_KG));
        storageUnits.add(new StorageUnit("Warehouse 2", DEFAULT_CAPACITY_KG));
        storageUnits.add(new StorageUnit("Warehouse 3", DEFAULT_CAPACITY_KG));

        loadData();

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setBackground(UI.BG);

        // Admins get their own oversight dashboard; staff get the operational one.
        mainPanel.add(isAdmin() ? createAdminDashboard() : createDashboard(), "Dashboard");
        mainPanel.add(createInventory(), "Inventory");
        mainPanel.add(createAddBatch(), "Add Batch");
        mainPanel.add(createWarehouses(), "Warehouses");
        mainPanel.add(createShelfLife(), "Shelf-life");
        mainPanel.add(createHistory(), "History");

        // Top Navigation Bar
        add(createNavigation(), BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);

        updateDashboard();
        updateWarnings();

        showPage("Dashboard");

        // Statuses depend on today's date, so when the date changes (e.g. the app is left open overnight)
        // every page is refreshed. Checking once a minute is enough and doesn't disturb the user otherwise.
        Timer dayTimer = new Timer(60_000, e -> {
            if (!LocalDate.now().equals(lastRefreshDate)) {
                lastRefreshDate = LocalDate.now();
                refreshAll();
            }
        });
        dayTimer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { dayTimer.stop(); }
            // Closing the window with X ends the session too, so it is recorded like "Log out".
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (currentUser != null) {
                    HistoryLog.log("USER LOGGED OUT", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, "Window closed");
                }
            }
        });
    }

    private static boolean isAdmin() {
        return currentUser != null && currentUser.isAdmin();
    }

    private boolean requireAdmin(String what) {
        if (isAdmin()) return true;
        UI.showMessage(this, "Only an administrator can " + what + ".\nYou are signed in as " + currentUser.getUsername() + " (" + currentUser.getRole() + ").",
                "Administrator Only", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private void restartApplication() {
        this.dispose();
        SwingUtilities.invokeLater(() -> {
            TITA newWindow = new TITA();
            newWindow.setVisible(true);
        });
    }

    private void logOut() {
        HistoryLog.log("USER LOGGED OUT", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, null);
        currentUser = null;
        HistoryLog.setCurrentUser(null);
        dispose();
        SwingUtilities.invokeLater(TITA::startSession);
    }

    /** Shows the sign-in screen (or first-time admin setup), then opens the main window. */
    // Held for as long as the app runs, so a second copy can't open the same data files at the same time
    // (two copies would overwrite each other's saves).
    private static java.nio.channels.FileChannel lockChannel;
    private static java.nio.channels.FileLock instanceLock;

    private static boolean acquireInstanceLock() {
        if (instanceLock != null) return true;
        try {
            lockChannel = java.nio.channels.FileChannel.open(AppPaths.file("tita.lock").toPath(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
        } catch (Exception e) {
            instanceLock = null;
        }
        return instanceLock != null;
    }

    private static void startSession() {
        UI.install();
        if (!acquireInstanceLock()) {
            UI.showMessage(null, "TITA is already open on this computer.\nUse the window that is already running, so no changes are lost.",
                    "Already Running", JOptionPane.WARNING_MESSAGE);
            System.exit(0);
            return;
        }
        try {
            if (userStore == null) userStore = new UserStore();
        } catch (IllegalStateException e) {
            UI.showMessage(null, e.getMessage() + "\nThe app will close so the file is not overwritten.", "Cannot Start", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        if (userStore.isEmpty() && !createFirstAdmin()) {
            System.exit(0);
            return;
        }

        UserStore.Account account = showLogin();
        if (account == null) {
            System.exit(0);
            return;
        }

        currentUser = account;
        HistoryLog.setCurrentUser(account.getUsername());
        // Check the history BEFORE writing anything, so the problem is recorded (once) and the login entry
        // can't hide it. Admins are told straight away.
        String tampering = HistoryLog.reportTampering();
        if (tampering != null && account.isAdmin()) {
            UI.showMessage(null, "The history file was changed outside TITA.\n" + tampering
                            + "\n\nThis has been recorded in the history. Open the History page for details.",
                    "History Tampering Detected", JOptionPane.WARNING_MESSAGE);
        }
        HistoryLog.log("USER LOGGED IN", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, "Role: " + account.getRole());
        new TITA().setVisible(true);
    }

    private static boolean createFirstAdmin() {
        // Known limitation: the accounts live in a local file, so deleting users.dat brings back this setup screen.
        // It cannot be fully prevented without a server or database, but it is made visible: the user is warned,
        // and the setup is written to the (fingerprinted) history with a note that data already existed.
        boolean existingData = AppPaths.file(DATA_FILE).length() > 0 || HistoryLog.getFile().length() > 0;
        String intro = existingData
                ? "WARNING: inventory or history data already exists, but no user accounts were found (users.dat is missing).\n"
                  + "If this is not a new installation, the user file may have been deleted. This setup will be recorded in the history.\n\n"
                  + "Create the administrator account."
                : "No accounts exist yet. Create the administrator account.\nAdministrators can manage users, warehouse capacity and clearing records.";

        while (true) {
            JTextField user = UI.hintField("e.g. admin");
            JPasswordField pass = new JPasswordField();
            JPasswordField confirm = new JPasswordField();
            JPanel form = credentialForm(intro, user, pass, confirm);
            if (!UI.showForm(null, form, "Set Up Administrator", "Exit", "Create account", user)) return false;

            String error = validateNewAccount(user.getText(), pass.getPassword(), confirm.getPassword());
            if (error != null) {
                UI.showMessage(null, error, "Input Error", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            try {
                UserStore.Account created = userStore.add(user.getText(), pass.getPassword(), UserStore.ADMIN);
                if (!created.getUsername().equals(user.getText().trim())) {
                    UI.showMessage(null, "Your username is \"" + created.getUsername() + "\".\n(Spaces are saved as \"_\". You can type it with spaces when signing in.)");
                }
                HistoryLog.setCurrentUser(created.getUsername());
                HistoryLog.log("ADMIN ACCOUNT SET UP", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null,
                        existingData ? "WARNING: users.dat was missing while inventory/history data existed" : "First-time installation");
                HistoryLog.setCurrentUser(null);
                return true;
            } catch (IOException e) {
                UI.showMessage(null, "Could not save the account: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static UserStore.Account showLogin() {
        while (true) {
            JTextField user = UI.hintField("Username");
            JPasswordField pass = new JPasswordField();
            JPanel form = credentialForm("Sign in to TITA Grain Storage.", user, pass, null);
            if (!UI.showForm(null, form, "Sign In", "Exit", "Sign in", user)) return null;

            UserStore.LoginResult result = userStore.login(user.getText(), pass.getPassword());
            if (result.account != null) return result.account;
            if (result.justLocked) {
                HistoryLog.log("ACCOUNT LOCKED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null,
                        "Account: " + UserStore.normalizeUsername(user.getText()) + " | " + UserStore.MAX_ATTEMPTS + " wrong passwords, locked for 5 minutes");
            }
            UI.showMessage(null, result.message, "Sign In Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JPanel credentialForm(String intro, JTextField user, JPasswordField pass, JPasswordField confirm) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(4, 0, 4, 10);
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        form.add(new JLabel("<html><body style='width:300px'>" + intro.replace("\n", "<br>") + "</body></html>"), g);
        g.gridwidth = 1;
        g.gridy = 1; form.add(new JLabel("Username:"), g);
        g.gridx = 1; g.weightx = 1; form.add(user, g);
        g.gridx = 0; g.gridy = 2; g.weightx = 0; form.add(new JLabel("Password:"), g);
        g.gridx = 1; g.weightx = 1; form.add(pass, g);
        if (confirm != null) {
            g.gridx = 0; g.gridy = 3; g.weightx = 0; form.add(new JLabel("Confirm password:"), g);
            g.gridx = 1; g.weightx = 1; form.add(confirm, g);
        }
        return form;
    }

    private static String validateNewAccount(String username, char[] password, char[] confirm) {
        // Spaces are allowed when typing: "Juan Dela Cruz" is saved as "Juan_Dela_Cruz".
        String u = UserStore.normalizeUsername(username);
        if (u.length() < 3 || u.length() > 20) return "Username must be 3 to 20 characters long (letters, numbers, spaces, dots, dashes or underscores).";
        if (userStore.find(u) != null) return "The username \"" + u + "\" is already taken.";
        if (password.length < 6) return "Password must be at least 6 characters.";
        if (!java.util.Arrays.equals(password, confirm)) return "The passwords do not match.";
        return null;
    }

    private void manageUsers() {
        if (!requireAdmin("manage user accounts")) return;
        if (userStore == null) {
            try {
                userStore = new UserStore();
            } catch (IllegalStateException e) {
                UI.showMessage(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        DefaultTableModel model = UI.model(new String[]{"Username", "Role", "Status"});
        JTable table = new JTable(model);
        UI.styleTable(table);
        Runnable reload = () -> {
            model.setRowCount(0);
            for (UserStore.Account a : userStore.getAccounts()) {
                model.addRow(new Object[]{a.getUsername(), a.getRole(), userStore.isLocked(a) ? "Locked (reset password to unlock)" : "Active"});
            }
        };
        reload.run();

        JButton addButton = UI.secondary("Add user");
        JButton resetButton = UI.secondary("Reset password");
        JButton removeButton = UI.danger("Remove user");

        addButton.addActionListener(e -> {
            JTextField user = UI.hintField("e.g. Juan Dela Cruz");
            JPasswordField pass = new JPasswordField();
            JPasswordField confirm = new JPasswordField();
            JComboBox<String> role = UI.combo(UserStore.STAFF, UserStore.ADMIN);
            JPanel form = credentialForm("Staff can add, release, transfer and write off stock, and record stock decreases.\n"
                    + "Spaces in the username are saved as \"_\".", user, pass, confirm);
            GridBagConstraints g = new GridBagConstraints();
            g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(4, 0, 4, 10);
            g.gridx = 0; g.gridy = 4; form.add(new JLabel("Role:"), g);
            g.gridx = 1; form.add(role, g);

            // Keep the form open (with what was typed) until the input is valid or the admin cancels.
            while (true) {
                if (!UI.showForm(this, form, "Add User", "Cancel", "Add user", user)) return;
                String error = validateNewAccount(user.getText(), pass.getPassword(), confirm.getPassword());
                if (error == null) break;
                UI.showMessage(this, error, "Input Error", JOptionPane.ERROR_MESSAGE);
            }
            try {
                UserStore.Account created = userStore.add(user.getText(), pass.getPassword(), (String) role.getSelectedItem());
                HistoryLog.log("USER ADDED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null,
                        "Account: " + created.getUsername() + " | Role: " + created.getRole());
                reload.run();
                UI.showMessage(this, "Account created.\nUsername: " + created.getUsername() + "\nRole: " + created.getRole()
                        + (created.getUsername().equals(user.getText().trim()) ? "" : "\n(Spaces were saved as \"_\"; the user can also type the name with spaces.)"));
            } catch (IOException ex) {
                UI.showMessage(this, "Could not save the account: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        resetButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            UserStore.Account account = userStore.find((String) model.getValueAt(row, 0));
            JPasswordField pass = new JPasswordField();
            JPasswordField confirm = new JPasswordField();
            JPanel form = new JPanel(new GridLayout(2, 2, 10, 8));
            form.setOpaque(false);
            form.add(new JLabel("New password:")); form.add(pass);
            form.add(new JLabel("Confirm password:")); form.add(confirm);
            if (!UI.showForm(this, form, "Reset Password: " + account.getUsername(), "Cancel", "Save", pass)) return;
            if (pass.getPassword().length < 6 || !java.util.Arrays.equals(pass.getPassword(), confirm.getPassword())) {
                UI.showMessage(this, "Passwords must match and be at least 6 characters.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                userStore.resetPassword(account, pass.getPassword());
                HistoryLog.log("PASSWORD RESET", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, "Account: " + account.getUsername());
                UI.showMessage(this, "Password updated for " + account.getUsername() + ". Any lockout was lifted.");
                reload.run();
            } catch (IOException ex) {
                UI.showMessage(this, "Could not save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        removeButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            UserStore.Account account = userStore.find((String) model.getValueAt(row, 0));
            if (account.getUsername().equalsIgnoreCase(currentUser.getUsername())) {
                UI.showMessage(this, "You cannot remove the account you are signed in with.", "Not Allowed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (account.isAdmin() && userStore.adminCount() <= 1) {
                UI.showMessage(this, "At least one administrator account must remain.", "Not Allowed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (UI.showConfirm(this, "Remove the account \"" + account.getUsername() + "\"?", "Remove User",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            try {
                userStore.remove(account);
                HistoryLog.log("USER REMOVED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, "N/A", null, "Account: " + account.getUsername());
                reload.run();
            } catch (IOException ex) {
                UI.showMessage(this, "Could not save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel buttons = UI.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(addButton);
        buttons.add(resetButton);
        buttons.add(removeButton);

        JScrollPane sp = UI.scroll(table);
        sp.setBorder(BorderFactory.createLineBorder(UI.LINE));
        sp.setPreferredSize(new Dimension(420, 220));

        JPanel content = UI.transparent(new BorderLayout(0, 12));
        content.add(sp, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        UI.showMessage(this, content, "User Accounts", JOptionPane.PLAIN_MESSAGE);
        updateHistory();
    }

    private String getWarehouseCode(String warehouseName) {
        if (warehouseName.equalsIgnoreCase("Warehouse 1")) return "A";
        if (warehouseName.equalsIgnoreCase("Warehouse 2")) return "B";
        if (warehouseName.equalsIgnoreCase("Warehouse 3")) return "C";
        return "X";
    }

    private String getGrainCode(String grainType) {
        if (grainType.startsWith("Rice")) return "R";
        if (grainType.equalsIgnoreCase("Corn")) return "C";
        if (grainType.equalsIgnoreCase("Mungbean")) return "M";
        return "G";
    }

    private String generateBatchId(String warehouseName, String grainType) {
        String warehouseCode = getWarehouseCode(warehouseName);
        String grainCode = getGrainCode(grainType);

        int currentNum = 1;
        if (warehouseName.equalsIgnoreCase("Warehouse 1")) {
            currentNum = wh1Counter++;
        } else if (warehouseName.equalsIgnoreCase("Warehouse 2")) {
            currentNum = wh2Counter++;
        } else if (warehouseName.equalsIgnoreCase("Warehouse 3")) {
            currentNum = wh3Counter++;
        }

        return String.format("%s%s%04d", warehouseCode, grainCode, currentNum);
    }

    private String peekBatchId(String warehouseName, String grainType) {
        int next = 1;
        if (warehouseName.equalsIgnoreCase("Warehouse 1")) next = wh1Counter;
        else if (warehouseName.equalsIgnoreCase("Warehouse 2")) next = wh2Counter;
        else if (warehouseName.equalsIgnoreCase("Warehouse 3")) next = wh3Counter;
        return String.format("%s%s%04d", getWarehouseCode(warehouseName), getGrainCode(grainType), next);
    }

    private static final int MAX_SHELF_LIFE_DAYS = 3650;

    private static int selectedSackSize(JComboBox<String> sackSizeBox) {
        return "25 kg".equals(sackSizeBox.getSelectedItem()) ? 25 : 50;
    }

    // Varieties typed under "Other" (saved in batches.dat), so they stay in the dropdown after the stock is gone.
    private final java.util.LinkedHashSet<String> customVarieties = new java.util.LinkedHashSet<>();

    private void rememberVariety(String variety) {
        if (variety == null || variety.trim().isEmpty()) return;
        String v = variety.trim();
        for (String fixed : RICE_VARIETIES) if (fixed.equalsIgnoreCase(v)) return;
        for (String known : customVarieties) if (known.equalsIgnoreCase(v)) return;
        customVarieties.add(v);
    }

    /** Known varieties, keyed by lower case: the fixed list, remembered custom ones, and any in storage. */
    private java.util.LinkedHashMap<String, String> knownVarieties() {
        java.util.LinkedHashMap<String, String> known = new java.util.LinkedHashMap<>();
        for (String v : RICE_VARIETIES) known.put(v.toLowerCase(), v);
        for (String v : customVarieties) known.putIfAbsent(v.toLowerCase(), v);
        for (Batch b : batches) {
            String v = Batch.varietyOf(b.getGrainType());
            if (v != null) known.putIfAbsent(v.toLowerCase(), v);
        }
        return known;
    }

    private String[] varietyChoices() {
        java.util.List<String> choices = new ArrayList<>(knownVarieties().values());
        choices.add(OTHER_VARIETY);
        return choices.toArray(new String[0]);
    }

    /**
     * Cleans up a variety typed under "Other". Matches an existing variety regardless of capitals
     * ("dinorado" becomes "Dinorado"), and asks "Did you mean ...?" for near-misses like "Dinarado".
     * Returns null when the input is rejected or the user cancels.
     */
    private String resolveTypedVariety(String typed) {
        String v = typed.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) {
            UI.showMessage(this, "Please type the rice variety name.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (!v.matches("[A-Za-z0-9 .'\\-]{2,30}")) {
            UI.showMessage(this, "Variety names may only use letters, numbers, spaces, dots, dashes and apostrophes (2 to 30 characters).",
                    "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        java.util.LinkedHashMap<String, String> known = knownVarieties();
        String exact = known.get(v.toLowerCase());
        if (exact != null) return exact;

        for (String candidate : known.values()) {
            if (candidate.length() >= 4 && editDistance(candidate.toLowerCase(), v.toLowerCase()) <= 2) {
                int choice = UI.showConfirm(this, "\"" + v + "\" is very close to the existing variety \"" + candidate + "\".\n\n"
                                + "Did you mean \"" + candidate + "\"?\n(Choose No to save \"" + v + "\" as a new variety.)",
                        "Check Variety Name", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) return candidate;
                if (choice != JOptionPane.NO_OPTION) return null;
                break;
            }
        }

        // New variety: capitalise each word so later entries match it consistently.
        StringBuilder out = new StringBuilder();
        for (String word : v.split(" ")) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private JPanel previewRow(String name, JLabel value) {
        JPanel row = UI.transparent(new BorderLayout(0, 3));
        row.add(UI.label(name, 12.5f, UI.MUTED, true), BorderLayout.NORTH);
        value.setFont(UI.font(Font.BOLD, 17));
        value.setForeground(UI.INK);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private static class NumberFilter extends DocumentFilter {
        private final boolean allowDecimal;

        public NumberFilter(boolean allowDecimal) {
            this.allowDecimal = allowDecimal;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            if (string == null) return;
            if (isValidInput(fb.getDocument().getText(0, fb.getDocument().getLength()), string, offset)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            if (text == null) return;
            if (isValidInput(fb.getDocument().getText(0, fb.getDocument().getLength()), text, offset)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        private boolean isValidInput(String currentText, String newText, int offset) {
            StringBuilder builder = new StringBuilder(currentText);
            builder.insert(offset, newText);
            String fullText = builder.toString();

            if (allowDecimal) {
                return fullText.matches("^\\d*\\.?\\d*$");
            } else {
                return fullText.matches("^\\d*$");
            }
        }
    }

    private void saveData() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) {
            oos.writeObject(batches);
            // ID counters are saved too, so clearing the inventory never makes IDs start over.
            oos.writeInt(wh1Counter);
            oos.writeInt(wh2Counter);
            oos.writeInt(wh3Counter);
            // Warehouse capacities, as set on the Warehouses page.
            oos.writeInt(storageUnits.size());
            for (StorageUnit w : storageUnits) oos.writeDouble(w.getCapacity());
            // Varieties typed under "Other", so they stay in the dropdown after their stock is gone.
            oos.writeInt(customVarieties.size());
            for (String v : customVarieties) oos.writeUTF(v);
        } catch (IOException e) {
            showSaveError(e);
            return;
        }

        // Written to a temp file and swapped in, with the previous version kept as batches.dat.bak,
        // so an interrupted save can never wipe the inventory.
        try {
            FileUtil.writeAtomically(AppPaths.file(DATA_FILE), bytes.toByteArray(), !skipNextBackup);
            skipNextBackup = false;
        } catch (IOException e) {
            showSaveError(e);
        }
    }

    private void showSaveError(IOException e) {
        UI.showMessage(this, "The inventory could NOT be saved: " + e.getMessage()
                + "\nYour last change is only in memory. Check that the folder is writable and try again.",
                "Save Failed", JOptionPane.ERROR_MESSAGE);
    }

    private void trackId(String id) {
        if (id == null || id.length() < 6) return;
        try {
            int next = Integer.parseInt(id.substring(2)) + 1;
            if (id.startsWith("A")) wh1Counter = Math.max(wh1Counter, next);
            else if (id.startsWith("B")) wh2Counter = Math.max(wh2Counter, next);
            else if (id.startsWith("C")) wh3Counter = Math.max(wh3Counter, next);
        } catch (NumberFormatException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void loadData() {
        // IDs already used in the history log are never handed out again.
        for (String id : HistoryLog.loggedBatchIds()) trackId(id);
        // Varieties that ever appeared in the history stay available in the Add Batch dropdown.
        for (String type : HistoryLog.loggedValues("Grain Type:")) rememberVariety(Batch.varietyOf(type));

        File file = AppPaths.file(DATA_FILE);
        File backup = AppPaths.file(DATA_FILE + ".bak");
        if (!file.exists() && !backup.exists()) {
            return;
        }

        if (file.exists() && readDataFile(file)) {
            return;
        }

        // The main file is missing or unreadable. Never start silently with an empty inventory:
        // set the damaged file aside, try the backup from the previous save, and tell the user.
        String setAside = "";
        if (file.exists()) {
            File damaged = AppPaths.file(DATA_FILE + ".damaged-" + System.currentTimeMillis());
            if (file.renameTo(damaged)) {
                setAside = "\nThe unreadable file was kept as " + damaged.getName() + ".";
            } else {
                // Could not move it: keep a copy, and make sure the next save doesn't back up the damaged file
                // over the good backup.
                try {
                    java.nio.file.Files.copy(file.toPath(), damaged.toPath());
                    setAside = "\nA copy of the unreadable file was kept as " + damaged.getName() + ".";
                } catch (IOException ignored) {}
                skipNextBackup = true;
            }
        }

        if (backup.exists() && readDataFile(backup)) {
            UI.showMessage(null, "The inventory file could not be read, so the backup from the previous save was loaded."
                    + "\nThe most recent change may be missing. Please check the inventory." + setAside,
                    "Inventory Restored From Backup", JOptionPane.WARNING_MESSAGE);
            saveData();
        } else {
            UI.showMessage(null, "The inventory file could not be read and no usable backup was found."
                    + "\nThe app will start with an empty inventory." + setAside,
                    "Inventory Could Not Be Loaded", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Reads one data file. Nothing is changed unless the whole file is read successfully. */
    @SuppressWarnings("unchecked")
    private boolean readDataFile(File file) {
        ArrayList<Batch> loaded;
        int c1 = 0, c2 = 0, c3 = 0;
        double[] capacities = null;
        java.util.List<String> savedVarieties = new ArrayList<>();

        // Two separate resources: if the header is corrupt, the ObjectInputStream constructor throws and the
        // file stream must still be closed, otherwise Windows keeps the file locked.
        try (FileInputStream fis = new FileInputStream(file); ObjectInputStream ois = new ObjectInputStream(fis)) {
            loaded = (ArrayList<Batch>) ois.readObject();
            try {
                c1 = ois.readInt();
                c2 = ois.readInt();
                c3 = ois.readInt();
                int n = ois.readInt();
                capacities = new double[n];
                for (int i = 0; i < n; i++) capacities[i] = ois.readDouble();
                int v = ois.readInt();
                for (int i = 0; i < v; i++) savedVarieties.add(ois.readUTF());
            } catch (EOFException ignored) {
                // File saved by an older version without counters, capacities or varieties.
            }
        } catch (Exception e) {
            System.err.println("Failed to load " + file.getName() + ": " + e.getMessage());
            return false;
        }

        batches = loaded;
        for (String v : savedVarieties) rememberVariety(v);
        for (StorageUnit w : storageUnits) w.getGrainQueue().getQueue().clear();

        for (Batch batch : batches) {
            if (batch.getStorageUnit() != null && batch.getStorageUnit().toLowerCase().contains("silo")) {
                batch.setStorageUnit(batch.getStorageUnit().replaceAll("(?i)Silo", "Warehouse"));
            }

            // Re-round weights saved by older versions (e.g. 70.60000000000001).
            batch.setQuantity(batch.getQuantity());

            StorageUnit warehouse = findStorageUnit(batch.getStorageUnit());
            if (warehouse != null) {
                warehouse.getGrainQueue().addBatch(batch);
            }

            trackId(batch.getId());
        }

        wh1Counter = Math.max(wh1Counter, c1);
        wh2Counter = Math.max(wh2Counter, c2);
        wh3Counter = Math.max(wh3Counter, c3);
        if (capacities != null) {
            for (int i = 0; i < Math.min(capacities.length, storageUnits.size()); i++) {
                if (capacities[i] > 0) storageUnits.get(i).setCapacity(capacities[i]);
            }
        }
        return true;
    }

    private static RowFilter<DefaultTableModel, Integer> searchFilter(String query) {
        // Pattern.quote makes characters like "(" in "Rice (Dinorado)" match literally.
        return RowFilter.regexFilter("(?i)" + Pattern.quote(query.trim()));
    }

    private void refreshAll() {
        updateInventory();
        updateShelfLife();
        updateDashboard();
        updateWarnings();
        updateAdminDashboard();
        updateHistory();
        updateWarehouses();
    }

    private void removeBatch(Batch batch) {
        batches.remove(batch);
        StorageUnit warehouse = findStorageUnit(batch.getStorageUnit());
        if (warehouse != null) warehouse.getGrainQueue().getQueue().remove(batch);
    }

    private void clearAllInventory() {
        if (!requireAdmin("clear the inventory")) return;

        double totalKg = 0;
        int totalSacks = 0;
        for (Batch b : batches) {
            totalKg += b.getQuantity();
            totalSacks += b.getSacks();
        }
        totalKg = Batch.round(totalKg);
        int count = batches.size();

        int confirm = UI.showConfirm(
                this,
                "Are you sure you want to clear ALL inventory batches?\n"
                        + count + (count == 1 ? " batch, " : " batches, ") + Batch.kg(totalKg) + " kg will be removed.\n"
                        + "Every batch and the total are recorded in the history first.",
                "Warning: Clear Inventory",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION) {
            // Record exactly what is being removed, batch by batch, before anything is deleted.
            for (Batch b : batches) {
                HistoryLog.log("BATCH REMOVED (INVENTORY CLEARED)", b.getId(), b.getGrainType(), b.getQuantity(), b.getSackSize(),
                        b.getStorageUnit(), "Removed " + HistoryLog.plainKg(b.getQuantity()) + " kg (batch removed)",
                        "Status: " + b.getStatus() + " | Expiry: " + b.getExpiryDate());
            }
            HistoryLog.log("INVENTORY CLEARED", "N/A", "All Grains", totalKg, Batch.DEFAULT_SACK_SIZE, "All Warehouses",
                    "Removed " + HistoryLog.plainKg(totalKg) + " kg in " + count + (count == 1 ? " batch" : " batches") + " (" + totalSacks + " sacks)",
                    null);

            batches.clear();
            for (StorageUnit warehouse : storageUnits) {
                warehouse.getGrainQueue().getQueue().clear();
            }

            // Counters are deliberately kept so new batches don't reuse IDs that are in the history.
            saveData();

            UI.showMessage(this, "All inventory records have been cleared.\n" + Batch.kg(totalKg) + " kg in " + count
                    + (count == 1 ? " batch was" : " batches were") + " recorded in the history.");
            restartApplication();
        }
    }

    private final java.util.LinkedHashMap<String, UI.NavButton> navButtons =
            new java.util.LinkedHashMap<String, UI.NavButton>();

    private void showPage(String name) {
        if (name.equals("Dashboard")) {
            updateDashboard();
            updateWarnings();
            updateAdminDashboard();
        } else if (name.equals("Inventory")) {
            updateInventory();
        } else if (name.equals("Warehouses")) {
            updateWarehouses();
        } else if (name.equals("Shelf-life")) {
            updateShelfLife();
        } else if (name.equals("History")) {
            updateHistory();
        }
        cardLayout.show(mainPanel, name);
        for (java.util.Map.Entry<String, UI.NavButton> entry : navButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey().equals(name));
        }
    }

    private JPanel createNavigation() {
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = UI.g2(g);
                g2.setPaint(new GradientPaint(0, 0, UI.PRIMARY_DARK, getWidth(), 0, UI.PRIMARY));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        nav.setOpaque(false);
        nav.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        String[][] pages = {
                {"Dashboard", isAdmin() ? "Admin dashboard" : "Dashboard", "dashboard"},
                {"Inventory", "Inventory", "inventory"},
                {"Add Batch", "Add batch", "add"},
                {"Warehouses", "Warehouses", "warehouse"},
                {"Shelf-life", "Shelf-life", "clock"},
                {"History", "History", "history"}
        };

        JPanel pageButtons = UI.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        for (String[] page : pages) {
            final String key = page[0];
            UI.NavButton button = new UI.NavButton(page[1], page[2]);
            button.addActionListener(e -> showPage(key));
            navButtons.put(key, button);
            pageButtons.add(button);
        }

        JPanel account = UI.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        account.add(UI.label(currentUser.getUsername() + " (" + currentUser.getRole() + ")", 13, Color.WHITE, true));
        if (isAdmin()) {
            UI.NavButton usersButton = new UI.NavButton("Users", "check");
            usersButton.addActionListener(e -> manageUsers());
            account.add(usersButton);
        }
        UI.NavButton logoutButton = new UI.NavButton("Log out", "");
        logoutButton.addActionListener(e -> logOut());
        account.add(logoutButton);

        nav.setLayout(new BorderLayout());
        nav.add(pageButtons, BorderLayout.CENTER);
        nav.add(account, BorderLayout.EAST);
        return nav;
    }

    private JPanel createDashboard() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Dashboard");

        totalGrainLabel = new JLabel(UI.big("0", "kg"));
        totalSacksLabel = new JLabel("0 sacks in storage");
        totalBatchLabel = new JLabel("0");
        expiringLabel = new JLabel("0");
        JLabel unitsLabel = new JLabel(String.valueOf(storageUnits.size()));

        expiredLabel = new JLabel("0");

        JPanel stats = UI.transparent(new GridLayout(1, 5, 16, 0));
        stats.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));
        stats.add(UI.statCard("Usable grain", totalGrainLabel, totalSacksLabel, "grain", UI.PRIMARY, UI.GOOD_BG));
        stats.add(UI.statCard("Active batches", totalBatchLabel, new JLabel("Currently stored"), "inventory", UI.PRIMARY, UI.GOOD_BG));
        expiringCard = UI.statCard("Expiring soon", expiringLabel, new JLabel("Within the next " + Batch.ALERT_DAYS + " days"), "alert", UI.WARN, UI.WARN_BG);
        stats.add(expiringCard);
        expiredCard = UI.statCard("Expired", expiredLabel, new JLabel("Write off before release"), "alert", UI.BAD, UI.BAD_BG);
        stats.add(expiredCard);
        stats.add(UI.statCard("Storage units", unitsLabel, new JLabel("Warehouse 1–" + storageUnits.size()), "warehouse", UI.PRIMARY, UI.GOOD_BG));

        String[] columns = {"Batch ID", "Grain type", "Storage unit", "Sacks", "Expires on", "Time left", "Status"};
        warningModel = UI.model(columns);
        JTable warningTable = new JTable(warningModel);
        warningTable.setAutoCreateRowSorter(true);
        UI.styleTable(warningTable);
        warningTable.getColumnModel().getColumn(5).setCellRenderer(UI.days());
        warningTable.getColumnModel().getColumn(6).setCellRenderer(UI.pill());
        int[] w = {90, 180, 120, 90, 110, 170, 140};
        for (int i = 0; i < w.length; i++) warningTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);

        JScrollPane warningScrollPane = UI.scroll(warningTable);
        warningScrollPane.setPreferredSize(new Dimension(100, 130));

        JLabel emptyLabel = new JLabel("Every batch is within a safe shelf-life.", SwingConstants.CENTER);
        emptyLabel.setFont(UI.font(Font.PLAIN, 14));
        emptyLabel.setForeground(UI.MUTED);

        warningCards = new CardLayout();
        warningCardPanel = UI.transparent(warningCards);
        warningCardPanel.add(warningScrollPane, "table");
        warningCardPanel.add(emptyLabel, "empty");
        warningCards.show(warningCardPanel, "empty");

        UI.Card warnCard = UI.card(new BorderLayout(0, 10));
        warnCard.setBorder(BorderFactory.createEmptyBorder(18, 6, 8, 6));
        JPanel warnHead = UI.transparent(new GridLayout(2, 1, 0, 2));
        warnHead.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        warnHead.add(UI.label("Shelf-life alerts", 16, UI.INK, true));
        warnHead.add(UI.label("Batches expiring within the next " + Batch.ALERT_DAYS + " days, nearest expiry first. "
                + "Expired batches are listed after them for write-off.", 12.5f, UI.MUTED, false));
        warnCard.add(warnHead, BorderLayout.NORTH);
        warnCard.add(warningCardPanel, BorderLayout.CENTER);

        stockPanel = UI.transparent(new GridLayout(0, 1, 0, 10));
        UI.Card stockCard = UI.card(new BorderLayout(0, 16));
        JPanel stockHead = UI.transparent(new GridLayout(2, 1, 0, 2));
        stockHead.add(UI.label("Stock by warehouse", 16, UI.INK, true));
        stockHead.add(UI.label("How full each warehouse is, against its capacity", 12.5f, UI.MUTED, false));
        stockCard.add(stockHead, BorderLayout.NORTH);
        stockCard.add(stockPanel, BorderLayout.CENTER);

        varietyPanel = UI.transparent(new GridLayout(0, 2, 24, 10));
        UI.Card varietyCard = UI.card(new BorderLayout(0, 16));
        JPanel varietyHead = UI.transparent(new GridLayout(2, 1, 0, 2));
        varietyHead.add(UI.label("Stock by grain and variety", 16, UI.INK, true));
        varietyHead.add(UI.label("Usable stock across all warehouses (expired not included)", 12.5f, UI.MUTED, false));
        varietyCard.add(varietyHead, BorderLayout.NORTH);
        varietyCard.add(varietyPanel, BorderLayout.CENTER);

        JPanel stockRowPanel = UI.transparent(new GridLayout(1, 2, 16, 0));
        stockRowPanel.add(stockCard);
        stockRowPanel.add(varietyCard);

        JPanel lower = UI.transparent(new BorderLayout(0, 16));
        lower.add(stockRowPanel, BorderLayout.NORTH);
        lower.add(warnCard, BorderLayout.CENTER);

        panel.add(UI.north(UI.header(title, "Stock levels and the batches that need attention."), stats), BorderLayout.NORTH);
        panel.add(lower, BorderLayout.CENTER);

        return panel;
    }

    // ---- Admin dashboard ---------------------------------------------------------------------------------
    // Shown instead of the operational dashboard when an administrator signs in: oversight figures (stock,
    // capacity, losses, accounts), what needs attention, and the latest activity from the audit trail.

    private JLabel adminStockLabel, adminStockCaption, adminCapLabel, adminCapCaption;
    private JLabel adminLossLabel, adminLossCaption, adminUsersLabel, adminUsersCaption;
    private UI.Card adminLossCard;
    private JPanel adminAttentionPanel, adminCapacityPanel;
    private DefaultTableModel adminActivityModel;

    private JPanel createAdminDashboard() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Admin dashboard");

        adminStockLabel = new JLabel(UI.big("0", "kg"));
        adminStockCaption = new JLabel(" ");
        adminCapLabel = new JLabel("0%");
        adminCapCaption = new JLabel(" ");
        adminLossLabel = new JLabel(UI.big("0", "kg"));
        adminLossCaption = new JLabel(" ");
        adminUsersLabel = new JLabel("0");
        adminUsersCaption = new JLabel(" ");

        JPanel stats = UI.transparent(new GridLayout(1, 4, 16, 0));
        stats.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));
        stats.add(UI.statCard("Usable stock", adminStockLabel, adminStockCaption, "grain", UI.PRIMARY, UI.GOOD_BG));
        stats.add(UI.statCard("Capacity used", adminCapLabel, adminCapCaption, "warehouse", UI.PRIMARY, UI.GOOD_BG));
        adminLossCard = UI.statCard("Losses this month", adminLossLabel, adminLossCaption, "alert", UI.BAD, UI.BAD_BG);
        stats.add(adminLossCard);
        stats.add(UI.statCard("User accounts", adminUsersLabel, adminUsersCaption, "check", UI.PRIMARY, UI.GOOD_BG));

        JButton usersButton = UI.secondary("Manage users");
        JButton reportButton = UI.secondary("Monthly report");
        JButton warehousesButton = UI.primary("Warehouses");
        usersButton.addActionListener(e -> manageUsers());
        reportButton.addActionListener(e -> showMonthlyReport());
        warehousesButton.addActionListener(e -> showPage("Warehouses"));
        JPanel actions = UI.buttonRow();
        actions.add(usersButton);
        actions.add(reportButton);
        actions.add(warehousesButton);

        adminAttentionPanel = UI.transparent(new GridLayout(0, 1, 0, 8));
        UI.Card attentionCard = UI.card(new BorderLayout(0, 14));
        attentionCard.add(UI.label("Needs attention", 16, UI.INK, true), BorderLayout.NORTH);
        attentionCard.add(adminAttentionPanel, BorderLayout.CENTER);

        adminCapacityPanel = UI.transparent(new GridLayout(0, 1, 0, 10));
        UI.Card capacityCard = UI.card(new BorderLayout(0, 14));
        capacityCard.add(UI.label("Warehouse capacity", 16, UI.INK, true), BorderLayout.NORTH);
        capacityCard.add(adminCapacityPanel, BorderLayout.CENTER);

        JPanel left = UI.transparent(new BorderLayout(0, 16));
        left.add(attentionCard, BorderLayout.NORTH);
        left.add(capacityCard, BorderLayout.CENTER);

        adminActivityModel = UI.model(new String[]{"Date & Time", "User", "Action", "Batch ID", "Change"});
        JTable activity = new JTable(adminActivityModel);
        UI.styleTable(activity);
        activity.getColumnModel().getColumn(2).setCellRenderer(UI.action());
        int[] w = {140, 80, 210, 70, 220};
        for (int i = 0; i < w.length; i++) activity.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        UI.Card activityCard = UI.card(new BorderLayout(0, 10));
        activityCard.setBorder(BorderFactory.createEmptyBorder(18, 6, 8, 6));
        JPanel activityHead = UI.transparent(new GridLayout(2, 1, 0, 2));
        activityHead.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        activityHead.add(UI.label("Recent activity", 16, UI.INK, true));
        activityHead.add(UI.label("The latest entries in the audit trail. Full log on the History page.", 12.5f, UI.MUTED, false));
        activityCard.add(activityHead, BorderLayout.NORTH);
        activityCard.add(UI.scroll(activity), BorderLayout.CENTER);

        JPanel lower = UI.transparent(new GridLayout(1, 2, 16, 0));
        lower.add(left);
        lower.add(activityCard);

        JPanel header = UI.header(title, "Oversight of stock, losses, accounts and the audit trail. Daily operations are on the other pages.");
        panel.add(UI.north(header, UI.north(UI.toolbar(UI.transparent(new FlowLayout()), actions), stats)), BorderLayout.NORTH);
        panel.add(lower, BorderLayout.CENTER);
        return panel;
    }

    private JLabel attentionItem(String text, Color color) {
        JLabel l = UI.label(text, 13, color, color != UI.MUTED);
        l.setIcon(new UI.DotIcon(color));
        l.setIconTextGap(10);
        return l;
    }

    private void updateAdminDashboard() {
        if (adminStockLabel == null) return;

        double usable = 0, expiredKg = 0;
        int expiredCount = 0, soonCount = 0;
        for (Batch b : batches) {
            long days = b.getRemainingDays();
            if (days < 0) { expiredCount++; expiredKg += b.getQuantity(); }
            else {
                usable += b.getQuantity();
                if (days <= Batch.ALERT_DAYS) soonCount++;
            }
        }
        adminStockLabel.setText(UI.big(Batch.kg(usable), "kg"));
        adminStockCaption.setText(batches.size() + (batches.size() == 1 ? " batch" : " batches") + " in storage");

        double stored = 0, capacity = 0;
        for (StorageUnit u : storageUnits) { stored += u.getTotalQuantity(); capacity += u.getCapacity(); }
        long capPercent = capacity > 0 ? Math.round(stored / capacity * 100) : 0;
        adminCapLabel.setText(capPercent + "%");
        adminCapCaption.setText(Batch.kg(stored) + " of " + Batch.kg(capacity) + " kg");

        // Losses this month, from the audit trail: adjustment losses plus write-offs.
        String month = java.time.YearMonth.now().toString();
        double adjustLoss = 0, writtenOff = 0;
        java.util.List<LogEntry> entries = parseHistory();
        for (LogEntry e : entries) {
            if (!e.dateTime.startsWith(month)) continue;
            String a = e.action.toUpperCase();
            if (a.startsWith("STOCK ADJUSTED")) adjustLoss += changeAmount(e.change, "Loss", "-");
            else if (a.startsWith("BATCH WRITTEN OFF") || a.startsWith("PARTIAL WRITE-OFF")) writtenOff += e.quantity;
        }
        double losses = adjustLoss + writtenOff;
        adminLossLabel.setText(UI.big(Batch.kg(losses), "kg"));
        adminLossCaption.setText(Batch.kg(writtenOff) + " kg written off · " + Batch.kg(adjustLoss) + " kg adjustments");
        if (adminLossCard != null) {
            if (losses > 0) adminLossCard.tone(UI.BAD_BG, new Color(0xF2B8B0));
            else adminLossCard.tone(UI.CARD, UI.LINE);
        }

        int admins = 0, staff = 0, locked = 0;
        if (userStore != null) {
            for (UserStore.Account acc : userStore.getAccounts()) {
                if (acc.isAdmin()) admins++; else staff++;
                if (userStore.isLocked(acc)) locked++;
            }
        }
        adminUsersLabel.setText(String.valueOf(admins + staff));
        adminUsersCaption.setText(admins + (admins == 1 ? " admin, " : " admins, ") + staff + " staff"
                + (locked > 0 ? " · " + locked + " locked" : ""));

        // Needs attention
        adminAttentionPanel.removeAll();
        String tampering = HistoryLog.verify();
        adminAttentionPanel.add(tampering == null
                ? attentionItem("History log intact (no changes outside TITA)", UI.GOOD)
                : attentionItem("History was changed outside TITA. See the History page.", UI.BAD));
        adminAttentionPanel.add(expiredCount == 0
                ? attentionItem("No expired stock", UI.GOOD)
                : attentionItem(expiredCount + (expiredCount == 1 ? " expired batch" : " expired batches") + " (" + Batch.kg(expiredKg) + " kg) to write off", UI.BAD));
        adminAttentionPanel.add(soonCount == 0
                ? attentionItem("Nothing expiring in the next " + Batch.ALERT_DAYS + " days", UI.GOOD)
                : attentionItem(soonCount + (soonCount == 1 ? " batch expires" : " batches expire") + " within " + Batch.ALERT_DAYS + " days", UI.WARN));
        int nearlyFull = 0;
        for (StorageUnit u : storageUnits) if (u.getFillFraction() >= 0.8) nearlyFull++;
        adminAttentionPanel.add(nearlyFull == 0
                ? attentionItem("All warehouses below 80% capacity", UI.GOOD)
                : attentionItem(nearlyFull + (nearlyFull == 1 ? " warehouse is" : " warehouses are") + " 80% full or more", UI.WARN));
        if (locked > 0) adminAttentionPanel.add(attentionItem(locked + (locked == 1 ? " account is" : " accounts are") + " locked after wrong passwords", UI.WARN));
        adminAttentionPanel.revalidate();
        adminAttentionPanel.repaint();

        adminCapacityPanel.removeAll();
        for (StorageUnit u : storageUnits) {
            adminCapacityPanel.add(UI.stockRow(u.getName(),
                    Batch.kg(u.getTotalQuantity()) + " / " + Batch.kg(u.getCapacity()) + " kg (" + Math.round(u.getFillFraction() * 100) + "%)",
                    u.getFillFraction()));
        }
        adminCapacityPanel.revalidate();
        adminCapacityPanel.repaint();

        // Most recent 15 history entries, newest first
        adminActivityModel.setRowCount(0);
        for (int i = entries.size() - 1, shown = 0; i >= 0 && shown < 15; i--, shown++) {
            LogEntry e = entries.get(i);
            adminActivityModel.addRow(new Object[]{e.dateTime, e.user.isEmpty() ? "–" : e.user, e.action, e.batchId, e.change});
        }
    }

    private JPanel createInventory() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Inventory");

        JPanel searchPanel = UI.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JTextField searchField = UI.searchField("Search inventory");

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }

            private void filterTable(String query) {
                TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) inventoryTable.getRowSorter();
                if (query.trim().length() == 0) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(searchFilter(query));
                }
            }
        });

        searchPanel.add(searchField);

        JPanel topContainer = UI.header(title, "All stored batches. Select a row to adjust its stock or write it off.");

        String[] columns = {
                "Batch ID", "Grain Type", "Quantity (kg)", "Sacks", "Sack Size (kg)", "Harvest Date",
                "Shelf Life", "Expiry Date", "Warehouse", "Status"
        };

        inventoryModel = UI.model(columns);
        inventoryTable = new JTable(inventoryModel);
        inventoryTable.setAutoCreateRowSorter(true);
        UI.styleTable(inventoryTable);
        inventoryTable.getColumnModel().getColumn(9).setCellRenderer(UI.pill());
        int[] widths = {90, 160, 110, 70, 100, 110, 90, 110, 120, 140};
        for (int i = 0; i < widths.length; i++) inventoryTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JButton addButton = UI.primary("Add batch");
        JButton releaseButton = UI.secondary("Release grain");
        JButton adjustButton = UI.secondary("Adjust stock");
        JButton transferButton = UI.secondary("Transfer");
        JButton writeOffButton = UI.secondary("Write off");
        JButton exportButton = UI.secondary("Export CSV");
        JButton clearInventoryButton = UI.danger("Clear inventory");

        addButton.addActionListener(e -> showPage("Add Batch"));
        releaseButton.addActionListener(e -> releaseGrain());
        adjustButton.addActionListener(e -> adjustStock());
        transferButton.addActionListener(e -> transferBatch());
        writeOffButton.addActionListener(e -> writeOffBatch());
        exportButton.addActionListener(e -> exportTableToCSV(inventoryModel, "inventory_report.csv", "Inventory"));
        clearInventoryButton.addActionListener(e -> clearAllInventory());

        JPanel buttons = UI.buttonRow();
        buttons.add(releaseButton);
        buttons.add(adjustButton);
        buttons.add(transferButton);
        buttons.add(writeOffButton);
        buttons.add(exportButton);
        buttons.add(addButton);

        JPanel footer = UI.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        if (isAdmin()) footer.add(clearInventoryButton);

        panel.add(UI.north(topContainer, UI.toolbar(searchPanel, buttons)), BorderLayout.NORTH);
        panel.add(UI.tableCard(inventoryTable), BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Makes one value safe for CSV: values containing a comma, quote or line break are wrapped in quotes
     * (with inner quotes doubled), so "Dinorado, Premium" stays in one column. Text starting with = + @
     * gets a leading apostrophe so Excel doesn't run it as a formula.
     */
    static String csvField(String value) {
        String v = value;
        if (!v.isEmpty() && "=+@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private void exportTableToCSV(DefaultTableModel model, String defaultFilename, String reportType) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save " + reportType + " Report as CSV");
        fileChooser.setSelectedFile(new File(defaultFilename));

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();

            try (PrintWriter pw = new PrintWriter(new FileWriter(fileToSave, java.nio.charset.StandardCharsets.UTF_8))) {
                pw.print('﻿'); // lets Excel detect UTF-8
                for (int i = 0; i < model.getColumnCount(); i++) {
                    pw.print(csvField(model.getColumnName(i)));
                    if (i < model.getColumnCount() - 1) pw.print(",");
                }
                pw.print("\r\n");

                for (int row = 0; row < model.getRowCount(); row++) {
                    for (int col = 0; col < model.getColumnCount(); col++) {
                        Object value = model.getValueAt(row, col);
                        pw.print(csvField(value != null ? value.toString() : ""));
                        if (col < model.getColumnCount() - 1) pw.print(",");
                    }
                    pw.print("\r\n");
                }

                UI.showMessage(this, reportType + " successfully exported to:\n" + fileToSave.getAbsolutePath(), "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                UI.showMessage(this, "Failed to export CSV file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void adjustStock() {
        if (inventoryTable == null) return;
        int selectedRow = inventoryTable.getSelectedRow();
        if (selectedRow == -1) {
            UI.showMessage(this, "Please select a batch from the inventory table to adjust.", "No Batch Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int modelRow = inventoryTable.convertRowIndexToModel(selectedRow);
        String batchId = (String) inventoryModel.getValueAt(modelRow, 0);

        Batch batch = findBatch(batchId);
        if (batch == null) {
            UI.showMessage(this, "Selected batch not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0;

        JTextField newQtyField = new JTextField(String.valueOf(batch.getQuantity()));
        JComboBox<String> reasonBox = UI.combo(new String[]{
                "Moisture Shrinkage",
                "Spillage / Handling Loss",
                "Pest / Spoilage Removal",
                "Physical Count Correction",
                "Other"
        });

        JLabel otherLabel = new JLabel("Specify 'Other' cause:");
        JTextField otherReasonField = new JTextField();

        otherLabel.setVisible(false);
        otherReasonField.setVisible(false);

        reasonBox.addActionListener(e -> {
            boolean isOther = "Other".equals(reasonBox.getSelectedItem());
            otherLabel.setVisible(isOther);
            otherReasonField.setVisible(isOther);
            Window window = SwingUtilities.getWindowAncestor(panel);
            if (window != null) window.pack();
        });

        gbc.gridx = 0; gbc.gridy = 0; panel.add(new JLabel("Batch ID:"), gbc);
        gbc.gridx = 1; panel.add(new JLabel(batch.getId() + " (" + batch.getGrainType() + ")"), gbc);

        gbc.gridx = 0; gbc.gridy = 1; panel.add(new JLabel("Current Quantity (kg):"), gbc);
        gbc.gridx = 1; panel.add(new JLabel(Batch.kg(batch.getQuantity()) + " kg (" + batch.getSacks() + " sacks)"), gbc);

        gbc.gridx = 0; gbc.gridy = 2; panel.add(new JLabel("New Physical Quantity (kg):"), gbc);
        gbc.gridx = 1; panel.add(newQtyField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; panel.add(new JLabel("Reason for Adjustment:"), gbc);
        gbc.gridx = 1; panel.add(reasonBox, gbc);

        gbc.gridx = 0; gbc.gridy = 4; panel.add(otherLabel, gbc);
        gbc.gridx = 1; panel.add(otherReasonField, gbc);

        int result = UI.showConfirm(
                this,
                panel,
                "Stock Adjustment / Audit",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) return;

        String qtyText = newQtyField.getText().trim();
        if (!qtyText.matches("\\d+(\\.\\d+)?")) {
            UI.showMessage(this, "Please enter a valid positive number for the new quantity.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double newQty;
        try {
            newQty = Batch.round(Double.parseDouble(qtyText));
        } catch (NumberFormatException ex) {
            UI.showMessage(this, "Please enter a valid positive number for the new quantity.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double oldQty = batch.getQuantity();
        double difference = Batch.round(newQty - oldQty);
        if (difference == 0) {
            UI.showMessage(this, "The new quantity is the same as the current quantity. Nothing was changed.");
            return;
        }

        // Decreases (shrinkage, spillage, pests) are normal staff work. Increases are where stock could be
        // inflated to hide a shortage, so only an administrator may raise a batch's quantity.
        if (difference > 0 && !isAdmin()) {
            UI.showMessage(this, "Only an administrator can increase stock (+" + Batch.kg(difference) + " kg).\n"
                    + "Staff can record decreases such as shrinkage or spillage. Ask an administrator to make this correction.",
                    "Administrator Approval Needed", JOptionPane.WARNING_MESSAGE);
            return;
        }

        StorageUnit warehouse = findStorageUnit(batch.getStorageUnit());
        if (warehouse != null && difference > warehouse.getFreeSpace()) {
            UI.showMessage(this, warehouse.getName() + " does not have enough space for this adjustment.\n"
                    + "Free space: " + Batch.kg(warehouse.getFreeSpace()) + " kg of " + Batch.kg(warehouse.getCapacity()) + " kg.",
                    "Warehouse Full", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String reason = (String) reasonBox.getSelectedItem();
        // Shrinkage, spillage and pest removal can only lower stock; an increase must be a count correction
        // (or an explained "Other"), so the audit log never says "Moisture Shrinkage: +50 kg".
        if (difference > 0 && !"Physical Count Correction".equals(reason) && !"Other".equals(reason)) {
            UI.showMessage(this, "\"" + reason + "\" can only reduce stock, but the new quantity is higher (+" + Batch.kg(difference) + " kg).\n"
                    + "For an increase, choose \"Physical Count Correction\" or \"Other\" and explain it.",
                    "Reason Does Not Match", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if ("Other".equals(reason)) {
            String customReason = cleanText(otherReasonField.getText());
            if (customReason.isEmpty()) {
                UI.showMessage(this, "Please describe the reason for this adjustment in the 'Other' box.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            reason = customReason;
        }

        String change = (difference < 0 ? "Loss " : "Gain ") + HistoryLog.plainKg(Math.abs(difference)) + " kg ("
                + HistoryLog.plainKg(oldQty) + " kg to " + HistoryLog.plainKg(newQty) + " kg)";
        String lossText = difference < 0 ? "Loss: " + Batch.kg(-difference) + " kg" : "Gain: " + Batch.kg(difference) + " kg";

        if (newQty == 0) {
            // An empty batch is removed instead of being left behind as a 0 kg "ghost" row.
            removeBatch(batch);
            saveData();
            HistoryLog.log("STOCK ADJUSTED (" + reason + ") - BATCH REMOVED", batch.getId(), batch.getGrainType(), 0, batch.getSackSize(), batch.getStorageUnit(), change);
            UI.showMessage(this, "Stock adjustment successful!\nBatch ID: " + batch.getId() + "\nPrevious: " + Batch.kg(oldQty) + " kg\n"
                    + lossText + "\nThe batch is now empty and was removed from the inventory.\nReason: " + reason);
        } else {
            batch.setQuantity(newQty);
            saveData();
            HistoryLog.log("STOCK ADJUSTED (" + reason + ")", batch.getId(), batch.getGrainType(), batch.getQuantity(), batch.getSackSize(), batch.getStorageUnit(), change);
            UI.showMessage(this, "Stock adjustment successful!\nBatch ID: " + batch.getId() + "\nPrevious: " + Batch.kg(oldQty) + " kg\nNew: "
                    + Batch.kg(newQty) + " kg (" + batch.getSacks() + " sacks)\n" + lossText + "\nReason: " + reason);
        }

        refreshAll();
    }

    private void writeOffBatch() {
        if (inventoryTable == null) return;
        int selectedRow = inventoryTable.getSelectedRow();
        if (selectedRow == -1) {
            UI.showMessage(this, "Please select a batch from the inventory table to write off.", "No Batch Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String batchId = (String) inventoryModel.getValueAt(inventoryTable.convertRowIndexToModel(selectedRow), 0);
        Batch batch = findBatch(batchId);
        if (batch == null) {
            UI.showMessage(this, "Selected batch not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int sackSize = batch.getSackSize();
        double inBatch = batch.getQuantity();

        JComboBox<String> reasonBox = UI.combo(new String[]{
                "Expired",
                "Spoiled / Moldy",
                "Pest Infestation",
                "Water / Flood Damage",
                "Other"
        });
        if (!batch.isExpired()) reasonBox.setSelectedItem("Spoiled / Moldy");
        JTextField otherReasonField = new JTextField();
        JLabel otherLabel = new JLabel("Details (for 'Other'):");

        // How much is affected: the whole batch by default, or part of it in kg or in sacks.
        JTextField amountField = new JTextField(HistoryLog.plainKg(inBatch));
        ((AbstractDocument) amountField.getDocument()).setDocumentFilter(new NumberFilter(true));
        JComboBox<String> unitBox = UI.combo("kg", "sacks");
        JPanel amountPanel = new JPanel(new BorderLayout(6, 0));
        amountPanel.setOpaque(false);
        amountPanel.add(amountField, BorderLayout.CENTER);
        amountPanel.add(unitBox, BorderLayout.EAST);
        JLabel deductLabel = new JLabel(" ");

        Runnable updateDeduction = () -> {
            double d = writeOffDeduction(amountField.getText(), (String) unitBox.getSelectedItem(), sackSize, inBatch);
            if (d <= 0) {
                deductLabel.setText("Enter the affected quantity");
            } else {
                int sacks = (int) Math.ceil(d / sackSize);
                deductLabel.setText("<html>Will deduct <b>" + Batch.kg(d) + " kg</b> (" + sacks + (sacks == 1 ? " sack" : " sacks")
                        + ")" + (d >= inBatch ? ": the whole batch" : "; " + Batch.kg(inBatch - d) + " kg will remain") + "</html>");
            }
        };
        UI.onChange(amountField, updateDeduction);
        unitBox.addActionListener(e -> {
            // Switching units converts the current amount so the meaning stays the same.
            double d = writeOffDeduction(amountField.getText(), "sacks".equals(unitBox.getSelectedItem()) ? "kg" : "sacks", sackSize, inBatch);
            if (d > 0) {
                amountField.setText("sacks".equals(unitBox.getSelectedItem())
                        ? String.valueOf((int) Math.ceil(d / sackSize)) : HistoryLog.plainKg(d));
            }
            updateDeduction.run();
        });
        updateDeduction.run();

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 10);
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {
                {"Batch ID:", new JLabel(batch.getId() + " (" + batch.getGrainType() + ")")},
                {"In batch:", new JLabel(Batch.kg(inBatch) + " kg (" + batch.getSacks() + " sacks of " + sackSize + " kg)")},
                {"Status:", new JLabel(batch.getStatus() + ", expiry " + batch.getExpiryDate())},
                {"Affected quantity:", amountPanel},
                {"", deductLabel},
                {"Reason:", reasonBox},
                {otherLabel, otherReasonField}
        };
        for (int r = 0; r < rows.length; r++) {
            g.gridy = r;
            g.gridx = 0; g.weightx = 0;
            panel.add(rows[r][0] instanceof JLabel ? (JLabel) rows[r][0] : new JLabel((String) rows[r][0]), g);
            g.gridx = 1; g.weightx = 1;
            panel.add((Component) rows[r][1], g);
        }
        deductLabel.setPreferredSize(new Dimension(320, deductLabel.getPreferredSize().height * 2));

        // The "Details" box only appears when "Other" is chosen.
        otherLabel.setVisible(false);
        otherReasonField.setVisible(false);
        reasonBox.addActionListener(e -> {
            boolean isOther = "Other".equals(reasonBox.getSelectedItem());
            otherLabel.setVisible(isOther);
            otherReasonField.setVisible(isOther);
            Window window = SwingUtilities.getWindowAncestor(panel);
            if (window != null) window.pack();
            if (isOther) otherReasonField.requestFocusInWindow();
        });

        int result = UI.showConfirm(this, panel, "Dispose / Write Off Batch", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        double deduct = writeOffDeduction(amountField.getText(), (String) unitBox.getSelectedItem(), sackSize, inBatch);
        if (deduct <= 0) {
            UI.showMessage(this, "Please enter the affected quantity (more than 0).", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        double typedKg = "sacks".equals(unitBox.getSelectedItem()) ? deduct : parseAmount(amountField.getText());

        String reason = (String) reasonBox.getSelectedItem();
        if ("Other".equals(reason)) {
            String custom = cleanText(otherReasonField.getText());
            if (custom.isEmpty()) {
                UI.showMessage(this, "Please describe why this batch is being written off in the 'Details' box.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            reason = custom;
        }

        int sacks = (int) Math.ceil(deduct / sackSize);
        String sackText = sacks + (sacks == 1 ? " sack" : " sacks");
        String rounding = typedKg > 0 && typedKg < deduct
                ? "; " + HistoryLog.plainKg(typedKg) + " kg affected, rounded up to whole sacks" : "";

        if (deduct >= inBatch) {
            removeBatch(batch);
            saveData();
            HistoryLog.log("BATCH WRITTEN OFF (" + reason + ")", batch.getId(), batch.getGrainType(), inBatch,
                    sackSize, batch.getStorageUnit(), "Disposed " + HistoryLog.plainKg(inBatch) + " kg (" + sackText + rounding + "); batch removed");
            UI.showMessage(this, "Batch " + batch.getId() + " was written off completely.\n" + Batch.kg(inBatch) + " kg disposed. Reason: " + reason);
        } else {
            batch.setQuantity(inBatch - deduct);
            saveData();
            HistoryLog.log("PARTIAL WRITE-OFF (" + reason + ")", batch.getId(), batch.getGrainType(), deduct,
                    sackSize, batch.getStorageUnit(), "Disposed " + HistoryLog.plainKg(deduct) + " kg (" + sackText + rounding + "); "
                            + HistoryLog.plainKg(batch.getQuantity()) + " kg left in batch");
            UI.showMessage(this, "Write-off recorded for batch " + batch.getId() + ".\nDisposed: " + Batch.kg(deduct) + " kg (" + sackText + ")"
                    + (rounding.isEmpty() ? "" : "\n(" + Batch.kg(typedKg) + " kg affected, rounded up to whole sacks)")
                    + "\nRemaining in batch: " + Batch.kg(batch.getQuantity()) + " kg\nReason: " + reason);
        }
        refreshAll();
    }

    private static double parseAmount(String text) {
        try {
            double v = Batch.round(Double.parseDouble(text.trim()));
            return v > 0 ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * How many kg a write-off removes. Damage is disposed in whole sacks: any affected amount is rounded UP
     * to full sacks of this batch's sack size (e.g. 10 kg or 50 kg of damage in 50 kg sacks removes one
     * 50 kg sack; 60 kg removes two). The result never exceeds what is in the batch. Returns 0 for no input.
     */
    static double writeOffDeduction(String text, String unit, int sackSize, double inBatch) {
        double v = parseAmount(text);
        if (v <= 0) return 0;
        double kg = "sacks".equals(unit) ? Math.ceil(v) * sackSize : Math.ceil(v / sackSize - 1e-9) * sackSize;
        return Batch.round(Math.min(inBatch, kg));
    }

    /**
     * Moves all or part of a batch to another warehouse. The moved grain gets a new batch ID in the
     * destination (IDs encode the warehouse) but keeps its harvest date, shelf life and sack size,
     * and both history entries point to each other so the movement can be traced.
     */
    private void transferBatch() {
        if (inventoryTable == null) return;
        int selectedRow = inventoryTable.getSelectedRow();
        if (selectedRow == -1) {
            UI.showMessage(this, "Please select a batch from the inventory table to transfer.", "No Batch Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Batch batch = findBatch((String) inventoryModel.getValueAt(inventoryTable.convertRowIndexToModel(selectedRow), 0));
        if (batch == null) {
            UI.showMessage(this, "Selected batch not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (batch.isExpired()) {
            UI.showMessage(this, "Batch " + batch.getId() + " is expired and should not be moved.\nUse \"Write off\" to dispose of it instead.",
                    "Expired Batch", JOptionPane.WARNING_MESSAGE);
            return;
        }

        java.util.List<String> destinations = new ArrayList<>();
        for (StorageUnit w : storageUnits) {
            if (!w.getName().equalsIgnoreCase(batch.getStorageUnit())) destinations.add(w.getName());
        }
        JComboBox<String> destinationBox = UI.combo(destinations.toArray(new String[0]));
        JTextField qtyField = new JTextField(HistoryLog.plainKg(batch.getQuantity()));
        ((AbstractDocument) qtyField.getDocument()).setDocumentFilter(new NumberFilter(true));
        JTextField noteField = new JTextField();
        JLabel spaceLabel = new JLabel();
        Runnable showSpace = () -> {
            StorageUnit d = findStorageUnit((String) destinationBox.getSelectedItem());
            if (d != null) spaceLabel.setText(Batch.kg(d.getFreeSpace()) + " kg free (" + Math.round(d.getFillFraction() * 100) + "% full)");
        };
        destinationBox.addActionListener(e -> showSpace.run());
        showSpace.run();

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 8));
        panel.add(new JLabel("Batch:"));
        panel.add(new JLabel(batch.getId() + " (" + batch.getGrainType() + ")"));
        panel.add(new JLabel("From:"));
        panel.add(new JLabel(batch.getStorageUnit() + ", " + Batch.kg(batch.getQuantity()) + " kg in batch"));
        panel.add(new JLabel("To:"));
        panel.add(destinationBox);
        panel.add(new JLabel("Space at destination:"));
        panel.add(spaceLabel);
        panel.add(new JLabel("Quantity to move (kg):"));
        panel.add(qtyField);
        panel.add(new JLabel("Note / reference (optional):"));
        panel.add(noteField);

        if (UI.showConfirm(this, panel, "Transfer Between Warehouses", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;

        double qty;
        try {
            qty = Batch.round(Double.parseDouble(qtyField.getText().trim()));
        } catch (NumberFormatException ex) {
            UI.showMessage(this, "Please enter a valid quantity in kg.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (qty <= 0 || qty > batch.getQuantity()) {
            UI.showMessage(this, "Quantity must be more than 0 and at most " + Batch.kg(batch.getQuantity()) + " kg.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        StorageUnit from = findStorageUnit(batch.getStorageUnit());
        StorageUnit to = findStorageUnit((String) destinationBox.getSelectedItem());
        if (to == null) return;
        if (qty > to.getFreeSpace()) {
            UI.showMessage(this, to.getName() + " does not have enough space.\nFree space: " + Batch.kg(to.getFreeSpace()) + " kg.",
                    "Warehouse Full", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String baseGrain = batch.getGrainType().startsWith("Rice") ? "Rice" : batch.getGrainType();
        Batch moved = new Batch(generateBatchId(to.getName(), baseGrain), batch.getGrainType(), qty, batch.getHarvestDate(),
                batch.getShelfLife(), to.getName(), batch.getSackSize());
        batches.add(moved);
        to.getGrainQueue().addBatch(moved);

        double left = Batch.round(batch.getQuantity() - qty);
        if (left == 0) {
            removeBatch(batch);
        } else {
            batch.setQuantity(left);
        }
        saveData();

        String note = cleanText(noteField.getText());
        String details = note.isEmpty() ? null : "Note: " + note;
        HistoryLog.log("TRANSFERRED OUT", batch.getId(), batch.getGrainType(), qty, batch.getSackSize(), from == null ? batch.getStorageUnit() : from.getName(),
                "Moved " + HistoryLog.plainKg(qty) + " kg to " + to.getName() + " as " + moved.getId() + "; "
                        + (left == 0 ? "batch emptied and removed" : HistoryLog.plainKg(left) + " kg left in batch"), details);
        HistoryLog.log("TRANSFERRED IN", moved.getId(), moved.getGrainType(), qty, moved.getSackSize(), to.getName(),
                "Received " + HistoryLog.plainKg(qty) + " kg from " + batch.getStorageUnit() + " (batch " + batch.getId() + ")", details);

        UI.showMessage(this, "Transfer complete.\n" + Batch.kg(qty) + " kg moved from " + batch.getStorageUnit() + " to " + to.getName()
                + ".\nNew batch ID at destination: " + moved.getId()
                + (left == 0 ? "\nThe original batch is now empty and was removed." : "\n" + Batch.kg(left) + " kg remains in " + batch.getId() + "."));
        refreshAll();
    }

    private JPanel createAddBatch() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Add grain batch");

        JPanel form = UI.transparent(new GridBagLayout());
        JComboBox<String> grainBox = UI.combo(new String[]{"Rice", "Corn", "Mungbean"});

        JLabel riceTypeLabel = UI.fieldLabel("Rice variety");
        JComboBox<String> varietyBox = UI.combo(varietyChoices());
        JTextField riceTypeField = UI.hintField("Type the variety name");
        riceTypeField.setVisible(false);
        JPanel varietyPanel = UI.transparent(new BorderLayout(0, 6));
        varietyPanel.add(varietyBox, BorderLayout.NORTH);
        varietyPanel.add(riceTypeField, BorderLayout.SOUTH);
        varietyBox.addActionListener(e -> {
            riceTypeField.setVisible(OTHER_VARIETY.equals(varietyBox.getSelectedItem()));
            varietyPanel.revalidate();
        });

        JTextField quantityField = UI.hintField("0");
        JTextField dateField = UI.hintField("YYYY-MM-DD");
        dateField.setText(LocalDate.now().toString());
        JTextField shelfField = UI.hintField("e.g. 180");
        JComboBox<String> warehouseBox = UI.combo(new String[]{"Warehouse 1", "Warehouse 2", "Warehouse 3"});

        JComboBox<String> sackSizeBox = UI.combo(new String[]{"50 kg", "25 kg"});

        ((AbstractDocument) quantityField.getDocument()).setDocumentFilter(new NumberFilter(true));
        ((AbstractDocument) shelfField.getDocument()).setDocumentFilter(new NumberFilter(false));

        JLabel sacksIndicationLabel = new JLabel("Equivalent Sacks: 0 sacks (Max 50kg/sack)");
        sacksIndicationLabel.setFont(UI.font(Font.PLAIN, 12));
        sacksIndicationLabel.setForeground(UI.MUTED);
        // This text changes while typing. A small fixed preferred width stops it from widening its column,
        // so the form does not shift left and right as the user types.
        sacksIndicationLabel.setPreferredSize(new Dimension(10, sacksIndicationLabel.getPreferredSize().height));
        sacksIndicationLabel.setMinimumSize(new Dimension(10, sacksIndicationLabel.getPreferredSize().height));

        Runnable updateIndication = () -> {
            int sackSize = selectedSackSize(sackSizeBox);
            try {
                String text = quantityField.getText().trim();
                if (text.isEmpty() || text.equals(".")) {
                    sacksIndicationLabel.setText("Equivalent Sacks: 0 sacks (Max " + sackSize + "kg/sack)");
                } else {
                    int calculatedSacks = Batch.sacksFor(Double.parseDouble(text), sackSize);
                    sacksIndicationLabel.setText("Equivalent Sacks: " + calculatedSacks + " sacks (Based on " + sackSize + "kg/sack max)");
                }
            } catch (NumberFormatException ex) {
                sacksIndicationLabel.setText("Equivalent Sacks: 0 sacks (Max " + sackSize + "kg/sack)");
            }
        };
        UI.onChange(quantityField, updateIndication);
        sackSizeBox.addActionListener(e -> updateIndication.run());

        JPanel quantityPanel = UI.transparent(new BorderLayout(0, 6));
        quantityPanel.add(quantityField, BorderLayout.CENTER);
        quantityPanel.add(sacksIndicationLabel, BorderLayout.SOUTH);

        UI.cell(form, UI.fieldLabel("Grain type"), 0, 0);
        UI.cell(form, riceTypeLabel, 1, 0);
        UI.cell(form, grainBox, 0, 1);
        UI.cell(form, varietyPanel, 1, 1);
        UI.cell(form, UI.fieldLabel("Quantity (kg)"), 0, 2);
        UI.cell(form, UI.fieldLabel("Harvest date"), 1, 2);
        UI.cell(form, quantityPanel, 0, 3);
        UI.cell(form, dateField, 1, 3);
        UI.cell(form, UI.fieldLabel("Shelf life (days)"), 0, 4);
        UI.cell(form, UI.fieldLabel("Storage unit"), 1, 4);
        UI.cell(form, shelfField, 0, 5);
        UI.cell(form, warehouseBox, 1, 5);
        UI.cell(form, UI.fieldLabel("Sack size"), 0, 6);
        UI.cell(form, sackSizeBox, 0, 7);

        // Every cell gets the same small base width, so both columns always share the space equally and
        // nothing moves when text changes or the rice-variety box is shown/hidden.
        // (Panels such as the variety box keep a flexible height, so only their contents get the fixed width.)
        for (Component c : form.getComponents()) {
            Component[] targets = c instanceof JPanel ? ((JPanel) c).getComponents() : new Component[]{c};
            for (Component t : targets) {
                if (!(t instanceof JComponent)) continue;
                int h = t.getPreferredSize().height;
                ((JComponent) t).setPreferredSize(new Dimension(10, h));
                ((JComponent) t).setMinimumSize(new Dimension(10, h));
            }
        }

        grainBox.addActionListener(e -> {
            boolean isRice = "Rice".equals(grainBox.getSelectedItem());
            riceTypeLabel.setVisible(isRice);
            varietyPanel.setVisible(isRice);
            form.revalidate();
            form.repaint();
        });

        boolean initialIsRice = "Rice".equals(grainBox.getSelectedItem());
        riceTypeLabel.setVisible(initialIsRice);
        varietyPanel.setVisible(initialIsRice);

        JButton addButton = UI.primary("Add batch");
        JButton clearButton = UI.secondary("Clear");

        JPanel buttons = UI.buttonRow();
        buttons.add(clearButton);
        buttons.add(addButton);

        addButton.addActionListener(e -> {
            String selectedGrain = grainBox.getSelectedItem().toString();
            String grainType;

            if ("Rice".equalsIgnoreCase(selectedGrain)) {
                String variety = (String) varietyBox.getSelectedItem();
                if (OTHER_VARIETY.equals(variety)) {
                    variety = resolveTypedVariety(riceTypeField.getText());
                    if (variety == null) return;
                }
                grainType = "Rice (" + variety + ")";
            } else {
                grainType = selectedGrain;
            }

            String qtyInput = quantityField.getText().trim();
            String shelfInput = shelfField.getText().trim();
            String dateInput = dateField.getText().trim();

            if (qtyInput.isEmpty() || shelfInput.isEmpty()) {
                UI.showMessage(this, "Please fill in all required fields.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            double quantity;
            try {
                quantity = Batch.round(Double.parseDouble(qtyInput));
            } catch (NumberFormatException ex) {
                UI.showMessage(this, "Please enter a valid quantity in kg (for example 120 or 120.5).", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            int shelfLife;
            try {
                shelfLife = Integer.parseInt(shelfInput);
            } catch (NumberFormatException ex) {
                UI.showMessage(this, "Shelf life is too large. Please enter a number of days up to " + MAX_SHELF_LIFE_DAYS + ".", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (quantity <= 0 || shelfLife <= 0) {
                UI.showMessage(this, "Quantity and Shelf Life must be greater than zero.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (shelfLife > MAX_SHELF_LIFE_DAYS) {
                UI.showMessage(this, "Shelf life cannot be more than " + MAX_SHELF_LIFE_DAYS + " days.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            LocalDate harvestDate;
            try {
                harvestDate = LocalDate.parse(dateInput);
            } catch (DateTimeParseException ex) {
                UI.showMessage(this, "Invalid date format. Please use YYYY-MM-DD format.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (harvestDate.isAfter(LocalDate.now())) {
                UI.showMessage(this, "Harvest date cannot be in the future.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Expired grain is never accepted into storage; grain already inside the alert window needs a confirmation.
            LocalDate expiry = harvestDate.plusDays(shelfLife);
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiry);
            if (daysLeft < 0) {
                UI.showMessage(this, "This batch is already expired (expiry date " + expiry + ", " + Math.abs(daysLeft)
                                + (Math.abs(daysLeft) == 1 ? " day" : " days") + " ago).\nExpired grain cannot be added to storage.\n"
                                + "Check the harvest date and shelf life.",
                        "Expired Batch Rejected", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (daysLeft <= Batch.ALERT_DAYS) {
                int choice = UI.showConfirm(this, "This batch expires in " + daysLeft + (daysLeft == 1 ? " day" : " days") + " (" + expiry + ").\n"
                                + "Add it anyway? It will be released first (FEFO).",
                        "Batch Expiring Soon", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
            }

            String warehouseName = warehouseBox.getSelectedItem().toString();
            StorageUnit warehouse = findStorageUnit(warehouseName);
            if (warehouse != null && quantity > warehouse.getFreeSpace()) {
                UI.showMessage(this, warehouseName + " does not have enough space for this batch.\n"
                        + "Free space: " + Batch.kg(warehouse.getFreeSpace()) + " kg of " + Batch.kg(warehouse.getCapacity()) + " kg ("
                        + Math.round(warehouse.getFillFraction() * 100) + "% full).", "Warehouse Full", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String generatedId = generateBatchId(warehouseName, selectedGrain);
            int sackSize = selectedSackSize(sackSizeBox);

            Batch batch = new Batch(generatedId, grainType, quantity, harvestDate, shelfLife, warehouseName, sackSize);
            batches.add(batch);
            rememberVariety(Batch.varietyOf(grainType));

            if (warehouse != null) {
                warehouse.getGrainQueue().addBatch(batch);
            }

            HistoryLog.log("BATCH ADDED", batch.getId(), batch.getGrainType(), batch.getQuantity(), batch.getSackSize(), batch.getStorageUnit(), null,
                    "Shelf life: " + shelfLife + " days | Expiry: " + batch.getExpiryDate());
            saveData();

            UI.showMessage(this, "Batch successfully added!\nAssigned Batch ID: " + batch.getId() + "\nGrain Type: " + grainType
                    + "\nSacks: " + batch.getSacks() + " sacks (" + sackSize + " kg each)");

            quantityField.setText("");
            shelfField.setText("");
            riceTypeField.setText("");
            // A new typed variety becomes a normal choice in the dropdown.
            Object selected = varietyBox.getSelectedItem();
            varietyBox.setModel(new DefaultComboBoxModel<>(varietyChoices()));
            varietyBox.setSelectedItem(OTHER_VARIETY.equals(selected) ? Batch.varietyOf(grainType) : selected);

            refreshAll();
        });

        clearButton.addActionListener(e -> {
            quantityField.setText("");
            shelfField.setText("");
            riceTypeField.setText("");
        });

        UI.Card formCard = UI.card(new BorderLayout(0, 26));
        formCard.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));
        formCard.add(form, BorderLayout.CENTER);
        formCard.add(buttons, BorderLayout.SOUTH);

        JLabel pvId = new JLabel("–");
        JLabel pvSacks = new JLabel("–");
        JLabel pvExpiry = new JLabel("–");
        JLabel pvLeft = new JLabel("–");
        Runnable refreshPreview = () -> {
            pvId.setText(peekBatchId((String) warehouseBox.getSelectedItem(), (String) grainBox.getSelectedItem()));
            try {
                int sacks = Batch.sacksFor(Double.parseDouble(quantityField.getText().trim()), selectedSackSize(sackSizeBox));
                pvSacks.setText(sacks + (sacks == 1 ? " sack" : " sacks"));
            } catch (NumberFormatException ex) {
                pvSacks.setText("–");
            }
            try {
                LocalDate harvest = LocalDate.parse(dateField.getText().trim());
                LocalDate expiry = harvest.plusDays(Integer.parseInt(shelfField.getText().trim()));
                long left = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiry);
                pvExpiry.setText(expiry.toString());
                pvLeft.setText(harvest.isAfter(LocalDate.now()) ? "Harvest date is in the future"
                        : left < 0 ? "Already expired" : left + (left == 1 ? " day" : " days") + " from today");
            } catch (Exception ex) {
                pvExpiry.setText("–");
                pvLeft.setText("–");
            }
        };
        UI.onChange(quantityField, refreshPreview);
        UI.onChange(dateField, refreshPreview);
        UI.onChange(shelfField, refreshPreview);
        warehouseBox.addActionListener(e -> refreshPreview.run());
        sackSizeBox.addActionListener(e -> refreshPreview.run());
        grainBox.addActionListener(e -> refreshPreview.run());
        refreshPreview.run();

        UI.Card previewCard = UI.card(new BorderLayout(0, 20));
        previewCard.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        JPanel previewHead = UI.transparent(new GridLayout(2, 1, 0, 2));
        previewHead.add(UI.label("Batch preview", 16, UI.INK, true));
        previewHead.add(UI.label("Updates as you fill in the form", 12.5f, UI.MUTED, false));
        JPanel previewRows = UI.transparent(new GridLayout(4, 1, 0, 16));
        previewRows.add(previewRow("Next batch ID", pvId));
        previewRows.add(previewRow("Sacks needed", pvSacks));
        previewRows.add(previewRow("Expiry date", pvExpiry));
        previewRows.add(previewRow("Time left", pvLeft));
        previewCard.add(previewHead, BorderLayout.NORTH);
        previewCard.add(previewRows, BorderLayout.CENTER);

        JPanel left = UI.transparent(new BorderLayout());
        left.add(formCard, BorderLayout.NORTH);
        JPanel right = UI.transparent(new BorderLayout());
        right.add(previewCard, BorderLayout.NORTH);
        right.setPreferredSize(new Dimension(290, 10));
        JPanel body = UI.transparent(new BorderLayout(16, 0));
        body.add(left, BorderLayout.CENTER);
        body.add(right, BorderLayout.EAST);

        panel.add(UI.header(title, "Record a new batch. Stock is released earliest expiry first."), BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createWarehouses() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Warehouses");

        warehouseContainerPanel = UI.transparent(new GridLayout(1, storageUnits.size(), 16, 0));
        JPanel wrap = UI.transparent(new BorderLayout());
        wrap.add(warehouseContainerPanel, BorderLayout.NORTH);

        panel.add(UI.header(title, "Grain held in each storage unit against its capacity. It is released earliest expiry first."), BorderLayout.NORTH);
        panel.add(wrap, BorderLayout.CENTER);

        updateWarehouses();
        return panel;
    }

    private void updateWarehouses() {
        if (warehouseContainerPanel == null) return;
        warehouseContainerPanel.removeAll();

        for (StorageUnit warehouse : storageUnits) {
            double qty = warehouse.getTotalQuantity();
            int sacks = 0;
            for (Batch b : warehouse.getGrainQueue().getQueue()) sacks += b.getSacks();
            int count = warehouse.getBatchCount();
            double fill = warehouse.getFillFraction();

            UI.Card box = UI.card(new BorderLayout(0, 20));
            box.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));

            JPanel head = UI.transparent(new BorderLayout(12, 0));
            head.add(UI.chip("warehouse", UI.PRIMARY, UI.GOOD_BG, 38), BorderLayout.WEST);
            head.add(UI.label(warehouse.getName(), 17, UI.INK, true), BorderLayout.CENTER);

            JLabel qtyLabel = new JLabel(UI.big(Batch.kg(qty), "kg"));
            qtyLabel.setForeground(UI.INK);
            qtyLabel.setFont(UI.font(Font.BOLD, 28));
            JPanel totals = UI.transparent(new GridLayout(2, 1, 0, 2));
            totals.add(qtyLabel);
            totals.add(UI.label(count + (count == 1 ? " batch, " : " batches, ") + sacks + (sacks == 1 ? " sack" : " sacks"),
                    13, UI.MUTED, false));

            JPanel shareBlock = UI.transparent(new BorderLayout(0, 7));
            shareBlock.add(new UI.Bar(fill), BorderLayout.NORTH);
            long percent = Math.round(fill * 100);
            shareBlock.add(UI.label(percent + "% full  ·  " + Batch.kg(warehouse.getFreeSpace()) + " of " + Batch.kg(warehouse.getCapacity()) + " kg free",
                    12.5f, percent >= 100 ? UI.BAD : (percent >= 80 ? UI.WARN : UI.MUTED), percent >= 80), BorderLayout.CENTER);

            // "Next out" is the batch a release would actually take: the earliest expiry that is NOT expired
            // (expired batches are never released; they are listed separately for write-off).
            Batch next = null;
            int expiredHere = 0;
            for (Batch b : warehouse.getGrainQueue().getQueue()) {
                if (b.isExpired()) { expiredHere++; continue; }
                if (next == null || b.getExpiryDate().isBefore(next.getExpiryDate())) next = b;
            }
            JPanel nextBlock = UI.transparent(new GridLayout(4, 1, 0, 3));
            nextBlock.add(UI.label("Next out", 12.5f, UI.MUTED, true));
            if (next == null) {
                nextBlock.add(UI.label(count == 0 ? "Nothing stored" : "Nothing releasable", 14, UI.INK, false));
                nextBlock.add(UI.label(" ", 12.5f, UI.MUTED, false));
            } else {
                long days = next.getRemainingDays();
                String when = "Expires in " + days + (days == 1 ? " day" : " days");
                nextBlock.add(UI.label(next.getId() + "  " + next.getGrainType(), 14, UI.INK, true));
                nextBlock.add(UI.label(when, 12.5f, days <= Batch.ALERT_DAYS ? UI.WARN : UI.MUTED, days <= Batch.ALERT_DAYS));
            }
            nextBlock.add(expiredHere == 0 ? UI.label(" ", 12.5f, UI.MUTED, false)
                    : UI.label(expiredHere + (expiredHere == 1 ? " expired batch" : " expired batches") + " to write off", 12.5f, UI.BAD, true));

            JPanel mid = UI.transparent(new BorderLayout(0, 18));
            mid.add(shareBlock, BorderLayout.NORTH);
            mid.add(nextBlock, BorderLayout.CENTER);

            JPanel body = UI.transparent(new BorderLayout(0, 18));
            body.add(totals, BorderLayout.NORTH);
            body.add(mid, BorderLayout.CENTER);

            JButton viewButton = UI.secondary("View contents");
            viewButton.addActionListener(e -> showWarehouseInventory(warehouse));
            JPanel foot = UI.transparent(new GridLayout(1, 0, 8, 0));
            foot.add(viewButton);
            if (isAdmin()) {
                JButton capacityButton = UI.secondary("Set capacity");
                capacityButton.addActionListener(e -> setCapacity(warehouse));
                foot.add(capacityButton);
            }

            box.add(head, BorderLayout.NORTH);
            box.add(body, BorderLayout.CENTER);
            box.add(foot, BorderLayout.SOUTH);

            warehouseContainerPanel.add(box);
        }

        warehouseContainerPanel.revalidate();
        warehouseContainerPanel.repaint();
    }

    private void setCapacity(StorageUnit warehouse) {
        if (!requireAdmin("change warehouse capacity")) return;
        String text = UI.showInput(this, "New capacity for " + warehouse.getName() + " (kg):\nCurrent capacity: "
                + Batch.kg(warehouse.getCapacity()) + " kg\nCurrently stored: " + Batch.kg(warehouse.getTotalQuantity()) + " kg");
        if (text == null) return;

        double capacity;
        try {
            capacity = Batch.round(Double.parseDouble(text.trim().replace(",", "")));
        } catch (NumberFormatException e) {
            UI.showMessage(this, "Please enter a number of kg, for example 50000.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (capacity <= 0 || capacity > 100_000_000) {
            UI.showMessage(this, "Capacity must be between 1 and 100,000,000 kg.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (capacity < warehouse.getTotalQuantity()) {
            UI.showMessage(this, "Capacity cannot be lower than what is already stored (" + Batch.kg(warehouse.getTotalQuantity()) + " kg).",
                    "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double old = warehouse.getCapacity();
        warehouse.setCapacity(capacity);
        saveData();
        HistoryLog.log("CAPACITY CHANGED", "N/A", "N/A", 0, Batch.DEFAULT_SACK_SIZE, warehouse.getName(),
                "Capacity " + HistoryLog.plainKg(old) + " kg to " + HistoryLog.plainKg(capacity) + " kg", null);
        refreshAll();
    }

    private void showWarehouseInventory(StorageUnit warehouse) {
        PriorityQueue<Batch> tempQueue = new PriorityQueue<>(Comparator.comparing(Batch::getExpiryDate));
        tempQueue.addAll(warehouse.getGrainQueue().getQueue());

        JPanel content = UI.transparent(new BorderLayout(0, 14));
        content.add(UI.label("Release order follows the earliest expiry date (FEFO).", 13.5f, UI.MUTED, false), BorderLayout.NORTH);

        if (tempQueue.isEmpty()) {
            JLabel empty = UI.label("No batches stored in this warehouse.", 14, UI.INK, false);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            content.add(empty, BorderLayout.CENTER);
        } else {
            String[] cols = {"Order", "Batch ID", "Grain type", "Quantity (kg)", "Sacks", "Expires", "Time left", "Status"};
            DefaultTableModel m = UI.model(cols);
            int order = 1;
            while (!tempQueue.isEmpty()) {
                Batch b = tempQueue.poll();
                m.addRow(new Object[]{order++, b.getId(), b.getGrainType(), b.getQuantity(), b.getSacks(),
                        b.getExpiryDate(), b.getRemainingDays(), b.getStatus()});
            }
            JTable t = new JTable(m);
            t.setAutoCreateRowSorter(true);
            UI.styleTable(t);
            t.getColumnModel().getColumn(6).setCellRenderer(UI.days());
            t.getColumnModel().getColumn(7).setCellRenderer(UI.pill());
            t.getColumnModel().getColumn(0).setPreferredWidth(60);
            t.getColumnModel().getColumn(2).setPreferredWidth(170);
            t.getColumnModel().getColumn(6).setPreferredWidth(130);
            t.getColumnModel().getColumn(7).setPreferredWidth(140);
            JScrollPane sp = UI.scroll(t);
            sp.setBorder(BorderFactory.createLineBorder(UI.LINE));
            sp.setPreferredSize(new Dimension(800, Math.min(340, 44 + m.getRowCount() * 41)));
            content.add(sp, BorderLayout.CENTER);
        }

        UI.showMessage(this, content, warehouse.getName(), JOptionPane.PLAIN_MESSAGE);
    }

    private JPanel createShelfLife() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("Shelf-life");

        JPanel searchPanel = UI.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JTextField searchField = UI.searchField("Search shelf-life");

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }

            private void filterTable(String query) {
                TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) shelfTable.getRowSorter();
                if (query.trim().length() == 0) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(searchFilter(query));
                }
            }
        });

        searchPanel.add(searchField);

        JPanel topContainer = UI.header(title, "Days remaining for each batch. Sort by time left to see what to release first.");

        String[] columns = { "Batch ID", "Grain Type", "Warehouse", "Remaining Days", "Status" };

        shelfModel = UI.model(columns);
        shelfTable = new JTable(shelfModel);
        shelfTable.setAutoCreateRowSorter(true);
        UI.styleTable(shelfTable);
        shelfTable.getColumnModel().getColumn(3).setCellRenderer(UI.days());
        shelfTable.getColumnModel().getColumn(4).setCellRenderer(UI.pill());
        shelfTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        shelfTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        shelfTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        shelfTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        shelfTable.getColumnModel().getColumn(4).setPreferredWidth(140);

        JButton exportButton = UI.secondary("Export CSV");
        exportButton.addActionListener(e -> exportTableToCSV(shelfModel, "shelflife_report.csv", "Shelf-life"));

        JPanel buttons = UI.buttonRow();
        buttons.add(exportButton);

        panel.add(UI.north(topContainer, UI.toolbar(searchPanel, buttons)), BorderLayout.NORTH);
        panel.add(UI.tableCard(shelfTable), BorderLayout.CENTER);

        return panel;
    }

    private JPanel createHistory() {
        JPanel panel = UI.page();
        JLabel title = new JLabel("History");

        JPanel searchPanel = UI.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JTextField searchField = UI.searchField("Search history");

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(searchField.getText()); }

            private void filterTable(String query) {
                TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) historyTable.getRowSorter();
                if (query.trim().length() == 0) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(searchFilter(query));
                }
            }
        });

        searchPanel.add(searchField);

        JPanel topContainer = UI.header(title, "A log of every addition, release and adjustment.");

        String[] columns = {
                "Date & Time", "User", "Action", "Batch ID", "Grain Type", "Quantity", "Sacks", "Change", "Details", "Storage Unit"
        };

        historyModel = UI.model(columns);
        historyTable = new JTable(historyModel);
        historyTable.setAutoCreateRowSorter(true);
        UI.styleTable(historyTable);
        historyTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        historyTable.getColumnModel().getColumn(2).setCellRenderer(UI.action());
        int[] widths = {150, 90, 260, 90, 150, 90, 70, 300, 320, 110};
        for (int i = 0; i < widths.length; i++) historyTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JButton refreshButton = UI.secondary("Refresh");
        JButton reportButton = UI.primary("Monthly report");
        JButton openFileButton = UI.secondary("Open history file");
        JButton exportButton = UI.secondary("Export CSV");
        JButton clearButton = UI.danger("Clear history");

        refreshButton.addActionListener(e -> updateHistory());
        reportButton.addActionListener(e -> showMonthlyReport());

        openFileButton.addActionListener(e -> {
            if (!requireAdmin("open the raw history file")) return;
            try {
                File file = HistoryLog.getFile();
                if (!file.exists()) file.createNewFile();
                Desktop.getDesktop().open(file);
            } catch (Exception ex) {
                UI.showMessage(this, "Unable to open history file.");
            }
        });

        exportButton.addActionListener(e -> exportTableToCSV(historyModel, "history_report.csv", "History"));

        clearButton.addActionListener(e -> {
            if (!requireAdmin("clear the history")) return;
            int confirm = UI.showConfirm(
                    this,
                    "Clear the history list?\nThe current log will first be saved to an archive file in the app folder,"
                            + " and the clearing itself is recorded with your username.",
                    "Warning: Clear History",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    String archive = HistoryLog.clearHistory();
                    updateHistory();
                    UI.showMessage(this, "History was cleared. The previous log was archived to:\n" + new File(archive).getAbsolutePath());
                } catch (IOException ex) {
                    UI.showMessage(this, "History was NOT cleared because the archive could not be written:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JPanel buttons = UI.buttonRow();
        buttons.add(refreshButton);
        // Staff cannot open the raw file from the app. Edits made in Notepad are still caught by the
        // fingerprint check below.
        if (isAdmin()) buttons.add(openFileButton);
        buttons.add(exportButton);
        if (isAdmin()) buttons.add(clearButton);
        buttons.add(reportButton);

        historyWarning = UI.label(" ", 13, UI.BAD, true);
        historyWarning.setVisible(false);
        historyWarning.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        panel.add(UI.north(topContainer, UI.north(historyWarning, UI.toolbar(searchPanel, buttons))), BorderLayout.NORTH);
        panel.add(UI.tableCard(historyTable), BorderLayout.CENTER);

        return panel;
    }

    private JLabel historyWarning;

    /** One history entry, parsed. */
    private static final class LogEntry {
        String dateTime = "", action = "", grainType = "", change = "", details = "", user = "", batchId = "";
        double quantity;
    }

    private static java.util.List<LogEntry> parseHistory() {
        java.util.List<LogEntry> list = new ArrayList<>();
        String history = HistoryLog.readHistory();
        if (history.equals("No history available.") || history.equals("Unable to read history.")) return list;
        for (String block : history.split("----------------------------------------")) {
            if (block.trim().isEmpty()) continue;
            LogEntry e = new LogEntry();
            for (String line : block.trim().split("\\r?\\n")) {
                if (line.startsWith("[") && line.indexOf("]") > 0) {
                    e.dateTime = line.substring(1, line.indexOf("]"));
                    e.action = line.substring(line.indexOf("]") + 1).trim();
                } else if (line.startsWith("Grain Type:")) e.grainType = line.substring(11).trim();
                else if (line.startsWith("Change:")) e.change = line.substring(7).trim();
                else if (line.startsWith("Details:")) e.details = line.substring(8).trim();
                else if (line.startsWith("User:")) e.user = line.substring(5).trim();
                else if (line.startsWith("Batch ID:")) e.batchId = line.substring(9).trim();
                else if (line.startsWith("Quantity:")) {
                    try { e.quantity = Double.parseDouble(line.replaceAll("[^0-9.]", "")); } catch (NumberFormatException ignored) {}
                }
            }
            list.add(e);
        }
        return list;
    }

    private static double changeAmount(String change, String positiveWord, String sign) {
        java.util.regex.Matcher m = Pattern.compile("^(?:" + positiveWord + " |" + Pattern.quote(sign) + ")([0-9.]+)").matcher(change);
        if (!m.find()) return 0;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * A one-page summary of a month, built from the history: grain received, released (with purposes),
     * lost to adjustments, written off and removed, per grain/variety, plus the current stock. Can be printed
     * (or saved as PDF with "Microsoft Print to PDF").
     */
    private void showMonthlyReport() {
        java.util.List<LogEntry> entries = parseHistory();

        java.util.TreeSet<java.time.YearMonth> months = new java.util.TreeSet<>(Comparator.reverseOrder());
        months.add(java.time.YearMonth.now());
        for (LogEntry e : entries) {
            try { months.add(java.time.YearMonth.parse(e.dateTime.substring(0, 7))); } catch (Exception ignored) {}
        }
        java.time.format.DateTimeFormatter monthName = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy");
        java.util.List<java.time.YearMonth> monthList = new ArrayList<>(months);
        String[] labels = new String[monthList.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = monthList.get(i).format(monthName);
        JComboBox<String> monthBox = UI.combo(labels);

        JEditorPane view = new JEditorPane("text/html", "");
        view.setEditable(false);
        Runnable render = () -> {
            java.time.YearMonth ym = monthList.get(Math.max(0, monthBox.getSelectedIndex()));
            view.setText(buildReport(entries, ym, ym.format(monthName)));
            view.setCaretPosition(0);
        };
        monthBox.addActionListener(e -> render.run());
        render.run();

        JScrollPane sp = new JScrollPane(view);
        sp.setPreferredSize(new Dimension(760, 520));
        JPanel top = UI.transparent(new BorderLayout(10, 0));
        top.add(UI.label("Month:", 13, UI.INK, true), BorderLayout.WEST);
        top.add(monthBox, BorderLayout.CENTER);
        JPanel content = UI.transparent(new BorderLayout(0, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);

        if (UI.showForm(this, content, "Monthly Stock Report", "Close", "Print", null)) {
            try {
                view.print();
            } catch (java.awt.print.PrinterException ex) {
                UI.showMessage(this, "Printing failed: " + ex.getMessage(), "Print Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String buildReport(java.util.List<LogEntry> entries, java.time.YearMonth ym, String title) {
        String prefix = ym.toString(); // yyyy-MM
        java.util.LinkedHashMap<String, String> names = new java.util.LinkedHashMap<>();
        java.util.Map<String, double[]> rows = new java.util.HashMap<>(); // received, released, lost, gained, written off, removed
        java.util.Map<String, Double> purposes = new java.util.TreeMap<>();
        double transferred = 0;
        int transfers = 0;

        for (LogEntry e : entries) {
            if (!e.dateTime.startsWith(prefix)) continue;
            String a = e.action.toUpperCase();
            if (a.startsWith("TRANSFERRED OUT")) { transferred += e.quantity; transfers++; continue; }

            int col;
            double kg = e.quantity;
            if (a.startsWith("BATCH ADDED")) col = 0;
            else if (a.startsWith("GRAIN RELEASED") || a.startsWith("GRAIN PARTIALLY RELEASED")) col = 1;
            else if (a.startsWith("STOCK ADJUSTED")) {
                double loss = changeAmount(e.change, "Loss", "-");
                double gain = changeAmount(e.change, "Gain", "+");
                if (loss > 0) { col = 2; kg = loss; } else if (gain > 0) { col = 3; kg = gain; } else continue;
            } else if (a.startsWith("BATCH WRITTEN OFF") || a.startsWith("PARTIAL WRITE-OFF")) col = 4;
            else if (a.startsWith("BATCH REMOVED (INVENTORY CLEARED)")) col = 5;
            else continue;

            String key = Batch.grainKey(e.grainType);
            names.putIfAbsent(key, displayGrainType(e.grainType));
            rows.computeIfAbsent(key, k -> new double[6])[col] += kg;

            if (col == 1) {
                String purpose = "Not recorded";
                int p = e.details.indexOf("Purpose: ");
                if (p >= 0) {
                    String rest = e.details.substring(p + 9);
                    purpose = rest.contains(" | ") ? rest.substring(0, rest.indexOf(" | ")) : rest;
                }
                purposes.merge(purpose, kg, Double::sum);
            }
        }

        double[] total = new double[6];
        StringBuilder table = new StringBuilder();
        java.util.List<String> keys = new ArrayList<>(names.keySet());
        keys.sort(Comparator.comparing(names::get));
        for (String k : keys) {
            double[] r = rows.get(k);
            for (int i = 0; i < 6; i++) total[i] += r[i];
            table.append("<tr><td>").append(esc(names.get(k))).append("</td>");
            for (double v : r) table.append("<td align='right'>").append(v == 0 ? "–" : Batch.kg(v)).append("</td>");
            table.append("</tr>");
        }
        if (keys.isEmpty()) table.append("<tr><td colspan='7'><i>No stock movements recorded this month.</i></td></tr>");

        StringBuilder purposeRows = new StringBuilder();
        for (java.util.Map.Entry<String, Double> p : purposes.entrySet()) {
            purposeRows.append("<tr><td>").append(esc(p.getKey())).append("</td><td align='right'>").append(Batch.kg(p.getValue())).append(" kg</td></tr>");
        }

        double usable = 0, expired = 0;
        for (Batch b : batches) { if (b.isExpired()) expired += b.getQuantity(); else usable += b.getQuantity(); }
        StringBuilder stockRows = new StringBuilder();
        for (StorageUnit w : storageUnits) {
            stockRows.append("<tr><td>").append(w.getName()).append("</td><td align='right'>").append(Batch.kg(w.getTotalQuantity()))
                    .append(" kg</td><td align='right'>").append(Math.round(w.getFillFraction() * 100)).append("%</td></tr>");
        }

        double lost = total[2] + total[4];
        return "<html><body style='font-family:sans-serif;font-size:11px'>"
                + "<h2>TITA Grain Storage: monthly report, " + title + "</h2>"
                + "<p>Prepared " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + " by " + esc(currentUser.getUsername()) + ". Figures come from the transaction history.</p>"
                + "<table cellpadding='4'><tr>"
                + "<td><b>Received</b><br><font size='5'>" + Batch.kg(total[0]) + " kg</font></td>"
                + "<td><b>Released</b><br><font size='5'>" + Batch.kg(total[1]) + " kg</font></td>"
                + "<td><b>Lost</b> (shrinkage, spillage, write-off)<br><font size='5' color='#C93B2B'>" + Batch.kg(lost) + " kg</font></td>"
                + "<td><b>Moved between warehouses</b><br><font size='5'>" + Batch.kg(transferred) + " kg</font> (" + transfers + ")</td>"
                + "</tr></table>"
                + "<h3>By grain and variety (kg)</h3>"
                + "<table border='1' cellspacing='0' cellpadding='4' width='100%'>"
                + "<tr bgcolor='#E8F3EC'><th align='left'>Grain / variety</th><th>Received</th><th>Released</th><th>Adjustment loss</th>"
                + "<th>Adjustment gain</th><th>Written off</th><th>Removed by clearing</th></tr>"
                + table
                + "<tr bgcolor='#F4F6F4'><td><b>Total</b></td>" + totalsRow(total) + "</tr></table>"
                + "<h3>Releases by purpose</h3>"
                + (purposeRows.length() == 0 ? "<p><i>No releases this month.</i></p>"
                : "<table border='1' cellspacing='0' cellpadding='4'>" + purposeRows + "</table>")
                + "<h3>Stock now</h3>"
                + "<p>Usable: <b>" + Batch.kg(usable) + " kg</b>. Expired (to write off): <b>" + Batch.kg(expired) + " kg</b>.</p>"
                + "<table border='1' cellspacing='0' cellpadding='4'><tr bgcolor='#E8F3EC'><th align='left'>Warehouse</th><th>Stored</th><th>Full</th></tr>"
                + stockRows + "</table>"
                + "</body></html>";
    }

    private static String totalsRow(double[] total) {
        StringBuilder sb = new StringBuilder();
        for (double v : total) sb.append("<td align='right'><b>").append(v == 0 ? "–" : Batch.kg(v)).append("</b></td>");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void updateHistory() {
        if (historyModel == null) return;

        // Checks the fingerprints and records any tampering once, so the warning is part of the audit trail.
        String problem = HistoryLog.reportTampering();
        if (historyWarning != null) {
            historyWarning.setVisible(problem != null);
            historyWarning.setText(problem == null ? " " : "WARNING: the history file was changed outside TITA. " + problem);
        }

        historyModel.setRowCount(0);
        String history = HistoryLog.readHistory();

        if (history.equals("No history available.") || history.equals("Unable to read history.")) return;

        String[] entries = history.split("----------------------------------------");

        for (String entry : entries) {
            if (entry.trim().isEmpty()) continue;

            String dateTime = "", action = "", batchId = "", grainType = "", quantity = "", sacksVal = "0 sacks", change = "", storageUnit = "";
            String user = "", details = "";
            String[] lines = entry.trim().split("\\r?\\n");

            for (String line : lines) {
                if (line.startsWith("[")) {
                    int closingBracket = line.indexOf("]");
                    if (closingBracket > 0) {
                        dateTime = line.substring(1, closingBracket);
                        action = line.substring(closingBracket + 2);
                    }
                }
                if (line.startsWith("Batch ID:")) batchId = line.substring("Batch ID:".length()).trim();
                if (line.startsWith("Grain Type:")) grainType = line.substring("Grain Type:".length()).trim();
                if (line.startsWith("Quantity:")) quantity = line.substring("Quantity:".length()).trim();
                if (line.startsWith("Sacks:")) sacksVal = line.substring("Sacks:".length()).trim();
                if (line.startsWith("Change:")) change = line.substring("Change:".length()).trim();
                if (line.startsWith("Details:")) details = line.substring("Details:".length()).trim();
                if (line.startsWith("User:")) user = line.substring("User:".length()).trim();
                if (line.startsWith("Storage Unit:")) storageUnit = line.substring("Storage Unit:".length()).trim();
            }

            if (storageUnit.toLowerCase().contains("silo")) {
                storageUnit = storageUnit.replaceAll("(?i)Silo", "Warehouse");
            } else if (storageUnit.equalsIgnoreCase("All Silos")) {
                storageUnit = "All Warehouses";
            }

            if (sacksVal.equals("0 sacks") && quantity.contains("(") && quantity.contains(")")) {
                int start = quantity.indexOf("(");
                int end = quantity.indexOf(")");
                sacksVal = quantity.substring(start + 1, end);
                quantity = quantity.substring(0, start).trim();
            }

            historyModel.addRow(new Object[]{ dateTime, user.isEmpty() ? "–" : user, action, batchId, grainType,
                    quantity, sacksVal, change, details, storageUnit });
        }
    }

    private void updateInventory() {
        if (inventoryModel == null) return;
        inventoryModel.setRowCount(0);
        for (Batch batch : batches) {
            inventoryModel.addRow(new Object[]{
                    batch.getId(),
                    batch.getGrainType(),
                    batch.getQuantity(),
                    batch.getSacks(),
                    batch.getSackSize(),
                    batch.getHarvestDate(),
                    batch.getShelfLife(),
                    batch.getExpiryDate(),
                    batch.getStorageUnit(),
                    batch.getStatus()
            });
        }
    }

    private void updateShelfLife() {
        if (shelfModel == null) return;
        shelfModel.setRowCount(0);
        for (Batch batch : batches) {
            shelfModel.addRow(new Object[]{
                    batch.getId(),
                    batch.getGrainType(),
                    batch.getStorageUnit(),
                    batch.getRemainingDays(),
                    batch.getStatus()
            });
        }
    }

    private void updateDashboard() {
        // Expired grain is counted separately so the headline number is only stock that can still be released.
        double usable = 0;
        double expiredKg = 0;
        int usableSacks = 0;
        int warningCount = 0;
        int expiredCount = 0;
        java.util.LinkedHashMap<String, String> varietyNames = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> varietyTotals = new java.util.HashMap<>();

        for (Batch batch : batches) {
            long days = batch.getRemainingDays();
            if (days >= 0 && days <= Batch.ALERT_DAYS) warningCount++;
            if (days < 0) {
                expiredCount++;
                expiredKg += batch.getQuantity();
                continue;
            }
            usable += batch.getQuantity();
            usableSacks += batch.getSacks();
            String key = Batch.grainKey(batch.getGrainType());
            varietyNames.putIfAbsent(key, displayGrainType(batch.getGrainType()));
            varietyTotals.merge(key, batch.getQuantity(), Double::sum);
        }

        if (totalGrainLabel != null) totalGrainLabel.setText(UI.big(Batch.kg(usable), "kg"));
        if (totalSacksLabel != null) {
            totalSacksLabel.setText(usableSacks + (usableSacks == 1 ? " sack" : " sacks")
                    + (expiredKg > 0 ? " · " + Batch.kg(expiredKg) + " kg expired not counted" : " in storage"));
        }
        if (expiredLabel != null) expiredLabel.setText(String.valueOf(expiredCount));
        if (expiredCard != null) {
            if (expiredCount > 0) expiredCard.tone(UI.BAD_BG, new Color(0xF2B8B0));
            else expiredCard.tone(UI.CARD, UI.LINE);
        }
        if (totalBatchLabel != null) totalBatchLabel.setText(String.valueOf(batches.size()));
        if (expiringLabel != null) expiringLabel.setText(String.valueOf(warningCount));
        if (expiringCard != null) {
            if (warningCount > 0) expiringCard.tone(new Color(0xFFF6E3), new Color(0xF0D79F));
            else expiringCard.tone(UI.CARD, UI.LINE);
        }

        if (stockPanel != null) {
            stockPanel.removeAll();
            for (StorageUnit w : storageUnits) {
                double q = w.getTotalQuantity();
                stockPanel.add(UI.stockRow(w.getName(),
                        Batch.kg(q) + " / " + Batch.kg(w.getCapacity()) + " kg (" + Math.round(w.getFillFraction() * 100) + "% full)",
                        w.getFillFraction()));
            }
            stockPanel.revalidate();
            stockPanel.repaint();
        }

        if (varietyPanel != null) {
            varietyPanel.removeAll();
            java.util.List<String> keys = new ArrayList<>(varietyTotals.keySet());
            keys.sort((a, b) -> Double.compare(varietyTotals.get(b), varietyTotals.get(a)));
            if (keys.isEmpty()) {
                varietyPanel.add(UI.label("No usable stock.", 13, UI.MUTED, false));
            }
            // Show the 5 largest; anything beyond that is summed into one row so the card stays compact.
            double others = 0;
            for (int i = 0; i < keys.size(); i++) {
                double q = varietyTotals.get(keys.get(i));
                if (i < 5 || keys.size() == 6) {
                    varietyPanel.add(UI.stockRow(varietyNames.get(keys.get(i)), Batch.kg(q) + " kg", usable > 0 ? q / usable : 0));
                } else {
                    others += q;
                }
            }
            if (others > 0) {
                varietyPanel.add(UI.stockRow("Other varieties (" + (keys.size() - 5) + ")", Batch.kg(others) + " kg", others / usable));
            }
            varietyPanel.revalidate();
            varietyPanel.repaint();
        }
    }

    /** "Rice (Dinorado)" is shown as "Dinorado (rice)"; plain "Rice" as "Unspecified (rice)". */
    private static String displayGrainType(String grainType) {
        String variety = Batch.varietyOf(grainType);
        return variety == null ? grainType.trim() : variety + " (rice)";
    }

    private void updateWarnings() {
        if (warningModel == null) return;
        warningModel.setRowCount(0);
        ArrayList<Batch> warningBatches = new ArrayList<>();

        for (Batch batch : batches) {
            if (batch.getRemainingDays() <= Batch.ALERT_DAYS) warningBatches.add(batch);
        }

        // Nearest expiry first: batches that can still be released (0 to 30 days left) come first, with the
        // fewest days left at the top. Expired batches (which can only be written off) follow at the end.
        warningBatches.sort((a, b) -> {
            boolean ae = a.isExpired(), be = b.isExpired();
            if (ae != be) return ae ? 1 : -1;
            return ae ? Long.compare(b.getRemainingDays(), a.getRemainingDays())
                      : Long.compare(a.getRemainingDays(), b.getRemainingDays());
        });

        for (Batch batch : warningBatches) {
            long days = batch.getRemainingDays();
            long abs = Math.abs(days);
            String daysText = (days < 0) ? "Expired (" + abs + (abs == 1 ? " day" : " days") + " ago)"
                    : days == 0 ? "Expires today" : days + (days == 1 ? " day" : " days") + " left";

            warningModel.addRow(new Object[]{
                    batch.getId(),
                    batch.getGrainType(),
                    batch.getStorageUnit(),
                    batch.getSacks() + " sacks",
                    batch.getExpiryDate(),
                    daysText,
                    batch.getStatus()
            });
        }

        if (warningCards != null && warningCardPanel != null) {
            if (warningModel.getRowCount() > 0) {
                warningCards.show(warningCardPanel, "table");
            } else {
                warningCards.show(warningCardPanel, "empty");
            }
            warningCardPanel.revalidate();
            warningCardPanel.repaint();
        }
    }

    private void releaseGrain() {
        String[] warehouseOptions = {"Warehouse 1", "Warehouse 2", "Warehouse 3"};
        JComboBox<String> warehouseComboBox = UI.combo(warehouseOptions);

        int warehouseChoice = UI.showConfirm(
                this,
                warehouseComboBox,
                "Select Storage Unit for Release",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (warehouseChoice != JOptionPane.OK_OPTION) return;

        String warehouseName = (String) warehouseComboBox.getSelectedItem();
        StorageUnit warehouse = findStorageUnit(warehouseName);

        if (warehouse == null || warehouse.getGrainQueue().isEmpty()) {
            UI.showMessage(this, "No grain stored in " + warehouseName + ".", "Empty Warehouse", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Group the stock by grain/variety. Batches are matched with Batch.grainKey, so "Dinorado" and
        // "dinorado", or "Rice" and "Rice (Unspecified)", are one choice and are released together.
        // Expired batches are never released; they are counted separately and must be written off.
        java.util.LinkedHashMap<String, String> typeNames = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> usableByType = new java.util.HashMap<>();
        java.util.Map<String, Double> expiredByType = new java.util.HashMap<>();
        for (Batch b : warehouse.getGrainQueue().getQueue()) {
            String key = Batch.grainKey(b.getGrainType());
            typeNames.putIfAbsent(key, b.getGrainType().trim().equalsIgnoreCase("Rice") ? "Rice (Unspecified)" : b.getGrainType().trim());
            (b.isExpired() ? expiredByType : usableByType).merge(key, b.getQuantity(), Double::sum);
        }

        java.util.List<String> keys = new ArrayList<>(typeNames.keySet());
        String[] labels = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            double usable = Batch.round(usableByType.getOrDefault(keys.get(i), 0.0));
            labels[i] = typeNames.get(keys.get(i)) + "  (" + Batch.kg(usable) + " kg available)";
        }

        JComboBox<String> grainTypeBox = UI.combo(labels);
        JLabel availableLabel = new JLabel();
        Runnable showAvailable = () -> {
            String key = keys.get(Math.max(0, grainTypeBox.getSelectedIndex()));
            double usable = Batch.round(usableByType.getOrDefault(key, 0.0));
            double expired = Batch.round(expiredByType.getOrDefault(key, 0.0));
            availableLabel.setText(Batch.kg(usable) + " kg" + (expired > 0 ? " (" + Batch.kg(expired) + " kg expired, excluded)" : ""));
        };
        grainTypeBox.addActionListener(e -> showAvailable.run());
        showAvailable.run();

        JTextField amountField = new JTextField();
        ((AbstractDocument) amountField.getDocument()).setDocumentFilter(new NumberFilter(true));
        JComboBox<String> purposeBox = UI.combo(RELEASE_PURPOSES);
        JTextField otherPurposeField = new JTextField();
        JLabel otherPurposeLabel = new JLabel("Specify 'Other' purpose:");
        otherPurposeField.setEnabled(false);
        otherPurposeLabel.setEnabled(false);
        purposeBox.addActionListener(e -> {
            boolean other = "Other".equals(purposeBox.getSelectedItem());
            otherPurposeField.setEnabled(other);
            otherPurposeLabel.setEnabled(other);
            if (other) otherPurposeField.requestFocusInWindow();
        });
        JTextField recipientField = new JTextField();
        JTextField referenceField = new JTextField();

        JPanel releasePanel = new JPanel(new GridLayout(0, 2, 10, 8));
        releasePanel.add(new JLabel("Grain / variety:"));
        releasePanel.add(grainTypeBox);
        releasePanel.add(new JLabel("Available:"));
        releasePanel.add(availableLabel);
        releasePanel.add(new JLabel("Quantity to release (kg):"));
        releasePanel.add(amountField);
        releasePanel.add(new JLabel("Purpose:"));
        releasePanel.add(purposeBox);
        releasePanel.add(otherPurposeLabel);
        releasePanel.add(otherPurposeField);
        releasePanel.add(new JLabel("Recipient / destination:"));
        releasePanel.add(recipientField);
        releasePanel.add(new JLabel("Reference no. (optional):"));
        releasePanel.add(referenceField);

        if (!UI.showForm(this, releasePanel, "Release Grain from " + warehouseName, "Cancel", "Release", amountField)) return;

        String targetKey = keys.get(Math.max(0, grainTypeBox.getSelectedIndex()));
        String targetFullGrainType = typeNames.get(targetKey);
        double availableForType = Batch.round(usableByType.getOrDefault(targetKey, 0.0));
        double expiredForType = Batch.round(expiredByType.getOrDefault(targetKey, 0.0));

        if (availableForType <= 0) {
            UI.showMessage(this, "All " + targetFullGrainType + " in " + warehouseName + " is expired (" + Batch.kg(expiredForType) + " kg).\n"
                    + "Expired grain cannot be released. Use \"Write off\" to dispose of it.", "Nothing to Release", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String amountText = amountField.getText().trim();
        if (!amountText.matches("\\d+(\\.\\d+)?|\\d*\\.\\d+")) {
            UI.showMessage(this, "Quantity must be a positive number.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double amount;
        try {
            amount = Batch.round(Double.parseDouble(amountText));
        } catch (NumberFormatException ex) { // e.g. hundreds of digits, which becomes "Infinity"
            amount = -1;
        }
        if (amount <= 0 || amount > availableForType) {
            UI.showMessage(this, "Invalid quantity entered or insufficient stock.\nAvailable: " + Batch.kg(availableForType) + " kg", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String recipient = cleanText(recipientField.getText());
        if (recipient.isEmpty()) {
            UI.showMessage(this, "Please enter who received the grain or where it was sent.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String purpose = (String) purposeBox.getSelectedItem();
        if ("Other".equals(purpose)) {
            String custom = cleanText(otherPurposeField.getText());
            if (custom.isEmpty()) {
                UI.showMessage(this, "Please describe the purpose of this release in the 'Other' box.", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            purpose = "Other: " + custom;
        }
        String reference = cleanText(referenceField.getText());
        String releaseDetails = "Purpose: " + purpose + " | Recipient: " + recipient
                + (reference.isEmpty() ? "" : " | Ref: " + reference);

        double remainingAmount = amount;
        PriorityQueue<Batch> tempQueue = new PriorityQueue<>(Comparator.comparing(Batch::getExpiryDate));
        tempQueue.addAll(warehouse.getGrainQueue().getQueue());

        while (remainingAmount > 0 && !tempQueue.isEmpty()) {
            Batch currentBatch = tempQueue.poll();
            if (!Batch.grainKey(currentBatch.getGrainType()).equals(targetKey) || currentBatch.isExpired()) {
                continue;
            }

            double releasedQuantity;
            if (currentBatch.getQuantity() <= remainingAmount) {
                releasedQuantity = currentBatch.getQuantity();
                remainingAmount = Batch.round(remainingAmount - releasedQuantity);
                HistoryLog.log("GRAIN RELEASED (FEFO)", currentBatch.getId(), currentBatch.getGrainType(), releasedQuantity,
                        currentBatch.getSackSize(), warehouse.getName(),
                        "Released " + HistoryLog.plainKg(releasedQuantity) + " kg; batch emptied and removed", releaseDetails);
                batches.remove(currentBatch);
            } else {
                releasedQuantity = remainingAmount;
                double before = currentBatch.getQuantity();
                currentBatch.setQuantity(before - releasedQuantity);
                remainingAmount = 0;
                HistoryLog.log("GRAIN PARTIALLY RELEASED (FEFO)", currentBatch.getId(), currentBatch.getGrainType(), releasedQuantity,
                        currentBatch.getSackSize(), warehouse.getName(),
                        "Released " + HistoryLog.plainKg(releasedQuantity) + " kg (" + HistoryLog.plainKg(before) + " kg to "
                                + HistoryLog.plainKg(currentBatch.getQuantity()) + " kg); " + HistoryLog.plainKg(currentBatch.getQuantity()) + " kg left in batch",
                        releaseDetails);
            }
        }

        // Rebuild queue from scratch to fix priority heap corruption after partial updates
        warehouse.getGrainQueue().getQueue().clear();
        for (Batch b : batches) {
            if (b.getStorageUnit() != null && b.getStorageUnit().equalsIgnoreCase(warehouse.getName())) {
                warehouse.getGrainQueue().addBatch(b);
            }
        }

        saveData();
        UI.showMessage(this, "FEFO release completed successfully.\nReleased: " + Batch.kg(amount) + " kg of " + targetFullGrainType
                + "\nTo: " + recipient + " (" + purpose + ")");

        refreshAll();
    }

    /**
     * Free text typed by the user (recipient, reasons, notes): trimmed, single spaces, at most 100 characters,
     * and "|" replaced, because "|" separates the fields of a history Details line.
     */
    private static String cleanText(String s) {
        String t = s == null ? "" : s.replace('|', '/').replaceAll("\\s+", " ").trim();
        return t.length() > 100 ? t.substring(0, 100).trim() : t;
    }

    private Batch findBatch(String id) {
        for (Batch batch : batches) {
            if (batch.getId().equalsIgnoreCase(id)) return batch;
        }
        return null;
    }

    private StorageUnit findStorageUnit(String name) {
        for (StorageUnit warehouse : storageUnits) {
            if (warehouse.getName().equalsIgnoreCase(name)) return warehouse;
        }
        return null;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(TITA::startSession);
    }
}