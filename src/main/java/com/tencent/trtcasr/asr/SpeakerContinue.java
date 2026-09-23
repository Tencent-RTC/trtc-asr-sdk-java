package com.tencent.trtcasr.asr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Speaker-context handshake result carried by the first response of a session
 * that enabled the speaker context (v3
 * {@code SpeechRecognizer.setEnableSpeakerContext}); it also reaches
 * {@code onRecognitionStart}. {@code null} when the session did not enable it.
 *
 * <p>Persist {@link #getSpeakerContextId()} on the client side and pass it
 * back through {@code setSpeakerContextId} when reconnecting within its
 * lifetime (24h by default); the response of a later session is
 * authoritative, so always overwrite the stored value. Wait for the first
 * response before sending audio: in sync mode with a stored id the server
 * answers only after the stored snapshot has been applied.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpeakerContinue {
    /**
     * One of Wire.CONTINUE_STATUS_FRESH / RESUMED / DEGRADED / DISABLED.
     * Empty in async mode, where the server answers before the snapshot is
     * loaded. Unknown values mean "no information".
     */
    @JsonProperty("continue_status")
    private String continueStatus = "";

    /**
     * Opaque id of this speaker session; pass it back with
     * {@code setSpeakerContextId} to resume the same speaker identities. It
     * is not a credential and is scoped to the SdkAppID that issued it.
     */
    @JsonProperty("speaker_context_id")
    private String speakerContextId = "";

    public String getContinueStatus() {
        return continueStatus == null ? "" : continueStatus;
    }

    public String getSpeakerContextId() {
        return speakerContextId == null ? "" : speakerContextId;
    }
}
