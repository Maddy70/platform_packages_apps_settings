/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.network.telephony.gsm;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import static com.android.settings.Utils.SETTINGS_PACKAGE_NAME;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.settings.R;
import com.android.settings.network.AllowedNetworkTypesListener;
import com.android.settings.network.CarrierConfigCache;
import com.android.settings.network.telephony.MobileNetworkUtils;
import com.android.settings.network.telephony.TelephonyTogglePreferenceController;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preference controller for "Auto Select Network"
 */
public class AutoSelectPreferenceController extends TelephonyTogglePreferenceController
        implements LifecycleObserver{
    private static final long MINIMUM_DIALOG_TIME_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final Handler mUiHandler;
    private PreferenceScreen mPreferenceScreen;
    private AllowedNetworkTypesListener mAllowedNetworkTypesListener;
    private TelephonyManager mTelephonyManager;
    private boolean mOnlyAutoSelectInHome;
    private List<OnNetworkSelectModeListener> mListeners;
    @VisibleForTesting
    ProgressDialog mProgressDialog;
    @VisibleForTesting
    SwitchPreference mSwitchPreference;
    private AtomicBoolean mUpdatingConfig;
    private int mCacheOfModeStatus;
    private AtomicLong mRecursiveUpdate;

    public AutoSelectPreferenceController(Context context, String key) {
        super(context, key);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mRecursiveUpdate = new AtomicLong();
        mUpdatingConfig = new AtomicBoolean();
        mCacheOfModeStatus = TelephonyManager.NETWORK_SELECTION_MODE_UNKNOWN;
        mListeners = new ArrayList<>();
        mUiHandler = new Handler(Looper.getMainLooper());
        mAllowedNetworkTypesListener = new AllowedNetworkTypesListener(
                new HandlerExecutor(mUiHandler));
        mAllowedNetworkTypesListener.setAllowedNetworkTypesListener(
                () -> updatePreference());
    }

    private void updatePreference() {
        if (mPreferenceScreen != null) {
            displayPreference(mPreferenceScreen);
        }
        if (mSwitchPreference != null) {
            mRecursiveUpdate.getAndIncrement();
            updateState(mSwitchPreference);
            mRecursiveUpdate.decrementAndGet();
        }
    }

    @OnLifecycleEvent(ON_START)
    public void onStart() {
        mAllowedNetworkTypesListener.register(mContext, mSubId);
    }

    @OnLifecycleEvent(ON_STOP)
    public void onStop() {
        mAllowedNetworkTypesListener.unregister(mContext, mSubId);
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        return MobileNetworkUtils.shouldDisplayNetworkSelectOptions(mContext, subId)
                ? AVAILABLE
                : CONDITIONALLY_UNAVAILABLE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreferenceScreen = screen;
        mSwitchPreference = screen.findPreference(getPreferenceKey());
    }

    @Override
    public boolean isChecked() {
        if (!mUpdatingConfig.get()) {
            mCacheOfModeStatus = mTelephonyManager.getNetworkSelectionMode();
            for (OnNetworkSelectModeListener lsn : mListeners) {
                lsn.onNetworkSelectModeUpdated(mCacheOfModeStatus);
            }
        }
        return mCacheOfModeStatus == TelephonyManager.NETWORK_SELECTION_MODE_AUTO;
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);

        preference.setSummary(null);
        final ServiceState serviceState = mTelephonyManager.getServiceState();
        if (serviceState == null) {
            preference.setEnabled(false);
            return;
        }

        if (serviceState.getRoaming()) {
            preference.setEnabled(true);
        } else {
            preference.setEnabled(!mOnlyAutoSelectInHome);
            if (mOnlyAutoSelectInHome) {
                preference.setSummary(mContext.getString(
                        R.string.manual_mode_disallowed_summary,
                        mTelephonyManager.getSimOperatorName()));
            }
        }
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        if (mRecursiveUpdate.get() != 0) {
            // Changing from software are allowed and changing presentation only.
            return true;
        }
        if (isChecked) {
            setAutomaticSelectionMode();
        } else {
            if (mSwitchPreference != null) {
                Intent intent = new Intent();
                intent.setClassName(SETTINGS_PACKAGE_NAME,
                        SETTINGS_PACKAGE_NAME + ".Settings$NetworkSelectActivity");
                intent.putExtra(Settings.EXTRA_SUB_ID, mSubId);
                mSwitchPreference.setIntent(intent);
            }
        }
        return false;
    }

    @VisibleForTesting
    Future setAutomaticSelectionMode() {
        final long startMillis = SystemClock.elapsedRealtime();
        showAutoSelectProgressBar();
        if (mSwitchPreference != null) {
            mSwitchPreference.setIntent(null);
            mSwitchPreference.setEnabled(false);
        }
        return ThreadUtils.postOnBackgroundThread(() -> {
            // set network selection mode in background
            mUpdatingConfig.set(true);
            mTelephonyManager.setNetworkSelectionModeAutomatic();
            mUpdatingConfig.set(false);

            //Update UI in UI thread
            final long durationMillis = SystemClock.elapsedRealtime() - startMillis;
            mUiHandler.postDelayed(() -> {
                mRecursiveUpdate.getAndIncrement();
                mSwitchPreference.setEnabled(true);
                mSwitchPreference.setChecked(isChecked());
                mRecursiveUpdate.decrementAndGet();
                dismissProgressBar();
            }, Math.max(MINIMUM_DIALOG_TIME_MILLIS - durationMillis, 0));
        });
    }

    /**
     * Initialization based on given subscription id.
     **/
    public AutoSelectPreferenceController init(int subId) {
        mSubId = subId;
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);
        final PersistableBundle carrierConfig =
                CarrierConfigCache.getInstance(mContext).getConfigForSubId(mSubId);
        mOnlyAutoSelectInHome = carrierConfig != null
                ? carrierConfig.getBoolean(
                CarrierConfigManager.KEY_ONLY_AUTO_SELECT_IN_HOME_NETWORK_BOOL)
                : false;

        return this;
    }

    public AutoSelectPreferenceController addListener(OnNetworkSelectModeListener lsn) {
        mListeners.add(lsn);

        return this;
    }

    private void showAutoSelectProgressBar() {
        if (mProgressDialog == null) {
            mProgressDialog = new ProgressDialog(mContext);
            mProgressDialog.setMessage(
                    mContext.getResources().getString(R.string.register_automatically));
            mProgressDialog.setCanceledOnTouchOutside(false);
            mProgressDialog.setCancelable(false);
            mProgressDialog.setIndeterminate(true);
        }
        mProgressDialog.show();
    }

    private void dismissProgressBar() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            try {
                mProgressDialog.dismiss();
            } catch (IllegalArgumentException e) {
                // Ignore exception since the dialog will be gone anyway.
            }
        }
    }

    /**
     * Callback when network select mode might get updated
     *
     * @see TelephonyManager#getNetworkSelectionMode()
     */
    public interface OnNetworkSelectModeListener {
        void onNetworkSelectModeUpdated(int mode);
    }
}
