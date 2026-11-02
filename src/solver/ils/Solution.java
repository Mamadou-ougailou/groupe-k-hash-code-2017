package solver.ils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Solution {

    public final FlatProblem problem;
    private final long[] cacheVideoBits;
    public final int[] usedCapacity;

    public Solution(FlatProblem problem) {
        this.problem = problem;
        this.cacheVideoBits = new long[(int) (((long) problem.cacheCount * problem.videoCount + 63) >>> 6)];
        this.usedCapacity = new int[problem.cacheCount];
    }

    public boolean contains(int cache, int video) {
        int bitIndex = cache * problem.videoCount + video;
        return (cacheVideoBits[bitIndex >>> 6] & (1L << bitIndex)) != 0;
    }

    public boolean add(int cache, int video) {
        if (contains(cache, video)) return false;
        int size = problem.videoSize[video];
        if (usedCapacity[cache] + size > problem.cacheCapacity) return false;
        int bitIndex = cache * problem.videoCount + video;
        cacheVideoBits[bitIndex >>> 6] |= (1L << bitIndex);
        usedCapacity[cache] += size;
        return true;
    }

    public boolean remove(int cache, int video) {
        if (!contains(cache, video)) return false;
        int bitIndex = cache * problem.videoCount + video;
        cacheVideoBits[bitIndex >>> 6] &= ~(1L << bitIndex);
        usedCapacity[cache] -= problem.videoSize[video];
        return true;
    }

    public void clearCache(int cache) {
        if (usedCapacity[cache] == 0) return;
        for (int video = 0; video < problem.videoCount; video++) {
            if (contains(cache, video)) { int bitIndex = cache * problem.videoCount + video; cacheVideoBits[bitIndex >>> 6] &= ~(1L << bitIndex); }
        }
        usedCapacity[cache] = 0;
    }

    public int remainingCapacity(int cache) { return problem.cacheCapacity - usedCapacity[cache]; }

    public Solution copy() {
        Solution duplicate = new Solution(problem);
        duplicate.copyFrom(this);
        return duplicate;
    }

    public void copyFrom(Solution other) {
        System.arraycopy(other.cacheVideoBits, 0, cacheVideoBits, 0, cacheVideoBits.length);
        System.arraycopy(other.usedCapacity, 0, usedCapacity, 0, usedCapacity.length);
    }

    public void validateOrThrow() {
        for (int cache = 0; cache < problem.cacheCount; cache++) {
            int sizeSum = 0;
            for (int video = 0; video < problem.videoCount; video++) if (contains(cache, video)) sizeSum += problem.videoSize[video];
            if (sizeSum != usedCapacity[cache])
                throw new IllegalStateException("cache " + cache + " : compteur desynchronise ("
                        + usedCapacity[cache] + " vs " + sizeSum + ")");
            if (sizeSum > problem.cacheCapacity)
                throw new IllegalStateException("cache " + cache + " : capacite depassee ("
                        + sizeSum + " > " + problem.cacheCapacity + ")");
        }
    }

    public void write(Path out) throws IOException {
        StringBuilder builder = new StringBuilder(1 << 20);
        int nonEmptyCacheCount = 0;
        for (int cache = 0; cache < problem.cacheCount; cache++) if (usedCapacity[cache] > 0) nonEmptyCacheCount++;
        builder.append(nonEmptyCacheCount).append('\n');
        for (int cache = 0; cache < problem.cacheCount; cache++) {
            if (usedCapacity[cache] == 0) continue;
            builder.append(cache);
            for (int video = 0; video < problem.videoCount; video++) if (contains(cache, video)) builder.append(' ').append(video);
            builder.append('\n');
        }
        try (BufferedWriter writer = Files.newBufferedWriter(out)) { writer.write(builder.toString()); }
    }
}
