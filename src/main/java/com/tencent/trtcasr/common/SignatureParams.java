package com.tencent.trtcasr.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * URL query parameters for the ASR WebSocket request.
 *
 * <p>The {@code secretid} URL parameter is required by the protocol but
 * internally populated with the APPID — users do not provide a separate
 * SecretID. The {@code signature} parameter is set to the UserSig value per
 * protocol spec, and the same value is also sent as {@code usersig} so the
 * gateway can authenticate clients (e.g. browsers) that cannot attach custom
 * WebSocket headers.
 */
public class SignatureParams {
    /** Speaker diarization: disabled (server default). */
    public static final int SPEAKER_DIARIZATION_OFF = 0;
    /** Anonymous clustering: speakers numbered from 1 in the session, -1 unknown. */
    public static final int SPEAKER_DIARIZATION_CLUSTER = 1;
    /** Voiceprint role authentication; combine with speakerRoles / voiceprintIds. */
    public static final int SPEAKER_DIARIZATION_VOICEPRINT = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();

    private final long appId;
    private long timestamp;
    private long expired;
    private int nonce;
    private String engineModelType;
    private String voiceId;
    private int voiceFormat = 1;
    private int needVad = 1;

    /** TRTC application ID, sent as the "sdkappid" query parameter. 0 = unset. */
    private long sdkAppId;

    // Optional parameters (empty/0 means not configured)
    private String hotwordId = "";
    /** Temporary inline hotwords: "word|weight,word|weight". */
    private String hotwordList = "";
    private String customizationId = "";
    private String replaceTextId = "";
    private int filterDirty;
    private int filterModal;
    private int filterPunc;
    private int convertNumMode = 1;
    private int wordInfo;
    private int vadSilenceTime;
    private int maxSpeakTime;
    /** 8000: feed 8kHz PCM to a 16k engine (upsampled server-side). */
    private int inputSampleRate;
    /** Bigmodel engine language hint (e.g. "zh", "en", "auto"). */
    private String language = "";

    /** 0 = deliver empty results, 1 = skip (server default). null = omit. */
    private Integer filterEmptyResult;

    /** VAD profile: 0 = high recall, 1 = far-field (server default). null = omit. */
    private Integer vadLevel;

    /** VAD noise suppression fine-tuning, range [0, 4]. null = omit. */
    private Double noiseThreshold;

    /** 0 = off (default), 1 = anonymous clustering, 3 = voiceprint roles. */
    private int speakerDiarization;

    /** Expected speaker count hint; 0 = auto detection (default). */
    private int speakerNumber;

    /** Temporary voiceprint enrollment entries; only sent when mode is 3. */
    private List<SpeakerRole> speakerRoles = new ArrayList<>();

    /** Pre-registered voiceprint IDs; only sent when mode is 3. */
    private List<String> voiceprintIds = new ArrayList<>();

    /** 说话人分离断点续传：0=off（默认），1=同步回报恢复状态，2=异步只回 id。
     *  需与 speakerDiarization 1/3 同开。
     */
    private int enableSpeakerContext;

    /** 上次会话签发的上下文 ID；仅与 enableSpeakerContext 一起下发。
     *  过期/非法 ID 服务端按新会话处理。
     */
    private String speakerContextId = "";

    public SignatureParams(long appId, String engineModelType, String voiceId) {
        this.appId = appId;
        this.timestamp = System.currentTimeMillis() / 1000;
        this.expired = this.timestamp + 86400;
        this.nonce = RANDOM.nextInt(9_999_999) + 1;
        this.engineModelType = engineModelType;
        this.voiceId = voiceId;
    }

    /** Builds the URL query string with all parameters (without signature). */
    public String buildQueryString() {
        return encodeParams(toMap());
    }

    /**
     * Builds the URL query string with {@code signature} and {@code usersig}
     * set to the given UserSig value (per protocol both carry the UserSig).
     */
    public String buildQueryStringWithSignature(String userSig) {
        Map<String, String> params = toMap();
        params.put("signature", userSig);
        params.put("usersig", userSig);
        return encodeParams(params);
    }

