package solver.ils;

public final class Scorer {

    public static long score(Instance in, Solution s) {
        long num = 0;
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r];
            int v = in.reqVideo[r];
            int best = in.dcLatency[e];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
                int l = in.epLat[i];
                if (l < best && s.has(in.epCache[i], v)) best = l;
            }
            num += (long) in.reqCount[r] * (in.dcLatency[e] - best);
        }
        return num * 1000L / in.totalRequests;
    }

    public static long upperBound(Instance in) {
        long num = 0;
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r];
            int best = in.dcLatency[e];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++)
                if (in.epLat[i] < best) best = in.epLat[i];
            num += (long) in.reqCount[r] * (in.dcLatency[e] - best);
        }
        return num * 1000L / in.totalRequests;
    }

    public static long validateAndScore(Instance in, Solution s) {
        s.validateOrThrow();
        return score(in, s);
    }
}

