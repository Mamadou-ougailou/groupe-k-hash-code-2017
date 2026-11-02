package solver.ils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FlatProblem {

    public final int videoCount;
    public final int endpointCount;
    public final int requestCount;
    public final int cacheCount;
    public final int cacheCapacity;

    public final int[] videoSize;
    public final int[] dataCenterLatency;

    public final int[] endpointLinkStart;
    public final int[] linkedCache;
    public final int[] linkLatency;

    public final int[] latencyMatrix;

    public final int[] requestVideo;
    public final int[] requestEndpoint;
    public final int[] requestViewCount;
    public final long totalRequests;

    public final int[] endpointRequestStart;

    public final int[] cacheLinkStart;
    public final int[] cacheLinkedEndpoint;
    public final int[] cacheLinkLatency;

    public final int[] videoRequestStart;
    public final int[] videoRequestIds;

    public final int endpointsWithoutCache;

    public FlatProblem(Path file) throws IOException {
        this(new TokenReader(Files.readAllBytes(file)));
    }

    public FlatProblem(int[] tokens) {
        this(new TokenReader(tokens));
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = b;
            b = a % b;
            a = remainder;
        }
        return Math.abs(a);
    }

    private FlatProblem(TokenReader reader) {

        videoCount = reader.next();  endpointCount = reader.next();  requestCount = reader.next();  cacheCount = reader.next();  int rawCacheCapacity = reader.next();

        videoSize = new int[videoCount];
        int sizeGcd = rawCacheCapacity;
        for (int video = 0; video < videoCount; video++) {
            videoSize[video] = reader.next();
            if (sizeGcd > 1) sizeGcd = gcd(sizeGcd, videoSize[video]);
        }
        if (sizeGcd > 1) {
            for (int video = 0; video < videoCount; video++) videoSize[video] /= sizeGcd;
            rawCacheCapacity /= sizeGcd;
        }
        cacheCapacity = rawCacheCapacity;

        dataCenterLatency = new int[endpointCount];
        int[] linksPerEndpoint = new int[endpointCount];

        int[] tempLinkedCache = new int[endpointCount * 4];
        int[] tempLinkLatency = new int[endpointCount * 4];
        int linkCount = 0;
        int endpointsWithoutCacheCount = 0;

        endpointLinkStart = new int[endpointCount + 1];
        for (int endpoint = 0; endpoint < endpointCount; endpoint++) {
            endpointLinkStart[endpoint] = linkCount;
            dataCenterLatency[endpoint] = reader.next();
            linksPerEndpoint[endpoint] = reader.next();
            if (linksPerEndpoint[endpoint] == 0) endpointsWithoutCacheCount++;
            if (linkCount + linksPerEndpoint[endpoint] > tempLinkedCache.length) {
                int newCapacity = Math.max(linkCount + linksPerEndpoint[endpoint], tempLinkedCache.length * 2);
                int[] grownCaches = new int[newCapacity]; System.arraycopy(tempLinkedCache, 0, grownCaches, 0, linkCount); tempLinkedCache = grownCaches;
                int[] grownLatencies = new int[newCapacity]; System.arraycopy(tempLinkLatency, 0, grownLatencies, 0, linkCount); tempLinkLatency = grownLatencies;
            }
            for (int link = 0; link < linksPerEndpoint[endpoint]; link++) {
                tempLinkedCache[linkCount] = reader.next();
                tempLinkLatency[linkCount] = reader.next();
                linkCount++;
            }
        }
        endpointLinkStart[endpointCount] = linkCount;
        endpointsWithoutCache = endpointsWithoutCacheCount;

        linkedCache = new int[linkCount];
        linkLatency = new int[linkCount];
        System.arraycopy(tempLinkedCache, 0, linkedCache, 0, linkCount);
        System.arraycopy(tempLinkLatency, 0, linkLatency, 0, linkCount);

        latencyMatrix = new int[endpointCount * cacheCount];
        java.util.Arrays.fill(latencyMatrix, -1);
        for (int endpoint = 0; endpoint < endpointCount; endpoint++) {
            int rowStart = endpoint * cacheCount;
            for (int link = endpointLinkStart[endpoint]; link < endpointLinkStart[endpoint + 1]; link++)
                latencyMatrix[rowStart + linkedCache[link]] = linkLatency[link];
        }

        cacheLinkStart = new int[cacheCount + 1];
        for (int link = 0; link < linkCount; link++) cacheLinkStart[linkedCache[link] + 1]++;
        for (int cache = 0; cache < cacheCount; cache++) cacheLinkStart[cache + 1] += cacheLinkStart[cache];
        cacheLinkedEndpoint = new int[linkCount];
        cacheLinkLatency = new int[linkCount];
        int[] nextSlotInCache = new int[cacheCount];
        for (int endpoint = 0; endpoint < endpointCount; endpoint++) {
            for (int link = endpointLinkStart[endpoint]; link < endpointLinkStart[endpoint + 1]; link++) {
                int cache = linkedCache[link];
                int slot = cacheLinkStart[cache] + nextSlotInCache[cache]++;
                cacheLinkedEndpoint[slot] = endpoint;
                cacheLinkLatency[slot] = linkLatency[link];
            }
        }

        requestVideo     = new int[requestCount];
        requestEndpoint  = new int[requestCount];
        requestViewCount = new int[requestCount];
        int[] rawVideo = new int[requestCount], rawEndpoint = new int[requestCount], rawViewCount = new int[requestCount];
        long totalViews = 0;
        endpointRequestStart = new int[endpointCount + 1];
        for (int request = 0; request < requestCount; request++) {
            rawVideo[request] = reader.next();
            rawEndpoint[request] = reader.next();
            rawViewCount[request] = reader.next();
            totalViews += rawViewCount[request];
            endpointRequestStart[rawEndpoint[request] + 1]++;
        }
        for (int endpoint = 0; endpoint < endpointCount; endpoint++) endpointRequestStart[endpoint + 1] += endpointRequestStart[endpoint];

        int[] requestsPerVideo = new int[videoCount + 1];
        int[] nextSlotInEndpoint = new int[endpointCount];
        for (int request = 0; request < requestCount; request++) {
            int slot = endpointRequestStart[rawEndpoint[request]] + nextSlotInEndpoint[rawEndpoint[request]]++;
            requestVideo[slot]     = rawVideo[request];
            requestEndpoint[slot]  = rawEndpoint[request];
            requestViewCount[slot] = rawViewCount[request];
            requestsPerVideo[rawVideo[request]]++;
        }
        totalRequests = totalViews;

        videoRequestStart = new int[videoCount + 1];
        for (int video = 0; video < videoCount; video++) videoRequestStart[video + 1] = videoRequestStart[video] + requestsPerVideo[video];
        int[] nextSlotForVideo = new int[videoCount];
        videoRequestIds = new int[requestCount];
        for (int request = 0; request < requestCount; request++) {
            int video = requestVideo[request];
            videoRequestIds[videoRequestStart[video] + nextSlotForVideo[video]++] = request;
        }
    }

    public int latency(int endpoint, int cache) { return latencyMatrix[endpoint * cacheCount + cache]; }

    public void printSummary() {
        int singleCacheEndpoints = 0, multiCacheEndpoints = 0;
        for (int endpoint = 0; endpoint < endpointCount; endpoint++) {
            int linkTotal = endpointLinkStart[endpoint + 1] - endpointLinkStart[endpoint];
            if (linkTotal == 1) singleCacheEndpoints++; else if (linkTotal > 1) multiCacheEndpoints++;
        }
        System.out.printf(
            "V=%d E=%d R=%d C=%d X=%d | requetes=%d%n" +
            "  endpoints : %d orphelins, %d mono-cache, %d multi-cache%n",
            videoCount, endpointCount, requestCount, cacheCount, cacheCapacity, totalRequests, endpointsWithoutCache, singleCacheEndpoints, multiCacheEndpoints);
    }

    private static final class TokenReader {
        private final byte[] bytes;
        private final int[] tokens;
        private int position;
        TokenReader(byte[] bytes) { this.bytes = bytes; this.tokens = null; }
        TokenReader(int[] tokens) { this.bytes = null; this.tokens = tokens; }
        int next() {
            if (tokens != null) return tokens[position++];
            byte[] data = bytes;
            int index = position, length = data.length;
            while (index < length && (data[index] < '0' || data[index] > '9')) index++;
            int value = 0;
            while (index < length && data[index] >= '0' && data[index] <= '9') { value = value * 10 + (data[index] - '0'); index++; }
            position = index;
            return value;
        }
    }
}
