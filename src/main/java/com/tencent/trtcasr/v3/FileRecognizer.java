package com.tencent.trtcasr.v3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;

/**
 * Async audio file recognition client for the v3 protocol:
 * submit a task ({@code POST /v3/create_transcription}), then poll for
 * results ({@code POST /v3/describe_transcription}).
 *
 * <p>The task ID ({@code transcription_id}) is valid for 24 hours and
 * belongs to the v3 task space: a v1 {@code RecTaskId} cannot be queried
 * through this client and vice versa.
 */
public class FileRecognizer {
    /** Production HTTPS endpoint for the v3 offline interfaces. */
    public static final String FILE_ENDPOINT = "https://asr.cloud-rtc.com";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREATE_PATH = "/v3/create_transcription";
    private static final String DESCRIBE_PATH = "/v3/describe_transcription";
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final Credential credential;
    private String endpoint = "";
    private Duration timeout = Duration.ofSeconds(60);

    public FileRecognizer(Credential credential) {
        this.credential = credential;
    }

    /** Overrides the default API endpoint (for testing). */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Sets the HTTP request timeout. */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /** Submits a file recognition task and returns the transcription ID. */
    public String createTask(CreateTranscriptionRequest req) throws ASRException {
        if (req == null) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "request is null");
        }
        req.validate();

        String requestId = Wire.newRequestId();
        Map<String, Object> body = Wire.offlineEnvelope(credential, requestId, req.toWire());
        JsonNode data = post(CREATE_PATH, body);
        if (data.path("code").asLong(0) != 0) {
            throw Wire.serverError(data);
        }
        String id = data.path("transcription_id").asText("");
        if (id.isEmpty()) {
            throw new ASRException(ErrorCodes.SERVER_ERROR, "empty transcription_id in response");
        }
        return id;
    }

    /** Submits local audio data (auto base64). Max 5MB. */
    public String createTaskFromData(byte[] data, String engineModelType) throws ASRException {
        if (data == null || data.length == 0) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data is empty");
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data exceeds 5MB limit");
        }
        CreateTranscriptionRequest req = new CreateTranscriptionRequest();
        req.engineModelType = engineModelType;
        req.channelNum = 1;
        req.resTextFormat = 1;
        req.sourceType = Wire.SOURCE_TYPE_DATA;
        req.data = java.util.Base64.getEncoder().encodeToString(data);
        req.dataLen = data.length;
        return createTask(req);
    }

    /** Submits an audio URL. Audio ≤12h, ≤1GB. */
    public String createTaskFromURL(String audioUrl, String engineModelType) throws ASRException {
        if (audioUrl == null || audioUrl.isEmpty()) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio URL is empty");
        }
        CreateTranscriptionRequest req = new CreateTranscriptionRequest();
        req.engineModelType = engineModelType;
        req.channelNum = 1;
        req.resTextFormat = 1;
        req.sourceType = Wire.SOURCE_TYPE_URL;
        req.url = audioUrl;
        return createTask(req);
    }

    /**
     * Submits local audio data with a pre-configured request (the request is
     * mutated in place).
     */
    public String createTaskFromDataWithOptions(byte[] rawData, CreateTranscriptionRequest req)
            throws ASRException {
        if (req == null) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "request is null");
        }
        if (rawData == null || rawData.length == 0) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data is empty");
        }
        if (rawData.length > MAX_FILE_SIZE) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data exceeds 5MB limit");
        }
        req.sourceType = Wire.SOURCE_TYPE_DATA;
        req.data = java.util.Base64.getEncoder().encodeToString(rawData);
        req.dataLen = rawData.length;
        return createTask(req);
    }

    /** Queries the status of a file recognition task. */
    public TranscriptionStatus describeTask(String transcriptionId) throws ASRException {
        if (transcriptionId == null || transcriptionId.isEmpty()) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "transcriptionId is empty");
        }
        String requestId = Wire.newRequestId();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("transcription_id", transcriptionId);
        Map<String, Object> body = Wire.offlineEnvelope(credential, requestId, params);
        JsonNode data = post(DESCRIBE_PATH, body);
        if (data.path("code").asLong(0) != 0) {
            throw Wire.serverError(data);
        }
        try {
            return MAPPER.treeToValue(data, TranscriptionStatus.class);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "unmarshal response failed: " + e.getMessage(), e);
        }
    }

    /** Polls for results with the default interval (1s) and timeout (10min). */
    public TranscriptionStatus waitForResult(String transcriptionId) throws ASRException {
        return waitForResultWithInterval(transcriptionId, Duration.ofSeconds(1),
                Duration.ofSeconds(600));
    }

    /** Polls for results with a custom interval and timeout. */
    public TranscriptionStatus waitForResultWithInterval(String transcriptionId,
            Duration interval, Duration timeout) throws ASRException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            TranscriptionStatus status = describeTask(transcriptionId);
            if (status.status == Wire.TASK_STATUS_SUCCESS) {
                return status;
            }
            if (status.status == Wire.TASK_STATUS_FAILED) {
                throw new ASRException(ErrorCodes.SERVER_ERROR,
                        "task failed: " + status.errorMsg
                                + " (transcription_id: " + status.transcriptionId + ")");
            }
            if (System.nanoTime() > deadline) {
                throw new ASRException(ErrorCodes.TIMEOUT,
                        "task not completed within " + timeout.toMillis() + "ms"
                                + " (transcription_id: " + transcriptionId
                                + ", Status: " + status.statusStr + ")");
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ASRException(ErrorCodes.TIMEOUT, "wait interrupted", e);
            }
        }
    }

    private JsonNode post(String path, Object body) throws ASRException {
        String url = Credential.resolveHTTPEndpoint(endpoint, credential.getSite()) + path;
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "marshal request failed: " + e.getMessage(), e);
        }

        int status;
        String respBody;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> httpResp = client.send(httpReq,
                    HttpResponse.BodyHandlers.ofString());
            status = httpResp.statusCode();
            respBody = httpResp.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "http request failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "http request failed: " + e.getMessage(), e);
        }
        return Wire.decodeFlatResponse(MAPPER, respBody, status);
    }
}