    Map<String, String> toMap() {
        // "secretid" is required by protocol; internally use AppID as its value.
        // TreeMap keeps keys sorted, matching Go's sort.Strings.
        Map<String, String> m = new TreeMap<>();
        m.put("secretid", Long.toString(appId));
        m.put("timestamp", Long.toString(timestamp));
        m.put("expired", Long.toString(expired));
        m.put("nonce", Integer.toString(nonce));
        m.put("engine_model_type", engineModelType);
        m.put("voice_id", voiceId);
        m.put("voice_format", Integer.toString(voiceFormat));
        m.put("needvad", Integer.toString(needVad));

        // SDK self-identification for server-side diagnostics. Not part of the
        // signature (the signature is the UserSig), so it is safe to append.
        m.putAll(SdkInfo.reportParams());

        if (sdkAppId > 0) {
            m.put("sdkappid", Long.toString(sdkAppId));
        }
        if (!hotwordId.isEmpty()) {
            m.put("hotword_id", hotwordId);
        }
        if (!hotwordList.isEmpty()) {
            m.put("hotword_list", hotwordList);
        }
        if (!customizationId.isEmpty()) {
            m.put("customization_id", customizationId);
        }
        if (!replaceTextId.isEmpty()) {
            m.put("replace_text_id", replaceTextId);
        }
        if (filterDirty != 0) {
            m.put("filter_dirty", Integer.toString(filterDirty));
        }
        if (filterModal != 0) {
            m.put("filter_modal", Integer.toString(filterModal));
        }
        if (filterPunc != 0) {
            m.put("filter_punc", Integer.toString(filterPunc));
        }
        if (filterEmptyResult != null) {
            m.put("filter_empty_result", Integer.toString(filterEmptyResult));
        }
        if (convertNumMode != 0) {
            m.put("convert_num_mode", Integer.toString(convertNumMode));
        }
        if (wordInfo != 0) {
            m.put("word_info", Integer.toString(wordInfo));
        }
        if (vadSilenceTime != 0) {
            m.put("vad_silence_time", Integer.toString(vadSilenceTime));
        }
        if (maxSpeakTime != 0) {
            m.put("max_speak_time", Integer.toString(maxSpeakTime));
        }
        if (inputSampleRate != 0) {
            m.put("input_sample_rate", Integer.toString(inputSampleRate));
        }
        // vadLevel / noiseThreshold are tri-state: an explicit 0 differs from
        // "not configured" (the server defaults vad_level to 1), so they are
        // only emitted when the caller set them.
        if (vadLevel != null) {
            m.put("vad_level", Integer.toString(vadLevel));
        }
        if (noiseThreshold != null) {
            // Matches Go strconv.FormatFloat(v, 'f', 3, 64): "0.000", "1.500".
            m.put("noise_threshold", String.format(Locale.ROOT, "%.3f", noiseThreshold));
        }
        if (speakerDiarization != 0) {
            m.put("speaker_diarization", Integer.toString(speakerDiarization));
            if (speakerNumber != 0) {
                m.put("speaker_number", Integer.toString(speakerNumber));
            }
        if (enableSpeakerContext != 0) {
            m.put("enable_speaker_context", Integer.toString(enableSpeakerContext));
            if (!speakerContextId.isEmpty()) {
                m.put("speaker_context_id", speakerContextId);
            }
        }
        }
        // speaker_roles / voiceprintids only apply to voiceprint mode.
        if (speakerDiarization == SPEAKER_DIARIZATION_VOICEPRINT) {
            if (!speakerRoles.isEmpty()) {
                try {
                    m.put("speaker_roles", MAPPER.writeValueAsString(speakerRoles));
                } catch (Exception ignored) {
                    // SpeakerRole serialization cannot fail; ignore.
                }
            }
            if (!voiceprintIds.isEmpty()) {
                try {
                    m.put("voiceprintids", MAPPER.writeValueAsString(voiceprintIds));
                } catch (Exception ignored) {
                    // String list serialization cannot fail; ignore.
                }
            }
        }
        if (!language.isEmpty()) {
            m.put("language", language);
        }
        return m;
    }

