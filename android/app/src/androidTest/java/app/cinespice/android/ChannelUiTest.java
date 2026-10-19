package app.cinespice.android;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChannelUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private Telegram telegram;
    private String oldStage;
    private Library oldLibrary;
    private ActivityScenario<MainActivity> scenario;
    @Before public void setup() {
        telegram = ((CinespiceApp) context.getApplicationContext()).telegram;
        oldStage = telegram.stage; oldLibrary = telegram.library;
        context.deleteDatabase("library_-95.db");
        telegram.stage = "ready"; telegram.library = new Library(context, -95);
        telegram.library.chat(-101, "Cinema archive", true, 1); telegram.library.select(-101, true);
        telegram.library.chat(-102, "Documentary club", false, 2);
        telegram.library.chat(-103, "Cinema discoveries", false, 3);
        Video video = new Video(Json.obj("chatId", -101, "messageId", 1, "name", "A night at the cinema.mp4", "duration", 5400, "size", 1073741824)); telegram.library.save(video);
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> set(activity, "discovering", true)); // Keep synthetic fixtures offline.
    }
    @After public void cleanup() {
        if (scenario != null) scenario.close();
        telegram.stage = oldStage; telegram.library = oldLibrary;
        context.deleteDatabase("library_-95.db");
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void navigate(MainActivity activity, String page) {
        try { Method method = MainActivity.class.getDeclaredMethod("navigate", String.class); method.setAccessible(true); method.invoke(activity, page); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static View described(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = described(group.getChildAt(i), description); if (found != null) return found; }
        return null;
    }
    private static boolean hasText(View root, String value) {
        if (root instanceof TextView text && text.getText().toString().equals(value)) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), value)) return true;
        return false;
    }
    private void screenshot(String name) throws IOException {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out)); } finally { image.recycle(); }
    }
    @Test public void selectedSourcesOnlyAndAddRemovePersist() throws Exception {
        screenshot("ui-library");
        scenario.onActivity(activity -> {
            navigate(activity, "sources"); View choices = activity.findViewById(R.id.channel_choices);
            assertTrue(hasText(choices, "Cinema archive")); assertFalse(hasText(choices, "Documentary club"));
        }); screenshot("ui-my-channels");
        scenario.onActivity(activity -> {
            navigate(activity, "addSources"); View choices = activity.findViewById(R.id.channel_choices);
            assertTrue(hasText(choices, "Documentary club"));
            assertTrue(described(choices, "Add Documentary club").performClick());
            assertEquals(2, telegram.library.chats(true).size());
            assertTrue(described(choices, "Remove Documentary club").performClick());
            assertEquals(1, telegram.library.chats(true).size());
            navigate(activity, "sources"); assertFalse(hasText(activity.findViewById(R.id.channel_choices), "Documentary club"));
        });
    }
    @Test public void discoveryRefreshKeepsFieldQueryCursorAndKeyboard() throws Exception {
        scenario.onActivity(activity -> {
            navigate(activity, "addSources"); EditText field = activity.findViewById(R.id.channel_search);
            field.requestFocus(); field.setText("Cinema"); field.setSelection(3);
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            EditText field = activity.findViewById(R.id.channel_search);
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        waitForKeyboard(true);
        telegram.library.chat(-104, "Cinema newly discovered", false, 4);
        scenario.onActivity(activity -> {
            EditText before = activity.findViewById(R.id.channel_search); activity.refreshChannelChoices();
            assertSame(before, activity.findViewById(R.id.channel_search)); assertTrue(before.hasFocus());
            assertEquals("Cinema", before.getText().toString()); assertEquals(3, before.getSelectionStart());
            assertTrue(hasText(activity.findViewById(R.id.channel_choices), "Cinema newly discovered"));
            assertFalse(hasText(activity.findViewById(R.id.channel_choices), "Documentary club"));
        });
        waitForKeyboard(true); screenshot("ui-search-keyboard");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForKeyboard(false);
        scenario.onActivity(activity -> { assertNotNull(activity.findViewById(R.id.channel_search)); assertTrue(hasText(activity.getWindow().getDecorView(), "Add channels")); });
        scenario.recreate();
        scenario.onActivity(activity -> assertEquals("Cinema", ((EditText)activity.findViewById(R.id.channel_search)).getText().toString()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> assertTrue(hasText(activity.getWindow().getDecorView(), "Only the channels and groups you've added.")));
    }
    @Test public void videoSearchDraftSurvivesRecreation() {
        scenario.onActivity(activity -> { navigate(activity, "search"); ((EditText) activity.findViewById(R.id.video_search)).setText("The long goodbye"); });
        scenario.recreate();
        scenario.onActivity(activity -> assertEquals("The long goodbye", ((EditText)activity.findViewById(R.id.video_search)).getText().toString()));
    }
    private void waitForKeyboard(boolean expected) {
        AtomicBoolean visible = new AtomicBoolean(); long end = SystemClock.elapsedRealtime() + 8000;
        do {
            scenario.onActivity(activity -> { WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView()); visible.set(insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())); });
            if (visible.get() == expected) return; SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        assertEquals("Keyboard visibility", expected, visible.get());
    }
}
