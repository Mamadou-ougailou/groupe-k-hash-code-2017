package solver.ils;

import java.util.Arrays;

public final class BlockKnapsack {

    private final FlatProblem problem;
    private final Solution solution;
    private final BestLatencyTracker tracker;

    private final long[] videoGain;
    private final long[] sortKey;
    private final int[]  videosByDensity;
    private final int[]  coreVideos;
    public static final int MAX_DP_CAP = 2_000_000;

    private final int[]  chosenVideos;
    private final int[]  greedySelection;
    private final int[]  previousVideos;
    private long[]       bestValueByCapacity;
    private long[]       takenBits;
    private final int    videoIdBits;
    private final long   videoIdMask;

    public long fallbacks;

    public BlockKnapsack(FlatProblem problem, Solution solution, BestLatencyTracker tracker) {
        this.problem = problem; this.solution = solution; this.tracker = tracker;
        videoGain       = new long[problem.videoCount];
        sortKey         = new long[problem.videoCount];
        videosByDensity = new int[problem.videoCount];
        coreVideos      = new int[problem.videoCount];
        chosenVideos    = new int[problem.videoCount];
        greedySelection = new int[problem.videoCount];
        previousVideos  = new int[problem.videoCount];
        bestValueByCapacity = new long[Math.min(problem.cacheCapacity + 1, 1024)];
        takenBits       = new long[1024];
        videoIdBits     = 32 - Integer.numberOfLeadingZeros(Math.max(1, problem.videoCount - 1));
        videoIdMask     = (1L << videoIdBits) - 1;
    }

    public void sweep(int[] cacheOrder) { sweep(cacheOrder, Long.MAX_VALUE); }

    public void sweep(int[] cacheOrder, long deadlineNs) { sweep(cacheOrder, cacheOrder.length, deadlineNs); }

    public void sweep(int[] cacheOrder, int length, long deadlineNs) {
        for (int i = 0; i < length; i++) {
            optimizeCache(cacheOrder[i]);
            if ((i & 15) == 15 && System.nanoTime() > deadlineNs) return;
        }
    }

    public int snapshot(int cache, int[] snapshotOut) {
        int count = 0;
        for (int video = 0; video < problem.videoCount; video++) if (solution.contains(cache, video)) snapshotOut[count++] = video;
        return count;
    }

    public void restore(int cache, int[] snapshot, int count) {
        clearCacheAndUpdate(cache);
        for (int i = 0; i < count; i++) place(cache, snapshot[i]);
    }

    public int clearCacheAndUpdate(int cache) {
        final int videoCount = problem.videoCount, cacheCount = problem.cacheCount;
        int previousCount = 0;
        for (int video = 0; video < videoCount; video++) if (solution.contains(cache, video)) previousVideos[previousCount++] = video;
        if (previousCount == 0) return 0;
        for (int i = 0; i < previousCount; i++) solution.remove(cache, previousVideos[i]);

        for (int i = 0; i < previousCount; i++) {
            int video = previousVideos[i];
            for (int j = problem.videoRequestStart[video]; j < problem.videoRequestStart[video + 1]; j++) {
                int request = problem.videoRequestIds[j];
                if (problem.latencyMatrix[problem.requestEndpoint[request] * cacheCount + cache] >= 0) tracker.recomputeRequest(request, solution);
            }
        }
        return previousCount;
    }

    public void optimizeCache(int cache) { optimizeCache(cache, 0.0, null); }

