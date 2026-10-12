
import model.Problem;
import io.Parser;
import solver.Solver;
import io.OutputWriter;

public class GoogleHash2017 {
    public static void main(String[] args) {
        System.out.println("Google Hash Code 2017 - Streaming Videos");

        if (args.length < 1) {
            System.err.println("Please provide the path to the input file as an argument.");
            return;
        }

        String inputFilePath = args[0];
        String outputFilePath = OutputWriter.getOutputFilePath(args, inputFilePath);

        // 1. Parse Input
        Parser parser = new Parser();
        Problem problem = parser.parse(inputFilePath);

        if (problem == null) {
            System.err.println("Failed to parse the problem.");
            return;
        }

        // 2. Run Solver (Developers can plug in their own solvers here)
        Solver solver = new solver.impl.ILSSolver();
        System.out.println("Running ILSSolver...");
        solver.solve(problem);

        OutputWriter.extracteIntoFile(problem, outputFilePath);
    }
}