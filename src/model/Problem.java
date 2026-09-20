package model;

import java.util.List;

public class Problem {
    private List<Video> videos;
    private List<Endpoint> endpoints;
    private List<Cache> caches;
    private List<RequestDescription> requests;
    private int cacheCapacity;

    public Problem(List<Video> videos, List<Endpoint> endpoints, List<Cache> caches, List<RequestDescription> requests, int cacheCapacity) {
        this.videos = videos;
        this.endpoints = endpoints;
        this.caches = caches;
        this.requests = requests;
        this.cacheCapacity = cacheCapacity;
    }

    public List<Video> getVideos() {
        return videos;
    }

    public List<Endpoint> getEndpoints() {
        return endpoints;
    }

    public List<Cache> getCaches() {
        return caches;
    }

    public List<RequestDescription> getRequests() {
        return requests;
    }

    public int getCacheCapacity() {
        return cacheCapacity;
    }
}
