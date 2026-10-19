package solver.ils;

import java.util.Arrays;

public final class BlockKnapsack {

    private final Instance in;
    private final Solution sol;
    private final Book book;

    private final long[] gain;
    private final long[] sortKey;
    private final int[]  ord;
    private final int[]  core;
    public static final int MAX_DP_CAP = 2_000_000;

    private final int[]  chosen;
    private final int[]  gsel;
    private final int[]  prev;
    private long[]       dp;
    private long[]       take;
    private final int    vBits;
    private final long   vMask;

    public long fallbacks;

    public BlockKnapsack(Instance in, Solution sol, Book book) {
        this.in = in; this.sol = sol; this.book = book;
        gain    = new long[in.V];
        sortKey = new long[in.V];
        ord     = new int[in.V];
        core    = new int[in.V];
        chosen  = new int[in.V];
        gsel    = new int[in.V];
        prev    = new int[in.V];
        dp      = new long[Math.min(in.X + 1, 1024)];
        take    = new long[1024];
        vBits   = 32 - Integer.numberOfLeadingZeros(Math.max(1, in.V - 1));
        vMask   = (1L << vBits) - 1;
    }

    public void sweep(int[] order) { sweep(order, Long.MAX_VALUE); }

    public void sweep(int[] order, long deadlineNs) { sweep(order, order.length, deadlineNs); }

    public void sweep(int[] order, int len, long deadlineNs) {
        for (int i = 0; i < len; i++) {
            optimizeCache(order[i]);
            if ((i & 15) == 15 && System.nanoTime() > deadlineNs) return;
        }
    }

    public int snapshot(int c, int[] out) {
        int n = 0;
        for (int v = 0; v < in.V; v++) if (sol.has(c, v)) out[n++] = v;
        return n;
    }

    public void restore(int c, int[] snap, int n) {
        clearCacheAndUpdate(c);
        for (int i = 0; i < n; i++) place(c, snap[i]);
    }

    public int clearCacheAndUpdate(int c) {
        final int V = in.V, C = in.C;
        int nPrev = 0;
        for (int v = 0; v < V; v++) if (sol.has(c, v)) prev[nPrev++] = v;
        if (nPrev == 0) return 0;
        for (int i = 0; i < nPrev; i++) sol.remove(c, prev[i]);

        for (int i = 0; i < nPrev; i++) {
            int v = prev[i];
            for (int j = in.vidStart[v]; j < in.vidStart[v + 1]; j++) {
                int r = in.vidReq[j];
                if (in.lat[in.reqEndpoint[r] * C + c] >= 0) book.recompute(r, sol);
            }
        }
        return nPrev;
    }

    public void optimizeCache(int c) { optimizeCache(c, 0.0, null); }

