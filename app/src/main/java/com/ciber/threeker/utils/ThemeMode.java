package com.ciber.threeker.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light/dark theme choice.
 *
 * The selection is stored in the app's private {@code settings} preferences and applied through
 * {@link AppCompatDelegate#setDefaultNightMode(int)}, so every activity (and the system dialogs)
 * follow it. Until the user picks a side explicitly, the system setting is followed.
 */
public final class ThemeMode {

    protected static final String PREFS = "settings";
    protected static final String KEY = "theme_mode";
    protected static final String LIGHT = "light";
    protected static final String DARK = "dark";

    private ThemeMode() {
    }

    /**
     * Applies the stored choice. Call this before {@code super.onCreate(...)} in every activity.
     */
    public static void apply(Context context) {
        String mode = prefs(context).getString(KEY, null);
        if (LIGHT.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (DARK.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /**
     * @return true when the current configuration is dark (explicitly chosen or from the system).
     */
    public static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Flips light/dark, stores the choice and lets AppCompat recreate the activities.
     */
    public static void toggle(Context context) {
        boolean dark = !isDark(context);
        prefs(context).edit().putString(KEY, dark ? DARK : LIGHT).apply();
        AppCompatDelegate.setDefaultNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    protected static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
