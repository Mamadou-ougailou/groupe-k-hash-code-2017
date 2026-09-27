

import model.Problem;
import io.Parser;
import solver.Solver;
import io.OutputWriter;

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
        String outputFilePath;
        if (args.length >= 2) {
            outputFilePath = args[1];
        } else {
            java.io.File inputFile = new java.io.File(inputFilePath);
            String name = inputFile.getName();
            int dotIdx = name.lastIndexOf('.');
            String baseName = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
            outputFilePath = "output/" + baseName + ".out";
        }

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


        // 4. Output results to file
        io.OutputWriter writer = new io.OutputWriter();
        try {
            writer.writeToFile(problem, outputFilePath);
            System.out.println("Saved solution to: " + outputFilePath);
        } catch (java.io.IOException e) {
            System.err.println("Failed to write output file: " + e.getMessage());
        }
    }
}