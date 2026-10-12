package solver.ils;

public final class Book {

    public final Instance in;
    public final int[] best1;
    public final int[] best2;
    public final int[] arg1;

    public Book(Instance in) {
        this.in = in;
        best1 = new int[in.R];
        best2 = new int[in.R];
        arg1  = new int[in.R];
        reset();
    }

    public void reset() {
        for (int r = 0; r < in.R; r++) {
            int ld = in.dcLatency[in.reqEndpoint[r]];
            best1[r] = ld;
            best2[r] = ld;
            arg1[r]  = -1;
        }
    }

    public void copyFrom(Book o) {
        System.arraycopy(o.best1, 0, best1, 0, best1.length);
        System.arraycopy(o.best2, 0, best2, 0, best2.length);
        System.arraycopy(o.arg1,  0, arg1,  0, arg1.length);
    }

    private void offer(int r, int l, int c) {
        if (l < best1[r]) { best2[r] = best1[r]; best1[r] = l; arg1[r] = c; }
        else if (l < best2[r]) { best2[r] = l; }
    }

    public void rebuild(Solution s) {
        reset();
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r], v = in.reqVideo[r];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
                int c = in.epCache[i];
                if (s.has(c, v)) offer(r, in.epLat[i], c);
            }
        }
    }

    public void onAdd(int c, int v) {
        final int C = in.C;
        for (int i = in.vidStart[v]; i < in.vidStart[v + 1]; i++) {
            int r = in.vidReq[i];
            int l = in.lat[in.reqEndpoint[r] * C + c];
            if (l >= 0) offer(r, l, c);
        }
    }

    public void recompute(int r, Solution s) {
        int e = in.reqEndpoint[r], v = in.reqVideo[r];
        int ld = in.dcLatency[e];
        best1[r] = ld; best2[r] = ld; arg1[r] = -1;
        for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
            int c = in.epCache[i];
            if (s.has(c, v)) offer(r, in.epLat[i], c);
        }
    }

    public int bestExcluding(int r, int c) {
        return (arg1[r] == c) ? best2[r] : best1[r];
    }

    public long score() {
        long num = 0;
        for (int r = 0; r < in.R; r++)
            num += (long) in.reqCount[r] * (in.dcLatency[in.reqEndpoint[r]] - best1[r]);
        return num * 1000L / in.totalRequests;
    }
}

