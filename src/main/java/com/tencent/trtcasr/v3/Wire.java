package com.tencent.trtcasr.v3;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.asr.ParamsValidator;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SdkInfo;
import com.tencent.trtcasr.common.UserSig;

/**
 * Shared wire helpers and constants for the TRTC-ASR v3 protocol.
 *
 * <p>The v3 protocol restructures the wire format around two separated
 * blocks: an "auth" block (sdkappid / usersig / request_id) consumed by the
 * gateway's authentication layer, and a "params" block (engine, VAD,
 * hotwords, filters, ...) consumed by the recognition layer. All field names
 * are snake_case and responses are flat (no Response envelope) with numeric
 * codes.
 *
 * <p>v3 uses SdkAppID as the only customer dimension; the Tencent Cloud
 * AppID is not needed (see {@link #newCredential}).
 */
public final class Wire {
    /** SourceType for the HTTP interfaces. */
    public static final int SOURCE_TYPE_URL = 0;
    /** SourceType for the HTTP interfaces. */
    public static final int SOURCE_TYPE_DATA = 1;

    /** Task status values returned by describe_transcription. */
    public static final int TASK_STATUS_WAITING = 0;
    /** Task status values returned by describe_transcription. */
    public static final int TASK_STATUS_RUNNING = 1;
    /** Task status values returned by describe_transcription. */
    public static final int TASK_STATUS_SUCCESS = 2;
    /** Task status values returned by describe_transcription. */
    public static final int TASK_STATUS_FAILED = 3;

    /** Speaker diarization modes. */
    public static final int SPEAKER_DIARIZATION_OFF = 0;
    /** Speaker diarization modes. */
    public static final int SPEAKER_DIARIZATION_CLUSTER = 1;
    /** Speaker diarization modes. */
    public static final int SPEAKER_DIARIZATION_VOICEPRINT = 3;

    /**
     * Speaker-context ("断点续传") modes for enable_speaker_context. They turn
     * speaker diarization into a resumable session: the server stores the
     * stable speaker anchors and hands back an opaque speaker_context_id,
     * which a later connection passes back to keep the same speakers on the
     * same ids. Both modes require speaker diarization.
     */
    public static final int SPEAKER_CONTEXT_OFF = 0;
    /** Sync: with a stored id the first response waits for the snapshot. */
    public static final int SPEAKER_CONTEXT_SYNC = 1;
    /** Async: the first response answers immediately (id only, no status). */
    public static final int SPEAKER_CONTEXT_ASYNC = 2;

    /** continue_status values of SpeakerContinue.getContinueStatus(). */
    public static final String CONTINUE_STATUS_FRESH = "fresh";
    /** continue_status values of SpeakerContinue.getContinueStatus(). */
    public static final String CONTINUE_STATUS_RESUMED = "resumed";
    /** continue_status values of SpeakerContinue.getContinueStatus(). */
    public static final String CONTINUE_STATUS_DEGRADED = "degraded";
    /** continue_status values of SpeakerContinue.getContinueStatus(). */
    public static final String CONTINUE_STATUS_DISABLED = "disabled";

    /** Wire size limit of the online start frame, mirroring the server. */
    public static final int START_FRAME_MAX_BYTES = 64 * 1024;
    /** Wire size limit of one audio frame, mirroring the server. */
    public static final int STREAM_FRAME_MAX_BYTES = 256 * 1024;

    // Server-side accepted ranges (asr-proxy validator_v3.go).
    public static final int MAX_VOICE_ID_LEN = 128;
    static final int MIN_MAX_SPEAK_TIME = 5000;
    static final int MAX_MAX_SPEAK_TIME = 90000;
    static final int MIN_VAD_SILENCE_TIME_MS = 240;
    static final int MAX_VAD_SILENCE_TIME_MS = 2000;
    static final int[] VALID_VOICE_FORMATS = {1, 4, 6, 8, 10, 11, 12, 14, 16};

    private Wire() {
    }

    /** Creates a credential for the v3 API: only SdkAppID + SecretKey. */
    public static Credential newCredential(long sdkAppId, String secretKey) {
        return new Credential(0, sdkAppId, secretKey);
    }

