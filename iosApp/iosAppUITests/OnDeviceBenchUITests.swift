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
            
            // Launch the app
            app.launch()
            
            // Wait for the app to finish its benchmark.
            let completeText = app.staticTexts["Benchmark Complete"]
            let exists = completeText.waitForExistence(timeout: 60.0)
            XCTAssertTrue(exists, "App should finish benchmark")
            
            // Terminate for the next cold init
            app.terminate()
        }
    }
}
