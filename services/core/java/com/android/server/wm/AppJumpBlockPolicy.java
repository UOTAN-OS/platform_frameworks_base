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

package com.android.server.wm;

import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores per-user app jump policy state for source packages, target packages, and pair overrides.
 */
final class AppJumpBlockPolicy {
    private static final String TAG = "AppJumpBlockPolicy";
    private static final String CONFIG_FILE_NAME = "app-jump-policy.xml";

    private static final String TAG_ROOT = "app-jump-policy";
    private static final String TAG_ALLOWED_SOURCE_PACKAGE = "allowed-source-package";
    private static final String TAG_BLOCKED_SOURCE_PACKAGE = "blocked-source-package";
    private static final String TAG_LEGACY_BLOCKED_SOURCE_PACKAGE = "blocked-package";
    private static final String TAG_ALLOWED_TARGET_PACKAGE = "allowed-target-package";
    private static final String TAG_BLOCKED_TARGET_PACKAGE = "blocked-target-package";
    private static final String TAG_PAIR_RULE = "pair-rule";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_SOURCE_PACKAGE = "source-package";
    private static final String ATTR_TARGET_PACKAGE = "target-package";
    private static final String ATTR_MODE = "mode";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<UserPolicyState> mPoliciesByUser = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseArray<AtomicFile> mConfigFilesByUser = new SparseArray<>();

    @GuardedBy("mLock")
    private final ArraySet<String> mBypassTokens = new ArraySet<>();

    AppJumpBlockPolicy() {
    }

    String createBypassToken() {
        final String token = UUID.randomUUID().toString();
        synchronized (mLock) {
            mBypassTokens.add(token);
        }
        return token;
    }

    boolean consumeBypassToken(@Nullable String token) {
        if (token == null) {
            return false;
        }
        synchronized (mLock) {
            return mBypassTokens.remove(token);
        }
    }

    void setSourceMode(int userId, String sourcePackage, int mode) {
        synchronized (mLock) {
            final UserPolicyState policy = getPolicyLocked(userId);
            setPackageModeLocked(policy.allowedSourcePackages, policy.blockedSourcePackages,
                    sourcePackage, mode);
            writeToFileLocked(userId, policy);
        }
    }

    int getSourceMode(int userId, String sourcePackage) {
        synchronized (mLock) {
            final UserPolicyState policy = getPolicyLocked(userId);
            return getPackageModeLocked(policy.allowedSourcePackages,
                    policy.blockedSourcePackages, sourcePackage);
        }
    }

    void setTargetMode(int userId, String targetPackage, int mode) {
        synchronized (mLock) {
            final UserPolicyState policy = getPolicyLocked(userId);
            setPackageModeLocked(policy.allowedTargetPackages, policy.blockedTargetPackages,
                    targetPackage, mode);
            writeToFileLocked(userId, policy);
        }
    }

    int getTargetMode(int userId, String targetPackage) {
        synchronized (mLock) {
            final UserPolicyState policy = getPolicyLocked(userId);
            return getPackageModeLocked(policy.allowedTargetPackages,
                    policy.blockedTargetPackages, targetPackage);
        }
    }

    void setBlocked(int userId, String sourcePackage, boolean blocked) {
        setSourceMode(userId, sourcePackage, blocked
                ? ActivityTaskManager.APP_JUMP_SOURCE_MODE_BLOCK
                : ActivityTaskManager.APP_JUMP_SOURCE_MODE_ASK);
    }

    boolean isBlocked(int userId, String sourcePackage) {
        return getSourceMode(userId, sourcePackage) == ActivityTaskManager.APP_JUMP_SOURCE_MODE_BLOCK;
    }

    void setPairMode(int userId, String sourcePackage, String targetPackage, int mode) {
        synchronized (mLock) {
            final UserPolicyState policy = getPolicyLocked(userId);
            ArrayMap<String, Integer> pairModes = policy.pairModesBySourcePackage.get(sourcePackage);
            if (mode == ActivityTaskManager.APP_JUMP_PAIR_MODE_INHERIT) {
                if (pairModes == null) {
                    return;
                }
                pairModes.remove(targetPackage);
                if (pairModes.isEmpty()) {
                    policy.pairModesBySourcePackage.remove(sourcePackage);
                }
            } else {
                if (pairModes == null) {
                    pairModes = new ArrayMap<>();
                    policy.pairModesBySourcePackage.put(sourcePackage, pairModes);
                }
                pairModes.put(targetPackage, mode);
            }
            writeToFileLocked(userId, policy);
        }
    }

