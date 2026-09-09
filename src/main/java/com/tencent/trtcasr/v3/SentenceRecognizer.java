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
 * One-shot sentence recognition client for the v3 protocol
 * ({@code POST /v3/transcribe}): {@code {"auth": ..., "params": ...}} body,
 * flat snake_case response, numeric codes.
 *
 * <p>A non-zero server code is thrown as an {@link ASRException} carrying
 * that code. Auth failures arrive with HTTP 200 (body code 4002) — the body
 * code, not the HTTP status, is authoritative (handled here).
 */
public class SentenceRecognizer {
    /** Production HTTPS endpoint for the v3 offline interfaces. */
    public static final String SENTENCE_ENDPOINT = "https://asr.cloud-rtc.com";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OFFLINE_PATH = "/v3/transcribe";
    private static final int MAX_AUDIO_SIZE = 3 * 1024 * 1024;

    private final Credential credential;
    private String endpoint = "";
    private Duration timeout = Duration.ofSeconds(30);

    public SentenceRecognizer(Credential credential) {
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

    /** Sends a sentence recognition request and returns the result. */
    public TranscribeResponse recognize(TranscribeRequest req) throws ASRException {
        if (req == null) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "request is null");
        }
        req.validate();

        String requestId = Wire.newRequestId();
        Map<String, Object> body;
        try {
            body = Wire.offlineEnvelope(credential, requestId, req.toWire());
        } catch (ASRException e) {
            throw e;
        }

        JsonNode data = post(OFFLINE_PATH, body);
        if (data.path("code").asLong(0) != 0) {
            throw Wire.serverError(data);
        }
        try {
            return MAPPER.treeToValue(data, TranscribeResponse.class);
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "unmarshal response failed: " + e.getMessage(), e);
        }
    }

    /** Recognizes local audio data (auto base64). Max 3MB / 60s. */
    public TranscribeResponse recognizeData(byte[] data, String voiceFormat,
            String engineModelType) throws ASRException {
        if (data == null || data.length == 0) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data is empty");
        }
        if (data.length > MAX_AUDIO_SIZE) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data exceeds 3MB limit");
        }
        TranscribeRequest req = new TranscribeRequest();
        req.engineModelType = engineModelType;
        req.sourceType = Wire.SOURCE_TYPE_DATA;
        req.voiceFormat = voiceFormat;
        req.data = java.util.Base64.getEncoder().encodeToString(data);
        req.dataLen = data.length;
        return recognize(req);
    }

    /**
     * Recognizes local audio data with a pre-configured request (the request
     * is mutated in place).
     */
    public TranscribeResponse recognizeDataWithOptions(byte[] rawData, TranscribeRequest req)
            throws ASRException {
        if (req == null) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "request is null");
        }
        if (rawData == null || rawData.length == 0) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data is empty");
        }
        if (rawData.length > MAX_AUDIO_SIZE) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio data exceeds 3MB limit");
        }
        req.sourceType = Wire.SOURCE_TYPE_DATA;
        req.data = java.util.Base64.getEncoder().encodeToString(rawData);
        req.dataLen = rawData.length;
        return recognize(req);
    }

    /** Recognizes audio from a URL. */
    public TranscribeResponse recognizeURL(String audioUrl, String voiceFormat,
            String engineModelType) throws ASRException {
        if (audioUrl == null || audioUrl.isEmpty()) {
            throw new ASRException(ErrorCodes.INVALID_PARAM, "audio URL is empty");
        }
        TranscribeRequest req = new TranscribeRequest();
        req.engineModelType = engineModelType;
        req.sourceType = Wire.SOURCE_TYPE_URL;
        req.voiceFormat = voiceFormat;
        req.url = audioUrl;
        return recognize(req);
    }

    private JsonNode post(String path, Object body) throws ASRException {
        String url;
        try {
            url = Credential.resolveHTTPEndpoint(endpoint, credential.getSite()) + path;
        } catch (ASRException e) {
            throw e;
        }
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
