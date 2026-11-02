package io;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public class Parser {

    public Problem parse(String filePath) {
        FastScanner scanner = null;
        try {
            scanner = new FastScanner(filePath);
        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + filePath);
            return null;
        }

        int numberOfVideos = scanner.nextInt();
        int numberOfEndpoints = scanner.nextInt();
        int numberOfRequests = scanner.nextInt();
        int numberOfCaches = scanner.nextInt();
        int cacheCapacity = scanner.nextInt();

        // 1. Parse Videos
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < numberOfVideos; i++) {
            videos.add(new Video(i, scanner.nextInt()));
        }

        // 2. Initialize Caches (we need them for Endpoints)
        List<Cache> caches = new ArrayList<>();
        for (int i = 0; i < numberOfCaches; i++) {
            caches.add(new Cache(i, cacheCapacity));
        }

        // 3. Parse Endpoints
        List<Endpoint> endpoints = new ArrayList<>();
        for (int endpointIndex = 0; endpointIndex < numberOfEndpoints; endpointIndex++) {
            int dataCenterLatency = scanner.nextInt();
            Endpoint endpoint = new Endpoint(endpointIndex, dataCenterLatency);

            int connectedCacheCount = scanner.nextInt();
            for (int j = 0; j < connectedCacheCount; j++) {
                int cacheId = scanner.nextInt();
                int latency = scanner.nextInt();
                Cache cache = caches.get(cacheId);
                endpoint.addCacheConnection(cache, latency);
                // Reverse mapping: let the cache know about this endpoint
                cache.addEndpointConnection(endpoint, latency);
            }
            endpoints.add(endpoint);
        }

        // 4. Parse Requests
        List<RequestDescription> requests = new ArrayList<>();
        for (int i = 0; i < numberOfRequests; i++) {
            int videoId = scanner.nextInt();
            int endpointId = scanner.nextInt();
            int viewCount = scanner.nextInt();

            Video video = videos.get(videoId);
            Endpoint endpoint = endpoints.get(endpointId);
            requests.add(new RequestDescription(video, endpoint, viewCount));
            // Populate endpoint's requested videos map
            endpoint.addVideoRequest(video, viewCount);
        }

        scanner.close();

        return new Problem(videos, endpoints, caches, requests, cacheCapacity);
    }
}
