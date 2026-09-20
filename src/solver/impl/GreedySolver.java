package solver.impl;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.Video;
import solver.Solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Greedy global solver with lazy evaluation.
 *
 * Strategy: Consider ALL (video, cache) pairs at once, ranked by marginal gain
 * per unit size in a max-heap. Use lazy evaluation to avoid recomputing all
 * gains after each placement — only recompute when an element is popped and
 * its timestamp is stale.
 */
public class GreedySolver implements Solver {

    // Key: "endpointId-videoId", Value: best latency seen so far for that pair
    private Map<String, Integer> bestLatencySoFar;

    // Reverse map: video -> list of (endpoint, nbRequests)
    private Map<Video, List<EndpointRequest>> videoToRequests;

    // Global iteration counter — incremented after each placement
    private int iteration;

    @Override
    public void solve(Problem problem) {
        // 1. Initialize best latency to data center latency
        bestLatencySoFar = new HashMap<>();
        for (Endpoint endpoint : problem.getEndpoints()) {
            for (Video video : endpoint.getRequestedVideos().keySet()) {
                bestLatencySoFar.put(makeKey(endpoint, video), endpoint.getDataCenterLatency());
            }
        }

        // 2. Build reverse map: video -> [(endpoint, nbRequests), ...]
        videoToRequests = new HashMap<>();
        for (Endpoint endpoint : problem.getEndpoints()) {
            for (Map.Entry<Video, Integer> entry : endpoint.getRequestedVideos().entrySet()) {
                videoToRequests.computeIfAbsent(entry.getKey(), v -> new ArrayList<>())
                        .add(new EndpointRequest(endpoint, entry.getValue()));
            }
        }

        // 3. Build the initial max-heap with all valid (video, cache) pairs
        iteration = 0;
        PriorityQueue<HeapEntry> heap = new PriorityQueue<>();
        Set<Long> seen = new HashSet<>(); // to avoid duplicate (video, cache) pairs

        for (Video video : problem.getVideos()) {
            List<EndpointRequest> requests = videoToRequests.get(video);
            if (requests == null) continue;

            for (EndpointRequest er : requests) {
                for (Map.Entry<Cache, Integer> ce : er.endpoint.getCacheLatencies().entrySet()) {
                    Cache cache = ce.getKey();
                    long pairKey = pairKey(video, cache, problem.getCaches().size());
                    if (seen.contains(pairKey)) continue;
                    seen.add(pairKey);

                    if (!cache.canFit(video)) continue;

                    long gain = computeMarginalGain(video, cache);
                    if (gain > 0) {
                        double ratio = (double) gain / video.getSize();
                        heap.add(new HeapEntry(ratio, video, cache, iteration));
                    }
                }
            }
        }

        // 4. Main greedy loop
        while (!heap.isEmpty()) {
            HeapEntry top = heap.poll();
            Video video = top.video;
            Cache cache = top.cache;

            // a. Skip if no room
            if (!cache.canFit(video)) continue;

            // b. Skip if video already in this cache
            if (cache.getCachedVideos().contains(video)) continue;

            // c. Lazy evaluation: if the entry is stale, recompute and re-insert
            if (top.timestamp < iteration) {
                long gainNow = computeMarginalGain(video, cache);
                if (gainNow <= 0) continue;
                double ratioNow = (double) gainNow / video.getSize();
                heap.add(new HeapEntry(ratioNow, video, cache, iteration));
                continue;
            }

            // d. Fresh entry — accept the placement
            cache.addVideo(video);
            iteration++;

            // e. Update best_latency_so_far for all endpoints affected
            List<EndpointRequest> requests = videoToRequests.get(video);
            if (requests != null) {
                for (EndpointRequest er : requests) {
                    Endpoint endpoint = er.endpoint;
                    if (endpoint.getCacheLatencies().containsKey(cache)) {
                        int cacheLatency = endpoint.getCacheLatencies().get(cache);
                        String key = makeKey(endpoint, video);
                        int current = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
                        if (cacheLatency < current) {
                            bestLatencySoFar.put(key, cacheLatency);
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the current marginal gain of placing video in cache,
     * considering the current state of best_latency_so_far.
     */
    private long computeMarginalGain(Video video, Cache cache) {
        long gain = 0;
        List<EndpointRequest> requests = videoToRequests.get(video);
        if (requests == null) return 0;

        for (EndpointRequest er : requests) {
            Endpoint endpoint = er.endpoint;
            if (!endpoint.getCacheLatencies().containsKey(cache)) continue;

            int cacheLatency = endpoint.getCacheLatencies().get(cache);
            String key = makeKey(endpoint, video);
            int currentBest = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
            int g = Math.max(0, currentBest - cacheLatency);
            gain += (long) er.nbRequests * g;
        }
        return gain;
    }

    private String makeKey(Endpoint endpoint, Video video) {
        return endpoint.getId() + "-" + video.getId();
    }

    private long pairKey(Video video, Cache cache, int numCaches) {
        return (long) video.getId() * numCaches + cache.getId();
    }

    /**
     * Max-heap entry: stores a (video, cache) pair with its gain/size ratio
     * and a timestamp indicating when the ratio was last computed.
     * PriorityQueue is a min-heap by default, so we reverse the comparison.
     */
    private static class HeapEntry implements Comparable<HeapEntry> {
        final double ratio;
        final Video video;
        final Cache cache;
        final int timestamp;

        HeapEntry(double ratio, Video video, Cache cache, int timestamp) {
            this.ratio = ratio;
            this.video = video;
            this.cache = cache;
            this.timestamp = timestamp;
        }

        @Override
        public int compareTo(HeapEntry other) {
            // Reverse order for max-heap behavior
            return Double.compare(other.ratio, this.ratio);
        }
    }

    /** Pairs an endpoint with the number of requests it makes for a video. */
    private static class EndpointRequest {
        final Endpoint endpoint;
        final int nbRequests;

        EndpointRequest(Endpoint endpoint, int nbRequests) {
            this.endpoint = endpoint;
            this.nbRequests = nbRequests;
        }
    }
}
