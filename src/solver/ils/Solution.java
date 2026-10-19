package solver.ils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Solution {

    public final Instance in;
    private final long[] bits;
    public final int[] used;

    public Solution(Instance in) {
        this.in = in;
        this.bits = new long[(int) (((long) in.C * in.V + 63) >>> 6)];
        this.used = new int[in.C];
    }

    public boolean has(int c, int v) {
        int i = c * in.V + v;
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    public boolean add(int c, int v) {
        if (has(c, v)) return false;
        int s = in.videoSize[v];
        if (used[c] + s > in.X) return false;
        int i = c * in.V + v;
        bits[i >>> 6] |= (1L << i);
        used[c] += s;
        return true;
    }

    public boolean remove(int c, int v) {
        if (!has(c, v)) return false;
        int i = c * in.V + v;
        bits[i >>> 6] &= ~(1L << i);
        used[c] -= in.videoSize[v];
        return true;
    }

    public void clearCache(int c) {
        if (used[c] == 0) return;
        for (int v = 0; v < in.V; v++) {
            if (has(c, v)) { int i = c * in.V + v; bits[i >>> 6] &= ~(1L << i); }
        }
        used[c] = 0;
    }

    public int remaining(int c) { return in.X - used[c]; }

    public Solution copy() {
        Solution s = new Solution(in);
        s.copyFrom(this);
        return s;
    }

    public void copyFrom(Solution other) {
        System.arraycopy(other.bits, 0, bits, 0, bits.length);
        System.arraycopy(other.used, 0, used, 0, used.length);
    }

    public void validateOrThrow() {
        for (int c = 0; c < in.C; c++) {
            int sum = 0;
            for (int v = 0; v < in.V; v++) if (has(c, v)) sum += in.videoSize[v];
            if (sum != used[c])
                throw new IllegalStateException("cache " + c + " : compteur desynchronise ("
                        + used[c] + " vs " + sum + ")");
            if (sum > in.X)
                throw new IllegalStateException("cache " + c + " : capacite depassee ("
                        + sum + " > " + in.X + ")");
        }
    }

    public void write(Path out) throws IOException {
        StringBuilder sb = new StringBuilder(1 << 20);
        int nonEmpty = 0;
        for (int c = 0; c < in.C; c++) if (used[c] > 0) nonEmpty++;
        sb.append(nonEmpty).append('\n');
        for (int c = 0; c < in.C; c++) {
            if (used[c] == 0) continue;
            sb.append(c);
            for (int v = 0; v < in.V; v++) if (has(c, v)) sb.append(' ').append(v);
            sb.append('\n');
        }
        try (BufferedWriter w = Files.newBufferedWriter(out)) { w.write(sb.toString()); }
    }
}

