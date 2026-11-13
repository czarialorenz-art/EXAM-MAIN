package data;

import java.io.Serializable;
import java.util.HashMap;

/** Generates readable sequential IDs such as "STU-0001", one counter per prefix. */
public class IdGenerator implements Serializable {
    private static final long serialVersionUID = 1L;

    private final HashMap<String, Integer> counters = new HashMap<>();

    public synchronized String next(String prefix) {
        int n = counters.merge(prefix, 1, Integer::sum);
        return String.format("%s-%04d", prefix, n);
    }

    /** Makes sure future IDs are above an existing ID (used if ids.dat is lost or restored). */
    public synchronized void ensureAbove(String id) {
        if (id == null) {
            return;
        }
        int dash = id.lastIndexOf('-');
        if (dash <= 0) {
            return;
        }
        try {
            int n = Integer.parseInt(id.substring(dash + 1));
            counters.merge(id.substring(0, dash), n, Math::max);
        } catch (NumberFormatException ignored) {
            // Not one of our generated IDs.
        }
    }
}