    public void optimizeCache(int c, double noise, java.util.Random rng) {
        final int V = in.V, X = in.X;
        final int[] size = in.videoSize;

        int nPrev = clearCacheAndUpdate(c);

        Arrays.fill(gain, 0L);
        final int[] best1 = book.best1;
        final int[] rv = in.reqVideo, rn = in.reqCount;
        for (int i = in.cEpStart[c], iEnd = in.cEpStart[c + 1]; i < iEnd; i++) {
            int e = in.cEp[i], l = in.cEpLat[i];
            for (int r = in.epReqStart[e], rEnd = in.epReqStart[e + 1]; r < rEnd; r++) {
                int d = best1[r] - l;
                if (d > 0) gain[rv[r]] += (long) rn[r] * d;
            }
        }
        if (noise > 0) {
            for (int v = 0; v < V; v++)
                if (gain[v] > 0)
                    gain[v] = Math.max(1L, (long) (gain[v] * (1.0 + noise * (2 * rng.nextDouble() - 1))));
        }

        int n = 0;
        for (int v = 0; v < V; v++) {
            long g = gain[v];
            if (g <= 0 || size[v] > X) continue;
            long bits = Double.doubleToRawLongBits((double) g / size[v]);
            sortKey[n++] = ((bits >>> vBits) << vBits) | v;
        }
        if (n == 0) return;
        Arrays.sort(sortKey, 0, n);
        for (int k = 0; k < n; k++) ord[k] = (int) (sortKey[n - 1 - k] & vMask);

        long cumW = 0, cumP = 0;
        int b = n;
        for (int k = 0; k < n; k++) {
            int v = ord[k];
            if (cumW + size[v] > X) { b = k; break; }
            cumW += size[v];
            cumP += gain[v];
        }
        if (b == n) {
            for (int k = 0; k < n; k++) place(c, ord[k]);
            return;
        }

        int ng = 0;
        for (int k = 0; k < b; k++) gsel[ng++] = ord[k];
        long gV = cumP, left = X - cumW;
        for (int k = b; k < n && left > 0; k++) {
            int v = ord[k];
            if (size[v] <= left) { gsel[ng++] = v; left -= size[v]; gV += gain[v]; }
        }
        long pV = 0;
        for (int i = 0; i < nPrev; i++) if (gain[prev[i]] > 0) pV += gain[prev[i]];
        final long lb = Math.max(gV, pV);

        int vb = ord[b];
        final double rb = (double) gain[vb] / size[vb];
        final double ub = cumP + (double) (X - cumW) * rb;
        final double lim = Math.max(0.0, ub - lb) + 1e-10 * ub + 1.0;

        int nFix = 0, nCore = 0;
        long fixW = 0, fixP = 0, coreW = 0;
        for (int k = 0; k < n; k++) {
            int v = ord[k];
            double d = gain[v] - size[v] * rb;
            if (d > lim)       { chosen[nFix++] = v; fixW += size[v]; fixP += gain[v]; }
            else if (-d > lim) {  }
            else               { core[nCore++] = v; coreW += size[v]; }
        }
        final long capL = X - fixW;

        boolean ok = capL >= 0;
        int nCh = nFix;
        if (ok) {
            int cap = (int) capL;
            if (coreW <= cap) {
                for (int i = 0; i < nCore; i++) chosen[nCh++] = core[i];
                ok = fixP + sumGain(core, nCore) >= lb;
            } else if (cap > MAX_DP_CAP) {
                ok = false;
            } else {

                final int words = (cap + 64) >>> 6;
                final int need = nCore * words;
                if (take.length < need) take = new long[need];
                if (dp.length < cap + 1) dp = new long[cap + 1];
                Arrays.fill(take, 0, need, 0L);
                Arrays.fill(dp, 0, cap + 1, 0L);
                for (int i = 0; i < nCore; i++) {
                    int v = core[i];
                    int wi = size[v];
                    long vi = gain[v];
                    int base = i * words;
                    for (int w = cap; w >= wi; w--) {
                        long cand = dp[w - wi] + vi;
                        if (cand > dp[w]) {
                            dp[w] = cand;
                            take[base + (w >>> 6)] |= 1L << w;
                        }
                    }
                }
                ok = fixP + dp[cap] >= lb;
                if (ok) {
                    int w = cap;
                    for (int i = nCore - 1; i >= 0; i--) {
                        if ((take[i * words + (w >>> 6)] & (1L << w)) != 0) {
                            int v = core[i];
                            chosen[nCh++] = v;
                            w -= size[v];
                        }
                    }
                }
            }
        }

        if (ok) {
            for (int i = 0; i < nCh; i++) place(c, chosen[i]);
        } else {
            fallbacks++;
            if (gV >= pV) for (int i = 0; i < ng; i++) place(c, gsel[i]);
            else for (int i = 0; i < nPrev; i++) if (gain[prev[i]] > 0) place(c, prev[i]);
        }
    }

    private long sumGain(int[] a, int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += gain[a[i]];
        return s;
    }

    private void place(int c, int v) {
        sol.add(c, v);
        book.onAdd(c, v);
    }
}

