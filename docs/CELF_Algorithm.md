# GreedyCELFSolver

A **naive greedy** approach recomputes the marginal gain of *every* (cache, video) pair after each placement  extremely slow.

**CELF (Cost-Effective Lazy Forward)** exploits the fact that marginal gains can only **decrease** as we place more videos. So instead of recomputing everything, we only recompute the **top candidate** and check if it's still the best. If yes, accept it; if not, push it back into the heap with its updated gain. This gives the **same result** as naive greedy but **much faster**.

### Step 1: Flatten Model into Primitive Arrays

The object model (`Video`, `Endpoint`, `Cache`, `RequestDescription`) is converted into flat `int[]` arrays for fast indexed access and zero autoboxing overhead.

### Step 2: Compute Initial Gains

For each (cache `c`, video `v`) pair, compute:

```
gain[c][v] = Σ  reqCount[r] × (dcLatency[e] − lat[e][c])
           for all requests r of video v
           where endpoint e is connected to cache c
           and dcLatency[e] > lat[e][c]
```

This is the total latency saved across all requests if we place video `v` on cache `c`, assuming **nothing is cached yet**.

### Step 3: Build Max-Heap

All (cache, video) pairs with positive gain are inserted into a **max-heap** sorted by **gain-per-byte** (`gain / videoSize`). This prioritises small videos with high gains.

```mermaid
flowchart TD
    subgraph Heap["Max-Heap (sorted by gain/size)"]
        H1["(c₂,v₅) gain/byte=12.5"]
        H2["(c₁,v₃) gain/byte=10.2"]
        H3["(c₃,v₁) gain/byte=8.7"]
        H4["..."]
    end

    style Heap fill:#fef3e2,stroke:#e67e22
    style H1 fill:#e74c3c,stroke:#c0392b,color:#fff
    style H2 fill:#e88e5a,stroke:#c0651e,color:#fff
    style H3 fill:#f0b27a,stroke:#c0651e
```

### Step 4: CELF Greedy Loop (Core Algorithm)

This is the heart of the solver:

```mermaid
flowchart TD
    START(["Heap empty?"]) -->|Yes| DONE(["Done"])
    START -->|No| POP["Pop top candidate (c, v)"]
    POP --> SKIP1{"Already placed?"}
    SKIP1 -->|Yes| START
    SKIP1 -->|No| SKIP2{"Video fits in cache?"}
    SKIP2 -->|No| START
    SKIP2 -->|Yes| RECOMP["Recompute true gain using current best1"]
    RECOMP --> ZERO{"gain ≤ 0?"}
    ZERO -->|Yes| START
    ZERO -->|No| COMPARE{"gain/size ≥ heap top?"}
    COMPARE -->|Yes| ACCEPT["✅ Accept placement"]
    COMPARE -->|No| PUSH["Push back with updated gain"]
    PUSH --> START
    ACCEPT --> UPDATE["Update best1/best2 for affected requests"]
    UPDATE --> START

    style START fill:#3498db,stroke:#2c5f8a,color:#fff
    style DONE fill:#27ae60,stroke:#1e8449,color:#fff
    style ACCEPT fill:#27ae60,stroke:#1e8449,color:#fff
    style PUSH fill:#e67e22,stroke:#c0651e,color:#fff
    style RECOMP fill:#9b59b6,stroke:#7d3c98,color:#fff
```



## Complexity

| Aspect | Naive Greedy | CELF |
|--------|-------------|------|
| Per iteration | Recompute **all** (C×V) gains | Recompute **only top** candidate |
| Worst case | O(iterations × C × V × R) | Same (but rarely hit) |
| Typical | — | ~2-5× fewer gain evaluations |
