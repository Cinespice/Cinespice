package app.cinespice.android;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;

public final class MainActivity extends Activity implements Telegram.Listener {
    private Telegram telegram;
    private LinearLayout root, body;
    private TextView status;
    private String renderedStage = "", page = "home", query = "", chatTitle = "";
    private long chat;
    private int displayLimit = 150;
    private boolean busy, more = true, alive;
    private SearchSession search;
    private Future<?> opening;
    private ProgressDialog openingDialog;
    private long openingToken;
    private Video exportVideo;
    private Artwork artwork;
    private long artworkSession = -1;
    private final Map<ImageView, Video> artworkViews = new HashMap<>();
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Map<TextView, Video> downloadLabels = new HashMap<>();
    private final Runnable refreshDownloads = new Runnable() {
        @Override public void run() {
            for (Map.Entry<TextView, Video> row : downloadLabels.entrySet()) row.getKey().setText(details(row.getValue()));
            if (alive) timer.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); telegram = ((CinespiceApp) getApplication()).telegram;
        if (state != null) { page = state.getString("page", "home"); chat = state.getLong("chat"); chatTitle = state.getString("title", ""); }
        telegram.listen(this); render();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("page", page); state.putLong("chat", chat); state.putString("title", chatTitle); }
    @Override protected void onResume() { super.onResume(); alive = true; timer.post(refreshDownloads); if (telegram.stage.equals("ready")) render(); }
    @Override protected void onPause() { alive = false; timer.removeCallbacks(refreshDownloads); super.onPause(); }
    @Override protected void onDestroy() { telegram.unlisten(this); cancelOpen(); if (artwork != null) artwork.close(); super.onDestroy(); }
    @Override public void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (!renderedStage.equals(telegram.stage)) { busy = false; page = "home"; chat = 0; search = null; render(); }
        else if (status != null && !telegram.error.isEmpty()) status.setText(telegram.error);
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        renderedStage = telegram.stage; downloadLabels.clear(); artworkViews.clear();
        root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(Ui.dp(this, 16), insets.getSystemWindowInsetTop() + Ui.dp(this, 8), Ui.dp(this, 16), insets.getSystemWindowInsetBottom() + Ui.dp(this, 8)); return insets.consumeSystemWindowInsets(); });
        setContentView(root); root.requestApplyInsets();
        LinearLayout brand = Ui.row(this);
        ImageView icon = new ImageView(this); icon.setImageResource(app.cinespice.android.R.drawable.app_icon); icon.setContentDescription("Cinespice icon"); brand.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = Ui.heading(this, "  Cinespice"); Ui.weighted(brand, title); root.addView(brand);
        status = Ui.text(this, telegram.error, 14, Ui.ACCENT); root.addView(status);
        if (!telegram.stage.equals("ready")) { auth(); return; }
        if (artworkSession != telegram.generation) {
            if (artwork != null) artwork.close();
            artwork = new Artwork(telegram, new File(getCacheDir(), "artwork_" + telegram.requireLibrary().accountId)); artworkSession = telegram.generation;
        }
        LinearLayout nav = Ui.row(this);
        Ui.weighted(nav, Ui.button(this, "Library", () -> navigate("home")));
        Ui.weighted(nav, Ui.button(this, "My channels", () -> navigate("sources")));
        Ui.weighted(nav, Ui.button(this, "Settings", () -> navigate("settings"))); root.addView(nav);
        if (busy) { ProgressBar progress = new ProgressBar(this); root.addView(progress); }
        ScrollView scroll = new ScrollView(this); body = Ui.column(this); body.setPadding(0, 0, 0, 0); scroll.addView(body); scroll.getViewTreeObserver().addOnScrollChangedListener(this::loadVisibleArtwork); scroll.post(this::loadVisibleArtwork); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case "sources" -> sources(); case "settings" -> settings();
            case "collection" -> collection(); case "search" -> searchPage(); case "history" -> history(); default -> home();
        }
    }
    private void auth() {
        ScrollView scroll = new ScrollView(this); LinearLayout form = Ui.column(this); scroll.addView(form); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        form.addView(Ui.heading(this, "Your private video library"));
        form.addView(Ui.text(this, "Connect your Telegram account. Browse videos from the channels and groups you choose, then watch with VLC.", 16, Ui.MUTED));
        String stage = telegram.stage;
        if (stage.equals("credentials")) {
            form.addView(Ui.text(this, "Get your own API ID and hash at my.telegram.org. These stay encrypted on this device.", 15, Ui.MUTED));
            form.addView(Ui.button(this, "Get Telegram API credentials ↗", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org")))));
            EditText id = Ui.input(this, "API ID", InputType.TYPE_CLASS_NUMBER); id.setContentDescription("API ID"); form.addView(id);
            EditText hash = Ui.input(this, "API hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); hash.setContentDescription("API hash"); form.addView(hash);
            Button connect = Ui.button(this, "Connect Telegram", () -> { String apiId = id.getText().toString().trim(), apiHash = hash.getText().toString().trim(); run("Connecting…", () -> telegram.connect(apiId, apiHash), this::render); });
            connect.setEnabled(!busy); form.addView(connect);
        } else if (Set.of("phone", "code", "password", "email", "emailCode").contains(stage)) {
            String label = switch (stage) { case "phone" -> "Phone number with country code"; case "code" -> "Telegram login code"; case "password" -> "Two-step verification password"; case "email" -> "Email address"; default -> "Email verification code"; };
            form.addView(Ui.text(this, label, 18, Ui.TEXT));
            int type = switch (stage) { case "phone" -> InputType.TYPE_CLASS_PHONE; case "code", "emailCode" -> InputType.TYPE_CLASS_NUMBER; case "password" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; case "email" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; default -> InputType.TYPE_CLASS_TEXT; };
            EditText value = Ui.input(this, label, type); value.setContentDescription(label); form.addView(value);
            if (stage.equals("code")) form.addView(Ui.text(this, "Check Telegram on your other devices for the sign-in code.", 14, Ui.MUTED));
            Button next = Ui.button(this, "Continue", () -> {
                if (value.getText().toString().isBlank()) { status.setText("Enter " + label.toLowerCase(Locale.ROOT)); return; }
                String authenticationValue = value.getText().toString();
                run("Checking with Telegram…", () -> telegram.authenticate(authenticationValue), () -> status.setText("Waiting for Telegram…"));
            }); next.setEnabled(!busy); form.addView(next);
        } else { form.addView(new ProgressBar(this)); form.addView(Ui.text(this, stage.equals("unsupported") ? telegram.error : "Connecting securely to Telegram…", 16, Ui.TEXT)); }
        form.addView(Ui.text(this, "No backend, subscriptions, or analytics. Your account session and library are stored locally.", 14, Ui.MUTED));
    }
    private void navigate(String destination) {
        if (busy || opening != null) return;
        page = destination; displayLimit = 150; query = ""; search = null; more = true; render();
        if (destination.equals("sources")) discoverMore();
    }
    private void discoverMore() {
        run("Finding channels and groups…", () -> more = telegram.discover(), () -> {
            render();
            if (more) timer.postDelayed(() -> { if (alive && page.equals("sources") && !busy && telegram.stage.equals("ready")) discoverMore(); }, 400);
        });
    }
    private void home() {
        body.addView(Ui.heading(this, "Your library"));
        body.addView(Ui.button(this, "Search selected channels", () -> { if (!busy) { page = "search"; chat = 0; render(); } }));
        body.addView(Ui.button(this, "Continue watching", () -> navigate("history")));
        List<Library.Chat> chats = telegram.requireLibrary().chats(true);
        if (chats.isEmpty()) body.addView(Ui.text(this, "Choose channels and groups in My channels to start your library.", 17, Ui.MUTED));
        for (Library.Chat source : chats) body.addView(Ui.button(this, source.title() + "  →", () -> {
            if (busy) return;
            chat = source.id(); chatTitle = source.title(); page = "collection"; more = true; displayLimit = 150;
            render(); run("Loading videos…", () -> more = telegram.page(chat, true), this::render);
        }));
    }
    private void sources() {
        body.addView(Ui.heading(this, "My channels"));
        body.addView(Ui.text(this, "Select channels and groups to include. Personal chats and Saved Messages are excluded.", 15, Ui.MUTED));
        EditText filter = Ui.input(this, "Find a channel or group", InputType.TYPE_CLASS_TEXT); body.addView(filter);
        body.addView(Ui.button(this, "Search Telegram", () -> {
            String text = filter.getText().toString().trim(); if (text.isEmpty()) return;
            run("Searching channels…", () -> telegram.searchChats(text), this::render);
        }));
        LinearLayout choices = Ui.column(this); body.addView(choices);
        List<Library.Chat> sources = telegram.requireLibrary().chats(false);
        Runnable show = () -> {
            choices.removeAllViews(); String text = filter.getText().toString().toLowerCase(Locale.ROOT);
            for (Library.Chat source : sources) {
                if (!source.title().toLowerCase(Locale.ROOT).contains(text)) continue;
                CheckBox toggle = new CheckBox(this); toggle.setText(source.title()); toggle.setTextColor(Ui.TEXT); toggle.setMinHeight(Ui.dp(this, 48)); toggle.setChecked(source.selected());
                toggle.setOnCheckedChangeListener((button, checked) -> telegram.requireLibrary().select(source.id(), checked)); choices.addView(toggle);
            }
        }; show.run();
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { show.run(); } public void afterTextChanged(android.text.Editable text) {}
        });
        if (more) body.addView(Ui.button(this, "Find more channels / Retry", () -> run("Finding more channels…", () -> more = telegram.discover(), this::render)));
        else body.addView(Ui.text(this, "All available sources loaded.", 14, Ui.MUTED));
    }
    private void collection() {
        body.addView(Ui.heading(this, chatTitle));
        body.addView(Ui.button(this, "Refresh newest videos", () -> run("Refreshing…", () -> more = telegram.page(chat, true), this::render)));
        showVideos(telegram.requireLibrary().videos(chat, "", false, displayLimit));
        body.addView(Ui.button(this, "Load more / Retry", () -> run("Loading older videos…", () -> { more = telegram.page(chat, false); displayLimit += 150; }, this::render)));
        if (!more) body.addView(Ui.text(this, "All video pages loaded. Load more also reveals older cached rows.", 14, Ui.MUTED));
    }
    private void searchPage() {
        body.addView(Ui.heading(this, "Search videos"));
        EditText text = Ui.input(this, "Title or filename", InputType.TYPE_CLASS_TEXT); text.setText(query); body.addView(text);
        body.addView(Ui.button(this, "Search", () -> {
            if (busy) return;
            query = text.getText().toString().trim(); if (query.isEmpty()) return;
            search = new SearchSession(telegram, telegram.requireLibrary().chats(true), query); displayLimit = 150;
            run("Searching selected sources…", () -> more = search.next(), this::render);
        }));
        if (!query.isEmpty()) {
            Map<String, Video> combined = new LinkedHashMap<>();
            for (Video video : telegram.requireLibrary().videos(0, query, false, displayLimit)) combined.put(video.key(), video);
            if (search != null) for (Video video : search.results.values()) combined.put(video.key(), video);
            showVideos(new ArrayList<>(combined.values()));
            if (search != null && more) body.addView(Ui.button(this, "Search more / Retry", () -> run("Searching more…", () -> { more = search.next(); displayLimit += 150; }, this::render)));
        }
    }
    private void history() { body.addView(Ui.heading(this, "Continue watching")); showVideos(telegram.requireLibrary().videos(0, "", true, displayLimit)); }
    private void showVideos(List<Video> videos) {
        if (videos.isEmpty()) body.addView(Ui.text(this, "No videos here yet. Load another page or choose more sources.", 16, Ui.MUTED));
        for (Video video : videos) {
            LinearLayout card = Ui.column(this); card.setBackground(Ui.card());
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 8)); body.addView(card, spacing);
            LinearLayout row = Ui.row(this);
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setContentDescription("Video thumbnail");
            row.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 64))); artworkViews.put(image, video);
            android.graphics.Bitmap cached = artwork.cached(video);
            if (cached != null) image.setImageBitmap(cached);
            else {
                String mini = video.data.optString("mini");
                if (!mini.isEmpty()) { try { byte[] bytes = android.util.Base64.decode(mini, android.util.Base64.DEFAULT); image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length)); } catch (IllegalArgumentException ignored) { } }
            }
            TextView name = Ui.text(this, video.name, 17, Ui.TEXT); name.setMaxLines(3); Ui.weighted(row, name); card.addView(row);
            TextView detail = Ui.text(this, details(video), 13, Ui.MUTED); card.addView(detail); downloadLabels.put(detail, video);
            long position = telegram.requireLibrary().position(video.key());
            LinearLayout actions = Ui.row(this);
            Ui.weighted(actions, Ui.button(this, position >= 5000 ? "Resume" : "Play", () -> open(video, true)));
            Ui.weighted(actions, Ui.button(this, "More", () -> videoMenu(video))); card.addView(actions);
        }
    }
    private void loadVisibleArtwork() {
        if (artwork == null || !alive) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        for (Map.Entry<ImageView, Video> entry : artworkViews.entrySet()) {
            ImageView image = entry.getKey(); Video video = entry.getValue();
            if (!image.getGlobalVisibleRect(visible)) continue;
            android.graphics.Bitmap cached = artwork.cached(video);
            if (cached != null) image.setImageBitmap(cached);
            else artwork.load(video, bitmap -> runOnUiThread(() -> { if (!isDestroyed() && artworkViews.get(image) == video) image.setImageBitmap(bitmap); }));
        }
    }
    private String details(Video video) {
        String extra = ""; JSONObject file = telegram.files.get(video.fileId);
        if (file != null) {
            JSONObject local = Json.child(file, "local");
            if (local.optBoolean("is_downloading_completed")) extra = " · Downloaded";
            else if (telegram.downloads.contains(video.fileId)) extra = " · Downloading " + (file.optLong("size") > 0 ? (local.optLong("downloaded_size") * 100 / file.optLong("size")) + "%" : "…");
        }
        return Ui.time(video.duration * 1000L) + " · " + String.format(Locale.ROOT, "%.1f MB", video.size / 1048576.0) + extra;
    }
    private void videoMenu(Video video) {
        new AlertDialog.Builder(this).setTitle(video.name).setItems(new String[]{"Play from beginning", "Download first", "Cancel download", "Save to Files…", "Mark watched", "Remove history"}, (dialog, which) -> {
            switch (which) {
                case 0 -> open(video, false);
                case 1 -> run("Starting download…", () -> telegram.download(video), () -> { status.setText("Downloading. Keep the app open; playback is available when complete."); });
                case 2 -> run("Cancelling download…", () -> telegram.cancelDownload(video), () -> status.setText("Download cancelled."));
                case 3 -> run("Preparing export…", () -> {
                    exportVideo = telegram.prepare(video);
                    if (telegram.completedPath(exportVideo).isEmpty()) throw new IllegalStateException("Download this video first, then save it.");
                }, () -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(video.mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, new File(video.name).getName()); startActivityForResult(intent, 100);
                });
                case 4 -> { telegram.requireLibrary().progress(video, video.duration * 1000L, video.duration * 1000L, true); render(); }
                case 5 -> { telegram.requireLibrary().removeHistory(video.key()); render(); }
            }
        }).show();
    }
    private void open(Video video, boolean resume) {
        if (opening != null || busy) return;
        final long token = ++openingToken;
        ProgressDialog dialog = new ProgressDialog(this); openingDialog = dialog; dialog.setTitle("Loading media…"); dialog.setMessage(video.name); dialog.setCancelable(true);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", (d, which) -> cancelOpen()); dialog.setOnCancelListener(d -> cancelOpen()); dialog.show();
        opening = telegram.io.submit(() -> {
            try {
                Video current = telegram.prepare(video);
                if (telegram.downloads.contains(current.fileId) && telegram.completedPath(current).isEmpty()) throw new IllegalStateException("Download first is in progress. Wait for it to finish.");
                long session = telegram.generation;
                runOnUiThread(() -> {
                    if (token != openingToken || isDestroyed()) return;
                    dialog.dismiss(); openingDialog = null; opening = null;
                    if (!telegram.stage.equals("ready") || telegram.generation != session) return;
                    Intent intent = new Intent(this, PlayerActivity.class).putExtra("video", current.data.toString()).putExtra("resume", resume).putExtra("generation", session); startActivity(intent);
                });
            } catch (Exception e) { runOnUiThread(() -> { if (token == openingToken && !isDestroyed()) { opening = null; dialog.dismiss(); status.setText(e.getMessage()); } }); }
        });
    }
    private void cancelOpen() { if (openingDialog != null) { openingDialog.dismiss(); openingDialog = null; } openingToken++; if (opening != null) { opening.cancel(true); opening = null; } }
    private void settings() {
        body.addView(Ui.heading(this, "Settings"));
        body.addView(Ui.text(this, "Cinespice for Android · 1.0\nTelegram + VLC\nDownloads and playback pause or stop when Android closes the app. Keep it open for downloads.", 16, Ui.MUTED));
        body.addView(Ui.button(this, "Clear downloaded videos", () -> confirm("Clear downloaded media?", () -> run("Clearing downloads…", telegram::clearDownloads, () -> status.setText("Downloaded media cleared.")))));
        body.addView(Ui.button(this, "Clear artwork cache", () -> run("Clearing artwork…", () -> telegram.request(Json.obj("@type", "optimizeStorage", "size", 0, "ttl", 0, "count", 0, "immunity_delay", 0, "file_types", new org.json.JSONArray().put(Json.obj("@type", "fileTypeThumbnail")))), () -> { if (artwork != null) artwork.clear(); status.setText("Artwork cache cleared."); })));
        body.addView(Ui.button(this, "Clear watch history", () -> confirm("Clear watch history?", () -> { telegram.requireLibrary().clearHistory(); status.setText("History cleared."); })));
        body.addView(Ui.button(this, "Sign out of Telegram", () -> confirm("Sign out of Telegram?", () -> run("Signing out…", () -> telegram.request(Json.obj("@type", "logOut")), this::render))));
        body.addView(Ui.button(this, "Open source notices", () -> {
            StringBuilder notices = new StringBuilder("TDLib 1.8.65 · up9cloud Android JNI (MIT)\nlibVLC Android 3.6.5 · LGPL 2.1 or later\n\nSources: github.com/tdlib/td, github.com/up9cloud/android-libtdjson, code.videolan.org/videolan/vlc-android/-/tree/3.6.5\nNative libraries are unmodified. Android app source and build scripts are in the Cinespice repository. LGPL permits debugging modifications to its components.\n\n");
            for (String name : new String[]{"TDLib.txt", "TDLib-JNI.txt", "libVLC.txt"}) {
                try (InputStream input = getAssets().open("licenses/" + name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096]; int count; while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
                    notices.append(name).append("\n").append(out.toString("UTF-8")).append("\n\n");
                } catch (IOException e) { notices.append("Could not load ").append(name).append("\n"); }
            }
            ScrollView scroll = new ScrollView(this); TextView text = Ui.text(this, notices.toString(), 14, Ui.TEXT); text.setTextIsSelectable(true); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle("Open source notices").setView(scroll).setPositiveButton("Close", null).show();
        }));
    }
    private void confirm(String title, Runnable action) { new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, w) -> action.run()).show(); }
    interface Work { void execute() throws Exception; }
    private void run(String message, Work work, Runnable done) {
        if (busy) return; busy = true; status.setText(message);
        telegram.io.execute(() -> {
            String failure = null;
            try { work.execute(); } catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            String result = failure;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed() || isFinishing()) return;
                if (result != null) status.setText(result + " · Try again.");
                else { telegram.error = ""; status.setText(""); done.run(); }
            });
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 100 && result == RESULT_OK && intent != null && intent.getData() != null && exportVideo != null) {
            Video video = exportVideo; Uri destination = intent.getData(); exportVideo = null;
            run("Saving video…", () -> {
                try (InputStream in = new FileInputStream(telegram.completedPath(video)); OutputStream out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new IOException("Could not open destination");
                    byte[] buffer = new byte[256 * 1024]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }, () -> status.setText("Video saved."));
        }
    }
    @Override public void onBackPressed() { if (!page.equals("home") && telegram.stage.equals("ready") && !busy) navigate("home"); else super.onBackPressed(); }
}
