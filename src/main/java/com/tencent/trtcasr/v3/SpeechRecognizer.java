package com.tencent.trtcasr.v3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.asr.SpeechRecognitionListener;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SdkInfo;
import com.tencent.trtcasr.common.UserSig;

/**
 * The main client for realtime speech recognition over the v3 protocol
 * (WebSocket /asr/v3).
 *
 * <p>Protocol recap:
 * <ol>
 * <li>Dial {@code wss://{host}/asr/v3?voice_id=<id>} — the URL carries only
 * voice_id.</li>
 * <li>Within 3s of the handshake, send one TEXT frame:
 * {@code {"type":"start","auth":{...},"params":{...}}} (≤64KB).</li>
 * <li>The server acks {@code {"code":0,...}} or fails with a structured
 * error frame followed by a normal close.</li>
 * <li>Stream audio as binary frames (≤256KB each), then send
 * {@code {"type":"end"}}.</li>
 * <li>Downlink result frames are identical to v2.</li>
 * </ol>
 *
 * <p>{@link #start()} waits synchronously for the acknowledgement, so
 * authentication (4002), gray-switch (4001) and scheduling (5000) errors are
 * raised directly from {@code start()} instead of only via
 * {@link SpeechRecognitionListener#onFail}.
 *
 * <p>Lifecycle and concurrency mirror the v2 client: single-use instances,
 * options configured before {@code start()}, callbacks delivered on an
 * SDK-owned thread with panic shielding, and re-entrant {@code stop()}
 * support. The downlink frame shape is identical to v2, so this client
 * reuses the v2 {@link SpeechRecognitionResponse} and
 * {@link SpeechRecognitionListener} types.
 */
public class SpeechRecognizer {
    /** Production WebSocket endpoint for the TRTC-ASR service (v3). */
    public static final String ENDPOINT = "wss://asr.cloud-rtc.com";

    // Recognizer states.
    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_STOPPING = 3;
    private static final int STATE_STOPPED = 4;

    private static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MIN_WRITE_TIMEOUT = Duration.ofMillis(50);
    private static final Duration MAX_WRITE_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MIN_STOP_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_STOP_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    /** How long start() waits for the server's start-frame acknowledgement. */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Marks threads currently executing a listener callback. */
    private static final ThreadLocal<Boolean> IN_CALLBACK =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Credential credential;
    private final SpeechRecognitionListener listener;

    // Configuration. Set via setters before start().
    private String endpoint = "";
    private final String engineModelType;
    private int voiceFormat = 1; // PCM
    private int needVad = 1;
    private int convertNumMode = 1;
    private String hotwordId = "";
    private String hotwordList = "";
    private int filterDirty;
    private int filterModal;
    private int filterPunc;
    private Integer filterEmptyResult;
    private int wordInfo;
    private int wordWithSpace;
    private int vadSilenceTime;
    private Integer vadLevel;
    private Double noiseThreshold;
    private int maxSpeakTime;
    private int inputSampleRate;
    private int speakerDiarization;
    private int speakerNumber;
    private List<SpeakerRole> speakerRoles = new ArrayList<>();
    private List<String> voiceprintIds = new ArrayList<>();
    private String voiceId = "";
    private String language = "";
    private Context context;

    private volatile Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
    private volatile Duration stopTimeout = DEFAULT_STOP_TIMEOUT;

    // Runtime state.
    private final AtomicInteger state = new AtomicInteger(STATE_IDLE);
    private volatile WebSocket webSocket;
    /** Serializes writes (audio frames and the end signal). */
    private final Object writeLock = new Object();
    private final CountDownLatch doneLatch = new CountDownLatch(1);
    private final Object doneMonitor = new Object();
    private final AtomicBoolean terminalReceived = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile ExecutorService callbackExecutor;

    // Start-frame ack handshake state.
    private final CountDownLatch ackLatch = new CountDownLatch(1);
    private final AtomicReference<String> ackFrame = new AtomicReference<>();
    private volatile boolean acked;

    public SpeechRecognizer(Credential credential, String engineModelType,
            SpeechRecognitionListener listener) {
        this.credential = credential;
        this.engineModelType = engineModelType;
        this.listener = listener;
    }

