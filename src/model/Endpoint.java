package model;

import java.util.HashMap;
import java.util.Map;

public class Endpoint {
    private int id;
    private int dataCenterLatency;
    // Maps a Cache to the latency from this endpoint to that cache
    private Map<Cache, Integer> cacheLatencies;

    public Endpoint(int id, int dataCenterLatency) {
        this.id = id;
        this.dataCenterLatency = dataCenterLatency;
        this.cacheLatencies = new HashMap<>();
    }

    public int getId() {
        return id;
    }

    public int getDataCenterLatency() {
        return dataCenterLatency;
    }

    public Map<Cache, Integer> getCacheLatencies() {
        return cacheLatencies;
    }

    public void addCacheConnection(Cache cache, int latency) {
        this.cacheLatencies.put(cache, latency);
    }
}
