

import model.Problem;
import io.Parser;
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

        System.out.println("Successfully parsed problem!");
        System.out.println("Number of videos: " + problem.getVideos().size());
        System.out.println("Number of endpoints: " + problem.getEndpoints().size());
        System.out.println("Number of caches: " + problem.getCaches().size());
        System.out.println("Number of request descriptions: " + problem.getRequests().size());

        // 2. Run Solver (Developers can plug in their own solvers here)
        Solver solver = new Solver() {
            @Override
            public void solve(Problem p) {
                // TODO: Implement solving logic
                System.out.println("Running Dummy Solver...");
            }
        };

        solver.solve(problem);

        // 3. Output results
        io.OutputWriter writer = new io.OutputWriter();
        writer.writeToConsole(problem);
    }
}