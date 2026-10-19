package app.cinespice.android;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;

public final class MainActivity extends AppCompatActivity implements Telegram.Listener {
    private Telegram telegram;
    private LinearLayout root, body, channelChoices, videoResults;
    private EditText channelFilter;
    private TextView channelSummary;
    private LinearProgressIndicator channelProgress;
    private Button channelRetry;
    private String channelQuery = "", videoDraft = "";
    private boolean discovering, channelSearching, discoveryMore = true;
    private long sourceRequest;
    private TextView status;
    private String renderedStage = "", page = "home", query = "", chatTitle = "";
    private long chat;
    private int displayLimit = 150;
    private boolean busy, more = true, alive, resumed;
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
        if (state != null) { page = state.getString("page", "home"); chat = state.getLong("chat"); chatTitle = state.getString("title", ""); channelQuery = state.getString("channelQuery", ""); videoDraft = state.getString("videoDraft", ""); query = state.getString("query", ""); }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false); telegram.listen(this); render();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("page", page); state.putLong("chat", chat); state.putString("title", chatTitle); state.putString("channelQuery", channelQuery); state.putString("videoDraft", videoDraft); state.putString("query", query); }
    @Override protected void onResume() { super.onResume(); alive = true; timer.post(refreshDownloads); if (resumed && telegram.stage.equals("ready") && !isSourcesPage() && !page.equals("search")) render(); resumed = true; }
    @Override protected void onPause() { alive = false; timer.removeCallbacks(refreshDownloads); super.onPause(); }
    @Override protected void onDestroy() { telegram.unlisten(this); cancelOpen(); if (artwork != null) artwork.close(); super.onDestroy(); }
    @Override public void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (!renderedStage.equals(telegram.stage)) { busy = false; sourceRequest++; channelSearching = false; discovering = false; discoveryMore = true; channelQuery = ""; page = "home"; chat = 0; search = null; render(); }
        else if (status != null && !telegram.error.isEmpty()) status.setText(telegram.error);
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        renderedStage = telegram.stage; downloadLabels.clear(); artworkViews.clear();
        channelFilter = null; channelChoices = null; videoResults = null;
        root = Ui.column(this); root.setPadding(0, 0, 0, 0); root.setBackgroundColor(Ui.BG); root.setFocusableInTouchMode(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom)); return insets;
        });
        setContentView(root); ViewCompat.requestApplyInsets(root);
        LinearLayout brand = Ui.row(this); brand.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.app_icon); icon.setContentDescription("Cinespice icon"); brand.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView title = Ui.text(this, "  cinespice", 21, Ui.TEXT); title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); Ui.weighted(brand, title);
        TextView badge = Ui.text(this, "YOUR CINEMA", 10, Ui.ACCENT); badge.setLetterSpacing(.12f); brand.addView(badge); root.addView(brand);
        status = Ui.text(this, telegram.error, 13, Ui.ACCENT); status.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0); root.addView(status);
        if (!telegram.stage.equals("ready")) { auth(); return; }
        if (artworkSession != telegram.generation) {
            if (artwork != null) artwork.close();
            artwork = new Artwork(telegram, new File(getCacheDir(), "artwork_" + telegram.requireLibrary().accountId)); artworkSession = telegram.generation;
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = Ui.column(this); scroll.addView(body); scroll.getViewTreeObserver().addOnScrollChangedListener(this::loadVisibleArtwork); scroll.post(this::loadVisibleArtwork); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case "sources", "addSources" -> sources(); case "settings" -> settings();
            case "collection" -> collection(); case "search" -> searchPage(); case "history" -> history(); default -> home();
        }
        BottomNavigationView nav = new BottomNavigationView(this); nav.setBackgroundColor(Ui.BG);
        nav.getMenu().add(0, 1, 0, "Library").setIcon(R.drawable.nav_library);
        nav.getMenu().add(0, 2, 1, "My channels").setIcon(R.drawable.nav_channels);
        nav.getMenu().add(0, 3, 2, "Settings").setIcon(R.drawable.nav_settings);
        nav.setSelectedItemId(page.equals("settings") ? 3 : isSourcesPage() ? 2 : 1);
        nav.setOnItemSelectedListener(item -> { navigate(item.getItemId() == 2 ? "sources" : item.getItemId() == 3 ? "settings" : "home"); return true; });
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> { v.setPadding(0, 0, 0, 0); v.setVisibility(insets.isVisible(WindowInsetsCompat.Type.ime()) ? View.GONE : View.VISIBLE); return insets; });
    }
    private void auth() {
        ScrollView scroll = new ScrollView(this); LinearLayout form = Ui.column(this); scroll.addView(form); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        form.addView(Ui.text(this, "WATCH ON YOUR TERMS", 11, Ui.ACCENT));
        form.addView(Ui.heading(this, "Your channels.\nYour cinema."));
        form.addView(Ui.text(this, "Connect your Telegram account. Browse videos from the channels and groups you choose, then watch with VLC.", 16, Ui.MUTED));
        String stage = telegram.stage;
        if (stage.equals("credentials")) {
            form.addView(Ui.text(this, "Get your own API ID and hash at my.telegram.org. These stay encrypted on this device.", 15, Ui.MUTED));
            form.addView(Ui.quiet(this, "Get Telegram API credentials ↗", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org")))));
            EditText id = Ui.input(this, "API ID", InputType.TYPE_CLASS_NUMBER); id.setContentDescription("API ID"); form.addView(id);
            EditText hash = Ui.input(this, "API hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); hash.setContentDescription("API hash"); form.addView(hash);
            Button connect = Ui.primary(this, "Connect Telegram", () -> { String apiId = id.getText().toString().trim(), apiHash = hash.getText().toString().trim(); run("Connecting…", () -> telegram.connect(apiId, apiHash), this::render); });
            connect.setEnabled(!busy); form.addView(connect);
        } else if (Set.of("phone", "code", "password", "email", "emailCode").contains(stage)) {
            String label = switch (stage) { case "phone" -> "Phone number with country code"; case "code" -> "Telegram login code"; case "password" -> "Two-step verification password"; case "email" -> "Email address"; default -> "Email verification code"; };
            form.addView(Ui.text(this, label, 18, Ui.TEXT));
            int type = switch (stage) { case "phone" -> InputType.TYPE_CLASS_PHONE; case "code", "emailCode" -> InputType.TYPE_CLASS_NUMBER; case "password" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; case "email" -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; default -> InputType.TYPE_CLASS_TEXT; };
            EditText value = Ui.input(this, label, type); value.setContentDescription(label); form.addView(value);
            if (stage.equals("code")) form.addView(Ui.text(this, "Check Telegram on your other devices for the sign-in code.", 14, Ui.MUTED));
            Button next = Ui.primary(this, "Continue", () -> {
                if (value.getText().toString().isBlank()) { status.setText("Enter " + label.toLowerCase(Locale.ROOT)); return; }
                String authenticationValue = value.getText().toString();
                run("Checking with Telegram…", () -> telegram.authenticate(authenticationValue), () -> status.setText("Waiting for Telegram…"));
            }); next.setEnabled(!busy); form.addView(next);
        } else { form.addView(new ProgressBar(this)); form.addView(Ui.text(this, stage.equals("unsupported") ? telegram.error : "Connecting securely to Telegram…", 16, Ui.TEXT)); }
        form.addView(Ui.text(this, "No backend, subscriptions, or analytics. Your account session and library are stored locally.", 14, Ui.MUTED));
    }
    private void navigate(String destination) {
        if (opening != null) return;
        hideKeyboard();
        page = destination; displayLimit = 150; query = ""; search = null; more = true; render();
        if (destination.equals("addSources")) discoverMore();
    }
    private boolean isSourcesPage() { return page.equals("sources") || page.equals("addSources"); }
    private void hideKeyboard() { WindowCompat.getInsetsController(getWindow(), root).hide(WindowInsetsCompat.Type.ime()); root.requestFocus(); }
    private void discoverMore() {
        if (discovering || !discoveryMore || !telegram.stage.equals("ready")) return;
        discovering = true; refreshChannelProgress(); final long session = telegram.generation;
        telegram.io.execute(() -> {
            boolean next = true; String error = null;
            try { next = telegram.discover(); } catch (Exception e) { error = e.getMessage(); }
            final boolean hasMore = next; final String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || session != telegram.generation) return;
                discovering = false; discoveryMore = hasMore;
                if (isSourcesPage()) { refreshChannelChoices(); refreshChannelProgress(); if (failure != null) status.setText(failure + " · Tap Refresh to retry."); }
                if (failure == null && hasMore && alive && page.equals("addSources")) timer.postDelayed(() -> { if (alive && page.equals("addSources")) discoverMore(); }, 400);
            });
        });
    }
    private void home() {
        body.addView(Ui.text(this, "MAKE TIME FOR A GOOD FILM", 11, Ui.ACCENT));
        body.addView(Ui.heading(this, "Your library"));
        body.addView(Ui.text(this, "Everything you love, in one place.", 16, Ui.MUTED));
        LinearLayout hero = Ui.column(this); hero.setBackground(Ui.card(this));
        hero.addView(Ui.text(this, "What are we watching?", 20, Ui.TEXT));
        hero.addView(Ui.text(this, "Find a video across the channels you've chosen.", 14, Ui.MUTED));
        hero.addView(Ui.primary(this, "Search videos", () -> { page = "search"; chat = 0; render(); })); body.addView(hero);
        body.addView(Ui.quiet(this, "Continue watching  →", () -> navigate("history")));
        body.addView(Ui.text(this, "YOUR CHANNELS", 12, Ui.MUTED));
        List<Library.Chat> chats = telegram.requireLibrary().chats(true);
        if (chats.isEmpty()) { emptyState(body, "Build your collection", "Add your favorite channels and groups to start watching."); body.addView(Ui.primary(this, "Add channels", () -> navigate("addSources"))); }
        for (Library.Chat source : chats) body.addView(channelRow(source, false));
    }
    private void emptyState(LinearLayout target, String title, String subtitle) {
        LinearLayout card = Ui.column(this); card.setBackground(Ui.card(this));
        card.addView(Ui.text(this, title, 20, Ui.TEXT)); card.addView(Ui.text(this, subtitle, 14, Ui.MUTED)); target.addView(card);
    }
    private View channelRow(Library.Chat source, boolean selectable) {
        LinearLayout row = Ui.row(this); row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8)); row.setBackground(Ui.card(this)); row.setMinimumHeight(Ui.dp(this, 76));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4)); row.setLayoutParams(spacing);
        TextView avatar = Ui.text(this, source.title().isEmpty() ? "#" : source.title().substring(0, source.title().offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT), 20, Ui.ACCENT); avatar.setGravity(Gravity.CENTER); avatar.setBackground(Ui.card(this)); row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout label = Ui.column(this); label.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        TextView title = Ui.text(this, source.title(), 16, Ui.TEXT); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); label.addView(title);
        label.addView(Ui.text(this, selectable ? source.selected() ? "In your library" : "Available to add" : "Browse videos", 12, Ui.MUTED)); Ui.weighted(row, label);
        if (selectable) {
            MaterialButton add = Ui.button(this, source.selected() ? "Added ✓" : "Add", () -> { telegram.requireLibrary().select(source.id(), !source.selected()); refreshChannelChoices(); });
            add.setContentDescription((source.selected() ? "Remove " : "Add ") + source.title()); row.addView(add);
        } else {
            MaterialButton moreButton = Ui.quiet(this, "•••", () -> new AlertDialog.Builder(this).setTitle(source.title()).setItems(new String[]{"Open channel", "Remove from My channels"}, (d, which) -> {
                if (which == 0) openChannel(source); else { telegram.requireLibrary().select(source.id(), false); if (page.equals("sources")) refreshChannelChoices(); else render(); }
            }).show()); moreButton.setContentDescription("Options for " + source.title()); row.addView(moreButton);
            row.setOnClickListener(v -> openChannel(source)); row.setContentDescription("Open " + source.title());
        }
        return row;
    }
    private void openChannel(Library.Chat source) {
        if (busy) return; hideKeyboard();
        chat = source.id(); chatTitle = source.title(); page = "collection"; more = true; displayLimit = 150;
        render(); final long id = chat;
        run("Loading videos…", () -> more = telegram.page(id, true), () -> { if (page.equals("collection") && chat == id) render(); });
    }
    private void sources() {
        boolean adding = page.equals("addSources");
        if (adding) body.addView(Ui.quiet(this, "‹  My channels", () -> navigate("sources")));
        body.addView(Ui.heading(this, adding ? "Add channels" : "My channels"));
        body.addView(Ui.text(this, adding ? "Choose what belongs in your library." : "Only the channels and groups you've added.", 15, Ui.MUTED));
        if (!adding) body.addView(Ui.primary(this, "+  Add channels", () -> navigate("addSources")));
        channelFilter = Ui.input(this, adding ? "Search channels and groups" : "Search my channels", InputType.TYPE_CLASS_TEXT); channelFilter.setId(R.id.channel_search); channelFilter.setImeOptions(EditorInfo.IME_ACTION_SEARCH); channelFilter.setText(channelQuery); body.addView(channelFilter);
        channelFilter.addTextChangedListener(watcher(() -> { channelQuery = channelFilter.getText().toString(); refreshChannelChoices(); }));
        if (adding) {
            body.addView(Ui.button(this, "Search Telegram", this::searchChannels));
            channelFilter.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { searchChannels(); return true; } return false; });
        }
        channelProgress = new LinearProgressIndicator(this); channelProgress.setId(R.id.channel_progress); channelProgress.setIndeterminate(true); body.addView(channelProgress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 4)));
        channelSummary = Ui.text(this, "", 12, Ui.MUTED); body.addView(channelSummary);
        channelChoices = Ui.column(this); channelChoices.setPadding(0, 0, 0, 0); channelChoices.setId(R.id.channel_choices); body.addView(channelChoices);
        channelRetry = Ui.quiet(this, "Refresh channels", () -> { discoveryMore = true; discoverMore(); }); body.addView(channelRetry);
        refreshChannelChoices(); refreshChannelProgress();
    }
    static TextWatcher watcher(Runnable change) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { change.run(); } public void afterTextChanged(Editable text) {} }; }
    // Update results only. Never replace the field while discovery or search completes.
    void refreshChannelChoices() {
        if (channelChoices == null || !isSourcesPage()) return;
        channelChoices.removeAllViews(); boolean adding = page.equals("addSources");
        String filter = channelQuery.trim().toLowerCase(Locale.ROOT); int count = 0;
        for (Library.Chat source : telegram.requireLibrary().chats(!adding)) {
            if (!source.title().toLowerCase(Locale.ROOT).contains(filter)) continue;
            channelChoices.addView(channelRow(source, adding)); count++;
        }
        channelSummary.setText(count + (adding ? " available" : " in your library"));
        if (count == 0) emptyState(channelChoices, filter.isEmpty() ? adding ? "Find your favorites" : "Your library starts here" : "No matching channels", adding ? "Search Telegram or refresh to find your channels and groups." : "Tap Add channels to choose what appears here.");
    }
    private void refreshChannelProgress() {
        if (!isSourcesPage() || channelProgress == null) return;
        channelProgress.setVisibility(page.equals("addSources") && (discovering || channelSearching) ? View.VISIBLE : View.GONE);
        channelRetry.setVisibility(page.equals("addSources") ? View.VISIBLE : View.GONE); channelRetry.setEnabled(!discovering);
    }
    private void searchChannels() {
        String text = channelQuery.trim(); if (text.isEmpty() || channelSearching) return;
        channelSearching = true; refreshChannelProgress(); final long session = telegram.generation, request = ++sourceRequest;
        telegram.io.execute(() -> {
            String failure = null; try { telegram.searchChats(text); } catch (Exception e) { failure = e.getMessage(); }
            final String error = failure;
            runOnUiThread(() -> {
                if (isDestroyed() || session != telegram.generation || request != sourceRequest) return;
                channelSearching = false;
                if (isSourcesPage()) { refreshChannelChoices(); refreshChannelProgress(); status.setText(error == null ? "" : error + " · Try again."); }
            });
        });
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
        EditText text = Ui.input(this, "Title or filename", InputType.TYPE_CLASS_TEXT); text.setId(R.id.video_search); text.setImeOptions(EditorInfo.IME_ACTION_SEARCH); text.setText(videoDraft); text.addTextChangedListener(watcher(() -> videoDraft = text.getText().toString())); body.addView(text);
        Runnable submit = () -> {
            if (busy) return;
            query = videoDraft.trim(); if (query.isEmpty()) return; hideKeyboard();
            search = new SearchSession(telegram, telegram.requireLibrary().chats(true), query); displayLimit = 150;
            SearchSession current = search; run("Searching selected sources…", () -> more = current.next(), () -> { if (page.equals("search") && search == current) refreshVideoResults(); });
        };
        body.addView(Ui.primary(this, "Search", submit));
        text.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { submit.run(); return true; } return false; });
        videoResults = Ui.column(this); videoResults.setPadding(0, 0, 0, 0); body.addView(videoResults); refreshVideoResults();
    }
    private void refreshVideoResults() {
        videoResults.removeAllViews(); downloadLabels.clear(); artworkViews.clear();
        if (!query.isEmpty()) {
            Map<String, Video> combined = new LinkedHashMap<>();
            for (Video video : telegram.requireLibrary().videos(0, query, false, displayLimit)) combined.put(video.key(), video);
            if (search != null) for (Video video : search.results.values()) combined.put(video.key(), video);
            LinearLayout savedBody = body; body = videoResults; showVideos(new ArrayList<>(combined.values())); body = savedBody;
            if (search != null && more) videoResults.addView(Ui.button(this, "Search more / Retry", () -> { SearchSession current = search; run("Searching more…", () -> { more = current.next(); displayLimit += 150; }, () -> { if (page.equals("search") && search == current) refreshVideoResults(); }); }));
        }
    }
    private void history() { body.addView(Ui.heading(this, "Continue watching")); showVideos(telegram.requireLibrary().videos(0, "", true, displayLimit)); }
    private void showVideos(List<Video> videos) {
        if (videos.isEmpty()) body.addView(Ui.text(this, "No videos here yet. Load another page or choose more sources.", 16, Ui.MUTED));
        for (Video video : videos) {
            LinearLayout card = Ui.column(this); card.setBackground(Ui.card(this));
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
            Ui.weighted(actions, Ui.primary(this, position >= 5000 ? "Resume" : "Play", () -> open(video, true)));
            Ui.weighted(actions, Ui.quiet(this, "More", () -> videoMenu(video))); card.addView(actions);
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
        body.addView(Ui.text(this, "Cinespice for Android · 1.1\nTelegram + VLC\nDownloads and playback pause or stop when Android closes the app. Keep it open for downloads.", 16, Ui.MUTED));
        body.addView(Ui.button(this, "Clear downloaded videos", () -> confirm("Clear downloaded media?", () -> run("Clearing downloads…", telegram::clearDownloads, () -> status.setText("Downloaded media cleared.")))));
        body.addView(Ui.button(this, "Clear artwork cache", () -> run("Clearing artwork…", () -> telegram.request(Json.obj("@type", "optimizeStorage", "size", 0, "ttl", 0, "count", 0, "immunity_delay", 0, "file_types", new org.json.JSONArray().put(Json.obj("@type", "fileTypeThumbnail")))), () -> { if (artwork != null) artwork.clear(); status.setText("Artwork cache cleared."); })));
        body.addView(Ui.button(this, "Clear watch history", () -> confirm("Clear watch history?", () -> { telegram.requireLibrary().clearHistory(); status.setText("History cleared."); })));
        body.addView(Ui.button(this, "Sign out of Telegram", () -> confirm("Sign out of Telegram?", () -> run("Signing out…", () -> telegram.request(Json.obj("@type", "logOut")), this::render))));
        body.addView(Ui.button(this, "Open source notices", () -> {
            StringBuilder notices = new StringBuilder("TDLib 1.8.65 · up9cloud Android JNI (MIT)\nlibVLC Android 3.6.5 · LGPL 2.1 or later\nMaterial Components 1.12.0 and AndroidX AppCompat 1.7.0 · Apache 2.0\n\nSources: github.com/tdlib/td, github.com/up9cloud/android-libtdjson, code.videolan.org/videolan/vlc-android/-/tree/3.6.5\nNative libraries are unmodified. Android app source and build scripts are in the Cinespice repository. LGPL permits debugging modifications to its components.\n\n");
            for (String name : new String[]{"TDLib.txt", "TDLib-JNI.txt", "libVLC.txt", "Apache-2.0.txt"}) {
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
        if (busy) return; busy = true; status.setText(message); final LinearLayout startedRoot = root;
        telegram.io.execute(() -> {
            String failure = null;
            try { work.execute(); } catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            String result = failure;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed() || isFinishing()) return;
                if (result != null) status.setText(result + " · Try again.");
                else { telegram.error = ""; status.setText(""); if (root == startedRoot) done.run(); }
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
    private void handleBack() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) { hideKeyboard(); return; }
        if (page.equals("addSources")) navigate("sources");
        else if (!page.equals("home") && telegram.stage.equals("ready")) navigate("home"); else finish();
    }
}
