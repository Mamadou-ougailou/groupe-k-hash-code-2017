package solver.ils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contenu des caches.
 *
 * Represente par un bitset plat (long[]) indexe par c * V + v : acces O(1),
 * memoire 8x plus compacte qu'un boolean[] (kittens : 625 Ko au lieu de 5 Mo),
 * ce qui le garde en cache CPU.
 */
public final class Solution {

    public final Instance in;
    private final long[] bits;   // bitset de taille C*V
    public final int[] used;     // [C] capacite consommee par cache

    public Solution(Instance in) {
        this.in = in;
        this.bits = new long[(int) (((long) in.C * in.V + 63) >>> 6)];
        this.used = new int[in.C];
    }

    public boolean has(int c, int v) {
        int i = c * in.V + v;
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    /** Ajoute v dans c. Retourne false si deja present ou si la capacite est depassee. */
    public boolean add(int c, int v) {
        if (has(c, v)) return false;
        int s = in.videoSize[v];
        if (used[c] + s > in.X) return false;
        int i = c * in.V + v;
        bits[i >>> 6] |= (1L << i);
        used[c] += s;
        return true;
    }

    /** Retire v de c. Retourne false si la video n'y etait pas. */
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

    /** Recopie en place : evite de reallouer (et de casser les references des optimiseurs). */
    public void copyFrom(Solution other) {
        System.arraycopy(other.bits, 0, bits, 0, bits.length);
        System.arraycopy(other.used, 0, used, 0, used.length);
    }

    /**
     * Verifie que la solution respecte la contrainte de capacite.
     * Une solution invalide vaut 0 point, pas "presque le bon score".
     */
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

    /** Ecrit le fichier de soumission au format impose par l'enonce. */
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
