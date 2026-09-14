package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.asr.FileRecognizer;
import com.tencent.trtcasr.asr.SentenceRecognizer;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;

/**
 * Async file recognition example (long audio, up to 12h).
 *
 * <p>Credentials come from environment variables: TRTC_ASR_APP_ID,
 * TRTC_ASR_SDK_APP_ID, TRTC_ASR_SECRET_KEY.
 *
 * <p>Usage: FileAsrExample &lt;audio.pcm&gt; [engine] [lang] |
 * FileAsrExample -u &lt;https-url&gt; [engine] [lang]
 */
public class FileAsrExample {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FileAsrExample <audio-file> <engine> [lang]"
                    + " | -u <url> <engine> [lang]");
            System.exit(1);
        }
        final boolean urlMode = args[0].equals("-u");
        // engine/lang default to the recommended bigmodel + zh pairing; pass an
        // empty lang to fall back to server-side language detection.
        final int engineIdx = urlMode ? 2 : 1;
        final int langIdx = engineIdx + 1;
        if (args.length <= engineIdx) {
            System.err.println("error: engine is required (engine model type, e.g. bigmodel)");
            System.exit(2);
        }
        String engine = args[engineIdx];
        String lang = args.length > langIdx ? args[langIdx] : "";
        // The bigmodel engine is best used with an explicit language; every
        // other engine falls back to server-side detection unless lang is given.
        if (lang.isEmpty() && "bigmodel".equals(engine)) {
            lang = "zh";
        }

        Credential credential = new Credential(
                Long.parseLong(env("TRTC_ASR_APP_ID")),
                Long.parseLong(env("TRTC_ASR_SDK_APP_ID")),
                env("TRTC_ASR_SECRET_KEY"));
        FileRecognizer recognizer = new FileRecognizer(credential);

        try {
            FileRecognizer.CreateRecTaskRequest req = new FileRecognizer.CreateRecTaskRequest();
            req.setEngineModelType(engine);
            req.setChannelNum(1);
            req.setResTextFormat(1);
            if (!lang.isEmpty()) {
                req.setLanguage(lang);
            }
            String taskId;
            if (urlMode) {
                if (args.length < 2) {
                    System.err.println("missing url after -u");
                    System.exit(1);
                }
                req.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
                req.setUrl(args[1]);
                taskId = recognizer.createTask(req);
            } else {
                byte[] data = Files.readAllBytes(Path.of(args[0]));
                taskId = recognizer.createTaskFromDataWithOptions(data, req);
            }
            System.out.println("task submitted: " + taskId);

            var status = recognizer.waitForResult(taskId);
            System.out.println("result: " + status.getResult());
            System.out.println("audio duration: " + status.getAudioDuration() + " s");
            for (var d : status.getResultDetail()) {
                String speaker;
                if (!d.getSpeakerRoleName().isEmpty()) {
                    speaker = d.getSpeakerRoleName();
                } else if (d.getChannelId() > 0) {
                    speaker = "ch" + d.getChannelId();
                } else {
                    speaker = "spk" + d.getSpeakerId();
                }
                System.out.println("  [" + speaker + "] (" + d.getStartMs() + "-"
                        + d.getEndMs() + "ms) " + d.getFinalSentence());
            }
        } catch (ASRException e) {
            System.err.println("recognition failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null) {
            System.err.println("missing env var: " + name);
            System.exit(1);
        }
        return v;
    }
}