    /** Builds the v3 auth block (sdkappid / usersig / request_id). */
    public static Map<String, String> buildAuthBlock(Credential credential,
            String requestId) throws ASRException {
        String userSig = credential.getUserSig();
        if (userSig.isEmpty()) {
            try {
                userSig = UserSig.genUserSig(credential.getSdkAppId(),
                        credential.getSecretKey(), requestId, 86400);
            } catch (ASRException e) {
                throw new ASRException(ErrorCodes.AUTH_FAILED,
                        "generate user sig failed: " + e.getRawMessage(), e);
            }
        }
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("sdkappid", String.valueOf(credential.getSdkAppId()));
        auth.put("usersig", userSig);
        if (requestId != null && !requestId.isEmpty()) {
            auth.put("request_id", requestId);
        }
        return auth;
    }

    /** Wraps params into the {"auth": ..., "params": ...} offline body,
     * injecting the SDK telemetry into the params block. */
    public static Map<String, Object> offlineEnvelope(Credential credential,
            String requestId, Map<String, Object> params) throws ASRException {
        params.put("sdk_info", SdkInfo.reportParams());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth", buildAuthBlock(credential, requestId));
        body.put("params", params);
        return body;
    }

    /** New UUID for the offline request_id (the UserSig identifier). */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Converts a v3 flat error into an {@link ASRException} carrying the
     * server code. Server codes (4xxx / 5xxx) are disjoint from the
     * SDK-local 10xx codes.
     */
    public static ASRException serverError(JsonNode data) {
        long code = data.path("code").asLong(0);
        String message = data.path("message").asText("");
        String requestId = data.path("request_id").asText("");
        if (!requestId.isEmpty()) {
            message = message + " (request_id: " + requestId + ")";
        }
        return new ASRException((int) code, message);
    }

