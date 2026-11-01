package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import leakcanary.LeakAssertions;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real app: leaked Activities, cleared ViewModels and detached views fail the test. */
@RunWith(AndroidJUnit4.class)
public class ActivityLeakTest {
    private SharedPreferences preferences;
    private Boolean originalBoard3d;

    @Before
    public void saveBoardSetting() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("chess", Context.MODE_PRIVATE);
        originalBoard3d = preferences.contains("settings.board_3d_enabled")
            ? preferences.getBoolean("settings.board_3d_enabled", true) : null;
    }

    @After
    public void restoreBoardSetting() {
        SharedPreferences.Editor editor = preferences.edit();
        if (originalBoard3d == null) editor.remove("settings.board_3d_enabled");
        else editor.putBoolean("settings.board_3d_enabled", originalBoard3d);
        editor.commit();
    }

    // Both board paths: every recreation must release the previous composition. Split so CI can
    // keep the 2D half while excluding the one that needs a real GPU (see Filament3D).
    @Test
    public void recreationAndFinishReleaseActivity2D() {
        recreateAndFinish(false);
    }

    @Filament3D
    @Test
    public void recreationAndFinishReleaseActivity3D() {
        recreateAndFinish(true);
    }

    private void recreateAndFinish(boolean board3d) {
        setBoard3d(board3d);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int i = 0; i < 3; i++) {
                waitForGame();
                scenario.recreate();
            }
            waitForGame();
        }
        LeakAssertions.INSTANCE.assertNoLeaks("recreation and finish, 3D=" + board3d);
    }

    @Filament3D
    @Test
    public void navigatingAwayFromBoardReleasesViews() {
        setBoard3d(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            for (int i = 0; i < 3; i++) {
                String[][] destinations = {
                    {"Settings", "Engine difficulty"},
                    {"History", "Game History"},
                    {"Rules", "Chess rules"},
                    {"Chat", "Position Chat"},
                };
                for (String[] destination : destinations) {
                    waitForGame();
                    device.findObject(By.text(destination[0])).click();
                    assertTrue("Screen did not open: " + destination[0],
                        device.wait(Until.hasObject(By.text(destination[1])), 10_000));
                    device.pressBack();
                }
            }
            waitForGame();
        }
        LeakAssertions.INSTANCE.assertNoLeaks("board navigation and finish");
    }

    private static void setBoard3d(boolean enabled) {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("chess", Context.MODE_PRIVATE).edit()
            .putBoolean("settings.board_3d_enabled", enabled).commit();
    }

    private static void waitForGame() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertNotNull("Game controls did not appear",
            device.wait(Until.findObject(By.text("Settings")), 30_000));
        assertTrue("3D board did not finish loading",
            device.wait(Until.gone(By.text("Loading 3D Engine")), 30_000));
        device.waitForIdle();
    }
}
