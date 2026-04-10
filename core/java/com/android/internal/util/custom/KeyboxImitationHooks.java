/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-FileCopyrightText: 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyParameterValue;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import com.android.internal.util.custom.KeyboxChainGenerator.GeneratedKeyMaterial;
import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;

import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @hide
 */
public class KeyboxImitationHooks {

    private static final String TAG = "KeyboxImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static KeyMetadata generateKey(IKeystoreSecurityLevel level, KeyDescriptor descriptor,
            Collection<KeyParameter> args, int flags, byte[] entropy) {
        if (!KeyProviderManager.isKeyboxAvailable()) {
            return null;
        }

        KeyGenParameters params = new KeyGenParameters(args.toArray(new KeyParameter[args.size()]));

        if (params.attestationChallenge == null) {
            return null;
        }

        if (params.algorithm != Algorithm.EC && params.algorithm != Algorithm.RSA) {
            Log.w(TAG, "Unsupported algorithm: " + params.algorithm);
            return null;
        }

        int uid = Binder.getCallingUid();
        try {
            GeneratedKeyMaterial keyMaterial = KeyboxChainGenerator.generateKeyMaterial(uid,
                    descriptor, params, entropy);
            if (keyMaterial == null || keyMaterial.certificateChain == null
                    || keyMaterial.certificateChain.isEmpty()) {
                return null;
            }

            List<KeyParameter> importArgs = new ArrayList<>(args.size() + 2);
            for (KeyParameter arg : args) {
                if (shouldKeepForImport(arg)) {
                    importArgs.add(arg);
                }
            }
            addImportSpecificParameters(importArgs, keyMaterial.keyPair);

            byte[] pkcs8EncodedPrivateKey = keyMaterial.keyPair.getPrivate().getEncoded();
            if (pkcs8EncodedPrivateKey == null) {
                return null;
            }

            KeyMetadata metadata = level.importKey(descriptor, null,
                    importArgs.toArray(new KeyParameter[importArgs.size()]), flags,
                    pkcs8EncodedPrivateKey);
            try {
                return updateSubcomponents(metadata.key, keyMaterial.certificateChain);
            } catch (Exception e) {
                cleanupImportedKey(metadata.key);
                throw toKeyStoreException("Failed to finalize imported attestation key", e);
            }
        } catch (KeyStoreException e) {
            throw new RuntimeKeyStoreException(e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key", e);
            return null;
        }
    }

    private static void addImportSpecificParameters(List<KeyParameter> importArgs, KeyPair keyPair) {
        if (keyPair.getPublic() instanceof ECKey ecKey) {
            importArgs.add(makeParameter(Tag.EC_CURVE,
                    KeyParameterValue.ecCurve(getEcCurve(ecKey))));
        } else if (keyPair.getPublic() instanceof RSAPublicKey rsaKey) {
            importArgs.add(makeParameter(Tag.RSA_PUBLIC_EXPONENT,
                    KeyParameterValue.longInteger(rsaKey.getPublicExponent().longValueExact())));
        }
    }

    private static boolean shouldKeepForImport(KeyParameter parameter) {
        return switch (parameter.tag) {
            case Tag.ATTESTATION_CHALLENGE,
                    Tag.ATTESTATION_APPLICATION_ID,
                    Tag.ATTESTATION_ID_BRAND,
                    Tag.ATTESTATION_ID_DEVICE,
                    Tag.ATTESTATION_ID_PRODUCT,
                    Tag.ATTESTATION_ID_SERIAL,
                    Tag.ATTESTATION_ID_IMEI,
                    Tag.ATTESTATION_ID_SECOND_IMEI,
                    Tag.ATTESTATION_ID_MEID,
                    Tag.ATTESTATION_ID_MANUFACTURER,
                    Tag.ATTESTATION_ID_MODEL,
                    Tag.DEVICE_UNIQUE_ATTESTATION,
                    Tag.RESET_SINCE_ID_ROTATION,
                    Tag.KEY_SIZE,
                    Tag.EC_CURVE,
                    Tag.RSA_PUBLIC_EXPONENT,
                    Tag.CERTIFICATE_NOT_BEFORE,
                    Tag.CERTIFICATE_NOT_AFTER,
                    Tag.CERTIFICATE_SERIAL,
                    Tag.CERTIFICATE_SUBJECT -> false;
            default -> true;
        };
    }

    private static int getEcCurve(ECKey key) {
        int fieldSize = key.getParams().getCurve().getField().getFieldSize();
        return switch (fieldSize) {
            case 224 -> EcCurve.P_224;
            case 256 -> EcCurve.P_256;
            case 384 -> EcCurve.P_384;
            case 521 -> EcCurve.P_521;
            default -> throw new IllegalArgumentException("Unsupported EC field size: " + fieldSize);
        };
    }

    private static KeyParameter makeParameter(int tag, KeyParameterValue value) {
        KeyParameter parameter = new KeyParameter();
        parameter.tag = tag;
        parameter.value = value;
        return parameter;
    }

    private static KeyMetadata updateSubcomponents(KeyDescriptor descriptor, List<Certificate> chain)
            throws Exception, KeyStoreException {
        KeyStore2 keyStore = KeyStore2.getInstance();
        byte[] certificate = chain.get(0).getEncoded();
        byte[] certificateChain = null;
        if (chain.size() > 1) {
            certificateChain = KeyboxUtils.toCertificateChainBytes(
                    chain.subList(1, chain.size()).toArray(new Certificate[0]));
        }
        keyStore.updateSubcomponents(descriptor, certificate, certificateChain);
        dlog("Imported generated key for alias: " + descriptor.alias);
        return keyStore.getKeyEntry(descriptor).metadata;
    }

    private static void cleanupImportedKey(KeyDescriptor descriptor) {
        try {
            KeyStore2.getInstance().deleteKey(descriptor);
        } catch (KeyStoreException e) {
            Log.w(TAG, "Failed to clean up imported key after attestation failure", e);
        }
    }

    private static KeyStoreException toKeyStoreException(String message, Exception cause) {
        KeyStoreException exception = new KeyStoreException(android.system.keystore2.ResponseCode.SYSTEM_ERROR,
                message, cause.getMessage());
        exception.initCause(cause);
        return exception;
    }

    public static final class RuntimeKeyStoreException extends RuntimeException {
        RuntimeKeyStoreException(KeyStoreException cause) {
            super(cause);
        }
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
