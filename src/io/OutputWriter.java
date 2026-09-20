package io;

import model.Cache;
import model.Problem;
import model.Video;

import java.util.List;
import java.util.stream.Collectors;

public class OutputWriter {

    /**
     * Prints the solution to the console in the exact format required by the Google Hash Code submission.
     */
    public void writeToConsole(Problem problem) {
        // 1. Filter out caches that have no videos, as they don't need to be in the output
        List<Cache> usedCaches = problem.getCaches().stream()
                .filter(c -> !c.getCachedVideos().isEmpty())
                .collect(Collectors.toList());

        // 2. Print N (the number of cache server descriptions to follow)
        System.out.println(usedCaches.size());

        // 3. For each used cache, print its ID followed by the IDs of the videos it stores
        for (Cache cache : usedCaches) {
            StringBuilder sb = new StringBuilder();
            sb.append(cache.getId());
            
            for (Video video : cache.getCachedVideos()) {
                sb.append(" ").append(video.getId());
            }
            
            System.out.println(sb.toString());
        }
    }
}
