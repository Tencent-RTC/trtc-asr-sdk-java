package com.tencent.trtcasr.asr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SignatureParams;
import com.tencent.trtcasr.common.SpeakerRole;
import com.tencent.trtcasr.common.UserSig;

/**
 * The main client for realtime speech recognition (WebSocket).
 *
 * <p>Lifecycle and concurrency (mirrors the Go SDK):
 * <ul>
 * <li>A {@code SpeechRecognizer} is single-use: once it reaches the stopped
 * state (via {@link #stop()} or a terminal error) it cannot be restarted.
 * Create a new instance to reconnect.</li>
 * <li>All {@code setXxx} options must be configured before {@link #start()}
 * and must not be called concurrently with {@code start()}.</li>
 * <li>After {@code start()} returns, {@link #write(byte[])} and
 * {@link #stop()} may be called from threads other than the one that called
 * {@code start()}. Recognition callbacks are delivered sequentially on an
 * SDK-owned thread.</li>
 * <li>{@code stop()} is safe to call from a recognition callback: re-entry is
 * detected via a thread-local flag set around dispatch. For terminal
 * callbacks the recognizer has already advanced to stopped, so {@code stop()}
 * returns immediately with not-running; for non-terminal callbacks it sends
 * the end signal and returns without waiting (waiting would self-block).</li>
 * </ul>
 */
public class SpeechRecognizer {
    /** Production WebSocket endpoint for the TRTC-ASR service. */
    public static final String ENDPOINT = "wss://asr.cloud-rtc.com";

    // Recognizer states.
    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_STOPPING = 3;
    private static final int STATE_STOPPED = 4;

    // Write-timeout bounds: a single write holds the writer for at most
    // writeTimeout, so stop's worst-case wait for the writer stays bounded.
    private static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MIN_WRITE_TIMEOUT = Duration.ofMillis(50);
    private static final Duration MAX_WRITE_TIMEOUT = Duration.ofSeconds(30);

    // Stop-timeout bounds: caps how long stop waits for the server's final
    // response before forcing the connection closed.
    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MIN_STOP_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_STOP_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

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
    private String customizationId = "";
    private String replaceTextId = "";
    private int filterDirty;
    private int filterModal;
    private int filterPunc;
    private Integer filterEmptyResult;
    private int wordInfo;
    private int vadSilenceTime;
    private Integer vadLevel;
    private Double noiseThreshold;
    private int maxSpeakTime;
    private int inputSampleRate;
    private int speakerDiarization;
    private int speakerNumber;
    private int enableSpeakerContext;
    private String speakerContextId = "";
    /** 首响应携带的断点续传握手结果（v2 的 onRecognitionStart 早于首响应）。 */
    private volatile SpeakerContinue speakerContinue;
    private List<SpeakerRole> speakerRoles = new ArrayList<>();
    private List<String> voiceprintIds = new ArrayList<>();
    private String voiceId = "";
    private String language = "";

    private volatile Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
    private volatile Duration stopTimeout = DEFAULT_STOP_TIMEOUT;

    // Runtime state.
    private final AtomicInteger state = new AtomicInteger(STATE_IDLE);
    private volatile WebSocket webSocket;
    /** Serializes writes (audio frames and the end signal). */
    private final Object writeLock = new Object();
    /** Counted down when the callback thread has fully exited. */
    private final CountDownLatch doneLatch = new CountDownLatch(1);
    /**
     * Monitor pairing with {@link #doneLatch}/{@link #terminalReceived}:
     * signalDone/markTerminalReceived notify it so a stop() inside its timed
     * wait wakes up and re-checks both conditions (a bare latch await would
     * keep sleeping until the timeout even after the terminal frame arrived).
     */
    private final Object doneMonitor = new Object();
    /** Set once a terminal response (final=1 or code!=0) has been received. */
    private final AtomicBoolean terminalReceived = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** Accumulates fragmented text frames until the last fragment arrives. */
    private final StringBuilder textBuffer = new StringBuilder();
    /** SDK-owned single thread delivering callbacks in order. */
    private volatile ExecutorService callbackExecutor;

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

    /** Sets whether to enable VAD. 0: disable, 1: enable (default). */
    public void setNeedVad(int needVad) {
        this.needVad = needVad;
    }

    /** Sets the number conversion mode. 0: none, 1: smart (default), 3: math. */
    public void setConvertNumMode(int mode) {
        this.convertNumMode = mode;
    }

    /** Sets the hotword list ID for biasing recognition. */
    public void setHotwordId(String id) {
        this.hotwordId = id;
    }

