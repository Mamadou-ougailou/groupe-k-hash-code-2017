package solver.ils;

/** Lazy greedy (a.k.a. CELF): initial placement of videos in caches, best gain per MB first. */
public final class LazyGreedyPlacement {

    public static Solution run(FlatProblem problem, BestLatencyTracker tracker, boolean verbose) {
        final int videoCount = problem.videoCount, cacheCount = problem.cacheCount, requestCount = problem.requestCount;
        Solution solution = new Solution(problem);
        tracker.reset();

        long startNanos = System.nanoTime();

        long[] initialGain = new long[cacheCount * videoCount];
        for (int request = 0; request < requestCount; request++) {
            int endpoint = problem.requestEndpoint[request];
            int video = problem.requestVideo[request];
            long viewCount = problem.requestViewCount[request];
            int dataCenterLatency = problem.dataCenterLatency[endpoint];
            for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++) {
                int latencyGain = dataCenterLatency - problem.linkLatency[link];
                if (latencyGain > 0) initialGain[problem.linkedCache[link] * videoCount + video] += viewCount * latencyGain;
            }
        }

        int candidateCount = 0;
        for (int pairId = 0; pairId < initialGain.length; pairId++) if (initialGain[pairId] > 0) candidateCount++;

        MaxHeap heap = new MaxHeap(candidateCount);
        for (int pairId = 0; pairId < initialGain.length; pairId++) {
            if (initialGain[pairId] <= 0) continue;
            int video = pairId % videoCount;
            heap.rawAdd((double) initialGain[pairId] / problem.videoSize[video], pairId);
        }
        heap.heapify();
        initialGain = null;

        if (verbose)
            System.out.printf("  paires candidates : %d (sur %d theoriques)%n",
                    candidateCount, (long) cacheCount * videoCount);

        long placed = 0, reevaluations = 0, pushBacks = 0;
        while (heap.size() > 0) {
            int pairId = heap.topPayload();
            heap.pop();

            int cache = pairId / videoCount, video = pairId - cache * videoCount;
            if (solution.contains(cache, video)) continue;
            int videoSize = problem.videoSize[video];
            if (videoSize > solution.remainingCapacity(cache)) continue;

            long currentGain = trueGain(problem, tracker, cache, video);
            reevaluations++;
            if (currentGain <= 0) continue;

            double newPriority = (double) currentGain / videoSize;
            if (heap.size() == 0 || newPriority >= heap.topPriority()) {
                solution.add(cache, video);
                tracker.onVideoAdded(cache, video);
                placed++;
            } else {
                heap.push(newPriority, pairId);
                pushBacks++;
            }
        }

        if (verbose)
            System.out.printf("  placements=%d reevaluations=%d repousses=%d | %.2fs%n",
                    placed, reevaluations, pushBacks, (System.nanoTime() - startNanos) / 1e9);
        return solution;
    }

    private static long trueGain(FlatProblem problem, BestLatencyTracker tracker, int cache, int video) {
        final int cacheCount = problem.cacheCount;
        long gain = 0;
        for (int i = problem.videoRequestStart[video]; i < problem.videoRequestStart[video + 1]; i++) {
            int request = problem.videoRequestIds[i];
            int cacheLatency = problem.latencyMatrix[problem.requestEndpoint[request] * cacheCount + cache];
            if (cacheLatency < 0) continue;
            int latencyGain = tracker.bestLatency[request] - cacheLatency;
            if (latencyGain > 0) gain += (long) problem.requestViewCount[request] * latencyGain;
        }
        return gain;
    }

    static final class MaxHeap {
        private double[] priorities;
        private int[] payloads;
        private int size;

        MaxHeap(int capacity) { priorities = new double[Math.max(capacity, 16)]; payloads = new int[Math.max(capacity, 16)]; }

        int size() { return size; }
        double topPriority() { return priorities[0]; }
        int topPayload() { return payloads[0]; }

        void rawAdd(double priority, int payload) { priorities[size] = priority; payloads[size] = payload; size++; }

        void heapify() { for (int i = (size >>> 1) - 1; i >= 0; i--) siftDown(i); }

        void push(double priority, int payload) {
            if (size == priorities.length) grow();
            priorities[size] = priority; payloads[size] = payload;
            siftUp(size++);
        }

        void pop() {
            size--;
            priorities[0] = priorities[size]; payloads[0] = payloads[size];
            if (size > 0) siftDown(0);
        }

        private void grow() {
            int newCapacity = priorities.length + (priorities.length >> 1) + 16;
            double[] grownPriorities = new double[newCapacity]; System.arraycopy(priorities, 0, grownPriorities, 0, size); priorities = grownPriorities;
            int[] grownPayloads = new int[newCapacity];         System.arraycopy(payloads, 0, grownPayloads, 0, size); payloads = grownPayloads;
        }

        private void siftUp(int index) {
            double priority = priorities[index]; int payload = payloads[index];
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (priorities[parent] >= priority) break;
                priorities[index] = priorities[parent]; payloads[index] = payloads[parent]; index = parent;
            }
            priorities[index] = priority; payloads[index] = payload;
        }

        private void siftDown(int index) {
            double priority = priorities[index]; int payload = payloads[index];
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1, right = child + 1;
                if (right < size && priorities[right] > priorities[child]) child = right;
                if (priorities[child] <= priority) break;
                priorities[index] = priorities[child]; payloads[index] = payloads[child]; index = child;
            }
            priorities[index] = priority; payloads[index] = payload;
        }
    }
}
