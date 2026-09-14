import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;
import java.io.FileNotFoundException;

public class Parser {
    private int NUMBER_OF_VIDEOS;
    private int NUMBER_OF_ENDPOINTS;
    private int NUMBER_OF_REQUESTS; 
    private int NUMBER_OF_CACHES;
    private int CACHE_CAPACITY;
    private int [] video_sizes;
    private int [] endpoints_data_latency;
    private List<List<int[]>> endpoints_to_caches;
    private List<List<int[]>> caches_to_endpoints;
    private Set<Integer>[] cache_content;
    private Request[] requests; 
    private int[] video_label_requests;

    static class FastScanner {
        BufferedReader br;
        StringTokenizer st;

        public FastScanner(String filePath) throws FileNotFoundException {
            br = new BufferedReader(new FileReader(filePath));
        }

        String next() {
            while (st == null || !st.hasMoreElements()) {
                try {
                    String line = br.readLine();
                    if (line == null) return null;
                    st = new StringTokenizer(line);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return st.nextToken();
        }

        int nextInt() {
            return Integer.parseInt(next());
        }

        void close() {
            try {
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void parse(String filePath) {
        FastScanner sc = null;
        try {
            sc = new FastScanner(filePath);
        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + filePath);
            return;
        }
        
        NUMBER_OF_VIDEOS = sc.nextInt();
        NUMBER_OF_ENDPOINTS = sc.nextInt();
        NUMBER_OF_REQUESTS = sc.nextInt();
        NUMBER_OF_CACHES = sc.nextInt();
        CACHE_CAPACITY = sc.nextInt();

        video_sizes = new int[NUMBER_OF_VIDEOS];
        for(int i = 0; i < NUMBER_OF_VIDEOS; i++){
            video_sizes[i] = sc.nextInt();
        }

        // Initialize data structures for endpoints and caches
        endpoints_data_latency = new int[NUMBER_OF_ENDPOINTS];
        endpoints_to_caches = new ArrayList<>();
        caches_to_endpoints = new ArrayList<>();
        
        for (int i = 0; i < NUMBER_OF_CACHES; i++) {
            caches_to_endpoints.add(new ArrayList<>());
        }

        // Parse Endpoints and Cache connections
        for (int e = 0; e < NUMBER_OF_ENDPOINTS; e++) {
            endpoints_data_latency[e] = sc.nextInt();
            int numConnectedCaches = sc.nextInt();
            
            List<int[]> currentEndpointCaches = new ArrayList<>();
            
            for (int j = 0; j < numConnectedCaches; j++) {
                int cacheId = sc.nextInt();
                int latency = sc.nextInt();
                
                // Add cache to endpoint's list
                currentEndpointCaches.add(new int[]{cacheId, latency});
                
                // Add reverse mapping (endpoint to cache's list)
                caches_to_endpoints.get(cacheId).add(new int[]{e, latency});
            }
            endpoints_to_caches.add(currentEndpointCaches);
        }

        // Parse Requests
        requests = new Request[NUMBER_OF_REQUESTS];
        for (int i = 0; i < NUMBER_OF_REQUESTS; i++) {
            int videoId = sc.nextInt();
            int endpointId = sc.nextInt();
            int numRequests = sc.nextInt();
            
            requests[i] = new Request(videoId, endpointId, numRequests);
        }

        // Optional: Initialize cache_content array so it's ready to use
        cache_content = new Set[NUMBER_OF_CACHES];
        for (int i = 0; i < NUMBER_OF_CACHES; i++) {
            cache_content[i] = new HashSet<>();
        }

        sc.close();
    }

    public void printData() {
        System.out.println("====== PARSED DATA ======");
        System.out.println("Videos: " + NUMBER_OF_VIDEOS + ", Endpoints: " + NUMBER_OF_ENDPOINTS 
                + ", Requests: " + NUMBER_OF_REQUESTS + ", Caches: " + NUMBER_OF_CACHES 
                + ", Cache Capacity: " + CACHE_CAPACITY + "MB");
        
        System.out.print("Video sizes (MB): ");
        for (int size : video_sizes) System.out.print(size + " ");
        System.out.println("\n");
        
        System.out.println("--- Endpoints ---");
        for (int i = 0; i < endpoints_data_latency.length; i++) {
            System.out.print("E" + i + " -> DataCenter Latency: " + endpoints_data_latency[i] + "ms");
            System.out.print(" | Connected Caches: ");
            for (int[] connection : endpoints_to_caches.get(i)) {
                System.out.print("[C" + connection[0] + ", " + connection[1] + "ms] ");
            }
            System.out.println();
        }
        
        System.out.println("\n--- Requests ---");
        for (Request req : requests) {
            System.out.println(req.numRequests + " requests for Video " + req.videoId + " from Endpoint " + req.endpointId);
        }
        System.out.println("=========================");
    }

    public static void main(String[] args) {
        Parser parser = new Parser();
        
        long startTime = System.currentTimeMillis();
        parser.parse("example.txt");
        long endTime = System.currentTimeMillis();
        
        parser.printData();
        
        System.out.println("Temps d'exécution du parsing : " + (endTime - startTime) + " ms");
    }
}
