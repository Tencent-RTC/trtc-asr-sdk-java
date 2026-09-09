package com.tencent.trtcasr.examples;

import java.nio.file.Files;
import java.nio.file.Path;

import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.v3.FileRecognizer;
import com.tencent.trtcasr.v3.TranscriptionStatus;
import com.tencent.trtcasr.v3.Wire;

/**
 * Async audio file recognition example over the v3 protocol
 * (POST /v3/create_transcription + /v3/describe_transcription).
 *
 * <p>Credentials come from environment variables:
 * {@code TRTC_ASR_SDK_APP_ID}, {@code TRTC_ASR_SECRET_KEY}
 * (v3 does not need the Tencent Cloud APPID.)
 *
 * <p>Prerequisite: the server has enabled the EnableV3Route gray switch for
 * your SDKAppID, otherwise requests fail with 404/4001.
 *
 * <p>Usage: {@code java ... V3FileAsrExample <local.wav> [engine]} or
 * {@code java ... V3FileAsrExample -u <url> [engine]}
 */
public class V3FileAsrExample {
    public static void main(String[] args) throws Exception {
        long sdkAppId = Long.parseLong(env("TRTC_ASR_SDK_APP_ID", "0"));
        String secretKey = env("TRTC_ASR_SECRET_KEY", "");
        if (sdkAppId == 0 || secretKey.isEmpty()) {
            System.err.println("Set TRTC_ASR_SDK_APP_ID and TRTC_ASR_SECRET_KEY first.");
            System.exit(1);
        }
        if (args.length < 1) {
            System.err.println("Usage: V3FileAsrExample <local.wav> | V3FileAsrExample -u <url> [engine]");
            System.exit(1);
        }
        String engine = args.length > 1 && !args[0].equals("-u") ? args[1]
                : args.length > 2 ? args[2] : "16k_zh_en";

        // v3 credentials need only SDKAppID + SecretKey (no Tencent Cloud APPID).
        Credential credential = Wire.newCredential(sdkAppId, secretKey);
        FileRecognizer recognizer = new FileRecognizer(credential);

        String taskId;
        if (args[0].equals("-u")) {
            taskId = recognizer.createTaskFromURL(args[1], engine);
        } else {
            byte[] data = Files.readAllBytes(Path.of(args[0]));
            taskId = recognizer.createTaskFromData(data, engine);
        }
        System.out.println("Task created: " + taskId);

        TranscriptionStatus status = recognizer.waitForResult(taskId);
        System.out.printf("Status: %s  Duration: %.2f s%n", status.statusStr, status.audioDuration);
        System.out.println("Result: " + status.result);
        if (status.resultDetail != null) {
            for (TranscriptionStatus.SentenceDetail d : status.resultDetail) {
                String speaker = !d.speakerRoleName.isEmpty() ? " [" + d.speakerRoleName + "]"
                        : d.speakerId > 0 ? " [spk" + d.speakerId + "]" : "";
                System.out.printf("  [%6d - %6d]%s %s%n", d.startMs, d.endMs, speaker,
                        d.finalSentence);
            }
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
