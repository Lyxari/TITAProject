package TITAProject;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class Batch implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_SACK_SIZE = 50;

    // A batch is "Expiring Soon", and appears in the Shelf-life alerts, this many days before its expiry date.
    public static final int ALERT_DAYS = 30;

    private static int counter = 1;

    private String id;
    private String grainType;
    private double quantity;
    private LocalDate harvestDate;
    private int shelfLife;
    private String storageUnit;
    // Added later: batches saved by older versions load with 0 here, which means the default 50 kg.
    private int sackSize;

    public Batch(String id, String grainType, double quantity, LocalDate harvestDate, int shelfLife, String storageUnit, int sackSize) {
        this.id = id;
        this.grainType = grainType;
        this.quantity = round(quantity);
        this.harvestDate = harvestDate;
        this.shelfLife = shelfLife;
        this.storageUnit = storageUnit;
        this.sackSize = sackSize;
    }

    public Batch(String id, String grainType, double quantity, LocalDate harvestDate, int shelfLife, String storageUnit) {
        this(id, grainType, quantity, harvestDate, shelfLife, storageUnit, DEFAULT_SACK_SIZE);
    }

    /**
     * Comparison key for grain types: ignores capitals and extra spaces, and treats plain "Rice"
     * the same as "Rice (Unspecified)". Two batches with the same key are the same variety.
     */
    public static String grainKey(String grainType) {
        String key = grainType == null ? "" : grainType.trim().replaceAll("\\s+", " ").toLowerCase();
        key = key.replace("( ", "(").replace(" )", ")");
        return key.equals("rice") ? "rice (unspecified)" : key;
    }

    /** "Rice (Dinorado)" gives "Dinorado", plain "Rice" gives "Unspecified", other grains give null. */
    public static String varietyOf(String grainType) {
        String t = grainType == null ? "" : grainType.trim();
        if (t.equalsIgnoreCase("Rice")) return "Unspecified";
        if (t.regionMatches(true, 0, "Rice (", 0, 6) && t.endsWith(")")) return t.substring(6, t.length() - 1).trim();
        return null;
    }

    public Batch(String grainType, double quantity, LocalDate harvestDate, int shelfLife, String storageUnit) {
        this(String.format("B%03d", counter++), grainType, quantity, harvestDate, shelfLife, storageUnit);
    }

    public static void setCounter(int newCounter) {
        counter = newCounter;
    }

    // Weights are kept to 2 decimal places (10 g) so sums like 10.1 + 20.2 + 30.3 stay exact.
    public static double round(double kg) {
        return BigDecimal.valueOf(kg).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static String kg(double kg) {
        return new DecimalFormat("#,##0.##").format(round(kg));
    }

    public static int sacksFor(double kg, int sackSize) {
        return (int) Math.ceil(round(kg) / (sackSize > 0 ? sackSize : DEFAULT_SACK_SIZE));
    }

    public String getId() {
        return id;
    }

    public String getGrainType() {
        return grainType;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = round(quantity);
    }

    public int getSackSize() {
        return sackSize > 0 ? sackSize : DEFAULT_SACK_SIZE;
    }

    public int getSacks() {
        return sacksFor(quantity, getSackSize());
    }

    public LocalDate getHarvestDate() {
        return harvestDate;
    }

    public int getShelfLife() {
        return shelfLife;
    }

    public String getStorageUnit() {
        return storageUnit;
    }

    public void setStorageUnit(String storageUnit) {
        this.storageUnit = storageUnit;
    }

    public LocalDate getExpiryDate() {
        return harvestDate.plusDays(shelfLife);
    }

    public long getRemainingDays() {
        return ChronoUnit.DAYS.between(LocalDate.now(), getExpiryDate());
    }

    public boolean isExpired() {
        return getRemainingDays() < 0;
    }

    public String getStatus() {
        long days = getRemainingDays();
        if (days < 0) return "Expired";
        if (days <= ALERT_DAYS) return "Expiring Soon";
        return "Good";
    }
}