    /** Overrides the WebSocket endpoint (for testing against a mock server). */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Sets the audio encoding format. 1: PCM (default). */
    public void setVoiceFormat(int voiceFormat) {
        this.voiceFormat = voiceFormat;
    }

    /**
     * Sets whether to enable VAD. Unlike the v2 transport, v3 honors an
     * explicit 0 (it is sent on the wire, not dropped).
     */
    public void setNeedVad(int needVad) {
        this.needVad = needVad;
    }

    /**
     * Sets the number conversion mode. Unlike the v2 transport, v3 honors an
     * explicit 0.
     */
    public void setConvertNumMode(int mode) {
        this.convertNumMode = mode;
    }

    /** Sets the hotword list ID for biasing recognition. */
    public void setHotwordId(String id) {
        this.hotwordId = id;
    }

    /** Sets a temporary inline hotword list: {@code "word1|weight1,..."}. */
    public void setHotwordList(String list) {
        this.hotwordList = list;
    }

    /** Sets the profanity filter mode. 0: off (default), 1: filter, 2: replace with *. */
    public void setFilterDirty(int mode) {
        this.filterDirty = mode;
    }

    /** Sets the modal particle filter mode. 0: off (default), 1: partial, 2: strict. */
    public void setFilterModal(int mode) {
        this.filterModal = mode;
    }

    /** Sets the sentence-ending punctuation filter mode. 0: off (default), 1: filter. */
    public void setFilterPunc(int mode) {
        this.filterPunc = mode;
    }

    /** Sets whether empty recognition results are delivered. 0: deliver, 1: skip. */
    public void setFilterEmptyResult(int mode) {
        this.filterEmptyResult = mode;
    }

    /** Sets word-level timing: 0 no, 1 yes, 2 with punctuation, 100 caption. */
    public void setWordInfo(int mode) {
        this.wordInfo = mode;
    }

    /** Sets whether English words are joined with spaces: 0 no (default), 1 yes. */
    public void setWordWithSpace(int mode) {
        this.wordWithSpace = mode;
    }

    /** Sets the silence detection threshold (ms). Range: 240-2000 (needvad=1). */
    public void setVadSilenceTime(int ms) {
        this.vadSilenceTime = ms;
    }

    /** Selects the VAD profile: 0 = high recall, 1 = far-field filtering. */
    public void setVadLevel(int level) {
        this.vadLevel = level;
    }

    /** Fine-tunes VAD noise suppression. Valid range: [0, 4]. */
    public void setNoiseThreshold(double threshold) {
        this.noiseThreshold = threshold;
    }

    /** Sets the maximum speech time (ms). Range: 5000-90000, default: 60000. */
    public void setMaxSpeakTime(int ms) {
        this.maxSpeakTime = ms;
    }

    /** Declares the sample rate of the incoming PCM audio. Only 8000 supported. */
    public void setInputSampleRate(int rate) {
        this.inputSampleRate = rate;
    }

    /** Enables realtime speaker diarization: 0 off, 1 cluster, 3 voiceprint. */
    public void setSpeakerDiarization(int mode) {
        this.speakerDiarization = mode;
    }

    /** Hints the expected number of speakers. 0 = auto detection (default). */
    public void setSpeakerNumber(int n) {
        this.speakerNumber = n;
    }

    /** Registers temporary voiceprints (v3 snake_case SpeakerRole). */
    public void setSpeakerRoles(List<SpeakerRole> roles) {
        this.speakerRoles = roles == null ? new ArrayList<>() : new ArrayList<>(roles);
    }

