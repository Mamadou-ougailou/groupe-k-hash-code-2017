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
        FastScanner sc = null;
        try {
            sc = new FastScanner(filePath);
        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + filePath);
            return null;
        }

        int numberOfVideos = sc.nextInt();
        int numberOfEndpoints = sc.nextInt();
        int numberOfRequests = sc.nextInt();
        int numberOfCaches = sc.nextInt();
        int cacheCapacity = sc.nextInt();

        // 1. Parse Videos
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < numberOfVideos; i++) {
            videos.add(new Video(i, sc.nextInt()));
        }

        // 2. Initialize Caches (we need them for Endpoints)
        List<Cache> caches = new ArrayList<>();
        for (int i = 0; i < numberOfCaches; i++) {
            caches.add(new Cache(i, cacheCapacity));
        }

        // 3. Parse Endpoints
        List<Endpoint> endpoints = new ArrayList<>();
        for (int e = 0; e < numberOfEndpoints; e++) {
            int dataCenterLatency = sc.nextInt();
            Endpoint endpoint = new Endpoint(e, dataCenterLatency);

            int numConnectedCaches = sc.nextInt();
            for (int j = 0; j < numConnectedCaches; j++) {
                int cacheId = sc.nextInt();
                int latency = sc.nextInt();
                endpoint.addCacheConnection(caches.get(cacheId), latency);
            }
            endpoints.add(endpoint);
        }

        // 4. Parse Requests
        List<RequestDescription> requests = new ArrayList<>();
        for (int i = 0; i < numberOfRequests; i++) {
            int videoId = sc.nextInt();
            int endpointId = sc.nextInt();
            int numRequests = sc.nextInt();

            Video video = videos.get(videoId);
            Endpoint endpoint = endpoints.get(endpointId);
            requests.add(new RequestDescription(video, endpoint, numRequests));
        }

        sc.close();

        return new Problem(videos, endpoints, caches, requests, cacheCapacity);
    }
}
