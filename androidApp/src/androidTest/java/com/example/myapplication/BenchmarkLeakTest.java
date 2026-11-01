package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import leakcanary.LeakAssertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class BenchmarkLeakTest {
    @Test
    public void finishingDuringBenchmarkReleasesActivity() {
        assumeTrue("Stockfish binaries are packaged for ARM devices only",
            Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")
                || Arrays.asList(Build.SUPPORTED_ABIS).contains("armeabi-v7a"));
        int readersBefore = stockfishReaders();
        Context context = ApplicationProvider.getApplicationContext();
        File results = new File(context.getFilesDir(), "bench/results.jsonl");
        results.delete();
        Intent intent = new Intent(context, MainActivity.class)
            .putExtra("bench_iterations", 100);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            long deadline = SystemClock.uptimeMillis() + 10_000;
            while ((!results.exists() || stockfishReaders() <= readersBefore)
                && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50);
            }
            assertTrue("Benchmark did not start", results.exists());
            assertTrue("Benchmark engine did not start", stockfishReaders() > readersBefore);
        }
        LeakAssertions.INSTANCE.assertNoLeaks("finish during benchmark");
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (stockfishReaders() > readersBefore && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
        }
        assertTrue("Benchmark left a Stockfish reader/process running after Activity destruction",
            stockfishReaders() <= readersBefore);
    }

    private static int stockfishReaders() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().equals("StockfishReader")) count++;
        }
        return count;
    }
}
