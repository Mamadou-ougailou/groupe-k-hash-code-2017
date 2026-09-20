

import model.Problem;
import io.Parser;
import solver.ScoreCalculator;
import solver.impl.GreedySolver;
import solver.Solver;

public class GoogleHash2017 {
    public static void main(String[] args) {
        System.out.println("Google Hash Code 2017 - Streaming Videos");

        if (args.length < 1) {
            System.err.println("Please provide the path to the input file as an argument.");
            // Example for development:
            // args = new String[]{"instances/me_at_the_zoo.in"};
            return;
        }

        String inputFilePath = args[0];

        // 1. Parse Input
        Parser parser = new Parser();
        Problem problem = parser.parse(inputFilePath);

        if (problem == null) {
            System.err.println("Failed to parse the problem.");
            return;
        }

        // 2. Run Solver (Developers can plug in their own solvers here)
        Solver solver = new solver.impl.GreedyCELFSolver();
        System.out.println("Running GreedySolver...");
        solver.solve(problem);

        // 3. Calculate and display the score
        long score = ScoreCalculator.compute(problem);
        System.out.println("Score: " + score);

        // 4. Output results
        // io.OutputWriter writer = new io.OutputWriter();
        // writer.writeToConsole(problem);
    }
}