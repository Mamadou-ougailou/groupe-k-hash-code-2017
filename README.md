# Hash Code 2017: Streaming Videos
This project is the GROUP K Problem Solver for the 2017 Google Hash Code qualification round.

## Project Structure
* **`model/`**: Domain objects (`Video`, `Endpoint`, `Cache`, `RequestDescription`, `Problem`).
* **`io/`**: Input parsing (`FastScanner`, `Parser`) and output formatting (`OutputWriter`).
* **`solver/`**: Algorithm implementations. Extend `Solver.java` to build your own heuristic!
* **`GoogleHash2017.java`**: Application entry point that stitches I/O and solvers together.

## How to Compile & Run
1. **Compile**: Compile all Java files and output the `.class` files into the `out/` directory:
   `javac -d out src/GoogleHash2017.java src/model/*.java src/io/*.java src/solver/*.java src/solver/impl/*.java`
2. **Run**: Execute `Main`, passing the path to an input instance file as an argument:
   `java -cp out GoogleHash2017 instances/me_at_the_zoo.in`