    int getPairMode(int userId, String sourcePackage, String targetPackage) {
        synchronized (mLock) {
            final ArrayMap<String, Integer> pairModes =
                    getPolicyLocked(userId).pairModesBySourcePackage.get(sourcePackage);
            if (pairModes == null) {
                return ActivityTaskManager.APP_JUMP_PAIR_MODE_INHERIT;
            }
            final Integer mode = pairModes.get(targetPackage);
            return mode != null ? mode : ActivityTaskManager.APP_JUMP_PAIR_MODE_INHERIT;
        }
    }

    void setTargetAllowed(int userId, String targetPackage, boolean allowed) {
        setTargetMode(userId, targetPackage, allowed
                ? ActivityTaskManager.APP_JUMP_SOURCE_MODE_ALLOW
                : ActivityTaskManager.APP_JUMP_SOURCE_MODE_ASK);
    }

    boolean isTargetAllowed(int userId, String targetPackage) {
        return getTargetMode(userId, targetPackage) == ActivityTaskManager.APP_JUMP_SOURCE_MODE_ALLOW;
    }

    @GuardedBy("mLock")
    private UserPolicyState getPolicyLocked(int userId) {
        UserPolicyState policy = mPoliciesByUser.get(userId);
        if (policy != null) {
            return policy;
        }
        policy = readFromFileLocked(userId);
        mPoliciesByUser.put(userId, policy);
        return policy;
    }

