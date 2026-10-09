package com.tencent.trtcasr.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tencent.trtcasr.common.ASRException;

/**
 * The params block of {@code POST /v3/transcribe}. Field names map
 * one-to-one to the snake_case wire names via {@link #toWire()}.
 */
public class TranscribeRequest {
    public String engineModelType = "";
    public int sourceType = Wire.SOURCE_TYPE_DATA;
    public String voiceFormat = "pcm";

    public String url = "";
    public String data = "";
    public long dataLen;

    public int wordInfo;
    public int filterDirty;
    public int filterModal;
    public int filterPunc;
    public int convertNumMode;
    public String hotwordId = "";
    public String customizationId = "";
    public String hotwordList = "";
    public int inputSampleRate;

    /** None leaves the server default; an explicit 0/1 is honored. */
    public Integer needvad;
    public Integer vadSilenceTime;

    public String language = "";

    public int speakerDiarization;
    public int speakerNumber;

    /** Serializes to the snake_case params block (omitting zero values). */
    public Map<String, Object> toWire() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("engine_model_type", engineModelType);
        d.put("source_type", sourceType);
        d.put("voice_format", voiceFormat);
        if (sourceType == Wire.SOURCE_TYPE_URL) {
            d.put("url", url);
        } else {
            d.put("data", data);
            d.put("data_len", dataLen);
        }
        if (wordInfo != 0) {
            d.put("word_info", wordInfo);
        }
        if (filterDirty != 0) {
            d.put("filter_dirty", filterDirty);
        }
        if (filterModal != 0) {
            d.put("filter_modal", filterModal);
        }
        if (filterPunc != 0) {
            d.put("filter_punc", filterPunc);
        }
        if (convertNumMode != 0) {
            d.put("convert_num_mode", convertNumMode);
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
        if (inputSampleRate != 0) {
            d.put("input_sample_rate", inputSampleRate);
        }
        if (needvad != null) {
            d.put("needvad", needvad);
        }
        if (vadSilenceTime != null) {
            d.put("vad_silence_time", vadSilenceTime);
        }
        if (!language.isEmpty()) {
            d.put("language", language);
        }
        if (speakerDiarization != 0) {
            d.put("speaker_diarization", speakerDiarization);
            if (speakerNumber != 0) {
                d.put("speaker_number", speakerNumber);
            }
        }
        return d;
    }

    /** Validates the required/conditional fields and local ranges. */
    public void validate() throws ASRException {
        if (engineModelType == null || engineModelType.isEmpty()) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "engineModelType is required");
        }
        if (voiceFormat == null || voiceFormat.isEmpty()) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "voiceFormat is required");
        }
        if (sourceType == Wire.SOURCE_TYPE_URL && (url == null || url.isEmpty())) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "url is required when source_type=0");
        }
        if (sourceType == Wire.SOURCE_TYPE_DATA && (data == null || data.isEmpty())) {
            throw new ASRException(com.tencent.trtcasr.common.ErrorCodes.INVALID_PARAM,
                    "data is required when source_type=1");
        }
        if (speakerDiarization != 0 || speakerNumber != 0) {
            Wire.validateSpeakerDiarization(speakerDiarization, speakerNumber, null, null);
        }
        if (needvad != null) {
            Wire.validateEnumOption("needvad", needvad, 0, 1);
        }
        // 8000 is the only supported override; 0 means "use the engine rate".
        Wire.validateEnumOption("input_sample_rate", inputSampleRate, 0, 8000);
    }
}
