package com.tencent.trtcasr.asr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SdkInfo;
import com.tencent.trtcasr.common.SpeakerRole;
import com.tencent.trtcasr.common.UserSig;

/**
 * Async audio file recognition client (HTTP).
 *
 * <p>Unlike {@link SentenceRecognizer} (one-shot, ≤60s), this client handles
 * longer audio via an async workflow: submit a task (CreateRecTask), then
 * poll for results (DescribeTaskStatus).
 */
public class FileRecognizer {
    /** Production HTTPS endpoint for audio file recognition. */
    public static final String FILE_ENDPOINT = "https://asr.cloud-rtc.com";

    /** Task is queued. */
    public static final int TASK_STATUS_WAITING = 0;
    /** Task is being processed. */
    public static final int TASK_STATUS_RUNNING = 1;
    /** Task completed successfully. */
    public static final int TASK_STATUS_SUCCESS = 2;
    /** Task failed. */
    public static final int TASK_STATUS_FAILED = 3;

    /** Max audio size for data upload (before base64 encoding). */
    public static final int MAX_AUDIO_SIZE = 5 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Credential credential;
    private String endpoint = "";
    private HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();
    private Duration requestTimeout = Duration.ofSeconds(60);

    public FileRecognizer(Credential credential) {
        this.credential = credential;
    }

    /** Overrides the default API endpoint (for testing). */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Overrides the HTTP client. */
    public void setHttpClient(HttpClient client) {
        this.httpClient = client;
    }

    /** Overrides the per-request timeout (default 60s). */
    public void setRequestTimeout(Duration timeout) {
        this.requestTimeout = timeout;
    }

