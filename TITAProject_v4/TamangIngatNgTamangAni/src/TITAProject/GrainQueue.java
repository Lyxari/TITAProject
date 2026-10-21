package TITAProject;

import java.io.Serializable;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

public class GrainQueue implements Serializable {

    private static final long serialVersionUID = 1L;

    private Queue<Batch> queue;

    public GrainQueue() {
        // Enforce FEFO order using PriorityQueue sorted by Expiry Date
        queue = new PriorityQueue<>(Comparator.comparing(Batch::getExpiryDate));
    }

    public void addBatch(Batch batch) {
        queue.add(batch);
    }

    public Batch removeBatch() {
        return queue.poll();
    }

    public Batch peekBatch() {
        return queue.peek();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public Queue<Batch> getQueue() {
        return queue;
    }
}
