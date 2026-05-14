// Copyright 2024 The Kiwi Browser Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.toolbar.bottom;

import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.browser.ActivityTabProvider;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorObserver;
import org.chromium.chrome.browser.tabmodel.EmptyTabModelSelectorObserver;
import org.chromium.chrome.browser.toolbar.TabCountProvider;
import org.chromium.chrome.browser.toolbar.TabCountProvider.TabCountObserver;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.widget.ChromeImageButton;

import org.chromium.chrome.R;

/**
 * Kiwi Phase 3 — Bottom navigation bar controller.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Wires back/forward/tabs/menu click listeners to the appropriate Chrome actions.</li>
 *   <li>Enables/disables back and forward buttons based on the current tab's navigation state.</li>
 *   <li>Updates the tab count badge TextView from {@link TabCountProvider}.</li>
 *   <li>Drives the slide-up/slide-down animations on the bar's host view.</li>
 * </ul>
 *
 * <p>Lifecycle: create after the bottom nav view is inflated into
 * {@code bottom_container_slot}. Call {@link #destroy()} when the activity is destroyed.
 *
 * <p>Animation contract: the controller does NOT manage its own visibility flag — it delegates
 * that to the native {@link ScrollingBottomViewResourceFrameLayout} which is driven by the
 * Chromium compositor. The slide-up/down animations are applied on top of that system when
 * the bar is first shown after a scroll-back-up gesture. This mirrors how the top toolbar's
 * show/hide works without duplicating the compositor scroll logic.
 */
public class KiwiBottomNavController {

    // ── Views ──────────────────────────────────────────────────────────────
    private final View mRootView;         // The kiwi_bottom_nav LinearLayout
    private final ChromeImageButton mBackButton;
    private final ChromeImageButton mForwardButton;
    private final ChromeImageButton mTabSwitcherButton;
    private final ChromeImageButton mMenuButton;
    private final TextView mTabCountView;
    private final ImageView mMenuBadge;

    // ── Dependencies ───────────────────────────────────────────────────────
    private final Context mContext;
    private final ActivityTabProvider mActivityTabProvider;
    private final TabModelSelector mTabModelSelector;
    private final TabCountProvider mTabCountProvider;

    // ── Click handlers (set by the caller) ────────────────────────────────
    private final View.OnClickListener mTabSwitcherClickHandler;
    private final View.OnClickListener mMenuClickHandler;

    // ── Internal state ─────────────────────────────────────────────────────
    private boolean mIsVisible;
    @Nullable private Tab mCurrentTab;

    // ── Observers ──────────────────────────────────────────────────────────
    private final TabObserver mTabObserver;
    private final TabCountObserver mTabCountObserver;
    private final ActivityTabProvider.ActivityTabObserver mActivityTabObserver;