    /** Submits an audio file recognition task and returns the task ID. */
    public String createTask(CreateRecTaskRequest req) throws ASRException {
        validateCreateRequest(req);

        String respBody;
        try {
            respBody = doRequest("/v1/CreateRecTask", MAPPER.writeValueAsBytes(req));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ASRException.invalidParam("marshal request failed: " + e.getMessage());
        }

        JsonNode response = parseResponse(respBody);
        checkApiError(response);
        JsonNode data = response.get("Data");
        String taskId = data == null ? "" : data.path("RecTaskId").asText("");
        if (taskId.isEmpty()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR, "empty RecTaskId in response");
        }
        return taskId;
    }

    /**
     * Convenience method that submits local audio data (≤ 5MB), handling
     * base64 encoding automatically.
     */
    public String createTaskFromData(byte[] data, String voiceFormat, String engineModelType)
            throws ASRException {
        if (data == null || data.length == 0) {
            throw ASRException.invalidParam("audio data is empty");
        }
        if (data.length > MAX_AUDIO_SIZE) {
            throw ASRException.invalidParam("audio data exceeds 5MB limit");
        }
        CreateRecTaskRequest req = new CreateRecTaskRequest();
        req.setEngineModelType(engineModelType);
        req.setChannelNum(1);
        req.setResTextFormat(1);
        req.setSourceType(SentenceRecognizer.SOURCE_TYPE_DATA);
        req.setData(Base64.getEncoder().encodeToString(data));
        req.setDataLen(data.length);
        return createTask(req);
    }

    /** Convenience method that submits an audio URL (≤ 12h / ≤ 1GB). */
    public String createTaskFromUrl(String audioUrl, String engineModelType)
            throws ASRException {
        if (audioUrl == null || audioUrl.isEmpty()) {
            throw ASRException.invalidParam("audio URL is empty");
        }
        CreateRecTaskRequest req = new CreateRecTaskRequest();
        req.setEngineModelType(engineModelType);
        req.setChannelNum(1);
        req.setResTextFormat(1);
        req.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
        req.setUrl(audioUrl);
        return createTask(req);
    }

    /**
     * Submits local audio data with a pre-configured request, handling base64
     * encoding automatically. Data/DataLen/SourceType are set from rawData.
     */
    public String createTaskFromDataWithOptions(byte[] rawData, CreateRecTaskRequest req)
            throws ASRException {
        if (rawData == null || rawData.length == 0) {
            throw ASRException.invalidParam("audio data is empty");
        }
        if (rawData.length > MAX_AUDIO_SIZE) {
            throw ASRException.invalidParam("audio data exceeds 5MB limit");
        }
        req.setSourceType(SentenceRecognizer.SOURCE_TYPE_DATA);
        req.setData(Base64.getEncoder().encodeToString(rawData));
        req.setDataLen(rawData.length);
        return createTask(req);
    }

    /** Queries the status of a file recognition task. */
    public TaskStatus describeTaskStatus(String recTaskId) throws ASRException {
        if (recTaskId == null || recTaskId.isEmpty()) {
            throw ASRException.invalidParam("RecTaskId is empty");
        }
        final String body;
        try {
            // Jackson escapes quotes/backslashes/control characters; never
            // hand-concatenate user input into JSON.
            body = MAPPER.writeValueAsString(
                    java.util.Collections.singletonMap("RecTaskId", recTaskId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "marshal request failed: " + e.getMessage(), e);
        }
        String respBody = doRequest("/v1/DescribeTaskStatus", body.getBytes(StandardCharsets.UTF_8));

        JsonNode response = parseResponse(respBody);
        checkApiError(response);
        JsonNode data = response.get("Data");
        if (data == null || data.isNull()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR, "empty response from server");
        }
        try {
            return MAPPER.treeToValue(data, TaskStatus.class);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "unmarshal response failed: " + e.getMessage(), e);
        }
    }

    /**
     * Polls for the result until the task completes or fails. Default poll
     * interval is 1s, max wait is 10 minutes.
     */
    public TaskStatus waitForResult(String recTaskId) throws ASRException {
        return waitForResult(recTaskId, Duration.ofSeconds(1), Duration.ofMinutes(10));
    }

    /** Polls for the result with a custom interval and timeout. */
    public TaskStatus waitForResult(String recTaskId, Duration interval, Duration timeout)
            throws ASRException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (true) {
            TaskStatus status = describeTaskStatus(recTaskId);

            if (status.getStatus() == TASK_STATUS_SUCCESS) {
                return status;
            }
            if (status.getStatus() == TASK_STATUS_FAILED) {
                throw new ASRException(ErrorCodes.SERVER_ERROR, String.format(
                        "task failed: %s (RecTaskId: %s)", status.getErrorMsg(),
                        status.getRecTaskId()));
            }

            if (System.nanoTime() > deadlineNanos) {
                throw new ASRException(ErrorCodes.TIMEOUT, String.format(
                        "task not completed within %s (RecTaskId: %s, Status: %s)",
                        timeout, recTaskId, status.getStatusStr()));
            }

            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ASRException(ErrorCodes.TIMEOUT, "interrupted while polling", e);
            }
        }
    }

    /** Sends an HTTP POST to the given API path with a JSON body. */
    private String doRequest(String path, byte[] jsonBody) throws ASRException {
        String requestId = UUID.randomUUID().toString();

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

        String reqUrl = String.format("%s%s?AppId=%d&Secretid=%d&RequestId=%s&Timestamp=%d&%s",
                Credential.resolveHTTPEndpoint(endpoint, credential.getSite()), path, credential.getAppId(), credential.getAppId(), requestId,
                System.currentTimeMillis() / 1000, SdkInfo.reportQuery());

        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(reqUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-TRTC-SdkAppId", Long.toString(credential.getSdkAppId()))
                .header("X-TRTC-UserSig", userSig)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
                .build();

        HttpResponse<String> httpResp;
        try {
            httpResp = httpClient.send(httpReq,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "http request failed: " + e.getMessage(), e);
        }

        if (httpResp.statusCode() != 200) {
            throw new ASRException(ErrorCodes.SERVER_ERROR,
                    "http status " + httpResp.statusCode() + ": " + httpResp.body());
        }
        return httpResp.body();
    }

    private JsonNode parseResponse(String respBody) throws ASRException {
        JsonNode root;
        try {
            root = MAPPER.readTree(respBody);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "unmarshal response failed: " + e.getMessage(), e);
        }
        JsonNode response = root.get("Response");
        if (response == null || response.isNull()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR, "empty response from server");
        }
        return response;
    }

    private void checkApiError(JsonNode response) throws ASRException {
        JsonNode error = response.get("Error");
        if (error != null && error.isObject()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR, String.format(
                    "server error [%s]: %s (RequestId: %s)",
                    error.path("Code").asText(), error.path("Message").asText(),
                    response.path("RequestId").asText()));
        }
    }

    private void validateCreateRequest(CreateRecTaskRequest req) throws ASRException {
        if (req == null) {
            throw ASRException.invalidParam("request is null");
        }
        if (req.getEngineModelType() == null || req.getEngineModelType().isEmpty()) {
            throw ASRException.invalidParam("EngineModelType is required");
        }
        if (req.getChannelNum() <= 0) {
            throw ASRException.invalidParam("ChannelNum must be positive");
        }
        if (req.getSourceType() == SentenceRecognizer.SOURCE_TYPE_URL
                && (req.getUrl() == null || req.getUrl().isEmpty())) {
            throw ASRException.invalidParam("Url is required when SourceType=0");
        }
        if (req.getSourceType() == SentenceRecognizer.SOURCE_TYPE_DATA
                && (req.getData() == null || req.getData().isEmpty())) {
            throw ASRException.invalidParam("Data is required when SourceType=1");
        }
        ParamsValidator.validateSpeakerDiarization(req.getSpeakerDiarization(),
                req.getSpeakerNumber(), req.getSpeakerRoles(), req.getVoiceprintIds());
        ParamsValidator.validateVadTuning(req.getVadLevel(), req.getNoiseThreshold());
    }

    /** JSON request body for creating a file recognition task. */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public static class CreateRecTaskRequest {
        /** Engine model type. Required. E.g. "16k_zh", "16k_zh_en". */
        @JsonProperty("EngineModelType")
        private String engineModelType = "";

        /** Audio channels. Required. 1: mono; 2: stereo (8k telephony). */
        @JsonProperty("ChannelNum")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private int channelNum;

        /** Result format: 0 basic, 1 word-level timing, 2 +punctuation. */
        @JsonProperty("ResTextFormat")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private int resTextFormat;

        /** Audio source: 0 = URL, 1 = base64 data in body. */
        @JsonProperty("SourceType")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private int sourceType;

        /** Audio file URL (required when sourceType = 0). ≤ 12h, ≤ 1GB. */
        @JsonProperty("Url")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String url = "";

        /** Base64-encoded audio data (required when sourceType = 1). ≤ 5MB. */
        @JsonProperty("Data")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String data = "";

        /** Audio data length in bytes (required when sourceType = 1). */
        @JsonProperty("DataLen")
        private long dataLen;

        /** Callback URL; results are POSTed there when the task completes. */
        @JsonProperty("CallbackUrl")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String callbackUrl = "";

        /** Profanity filter: 0 off (default), 1 filter, 2 replace with *. */
        @JsonProperty("FilterDirty")
        private int filterDirty;

        /** Modal particle filter: 0 off (default), 1 partial, 2 strict. */
        @JsonProperty("FilterModal")
        private int filterModal;

        /** Punctuation filter: 0 off (default), 1 trailing, 2 all. */
        @JsonProperty("FilterPunc")
        private int filterPunc;

        /** Arabic numeral conversion: 0 off, 1 smart (default). */
        @JsonProperty("ConvertNumMode")
        private int convertNumMode;

        /** Hotword vocabulary ID from the console. */
        @JsonProperty("HotwordId")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String hotwordId = "";

        /** Temporary inline hotword list: "word1|weight1,word2|weight2". */
        @JsonProperty("HotwordList")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String hotwordList = "";

        /** Custom language model ID. */
        @JsonProperty("CustomizationId")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String customizationId = "";

        /** Replacement word table ID for forced text replacement. */
        @JsonProperty("ReplaceTextId")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String replaceTextId = "";

        /** Forces the audio language on supporting engines. Empty = auto. */
        @JsonProperty("Language")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String language = "";

        /**
         * Speaker diarization: 0 off (default), 1 anonymous clustering,
         * 3 voiceprint role authentication. For stereo (channelNum=2) do NOT
         * enable this: the server fills channelId per sentence instead.
         */
        @JsonProperty("SpeakerDiarization")
        private int speakerDiarization;

        /** Expected speaker count hint. 0 = auto (default). */
        @JsonProperty("SpeakerNumber")
        private int speakerNumber;

        /** Temporary voiceprints; only used when speakerDiarization is 3. */
        @JsonProperty("SpeakerRoles")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<SpeakerRole> speakerRoles = new ArrayList<>();

        /** Previously enrolled voiceprint IDs; only when mode is 3. */
        @JsonProperty("VoiceprintIds")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> voiceprintIds = new ArrayList<>();

        /** Silence detection threshold in milliseconds. */
        @JsonProperty("VadSilenceMs")
        private int vadSilenceMs;

        /**
         * VAD profile: 0 = high recall (default), 1 = far-field filtering.
         * Boxed so an explicit 0 is distinguishable from "not configured".
         */
        @JsonProperty("VadLevel")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer vadLevel;

        /** VAD noise fine-tuning, range [0, 4]. Overrides vadLevel when set. */
        @JsonProperty("NoiseThreshold")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Double noiseThreshold;

        public String getEngineModelType() {
            return engineModelType;
        }

        public void setEngineModelType(String engineModelType) {
            this.engineModelType = engineModelType;
        }

        public int getChannelNum() {
            return channelNum;
        }

        public void setChannelNum(int channelNum) {
            this.channelNum = channelNum;
        }

        public int getResTextFormat() {
            return resTextFormat;
        }

        public void setResTextFormat(int resTextFormat) {
            this.resTextFormat = resTextFormat;
        }

        public int getSourceType() {
            return sourceType;
        }

        public void setSourceType(int sourceType) {
            this.sourceType = sourceType;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public long getDataLen() {
            return dataLen;
        }

        public void setDataLen(long dataLen) {
            this.dataLen = dataLen;
        }

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
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

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
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
            this.speakerRoles = speakerRoles == null ? new ArrayList<>()
                    : new ArrayList<>(speakerRoles);
        }

        public List<String> getVoiceprintIds() {
            return voiceprintIds;
        }

        public void setVoiceprintIds(List<String> voiceprintIds) {
            this.voiceprintIds = voiceprintIds == null ? new ArrayList<>()
                    : new ArrayList<>(voiceprintIds);
        }

        public int getVadSilenceMs() {
            return vadSilenceMs;
        }

        public void setVadSilenceMs(int vadSilenceMs) {
            this.vadSilenceMs = vadSilenceMs;
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
    }

    /** Full task status and result returned by DescribeTaskStatus. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskStatus {
        @JsonProperty("RecTaskId")
        private String recTaskId = "";

        /** 0 waiting, 1 executing, 2 success, 3 failed. */
        @JsonProperty("Status")
        private int status;

        @JsonProperty("StatusStr")
        private String statusStr = "";

        /** Progress 0-100. */
        @JsonProperty("Progress")
        private int progress;

        @JsonProperty("Result")
        private String result = "";

        @JsonProperty("ErrorMsg")
        private String errorMsg = "";

        @JsonProperty("ResultDetail")
        private List<SentenceDetail> resultDetail = new ArrayList<>();

        /** Audio duration in seconds. */
        @JsonProperty("AudioDuration")
        private double audioDuration;

        public String getRecTaskId() {
            return recTaskId;
        }

        public int getStatus() {
            return status;
        }

        public String getStatusStr() {
            return statusStr;
        }

        public int getProgress() {
            return progress;
        }

        public String getResult() {
            return result;
        }

        public String getErrorMsg() {
            return errorMsg;
        }

        public List<SentenceDetail> getResultDetail() {
            return resultDetail == null ? new ArrayList<>() : resultDetail;
        }

        public double getAudioDuration() {
            return audioDuration;
        }
    }

    /** Sentence-level recognition result with word timing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SentenceDetail {
        @JsonProperty("FinalSentence")
        private String finalSentence = "";

        @JsonProperty("SliceSentence")
        private String sliceSentence = "";

        @JsonProperty("WrittenText")
        private String writtenText = "";

        @JsonProperty("StartMs")
        private long startMs;

        @JsonProperty("EndMs")
        private long endMs;

        @JsonProperty("WordsNum")
        private int wordsNum;

        @JsonProperty("Words")
        private List<SentenceWords> words = new ArrayList<>();

        @JsonProperty("SpeechSpeed")
        private double speechSpeed;

        @JsonProperty("SilenceTime")
        private long silenceTime;

        /** Speaker number of this sentence, when diarization is enabled. */
        @JsonProperty("SpeakerId")
        private int speakerId;

        /** Enrolled role name (mode 3); empty when no enrolled speaker matched. */
        @JsonProperty("SpeakerRoleName")
        private String speakerRoleName = "";

        /** Audio channel for stereo recordings: 1=left, 2=right. */
        @JsonProperty("ChannelId")
        private int channelId;

        /** Detected language of this sentence, when the engine reports one. */
        @JsonProperty("Language")
        private String language = "";

        public String getFinalSentence() {
            return finalSentence;
        }

        public String getSliceSentence() {
            return sliceSentence;
        }

        public String getWrittenText() {
            return writtenText;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }

        public int getWordsNum() {
            return wordsNum;
        }

        public List<SentenceWords> getWords() {
            return words == null ? new ArrayList<>() : words;
        }

        public double getSpeechSpeed() {
            return speechSpeed;
        }

        public long getSilenceTime() {
            return silenceTime;
        }

        public int getSpeakerId() {
            return speakerId;
        }

        public String getSpeakerRoleName() {
            return speakerRoleName;
        }

        public int getChannelId() {
            return channelId;
        }

        public String getLanguage() {
            return language;
        }
    }

    /**
     * Word-level timing within a sentence. Offsets are milliseconds relative
     * to the start of the audio; the server spells them StartTime / EndTime,
     * while the older OffsetStartMs / OffsetEndMs spelling is accepted as a
     * fallback.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SentenceWords {
        @JsonProperty("Word")
        private String word = "";

        @JsonProperty("StartTime")
        @JsonAlias("OffsetStartMs")
        private long offsetStartMs;

        @JsonProperty("EndTime")
        @JsonAlias("OffsetEndMs")
        private long offsetEndMs;

        public String getWord() {
            return word;
        }

        public long getOffsetStartMs() {
            return offsetStartMs;
        }

        public long getOffsetEndMs() {
            return offsetEndMs;
        }
    }
}
