package TITAProject;

import java.io.Serializable;

public class StorageUnit implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private GrainQueue grainQueue;
    private double capacity;

    public StorageUnit(String name, double capacity) {
        this.name = name;
        this.grainQueue = new GrainQueue();
        this.capacity = capacity;
    }

    public String getName() {
        return name;
    }

    public GrainQueue getGrainQueue() {
        return grainQueue;
    }

    public double getTotalQuantity() {
        double total = 0;
        for (Batch batch : grainQueue.getQueue()) {
            total += batch.getQuantity();
        }
        return Batch.round(total);
    }

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = Batch.round(capacity);
    }

    public double getFreeSpace() {
        return Math.max(0, Batch.round(capacity - getTotalQuantity()));
    }

    public double getFillFraction() {
        return capacity > 0 ? getTotalQuantity() / capacity : 0;
    }

    public int getBatchCount() {
        return grainQueue.size();
    }
}
