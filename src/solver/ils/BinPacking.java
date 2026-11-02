package solver.ils;

import java.util.Random;

public final class BinPacking {

    public static boolean isDegenerate(FlatProblem problem) {
        if (problem.endpointCount == 0 || problem.cacheCount == 0) return false;
        int commonDataCenterLatency = problem.dataCenterLatency[0];
        if (problem.endpointLinkStart[1] - problem.endpointLinkStart[0] != problem.cacheCount) return false;
        int commonCacheLatency = problem.linkLatency[0];
        for (int endpoint = 0; endpoint < problem.endpointCount; endpoint++) {
            if (problem.dataCenterLatency[endpoint] != commonDataCenterLatency) return false;
            if (problem.endpointLinkStart[endpoint + 1] - problem.endpointLinkStart[endpoint] != problem.cacheCount) return false;
            for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++)
                if (problem.linkLatency[link] != commonCacheLatency) return false;
        }
        return true;
    }

    private final FlatProblem problem;
    private final int videoCount, cacheCount, cacheCapacity;
    private final long[] videoRequestWeight;
    private final int[] cacheOfVideo, positionInCache, remainingCapacity, videoCountInCache;
    private int[][] videosInCache;
    private final int[] unplacedVideos;
    private int unplacedCount;
    private long unplacedRequestWeight;

    private BinPacking(FlatProblem problem) {
        this.problem = problem; videoCount = problem.videoCount; cacheCount = problem.cacheCount; cacheCapacity = problem.cacheCapacity;
        videoRequestWeight = new long[videoCount];
        for (int request = 0; request < problem.requestCount; request++) videoRequestWeight[problem.requestVideo[request]] += problem.requestViewCount[request];
        cacheOfVideo = new int[videoCount]; positionInCache = new int[videoCount];
        remainingCapacity = new int[cacheCount]; videoCountInCache = new int[cacheCount];
        videosInCache = new int[cacheCount][16];
        unplacedVideos = new int[videoCount];
        java.util.Arrays.fill(cacheOfVideo, -1);
        java.util.Arrays.fill(remainingCapacity, cacheCapacity);
    }

    private void addToCache(int cache, int video) {
        if (videoCountInCache[cache] == videosInCache[cache].length) {
            int[] grown = new int[videosInCache[cache].length * 2];
            System.arraycopy(videosInCache[cache], 0, grown, 0, videoCountInCache[cache]);
            videosInCache[cache] = grown;
        }
        videosInCache[cache][videoCountInCache[cache]] = video;
        positionInCache[video] = videoCountInCache[cache]++;
        cacheOfVideo[video] = cache;
        remainingCapacity[cache] -= problem.videoSize[video];
    }

    private void removeFromCache(int video) {
        int cache = cacheOfVideo[video], position = positionInCache[video], lastPosition = --videoCountInCache[cache];
        int movedVideo = videosInCache[cache][lastPosition];
        videosInCache[cache][position] = movedVideo; positionInCache[movedVideo] = position;
        cacheOfVideo[video] = -1;
        remainingCapacity[cache] += problem.videoSize[video];
    }

    private int bestFitCache(int video) {
        int size = problem.videoSize[video], bestCache = -1, bestLeftover = Integer.MAX_VALUE;
        for (int cache = 0; cache < cacheCount; cache++)
            if (remainingCapacity[cache] >= size && remainingCapacity[cache] - size < bestLeftover) { bestLeftover = remainingCapacity[cache] - size; bestCache = cache; }
        return bestCache;
    }

    public static Solution solve(FlatProblem problem, long budgetMs, boolean verbose) {
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;

        for (int attempt = 0; attempt < 40 && System.nanoTime() < deadlineNanos; attempt++) {
            BinPacking packing = new BinPacking(problem);
            if (packing.tryExactPacking(new Random(attempt * 7919L + 1))) {
                if (verbose) System.out.printf("  remplissage EXACT reussi (essai %d) : 0 video non placee%n",
                        attempt + 1);
                return packing.toSolution(problem);
            }
        }

        if (verbose) System.out.println("  remplissage exact non trouve -> BFD + reparations");
        BinPacking packing = new BinPacking(problem);
        packing.packWithRepairs(Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000L), verbose);
        return packing.toSolution(problem);
    }

    private Solution toSolution(FlatProblem problem) {
        Solution solution = new Solution(problem);
        for (int video = 0; video < problem.videoCount; video++) if (cacheOfVideo[video] >= 0) solution.add(cacheOfVideo[video], video);
        return solution;
    }

    private boolean tryExactPacking(Random rng) {
        int[] pool = new int[videoCount];
        for (int i = 0; i < videoCount; i++) pool[i] = i;
        for (int i = videoCount - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int swapped = pool[i]; pool[i] = pool[j]; pool[j] = swapped;
        }
        int poolCount = videoCount;

        final int SAMPLE = 420;
        boolean[] reachable = new boolean[cacheCapacity + 1];
        int[] lastItemForSum = new int[cacheCapacity + 1];
        int[] sample = new int[SAMPLE];

        for (int cache = 0; cache < cacheCount; cache++) {
            int sampleCount = Math.min(SAMPLE, poolCount);
            for (int i = 0; i < sampleCount; i++) sample[i] = pool[poolCount - sampleCount + i];

            java.util.Arrays.fill(reachable, false);
            java.util.Arrays.fill(lastItemForSum, -1);
            reachable[0] = true;
            for (int i = 0; i < sampleCount; i++) {
                int size = problem.videoSize[sample[i]];
                for (int sum = cacheCapacity; sum >= size; sum--)
                    if (!reachable[sum] && reachable[sum - size]) { reachable[sum] = true; lastItemForSum[sum] = i; }
                if (reachable[cacheCapacity]) break;
            }
            if (!reachable[cacheCapacity]) return false;

            boolean[] used = new boolean[sampleCount];
            int sum = cacheCapacity;
            while (sum > 0) {
                int item = lastItemForSum[sum];
                if (item < 0) return false;
                used[item] = true;
                addToCache(cache, sample[item]);
                sum -= problem.videoSize[sample[item]];
            }

            int writeIndex = poolCount - sampleCount;
            for (int i = 0; i < sampleCount; i++) if (!used[i]) pool[writeIndex++] = sample[i];
            poolCount = writeIndex;
        }
        return poolCount == 0;
    }

    private void packWithRepairs(long budgetMs, boolean verbose) {
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        Random rng = new Random(20170223L);

        Integer[] boxedVideos = new Integer[videoCount];
        for (int i = 0; i < videoCount; i++) boxedVideos[i] = i;
        java.util.Arrays.sort(boxedVideos, (first, second) -> problem.videoSize[second] - problem.videoSize[first]);
        int[] videosBySizeDescending = new int[videoCount];
        for (int i = 0; i < videoCount; i++) videosBySizeDescending[i] = boxedVideos[i];

        for (int i = 0; i < videoCount; i++) {
            int video = videosBySizeDescending[i], cache = bestFitCache(video);
            if (cache >= 0) addToCache(cache, video);
            else { unplacedVideos[unplacedCount++] = video; unplacedRequestWeight += videoRequestWeight[video]; }
        }
        long totalSize = 0;
        for (int video = 0; video < videoCount; video++) totalSize += problem.videoSize[video];
        if (verbose)
            System.out.printf("  BFD : %d videos non placees, gaspillage %d Mo (capacite=%d, somme=%d)%n",
                    unplacedCount, totalRemainingCapacity(), (long) cacheCount * cacheCapacity, totalSize);

        int randomRepairIterations = 0;
        while (unplacedCount > 0 && System.nanoTime() < deadlineNanos) {
        boolean progress = true;
        while (unplacedCount > 0 && progress && System.nanoTime() < deadlineNanos) {
            progress = false;
            for (int unplacedIndex = 0; unplacedIndex < unplacedCount && !progress; unplacedIndex++) {
                int unplacedVideo = unplacedVideos[unplacedIndex], unplacedSize = problem.videoSize[unplacedVideo];
                for (int cache = 0; cache < cacheCount && !progress; cache++) {
                    if (remainingCapacity[cache] >= unplacedSize) continue;
                    if (cacheCapacity - unplacedSize < 0) continue;

                    for (int position = videoCountInCache[cache] - 1; position >= 0 && remainingCapacity[cache] < unplacedSize; position--) {
                        int movedVideo = videosInCache[cache][position];
                        int movedSize = problem.videoSize[movedVideo], destination = -1, bestLeftover = Integer.MAX_VALUE;
                        for (int otherCache = 0; otherCache < cacheCount; otherCache++)
                            if (otherCache != cache && remainingCapacity[otherCache] >= movedSize && remainingCapacity[otherCache] - movedSize < bestLeftover) {
                                bestLeftover = remainingCapacity[otherCache] - movedSize; destination = otherCache;
                            }
                        if (destination >= 0) { removeFromCache(movedVideo); addToCache(destination, movedVideo); position = videoCountInCache[cache]; }
                    }
                    if (remainingCapacity[cache] >= unplacedSize) {
                        addToCache(cache, unplacedVideo);
                        unplacedVideos[unplacedIndex] = unplacedVideos[--unplacedCount];
                        unplacedRequestWeight -= videoRequestWeight[unplacedVideo];
                        progress = true;
                    }

                }
            }

            if (!progress) {
                for (int unplacedIndex = 0; unplacedIndex < unplacedCount && !progress; unplacedIndex++) {
                    int unplacedVideo = unplacedVideos[unplacedIndex], unplacedSize = problem.videoSize[unplacedVideo];
                    for (int cache = 0; cache < cacheCount && !progress; cache++) {
                        boolean moved = true;
                        while (remainingCapacity[cache] < unplacedSize && moved && System.nanoTime() < deadlineNanos) {
                            moved = false;
                            for (int positionX = 0; positionX < videoCountInCache[cache] && !moved; positionX++) {
                                int videoX = videosInCache[cache][positionX], sizeX = problem.videoSize[videoX];
                                for (int otherCache = 0; otherCache < cacheCount && !moved; otherCache++) {
                                    if (otherCache == cache) continue;
                                    for (int positionY = 0; positionY < videoCountInCache[otherCache]; positionY++) {
                                        int videoY = videosInCache[otherCache][positionY], sizeDifference = sizeX - problem.videoSize[videoY];
                                        if (sizeDifference <= 0 || sizeDifference > remainingCapacity[otherCache]) continue;
                                        removeFromCache(videoX); removeFromCache(videoY);
                                        addToCache(otherCache, videoX); addToCache(cache, videoY);
                                        moved = true; break;
                                    }
                                }
                            }
                        }
                        if (remainingCapacity[cache] >= unplacedSize) {
                            addToCache(cache, unplacedVideo);
                            unplacedVideos[unplacedIndex] = unplacedVideos[--unplacedCount];
                            unplacedRequestWeight -= videoRequestWeight[unplacedVideo];
                            progress = true;
                        }
                    }
                }
            }

            if (!progress) {
                for (int unplacedIndex = 0; unplacedIndex < unplacedCount && !progress; unplacedIndex++) {
                    int unplacedVideo = unplacedVideos[unplacedIndex], unplacedSize = problem.videoSize[unplacedVideo];
                    for (int cache = 0; cache < cacheCount && !progress; cache++) {
                        int neededSpace = unplacedSize - remainingCapacity[cache];
                        if (neededSpace <= 0) continue;
                        for (int positionX = 0; positionX < videoCountInCache[cache] && !progress; positionX++) {
                            int videoX = videosInCache[cache][positionX], sizeX = problem.videoSize[videoX];
                            if (sizeX < neededSpace) continue;
                            for (int otherCache = 0; otherCache < cacheCount && !progress; otherCache++) {
                                if (otherCache == cache) continue;
                                for (int positionY = 0; positionY < videoCountInCache[otherCache]; positionY++) {
                                    int videoY = videosInCache[otherCache][positionY], sizeDifference = sizeX - problem.videoSize[videoY];
                                    if (sizeDifference < neededSpace || sizeDifference > remainingCapacity[otherCache]) continue;
                                    removeFromCache(videoX); removeFromCache(videoY);
                                    addToCache(otherCache, videoX); addToCache(cache, videoY);
                                    addToCache(cache, unplacedVideo);
                                    unplacedVideos[unplacedIndex] = unplacedVideos[--unplacedCount];
                                    unplacedRequestWeight -= videoRequestWeight[unplacedVideo];
                                    progress = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (unplacedCount == 0) break;

        int[] undoVideos = new int[64]; int[] undoCaches = new int[64];
        long phaseEndNanos = Math.min(deadlineNanos, System.nanoTime() + 3_000_000_000L);
        while (unplacedCount > 0 && System.nanoTime() < phaseEndNanos) {
            randomRepairIterations++;
            int unplacedIndex = rng.nextInt(unplacedCount);
            int unplacedVideo = unplacedVideos[unplacedIndex];
            int unplacedSize = problem.videoSize[unplacedVideo];
            int cache = rng.nextInt(cacheCount);

            int undoCount = 0;
            long weightBefore = unplacedRequestWeight;

            int guard = 0;
            while (remainingCapacity[cache] < unplacedSize && videoCountInCache[cache] > 0 && guard++ < 32) {
                int evictedVideo = videosInCache[cache][rng.nextInt(videoCountInCache[cache])];
                if (undoCount == undoVideos.length) {
                    undoVideos = java.util.Arrays.copyOf(undoVideos, undoCount * 2);
                    undoCaches = java.util.Arrays.copyOf(undoCaches, undoCount * 2);
                }
                undoVideos[undoCount] = evictedVideo; undoCaches[undoCount] = cache; undoCount++;
                removeFromCache(evictedVideo);
            }
            if (remainingCapacity[cache] < unplacedSize) { restore(undoVideos, undoCaches, undoCount); continue; }

            addToCache(cache, unplacedVideo);
            unplacedVideos[unplacedIndex] = unplacedVideos[--unplacedCount];
            unplacedRequestWeight -= videoRequestWeight[unplacedVideo];

            int firstNewUnplaced = unplacedCount;
            for (int i = 0; i < undoCount; i++) {
                int evictedVideo = undoVideos[i], newCache = bestFitCache(evictedVideo);
                if (newCache >= 0) addToCache(newCache, evictedVideo);
                else { unplacedVideos[unplacedCount++] = evictedVideo; unplacedRequestWeight += videoRequestWeight[evictedVideo]; }
            }

            if (unplacedRequestWeight > weightBefore) {

                for (int i = unplacedCount - 1; i >= firstNewUnplaced; i--) unplacedRequestWeight -= videoRequestWeight[unplacedVideos[i]];
                unplacedCount = firstNewUnplaced;
                for (int i = 0; i < undoCount; i++) if (cacheOfVideo[undoVideos[i]] >= 0) removeFromCache(undoVideos[i]);
                removeFromCache(unplacedVideo);
                unplacedVideos[unplacedCount++] = unplacedVideo; unplacedRequestWeight += videoRequestWeight[unplacedVideo];
                restore(undoVideos, undoCaches, undoCount);
            }
        }
        }
        if (verbose)
            System.out.printf("  reparation : %d iterations -> %d non placees, gaspillage %d Mo%n",
                    randomRepairIterations, unplacedCount, totalRemainingCapacity());
    }

    private void restore(int[] videos, int[] caches, int count) {
        for (int i = 0; i < count; i++)
            if (cacheOfVideo[videos[i]] < 0 && remainingCapacity[caches[i]] >= problem.videoSize[videos[i]]) addToCache(caches[i], videos[i]);
    }

    private int totalRemainingCapacity() { int total = 0; for (int cache = 0; cache < cacheCount; cache++) total += remainingCapacity[cache]; return total; }
}
