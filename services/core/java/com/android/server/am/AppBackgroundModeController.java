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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_CAPABILITY_CPU_TIME;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_RESTRICTION_CHANGE;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.os.BackgroundThread;
import com.android.server.AppBackgroundModeInternal;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.am.psc.ProcessRecordInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native uwuAOSP implementation of per-app tombstone and Full background modes. */
final class AppBackgroundModeController {
    static final String TAG = AppBackgroundModeInternal.TAG;

    private static final ArraySet<String> ALWAYS_CRITICAL_PACKAGES = new ArraySet<>(Arrays.asList(
            "android",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings",
            "com.android.systemui",
            "org.uwuaosp.settingsext"));

    private final ActivityManagerService mService;
    private final Context mContext;
    private final Handler mHandler;
    private PackageManager mPackageManager;
    private final Object mLock = new Object();
    private final SparseArray<ArrayMap<String, Integer>> mModesByUser = new SparseArray<>();
    private final SparseBooleanArray mIgnoreTaskRemovalByUser = new SparseBooleanArray();
    private final SparseIntArray mEffectiveUidModes = new SparseIntArray();

    // All fields below are accessed on mHandler, except where explicitly synchronized.
    private final SparseArray<Runnable> mPendingFreezes = new SparseArray<>();
    private final SparseArray<Runnable> mBinderProtectionTimeouts = new SparseArray<>();
    private final SparseArray<Runnable> mBinderRecoveryRetries = new SparseArray<>();
    private final Object mStateLock = new Object();
    private final SparseIntArray mRuntimeToApplicationUid = new SparseIntArray();
    private final SparseArray<ArraySet<Integer>> mKnownProcesses = new SparseArray<>();
    private final SparseArray<ArraySet<Integer>> mVisibleProcesses = new SparseArray<>();
    private final ArraySet<Integer> mAudioUids = new ArraySet<>();
    private final ArraySet<Integer> mRecordingUids = new ArraySet<>();
    private final SparseIntArray mLocationListenerCounts = new SparseIntArray();
    private final ArraySet<Integer> mVpnUids = new ArraySet<>();
    private final ArraySet<Integer> mBinderProtectedUids = new ArraySet<>();
    private final ArraySet<Integer> mBinderRecoveryPendingUids = new ArraySet<>();

    private boolean mSystemReady;
    private boolean mPackageManagerRetryScheduled;
    private boolean mLoggedFreezerUnavailable;
    private boolean mDeviceIdleRetryScheduled;

    private final AppBackgroundModeInternal mLocalService = new LocalService();

    AppBackgroundModeController(ActivityManagerService service) {
        mService = service;
        mContext = service.mContext;
        mHandler = BackgroundThread.getHandler();
    }

    AppBackgroundModeInternal getLocalService() {
        return mLocalService;
    }

