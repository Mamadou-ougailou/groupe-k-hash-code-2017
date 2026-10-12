package solver.ils;

public final class Greedy {

    public static Solution run(Instance in, Book book, boolean verbose) {
        final int V = in.V, C = in.C, R = in.R;
        Solution sol = new Solution(in);
        book.reset();

        long t0 = System.nanoTime();

        long[] gain = new long[C * V];
        for (int r = 0; r < R; r++) {
            int e = in.reqEndpoint[r];
            int v = in.reqVideo[r];
            long n = in.reqCount[r];
            int ld = in.dcLatency[e];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
                int d = ld - in.epLat[i];
                if (d > 0) gain[in.epCache[i] * V + v] += n * d;
            }
        }

        int nCand = 0;
        for (int p = 0; p < gain.length; p++) if (gain[p] > 0) nCand++;

        MaxHeap heap = new MaxHeap(nCand);
        for (int p = 0; p < gain.length; p++) {
            if (gain[p] <= 0) continue;
            int v = p % V;
            heap.rawAdd((double) gain[p] / in.videoSize[v], p);
        }
        heap.heapify();
        gain = null;

        if (verbose)
            System.out.printf("  paires candidates : %d (sur %d theoriques)%n",
                    nCand, (long) C * V);

        long placed = 0, reeval = 0, pushBack = 0;
        while (heap.size() > 0) {
            int pid = heap.topVal();
            heap.pop();

            int c = pid / V, v = pid - c * V;
            if (sol.has(c, v)) continue;
            int sz = in.videoSize[v];
            if (sz > sol.remaining(c)) continue;

            long g = trueGain(in, book, c, v);
            reeval++;
            if (g <= 0) continue;

            double nk = (double) g / sz;
            if (heap.size() == 0 || nk >= heap.topKey()) {
                sol.add(c, v);
                book.onAdd(c, v);
                placed++;
            } else {
                heap.push(nk, pid);
                pushBack++;
            }
        }

        if (verbose)
            System.out.printf("  placements=%d reevaluations=%d repousses=%d | %.2fs%n",
                    placed, reeval, pushBack, (System.nanoTime() - t0) / 1e9);
        return sol;
    }

    private static long trueGain(Instance in, Book book, int c, int v) {
        final int C = in.C;
        long g = 0;
        for (int i = in.vidStart[v]; i < in.vidStart[v + 1]; i++) {
            int r = in.vidReq[i];
            int l = in.lat[in.reqEndpoint[r] * C + c];
            if (l < 0) continue;
            int d = book.best1[r] - l;
            if (d > 0) g += (long) in.reqCount[r] * d;
        }
        return g;
    }

    static final class MaxHeap {
        private double[] key;
        private int[] val;
        private int n;

        MaxHeap(int cap) { key = new double[Math.max(cap, 16)]; val = new int[Math.max(cap, 16)]; }

        int size() { return n; }
        double topKey() { return key[0]; }
        int topVal() { return val[0]; }

        void rawAdd(double k, int v) { key[n] = k; val[n] = v; n++; }

        void heapify() { for (int i = (n >>> 1) - 1; i >= 0; i--) siftDown(i); }

        void push(double k, int v) {
            if (n == key.length) grow();
            key[n] = k; val[n] = v;
            siftUp(n++);
        }

        void pop() {
            n--;
            key[0] = key[n]; val[0] = val[n];
            if (n > 0) siftDown(0);
        }

        private void grow() {
            int cap = key.length + (key.length >> 1) + 16;
            double[] a = new double[cap]; System.arraycopy(key, 0, a, 0, n); key = a;
            int[] b = new int[cap];       System.arraycopy(val, 0, b, 0, n); val = b;
        }

        private void siftUp(int i) {
            double k = key[i]; int v = val[i];
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (key[p] >= k) break;
                key[i] = key[p]; val[i] = val[p]; i = p;
            }
            key[i] = k; val[i] = v;
        }

        private void siftDown(int i) {
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
    }
}

