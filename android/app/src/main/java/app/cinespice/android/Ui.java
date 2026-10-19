package app.cinespice.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(17,17,20), PANEL = Color.rgb(32,32,38), ACCENT = Color.rgb(255,122,48), TEXT = Color.rgb(247,244,242), MUTED = Color.rgb(172,170,179);
    static int dp(Activity activity, float value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }
    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12)); return layout;
    }
    static LinearLayout row(Activity activity) { LinearLayout row = new LinearLayout(activity); row.setGravity(android.view.Gravity.CENTER_VERTICAL); return row; }
    static TextView text(Activity activity, String value, int size, int color) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setPadding(0, dp(activity, 8), 0, dp(activity, 8)); return text;
    }
    static TextView heading(Activity activity, String value) { TextView text = text(activity, value, 28, TEXT); text.setTypeface(null, Typeface.BOLD); return text; }
    static Button button(Activity activity, String label, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(activity, 48)); button.setTextColor(TEXT); button.setOnClickListener(v -> action.run()); return button;
    }
    static EditText input(Activity activity, String hint, int type) {
        EditText input = new EditText(activity); input.setHint(hint); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setInputType(type); input.setSingleLine(true); input.setMinHeight(dp(activity, 52)); return input;
    }
    static void weighted(LinearLayout row, View child) { row.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    static GradientDrawable card() { GradientDrawable background = new GradientDrawable(); background.setColor(PANEL); background.setCornerRadius(18); return background; }
    static String time(long milliseconds) { long seconds = Math.max(0, milliseconds / 1000); return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
}
