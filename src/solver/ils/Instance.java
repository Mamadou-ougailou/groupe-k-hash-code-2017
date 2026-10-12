package solver.ils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Instance {

    public final int V;
    public final int E;
    public final int R;
    public final int C;
    public final int X;

    public final int[] videoSize;
    public final int[] dcLatency;

    public final int[] epStart;
    public final int[] epCache;
    public final int[] epLat;

    public final int[] lat;

    public final int[] reqVideo;
    public final int[] reqEndpoint;
    public final int[] reqCount;
    public final long totalRequests;

    public final int[] epReqStart;

    public final int[] cEpStart;
    public final int[] cEp;
    public final int[] cEpLat;

    public final int[] vidStart;
    public final int[] vidReq;

    public final int orphanEndpoints;

    public Instance(Path file) throws IOException {
        this(new Tok(Files.readAllBytes(file)));
    }

    public Instance(int[] tokens) {
        this(new Tok(tokens));
    }

    private Instance(Tok t) {

        V = t.next();  E = t.next();  R = t.next();  C = t.next();  X = t.next();

        videoSize = new int[V];
        for (int i = 0; i < V; i++) videoSize[i] = t.next();

        dcLatency = new int[E];
        int[] k = new int[E];

        int[] tmpCache = new int[E * 4];
        int[] tmpLat   = new int[E * 4];
        int nConn = 0;
        int orphans = 0;

        epStart = new int[E + 1];
        for (int e = 0; e < E; e++) {
            epStart[e] = nConn;
            dcLatency[e] = t.next();
            k[e] = t.next();
            if (k[e] == 0) orphans++;
            if (nConn + k[e] > tmpCache.length) {
                int cap = Math.max(nConn + k[e], tmpCache.length * 2);
                int[] a = new int[cap]; System.arraycopy(tmpCache, 0, a, 0, nConn); tmpCache = a;
                int[] b = new int[cap]; System.arraycopy(tmpLat,   0, b, 0, nConn); tmpLat   = b;
            }
            for (int j = 0; j < k[e]; j++) {
                tmpCache[nConn] = t.next();
                tmpLat[nConn]   = t.next();
                nConn++;
            }
        }
        epStart[E] = nConn;
        orphanEndpoints = orphans;

        epCache = new int[nConn];
        epLat   = new int[nConn];
        System.arraycopy(tmpCache, 0, epCache, 0, nConn);
        System.arraycopy(tmpLat,   0, epLat,   0, nConn);

        lat = new int[E * C];
        java.util.Arrays.fill(lat, -1);
        for (int e = 0; e < E; e++) {
            int base = e * C;
            for (int i = epStart[e]; i < epStart[e + 1]; i++) lat[base + epCache[i]] = epLat[i];
        }

        cEpStart = new int[C + 1];
        for (int i = 0; i < nConn; i++) cEpStart[epCache[i] + 1]++;
        for (int c = 0; c < C; c++) cEpStart[c + 1] += cEpStart[c];
        cEp = new int[nConn];
        cEpLat = new int[nConn];
        int[] curC = new int[C];
        for (int e = 0; e < E; e++) {
            for (int i = epStart[e]; i < epStart[e + 1]; i++) {
                int c = epCache[i];
                int pos = cEpStart[c] + curC[c]++;
                cEp[pos] = e;
                cEpLat[pos] = epLat[i];
            }
        }

        reqVideo    = new int[R];
        reqEndpoint = new int[R];
        reqCount    = new int[R];
        int[] tv = new int[R], te = new int[R], tn = new int[R];
        long tot = 0;
        epReqStart = new int[E + 1];
        for (int r = 0; r < R; r++) {
            tv[r] = t.next();
            te[r] = t.next();
            tn[r] = t.next();
            tot += tn[r];
            epReqStart[te[r] + 1]++;
        }
        for (int e = 0; e < E; e++) epReqStart[e + 1] += epReqStart[e];

        int[] cnt = new int[V + 1];
        int[] curE = new int[E];
        for (int r = 0; r < R; r++) {
            int pos = epReqStart[te[r]] + curE[te[r]]++;
            reqVideo[pos]    = tv[r];
            reqEndpoint[pos] = te[r];
            reqCount[pos]    = tn[r];
            cnt[tv[r]]++;
        }
        totalRequests = tot;

        vidStart = new int[V + 1];
        for (int v = 0; v < V; v++) vidStart[v + 1] = vidStart[v] + cnt[v];
        int[] cursor = new int[V];
        vidReq = new int[R];
        for (int r = 0; r < R; r++) {
            int v = reqVideo[r];
            vidReq[vidStart[v] + cursor[v]++] = r;
        }
    }

    public int latency(int e, int c) { return lat[e * C + c]; }

    public void printSummary() {
        int mono = 0, multi = 0;
        for (int e = 0; e < E; e++) {
            int kk = epStart[e + 1] - epStart[e];
            if (kk == 1) mono++; else if (kk > 1) multi++;
        }
        System.out.printf(
            "V=%d E=%d R=%d C=%d X=%d | requetes=%d%n" +
            "  endpoints : %d orphelins, %d mono-cache, %d multi-cache%n",
            V, E, R, C, X, totalRequests, orphanEndpoints, mono, multi);
    }

    private static final class Tok {
        private final byte[] b;
        private final int[] ints;
        private int p;
        Tok(byte[] b) { this.b = b; this.ints = null; }
        Tok(int[] ints) { this.b = null; this.ints = ints; }
        int next() {
            if (ints != null) return ints[p++];
            byte[] a = b;
            int i = p, n = a.length;
            while (i < n && (a[i] < '0' || a[i] > '9')) i++;
            int x = 0;
            while (i < n && a[i] >= '0' && a[i] <= '9') { x = x * 10 + (a[i] - '0'); i++; }
            p = i;
            return x;
        }
    }
}

