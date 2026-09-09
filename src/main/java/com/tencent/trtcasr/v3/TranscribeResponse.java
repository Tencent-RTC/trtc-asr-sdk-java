package com.tencent.trtcasr.v3;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The flat {@code /v3/transcribe} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TranscribeResponse {
    /** A word-level timing entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Word {
        @JsonProperty("word")
        public String word = "";

        @JsonProperty("start_time")
        public long startTime;

        @JsonProperty("end_time")
        public long endTime;
    }

    @JsonProperty("code")
    public long code;

    @JsonProperty("message")
    public String message = "";

    @JsonProperty("request_id")
    public String requestId = "";

    @JsonProperty("result")
    public String result = "";

    @JsonProperty("audio_duration")
    public long audioDuration;

    @JsonProperty("language")
    public String language = "";

    @JsonProperty("language_b47")
    public String languageB47 = "";

    @JsonProperty("word_size")
    public int wordSize;

    @JsonProperty("word_list")
    public List<Word> wordList;
}
