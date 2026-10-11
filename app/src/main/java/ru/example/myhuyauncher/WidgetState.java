package ru.example.myhuyauncher;

import android.content.Context;
import android.content.SharedPreferences;

final class WidgetState {
    private static final String PREFS = "widget";
    private static final String KEY_HIDDEN = "hidden";

    private WidgetState() {
    }

    static boolean isHidden(Context context) {
        return prefs(context).getBoolean(KEY_HIDDEN, false);
    }

    static void setHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_HIDDEN, hidden).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
