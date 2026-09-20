package solver;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;

import java.util.Map;

/**
 * Calculates the submission score based on the Hash Code 2017 scoring formula.
 * 
 * Score = floor( (sum of time saved for all requests * 1000) / total number of requests )
 */
public class ScoreCalculator {

    public static long compute(Problem problem) {
        long totalTimeSaved = 0;
        long totalRequests = 0;

        for (RequestDescription request : problem.getRequests()) {
            Video video = request.getVideo();
            Endpoint endpoint = request.getEndpoint();
            int numberOfRequests = request.getNumberOfRequests();

            // Start with data center latency as the best option
            int bestLatency = endpoint.getDataCenterLatency();

            // Check all caches connected to this endpoint for a better latency
            for (Map.Entry<Cache, Integer> entry : endpoint.getCacheLatencies().entrySet()) {
                Cache cache = entry.getKey();
                int cacheLatency = entry.getValue();

                if (cache.getCachedVideos().contains(video) && cacheLatency < bestLatency) {
                    bestLatency = cacheLatency;
                }
            }

            int timeSaved = endpoint.getDataCenterLatency() - bestLatency;
            totalTimeSaved += (long) numberOfRequests * timeSaved;
            totalRequests += numberOfRequests;
        }

        if (totalRequests == 0) return 0;

        return (totalTimeSaved * 1000) / totalRequests;
    }
}