    @GuardedBy("mLock")
    private UserPolicyState readFromFileLocked(int userId) {
        final UserPolicyState policy = new UserPolicyState();
        FileInputStream fis = null;
        try {
            fis = getConfigFileLocked(userId).openRead();
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // Skip to the first tag.
            }
            if (type != XmlPullParser.START_TAG || !TAG_ROOT.equals(parser.getName())) {
                return policy;
            }
            final int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final String tagName = parser.getName();
                if (TAG_ALLOWED_SOURCE_PACKAGE.equals(tagName)) {
                    readPackageTagIntoSet(parser, policy.allowedSourcePackages);
                } else if (TAG_BLOCKED_SOURCE_PACKAGE.equals(tagName)
                        || TAG_LEGACY_BLOCKED_SOURCE_PACKAGE.equals(tagName)) {
                    readPackageTagIntoSet(parser, policy.blockedSourcePackages);
                } else if (TAG_ALLOWED_TARGET_PACKAGE.equals(tagName)) {
                    readPackageTagIntoSet(parser, policy.allowedTargetPackages);
                } else if (TAG_BLOCKED_TARGET_PACKAGE.equals(tagName)) {
                    readPackageTagIntoSet(parser, policy.blockedTargetPackages);
                } else if (TAG_PAIR_RULE.equals(tagName)) {
                    final String sourcePackage =
                            parser.getAttributeValue(null, ATTR_SOURCE_PACKAGE);
                    final String targetPackage =
                            parser.getAttributeValue(null, ATTR_TARGET_PACKAGE);
                    final int mode = parser.getAttributeInt(null, ATTR_MODE,
                            ActivityTaskManager.APP_JUMP_PAIR_MODE_INHERIT);
                    if (sourcePackage == null || targetPackage == null
                            || mode == ActivityTaskManager.APP_JUMP_PAIR_MODE_INHERIT) {
                        continue;
                    }
                    ArrayMap<String, Integer> pairModes =
                            policy.pairModesBySourcePackage.get(sourcePackage);
                    if (pairModes == null) {
                        pairModes = new ArrayMap<>();
                        policy.pairModesBySourcePackage.put(sourcePackage, pairModes);
                    }
                    pairModes.put(targetPackage, mode);
                }
            }
        } catch (FileNotFoundException e) {
            // No persisted rules yet.
        } catch (IOException | XmlPullParserException e) {
            Slog.w(TAG, "Failed to read app jump policy for user " + userId, e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close app jump policy file for user " + userId, e);
                }
            }
        }
        return policy;
    }

    @GuardedBy("mLock")
    private void writeToFileLocked(int userId, UserPolicyState policy) {
        final AtomicFile configFile = getConfigFileLocked(userId);
        final File parent = configFile.getBaseFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Slog.w(TAG, "Failed to create app jump policy directory for user " + userId);
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = configFile.startWrite();
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_ROOT);
            writePackageSet(serializer, TAG_ALLOWED_SOURCE_PACKAGE, policy.allowedSourcePackages);
            writePackageSet(serializer, TAG_BLOCKED_SOURCE_PACKAGE, policy.blockedSourcePackages);
            writePackageSet(serializer, TAG_ALLOWED_TARGET_PACKAGE, policy.allowedTargetPackages);
            writePackageSet(serializer, TAG_BLOCKED_TARGET_PACKAGE, policy.blockedTargetPackages);
            for (int i = 0; i < policy.pairModesBySourcePackage.size(); i++) {
                final String sourcePackage = policy.pairModesBySourcePackage.keyAt(i);
                final ArrayMap<String, Integer> pairModes = policy.pairModesBySourcePackage.valueAt(i);
                for (int j = 0; j < pairModes.size(); j++) {
                    serializer.startTag(null, TAG_PAIR_RULE);
                    serializer.attribute(null, ATTR_SOURCE_PACKAGE, sourcePackage);
                    serializer.attribute(null, ATTR_TARGET_PACKAGE, pairModes.keyAt(j));
                    serializer.attributeInt(null, ATTR_MODE, pairModes.valueAt(j));
                    serializer.endTag(null, TAG_PAIR_RULE);
                }
            }
            serializer.endTag(null, TAG_ROOT);
            serializer.endDocument();
            configFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write app jump policy for user " + userId, e);
            if (fos != null) {
                configFile.failWrite(fos);
            }
        }
    }

    @GuardedBy("mLock")
    private AtomicFile getConfigFileLocked(int userId) {
        AtomicFile configFile = mConfigFilesByUser.get(userId);
        if (configFile != null) {
            return configFile;
        }
        configFile = new AtomicFile(new File(Environment.getDataSystemDeDirectory(userId),
                CONFIG_FILE_NAME), "app-jump-policy");
        mConfigFilesByUser.put(userId, configFile);
        return configFile;
    }

    private static void setPackageModeLocked(ArraySet<String> allowedPackages,
            ArraySet<String> blockedPackages, String packageName, int mode) {
        allowedPackages.remove(packageName);
        blockedPackages.remove(packageName);
        if (mode == ActivityTaskManager.APP_JUMP_SOURCE_MODE_ALLOW) {
            allowedPackages.add(packageName);
        } else if (mode == ActivityTaskManager.APP_JUMP_SOURCE_MODE_BLOCK) {
            blockedPackages.add(packageName);
        }
    }

    private static int getPackageModeLocked(ArraySet<String> allowedPackages,
            ArraySet<String> blockedPackages, String packageName) {
        if (allowedPackages.contains(packageName)) {
            return ActivityTaskManager.APP_JUMP_SOURCE_MODE_ALLOW;
        }
        if (blockedPackages.contains(packageName)) {
            return ActivityTaskManager.APP_JUMP_SOURCE_MODE_BLOCK;
        }
        return ActivityTaskManager.APP_JUMP_SOURCE_MODE_ASK;
    }

    private static void readPackageTagIntoSet(TypedXmlPullParser parser, ArraySet<String> targetSet) {
        final String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
        if (packageName != null) {
            targetSet.add(packageName);
        }
    }

    private static void writePackageSet(TypedXmlSerializer serializer, String tagName,
            ArraySet<String> packages) throws IOException {
        for (int i = 0; i < packages.size(); i++) {
            serializer.startTag(null, tagName);
            serializer.attribute(null, ATTR_PACKAGE_NAME, packages.valueAt(i));
            serializer.endTag(null, tagName);
        }
    }

    private static final class UserPolicyState {
        final ArraySet<String> allowedSourcePackages = new ArraySet<>();
        final ArraySet<String> blockedSourcePackages = new ArraySet<>();
        final ArraySet<String> allowedTargetPackages = new ArraySet<>();
        final ArraySet<String> blockedTargetPackages = new ArraySet<>();
        final ArrayMap<String, ArrayMap<String, Integer>> pairModesBySourcePackage =
                new ArrayMap<>();
    }
}
