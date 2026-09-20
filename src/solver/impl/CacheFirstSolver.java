package solver.impl;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.Video;
import solver.Solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cache-first greedy solver.
 * 
 * Strategy: Rank caches by importance, then for each cache greedily fill it
 * with the highest value-per-size videos.
 */
public class CacheFirstSolver implements Solver {

    // Key: "endpointId-videoId", Value: best latency seen so far for that pair
    private Map<String, Integer> bestLatencySoFar;

    @Override
    public void solve(Problem problem) {
        // 1. Initialize best latency to data center latency for every (endpoint, video) pair
        bestLatencySoFar = new HashMap<>();
        for (Endpoint endpoint : problem.getEndpoints()) {
            for (Video video : endpoint.getRequestedVideos().keySet()) {
                String key = makeKey(endpoint, video);
                bestLatencySoFar.put(key, endpoint.getDataCenterLatency());
            }
        }

        // 2. Sort caches by importance (descending)
        List<Cache> sortedCaches = new ArrayList<>(problem.getCaches());
        sortedCaches.sort(Comparator.comparingInt(this::computeCacheImportance).reversed());

        // 3. Process each cache in order
        for (Cache cache : sortedCaches) {

            // a. Build the set of candidate videos for this cache
            Set<Video> candidateVideos = new HashSet<>();
            for (Endpoint endpoint : cache.getEndpointLatencies().keySet()) {
                candidateVideos.addAll(endpoint.getRequestedVideos().keySet());
            }

            // b. Compute value/size ratio for each candidate video
            List<VideoScore> sortedScores = new ArrayList<>();
            for (Video video : candidateVideos) {
                int value = computeVideoValueForCache(video, cache);
                if (value > 0) {
                    double ratio = (double) value / video.getSize();
                    sortedScores.add(new VideoScore(video, ratio));
                }
            }

            // c. Sort by ratio (descending)
            sortedScores.sort(Comparator.comparingDouble(VideoScore::getRatio).reversed());

            // d. Greedy knapsack: fill the cache
            for (VideoScore scored : sortedScores) {
                Video video = scored.getVideo();
                if (cache.canFit(video)) {
                    cache.addVideo(video);

                    // Update best latency for all endpoints connected to this cache
                    for (Map.Entry<Endpoint, Integer> entry : cache.getEndpointLatencies().entrySet()) {
                        Endpoint endpoint = entry.getKey();
                        int cacheLatency = entry.getValue();
                        String key = makeKey(endpoint, video);

                        if (bestLatencySoFar.containsKey(key) && cacheLatency < bestLatencySoFar.get(key)) {
                            bestLatencySoFar.put(key, cacheLatency);
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the total importance of a cache server.
     * Importance = sum over all connected (endpoint, video) pairs of:
     *   numberOfRequests * max(0, bestLatencySoFar - cacheLatency)
     */
    private int computeCacheImportance(Cache cache) {
        int importance = 0;
        for (Map.Entry<Endpoint, Integer> entry : cache.getEndpointLatencies().entrySet()) {
            Endpoint endpoint = entry.getKey();
            int cacheLatency = entry.getValue();

            for (Map.Entry<Video, Integer> videoEntry : endpoint.getRequestedVideos().entrySet()) {
                Video video = videoEntry.getKey();
                int numberOfRequests = videoEntry.getValue();
                String key = makeKey(endpoint, video);

                int currentBest = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
                int gain = Math.max(0, currentBest - cacheLatency);
                importance += numberOfRequests * gain;
            }
        }
        return importance;
    }

    /**
     * Computes the value of placing a specific video in a specific cache.
     * Value = sum over all endpoints connected to this cache that request this video of:
     *   numberOfRequests * max(0, bestLatencySoFar - cacheLatency)
     */
    private int computeVideoValueForCache(Video video, Cache cache) {
        int value = 0;
        for (Map.Entry<Endpoint, Integer> entry : cache.getEndpointLatencies().entrySet()) {
            Endpoint endpoint = entry.getKey();
            int cacheLatency = entry.getValue();

            if (endpoint.getRequestedVideos().containsKey(video)) {
                int numberOfRequests = endpoint.getRequestedVideos().get(video);
                String key = makeKey(endpoint, video);

                int currentBest = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
                int gain = Math.max(0, currentBest - cacheLatency);
                value += numberOfRequests * gain;
            }
        }
        return value;
    }

    private String makeKey(Endpoint endpoint, Video video) {
        return endpoint.getId() + "-" + video.getId();
    }

    /**
     * Helper class to pair a Video with its value/size ratio for sorting.
     */
    private static class VideoScore {
        private final Video video;
        private final double ratio;

        VideoScore(Video video, double ratio) {
            this.video = video;
            this.ratio = ratio;
        }

        Video getVideo() {
            return video;
        }

        double getRatio() {
            return ratio;
        }
    }
}
