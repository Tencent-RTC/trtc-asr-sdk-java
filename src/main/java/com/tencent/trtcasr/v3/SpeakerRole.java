package com.tencent.trtcasr.v3;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A temporary voiceprint enrollment entry used with
 * {@code speaker_diarization=3}. Serialized with the v3 snake_case wire
 * names ({@code role_name} / {@code audio_url}), unlike the v2 CamelCase
 * {@link com.tencent.trtcasr.common.SpeakerRole}.
 */
public class SpeakerRole {
    @JsonProperty("role_name")
    private String roleName;

    @JsonProperty("audio_url")
    private String audioUrl;

    public SpeakerRole() {
    }

    public SpeakerRole(String roleName, String audioUrl) {
        this.roleName = roleName;
        this.audioUrl = audioUrl;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }
}
