// Copyright 2024 The Kiwi Browser Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.settings;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragmentCompat;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.components.browser_ui.settings.SettingsUtils;

/**
 * Kiwi Phase 4 — Appearance Settings fragment.
 *
 * <p>Hosts the "Address bar position" setting (Top / Bottom).
 * Changing the position writes the {@code address_bar_position} SharedPreference
 * and also updates {@code enable_bottom_toolbar} so that ChromeActivity picks the
 * correct root layout on the next startup.
 *
 * <p>Registered in main_preferences.xml under the Appearance category as
 * {@code org.chromium.chrome.browser.settings.KiwiAppearanceSettings}.
 */
public class KiwiAppearanceSettings extends PreferenceFragmentCompat
        implements OnPreferenceChangeListener {

    /** SharedPreference key for address bar position. Values: "bottom" | "top". */
    public static final String PREF_ADDRESS_BAR_POSITION = "address_bar_position";

    /** Default position: bottom, matching Phase 3's enable_bottom_toolbar=true default. */
    public static final String DEFAULT_POSITION = "bottom";

    private ListPreference mAddressBarPositionPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getActivity().setTitle(R.string.kiwi_address_bar_position_title);
        SettingsUtils.addPreferencesFromResource(this, R.xml.kiwi_appearance_preferences);

        mAddressBarPositionPref = findPreference(PREF_ADDRESS_BAR_POSITION);

        if (mAddressBarPositionPref != null) {
            // Read the persisted value; fall back to "bottom".
            String currentValue = ContextUtils.getAppSharedPreferences()
                    .getString(PREF_ADDRESS_BAR_POSITION, DEFAULT_POSITION);
            mAddressBarPositionPref.setValue(currentValue);
            mAddressBarPositionPref.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (!PREF_ADDRESS_BAR_POSITION.equals(preference.getKey())) return false;

        String newPosition = (String) newValue;
        boolean wantsBottomBar = "bottom".equals(newPosition);

        // Write both prefs atomically so ChromeActivity and ToolbarManager see a
        // consistent state on the next startup.
        SharedPreferences.Editor editor =
                ContextUtils.getAppSharedPreferences().edit();
        editor.putString(PREF_ADDRESS_BAR_POSITION, newPosition);
        editor.putBoolean("enable_bottom_toolbar", wantsBottomBar);
        editor.apply();

        // Notify the user that a restart is needed for the change to take effect.
        // We avoid forcibly recreating the activity here because:
        //  1. We're inside the Settings activity, not ChromeTabbedActivity.
        //  2. Recreating the wrong activity would discard the user's open tabs.
        // Instead, the change takes effect the next time the browser is opened.
        showRestartBanner();

        return true;
    }

    /**
     * Shows a brief informational Snackbar-like message telling the user the change
     * will take effect after restarting the browser.
     *
     * <p>Uses Android's built-in Toast since we're in the Settings stack and do not
     * have access to Chrome's SnackbarManager here.
     */
    private void showRestartBanner() {
        if (getContext() == null) return;
        android.widget.Toast.makeText(
                getContext(),
                "Restart Kiwi Browser for the change to take effect",
                android.widget.Toast.LENGTH_LONG
        ).show();
    }
}
