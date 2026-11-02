package solver.ils;

public final class BestLatencyTracker {

    public final FlatProblem problem;
    public final int[] bestLatency;
    public final int[] secondBestLatency;
    public final int[] bestCache;

    public BestLatencyTracker(FlatProblem problem) {
        this.problem = problem;
        bestLatency = new int[problem.requestCount];
        secondBestLatency = new int[problem.requestCount];
        bestCache = new int[problem.requestCount];
        reset();
    }

    public void reset() {
        for (int request = 0; request < problem.requestCount; request++) {
            int dataCenterLatency = problem.dataCenterLatency[problem.requestEndpoint[request]];
            bestLatency[request] = dataCenterLatency;
            secondBestLatency[request] = dataCenterLatency;
            bestCache[request] = -1;
        }
    }

    public void copyFrom(BestLatencyTracker other) {
        System.arraycopy(other.bestLatency, 0, bestLatency, 0, bestLatency.length);
        System.arraycopy(other.secondBestLatency, 0, secondBestLatency, 0, secondBestLatency.length);
        System.arraycopy(other.bestCache,  0, bestCache,  0, bestCache.length);
    }

    private void offerLatency(int request, int latency, int cache) {
        if (latency < bestLatency[request]) { secondBestLatency[request] = bestLatency[request]; bestLatency[request] = latency; bestCache[request] = cache; }
        else if (latency < secondBestLatency[request]) { secondBestLatency[request] = latency; }
    }

    public void rebuildFrom(Solution solution) {
        reset();
        for (int request = 0; request < problem.requestCount; request++) {
            int endpoint = problem.requestEndpoint[request], video = problem.requestVideo[request];
            for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++) {
                int cache = problem.linkedCache[link];
                if (solution.contains(cache, video)) offerLatency(request, problem.linkLatency[link], cache);
            }
        }
    }

    public void onVideoAdded(int cache, int video) {
        final int cacheCount = problem.cacheCount;
        for (int i = problem.videoRequestStart[video]; i < problem.videoRequestStart[video + 1]; i++) {
            int request = problem.videoRequestIds[i];
            int latency = problem.latencyMatrix[problem.requestEndpoint[request] * cacheCount + cache];
            if (latency >= 0) offerLatency(request, latency, cache);
        }
    }

    public void recomputeRequest(int request, Solution solution) {
        int endpoint = problem.requestEndpoint[request], video = problem.requestVideo[request];
        int dataCenterLatency = problem.dataCenterLatency[endpoint];
        bestLatency[request] = dataCenterLatency; secondBestLatency[request] = dataCenterLatency; bestCache[request] = -1;
        for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++) {
            int cache = problem.linkedCache[link];
            if (solution.contains(cache, video)) offerLatency(request, problem.linkLatency[link], cache);
        }
    }

    public int bestLatencyWithoutCache(int request, int cache) {
        return (bestCache[request] == cache) ? secondBestLatency[request] : bestLatency[request];
    }

    public long score() {
        long totalSavedLatency = 0;
        for (int request = 0; request < problem.requestCount; request++)
            totalSavedLatency += (long) problem.requestViewCount[request] * (problem.dataCenterLatency[problem.requestEndpoint[request]] - bestLatency[request]);
        return totalSavedLatency * 1000L / problem.totalRequests;
    }
}