    /**
     * Creates and wires the Kiwi bottom nav controller.
     *
     * @param navView             The inflated {@code kiwi_bottom_nav} view.
     * @param context             Activity context.
     * @param activityTabProvider Provides the currently active Tab.
     * @param tabModelSelector    For tab count observation.
     * @param tabCountProvider    Drives the tab count badge.
     * @param tabSwitcherClickHandler Called when the tabs button is tapped.
     * @param menuClickHandler        Called when the 3-dot menu button is tapped.
     */
    public KiwiBottomNavController(
            @NonNull View navView,
            @NonNull Context context,
            @NonNull ActivityTabProvider activityTabProvider,
            @NonNull TabModelSelector tabModelSelector,
            @NonNull TabCountProvider tabCountProvider,
            @NonNull View.OnClickListener tabSwitcherClickHandler,
            @NonNull View.OnClickListener menuClickHandler) {

        mRootView = navView;
        mContext = context;
        mActivityTabProvider = activityTabProvider;
        mTabModelSelector = tabModelSelector;
        mTabCountProvider = tabCountProvider;
        mTabSwitcherClickHandler = tabSwitcherClickHandler;
        mMenuClickHandler = menuClickHandler;

        // Resolve child views
        mBackButton = navView.findViewById(R.id.kiwi_bottom_back);
        mForwardButton = navView.findViewById(R.id.kiwi_bottom_forward);
        mTabSwitcherButton = navView.findViewById(R.id.kiwi_bottom_tab_switcher);
        mMenuButton = navView.findViewById(R.id.kiwi_bottom_menu_button);
        mTabCountView = navView.findViewById(R.id.kiwi_bottom_tab_count);
        mMenuBadge = navView.findViewById(R.id.kiwi_bottom_menu_badge);

        // ── Click listeners ────────────────────────────────────────────────
        mBackButton.setOnClickListener(v -> {
            if (mCurrentTab != null && mCurrentTab.getWebContents() != null) {
                NavigationController nav = mCurrentTab.getWebContents().getNavigationController();
                if (nav.canGoBack()) nav.goBack();
            }
        });

        mForwardButton.setOnClickListener(v -> {
            if (mCurrentTab != null && mCurrentTab.getWebContents() != null) {
                NavigationController nav = mCurrentTab.getWebContents().getNavigationController();
                if (nav.canGoForward()) nav.goForward();
            }
        });

        mTabSwitcherButton.setOnClickListener(mTabSwitcherClickHandler);
        mMenuButton.setOnClickListener(mMenuClickHandler);

        // ── Tab observer — updates back/forward enabled state ──────────────
        mTabObserver = new EmptyTabObserver() {
            @Override
            public void onDidFinishNavigation(Tab tab,
                    org.chromium.content_public.browser.NavigationHandle navigationHandle) {
                updateNavButtonState();
            }

            @Override
            public void onPageLoadStarted(Tab tab, org.chromium.url.GURL url) {
                updateNavButtonState();
            }

            @Override
            public void onDestroyed(Tab tab) {
                tab.removeObserver(this);
                if (mCurrentTab == tab) mCurrentTab = null;
                updateNavButtonState();
            }
        };

        // ── Activity tab observer — swap the current tab ───────────────────
        mActivityTabObserver = (tab, hint) -> {
            if (mCurrentTab != null) mCurrentTab.removeObserver(mTabObserver);
            mCurrentTab = tab;
            if (mCurrentTab != null) mCurrentTab.addObserver(mTabObserver);
            updateNavButtonState();
        };
        mActivityTabProvider.addObserver(mActivityTabObserver);

        // ── Tab count badge ────────────────────────────────────────────────
        mTabCountObserver = count -> updateTabCountBadge(count);
        mTabCountProvider.addObserver(mTabCountObserver);

        // Initial state
        updateNavButtonState();
        updateTabCountBadge(mTabCountProvider.getTabCount());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Plays the slide-up entry animation on the bottom nav host view.
     * Called by the compositor scroll observer when the user swipes back up.
     */
    public void animateShow() {
        if (mIsVisible) return;
        mIsVisible = true;
        Animation anim = AnimationUtils.loadAnimation(mContext,
                R.anim.kiwi_bottom_nav_slide_up);
        mRootView.startAnimation(anim);
        mRootView.setVisibility(View.VISIBLE);
    }

    /**
     * Plays the slide-down exit animation on the bottom nav host view.
     * Called by the compositor scroll observer when the user scrolls down.
     */
    public void animateHide() {
        if (!mIsVisible) return;
        mIsVisible = false;
        Animation anim = AnimationUtils.loadAnimation(mContext,
                R.anim.kiwi_bottom_nav_slide_down);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override
            public void onAnimationEnd(Animation a) {
                // Set GONE only after animation completes so compositor can
                // finish rendering before removing from layout.
                mRootView.setVisibility(View.GONE);
            }
        });
        mRootView.startAnimation(anim);
    }

    /**
     * Shows the menu update badge on the bottom menu button.
     * Mirrors the existing top toolbar badge behaviour.
     */
    public void showMenuUpdateBadge() {
        if (mMenuBadge != null) mMenuBadge.setVisibility(View.VISIBLE);
    }

    /** Hides the menu update badge. */
    public void hideMenuUpdateBadge() {
        if (mMenuBadge != null) mMenuBadge.setVisibility(View.INVISIBLE);
    }

    /** Clean up observers and references. */
    public void destroy() {
        mActivityTabProvider.removeObserver(mActivityTabObserver);
        mTabCountProvider.removeObserver(mTabCountObserver);
        if (mCurrentTab != null) {
            mCurrentTab.removeObserver(mTabObserver);
            mCurrentTab = null;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Refreshes the enabled/disabled state of the back and forward buttons based on
     * the current tab's WebContents navigation history.
     */
    private void updateNavButtonState() {
        boolean canGoBack = false;
        boolean canGoForward = false;

        if (mCurrentTab != null) {
            WebContents webContents = mCurrentTab.getWebContents();
            if (webContents != null && !webContents.isDestroyed()) {
                NavigationController nav = webContents.getNavigationController();
                canGoBack = nav.canGoBack();
                canGoForward = nav.canGoForward();
            }
        }

        mBackButton.setEnabled(canGoBack);
        mForwardButton.setEnabled(canGoForward);

        // Dim disabled buttons to 40% opacity — same convention as ToolbarPhone
        mBackButton.setAlpha(canGoBack ? 1.0f : 0.4f);
        mForwardButton.setAlpha(canGoForward ? 1.0f : 0.4f);
    }

    /**
     * Updates the tab count badge TextView.
     * Hides the badge when there is exactly 1 tab (no need to show "1").
     * Shows the badge for 2+ tabs. Caps display at 99+.
     */
    private void updateTabCountBadge(int count) {
        if (mTabCountView == null) return;
        if (count <= 1) {
            mTabCountView.setVisibility(View.GONE);
        } else {
            mTabCountView.setVisibility(View.VISIBLE);
            mTabCountView.setText(count > 99 ? "99+" : String.valueOf(count));
        }
    }
}
