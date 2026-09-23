package com.tencent.trtcasr.asr;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A response message from the ASR service (realtime WebSocket protocol). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpeechRecognitionResponse {
    @JsonProperty("code")
    private int code;

    @JsonProperty("message")
    private String message = "";

    @JsonProperty("voice_id")
    private String voiceId = "";

    @JsonProperty("message_id")
    private String messageId = "";

    /** 1 marks the session-ending frame. */
    @JsonProperty("final")
    private int finalFlag;

    @JsonProperty("result")
    private RecognitionResult result = new RecognitionResult();

    /**
     * Speaker-context result of the first response, present only when the
     * session enabled the speaker context (v3
     * {@code setEnableSpeakerContext}); it also reaches
     * {@code onRecognitionStart}.
     */
    @JsonProperty("speaker_continue")
    private SpeakerContinue speakerContinue;

    /** Package-private factory for the session-start callback response. */
    public static SpeechRecognitionResponse sessionStart(String voiceId) {
        return sessionStart(voiceId, null);
    }

    /** Package-private factory for the session-start callback response. */
    public static SpeechRecognitionResponse sessionStart(String voiceId,
            SpeakerContinue speakerContinue) {
        SpeechRecognitionResponse resp = new SpeechRecognitionResponse();
        resp.code = 0;
        resp.message = "success";
        resp.voiceId = voiceId;
        resp.speakerContinue = speakerContinue;
        return resp;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public String getMessageId() {
        return messageId;
    }

    public int getFinalFlag() {
        return finalFlag;
    }

    public RecognitionResult getResult() {
        return result;
    }

    /** Speaker-context handshake result; {@code null} unless the session
     * enabled the speaker context (v3 only). */
    public SpeakerContinue getSpeakerContinue() {
        return speakerContinue;
    }

    /** Recognition result details carried by a realtime response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecognitionResult {
        /** 0 = sentence begin, 1 = intermediate, 2 = sentence-final. */
        @JsonProperty("slice_type")
        private int sliceType;

        @JsonProperty("index")
        private int index;

        @JsonProperty("start_time")
        private long startTime;

        @JsonProperty("end_time")
        private long endTime;

        @JsonProperty("voice_text_str")
        private String voiceTextStr = "";

        @JsonProperty("word_size")
        private int wordSize;

        @JsonProperty("word_list")
        private List<WordInfo> wordList = new ArrayList<>();

        /** Detected language when the engine reports one. */
        @JsonProperty("language")
        private String language = "";

        /**
         * Speaker attribution of this result, split by speaker turn. This is
         * the recommended entry point for speaker diarization: one result may
         * contain several speakers. Empty when diarization is disabled. A
         * result is single-speaker when {@code speakerSegments.size() == 1}.
         */
        @JsonProperty("speaker_segments")
        private List<SpeakerSegment> speakerSegments;

        /**
         * Legacy sentence-level speaker attribution. {@code Integer} because 0
         * is a reserved value and the field is absent on most engines. Prefer
         * {@link #getSpeakerSegments()} / {@link WordInfo#getSpeakerId()}.
         */
        @JsonProperty("speaker_id")
        private Integer speakerId;

        /** Trailing silence (ms) that triggered the sentence break. */
        @JsonProperty("finish_silence_ms")
        private long finishSilenceMs;

        /** Server-side decoding time (ms) of the last token. */
        @JsonProperty("last_token_runtime_ms")
        private long lastTokenRuntimeMs;

        public int getSliceType() {
            return sliceType;
        }

        public int getIndex() {
            return index;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public String getVoiceTextStr() {
            return voiceTextStr;
        }

        public int getWordSize() {
            return wordSize;
        }

        public List<WordInfo> getWordList() {
            return wordList == null ? new ArrayList<>() : wordList;
        }

        public String getLanguage() {
            return language;
        }

        public List<SpeakerSegment> getSpeakerSegments() {
            return speakerSegments == null ? new ArrayList<>() : speakerSegments;
        }

        public Integer getSpeakerId() {
            return speakerId;
        }

        public long getFinishSilenceMs() {
            return finishSilenceMs;
        }

        public long getLastTokenRuntimeMs() {
            return lastTokenRuntimeMs;
        }
    }

    /** A contiguous section of one result attributed to a single speaker. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpeakerSegment {
        /** Speaker number in the session. Valid IDs start at 1, -1 unknown. */
        @JsonProperty("speaker_id")
        private int speakerId;

        /** Enrolled role name, only with speaker_diarization=3. */
        @JsonProperty("speaker_name")
        private String speakerName = "";

        @JsonProperty("start_time")
        private long startTime;

        @JsonProperty("end_time")
        private long endTime;

        @JsonProperty("text")
        private String text = "";

        /** Inclusive indexes into wordList; null when word_info=0. */
        @JsonProperty("word_start")
        private Integer wordStart;

        @JsonProperty("word_end")
        private Integer wordEnd;

        /** 1 = stable, 0 = not. */
        @JsonProperty("stable_flag")
        private int stableFlag;

        public int getSpeakerId() {
            return speakerId;
        }

        public String getSpeakerName() {
            return speakerName;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public String getText() {
            return text;
        }

        public Integer getWordStart() {
            return wordStart;
        }

        public Integer getWordEnd() {
            return wordEnd;
        }

        public int getStableFlag() {
            return stableFlag;
        }
    }

    /** Word-level recognition details. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WordInfo {
        @JsonProperty("word")
        private String word = "";

        @JsonProperty("start_time")
        private long startTime;

        @JsonProperty("end_time")
        private long endTime;

        @JsonProperty("stable_flag")
        private int stableFlag;

        /** Speaker of this word; requires diarization + word_info != 0. */
        @JsonProperty("speaker_id")
        private int speakerId;

        /** Enrolled role name, only with speaker_diarization=3. */
        @JsonProperty("speaker_name")
        private String speakerName = "";

        public String getWord() {
            return word;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public int getStableFlag() {
            return stableFlag;
        }

        public int getSpeakerId() {
            return speakerId;
        }

        public String getSpeakerName() {
            return speakerName;
        }
    }
}
