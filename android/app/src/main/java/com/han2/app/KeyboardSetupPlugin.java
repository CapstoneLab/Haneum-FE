package com.han2.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONException;

@CapacitorPlugin(name = "KeyboardSetup")
public class KeyboardSetupPlugin extends Plugin {
    private static final String PREFS = "han2_keyboard_preferences";
    private String serviceId() {
        return new ComponentName(getContext(), Han2KeyboardService.class).flattenToShortString();
    }

    private boolean isEnabled() {
        InputMethodManager manager = (InputMethodManager)
            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (serviceId().equals(info.getId())) return true;
        }
        return false;
    }

    private boolean isSelected() {
        InputMethodManager manager = (InputMethodManager)
            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        try {
            String currentId = Settings.Secure.getString(
                getContext().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD
            );
            if (serviceId().equals(currentId)) return true;
        } catch (SecurityException ignored) {
            // Fall through to the public API available on recent Android versions.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            InputMethodInfo current = manager.getCurrentInputMethodInfo();
            return current != null
                && getContext().getPackageName().equals(current.getPackageName())
                && Han2KeyboardService.class.getName().equals(current.getServiceName());
        }
        return false;
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("enabled", isEnabled());
        result.put("selected", isSelected());
        call.resolve(result);
    }

    @PluginMethod
    public void openKeyboardSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void showKeyboardPicker(PluginCall call) {
        if (!isEnabled()) {
            call.reject("먼저 시스템 설정에서 HAN2 안전 키보드를 활성화해 주세요.");
            return;
        }
        getActivity().runOnUiThread(() -> {
            InputMethodManager manager = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.showInputMethodPicker();
            call.resolve();
        });
    }

    @PluginMethod
    public void setProtectionEnabled(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", true);
        getContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
            .edit().putBoolean("protection_enabled", enabled).commit();
        JSObject result = new JSObject();
        result.put("enabled", enabled);
        call.resolve(result);
    }

    @PluginMethod
    public void getProtectionEnabled(PluginCall call) {
        boolean enabled = getContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
            .getBoolean("protection_enabled", true);
        JSObject result = new JSObject();
        result.put("enabled", enabled);
        call.resolve(result);
    }

    @PluginMethod
    public void setKeyboardPreferences(PluginCall call) {
        getContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit()
            .putBoolean("protection_enabled", call.getBoolean("protection", true))
            .putBoolean("haptics_enabled", call.getBoolean("haptics", true))
            .putBoolean("sound_enabled", call.getBoolean("sound", false))
            .putBoolean("number_row_enabled", call.getBoolean("numberRow", false))
            .putString("keyboard_theme", call.getString("theme", "system"))
            .commit();
        call.resolve();
    }

    @PluginMethod
    public void getDetectionReports(PluginCall call) {
        String raw = getContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
            .getString("detection_reports", "[]");
        JSObject result = new JSObject();
        try { result.put("reports", new JSArray(raw)); }
        catch (JSONException error) { result.put("reports", new JSArray()); }
        call.resolve(result);
    }

    @PluginMethod
    public void clearDetectionReports(PluginCall call) {
        getContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
            .edit().putString("detection_reports", "[]").commit();
        call.resolve();
    }
}
