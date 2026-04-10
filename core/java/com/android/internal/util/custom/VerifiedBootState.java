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

package com.android.internal.util.custom;

import android.os.SystemProperties;

public final class VerifiedBootState {
    private static final String REAL_VBMETA_DIGEST_PROP = "ro.boot.vbmeta.digest";
    private static final String REAL_VBMETA_PUBLIC_KEY_DIGEST_PROP =
            "ro.boot.vbmeta.public_key_digest";
    private static final int DIGEST_SIZE_BYTES = 32;

    private VerifiedBootState() {}

    public static void ensureInitialized() {}

    public static String getVerifiedBootHashHex() {
        ensureInitialized();
        return getEffectiveDigestHex(REAL_VBMETA_DIGEST_PROP);
    }

    public static String getVerifiedBootKeyHex() {
        ensureInitialized();
        return getEffectiveDigestHex(REAL_VBMETA_PUBLIC_KEY_DIGEST_PROP);
    }

    public static byte[] getVerifiedBootHashBytes() {
        return hexToBytes(getVerifiedBootHashHex());
    }

    public static byte[] getVerifiedBootKeyBytes() {
        return hexToBytes(getVerifiedBootKeyHex());
    }

    private static String getEffectiveDigestHex(String property) {
        String primary = SystemProperties.get(property, "");
        if (isValidDigestHex(primary)) {
            return primary.toLowerCase();
        }

        return "0".repeat(DIGEST_SIZE_BYTES * 2);
    }

    private static boolean isValidDigestHex(String value) {
        if (value == null || value.length() != DIGEST_SIZE_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9')
                    && (c < 'a' || c > 'f')
                    && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[DIGEST_SIZE_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return bytes;
    }
}
