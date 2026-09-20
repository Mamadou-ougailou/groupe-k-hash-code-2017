package solver.impl;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;
import solver.Solver;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glouton paresseux CELF (Cost-Effective Lazy Forward).
 *
 * Port exact de l'algorithme haute performance vers le modèle objet
 * du projet (Problem/Cache/Endpoint/Video).
 *
 * Différences clés par rapport au GreedySolver naïf :
 *   1. Carnet best1/best2/arg1 par requête → évaluation O(1)
 *   2. Tas binaire max sur tableaux primitifs → zéro autoboxing
 *   3. Vraie CELF : on recalcule TOUJOURS le sommet, puis on compare
 *      au suivant. Si le gain recalculé domine, c'est le vrai max ;
 *      sinon on repousse. Résultat IDENTIQUE au glouton naïf.
 *   4. Structures internes aplaties en int[] pour la vitesse.
 */
public class GreedyCELFSolver implements Solver {

    @Override
    public void solve(Problem problem) {
        final List<Video> videos = problem.getVideos();
        final List<Endpoint> endpoints = problem.getEndpoints();
        final List<Cache> caches = problem.getCaches();
        final List<RequestDescription> requests = problem.getRequests();

        final int V = videos.size();
        final int C = caches.size();
        final int R = requests.size();

        // ---- Flatten model into primitive arrays ----

        // Video sizes
        int[] videoSize = new int[V];
        for (Video v : videos) videoSize[v.getId()] = v.getSize();

        // Request arrays
        int[] reqEndpoint = new int[R];
        int[] reqVideo    = new int[R];
        int[] reqCount    = new int[R];
        for (int r = 0; r < R; r++) {
            RequestDescription rd = requests.get(r);
            reqEndpoint[r] = rd.getEndpoint().getId();
            reqVideo[r]    = rd.getVideo().getId();
            reqCount[r]    = rd.getNumberOfRequests();
        }

        // Data center latency per endpoint
        int[] dcLatency = new int[endpoints.size()];
        for (Endpoint ep : endpoints) dcLatency[ep.getId()] = ep.getDataCenterLatency();

        // Latency matrix: lat[e * C + c] = latency, or -1 if not connected
        int[] lat = new int[endpoints.size() * C];
        java.util.Arrays.fill(lat, -1);
        for (Endpoint ep : endpoints) {
            int e = ep.getId();
            for (Map.Entry<Cache, Integer> entry : ep.getCacheLatencies().entrySet()) {
                lat[e * C + entry.getKey().getId()] = entry.getValue();
            }
        }

        // CSR for endpoint → connected caches
        // First pass: count
        int[] epCount = new int[endpoints.size()];
        for (Endpoint ep : endpoints) epCount[ep.getId()] = ep.getCacheLatencies().size();
        int[] epStart = new int[endpoints.size() + 1];
        for (int i = 0; i < endpoints.size(); i++) epStart[i + 1] = epStart[i] + epCount[i];
        int[] epCache = new int[epStart[endpoints.size()]];
        int[] epLat   = new int[epStart[endpoints.size()]];
        int[] epPos   = new int[endpoints.size()]; // temp write cursor
        System.arraycopy(epStart, 0, epPos, 0, endpoints.size());
        for (Endpoint ep : endpoints) {
            int e = ep.getId();
            for (Map.Entry<Cache, Integer> entry : ep.getCacheLatencies().entrySet()) {
                int pos = epPos[e]++;
                epCache[pos] = entry.getKey().getId();
                epLat[pos]   = entry.getValue();
            }
        }

        // CSR for video → requests involving that video
        int[] vidCount = new int[V];
        for (int r = 0; r < R; r++) vidCount[reqVideo[r]]++;
        int[] vidStart = new int[V + 1];
        for (int i = 0; i < V; i++) vidStart[i + 1] = vidStart[i] + vidCount[i];
        int[] vidReq = new int[R];
        int[] vidPos = new int[V];
        System.arraycopy(vidStart, 0, vidPos, 0, V);
        for (int r = 0; r < R; r++) vidReq[vidPos[reqVideo[r]]++] = r;

        // Cache remaining capacity
        int[] cacheRemaining = new int[C];
        for (Cache c : caches) cacheRemaining[c.getId()] = c.getCapacity();

        // Cache content as flat bitset: has[c * V + v]
        boolean[] has = new boolean[C * V];

        // ---- Book: best1 / best2 / arg1 per request ----
        int[] best1 = new int[R];
        int[] best2 = new int[R];
        int[] arg1  = new int[R];
        for (int r = 0; r < R; r++) {
            int ld = dcLatency[reqEndpoint[r]];
            best1[r] = ld;
            best2[r] = ld;
            arg1[r]  = -1;  // data center
        }

        // ---- 1. Compute initial gains: best1 == L_D ----
        long[] gain = new long[C * V];
        for (int r = 0; r < R; r++) {
            int e = reqEndpoint[r];
            int v = reqVideo[r];
            long n = reqCount[r];
            int ld = dcLatency[e];
            for (int i = epStart[e]; i < epStart[e + 1]; i++) {
                int d = ld - epLat[i];
                if (d > 0) gain[epCache[i] * V + v] += n * d;
            }
        }

        // ---- 2. Build max-heap on gain/size ----
        int nCand = 0;
        for (int p = 0; p < gain.length; p++) if (gain[p] > 0) nCand++;

        double[] heapKey = new double[Math.max(nCand, 16)];
        int[]    heapVal = new int[Math.max(nCand, 16)];
        int heapSize = 0;

        // Raw insert (unsorted)
        for (int p = 0; p < gain.length; p++) {
            if (gain[p] <= 0) continue;
            int v = p % V;
            heapKey[heapSize] = (double) gain[p] / videoSize[v];
            heapVal[heapSize] = p;
            heapSize++;
        }
        // O(n) heapify
        for (int i = (heapSize >>> 1) - 1; i >= 0; i--)
            siftDown(heapKey, heapVal, heapSize, i);

        gain = null; // free

        // ---- 3. CELF greedy loop ----
        while (heapSize > 0) {
            int pid = heapVal[0];
            heapPop(heapKey, heapVal, heapSize);
            heapSize--;

            int c = pid / V, v = pid - c * V;
            if (has[pid]) continue;
            int sz = videoSize[v];
            if (sz > cacheRemaining[c]) continue;

            // Recompute true gain against current best1
            long g = 0;
            for (int i = vidStart[v]; i < vidStart[v + 1]; i++) {
                int r = vidReq[i];
                int l = lat[reqEndpoint[r] * C + c];
                if (l < 0) continue;
                int d = best1[r] - l;
                if (d > 0) g += (long) reqCount[r] * d;
            }
            if (g <= 0) continue;

            double nk = (double) g / sz;

            // True CELF: compare recomputed gain to the next in heap
            if (heapSize == 0 || nk >= heapKey[0]) {
                // Accept placement
                has[pid] = true;
                cacheRemaining[c] -= sz;

                // Update book: offer (c, lat) to all requests of video v
                for (int i = vidStart[v]; i < vidStart[v + 1]; i++) {
                    int r = vidReq[i];
                    int l = lat[reqEndpoint[r] * C + c];
                    if (l >= 0) {
                        // offer
                        if (l < best1[r]) {
                            best2[r] = best1[r];
                            best1[r] = l;
                            arg1[r]  = c;
                        } else if (l < best2[r]) {
                            best2[r] = l;
                        }
                    }
                }
            } else {
                // Stale: push back with recomputed gain
                heapPush(heapKey, heapVal, heapSize, nk, pid);
                heapSize++;
            }
        }

        // ---- 4. Write results back to model objects ----
        for (int c = 0; c < C; c++) {
            Cache cache = caches.get(c);
            for (int v = 0; v < V; v++) {
                if (has[c * V + v]) {
                    cache.addVideo(videos.get(v));
                }
            }
        }
    }

    // ---- Max-heap on primitive arrays (no autoboxing) ----

    private static void siftDown(double[] key, int[] val, int n, int i) {
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

    private static void siftUp(double[] key, int[] val, int i) {
        double k = key[i]; int v = val[i];
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (key[p] >= k) break;
            key[i] = key[p]; val[i] = val[p]; i = p;
        }
        key[i] = k; val[i] = v;
    }

    private static void heapPop(double[] key, int[] val, int n) {
        n--;
        key[0] = key[n]; val[0] = val[n];
        if (n > 0) siftDown(key, val, n, 0);
    }

    private static void heapPush(double[] key, int[] val, int n, double k, int v) {
        // Grow if needed
        if (n >= key.length) {
            int cap = key.length + (key.length >> 1) + 16;
            double[] a = new double[cap]; System.arraycopy(key, 0, a, 0, n);
            int[] b = new int[cap];       System.arraycopy(val, 0, b, 0, n);
            // Can't resize in-place with static methods, but push-backs are rare
            // and the initial array is sized for all candidates
        }
        key[n] = k; val[n] = v;
        siftUp(key, val, n);
    }
}
