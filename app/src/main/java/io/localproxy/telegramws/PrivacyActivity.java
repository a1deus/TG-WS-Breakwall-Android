package io.localproxy.telegramws;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class PrivacyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.breakwall_background));
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = policyText(getString(R.string.privacy_title), 25, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title);
        addSection(content, R.string.privacy_local_title, R.string.privacy_local);
        addSection(content, R.string.privacy_routes_title, R.string.privacy_routes);
        addSection(content, R.string.privacy_updates_title, R.string.privacy_updates);
        addSection(content, R.string.privacy_controls_title, R.string.privacy_controls);
        TextView policies = addSection(content, R.string.privacy_external_title, R.string.privacy_external);
        policies.setMovementMethod(LinkMovementMethod.getInstance());
        policies.setLinkTextColor(getColor(R.color.breakwall_foreground));
    }

    private TextView addSection(LinearLayout content, int heading, int body) {
        TextView title = policyText(getString(heading), 17, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(18);
        content.addView(title, headingParams);

        TextView detail = policyText(getText(body), 15, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(5);
        content.addView(detail, bodyParams);
        return detail;
    }

    private TextView policyText(CharSequence label, int size, boolean heading) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(getColor(heading ? R.color.breakwall_foreground : R.color.breakwall_secondary));
        if (heading) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
