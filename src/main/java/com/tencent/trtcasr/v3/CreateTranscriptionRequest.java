package com.tencent.trtcasr.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tencent.trtcasr.common.ASRException;

/**
 * The params block of {@code POST /v3/create_transcription}. Field names map
 * one-to-one to the snake_case wire names via {@link #toWire()}.
 */
public class CreateTranscriptionRequest {
    public String engineModelType = "";
    public int channelNum = 1;
    public int resTextFormat = 1;
    public int sourceType = Wire.SOURCE_TYPE_DATA;

    public String url = "";
    public String data = "";
    public long dataLen;

    /**
     * Distributed recording pieces (distributed speaker feature extraction).
     * When non-empty, sourceType/url/data must be left at their zero values
     * (sourceType=0, url/data empty) — the server rejects any combination of
     * audio_urls with a single-audio source.
     */
    public List<AudioURLItem> audioUrls;

    public String callbackUrl = "";

    public int speakerDiarization;
    public int speakerNumber;
    public List<String> voiceprintIds;
    public List<SpeakerRole> speakerRoles;

    public String hotwordId = "";
    public String customizationId = "";
    public String hotwordList = "";
    public List<String> keywordLibIdList;
    public String replaceTextId = "";

    public int convertNumMode;
    public int filterDirty;
    public int filterPunc;
    public int filterModal;

    public int sentenceMaxLength;
    public String extra = "";

    public int vadSilenceMs;
    /** None leaves the server default; an explicit 0 is honored. */
    public Integer vadLevel;
    public Double noiseThreshold;

    public String language = "";

    /** Serializes to the snake_case params block (omitting zero values). */
    public Map<String, Object> toWire() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("engine_model_type", engineModelType);
        d.put("channel_num", channelNum);
        d.put("res_text_format", resTextFormat);
        d.put("source_type", sourceType);
        // Distributed mode (sourceType=0 with empty url/data) must omit both
        // fields entirely — the server rejects any url/data alongside
        // audio_urls.
        if (sourceType == Wire.SOURCE_TYPE_URL && !url.isEmpty()) {
            d.put("url", url);
        } else if (sourceType == Wire.SOURCE_TYPE_DATA && !data.isEmpty()) {
            d.put("data", data);
            d.put("data_len", dataLen);
        }
        if (audioUrls != null && !audioUrls.isEmpty()) {
            d.put("audio_urls", audioUrls);
        }
        if (!callbackUrl.isEmpty()) {
            d.put("callback_url", callbackUrl);
        }
        if (speakerDiarization != 0) {
            d.put("speaker_diarization", speakerDiarization);
            if (speakerNumber != 0) {
                d.put("speaker_number", speakerNumber);
            }
        }
        if (speakerDiarization == Wire.SPEAKER_DIARIZATION_VOICEPRINT) {
            if (voiceprintIds != null && !voiceprintIds.isEmpty()) {
                d.put("voiceprint_ids", voiceprintIds);
            }
            if (speakerRoles != null && !speakerRoles.isEmpty()) {
                d.put("speaker_roles", speakerRoles);
            }
        }
        if (!hotwordId.isEmpty()) {
            d.put("hotword_id", hotwordId);
        }
        if (!customizationId.isEmpty()) {
            d.put("customization_id", customizationId);
        }
        if (!hotwordList.isEmpty()) {
            d.put("hotword_list", hotwordList);
        }
        if (keywordLibIdList != null && !keywordLibIdList.isEmpty()) {
            d.put("keyword_lib_id_list", keywordLibIdList);
        }
        if (!replaceTextId.isEmpty()) {
            d.put("replace_text_id", replaceTextId);
        }
        if (convertNumMode != 0) {
            d.put("convert_num_mode", convertNumMode);
        }
        if (filterDirty != 0) {
            d.put("filter_dirty", filterDirty);
        }
        if (filterPunc != 0) {
            d.put("filter_punc", filterPunc);
        }
        if (filterModal != 0) {
            d.put("filter_modal", filterModal);
        }
        if (sentenceMaxLength != 0) {
            d.put("sentence_max_length", sentenceMaxLength);
        }
        if (!extra.isEmpty()) {
            d.put("extra", extra);
        }
        if (vadSilenceMs != 0) {
            d.put("vad_silence_ms", vadSilenceMs);
        }
        if (vadLevel != null) {
            d.put("vad_level", vadLevel);
        }
        if (noiseThreshold != null) {
            d.put("noise_threshold", noiseThreshold);
        }
        if (!language.isEmpty()) {
            d.put("language", language);
        }
        return d;
    }

    /** Validates the required/conditional fields and local ranges. */
    public void validate() throws ASRException {
        if (engineModelType == null || engineModelType.isEmpty()) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "engineModelType is required");
        }
        if (channelNum != 1 && channelNum != 2) {
            throw ASRException.invalidParam("channelNum must be 1 or 2");
        }
        if (resTextFormat < 0 || resTextFormat > 3) {
            throw ASRException.invalidParam(
                    "resTextFormat must be one of [0, 1, 2, 3], got %d", resTextFormat);
        }
        if (channelNum == 2 && speakerDiarization != 0) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "speaker_diarization is not supported for stereo (channel_num=2); "
                            + "sentences carry channel_id instead");
        }
        if (audioUrls != null && !audioUrls.isEmpty()) {
            // Distributed path: the server requires sourceType=0 with
            // url/data empty (rectask_cluster.go
            // validateDistributedAudioUrlsRequest).
            Wire.validateAudioURLs(sourceType, url, data, audioUrls);
        } else {
            if (sourceType == Wire.SOURCE_TYPE_URL && (url == null || url.isEmpty())) {
                throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                        "url is required when source_type=0");
            }
            if (sourceType == Wire.SOURCE_TYPE_DATA && (data == null || data.isEmpty())) {
                throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                        "data is required when source_type=1");
            }
        }
        Wire.validateSpeakerDiarization(speakerDiarization, speakerNumber,
                speakerRoles, voiceprintIds);
        if (noiseThreshold != null) {
            Wire.validateVadTuning(null, noiseThreshold);
        }
        if (vadLevel != null) {
            Wire.validateEnumOption("VadLevel", vadLevel, 0, 1);
        }
    }
}