    /** Registers previously enrolled voiceprints by ID; only voiceprint mode. */
    public void setVoiceprintIds(List<String> ids) {
        this.voiceprintIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    /** Sets a custom voice ID. A UUID is generated when left empty. */
    public void setVoiceId(String id) {
        this.voiceId = id;
    }

    /** Sets the language hint for the bigmodel engine (e.g. "zh", "en"). */
    public void setLanguage(String lang) {
        this.language = lang == null ? "" : lang;
    }

    /** Sets the recognition context (background text, terms, domain KVs). */
    public void setContext(Context context) {
        this.context = context;
    }

    /** Sets the write timeout, clamped to [50ms, 30s]. */
    public void setWriteTimeout(Duration timeout) {
        this.writeTimeout = clampTimeout(timeout, MIN_WRITE_TIMEOUT, MAX_WRITE_TIMEOUT,
                DEFAULT_WRITE_TIMEOUT);
    }

    /** Sets the stop timeout, clamped to [1s, 60s]. */
    public void setStopTimeout(Duration timeout) {
        this.stopTimeout = clampTimeout(timeout, MIN_STOP_TIMEOUT, MAX_STOP_TIMEOUT,
                DEFAULT_STOP_TIMEOUT);
    }

    /**
     * Connects to /asr/v3, sends the start frame and waits for the server
     * ack. Auth (4002), gray-switch (4001) and scheduling (5000) errors are
     * thrown here synchronously.
     */
    public void start() throws ASRException {
        if (!state.compareAndSet(STATE_IDLE, STATE_STARTING)) {
            throw new ASRException(ErrorCodes.ALREADY_STARTED, "recognizer already started");
        }
        try {
            validateOptions();
            connect();
        } catch (ASRException e) {
            state.set(STATE_IDLE);
            throw e;
        } catch (Exception e) {
            state.set(STATE_IDLE);
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "websocket connect failed: " + e.getMessage(), e);
        }
        state.set(STATE_RUNNING);

        SpeechRecognitionResponse startResp = SpeechRecognitionResponse.sessionStart(voiceId);
        dispatchOnCallbackThread(() -> {
            try {
                listener.onRecognitionStart(startResp);
            } catch (Throwable t) {
                handleCallbackThrowable(t);
            }
        });

        // Begin pulling downlink frames after the ack.
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.request(FRAME_CREDIT);
        }
    }

    /**
     * Checks the options that have a documented server-side range, mirroring
     * the proxy's v3 online validator so an invalid value fails locally
     * instead of coming back as a remote 4001.
     */
    private void validateOptions() throws ASRException {
        if (voiceId.length() > Wire.MAX_VOICE_ID_LEN) {
            throw ASRException.invalidParam("VoiceID length must not exceed %d",
                    Wire.MAX_VOICE_ID_LEN);
        }
        Wire.validateSpeakerDiarization(speakerDiarization, speakerNumber,
                speakerRoles, voiceprintIds);
        Wire.validateVadTuning(vadLevel, noiseThreshold);
        if (maxSpeakTime != 0
                && (maxSpeakTime < Wire.MIN_MAX_SPEAK_TIME || maxSpeakTime > Wire.MAX_MAX_SPEAK_TIME)) {
            throw ASRException.invalidParam("MaxSpeakTime must be between %d and %d ms, got %d",
                    Wire.MIN_MAX_SPEAK_TIME, Wire.MAX_MAX_SPEAK_TIME, maxSpeakTime);
        }
        // vadSilenceTime==0 means "not set"; the range check only applies to
        // an explicitly set value with VAD enabled, matching the server rule.
        if (vadSilenceTime != 0 && needVad == 1
                && (vadSilenceTime < Wire.MIN_VAD_SILENCE_TIME_MS
                        || vadSilenceTime > Wire.MAX_VAD_SILENCE_TIME_MS)) {
            throw ASRException.invalidParam(
                    "VadSilenceTime must be between %d and %d ms (needvad=1), got %d",
                    Wire.MIN_VAD_SILENCE_TIME_MS, Wire.MAX_VAD_SILENCE_TIME_MS, vadSilenceTime);
        }
        Wire.validateEnumOption("NeedVad", needVad, 0, 1);
        Wire.validateEnumOption("ConvertNumMode", convertNumMode, 0, 1, 3);
        Wire.validateEnumOption("FilterDirty", filterDirty, 0, 1, 2);
        Wire.validateEnumOption("FilterModal", filterModal, 0, 1, 2);
        Wire.validateEnumOption("FilterPunc", filterPunc, 0, 1);
        Wire.validateEnumOption("WordInfo", wordInfo, 0, 1, 2, 100);
        Wire.validateEnumOption("WordWithSpace", wordWithSpace, 0, 1);
        Wire.validateEnumOption("VoiceFormat", voiceFormat, Wire.VALID_VOICE_FORMATS);
        if (filterEmptyResult != null) {
            Wire.validateEnumOption("FilterEmptyResult", filterEmptyResult, 0, 1);
        }
        // 8000 is the only supported override; 0 means "use the engine rate".
        Wire.validateEnumOption("InputSampleRate", inputSampleRate, 0, 8000);
    }

    /**
     * Assembles the start frame as a JSON string — it must be sent as a
     * TEXT frame (a binary first frame is rejected by the server with
     * 4010).
     */
    private String buildStartFrame(String userSig, String resolvedVoiceId) throws ASRException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("voice_id", resolvedVoiceId);
        params.put("engine_model_type", engineModelType);
        if (!language.isEmpty()) {
            params.put("language", language);
        }
        params.put("voice_format", voiceFormat);
        // SDK-managed defaults are always sent — including an explicit 0,
        // which v3 honors (the v2 query transport dropped it).
        params.put("needvad", needVad);
        params.put("convert_num_mode", convertNumMode);
        // SDK telemetry: the gateway replays the start frame byte-for-byte to
        // the worker, which ignores unknown keys, so it survives in
        // server-side dumps without disturbing the protocol.
        params.put("sdk_info", SdkInfo.reportParams());
        if (!hotwordId.isEmpty()) {
            params.put("hotword_id", hotwordId);
        }
        if (!hotwordList.isEmpty()) {
            params.put("hotword_list", hotwordList);
        }
        if (filterDirty != 0) {
            params.put("filter_dirty", filterDirty);
        }
        if (filterModal != 0) {
            params.put("filter_modal", filterModal);
        }
        if (filterPunc != 0) {
            params.put("filter_punc", filterPunc);
        }
        if (filterEmptyResult != null) {
            params.put("filter_empty_result", filterEmptyResult);
        }
        if (wordInfo != 0) {
            params.put("word_info", wordInfo);
        }
        if (wordWithSpace != 0) {
            params.put("word_with_space", wordWithSpace);
        }
        if (vadSilenceTime != 0) {
            params.put("vad_silence_time", vadSilenceTime);
        }
        if (vadLevel != null) {
            params.put("vad_level", vadLevel);
        }
        if (noiseThreshold != null) {
            params.put("noise_threshold", noiseThreshold);
        }
        if (maxSpeakTime != 0) {
            params.put("max_speak_time", maxSpeakTime);
        }
        if (inputSampleRate != 0) {
            params.put("input_sample_rate", inputSampleRate);
        }
        if (speakerDiarization != 0) {
            params.put("speaker_diarization", speakerDiarization);
            if (speakerNumber != 0) {
                params.put("speaker_number", speakerNumber);
            }
        }
        if (speakerDiarization == Wire.SPEAKER_DIARIZATION_VOICEPRINT) {
            if (!speakerRoles.isEmpty()) {
                params.put("speaker_roles", speakerRoles);
            }
            if (!voiceprintIds.isEmpty()) {
                params.put("voiceprint_ids", voiceprintIds);
            }
        }
        if (context != null) {
            params.put("context", context);
        }

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("sdkappid", String.valueOf(credential.getSdkAppId()));
        auth.put("usersig", userSig);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "start");
        frame.put("auth", auth);
        frame.put("params", params);
        try {
            String data = MAPPER.writeValueAsString(frame);
            if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Wire.START_FRAME_MAX_BYTES) {
                throw ASRException.invalidParam("start frame exceeds %d bytes",
                        Wire.START_FRAME_MAX_BYTES);
            }
            return data;
        } catch (ASRException e) {
            throw e;
        } catch (Exception e) {
            throw new ASRException(ErrorCodes.READ_FAILED,
                    "marshal start frame failed: " + e.getMessage(), e);
        }
    }

    private void connect() throws ASRException {
        if (voiceId.isEmpty()) {
            voiceId = UUID.randomUUID().toString();
        }

        // Resolve UserSig locally without mutating the shared credential.
        // The v3 signature identifier is the voice_id.
        String userSig = credential.getUserSig();
        if (userSig.isEmpty()) {
            try {
                userSig = UserSig.genUserSig(credential.getSdkAppId(),
                        credential.getSecretKey(), voiceId, 86400);
            } catch (ASRException e) {
                throw new ASRException(ErrorCodes.AUTH_FAILED,
                        "generate user sig failed: " + e.getRawMessage(), e);
            }
        }

        String frame = buildStartFrame(userSig, voiceId);

        // The URL carries only voice_id; auth and params travel in the start
        // frame, so the handshake stays header-free.
        String base = Credential.resolveWSEndpoint(endpoint, credential.getSite());
        String wsUrl = base + "/asr/v3?voice_id=" + java.net.URLEncoder
                .encode(voiceId, java.nio.charset.StandardCharsets.UTF_8);

        callbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "trtc-asr-v3-callback");
                t.setDaemon(true);
                return t;
            }
        });

        WebSocket ws;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .build();
            ws = client.newWebSocketBuilder()
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .buildAsync(URI.create(wsUrl), new WsListener())
                    .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            webSocket = ws;
        } catch (Exception e) {
            shutdownCallbackExecutor();
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "websocket dial failed: " + e.getMessage(), e);
        }

        // Send the start frame immediately (the server enforces a 3s
        // deadline). Sending does not consume receive permits.
        try {
            ws.sendText(frame, true).get(writeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            close();
            throw new ASRException(ErrorCodes.WRITE_FAILED,
                    "send start frame failed: " + e.getMessage(), e);
        }

        // Pull the first frame (the ack) and wait for it.
        ws.request(FRAME_CREDIT);
        try {
            if (!ackLatch.await(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                close();
                throw new ASRException(ErrorCodes.READ_FAILED,
                        "read start ack failed: ack timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new ASRException(ErrorCodes.READ_FAILED, "read start ack interrupted", e);
        }

        String ack = ackFrame.get();
        JsonNode node;
        try {
            node = MAPPER.readTree(ack);
        } catch (Exception e) {
            close();
            throw new ASRException(ErrorCodes.SERVER_ERROR,
                    "invalid start ack: " + e.getMessage(), e);
        }
        long code = node.path("code").asLong(0);
        if (code != 0) {
            close();
            throw new ASRException((int) code, node.path("message").asText(""));
        }
        // The ack frame never carries a result per the protocol; a defensive
        // ack-with-result is consumed here.
    }

    /** Sends one audio frame (binary, ≤256KB) to the ASR service. */
    public void write(byte[] data) throws ASRException {
        if (state.get() != STATE_RUNNING) {
            throw new ASRException(ErrorCodes.NOT_STARTED, "recognizer not running");
        }
        if (data.length > Wire.STREAM_FRAME_MAX_BYTES) {
            throw ASRException.invalidParam("audio frame exceeds %d bytes",
                    Wire.STREAM_FRAME_MAX_BYTES);
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new ASRException(ErrorCodes.NOT_STARTED, "connection not established");
        }

        synchronized (writeLock) {
            if (state.get() != STATE_RUNNING) {
                throw new ASRException(ErrorCodes.NOT_STARTED, "recognizer not running");
            }
            try {
                ws.sendBinary(ByteBuffer.wrap(data), true)
                        .get(writeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new ASRException(ErrorCodes.WRITE_FAILED,
                        "write audio data failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Gracefully stops the recognition session: sends the end signal and
     * waits for the server's final response (up to stopTimeout). Safe to
     * call from a recognition callback.
     */
    public void stop() throws ASRException {
        if (!state.compareAndSet(STATE_RUNNING, STATE_STOPPING)) {
            throw new ASRException(ErrorCodes.NOT_STARTED, "recognizer not running");
        }

        WebSocket ws = webSocket;
        if (ws == null) {
            state.set(STATE_STOPPED);
            throw new ASRException(ErrorCodes.NOT_STARTED, "connection not established");
        }

        Exception sendFailure = null;
        synchronized (writeLock) {
            if (state.get() != STATE_STOPPED) {
                try {
                    ws.sendText("{\"type\":\"end\"}", true)
                            .get(writeTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    sendFailure = e;
                }
            }
        }

        if (sendFailure != null) {
            if (state.get() == STATE_STOPPED) {
                waitForCallbacksOrAbort();
                return;
            }
            close();
            state.set(STATE_STOPPED);
            throw new ASRException(ErrorCodes.WRITE_FAILED,
                    "send end signal failed: " + sendFailure.getMessage(), sendFailure);
        }

        if (IN_CALLBACK.get()) {
            Thread watchdog = new Thread(this::waitForCallbacksOrAbort, "trtc-asr-v3-stop-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            return;
        }

        waitForCallbacksOrAbort();
        state.set(STATE_STOPPED);
    }

    private void waitForCallbacksOrAbort() {
        long deadlineNanos = System.nanoTime() + stopTimeout.toNanos();
        try {
            synchronized (doneMonitor) {
                while (true) {
                    if (doneLatch.getCount() == 0) {
                        return;
                    }
                    if (terminalReceived.get()) {
                        break;
                    }
                    long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainingMs <= 0) {
                        close();
                        doneLatch.await(300, TimeUnit.MILLISECONDS);
                        return;
                    }
                    doneMonitor.wait(remainingMs);
                }
            }
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finish() {
        if (finished.compareAndSet(false, true)) {
            state.set(STATE_STOPPED);
            close();
        }
    }

    private void close() {
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
    }

    private void shutdownCallbackExecutor() {
        ExecutorService exec = callbackExecutor;
        if (exec != null) {
            exec.shutdown();
        }
    }

    private void dispatchOnCallbackThread(Runnable task) {
        ExecutorService exec = callbackExecutor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        try {
            exec.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor is shutting down; the session is over.
        }
    }

    private void handleCallbackThrowable(Throwable t) {
        finish();
        safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                "recovered from panic in listener callback: " + t));
        signalDone();
    }

    private void safeOnFail(SpeechRecognitionResponse resp, ASRException err) {
        try {
            IN_CALLBACK.set(Boolean.TRUE);
            listener.onFail(resp, err);
        } catch (Throwable ignored) {
            // A faulty onFail must never crash the host process.
        } finally {
            IN_CALLBACK.set(Boolean.FALSE);
        }
    }

    private void safeComplete(SpeechRecognitionResponse resp) {
        try {
            IN_CALLBACK.set(Boolean.TRUE);
            listener.onRecognitionComplete(resp);
        } catch (Throwable ignored) {
            // Same panic-shielding guarantee as safeOnFail.
        } finally {
            IN_CALLBACK.set(Boolean.FALSE);
        }
    }

    private void signalDone() {
        doneLatch.countDown();
        synchronized (doneMonitor) {
            doneMonitor.notifyAll();
        }
        shutdownCallbackExecutor();
    }

    private void markTerminalReceived() {
        terminalReceived.set(true);
        synchronized (doneMonitor) {
            doneMonitor.notifyAll();
        }
    }

    /**
     * Server frames are pulled without a manual credit window. A close frame
     * is delivered by the JDK without needing a request, so a one-frame
     * credit window lets a close overtake a terminal text frame that has
     * already arrived: onClose then runs first, shuts the callback executor
     * down, and the terminal frame is never delivered — the caller sees
     * neither onRecognitionComplete nor onFail. Requesting an unbounded
     * number of frames keeps delivery in arrival order.
     */
    private static final long FRAME_CREDIT = Long.MAX_VALUE;

    private static Duration clampTimeout(Duration v, Duration min, Duration max, Duration dflt) {
        if (v == null || v.isZero() || v.isNegative()) {
            return dflt;
        }
        if (v.compareTo(min) < 0) {
            return min;
        }
        if (v.compareTo(max) > 0) {
            return max;
        }
        return v;
    }

    /** WebSocket.Listener bridging incoming frames onto the callback thread. */
    private class WsListener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            final String text;
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (!last) {
                    ws.request(FRAME_CREDIT);
                    return null;
                }
                text = textBuffer.toString();
                textBuffer.setLength(0);
            }
            if (!acked) {
                // The first frame is the start-frame acknowledgement; hand it
                // to start() directly (do not pull further frames yet).
                ackFrame.set(text);
                acked = true;
                ackLatch.countDown();
                return null;
            }
            dispatchOnCallbackThread(() -> handleText(text));
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            dispatchOnCallbackThread(() -> safeOnFail(null, new ASRException(
                    ErrorCodes.READ_FAILED, "unmarshal response failed: unexpected binary frame")));
            ws.request(FRAME_CREDIT);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            ws.request(FRAME_CREDIT);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            ws.request(FRAME_CREDIT);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            dispatchOnCallbackThread(() -> {
                if (!acked) {
                    // Failed before the ack: start() owns the error path.
                    ackFrame.set("");
                    ackLatch.countDown();
                    signalDone();
                    return;
                }
                if (state.get() >= STATE_STOPPING) {
                    // We asked to stop, so a close is expected — but only
                    // after the terminal response was delivered. Without this
                    // check a close that overtakes the terminal frame ends the
                    // session with neither onRecognitionComplete nor onFail,
                    // making a truncated session look like a silent success.
                    if (!terminalReceived.get()) {
                        safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                                "connection closed by server before the final response: "
                                        + statusCode + " " + reason));
                    }
                    signalDone();
                    return;
                }
                finish();
                safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                        "connection closed by server: " + statusCode + " " + reason));
                signalDone();
            });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            dispatchOnCallbackThread(() -> {
                if (!acked) {
                    ackFrame.set("");
                    ackLatch.countDown();
                    signalDone();
                    return;
                }
                if (state.get() >= STATE_STOPPING) {
                    if (!terminalReceived.get()) {
                        safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                                "read message failed before the final response: "
                                        + error.getMessage(), error));
                    }
                    signalDone();
                    return;
                }
                finish();
                safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                        "read message failed: " + error.getMessage(), error));
                signalDone();
            });
        }

        private void handleText(String text) {
            try {
                handleTextInner(text);
            } catch (Throwable t) {
                handleCallbackThrowable(t);
                return;
            }
            WebSocket ws = webSocket;
            if (ws != null && doneLatch.getCount() != 0) {
                ws.request(FRAME_CREDIT);
            }
        }

        private void handleTextInner(String text) throws Exception {
            JsonNode probe;
            try {
                probe = MAPPER.readTree(text);
            } catch (Exception e) {
                // Non-terminal: the session continues.
                safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                        "unmarshal response failed: " + e.getMessage()));
                return;
            }

            SpeechRecognitionResponse resp;
            try {
                resp = MAPPER.treeToValue(probe, SpeechRecognitionResponse.class);
            } catch (Exception e) {
                safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                        "unmarshal response failed: " + e.getMessage()));
                return;
            }

            if (resp.getCode() != 0) {
                finish();
                markTerminalReceived();
                safeOnFail(resp, new ASRException(resp.getCode(), resp.getMessage()));
                signalDone();
                return;
            }

            if (resp.getFinalFlag() == 1) {
                finish();
                markTerminalReceived();
                dispatchEvent(resp);
                safeComplete(resp);
                signalDone();
                return;
            }

            if (!probe.has("result") || probe.get("result").isNull()) {
                return;
            }

            dispatchEvent(resp);
        }

        private void dispatchEvent(SpeechRecognitionResponse resp) {
            if (resp.getFinalFlag() == 1 && resp.getResult().getSliceType() != 2) {
                return;
            }
            try {
                IN_CALLBACK.set(Boolean.TRUE);
                switch (resp.getResult().getSliceType()) {
                    case 0:
                        listener.onSentenceBegin(resp);
                        break;
                    case 1:
                        listener.onRecognitionResultChange(resp);
                        break;
                    case 2:
                        listener.onSentenceEnd(resp);
                        break;
                    default:
                        break;
                }
            } finally {
                IN_CALLBACK.set(Boolean.FALSE);
            }
        }
    }
}
