package TITAProject;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class UI {

    private UI() {}

    public static final Color SIDEBAR = new Color(0x1B4332);
    public static final Color PRIMARY = new Color(0x2D6A4F);
    public static final Color PRIMARY_DARK = new Color(0x1B4332);
    public static final Color ACCENT_GREEN = new Color(0x52B788);
    public static final Color BG = new Color(0xF4F6F4);
    public static final Color CARD = Color.WHITE;
    public static final Color LINE = new Color(0xD8DFD8);
    public static final Color FIELD_LINE = new Color(0xC5CDC6);
    public static final Color INK = new Color(0x1A2E22);
    public static final Color MUTED = new Color(0x5A6B60);
    public static final Color GOOD = new Color(0x2D6A4F);
    public static final Color GOOD_BG = new Color(0xE8F3EC);
    public static final Color WARN = new Color(0xB57B00);
    public static final Color WARN_BG = new Color(0xFEF3D6);
    public static final Color BAD = new Color(0xC93B2B);
    public static final Color BAD_BG = new Color(0xFDECEA);
    public static final Color SELECT = new Color(0xDCEDDF);

    private static final String FAMILY = pickFamily();
    private static boolean installed;
    private static boolean keyboard;

    private static String pickFamily() {
        Set<String> have = new HashSet<String>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String[] wanted = {"Segoe UI", "SF Pro Text", "Helvetica Neue", "Roboto", "Noto Sans", "Open Sans"};
        for (String f : wanted) {
            if (have.contains(f)) return f;
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size) {
        return new Font(FAMILY, style, 12).deriveFont(size);
    }

    static Graphics2D g2(Graphics g) {
        Graphics2D x = (Graphics2D) g.create();
        x.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        x.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        x.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return x;
    }

    public static void install() {
        if (installed) return;
        installed = true;
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        UIManager.put("Panel.background", CARD);
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Label.font", font(Font.PLAIN, 13));
        UIManager.put("Label.foreground", INK);
        UIManager.put("TextField.font", font(Font.PLAIN, 13));
        UIManager.put("TextField.foreground", INK);
        UIManager.put("TextField.background", CARD);
        UIManager.put("TextField.caretForeground", INK);
        UIManager.put("TextField.selectionBackground", SELECT);
        UIManager.put("TextField.selectionForeground", INK);
        UIManager.put("TextField.border", new FieldBorder(0, true));
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(AWTEvent ev) {
                if (ev.getID() == KeyEvent.KEY_PRESSED && ((KeyEvent) ev).getKeyCode() == KeyEvent.VK_TAB) keyboard = true;
                else if (ev.getID() == MouseEvent.MOUSE_PRESSED) keyboard = false;
            }
        }, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
    }

    public static void drawIcon(Graphics2D g, String name, double x, double y, double s, Color c) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.translate(x, y);
        g2.scale(s / 24.0, s / 24.0);
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "dashboard":
                g2.draw(new RoundRectangle2D.Double(3, 3, 7, 7, 2.5, 2.5));
                g2.draw(new RoundRectangle2D.Double(14, 3, 7, 7, 2.5, 2.5));
                g2.draw(new RoundRectangle2D.Double(3, 14, 7, 7, 2.5, 2.5));
                g2.draw(new RoundRectangle2D.Double(14, 14, 7, 7, 2.5, 2.5));
                break;
            case "inventory": {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 3); p.lineTo(20, 7.5); p.lineTo(20, 16.5); p.lineTo(12, 21);
                p.lineTo(4, 16.5); p.lineTo(4, 7.5); p.closePath();
                g2.draw(p);
                Path2D q = new Path2D.Double();
                q.moveTo(4, 7.5); q.lineTo(12, 12); q.lineTo(20, 7.5);
                q.moveTo(12, 12); q.lineTo(12, 21);
                g2.draw(q);
                break;
            }
            case "add":
                g2.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g2.drawLine(12, 8, 12, 16);
                g2.drawLine(8, 12, 16, 12);
                break;
            case "warehouse": {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 10); p.lineTo(12, 4); p.lineTo(21, 10);
                p.moveTo(5, 9); p.lineTo(5, 20); p.lineTo(19, 20); p.lineTo(19, 9);
                g2.draw(p);
                g2.draw(new RoundRectangle2D.Double(9, 13, 6, 7, 1, 1));
                break;
            }
            case "clock":
                g2.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g2.drawLine(12, 7, 12, 12);
                g2.drawLine(12, 12, 15, 14);
                break;
            case "history":
                g2.draw(new RoundRectangle2D.Double(5, 3, 14, 18, 3, 3));
                g2.drawLine(9, 9, 15, 9);
                g2.drawLine(9, 13, 15, 13);
                g2.drawLine(9, 17, 12, 17);
                break;
            case "alert": {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 3.5); p.lineTo(21.5, 20); p.lineTo(2.5, 20); p.closePath();
                g2.draw(p);
                g2.drawLine(12, 10, 12, 14);
                g2.fill(new Ellipse2D.Double(11.1, 16.1, 1.8, 1.8));
                break;
            }
            case "grain": {
                g2.drawLine(12, 22, 12, 3);
                double[] ys = {9.5, 14, 18.5};
                for (double yy : ys) {
                    g2.draw(new java.awt.geom.Line2D.Double(12, yy, 7.5, yy - 4));
                    g2.draw(new java.awt.geom.Line2D.Double(12, yy, 16.5, yy - 4));
                }
                break;
            }
            case "check": {
                Path2D p = new Path2D.Double();
                p.moveTo(5, 12.5); p.lineTo(10, 17.5); p.lineTo(19, 7);
                g2.draw(p);
                break;
            }
            case "search":
                g2.draw(new Ellipse2D.Double(4, 4, 12, 12));
                g2.drawLine(14, 14, 20, 20);
                break;
            default:
                break;
        }
        g2.dispose();
    }

    public static Image appIcon() {
        BufferedImage im = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g2(im.getGraphics());
        g.setColor(ACCENT_GREEN);
        g.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 18, 18));
        drawIcon(g, "grain", 13, 13, 38, SIDEBAR);
        g.dispose();
        return im;
    }

    public static JPanel transparent(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    public static JLabel label(String text, float size, Color color, boolean bold) {
        JLabel l = new JLabel(text);
        l.setFont(font(bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(color);
        return l;
    }

    public static JLabel fieldLabel(String text) {
        return label(text, 12.5f, MUTED, true);
    }

    public static String big(String number, String unit) {
        return "<html><span style='font-size:28px'>" + number + "</span> <span style='font-size:14px;color:#5A6B60'>"
                + unit + "</span></html>";
    }

    public static JPanel page() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(24, 28, 24, 28));
        return p;
    }

    public static JPanel header(JLabel title, String subtitle) {
        JPanel p = transparent(new BorderLayout(0, 4));
        title.setFont(font(Font.BOLD, 26));
        title.setForeground(INK);
        p.add(title, BorderLayout.NORTH);
        p.add(label(subtitle, 14, MUTED, false), BorderLayout.CENTER);
        p.setBorder(new EmptyBorder(0, 0, 20, 0));
        return p;
    }

    public static JPanel north(Component top, Component rest) {
        JPanel p = transparent(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(rest, BorderLayout.CENTER);
        return p;
    }

    public static JPanel toolbar(Component left, Component right) {
        JPanel t = transparent(new BorderLayout());
        t.setBorder(new EmptyBorder(0, 0, 14, 0));
        t.add(left, BorderLayout.WEST);
        t.add(right, BorderLayout.EAST);
        return t;
    }

    public static JPanel buttonRow() {
        return transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    }

    public static void cell(JPanel p, Component c, int x, int y) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        boolean isLabel = y % 2 == 0;
        g.insets = new Insets(isLabel ? (y == 0 ? 0 : 18) : 0, x == 1 ? 10 : 0, isLabel ? 6 : 0, x == 0 ? 10 : 0);
        p.add(c, g);
    }

    public static void onChange(JTextField f, final Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    public static class Card extends JPanel {
        private Color fill = CARD;
        private Color line = LINE;

        public Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(18, 20, 18, 20));
        }

        public void tone(Color fill, Color line) {
            this.fill = fill;
            this.line = line;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = g2(g);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 16, 16));
            g2.setColor(line);
            g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 16, 16));
            g2.dispose();
        }
    }

    public static Card card(LayoutManager lm) { return new Card(lm); }

    public static JComponent chip(final String icon, final Color fg, final Color bg, final int size) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = g2(g);
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Double(0, 0, size, size, 11, 11));
                drawIcon(g2, icon, size * 0.24, size * 0.24, size * 0.52, fg);
                g2.dispose();
            }
        };
        c.setPreferredSize(new Dimension(size, size));
        c.setMinimumSize(new Dimension(size, size));
        return c;
    }

    public static Card statCard(String title, JLabel value, JLabel caption, String icon, Color fg, Color chipBg) {
        Card c = card(new BorderLayout(0, 12));
        JPanel top = transparent(new BorderLayout());
        top.add(label(title, 13, MUTED, true), BorderLayout.WEST);
        top.add(chip(icon, fg, chipBg, 34), BorderLayout.EAST);
        value.setFont(font(Font.BOLD, 28));
        value.setForeground(INK);
        caption.setFont(font(Font.PLAIN, 12.5f));
        caption.setForeground(MUTED);
        JPanel mid = transparent(new GridLayout(2, 1, 0, 2));
        mid.add(value);
        mid.add(caption);
        c.add(top, BorderLayout.NORTH);
        c.add(mid, BorderLayout.CENTER);
        return c;
    }

    public static class Bar extends JComponent {
        private double f;

        public Bar(double fraction) {
            this.f = Math.max(0, Math.min(1, fraction));
            setPreferredSize(new Dimension(10, 8));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = g2(g);
            int h = getHeight();
            g2.setColor(new Color(0xE2E8E2));
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), h, h, h));
            if (f > 0) {
                g2.setColor(PRIMARY);
                g2.fill(new RoundRectangle2D.Double(0, 0, Math.max(h, getWidth() * f), h, h, h));
            }
            g2.dispose();
        }
    }

    public static JComponent stockRow(String name, String value, double fraction) {
        JPanel p = transparent(new BorderLayout(0, 7));
        p.setBorder(new EmptyBorder(0, 0, 4, 0));
        JPanel top = transparent(new BorderLayout());
        top.add(label(name, 13, INK, true), BorderLayout.WEST);
        top.add(label(value, 13, MUTED, false), BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        p.add(new Bar(fraction), BorderLayout.CENTER);
        return p;
    }

    public static class NavButton extends JButton {
        private final String icon;
        private boolean selected;
        private boolean hover;

        public NavButton(String text, String icon) {
            super(text);
            this.icon = icon;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFont(font(Font.PLAIN, 13f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(6, 12, 6, 14));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        public void setSelected(boolean s) {
            selected = s;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            FontMetrics fm = getFontMetrics(getFont());
            int calculatedWidth = 16 + 8 + fm.stringWidth(getText()) + 26;
            return new Dimension(Math.max(d.width, calculatedWidth), Math.max(d.height, 34));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = g2(g);
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                g2.setColor(new Color(255, 255, 255, 35));
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 8, 8));
            } else if (hover) {
                g2.setColor(new Color(255, 255, 255, 18));
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 8, 8));
            }
            Color fg = selected ? Color.WHITE : new Color(0xCBD6CE);
            drawIcon(g2, icon, 12, (h - 16) / 2.0, 16, selected ? ACCENT_GREEN : fg);
            g2.setFont(font(selected ? Font.BOLD : Font.PLAIN, 13f));
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), 36, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    public static JButton button(String text, String variant) {
        JButton b = new JButton(text);
        b.setUI(new FlatButtonUI(variant));
        return b;
    }

    public static JButton primary(String t) { return button(t, "primary"); }
    public static JButton secondary(String t) { return button(t, "secondary"); }
    public static JButton danger(String t) { return button(t, "danger"); }

    static final class FlatButtonUI extends BasicButtonUI {
        private final String variant;

        FlatButtonUI(String variant) { this.variant = variant; }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            AbstractButton b = (AbstractButton) c;
            b.setOpaque(false);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setRolloverEnabled(true);
            b.setFont(font(Font.BOLD, 13));
            b.setBorder(new EmptyBorder(9, 16, 9, 16));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            boolean on = m.isEnabled();
            boolean hot = on && (m.isRollover() || m.isArmed());
            Color bg = null;
            Color fg;
            Color bd = null;
            switch (variant) {
                case "primary":
                    bg = m.isPressed() ? PRIMARY_DARK.darker() : (hot ? PRIMARY_DARK : PRIMARY);
                    fg = Color.WHITE;
                    break;
                case "dangerSolid":
                    bg = hot ? BAD.darker() : BAD;
                    fg = Color.WHITE;
                    break;
                case "danger":
                    bg = hot ? BAD_BG : CARD;
                    bd = new Color(0xE9C4C0);
                    fg = BAD;
                    break;
                default:
                    bg = hot ? new Color(0xEEF3EE) : CARD;
                    bd = FIELD_LINE;
                    fg = INK;
                    break;
            }
            if (!on) {
                bg = new Color(0xE9EDEA);
                bd = null;
                fg = new Color(0x98A39B);
            }
            Graphics2D g2 = g2(g);
            int w = c.getWidth();
            int h = c.getHeight();
            if (bg != null) {
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Double(0, 0, w, h, 12, 12));
            }
            if (bd != null) {
                g2.setColor(bd);
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 12, 12));
            }
            g2.dispose();
            b.setForeground(fg);
            super.paint(g, c);
        }
    }

    public static class FieldBorder extends AbstractBorder {
        private final int extraLeft;
        private final boolean fillCorners;

        public FieldBorder(int extraLeft, boolean fillCorners) {
            this.extraLeft = extraLeft;
            this.fillCorners = fillCorners;
        }

        @Override
        public Insets getBorderInsets(Component c) { return new Insets(9, 13 + extraLeft, 9, 13); }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = g2(g);
            RoundRectangle2D rr = new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 12, 12);
            if (fillCorners && c.getParent() != null) {
                Area outer = new Area(new Rectangle(x, y, w, h));
                outer.subtract(new Area(rr));
                g2.setColor(c.getParent().getBackground());
                g2.fill(outer);
            }
            boolean focus = c.hasFocus();
            g2.setColor(focus ? PRIMARY : FIELD_LINE);
            g2.setStroke(new BasicStroke(focus ? 2f : 1f));
            g2.draw(focus ? new RoundRectangle2D.Double(x + 1, y + 1, w - 2, h - 2, 12, 12) : rr);
            g2.dispose();
        }
    }

    public static class HintField extends JTextField {
        private final String hint;
        private final boolean search;

        public HintField(String hint, boolean search) {
            this.hint = hint;
            this.search = search;
            setOpaque(false);
            setBorder(new FieldBorder(search ? 24 : 0, false));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D bgG = g2(g);
            bgG.setColor(isEnabled() ? CARD : BG);
            bgG.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 12, 12));
            bgG.dispose();
            super.paintComponent(g);
            Graphics2D g2 = g2(g);
            if (getText().isEmpty() && hint != null && !hint.isEmpty()) {
                g2.setColor(new Color(0x8C9A90));
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            }
            if (search) drawIcon(g2, "search", 13, (getHeight() - 17) / 2.0, 17, MUTED);
            g2.dispose();
        }
    }

    public static JTextField hintField(String hint) {
        JTextField f = new HintField(hint, false);
        f.setColumns(10);
        return f;
    }

    public static JTextField searchField(String hint) {
        JTextField f = new HintField(hint, true);
        f.setPreferredSize(new Dimension(260, 40));
        return f;
    }

    public static JComboBox<String> combo(String... items) {
        JComboBox<String> cb = new JComboBox<String>(items);
        cb.setUI(new ComboUI());
        return cb;
    }

    static final class ComboRenderer extends JLabel implements ListCellRenderer<Object> {
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foc) {
            setText(value == null ? "" : value.toString());
            setFont(font(Font.PLAIN, 13));
            setBorder(new EmptyBorder(9, 12, 9, 8));
            setOpaque(index >= 0);
            setBackground(sel ? SELECT : CARD);
            setForeground(INK);
            return this;
        }
    }

    static final class ComboUI extends BasicComboBoxUI {
        @Override
        @SuppressWarnings("unchecked")
        public void installUI(JComponent c) {
            super.installUI(c);
            JComboBox<Object> cb = (JComboBox<Object>) c;
            cb.setOpaque(false);
            cb.setBorder(new EmptyBorder(0, 0, 0, 0));
            cb.setFont(font(Font.PLAIN, 13));
            cb.setRenderer(new ComboRenderer());
            cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override
                public void paint(Graphics g) {
                    Graphics2D g2 = g2(g);
                    g2.setColor(MUTED);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2 - 2;
                    int cy = getHeight() / 2;
                    g2.drawPolyline(new int[]{cx - 4, cx, cx + 4}, new int[]{cy - 2, cy + 2, cy - 2}, 3);
                    g2.dispose();
                }
            };
            b.setBorder(null);
            b.setContentAreaFilled(false);
            b.setFocusable(false);
            return b;
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {}

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = g2(g);
            g2.setColor(c.isEnabled() ? CARD : BG);
            g2.fill(new RoundRectangle2D.Double(0, 0, c.getWidth(), c.getHeight(), 12, 12));
            boolean focus = c.hasFocus() || (popup != null && popup.isVisible());
            g2.setColor(focus ? PRIMARY : FIELD_LINE);
            g2.setStroke(new BasicStroke(focus ? 2f : 1f));
            g2.draw(new RoundRectangle2D.Double(focus ? 1 : 0.5, focus ? 1 : 0.5,
                    c.getWidth() - (focus ? 2 : 1), c.getHeight() - (focus ? 2 : 1), 12, 12));
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected ComboPopup createPopup() {
            return new BasicComboPopup(comboBox) {
                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setBorder(new LineBorder(LINE));
                }
            };
        }
    }

    static final class ScrollUI extends BasicScrollBarUI {
        private JButton none() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }
        @Override protected JButton createDecreaseButton(int o) { return none(); }
        @Override protected JButton createIncreaseButton(int o) { return none(); }
        @Override public Dimension getPreferredSize(JComponent c) { return new Dimension(10, 10); }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}
        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = g2(g);
            g2.setColor(isDragging ? new Color(0x95A399) : new Color(0xBEC9C1));
            g2.fill(new RoundRectangle2D.Double(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6));
            g2.dispose();
        }
    }

    public static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(null);
        sp.getViewport().setBackground(CARD);
        sp.getVerticalScrollBar().setUI(new ScrollUI());
        sp.getHorizontalScrollBar().setUI(new ScrollUI());
        if (view instanceof JTable) sp.setColumnHeaderView(((JTable) view).getTableHeader());
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getHorizontalScrollBar().setOpaque(false);
        return sp;
    }

    public static DefaultTableModel model(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override
            public Class<?> getColumnClass(int col) {
                for (int r = 0; r < getRowCount(); r++) {
                    Object v = getValueAt(r, col);
                    if (v != null) return v.getClass();
                }
                return Object.class;
            }
        };
    }

    private static final DecimalFormat NUM = new DecimalFormat("#,##0.##");

    static class CellRenderer extends DefaultTableCellRenderer {
        protected String text(Object v) {
            if (v == null) return "";
            if (v instanceof Double || v instanceof Float) return NUM.format(((Number) v).doubleValue());
            return v.toString();
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, text(v), sel, false, r, c);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setFont(font(Font.PLAIN, 13));
            setBackground(sel ? SELECT : CARD);
            setForeground(INK);
            setHorizontalAlignment(SwingConstants.LEFT);
            setIcon(null);
            return this;
        }
    }

    static final class DaysRenderer extends CellRenderer {
        @Override
        protected String text(Object v) {
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                long a = Math.abs(n);
                String unit = a == 1 ? " day" : " days";
                return n < 0 ? "Expired " + a + unit + " ago" : n + unit;
            }
            return super.text(v);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            String s = getText();
            boolean expired = s.startsWith("Expired");
            boolean soon = !expired && v instanceof Number && ((Number) v).longValue() <= Batch.ALERT_DAYS;
            setForeground(expired ? BAD : (soon ? WARN : INK));
            setFont(font(expired || soon ? Font.BOLD : Font.PLAIN, 13));
            return this;
        }
    }

    static final class DotIcon implements Icon {
        private final Color color;
        DotIcon(Color color) { this.color = color; }
        public int getIconWidth() { return 8; }
        public int getIconHeight() { return 8; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = g2(g);
            g2.setColor(color);
            g2.fill(new Ellipse2D.Double(x, y, 8, 8));
            g2.dispose();
        }
    }

    static final class ActionRenderer extends CellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            String s = getText().toUpperCase();
            Color k = s.contains("ADDED") ? GOOD : s.contains("RELEASED") ? new Color(0x3A6EA5)
                    : s.contains("ADJUST") ? WARN : s.contains("CLEARED") ? BAD : MUTED;
            setIcon(new DotIcon(k));
            setIconTextGap(10);
            return this;
        }
    }

    static Color[] tone(String status) {
        String s = status == null ? "" : status.trim();
        if (s.equalsIgnoreCase("Expired")) return new Color[]{BAD, BAD_BG};
        if (s.equalsIgnoreCase("Expiring Soon")) return new Color[]{WARN, WARN_BG};
        if (s.equalsIgnoreCase("Good")) return new Color[]{GOOD, GOOD_BG};
        return new Color[]{MUTED, new Color(0xEAEEEB)};
    }

    static final class PillRenderer extends JComponent implements TableCellRenderer {
        private String text = "";
        private Color fg = GOOD;
        private Color bg = GOOD_BG;
        private Color row = CARD;

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            text = v == null ? "" : v.toString();
            Color[] k = tone(text);
            fg = k[0]; bg = k[1];
            row = sel ? SELECT : CARD;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = g2(g);
            g2.setColor(row);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(font(Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            int h = 26;
            int w = fm.stringWidth(text) + 36;
            int x = 12;
            int y = (getHeight() - h) / 2;
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
            g2.setColor(fg);
            g2.fill(new Ellipse2D.Double(x + 11, getHeight() / 2.0 - 3, 6, 6));
            g2.drawString(text, x + 23, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    static final class HeaderRenderer extends DefaultTableCellRenderer {
        private int hoverColumn = -1;

        public void setHoverColumn(int col) {
            this.hoverColumn = col;
        }

        public int getHoverColumn() {
            return hoverColumn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v == null ? "" : v.toString(), false, false, r, c);
            setFont(font(Font.BOLD, 12.5f));
            setForeground(c == hoverColumn ? PRIMARY : MUTED);
            setBackground(c == hoverColumn ? new Color(0xEEF3EE) : CARD);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(0, 12, 0, 12)));
            return this;
        }
    }

    public static void styleTable(JTable t) {
        t.setRowHeight(40);
        t.setFont(font(Font.PLAIN, 13));
        t.setForeground(INK);
        t.setBackground(CARD);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(new Color(0xE8ECE8));
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setSelectionBackground(SELECT);
        t.setSelectionForeground(INK);
        t.setFillsViewportHeight(true);
        for (int i = 0; i < t.getColumnModel().getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(new CellRenderer());
        }
        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        final HeaderRenderer headerRenderer = new HeaderRenderer();
        h.setDefaultRenderer(headerRenderer);

        h.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                int col = h.columnAtPoint(e.getPoint());
                if (col != headerRenderer.getHoverColumn()) {
                    headerRenderer.setHoverColumn(col);
                    h.repaint();
                }
            }
        });
        h.addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) {
                headerRenderer.setHoverColumn(-1);
                h.repaint();
            }
        });

        h.setPreferredSize(new Dimension(10, 40));
        h.setBackground(CARD);
    }

    public static void showMessage(Component parent, Object msg) {
        show(parent, msg, "Message", JOptionPane.INFORMATION_MESSAGE, null, null, null);
    }

    public static void showMessage(Component parent, Object msg, String title, int type) {
        show(parent, msg, title, type, null, null, null);
    }

    public static int showConfirm(Component parent, Object msg, String title, int option, int type) {
        String[] labels = option == JOptionPane.YES_NO_OPTION ? new String[]{"No", "Yes"} : new String[]{"Cancel", "OK"};
        int[] results = option == JOptionPane.YES_NO_OPTION ? new int[]{JOptionPane.NO_OPTION, JOptionPane.YES_OPTION} : new int[]{JOptionPane.CANCEL_OPTION, JOptionPane.OK_OPTION};
        return show(parent, msg, title, type, labels, results, null);
    }

    /** Shows a dialog with custom content; returns true when the user clicks the OK button. */
    public static boolean showForm(Component parent, Component content, String title, String cancelLabel, String okLabel, Component focus) {
        int r = show(parent, content, title, JOptionPane.PLAIN_MESSAGE, new String[]{cancelLabel, okLabel},
                new int[]{JOptionPane.CANCEL_OPTION, JOptionPane.OK_OPTION}, focus);
        return r == JOptionPane.OK_OPTION;
    }

    public static String showInput(Component parent, Object msg) {
        JTextField f = hintField("");
        f.setPreferredSize(new Dimension(340, 40));
        JPanel p = transparent(new BorderLayout(0, 12));
        p.add(messageLabel(String.valueOf(msg)), BorderLayout.NORTH);
        p.add(f, BorderLayout.CENTER);
        int r = show(parent, p, null, JOptionPane.PLAIN_MESSAGE, new String[]{"Cancel", "OK"},
                new int[]{JOptionPane.CANCEL_OPTION, JOptionPane.OK_OPTION}, f);
        return r == JOptionPane.OK_OPTION ? f.getText() : null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JComponent messageLabel(String s) {
        JLabel l = new JLabel("<html><body style='width:320px'>" + esc(s).replace("\n", "<br>") + "</body></html>");
        l.setFont(font(Font.PLAIN, 14));
        l.setForeground(INK);
        return l;
    }

    public static JScrollPane tableCard(JTable t) {
        JScrollPane sp = scroll(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    public static TableCellRenderer days() {
        return new DaysRenderer();
    }

    public static TableCellRenderer pill() {
        return new PillRenderer();
    }

    public static TableCellRenderer action() {
        return new ActionRenderer();
    }
    
    private static int show(Component parent, Object msg, String title, int type, String[] labels, int[] results, Component focus) {
        Window owner = parent == null ? null : (parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent));
        final JDialog d = new JDialog(owner, title == null ? "TITA" : title, Dialog.ModalityType.APPLICATION_MODAL);
        final int[] out = {JOptionPane.CLOSED_OPTION};
        boolean isComp = msg instanceof Component;

        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(CARD);
        root.setBorder(new EmptyBorder(24, 26, 18, 26));

        JPanel col = transparent(new BorderLayout(0, 8));
        if (title != null && !"Message".equals(title)) col.add(label(title, 17, INK, true), BorderLayout.NORTH);
        col.add(isComp ? (Component) msg : messageLabel(String.valueOf(msg)), BorderLayout.CENTER);
        root.add(col, BorderLayout.CENTER);

        if (labels == null) {
            labels = new String[]{"OK"};
            results = new int[]{JOptionPane.OK_OPTION};
        }
        JPanel row = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton defaultBtn = null;
        for (int i = 0; i < labels.length; i++) {
            boolean last = i == labels.length - 1;
            String variant = !last ? "secondary" : "primary";
            final int res = results[i];
            JButton b = button(labels[i], variant);
            b.addActionListener(e -> { out[0] = res; d.dispose(); });
            row.add(b);
            if (last) defaultBtn = b;
        }
        root.add(row, BorderLayout.SOUTH);

        d.setContentPane(root);
        d.getRootPane().setDefaultButton(defaultBtn);
        d.setResizable(false);
        d.pack();
        d.setLocationRelativeTo(owner);
        if (focus != null) {
            d.addWindowListener(new WindowAdapter() {
                @Override public void windowOpened(WindowEvent e) { focus.requestFocusInWindow(); }
            });
        }
        d.setVisible(true);
        return out[0];
    }
}