    public void optimizeCache(int cache, double noise, java.util.Random rng) {
        final int videoCount = problem.videoCount, cacheCapacity = problem.cacheCapacity;
        final int[] size = problem.videoSize;

        int previousCount = clearCacheAndUpdate(cache);

        Arrays.fill(videoGain, 0L);
        final int[] bestLatency = tracker.bestLatency;
        final int[] requestVideo = problem.requestVideo, requestViewCount = problem.requestViewCount;
        for (int i = problem.cacheLinkStart[cache], linksEnd = problem.cacheLinkStart[cache + 1]; i < linksEnd; i++) {
            int endpoint = problem.cacheLinkedEndpoint[i], cacheLatency = problem.cacheLinkLatency[i];
            for (int request = problem.endpointRequestStart[endpoint], requestsEnd = problem.endpointRequestStart[endpoint + 1]; request < requestsEnd; request++) {
                int latencyGain = bestLatency[request] - cacheLatency;
                if (latencyGain > 0) videoGain[requestVideo[request]] += (long) requestViewCount[request] * latencyGain;
            }
        }
        if (noise > 0) {
            for (int video = 0; video < videoCount; video++)
                if (videoGain[video] > 0)
                    videoGain[video] = Math.max(1L, (long) (videoGain[video] * (1.0 + noise * (2 * rng.nextDouble() - 1))));
        }

        int candidateCount = 0;
        for (int video = 0; video < videoCount; video++) {
            long gain = videoGain[video];
            if (gain <= 0 || size[video] > cacheCapacity) continue;
            long densityBits = Double.doubleToRawLongBits((double) gain / size[video]);
            sortKey[candidateCount++] = ((densityBits >>> videoIdBits) << videoIdBits) | video;
        }
        if (candidateCount == 0) return;
        Arrays.sort(sortKey, 0, candidateCount);
        for (int rank = 0; rank < candidateCount; rank++) videosByDensity[rank] = (int) (sortKey[candidateCount - 1 - rank] & videoIdMask);

        long cumulativeWeight = 0, cumulativeProfit = 0;
        int breakIndex = candidateCount;
        for (int rank = 0; rank < candidateCount; rank++) {
            int video = videosByDensity[rank];
            if (cumulativeWeight + size[video] > cacheCapacity) { breakIndex = rank; break; }
            cumulativeWeight += size[video];
            cumulativeProfit += videoGain[video];
        }
        if (breakIndex == candidateCount) {
            for (int rank = 0; rank < candidateCount; rank++) place(cache, videosByDensity[rank]);
            return;
        }

        int greedyCount = 0;
        for (int rank = 0; rank < breakIndex; rank++) greedySelection[greedyCount++] = videosByDensity[rank];
        long greedyValue = cumulativeProfit, remainingCapacity = cacheCapacity - cumulativeWeight;
        for (int rank = breakIndex; rank < candidateCount && remainingCapacity > 0; rank++) {
            int video = videosByDensity[rank];
            if (size[video] <= remainingCapacity) { greedySelection[greedyCount++] = video; remainingCapacity -= size[video]; greedyValue += videoGain[video]; }
        }
        long previousValue = 0;
        for (int i = 0; i < previousCount; i++) if (videoGain[previousVideos[i]] > 0) previousValue += videoGain[previousVideos[i]];
        final long lowerBound = Math.max(greedyValue, previousValue);

        int breakVideo = videosByDensity[breakIndex];
        final double breakDensity = (double) videoGain[breakVideo] / size[breakVideo];
        final double upperBound = cumulativeProfit + (double) (cacheCapacity - cumulativeWeight) * breakDensity;
        final double fixingMargin = Math.max(0.0, upperBound - lowerBound) + 1e-10 * upperBound + 1.0;

        int fixedCount = 0, coreCount = 0;
        long fixedWeight = 0, fixedProfit = 0, coreWeight = 0;
        for (int rank = 0; rank < candidateCount; rank++) {
            int video = videosByDensity[rank];
            double reducedCost = videoGain[video] - size[video] * breakDensity;
            if (reducedCost > fixingMargin)       { chosenVideos[fixedCount++] = video; fixedWeight += size[video]; fixedProfit += videoGain[video]; }
            else if (-reducedCost > fixingMargin) {  }
            else                                  { coreVideos[coreCount++] = video; coreWeight += size[video]; }
        }
        final long coreCapacityLong = cacheCapacity - fixedWeight;

        boolean solved = coreCapacityLong >= 0;
        int chosenCount = fixedCount;
        if (solved) {
            int coreCapacity = (int) coreCapacityLong;
            if (coreWeight <= coreCapacity) {
                for (int i = 0; i < coreCount; i++) chosenVideos[chosenCount++] = coreVideos[i];
                solved = fixedProfit + sumGain(coreVideos, coreCount) >= lowerBound;
            } else if (coreCapacity > MAX_DP_CAP) {
                solved = false;
            } else {

                final int wordsPerItem = (coreCapacity + 64) >>> 6;
                final int bitsNeeded = coreCount * wordsPerItem;
                if (takenBits.length < bitsNeeded) takenBits = new long[bitsNeeded];
                if (bestValueByCapacity.length < coreCapacity + 1) bestValueByCapacity = new long[coreCapacity + 1];
                Arrays.fill(takenBits, 0, bitsNeeded, 0L);
                Arrays.fill(bestValueByCapacity, 0, coreCapacity + 1, 0L);
                for (int i = 0; i < coreCount; i++) {
                    int video = coreVideos[i];
                    int itemWeight = size[video];
                    long itemProfit = videoGain[video];
                    int wordBase = i * wordsPerItem;
                    for (int capacity = coreCapacity; capacity >= itemWeight; capacity--) {
                        long candidateValue = bestValueByCapacity[capacity - itemWeight] + itemProfit;
                        if (candidateValue > bestValueByCapacity[capacity]) {
                            bestValueByCapacity[capacity] = candidateValue;
                            takenBits[wordBase + (capacity >>> 6)] |= 1L << capacity;
                        }
                    }
                }
                solved = fixedProfit + bestValueByCapacity[coreCapacity] >= lowerBound;
                if (solved) {
                    int capacity = coreCapacity;
                    for (int i = coreCount - 1; i >= 0; i--) {
                        if ((takenBits[i * wordsPerItem + (capacity >>> 6)] & (1L << capacity)) != 0) {
                            int video = coreVideos[i];
                            chosenVideos[chosenCount++] = video;
                            capacity -= size[video];
                        }
                    }
                }
            }
        }

        if (solved) {
            for (int i = 0; i < chosenCount; i++) place(cache, chosenVideos[i]);
        } else {
            fallbacks++;
            if (greedyValue >= previousValue) for (int i = 0; i < greedyCount; i++) place(cache, greedySelection[i]);
            else for (int i = 0; i < previousCount; i++) if (videoGain[previousVideos[i]] > 0) place(cache, previousVideos[i]);
        }
    }

    private long sumGain(int[] videos, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) sum += videoGain[videos[i]];
        return sum;
    }

    private void place(int cache, int video) {
        solution.add(cache, video);
        tracker.onVideoAdded(cache, video);
    }
}