    /**
     * Sets a temporary inline hotword list, which does not require creating a
     * hotword table on the console.
     *
     * <p>Format: {@code "word1|weight1,word2|weight2"}. Each word is at most
     * 30 bytes and the weight must be 1-11 (11 = super hotword) or 100
     * (homophone replacement).
     */
    public void setHotwordList(String list) {
        this.hotwordList = list;
    }

    /** Sets the custom language model ID. */
    public void setCustomizationId(String id) {
        this.customizationId = id;
    }

    /** Sets the replacement word table ID. */
    public void setReplaceTextId(String id) {
        this.replaceTextId = id;
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

    /**
     * Sets whether empty recognition results are delivered. 0: deliver,
     * 1: skip (server default). Calling this makes the choice explicit on the
     * wire, so passing 0 is honored instead of falling back to the default.
     */
    public void setFilterEmptyResult(int mode) {
        this.filterEmptyResult = mode;
    }

    /**
     * Sets whether to show word-level timing information. 0: no (default),
     * 1: yes, 2: include punctuation timing.
     */
    public void setWordInfo(int mode) {
        this.wordInfo = mode;
    }

    /** Sets the silence detection threshold (ms). Range: 240-2000. */
    public void setVadSilenceTime(int ms) {
        this.vadSilenceTime = ms;
    }

    /**
     * Selects the VAD profile: 0 = high recall, 1 = far-field noise filtering
     * (server default). Calling this makes the choice explicit on the wire.
     */
    public void setVadLevel(int level) {
        this.vadLevel = level;
    }

    /**
     * Fine-tunes VAD noise suppression. Valid range: [0, 4]; larger values
     * suppress more noise at the cost of recall. When set, it overrides the
     * profile selected by {@link #setVadLevel(int)}.
     */
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

    /**
     * Enables realtime speaker diarization:
     * {@link SignatureParams#SPEAKER_DIARIZATION_OFF} (0) disabled (default),
     * {@link SignatureParams#SPEAKER_DIARIZATION_CLUSTER} (1) anonymous
     * clustering, {@link SignatureParams#SPEAKER_DIARIZATION_VOICEPRINT} (3)
     * voiceprint role authentication.
     */
    public void setSpeakerDiarization(int mode) {
        this.speakerDiarization = mode;
    }

    /** Hints the expected number of speakers. 0 = auto detection (default). */
    public void setSpeakerNumber(int n) {
        this.speakerNumber = n;
    }

    /** 说话人分离断点续传：1=同步（首响应回报恢复状态）2=异步（只回 id）。
     *  需与 setSpeakerDiarization(1/3) 同开。注意 v2 的 onRecognitionStart 早于
     *  服务端首响应，握手结果只能经 getSpeakerContinue() 读取。
     */
    public void setEnableSpeakerContext(int mode) {
        this.enableSpeakerContext = mode;
    }

    /** 传回上次会话的 speaker_context_id；过期/非法 ID 服务端按新会话处理。 */
    public void setSpeakerContextId(String id) {
        this.speakerContextId = id == null ? "" : id.trim();
    }

    /** 首响应携带的断点续传握手结果；未开启时为 {@code null}。 */
    public SpeakerContinue getSpeakerContinue() {
        return speakerContinue;
    }

    /** Registers temporary voiceprints; only used with voiceprint mode. */
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

    /** Sets the language hint for the bigmodel engine (e.g. "zh", "en", "auto"). */
    public void setLanguage(String lang) {
        this.language = lang == null ? "" : lang;
    }

    /**
     * Sets the timeout for a single audio write, clamped to [50ms, 30s]; a
     * non-positive value resets to the default (5s). Clamping keeps stop's
     * worst-case exit time predictable.
     */
    public void setWriteTimeout(Duration timeout) {
        this.writeTimeout = clampTimeout(timeout, MIN_WRITE_TIMEOUT, MAX_WRITE_TIMEOUT,
                DEFAULT_WRITE_TIMEOUT);
    }

    /**
     * Sets how long stop waits for the server's final response after sending
     * the end signal, clamped to [1s, 60s]; a non-positive value resets to
     * the default (10s).
     */
    public void setStopTimeout(Duration timeout) {
        this.stopTimeout = clampTimeout(timeout, MIN_STOP_TIMEOUT, MAX_STOP_TIMEOUT,
                DEFAULT_STOP_TIMEOUT);
    }

    /** Initiates the WebSocket connection and begins the recognition session. */
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

        // Fire OnRecognitionStart on the callback thread, before any server
        // event (matching the Go readLoop entry behavior).
        SpeechRecognitionResponse startResp = SpeechRecognitionResponse.sessionStart(voiceId);
        dispatchOnCallbackThread(() -> {
            try {
                listener.onRecognitionStart(startResp);
            } catch (Throwable t) {
                handleCallbackThrowable(t);
            }
        });

        // Only now start pulling server frames: the state is RUNNING and the
        // start callback is queued first on the single callback thread, so
        // event order is guaranteed and a fast final/error frame cannot be
        // processed before onRecognitionStart.
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.request(FRAME_CREDIT);
        }
    }

    private void validateOptions() throws ASRException {
        ParamsValidator.validateSpeakerDiarization(speakerDiarization, speakerNumber,
                speakerRoles, voiceprintIds);
        if (enableSpeakerContext != 0 && enableSpeakerContext != 1
                && enableSpeakerContext != 2) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "EnableSpeakerContext must be 0 (off), 1 (sync) or 2 (async), got "
                            + enableSpeakerContext);
        }
        if (enableSpeakerContext != 0 && speakerDiarization == 0) {
            throw new ASRException(ErrorCodes.INVALID_PARAM,
                    "EnableSpeakerContext requires setSpeakerDiarization(1) or (3)");
        }
        ParamsValidator.validateVadTuning(vadLevel, noiseThreshold);
        if (filterEmptyResult != null) {
            ParamsValidator.validateEnumOption("FilterEmptyResult", filterEmptyResult, 0, 1);
        }
        // 8000 is the only supported override; 0 means "use the engine rate".
        ParamsValidator.validateEnumOption("InputSampleRate", inputSampleRate, 0, 8000);
    }

    private void connect() throws ASRException {
        if (voiceId.isEmpty()) {
            voiceId = UUID.randomUUID().toString();
        }

        // Resolve UserSig locally without mutating the shared credential.
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

        // Authentication identity (sdkappid + usersig) travels in the query
        // string instead of headers, so browser WebSocket clients work
        // without header support.
        SignatureParams p = new SignatureParams(credential.getAppId(), engineModelType, voiceId);
        p.setSdkAppId(credential.getSdkAppId());
        p.setVoiceFormat(voiceFormat);
        p.setNeedVad(needVad);
        p.setConvertNumMode(convertNumMode);
        p.setHotwordId(hotwordId);
        p.setHotwordList(hotwordList);
        p.setCustomizationId(customizationId);
        p.setReplaceTextId(replaceTextId);
        p.setFilterDirty(filterDirty);
        p.setFilterModal(filterModal);
        p.setFilterPunc(filterPunc);
        p.setFilterEmptyResult(filterEmptyResult);
        p.setWordInfo(wordInfo);
        p.setVadSilenceTime(vadSilenceTime);
        p.setVadLevel(vadLevel);
        p.setNoiseThreshold(noiseThreshold);
        p.setMaxSpeakTime(maxSpeakTime);
        p.setInputSampleRate(inputSampleRate);
        p.setSpeakerDiarization(speakerDiarization);
        p.setEnableSpeakerContext(enableSpeakerContext);
        p.setSpeakerContextId(speakerContextId);
        p.setSpeakerNumber(speakerNumber);
        p.setSpeakerRoles(speakerRoles);
        p.setVoiceprintIds(voiceprintIds);
        p.setLanguage(language);

        String query = p.buildQueryStringWithSignature(userSig);
        // URL path uses the Tencent Cloud AppID (not SdkAppID).
        String base = Credential.resolveWSEndpoint(endpoint, credential.getSite());
        String wsUrl = base + "/asr/v2/" + credential.getAppId() + "?" + query;

        callbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "trtc-asr-callback");
                t.setDaemon(true);
                return t;
            }
        });

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .build();
            webSocket = client.newWebSocketBuilder()
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .buildAsync(URI.create(wsUrl), new WsListener())
                    .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            shutdownCallbackExecutor();
            throw new ASRException(ErrorCodes.CONNECT_FAILED,
                    "websocket dial failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sends audio data to the ASR service for recognition. The data should be
     * in the format specified by {@link #setVoiceFormat(int)} (default: PCM).
     */
    public void write(byte[] data) throws ASRException {
        if (state.get() != STATE_RUNNING) {
            throw new ASRException(ErrorCodes.NOT_STARTED, "recognizer not running");
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new ASRException(ErrorCodes.NOT_STARTED, "connection not established");
        }

        synchronized (writeLock) {
            // Re-check under the lock: stop may have transitioned the state
            // and sent the end signal while we waited.
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
     * Gracefully stops the recognition session.
     *
     * <p>Sends the end signal and waits for the server's final response (up
     * to stopTimeout) before forcing the connection closed. Worst-case
     * duration is bounded by writeTimeout plus stopTimeout.
     *
     * <p>Safe to call from a recognition callback.
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
            // If the session finished naturally while we waited for an
            // in-flight writer, skip the end signal entirely.
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

        // If stop is called from within a listener callback (which runs on
        // the callback thread), waiting on done here would self-block. Return
        // after sending end; the watchdog preserves the timeout semantics if
        // the server never sends a terminal response.
        if (IN_CALLBACK.get()) {
            Thread watchdog = new Thread(this::waitForCallbacksOrAbort, "trtc-asr-stop-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            return;
        }

        waitForCallbacksOrAbort();
        state.set(STATE_STOPPED);
    }

    /**
     * Waits for the callback thread to finish (bounded by stopTimeout unless
     * a terminal response already arrived, in which case the terminal
     * callbacks are allowed to complete), then force-closes the connection.
     */
    private void waitForCallbacksOrAbort() {
        long deadlineNanos = System.nanoTime() + stopTimeout.toNanos();
        try {
            synchronized (doneMonitor) {
                while (true) {
                    if (doneLatch.getCount() == 0) {
                        return;
                    }
                    if (terminalReceived.get()) {
                        // Terminal response arrived (possibly after this
                        // waiter entered the timed wait): leave the monitor
                        // and wait without a timeout so the terminal
                        // callbacks can finish — mirrors the Go SDK's
                        // waitForCallbacksOrAbort terminal branch.
                        break;
                    }
                    long remainingMs =
                            (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (remainingMs <= 0) {
                        // Timed out waiting for the server's final response:
                        // force the connection closed so the reader exits.
                        close();
                        // Give the callback thread a brief chance to run its
                        // exit path; never wait indefinitely.
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

    /**
     * Advances the recognizer to the terminal stopped state exactly once and
     * closes the connection. Invoked before terminal callbacks (so a
     * stop/write from inside a callback returns immediately).
     */
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
                // The peer may already be gone; abort is best-effort.
            }
        }
    }

    private void shutdownCallbackExecutor() {
        ExecutorService exec = callbackExecutor;
        if (exec != null) {
            exec.shutdown();
        }
    }

    /** Runs a task on the SDK-owned callback thread. */
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

    /**
     * A listener callback threw. Mirror the Go SDK: finish the lifecycle
     * first (so a re-entrant stop from onFail observes the stopped state),
     * then surface the failure via onFail and close out the session.
     */
    private void handleCallbackThrowable(Throwable t) {
        finish();
        String stack = throwableToString(t);
        safeOnFail(null, new ASRException(ErrorCodes.READ_FAILED,
                "recovered from panic in listener callback: " + t + "\n" + stack));
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

    /** Records terminal arrival and wakes any stop() in its timed wait. */
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

    private static String throwableToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** WebSocket.Listener bridging incoming frames onto the callback thread. */
    private class WsListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            // Deliberately no ws.request(1) here: start() requests the first
            // frame only after the state is RUNNING and onRecognitionStart is
            // enqueued, so a server frame can never be processed before the
            // session-start callback (or flip a finished session back to
            // RUNNING).
        }

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
            dispatchOnCallbackThread(() -> handleText(text));
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            // The server protocol only uses text frames; treat binary as an
            // unmarshal failure (non-terminal), like the Go SDK does.
            dispatchOnCallbackThread(() -> safeOnFail(null, new ASRException(
                    ErrorCodes.READ_FAILED,
                    "unmarshal response failed: unexpected binary frame")));
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
                // A listener callback panicked: the session cannot continue.
                handleCallbackThrowable(t);
                return;
            }
            // Normal return: keep reading.
            WebSocket ws = webSocket;
            if (ws != null && !isDone()) {
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

            if (resp.getSpeakerContinue() != null) {
                // 首响应携带断点续传握手结果；v2 的 onRecognitionStart 在建连后
                // 本地合成（早于本帧），只能经 getSpeakerContinue() 读取。
                speakerContinue = resp.getSpeakerContinue();
            }

            if (resp.getCode() != 0) {
                finish();
                markTerminalReceived();
                safeOnFail(resp, new ASRException(resp.getCode(), resp.getMessage()));
                signalDone();
                return;
            }

            // Check completion before dispatching the terminal response. A
            // final=1 response can still carry slice_type=2, which dispatches
            // onSentenceEnd; finish first so stop/write from that callback
            // observes the stopped state.
            if (resp.getFinalFlag() == 1) {
                finish();
                markTerminalReceived();
                dispatchEvent(resp);
                safeComplete(resp);
                signalDone();
                return;
            }

            // Skip the connection acknowledgement frame: after connect, the
            // server sends an ack that carries no "result" object
            // (e.g. {"code":0,"message":"success","voice_id":"v1"}). Decoding
            // such a frame yields a zero-valued result whose slice_type=0
            // would otherwise be misread as "sentence begin".
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

        private boolean isDone() {
            return doneLatch.getCount() == 0;
        }
    }
}
