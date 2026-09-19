package com.moa1er.androidremotecontroller;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public final class MainActivitySmokeTest {
    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Before
    public void wakeDeviceBeforeEspresso() throws Exception {
        executeShellCommand("settings put global stay_on_while_plugged_in 7");
        executeShellCommand("svc power stayon true");
        executeShellCommand("input keyevent KEYCODE_WAKEUP");
        executeShellCommand("input keyevent KEYCODE_MENU");
        executeShellCommand("wm dismiss-keyguard");
        executeShellCommand("input swipe 540 1600 540 400 300");
        SystemClock.sleep(500L);
    }

    @Test
    public void mainScreenOpensAddConnectionEditor() {
        onView(withId(R.id.connection_list)).check(matches(isDisplayed()));
        onView(withText(R.string.add_connection))
                .check(matches(isDisplayed()))
                .perform(click());
        onView(withText(R.string.add_connection_title)).check(matches(isDisplayed()));
        onView(withHint(R.string.connection_name_hint)).check(matches(isDisplayed()));
        onView(withText(R.string.automatic_resolution)).check(matches(isDisplayed()));
        onView(withText(R.string.max_video_size)).check(matches(isDisplayed()));
    }

    private static void executeShellCommand(String command) throws IOException {
        ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        output.close();
    }
}