    void onSystemReady() {
        if (mSystemReady) {
            return;
        }
        mPackageManager = mContext.getPackageManager();
        if (mPackageManager == null) {
            if (!mPackageManagerRetryScheduled) {
                mPackageManagerRetryScheduled = true;
                Slog.w(TAG, "PackageManager unavailable; retrying initialization");
                mHandler.postDelayed(() -> {
                    mPackageManagerRetryScheduled = false;
                    onSystemReady();
                }, 1_000L);
            }
            return;
        }
        mSystemReady = true;
        final ContentResolver resolver = mContext.getContentResolver();
        final ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                reloadUser(userId);
            }
        };
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.UWU_APP_BACKGROUND_MODES),
                false, settingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(
                        Settings.Secure.UWU_APP_BACKGROUND_IGNORE_TASK_REMOVAL),
                false, settingsObserver, UserHandle.USER_ALL);

        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mReceiver, packages, null, mHandler);

        final IntentFilter policy = new IntentFilter();
        policy.addAction(Intent.ACTION_USER_ADDED);
        policy.addAction(Intent.ACTION_USER_REMOVED);
        policy.addAction(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
        policy.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverForAllUsers(mReceiver, policy, null, mHandler);

        mHandler.post(() -> {
            final UserManager userManager = mContext.getSystemService(UserManager.class);
            if (userManager == null) {
                reloadUser(UserHandle.USER_SYSTEM);
                return;
            }
            for (UserHandle user : userManager.getUserHandles(true)) {
                reloadUser(user.getIdentifier());
            }
        });
    }

    int adjustOomAdj(@NonNull ProcessRecordInternal app, int adj) {
        return getUidMode(app.getApplicationUid()) == AppBackgroundModeConfig.MODE_FULL
                ? Math.min(adj, ProcessList.PERCEPTIBLE_APP_ADJ) : adj;
    }

    boolean isFullMode(@NonNull ProcessRecordInternal app) {
        return getUidMode(app.getApplicationUid()) == AppBackgroundModeConfig.MODE_FULL;
    }

    boolean isTombstoneMode(@NonNull ProcessRecordInternal app) {
        return getUidMode(app.getApplicationUid()) == AppBackgroundModeConfig.MODE_TOMBSTONE;
    }

    void onBinderActivity(int applicationUid, @NonNull String reason) {
        mHandler.post(() -> beginBinderProtection(applicationUid, reason));
    }

    void onBinderRecoveryFailed(int applicationUid, @NonNull String reason) {
        synchronized (mStateLock) {
            mBinderProtectedUids.add(applicationUid);
            mBinderRecoveryPendingUids.add(applicationUid);
        }
        mHandler.post(() -> scheduleBinderRecoveryRetry(applicationUid, reason));
    }

    boolean isBinderRecoveryPending(int applicationUid) {
        synchronized (mStateLock) {
            return mBinderRecoveryPendingUids.contains(applicationUid);
        }
    }

    boolean shouldIgnoreTaskRemoval(@NonNull ProcessRecordInternal app) {
        return shouldKeepTaskAlive(app.getApplicationUid());
    }

    private boolean shouldKeepTaskAlive(int applicationUid) {
        synchronized (mLock) {
            return AppBackgroundModeConfig.shouldIgnoreTaskRemoval(
                    mIgnoreTaskRemovalByUser.get(
                            UserHandle.getUserId(applicationUid), false),
                    mEffectiveUidModes.get(
                            applicationUid, AppBackgroundModeConfig.MODE_DEFAULT));
        }
    }

    private int getUidMode(int uid) {
        synchronized (mLock) {
            return mEffectiveUidModes.get(uid, AppBackgroundModeConfig.MODE_DEFAULT);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (Intent.ACTION_USER_REMOVED.equals(action) && userId != UserHandle.USER_NULL) {
                synchronized (mLock) {
                    mModesByUser.remove(userId);
                    mIgnoreTaskRemovalByUser.delete(userId);
                }
                rebuildEffectiveModes();
                return;
            }
            if (userId != UserHandle.USER_NULL && userId != UserHandle.USER_ALL) {
                reloadUser(userId);
                return;
            }
            reloadAllUsers();
        }
    };

    private void reloadAllUsers() {
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            reloadUser(UserHandle.USER_SYSTEM);
            return;
        }
        for (UserHandle user : userManager.getUserHandles(true)) {
            reloadUser(user.getIdentifier(), false);
        }
        rebuildEffectiveModes();
    }

    private void reloadUser(int userId) {
        reloadUser(userId, true);
    }

    private void reloadUser(int userId, boolean rebuild) {
        if (userId < 0) {
            return;
        }
        final ArraySet<String> criticalPackages = collectCriticalPackages(userId);
        final String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.UWU_APP_BACKGROUND_MODES, userId);
        final boolean ignoreTaskRemoval = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.UWU_APP_BACKGROUND_IGNORE_TASK_REMOVAL, 0, userId) != 0;
        final AppBackgroundModeConfig.ParseResult parsed = AppBackgroundModeConfig.parse(value,
                packageName -> isConfigurablePackage(packageName, userId, criticalPackages));
        synchronized (mLock) {
            mModesByUser.put(userId, parsed.modes);
            mIgnoreTaskRemovalByUser.put(userId, ignoreTaskRemoval);
        }
        if (parsed.changed) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.UWU_APP_BACKGROUND_MODES, parsed.normalized, userId);
            Slog.i(TAG, "Cleaned background mode configuration for user " + userId);
        } else {
            Slog.i(TAG, "Loaded " + parsed.modes.size() + " background modes for user " + userId);
        }
        if (rebuild) {
            rebuildEffectiveModes();
        }
    }

    private void rebuildEffectiveModes() {
        final SparseIntArray previous;
        final SparseIntArray next = new SparseIntArray();
        final ArraySet<Integer> candidateUids = new ArraySet<>();
        synchronized (mLock) {
            previous = mEffectiveUidModes.clone();
            for (int userIndex = 0; userIndex < mModesByUser.size(); userIndex++) {
                final int userId = mModesByUser.keyAt(userIndex);
                final ArrayMap<String, Integer> modes = mModesByUser.valueAt(userIndex);
                for (int packageIndex = 0; packageIndex < modes.size(); packageIndex++) {
                    final ApplicationInfo info = getApplicationInfo(modes.keyAt(packageIndex),
                            userId);
                    if (info != null) {
                        candidateUids.add(info.uid);
                    }
                }
            }
        }

        final ArraySet<String> fullPackages = new ArraySet<>();
        final ArraySet<Integer> fullAppIds = new ArraySet<>();
        for (int i = 0; i < candidateUids.size(); i++) {
            final int uid = candidateUids.valueAt(i);
            final int mode = resolveUidMode(uid);
            if (mode != AppBackgroundModeConfig.MODE_DEFAULT) {
                next.put(uid, mode);
            }
            if (mode == AppBackgroundModeConfig.MODE_FULL) {
                fullAppIds.add(UserHandle.getAppId(uid));
                final String[] packages = mPackageManager.getPackagesForUid(uid);
                if (packages != null) {
                    fullPackages.addAll(Arrays.asList(packages));
                }
            }
        }

        synchronized (mLock) {
            mEffectiveUidModes.clear();
            for (int i = 0; i < next.size(); i++) {
                mEffectiveUidModes.put(next.keyAt(i), next.valueAt(i));
            }
        }
        updateDeviceIdleAllowlist(fullPackages, fullAppIds);
        reconcileChangedUids(previous, next);
    }

    private int resolveUidMode(int uid) {
        final String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return AppBackgroundModeConfig.MODE_DEFAULT;
        }
        final int userId = UserHandle.getUserId(uid);
        final ArrayMap<String, Integer> userModes;
        synchronized (mLock) {
            userModes = mModesByUser.get(userId);
            if (userModes == null) {
                return AppBackgroundModeConfig.MODE_DEFAULT;
            }
        }
        final int[] modes = new int[packages.length];
        for (int i = 0; i < packages.length; i++) {
            modes[i] = userModes.getOrDefault(packages[i], AppBackgroundModeConfig.MODE_DEFAULT);
        }
        return AppBackgroundModeConfig.resolveUidMode(modes);
    }

    private void updateDeviceIdleAllowlist(ArraySet<String> packages, ArraySet<Integer> appIds) {
        final DeviceIdleInternal deviceIdle = LocalServices.getService(DeviceIdleInternal.class);
        if (deviceIdle == null) {
            if (!mDeviceIdleRetryScheduled) {
                mDeviceIdleRetryScheduled = true;
                Slog.w(TAG, "DeviceIdleInternal unavailable; retrying Full mode allowlist");
                mHandler.postDelayed(() -> {
                    mDeviceIdleRetryScheduled = false;
                    rebuildEffectiveModes();
                }, 5_000L);
            }
            return;
        }
        mDeviceIdleRetryScheduled = false;
        final int[] ids = new int[appIds.size()];
        for (int i = 0; i < appIds.size(); i++) {
            ids[i] = appIds.valueAt(i);
        }
        Arrays.sort(ids);
        deviceIdle.setAppBackgroundModeWhitelist(packages.toArray(new String[0]), ids);
    }

    private void reconcileChangedUids(SparseIntArray previous, SparseIntArray next) {
        final ArraySet<Integer> changed = new ArraySet<>();
        for (int i = 0; i < previous.size(); i++) {
            final int uid = previous.keyAt(i);
            if (previous.valueAt(i) != next.get(uid, AppBackgroundModeConfig.MODE_DEFAULT)) {
                changed.add(uid);
            }
        }
        for (int i = 0; i < next.size(); i++) {
            final int uid = next.keyAt(i);
            if (next.valueAt(i) != previous.get(uid, AppBackgroundModeConfig.MODE_DEFAULT)) {
                changed.add(uid);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        Slog.i(TAG, "Applying background mode changes to " + changed.size() + " UIDs");
        synchronized (mService) {
            mService.updateOomAdjLocked(OOM_ADJ_REASON_RESTRICTION_CHANGE);
            synchronized (mService.mProcLock) {
                for (int i = 0; i < changed.size(); i++) {
                    final int uid = changed.valueAt(i);
                    if (next.get(uid, AppBackgroundModeConfig.MODE_DEFAULT)
                            == AppBackgroundModeConfig.MODE_TOMBSTONE) {
                        scheduleFreeze(uid, AppBackgroundModeConfig.FREEZE_DELAY_MS,
                                "mode changed");
                    } else {
                        cancelFreeze(uid);
                        cancelBinderProtectionTimeout(uid);
                        if (previous.get(uid, AppBackgroundModeConfig.MODE_DEFAULT)
                                == AppBackgroundModeConfig.MODE_TOMBSTONE) {
                            mService.getCachedAppOptimizer()
                                    .markTombstoneThawRecoveryForUidLSP(uid);
                        }
                        unfreezeUidLSP(uid, "mode changed");
                        if (!isBinderRecoveryPending(uid)) {
                            synchronized (mStateLock) {
                                mBinderProtectedUids.remove(uid);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isConfigurablePackage(String packageName, int userId,
            ArraySet<String> criticalPackages) {
        final ApplicationInfo info = getApplicationInfo(packageName, userId);
        if (AppBackgroundModeConfig.isCoreApplication(
                info, criticalPackages.contains(packageName))) {
            return false;
        }
        final boolean systemApp = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        final String[] sharedPackages = mPackageManager.getPackagesForUid(info.uid);
        return !systemApp || sharedPackages == null || sharedPackages.length <= 1;
    }

    private ApplicationInfo getApplicationInfo(String packageName, int userId) {
        try {
            return mPackageManager.getApplicationInfoAsUser(packageName,
                    PackageManager.ApplicationInfoFlags.of(
                            PackageManager.MATCH_DISABLED_COMPONENTS),
                    userId);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private ArraySet<String> collectCriticalPackages(int userId) {
        final ArraySet<String> packages = new ArraySet<>(ALWAYS_CRITICAL_PACKAGES);
        addResolvedPackages(packages,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), userId);
        addResolvedPackages(packages,
                new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(Uri.parse("package:example")),
                userId);

        final String permissionController = mPackageManager.getPermissionControllerPackageName();
        if (permissionController != null) {
            packages.add(permissionController);
        }

        final InputMethodManagerInternal inputMethodManager =
                LocalServices.getService(InputMethodManagerInternal.class);
        if (inputMethodManager != null) {
            for (InputMethodInfo inputMethod
                    : inputMethodManager.getInputMethodListAsUser(userId)) {
                packages.add(inputMethod.getPackageName());
            }
        }

        final DevicePolicyManager devicePolicyManager =
                mContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager != null) {
            final List<ComponentName> admins = devicePolicyManager.getActiveAdminsAsUser(userId);
            if (admins != null) {
                for (ComponentName admin : admins) {
                    packages.add(admin.getPackageName());
                }
            }
        }

        final TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        if (telecomManager != null) {
            final String dialer = telecomManager.getDefaultDialerPackage(UserHandle.of(userId));
            if (dialer != null) {
                packages.add(dialer);
            }
        }
        return packages;
    }

    private void addResolvedPackages(ArraySet<String> packages, Intent intent, int userId) {
        final List<ResolveInfo> resolved = mPackageManager.queryIntentActivitiesAsUser(intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL), userId);
        for (ResolveInfo info : resolved) {
            if (info.activityInfo != null) {
                packages.add(info.activityInfo.packageName);
            }
        }
    }

    private void setActiveUidSnapshot(ArraySet<Integer> target, int[] runtimeUids,
            boolean audio) {
        final ArraySet<Integer> next = new ArraySet<>();
        synchronized (mStateLock) {
            for (int uid : runtimeUids) {
                next.add(normalizeUidLocked(uid));
            }
            final ArrayList<Integer> removed = new ArrayList<>();
            final ArrayList<Integer> added = new ArrayList<>();
            for (int i = 0; i < target.size(); i++) {
                final int uid = target.valueAt(i);
                if (!next.contains(uid)) {
                    removed.add(uid);
                }
            }
            for (int i = 0; i < next.size(); i++) {
                final int uid = next.valueAt(i);
                if (!target.contains(uid)) {
                    added.add(uid);
                }
            }
            target.clear();
            target.addAll(next);
            for (int uid : added) {
                onProtectionStarted(uid, audio ? "audio started" : "recording started");
            }
            for (int uid : removed) {
                onProtectionStopped(uid,
                        audio ? AppBackgroundModeConfig.AUDIO_STOP_FREEZE_DELAY_MS
                                : AppBackgroundModeConfig.FREEZE_DELAY_MS,
                        audio ? "audio stopped" : "recording stopped");
            }
        }
    }

    private int normalizeUidLocked(int runtimeUid) {
        return mRuntimeToApplicationUid.get(runtimeUid, runtimeUid);
    }

    private void registerRuntimeUidMappingLocked(int runtimeUid, int applicationUid) {
        mRuntimeToApplicationUid.put(runtimeUid, applicationUid);
        if (runtimeUid == applicationUid) {
            return;
        }
        if (mAudioUids.remove(runtimeUid)) {
            mAudioUids.add(applicationUid);
        }
        if (mRecordingUids.remove(runtimeUid)) {
            mRecordingUids.add(applicationUid);
        }
        final int locationCount = mLocationListenerCounts.get(runtimeUid, 0);
        if (locationCount > 0) {
            mLocationListenerCounts.delete(runtimeUid);
            mLocationListenerCounts.put(applicationUid,
                    mLocationListenerCounts.get(applicationUid, 0) + locationCount);
        }
        if (mVpnUids.remove(runtimeUid)) {
            mVpnUids.add(applicationUid);
        }
    }

    private void onProtectionStarted(int uid, String reason) {
        cancelFreeze(uid);
        mHandler.post(() -> unfreezeUid(uid, reason));
    }

    private void onProtectionStopped(int uid, long delay, String reason) {
        if (!isProtectedLocked(uid)) {
            scheduleFreeze(uid, delay, reason);
        }
    }

    private boolean isProtectedLocked(int uid) {
        final ArraySet<Integer> visible = mVisibleProcesses.get(uid);
        return (visible != null && !visible.isEmpty())
                || mAudioUids.contains(uid)
                || mRecordingUids.contains(uid)
                || mLocationListenerCounts.get(uid, 0) > 0
                || mVpnUids.contains(uid)
                || mBinderProtectedUids.contains(uid);
    }

    private boolean isProtected(int uid) {
        synchronized (mStateLock) {
            return isProtectedLocked(uid);
        }
    }

    private void beginBinderProtection(int uid, String reason) {
        if (getUidMode(uid) != AppBackgroundModeConfig.MODE_TOMBSTONE) {
            return;
        }
        if (isBinderRecoveryPending(uid)) {
            return;
        }
        cancelBinderProtectionTimeout(uid);
        synchronized (mStateLock) {
            mBinderProtectedUids.add(uid);
        }
        cancelFreeze(uid);
        unfreezeUid(uid, "binder activity: " + reason);

        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (mBinderProtectionTimeouts.get(uid) != this) {
                    return;
                }
                mBinderProtectionTimeouts.remove(uid);
                if (isBinderRecoveryPending(uid)) {
                    return;
                }
                synchronized (mStateLock) {
                    mBinderProtectedUids.remove(uid);
                }
                scheduleFreeze(uid, 0, "binder idle");
            }
        };
        mBinderProtectionTimeouts.put(uid, timeout);
        mHandler.postDelayed(timeout, AppBackgroundModeConfig.FREEZE_DELAY_MS);
    }

    private void scheduleBinderRecoveryRetry(int uid, String reason) {
        if (mBinderRecoveryRetries.get(uid) != null) {
            return;
        }
        synchronized (mStateLock) {
            mBinderProtectedUids.add(uid);
        }
        cancelBinderProtectionTimeout(uid);
        cancelFreeze(uid);
        final Runnable retry = () -> {
            mBinderRecoveryRetries.remove(uid);
            synchronized (mStateLock) {
                mBinderRecoveryPendingUids.remove(uid);
            }
            if (getUidMode(uid) == AppBackgroundModeConfig.MODE_TOMBSTONE) {
                beginBinderProtection(uid, "recovery retry: " + reason);
            } else {
                unfreezeUid(uid, "binder recovery retry: " + reason);
                if (!isBinderRecoveryPending(uid)) {
                    synchronized (mStateLock) {
                        mBinderProtectedUids.remove(uid);
                    }
                }
            }
        };
        mBinderRecoveryRetries.put(uid, retry);
        mHandler.postDelayed(retry, AppBackgroundModeConfig.BINDER_RECOVERY_RETRY_DELAY_MS);
        Slog.w(TAG, "Scheduled binder recovery uid=" + uid + " reason=" + reason);
    }

    private void cancelBinderProtectionTimeout(int uid) {
        final Runnable timeout = mBinderProtectionTimeouts.get(uid);
        if (timeout != null) {
            mHandler.removeCallbacks(timeout);
            mBinderProtectionTimeouts.remove(uid);
        }
    }

    private void scheduleFreeze(int uid, long delay, String reason) {
        if (getUidMode(uid) != AppBackgroundModeConfig.MODE_TOMBSTONE || isProtected(uid)) {
            return;
        }
        if (mPendingFreezes.get(uid) != null) {
            return;
        }
        final Runnable task = () -> {
            mPendingFreezes.remove(uid);
            freezeUid(uid);
        };
        mPendingFreezes.put(uid, task);
        mHandler.postDelayed(task, delay);
        Slog.i(TAG, "Scheduled freeze uid=" + uid + " delay=" + delay + "ms reason=" + reason);
    }

    private void cancelFreeze(int uid) {
        final Runnable pending = mPendingFreezes.get(uid);
        if (pending != null) {
            mHandler.removeCallbacks(pending);
            mPendingFreezes.remove(uid);
            Slog.i(TAG, "Cancelled freeze uid=" + uid);
        }
    }

    private void freezeUid(int uid) {
        if (getUidMode(uid) != AppBackgroundModeConfig.MODE_TOMBSTONE || isProtected(uid)) {
            Slog.i(TAG, "Skipped freeze uid=" + uid + " reason=protected or mode changed");
            return;
        }
        if (!mService.getCachedAppOptimizer().useFreezer()) {
            if (!mLoggedFreezerUnavailable) {
                mLoggedFreezerUnavailable = true;
                Slog.w(TAG, "Freezer unavailable; tombstone modes remain configured");
            }
            return;
        }
        synchronized (mService) {
            synchronized (mService.mProcLock) {
                if (isProtected(uid)) {
                    Slog.i(TAG, "Skipped freeze uid=" + uid + " reason=state changed");
                    return;
                }
                final ArrayList<ProcessRecord> processes = collectProcessesForUidLSP(uid);
                if (processes.isEmpty()) {
                    return;
                }
                for (ProcessRecord process : processes) {
                    final String skipReason = getFreezeSkipReasonLSP(process);
                    if (skipReason != null) {
                        Slog.i(TAG, "Deferred freeze uid=" + uid + " pid=" + process.getPid()
                                + " reason=" + skipReason);
                        unfreezeUidLSP(uid, "freeze deferred: " + skipReason);
                        scheduleFreeze(uid, AppBackgroundModeConfig.FREEZE_DELAY_MS,
                                "retry after " + skipReason);
                        return;
                    }
                }
                for (ProcessRecord process : processes) {
                    if (process.isFrozen() || process.isPendingFreeze()) {
                        continue;
                    }
                    mService.getCachedAppOptimizer().freezeAppAsyncImmediateLSP(process);
                    Slog.i(TAG, "Freezing uid=" + uid + " pid=" + process.getPid()
                            + " process=" + process.processName);
                }
            }
        }
    }

    private String getFreezeSkipReasonLSP(ProcessRecord process) {
        if (process.getPid() == 0 || process.getThread() == null) {
            return "process not running";
        }
        if (process.getHasForegroundActivities() || process.hasVisibleActivities()) {
            return "foreground activity";
        }
        if (process.mServices.hasForegroundServices()) {
            return "foreground service";
        }
        if (process.mReceivers.isReceivingBroadcast()) {
            return "receiving broadcast";
        }
        if (process.mServices.hasExecutingServices()) {
            return "executing service";
        }
        if (process.hasActiveInstrumentation()) {
            return "instrumentation";
        }
        if ((process.getCurCapability() & PROCESS_CAPABILITY_CPU_TIME) != 0) {
            return "explicit CPU capability";
        }
        if (process.shouldNotFreeze()) {
            return "AOSP freezer exemption " + process.shouldNotFreezeReason();
        }
        if (process.getCurAdj() < ProcessList.CACHED_APP_MIN_ADJ
                || process.getSetAdj() < ProcessList.CACHED_APP_MIN_ADJ) {
            return "not cached curAdj=" + process.getCurAdj()
                    + " setAdj=" + process.getSetAdj();
        }
        if (!process.isFreezable()) {
            return "AOSP freeze policy";
        }
        return null;
    }

    private ArrayList<ProcessRecord> collectProcessesForUidLSP(int applicationUid) {
        final ArrayList<ProcessRecord> processes = new ArrayList<>();
        mService.mProcessList.forEachLruProcessesLOSP(false, process -> {
            if (process.getApplicationUid() == applicationUid) {
                processes.add(process);
            }
        });
        return processes;
    }

    private void unfreezeUid(int uid, String reason) {
        synchronized (mService) {
            synchronized (mService.mProcLock) {
                unfreezeUidLSP(uid, reason);
            }
        }
    }

    private void unfreezeUidLSP(int uid, String reason) {
        for (ProcessRecord process : collectProcessesForUidLSP(uid)) {
            if (process.isFrozen() || process.isPendingFreeze()
                    || mService.getCachedAppOptimizer()
                            .hasPendingTombstoneRecoveryLSP(process)) {
                mService.getCachedAppOptimizer().unfreezeAppLSP(process,
                        CachedAppOptimizer.UNFREEZE_REASON_UI_VISIBILITY, true);
                Slog.i(TAG, "Unfroze uid=" + uid + " pid=" + process.getPid()
                        + " reason=" + reason);
            }
        }
    }

    private final class LocalService implements AppBackgroundModeInternal {
        @Override
        public boolean shouldKeepTaskAlive(int applicationUid) {
            return AppBackgroundModeController.this.shouldKeepTaskAlive(applicationUid);
        }

        @Override
        public void onProcessActivityStateChanged(int runtimeUid, int processId,
                int applicationUid, boolean visible) {
            if (processId <= 0) {
                return;
            }
            final boolean wasProtected;
            final boolean protectedNow;
            final boolean firstSeen;
            synchronized (mStateLock) {
                wasProtected = isProtectedLocked(applicationUid);
                registerRuntimeUidMappingLocked(runtimeUid, applicationUid);

                ArraySet<Integer> known = mKnownProcesses.get(applicationUid);
                if (known == null) {
                    known = new ArraySet<>();
                    mKnownProcesses.put(applicationUid, known);
                }
                firstSeen = known.add(processId);

                ArraySet<Integer> visibleProcesses = mVisibleProcesses.get(applicationUid);
                if (visibleProcesses == null && visible) {
                    visibleProcesses = new ArraySet<>();
                    mVisibleProcesses.put(applicationUid, visibleProcesses);
                }
                if (visibleProcesses != null) {
                    if (visible) {
                        visibleProcesses.add(processId);
                    } else {
                        visibleProcesses.remove(processId);
                    }
                    if (visibleProcesses.isEmpty()) {
                        mVisibleProcesses.remove(applicationUid);
                    }
                }
                protectedNow = isProtectedLocked(applicationUid);
            }
            if (!wasProtected && protectedNow) {
                mHandler.post(() -> onProtectionStarted(
                        applicationUid, "activity visible"));
            } else if ((wasProtected && !protectedNow) || (firstSeen && !protectedNow)) {
                mHandler.post(() -> onProtectionStopped(applicationUid,
                        AppBackgroundModeConfig.FREEZE_DELAY_MS, "activity background"));
            }
        }

        @Override
        public void onProcessRemoved(int runtimeUid, int processId, int applicationUid) {
            if (processId <= 0) {
                return;
            }
            final boolean wasProtected;
            final boolean protectedNow;
            final boolean hasProcesses;
            synchronized (mStateLock) {
                wasProtected = isProtectedLocked(applicationUid);
                final ArraySet<Integer> known = mKnownProcesses.get(applicationUid);
                if (known != null) {
                    known.remove(processId);
                    if (known.isEmpty()) {
                        mKnownProcesses.remove(applicationUid);
                    }
                }
                final ArraySet<Integer> visibleProcesses =
                        mVisibleProcesses.get(applicationUid);
                if (visibleProcesses != null) {
                    visibleProcesses.remove(processId);
                    if (visibleProcesses.isEmpty()) {
                        mVisibleProcesses.remove(applicationUid);
                    }
                }
                if (runtimeUid != applicationUid) {
                    mRuntimeToApplicationUid.delete(runtimeUid);
                }
                protectedNow = isProtectedLocked(applicationUid);
                final ArraySet<Integer> remaining = mKnownProcesses.get(applicationUid);
                hasProcesses = remaining != null && !remaining.isEmpty();
            }
            if (!hasProcesses) {
                mHandler.post(() -> cancelFreeze(applicationUid));
            } else if (wasProtected && !protectedNow) {
                mHandler.post(() -> onProtectionStopped(applicationUid,
                        AppBackgroundModeConfig.FREEZE_DELAY_MS, "process removed"));
            }
        }

        @Override
        public void onAudioPlaybackActiveUidsChanged(int[] uids) {
            final int[] snapshot = uids.clone();
            mHandler.post(() -> setActiveUidSnapshot(mAudioUids, snapshot, true));
        }

        @Override
        public void onAudioRecordingActiveUidsChanged(int[] uids) {
            final int[] snapshot = uids.clone();
            mHandler.post(() -> setActiveUidSnapshot(mRecordingUids, snapshot, false));
        }

        @Override
        public void onLocationListenerChanged(int runtimeUid, boolean added) {
            mHandler.post(() -> {
                synchronized (mStateLock) {
                    final int uid = normalizeUidLocked(runtimeUid);
                    final int oldCount = mLocationListenerCounts.get(uid, 0);
                    final int newCount = Math.max(0, oldCount + (added ? 1 : -1));
                    if (newCount == 0) {
                        mLocationListenerCounts.delete(uid);
                    } else {
                        mLocationListenerCounts.put(uid, newCount);
                    }
                    if (oldCount == 0 && newCount > 0) {
                        onProtectionStarted(uid, "location listener added");
                    } else if (oldCount > 0 && newCount == 0) {
                        onProtectionStopped(uid, AppBackgroundModeConfig.FREEZE_DELAY_MS,
                                "location listener removed");
                    }
                }
            });
        }

        @Override
        public void onVpnStateChanged(int runtimeUid, boolean connected) {
            mHandler.post(() -> {
                synchronized (mStateLock) {
                    final int uid = normalizeUidLocked(runtimeUid);
                    final boolean changed = connected ? mVpnUids.add(uid) : mVpnUids.remove(uid);
                    if (!changed) {
                        return;
                    }
                    if (connected) {
                        onProtectionStarted(uid, "VPN connected");
                    } else {
                        onProtectionStopped(uid, AppBackgroundModeConfig.FREEZE_DELAY_MS,
                                "VPN disconnected");
                    }
                }
            });
        }
    }
}
