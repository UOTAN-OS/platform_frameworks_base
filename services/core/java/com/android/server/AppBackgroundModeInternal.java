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

package com.android.server;

/** Internal state and event feed for uwuAOSP per-application background modes. */
public interface AppBackgroundModeInternal {
    String TAG = "UwuAppBackground";

    /** Returns whether launcher task removal should be ignored for an application UID. */
    boolean shouldKeepTaskAlive(int applicationUid);

    /** Reports whether a process currently hosts a foreground or visible activity. */
    void onProcessActivityStateChanged(int runtimeUid, int processId, int applicationUid,
            boolean visible);

    /** Removes state retained for a process that has exited. */
    void onProcessRemoved(int runtimeUid, int processId, int applicationUid);

    /** Replaces the set of UIDs that are actively playing audio. */
    void onAudioPlaybackActiveUidsChanged(int[] uids);

    /** Replaces the set of UIDs that are actively recording audio. */
    void onAudioRecordingActiveUidsChanged(int[] uids);

    /** Reports creation or removal of a location listener. */
    void onLocationListenerChanged(int uid, boolean added);

    /** Reports whether a UID owns a connected VPN. */
    void onVpnStateChanged(int uid, boolean connected);
}
