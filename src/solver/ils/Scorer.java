package solver.ils;

public final class Scorer {

    public static long score(FlatProblem problem, Solution solution) {
        long totalSavedLatency = 0;
        for (int request = 0; request < problem.requestCount; request++) {
            int endpoint = problem.requestEndpoint[request];
            int video = problem.requestVideo[request];
            int bestLatency = problem.dataCenterLatency[endpoint];
            for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++) {
                int cacheLatency = problem.linkLatency[link];
                if (cacheLatency < bestLatency && solution.contains(problem.linkedCache[link], video)) bestLatency = cacheLatency;
            }
            totalSavedLatency += (long) problem.requestViewCount[request] * (problem.dataCenterLatency[endpoint] - bestLatency);
        }
        return totalSavedLatency * 1000L / problem.totalRequests;
    }

    public static long upperBound(FlatProblem problem) {
        long totalSavedLatency = 0;
        for (int request = 0; request < problem.requestCount; request++) {
            int endpoint = problem.requestEndpoint[request];
            int bestLatency = problem.dataCenterLatency[endpoint];
            for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++)
                if (problem.linkLatency[link] < bestLatency) bestLatency = problem.linkLatency[link];
            totalSavedLatency += (long) problem.requestViewCount[request] * (problem.dataCenterLatency[endpoint] - bestLatency);
        }
        return totalSavedLatency * 1000L / problem.totalRequests;
    }

    public static long validateAndScore(FlatProblem problem, Solution solution) {
        solution.validateOrThrow();
        return score(problem, solution);
    }
}
