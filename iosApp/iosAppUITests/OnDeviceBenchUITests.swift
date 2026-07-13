import XCTest

final class OnDeviceBenchUITests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.
        continueAfterFailure = false
    }

    func testColdInitBenchmark() throws {
        // We will run the app N times to collect cold-init samples.
        // The app is configured to write to bench_results.jsonl in its Documents dir when BENCHMARK_MODE is set.
        let iterations = 20
        
        for i in 1...iterations {
            print("Running iteration \(i) of \(iterations)...")
            let app = XCUIApplication()
            app.launchEnvironment = ["BENCHMARK_MODE": "1"]
            // Optional: simulate cold init by setting a flag if we wanted warm vs cold
            
            // Launch the app
            app.launch()
            
            // Wait for the app to finish its benchmark.
            // In BENCHMARK_MODE, the app runs the benchmark task and exits with 0.
            // XCTest considers the app exiting as a failure if it's not expected, but wait, `exit(0)` might fail the test.
            // Let's just wait for a certain duration or until the app is not running.
            let exists = app.wait(for: .notRunning, timeout: 60.0)
            XCTAssertTrue(exists, "App should terminate after benchmark")
            
            // Terminate just in case it didn't exit cleanly
            app.terminate()
        }
    }
}
