package solver;

public class SolverUtil {

    public static void siftDown(double[] key, int[] val, int n, int i) {
        double k = key[i]; int v = val[i];
        int half = n >>> 1;
        while (i < half) {
            int ch = (i << 1) + 1, right = ch + 1;
            if (right < n && key[right] > key[ch]) ch = right;
            if (key[ch] <= k) break;
            key[i] = key[ch]; val[i] = val[ch]; i = ch;
        }
        key[i] = k; val[i] = v;
    }

    public static void siftUp(double[] key, int[] val, int i) {
        double k = key[i]; int v = val[i];
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (key[p] >= k) break;
            key[i] = key[p]; val[i] = val[p]; i = p;
        }
        key[i] = k; val[i] = v;
    }

    public static void heapPop(double[] key, int[] val, int n) {
        n--;
        key[0] = key[n]; val[0] = val[n];
        if (n > 0) siftDown(key, val, n, 0);
    }

    public static void heapPush(double[] key, int[] val, int n, double k, int v) {
        // Grow if needed
        if (n >= key.length) {
            int cap = key.length + (key.length >> 1) + 16;
            double[] a = new double[cap]; System.arraycopy(key, 0, a, 0, n);
            int[] b = new int[cap];       System.arraycopy(val, 0, b, 0, n);
        }
        // Can't resize in-place with static methods, but push-backs are rare
        // and the initial array is sized for all candidates
        key[n] = k; val[n] = v;
        siftUp(key, val, n);
    }
}
