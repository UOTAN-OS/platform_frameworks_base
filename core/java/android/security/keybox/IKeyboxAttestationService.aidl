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

package android.security.keybox;

import android.hardware.security.keymint.KeyParameter;
import android.security.keybox.AttestationCertificates;

interface IKeyboxAttestationService {
    AttestationCertificates generateCertificateChain(int targetUid, String alias, int domain,
            long nspace, in KeyParameter[] params, in byte[] leafCertificate);

    AttestationCertificates generateSoftwareKey(int targetUid, String alias, int domain,
            long nspace, in KeyParameter[] params, in byte[] entropy);
}
