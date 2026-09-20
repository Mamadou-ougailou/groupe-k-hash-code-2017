package model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Cache {
    private int id;
    private int capacity;
    private int usedCapacity;
    private Set<Video> cachedVideos;
    // Reverse mapping: which endpoints are connected to this cache, and at what latency
    private Map<Endpoint, Integer> endpointLatencies;

    public Cache(int id, int capacity) {
        this.id = id;
        this.capacity = capacity;
        this.usedCapacity = 0;
        this.cachedVideos = new HashSet<>();
        this.endpointLatencies = new HashMap<>();
    }

    public int getId() {
        return id;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getUsedCapacity() {
        return usedCapacity;
    }

    public int getRemainingCapacity() {
        return capacity - usedCapacity;
    }

    public Set<Video> getCachedVideos() {
        return cachedVideos;
    }

    public Map<Endpoint, Integer> getEndpointLatencies() {
        return endpointLatencies;
    }

    public void addEndpointConnection(Endpoint endpoint, int latency) {
        this.endpointLatencies.put(endpoint, latency);
    }

    public boolean canFit(Video video) {
        return video.getSize() <= getRemainingCapacity();
    }

    public void addVideo(Video video) {
        this.cachedVideos.add(video);
        this.usedCapacity += video.getSize();
    }
}
