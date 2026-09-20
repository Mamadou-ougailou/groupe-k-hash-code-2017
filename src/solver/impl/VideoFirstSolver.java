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


public class VideoFirstSolver implements Solver {

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

        // 2. Build a reverse map: video -> list of (endpoint, nbRequests)
        Map<Video, List<EndpointRequest>> videoToRequests = new HashMap<>();
        for (Endpoint endpoint : problem.getEndpoints()) {
            for (Map.Entry<Video, Integer> entry : endpoint.getRequestedVideos().entrySet()) {
                Video video = entry.getKey();
                int nbRequests = entry.getValue();
                videoToRequests.computeIfAbsent(video, v -> new ArrayList<>())
                        .add(new EndpointRequest(endpoint, nbRequests));
            }
        }

        // 3. Compute initial score for each video and sort descending
        List<VideoScore> sortedVideos = new ArrayList<>();
        for (Video video : problem.getVideos()) {
            List<EndpointRequest> requests = videoToRequests.get(video);
            if (requests == null || requests.isEmpty()) continue;

            double score = computeVideoScore(video, requests);
            if (score > 0) {
                sortedVideos.add(new VideoScore(video, score));
            }
        }
        sortedVideos.sort(Comparator.comparingDouble(VideoScore::getScore).reversed());

        // 4. Process each video in order
        for (VideoScore scored : sortedVideos) {
            Video video = scored.getVideo();
            List<EndpointRequest> requests = videoToRequests.get(video);
            if (requests == null) continue;

            // a. Build the set of candidate caches for this video
            Set<Cache> candidateCaches = new HashSet<>();
            for (EndpointRequest er : requests) {
                candidateCaches.addAll(er.getEndpoint().getCacheLatencies().keySet());
            }

            Set<Endpoint> endpointsAlreadyServed = new HashSet<>();

            // b. Greedily place the video into caches, one by one, while there is gain
            while (true) {
                Cache bestCache = null;
                int bestGain = 0;

                for (Cache cache : candidateCaches) {
                    if (!cache.canFit(video)) continue;

                    int gain = computeGainForVideoInCache(cache, video, requests, endpointsAlreadyServed);
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestCache = cache;
                    }
                }

                if (bestCache == null) break; // No more gain possible for this video

                // Place the video in the best cache
                bestCache.addVideo(video);
                candidateCaches.remove(bestCache);

                // Update state for endpoints served by this cache
                for (EndpointRequest er : requests) {
                    Endpoint endpoint = er.getEndpoint();
                    if (endpoint.getCacheLatencies().containsKey(bestCache)) {
                        int cacheLatency = endpoint.getCacheLatencies().get(bestCache);
                        String key = makeKey(endpoint, video);
                        if (cacheLatency < bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency())) {
                            bestLatencySoFar.put(key, cacheLatency);
                        }
                        endpointsAlreadyServed.add(endpoint);
                    }
                }
            }
        }
    }

    /**
     * Computes the global score for a video (used for initial ranking).
     * Score = sum over requesting endpoints of:
     *   nbRequests * max(0, bestLatencySoFar - bestPossibleCacheLatency)
     * Divided by video size (value-density).
     */
    private double computeVideoScore(Video video, List<EndpointRequest> requests) {
        long score = 0;
        for (EndpointRequest er : requests) {
            Endpoint endpoint = er.getEndpoint();
            int nbRequests = er.getNbRequests();

            // Find the minimum latency among all caches connected to this endpoint
            int bestCacheLatency = Integer.MAX_VALUE;
            for (int latency : endpoint.getCacheLatencies().values()) {
                if (latency < bestCacheLatency) {
                    bestCacheLatency = latency;
                }
            }

            if (bestCacheLatency == Integer.MAX_VALUE) continue; // No caches connected

            String key = makeKey(endpoint, video);
            int currentBest = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
            int gain = Math.max(0, currentBest - bestCacheLatency);
            score += (long) nbRequests * gain;
        }
        return (double) score / video.getSize();
    }

    /**
     * Computes the gain of placing a video in a specific cache,
     * excluding endpoints already well-served by a previous placement of this video.
     */
    private int computeGainForVideoInCache(Cache cache, Video video,
                                           List<EndpointRequest> requests,
                                           Set<Endpoint> endpointsAlreadyServed) {
        int gain = 0;
        for (EndpointRequest er : requests) {
            Endpoint endpoint = er.getEndpoint();

            if (endpointsAlreadyServed.contains(endpoint)) continue;
            if (!endpoint.getCacheLatencies().containsKey(cache)) continue;

            int cacheLatency = endpoint.getCacheLatencies().get(cache);
            String key = makeKey(endpoint, video);
            int currentBest = bestLatencySoFar.getOrDefault(key, endpoint.getDataCenterLatency());
            int g = Math.max(0, currentBest - cacheLatency);
            gain += er.getNbRequests() * g;
        }
        return gain;
    }

    private String makeKey(Endpoint endpoint, Video video) {
        return endpoint.getId() + "-" + video.getId();
    }

    /** Pairs a video with its computed score for sorting. */
    private static class VideoScore {
        private final Video video;
        private final double score;

        VideoScore(Video video, double score) {
            this.video = video;
            this.score = score;
        }

        Video getVideo() { return video; }
        double getScore() { return score; }
    }

    /** Pairs an endpoint with the number of requests it makes for a video. */
    private static class EndpointRequest {
        private final Endpoint endpoint;
        private final int nbRequests;

        EndpointRequest(Endpoint endpoint, int nbRequests) {
            this.endpoint = endpoint;
            this.nbRequests = nbRequests;
        }

        Endpoint getEndpoint() { return endpoint; }
        int getNbRequests() { return nbRequests; }
    }
}
