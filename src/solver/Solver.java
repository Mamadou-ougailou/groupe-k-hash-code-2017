package solver;

import model.Problem;

/**
 * Interface for all solvers.
 * By using this interface, multiple developers can work on different algorithms concurrently.
 */
public interface Solver {
    
    /**
     * Solves the given Hash Code problem instance.
     * 
     * @param problem The parsed problem object containing all data.
     */
    void solve(Problem problem);
}
