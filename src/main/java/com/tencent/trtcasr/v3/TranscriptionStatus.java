package com.tencent.trtcasr.v3;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The flat {@code /v3/describe_transcription} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TranscriptionStatus {
    /** A word-level timing entry of {@link SentenceDetail}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Word {
        @JsonProperty("word")
        public String word = "";

        @JsonProperty("start_time")
        public long startTime;

        @JsonProperty("end_time")
        public long endTime;
    }

    /** One sentence of a describe_transcription result. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SentenceDetail {
        @JsonProperty("final_sentence")
        public String finalSentence = "";

        @JsonProperty("slice_sentence")
        public String sliceSentence = "";

        @JsonProperty("written_text")
        public String writtenText = "";

        @JsonProperty("start_ms")
        public long startMs;

        @JsonProperty("end_ms")
        public long endMs;

        @JsonProperty("words_num")
        public int wordsNum;

        @JsonProperty("words")
        public List<Word> words;

        @JsonProperty("speech_speed")
        public double speechSpeed;

        @JsonProperty("speaker_id")
        public int speakerId;

        @JsonProperty("channel_id")
        public int channelId;

        @JsonProperty("speaker_role_name")
        public String speakerRoleName = "";

        @JsonProperty("silence_time")
        public int silenceTime;

        @JsonProperty("language")
        public String language = "";

        @JsonProperty("language_b47")
        public String languageB47 = "";
    }

    @JsonProperty("code")
    public long code;

    @JsonProperty("message")
    public String message = "";

    @JsonProperty("request_id")
    public String requestId = "";

    @JsonProperty("transcription_id")
    public String transcriptionId = "";

    @JsonProperty("status")
    public int status;

    @JsonProperty("status_str")
    public String statusStr = "";

    @JsonProperty("progress")
    public int progress;

    @JsonProperty("audio_duration")
    public double audioDuration;

    @JsonProperty("result")
    public String result = "";

    @JsonProperty("result_detail")
    public List<SentenceDetail> resultDetail;

    @JsonProperty("error_msg")
    public String errorMsg = "";
}
