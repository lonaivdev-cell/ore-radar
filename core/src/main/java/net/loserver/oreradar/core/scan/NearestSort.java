package net.loserver.oreradar.core.scan;

/**
 * Selection and sorting over parallel (distance, packed position) arrays, without boxing or
 * comparator objects, so a scan of a very common block stays cheap (ported from 1.0).
 */
public final class NearestSort {
    private NearestSort() {}

    /** Rearranges key/val so their k smallest keys come first, in no particular order. */
    public static void selectSmallest(double[] key, long[] val, int n, int k) {
        if (k <= 0 || k >= n) return;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int p = partition(key, val, lo, hi);
            if (p == k - 1) return;
            if (p < k - 1) lo = p + 1;
            else hi = p - 1;
        }
    }

    /** Sorts key/val[lo..hi] (inclusive) by key, ascending. */
    public static void sortByKey(double[] key, long[] val, int lo, int hi) {
        while (hi - lo > 16) {
            int p = partition(key, val, lo, hi);
            // Recurse into the smaller side so the stack stays O(log n).
            if (p - lo < hi - p) {
                sortByKey(key, val, lo, p - 1);
                lo = p + 1;
            } else {
                sortByKey(key, val, p + 1, hi);
                hi = p - 1;
            }
        }
        for (int i = lo + 1; i <= hi; i++) {
            double k = key[i];
            long v = val[i];
            int j = i - 1;
            while (j >= lo && key[j] > k) {
                key[j + 1] = key[j];
                val[j + 1] = val[j];
                j--;
            }
            key[j + 1] = k;
            val[j + 1] = v;
        }
    }

    /** Lomuto partition around the median of three; returns the pivot's final index. */
    private static int partition(double[] key, long[] val, int lo, int hi) {
        int mid = (lo + hi) >>> 1;
        if (key[mid] < key[lo]) swap(key, val, mid, lo);
        if (key[hi] < key[lo]) swap(key, val, hi, lo);
        if (key[mid] < key[hi]) swap(key, val, mid, hi); // the median is now at hi
        double pivot = key[hi];
        int store = lo;
        for (int i = lo; i < hi; i++) {
            if (key[i] < pivot) swap(key, val, i, store++);
        }
        swap(key, val, store, hi);
        return store;
    }

    private static void swap(double[] key, long[] val, int i, int j) {
        double k = key[i];
        key[i] = key[j];
        key[j] = k;
        long v = val[i];
        val[i] = val[j];
        val[j] = v;
    }
}