    private static String encodeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(queryEscape(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * Percent-encodes a query value with Go {@code url.QueryEscape}
     * semantics: unreserved {@code [A-Za-z0-9-_.~]} stay as-is, space becomes
     * {@code +}, everything else becomes {@code %XX} (uppercase hex, per
     * UTF-8 byte).
     */
    public static String queryEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    // Getters and setters.

    public long getAppId() {
        return appId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getExpired() {
        return expired;
    }

    public void setExpired(long expired) {
        this.expired = expired;
    }

    public int getNonce() {
        return nonce;
    }

    public void setNonce(int nonce) {
        this.nonce = nonce;
    }

    public String getEngineModelType() {
        return engineModelType;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public int getVoiceFormat() {
        return voiceFormat;
    }

    public void setVoiceFormat(int voiceFormat) {
        this.voiceFormat = voiceFormat;
    }

    public int getNeedVad() {
        return needVad;
    }

    public void setNeedVad(int needVad) {
        this.needVad = needVad;
    }

    public long getSdkAppId() {
        return sdkAppId;
    }

    public void setSdkAppId(long sdkAppId) {
        this.sdkAppId = sdkAppId;
    }

    public String getHotwordId() {
        return hotwordId;
    }

    public void setHotwordId(String hotwordId) {
        this.hotwordId = hotwordId;
    }

    public String getHotwordList() {
        return hotwordList;
    }

    public void setHotwordList(String hotwordList) {
        this.hotwordList = hotwordList;
    }

    public String getCustomizationId() {
        return customizationId;
    }

    public void setCustomizationId(String customizationId) {
        this.customizationId = customizationId;
    }

    public String getReplaceTextId() {
        return replaceTextId;
    }

    public void setReplaceTextId(String replaceTextId) {
        this.replaceTextId = replaceTextId;
    }

    public int getFilterDirty() {
        return filterDirty;
    }

    public void setFilterDirty(int filterDirty) {
        this.filterDirty = filterDirty;
    }

    public int getFilterModal() {
        return filterModal;
    }

    public void setFilterModal(int filterModal) {
        this.filterModal = filterModal;
    }

    public int getFilterPunc() {
        return filterPunc;
    }

    public void setFilterPunc(int filterPunc) {
        this.filterPunc = filterPunc;
    }

    public int getConvertNumMode() {
        return convertNumMode;
    }

    public void setConvertNumMode(int convertNumMode) {
        this.convertNumMode = convertNumMode;
    }

    public int getWordInfo() {
        return wordInfo;
    }

    public void setWordInfo(int wordInfo) {
        this.wordInfo = wordInfo;
    }

    public int getVadSilenceTime() {
        return vadSilenceTime;
    }

    public void setVadSilenceTime(int vadSilenceTime) {
        this.vadSilenceTime = vadSilenceTime;
    }

    public int getMaxSpeakTime() {
        return maxSpeakTime;
    }

    public void setMaxSpeakTime(int maxSpeakTime) {
        this.maxSpeakTime = maxSpeakTime;
    }

    public int getInputSampleRate() {
        return inputSampleRate;
    }

    public void setInputSampleRate(int inputSampleRate) {
        this.inputSampleRate = inputSampleRate;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language == null ? "" : language;
    }

    public Integer getFilterEmptyResult() {
        return filterEmptyResult;
    }

    public void setFilterEmptyResult(Integer filterEmptyResult) {
        this.filterEmptyResult = filterEmptyResult;
    }

    public Integer getVadLevel() {
        return vadLevel;
    }

    public void setVadLevel(Integer vadLevel) {
        this.vadLevel = vadLevel;
    }

    public Double getNoiseThreshold() {
        return noiseThreshold;
    }

    public void setNoiseThreshold(Double noiseThreshold) {
        this.noiseThreshold = noiseThreshold;
    }

    public int getSpeakerDiarization() {
        return speakerDiarization;
    }

    public void setSpeakerDiarization(int speakerDiarization) {
        this.speakerDiarization = speakerDiarization;
    }

    public int getSpeakerNumber() {
        return speakerNumber;
    }

    public void setSpeakerNumber(int speakerNumber) {
        this.speakerNumber = speakerNumber;
    }

    public List<SpeakerRole> getSpeakerRoles() {
        return speakerRoles;
    }

    public void setSpeakerRoles(List<SpeakerRole> speakerRoles) {
        this.speakerRoles = speakerRoles == null ? new ArrayList<>() : new ArrayList<>(speakerRoles);
    }

    public List<String> getVoiceprintIds() {
        return voiceprintIds;
    }

    public void setVoiceprintIds(List<String> voiceprintIds) {
        this.voiceprintIds = voiceprintIds == null ? new ArrayList<>() : new ArrayList<>(voiceprintIds);
    }

    public void setEnableSpeakerContext(int enableSpeakerContext) {
        this.enableSpeakerContext = enableSpeakerContext;
    }

    public void setSpeakerContextId(String speakerContextId) {
        this.speakerContextId = speakerContextId == null ? "" : speakerContextId.trim();
    }
}
