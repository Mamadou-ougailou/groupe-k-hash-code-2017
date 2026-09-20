package model;

import java.util.HashSet;
import java.util.Set;

public class Cache {
    private int id;
    private int capacity;
    private Set<Video> cachedVideos;

    public Cache(int id, int capacity) {
        this.id = id;
        this.capacity = capacity;
        this.cachedVideos = new HashSet<>();
    }

    public int getId() {
        return id;
    }

    public int getCapacity() {
        return capacity;
    }

    public Set<Video> getCachedVideos() {
        return cachedVideos;
    }

    public void addVideo(Video video) {
        this.cachedVideos.add(video);
    }
}
