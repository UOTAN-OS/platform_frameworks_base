/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.clipboardoverlay;

import static com.android.systemui.Flags.clipboardOverlayMultiuser;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.systemui.settings.UserTracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.inject.Inject;

/**
 * Creates app-specific clipboard actions for links that System TextClassifier does not reliably
 * route to the expected app.
 */
class ClipboardAppSuggestionUtils {
    private static final int APP_ICON_SIZE_PX = 96;
    private static final String SECURE_KEY_RULES = "uwuaosp_clipboard_app_rules";
    private static final String SECURE_KEY_ENABLED = "uwuaosp_clipboard_app_rules_enabled";
    private static final String JSON_NAME = "name";
    private static final String JSON_PACKAGE = "package_name";
    private static final String JSON_PATTERN = "pattern";
    private static final String JSON_ENABLED = "enabled";

    private final Context mContext;
    private final UserTracker mUserTracker;

    @Inject
    ClipboardAppSuggestionUtils(Context context, UserTracker userTracker) {
        mContext = context;
        mUserTracker = userTracker;
    }

    Optional<RemoteAction> getAction(CharSequence text, String sourcePackage) {
        if (TextUtils.isEmpty(text)) {
            return Optional.empty();
        }

        String value = text.toString();
        Context userContext = mUserTracker.getUserContext();
        if (!isEnabled(userContext)) {
            return Optional.empty();
        }
        PackageManager packageManager = userContext.getPackageManager();
        for (AppRule rule : loadRules(userContext)) {
            Matcher matcher = rule.pattern.matcher(value);
            if (!matcher.find() || TextUtils.equals(sourcePackage, rule.packageName)) {
                continue;
            }

            try {
                ApplicationInfo info = packageManager.getApplicationInfo(rule.packageName, 0);
                if (!info.enabled) {
                    continue;
                }
                Intent launchIntent = createLaunchIntent(packageManager, rule.packageName,
                        matcher.group());
                if (launchIntent == null) {
                    continue;
                }
                CharSequence appLabel = info.loadLabel(packageManager);
                String title = mContext.getString(
                        com.android.internal.R.string.whichViewApplicationNamed, appLabel);
                PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                        mContext,
                        rule.packageName.hashCode(),
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        null,
                        clipboardOverlayMultiuser() ? mUserTracker.getUserHandle()
                                : UserHandle.CURRENT);
                return Optional.of(new RemoteAction(drawableToIcon(info.loadIcon(packageManager)),
                        title, title, pendingIntent));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        return Optional.empty();
    }

    private boolean isEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), SECURE_KEY_ENABLED, 0) == 1;
    }

    private List<AppRule> loadRules(Context context) {
        String json = Settings.Secure.getString(context.getContentResolver(), SECURE_KEY_RULES);
        if (TextUtils.isEmpty(json)) {
            return Collections.emptyList();
        }

        ArrayList<AppRule> rules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null || !object.optBoolean(JSON_ENABLED, true)) {
                    continue;
                }
                String packageName = object.optString(JSON_PACKAGE);
                String pattern = object.optString(JSON_PATTERN);
                String name = object.optString(JSON_NAME, packageName);
                if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(pattern)) {
                    continue;
                }
                try {
                    rules.add(new AppRule(name, packageName, pattern));
                } catch (PatternSyntaxException ignored) {
                }
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }

        return rules;
    }

    private Intent createLaunchIntent(PackageManager packageManager, String packageName,
            String matchedText) {
        Uri uri = getUri(matchedText);
        if (uri != null) {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(packageName)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (clipboardOverlayMultiuser()) {
                viewIntent.prepareToLeaveUser(mUserTracker.getUserId());
            }
            if (packageManager.resolveActivity(viewIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)) != null) {
                return viewIntent;
            }
        }

        Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (clipboardOverlayMultiuser()) {
                launchIntent.prepareToLeaveUser(mUserTracker.getUserId());
            }
        }
        return launchIntent;
    }

    private static Uri getUri(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("dtk://")) {
            return Uri.parse(value);
        }
        if (value.startsWith("m.tb.cn/")) {
            return Uri.parse("https://" + value);
        }
        return null;
    }

    private static Icon drawableToIcon(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                return Icon.createWithBitmap(bitmap);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(APP_ICON_SIZE_PX, APP_ICON_SIZE_PX,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return Icon.createWithBitmap(bitmap);
    }

    private static final class AppRule {
        final String name;
        final String packageName;
        final Pattern pattern;

        AppRule(String name, String packageName, String pattern) {
            this.name = name;
            this.packageName = packageName;
            this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        }
    }
}