    /**
     * Unmarshals a v3 flat response body. Two failure shapes are rejected
     * here: a body that is not a JSON object (cannot be a v3 response), and
     * a non-2xx status whose body carries no numeric code — a gateway/LB
     * JSON error page would otherwise decode to code==0 and be mistaken for
     * success. A legitimate v3 endpoint always sends 2xx for code==0, so
     * the second guard never misfires on a real response.
     */
    public static JsonNode decodeFlatResponse(ObjectMapper mapper, String body, int status)
            throws ASRException {
        JsonNode data;
        try {
            data = mapper.readTree(body);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.SERVER_ERROR,
                    "invalid response (http " + status + "): " + e.getMessage());
        }
        if (data == null || !data.isObject()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR,
                    "invalid response (http " + status + "): not a JSON object");
        }
        if ((status < 200 || status > 299) && data.path("code").asLong(0) == 0) {
            String preview = body.length() > 256 ? body.substring(0, 256) + "..." : body;
            throw new ASRException(ErrorCodes.SERVER_ERROR,
                    "http " + status + " with non-v3 response body: " + preview);
        }
        return data;
    }

    /** Checks a small enumerated option such as input_sample_rate. */
    public static void validateEnumOption(String name, int value, int... allowed)
            throws ASRException {
        ParamsValidator.validateEnumOption(name, value, allowed);
    }

    /** Checks the VAD profile and noise threshold (shared with v2). */
    public static void validateVadTuning(Integer vadLevel, Double noiseThreshold)
            throws ASRException {
        ParamsValidator.validateVadTuning(vadLevel, noiseThreshold);
    }

    /**
     * Checks the diarization mode and its enrollment input (v3 shape: roles
     * use the snake_case wire fields role_name / audio_url, unlike the v2
     * CamelCase {@link com.tencent.trtcasr.common.SpeakerRole}).
     */
    public static void validateSpeakerDiarization(int mode, int speakerNumber,
            List<SpeakerRole> roles, List<String> voiceprintIds) throws ASRException {
        if (mode != SPEAKER_DIARIZATION_OFF && mode != 1 && mode != SPEAKER_DIARIZATION_VOICEPRINT) {
            throw ASRException.invalidParam(
                    "SpeakerDiarization must be 0 (off), 1 (cluster) or 3 (voiceprint), got %d",
                    mode);
        }
        if (speakerNumber < 0) {
            throw ASRException.invalidParam(
                    "SpeakerNumber must be >= 0 (0 = auto detection), got %d", speakerNumber);
        }
        boolean hasEnrollment = (roles != null && !roles.isEmpty())
                || (voiceprintIds != null && !voiceprintIds.isEmpty());
        if (mode != SPEAKER_DIARIZATION_VOICEPRINT && hasEnrollment) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "SpeakerRoles/VoiceprintIds require SpeakerDiarization=3");
        }
        if (roles != null) {
            for (int i = 0; i < roles.size(); i++) {
                SpeakerRole role = roles.get(i);
                if (role.getRoleName() == null || role.getRoleName().isEmpty()) {
                    throw ASRException.invalidParam("SpeakerRoles[%d].RoleName is empty", i);
                }
                validateEnrollmentUrl(i, role.getAudioUrl());
            }
        }
        if (voiceprintIds != null) {
            for (int i = 0; i < voiceprintIds.size(); i++) {
                if (voiceprintIds.get(i) == null || voiceprintIds.get(i).isEmpty()) {
                    throw ASRException.invalidParam("VoiceprintIds[%d] is empty", i);
                }
            }
        }
    }

    /**
     * Checks the speaker-context ("断点续传") options.
     *
     * <p>enable_speaker_context accepts 0 (off), 1 (sync) or 2 (async); the
     * server silently normalizes anything else to off, but a caller that meant
     * to enable resumption is better served by an immediate error than by a
     * session that quietly never returns a speaker_context_id.
     *
     * <p>The server ignores the speaker-context parameters entirely when
     * speaker diarization is off, so that combination is a caller mistake as
     * well (mirroring how roles/voiceprint ids require mode 3).
     *
     * <p>speaker_context_id itself is not format-checked on purpose: the
     * server treats an unknown or expired id as "start a new session", so a
     * stale value degrades gracefully instead of failing the connection.
     */
    public static void validateSpeakerContext(int mode, int diarization) throws ASRException {
        if (mode != SPEAKER_CONTEXT_OFF && mode != SPEAKER_CONTEXT_SYNC
                && mode != SPEAKER_CONTEXT_ASYNC) {
            throw ASRException.invalidParam(
                    "EnableSpeakerContext must be 0 (off), 1 (sync) or 2 (async), got %d", mode);
        }
        if (mode != SPEAKER_CONTEXT_OFF && diarization == SPEAKER_DIARIZATION_OFF) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "EnableSpeakerContext requires SpeakerDiarization=1 or 3");
        }
    }

    /** Requires an absolute http(s) URL for enrollment audio. */
    static void validateEnrollmentUrl(int index, String rawUrl) throws ASRException {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw ASRException.invalidParam("SpeakerRoles[%d].AudioURL is empty", index);
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw ASRException.invalidParam(
                        "SpeakerRoles[%d].AudioURL must use http or https", index);
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw ASRException.invalidParam("SpeakerRoles[%d].AudioURL has no host", index);
            }
        } catch (IllegalArgumentException e) {
            throw ASRException.invalidParam("SpeakerRoles[%d].AudioURL is not a valid URL: %s",
                    index, e.getMessage());
        }
    }

    /**
     * Checks the distributed-recording invariants the server enforces
     * (rectask_cluster.go validateDistributedAudioUrlsRequest): audio_urls
     * requires source_type=0 with url/data left empty, and each item needs a
     * unique non-negative index and an absolute http(s) URL.
     */
    public static void validateAudioURLs(int sourceType, String url, String data,
            List<AudioURLItem> audioUrls) throws ASRException {
        if (sourceType != SOURCE_TYPE_URL
                || (url != null && !url.isEmpty())
                || (data != null && !data.isEmpty())) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "AudioURLs cannot be used together with non-zero sourceType, url or data");
        }
        List<Long> seen = new ArrayList<>();
        for (int i = 0; i < audioUrls.size(); i++) {
            AudioURLItem item = audioUrls.get(i);
            if (item.getIndex() < 0) {
                throw ASRException.invalidParam("AudioURLs[%d].Index must be non-negative", i);
            }
            if (seen.contains(item.getIndex())) {
                throw ASRException.invalidParam("AudioURLs index duplicated: %d", item.getIndex());
            }
            seen.add(item.getIndex());
            String raw = item.getUrl() == null ? "" : item.getUrl().trim();
            if (raw.isEmpty()) {
                throw ASRException.invalidParam("AudioURLs[%d].URL is required", i);
            }
            try {
                URI uri = URI.create(raw);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if ((!scheme.equals("http") && !scheme.equals("https"))
                        || uri.getHost() == null || uri.getHost().isEmpty()) {
                    throw ASRException.invalidParam(
                            "AudioURLs[%d].URL must be a valid http/https URL", i);
                }
            } catch (IllegalArgumentException e) {
                throw ASRException.invalidParam("AudioURLs[%d].URL is not a valid URL: %s",
                        i, e.getMessage());
            }
        }
    }
}
