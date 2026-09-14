package com.tencent.trtcasr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.asr.FileRecognizer;
import com.tencent.trtcasr.asr.FileRecognizer.CreateRecTaskRequest;
import com.tencent.trtcasr.asr.FileRecognizer.TaskStatus;
import com.tencent.trtcasr.asr.SentenceRecognizer;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.ErrorCodes;
import com.tencent.trtcasr.common.SignatureParams;
import com.tencent.trtcasr.common.SpeakerRole;

/** FileRecognizer tests, ported from the Go SDK's file_recognizer_test.go. */
class FileRecognizerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Credential testCredential() {
        return new Credential(1300000000L, 1400000000L, "test-secret");
    }

    @Test
    void validateCreateRequest() {
        FileRecognizer r = new FileRecognizer(testCredential());

        ASRException err = assertThrows(ASRException.class, () -> r.createTask(null));
        assertEquals(ErrorCodes.INVALID_PARAM, err.getCode());
        assertTrue(err.getMessage().contains("request is null"));

        CreateRecTaskRequest noEngine = new CreateRecTaskRequest();
        noEngine.setChannelNum(1);
        err = assertThrows(ASRException.class, () -> r.createTask(noEngine));
        assertTrue(err.getMessage().contains("EngineModelType is required"));

        CreateRecTaskRequest badChannel = new CreateRecTaskRequest();
        badChannel.setEngineModelType("16k_zh");
        badChannel.setChannelNum(0);
        err = assertThrows(ASRException.class, () -> r.createTask(badChannel));
        assertTrue(err.getMessage().contains("ChannelNum must be positive"));

        CreateRecTaskRequest urlNoUrl = new CreateRecTaskRequest();
        urlNoUrl.setEngineModelType("16k_zh");
        urlNoUrl.setChannelNum(1);
        urlNoUrl.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
        err = assertThrows(ASRException.class, () -> r.createTask(urlNoUrl));
        assertTrue(err.getMessage().contains("Url is required"));

        CreateRecTaskRequest dataNoData = new CreateRecTaskRequest();
        dataNoData.setEngineModelType("16k_zh");
        dataNoData.setChannelNum(1);
        dataNoData.setSourceType(SentenceRecognizer.SOURCE_TYPE_DATA);
        err = assertThrows(ASRException.class, () -> r.createTask(dataNoData));
        assertTrue(err.getMessage().contains("Data is required"));
    }

    @Test
    void createTaskFromDataRejectsEmptyAndOversized() {
        FileRecognizer r = new FileRecognizer(testCredential());

        ASRException err = assertThrows(ASRException.class,
                () -> r.createTaskFromData(new byte[0], "pcm", "16k_zh"));
        assertTrue(err.getMessage().contains("audio data is empty"));

        byte[] big = new byte[6 * 1024 * 1024]; // 6MB > 5MB limit
        err = assertThrows(ASRException.class,
                () -> r.createTaskFromData(big, "pcm", "16k_zh"));
        assertTrue(err.getMessage().contains("5MB"));
    }

    @Test
    void createTaskFromUrlRejectsEmpty() {
        FileRecognizer r = new FileRecognizer(testCredential());
        ASRException err = assertThrows(ASRException.class,
                () -> r.createTaskFromUrl("", "16k_zh"));
        assertTrue(err.getMessage().contains("audio URL is empty"));
    }

    @Test
    void createTaskSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                assertEquals("POST", req.method);
                assertEquals("/v1/CreateRecTask", req.path);
                assertEquals("application/json; charset=utf-8", req.header("Content-Type"));
                assertNotNull(req.header("X-TRTC-SdkAppId"));
                assertNotNull(req.header("X-TRTC-UserSig"));
                for (String key : new String[]{"AppId", "Secretid", "RequestId", "Timestamp"}) {
                    assertNotNull(req.queryParam(key), "missing " + key);
                }
                JsonNode body = MAPPER.readTree(req.body);
                assertEquals("16k_zh_en", body.get("EngineModelType").asText());
                assertEquals(SentenceRecognizer.SOURCE_TYPE_DATA, body.get("SourceType").asInt());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{\"RecTaskId\":\"test-task-id-12345\"},\"RequestId\":\"test-request-id\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            String taskId = r.createTaskFromData(
                    "fake-pcm-audio".getBytes(StandardCharsets.UTF_8), "pcm", "16k_zh_en");
            assertEquals("test-task-id-12345", taskId);
        }
    }

    @Test
    void createTaskServerError() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Error\":{\"Code\":\"4002\",\"Message\":\"鉴权失败\"},\"RequestId\":\"test-request-id\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            ASRException err = assertThrows(ASRException.class, () -> r
                    .createTaskFromData("fake-audio".getBytes(StandardCharsets.UTF_8), "pcm", "16k_zh_en"));
            assertEquals(ErrorCodes.SERVER_ERROR, err.getCode());
            assertTrue(err.getMessage().contains("4002"), err.getMessage());
        }
    }

    @Test
    void createTaskHttpError() throws Exception {
        try (MockHttpServer server = new MockHttpServer(
                req -> new MockHttpServer.MockResponse(500, "internal server error"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            ASRException err = assertThrows(ASRException.class, () -> r
                    .createTaskFromData("fake-audio".getBytes(StandardCharsets.UTF_8), "pcm", "16k_zh_en"));
            assertTrue(err.getMessage().contains("500"), err.getMessage());
        }
    }

    @Test
    void createTaskEmptyTaskIdIsError() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"\"},\"RequestId\":\"r\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            ASRException err = assertThrows(ASRException.class, () -> r
                    .createTaskFromData("fake-audio".getBytes(StandardCharsets.UTF_8), "pcm", "16k_zh_en"));
            assertTrue(err.getMessage().contains("empty RecTaskId"));
        }
    }

    @Test
    void createTaskWithDiarizationAndVadOptions() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                JsonNode body = MAPPER.readTree(req.body);
                assertEquals(3, body.get("SpeakerDiarization").asInt());
                assertEquals(2, body.get("SpeakerNumber").asInt());
                assertEquals("teacher", body.get("SpeakerRoles").get(0).get("RoleName").asText());
                assertEquals("https://example.com/a.wav",
                        body.get("SpeakerRoles").get(0).get("AudioUrl").asText());
                assertEquals("vp-1", body.get("VoiceprintIds").get(0).asText());
                // VadLevel=0 must be serialized (boxed Integer distinguishes "unset").
                assertTrue(body.has("VadLevel"), "VadLevel=0 must reach the wire: " + req.body);
                assertEquals(0, body.get("VadLevel").asInt());
                assertEquals(1.5, body.get("NoiseThreshold").asDouble());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-diar\"},\"RequestId\":\"r\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            CreateRecTaskRequest req = new CreateRecTaskRequest();
            req.setEngineModelType("16k_zh_en");
            req.setChannelNum(1);
            req.setResTextFormat(1);
            req.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
            req.setUrl("https://example.com/call.wav");
            req.setSpeakerDiarization(SignatureParams.SPEAKER_DIARIZATION_VOICEPRINT);
            req.setSpeakerNumber(2);
            req.setSpeakerRoles(List.of(new SpeakerRole("teacher", "https://example.com/a.wav")));
            req.setVoiceprintIds(List.of("vp-1"));
            req.setVadLevel(0);
            req.setNoiseThreshold(1.5);

            assertEquals("task-diar", r.createTask(req));
        }
    }

    @Test
    void createTaskRejectsInvalidDiarization() {
        FileRecognizer r = new FileRecognizer(testCredential());
        CreateRecTaskRequest req = new CreateRecTaskRequest();
        req.setEngineModelType("16k_zh_en");
        req.setChannelNum(1);
        req.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
        req.setUrl("https://example.com/call.wav");
        req.setSpeakerDiarization(2); // unsupported

        ASRException err = assertThrows(ASRException.class, () -> r.createTask(req));
        assertTrue(err.getMessage().contains("SpeakerDiarization must be 0"));
    }

    @Test
    void createTaskFromDataWithOptions() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                JsonNode body = MAPPER.readTree(req.body);
                assertEquals(1, body.get("FilterDirty").asInt());
                assertEquals(2, body.get("ResTextFormat").asInt());
                assertEquals("hw-123", body.get("HotwordId").asText());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-opts\"},\"RequestId\":\"r\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            CreateRecTaskRequest req = new CreateRecTaskRequest();
            req.setEngineModelType("16k_zh_en");
            req.setChannelNum(1);
            req.setResTextFormat(2);
            req.setFilterDirty(1);
            req.setHotwordId("hw-123");
            String taskId = r.createTaskFromDataWithOptions(
                    "fake-audio".getBytes(StandardCharsets.UTF_8), req);
            assertEquals("task-opts", taskId);
        }
    }

    @Test
    void describeTaskStatusEscapesTaskId() throws Exception {
        // RecTaskId containing quotes, backslashes, control chars and
        // unicode must round-trip through valid JSON (Jackson-escaped).
        String tricky = "task-\"quoted\"-\\back\\-换行\n-uni";
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                JsonNode body = MAPPER.readTree(req.body); // must stay valid JSON
                assertEquals(tricky, body.get("RecTaskId").asText());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{\"RecTaskId\":\"t\",\"Status\":2,\"StatusStr\":\"success\"},\"RequestId\":\"r\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());
            TaskStatus status = r.describeTaskStatus(tricky);
            assertEquals(FileRecognizer.TASK_STATUS_SUCCESS, status.getStatus());
        }
    }

    @Test
    void describeTaskStatusEmptyId() {
        FileRecognizer r = new FileRecognizer(testCredential());
        ASRException err = assertThrows(ASRException.class, () -> r.describeTaskStatus(""));
        assertTrue(err.getMessage().contains("RecTaskId is empty"));
    }

    @Test
    void describeTaskStatusSuccessWithDetails() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            try {
                assertEquals("/v1/DescribeTaskStatus", req.path);
                JsonNode body = MAPPER.readTree(req.body);
                assertEquals("task-123", body.get("RecTaskId").asText());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{"
                            + "\"RecTaskId\":\"task-123\",\"Status\":2,\"StatusStr\":\"success\",\"Progress\":100,"
                            + "\"Result\":\"今天天气不错。\",\"AudioDuration\":2.38,"
                            + "\"ResultDetail\":[{"
                            + "\"FinalSentence\":\"今天天气不错。\",\"SliceSentence\":\"今天 天气 不错\","
                            + "\"StartMs\":200,\"EndMs\":1380,\"WordsNum\":1,\"SpeechSpeed\":2.0,"
                            + "\"SpeakerId\":1,\"SpeakerRoleName\":\"teacher\",\"ChannelId\":0,\"Language\":\"zh\","
                            + "\"Words\":[{\"Word\":\"今天\",\"StartTime\":200,\"EndTime\":500}]}]"
                            + "},\"RequestId\":\"req-123\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            TaskStatus status = r.describeTaskStatus("task-123");
            assertEquals(FileRecognizer.TASK_STATUS_SUCCESS, status.getStatus());
            assertEquals("今天天气不错。", status.getResult());
            assertEquals(2.38, status.getAudioDuration(), 1e-9);
            assertEquals(1, status.getResultDetail().size());
            assertEquals("今天天气不错。", status.getResultDetail().get(0).getFinalSentence());
            assertEquals(1, status.getResultDetail().get(0).getSpeakerId());
            assertEquals("teacher", status.getResultDetail().get(0).getSpeakerRoleName());
            assertEquals("zh", status.getResultDetail().get(0).getLanguage());
            assertEquals("今天", status.getResultDetail().get(0).getWords().get(0).getWord());
            assertEquals(200, status.getResultDetail().get(0).getWords().get(0).getOffsetStartMs());
            assertEquals(500, status.getResultDetail().get(0).getWords().get(0).getOffsetEndMs());
        }
    }

    @Test
    void describeTaskStatusAcceptsOffsetSpelling() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-2\",\"Status\":2,\"StatusStr\":\"success\","
                        + "\"ResultDetail\":[{\"FinalSentence\":\"你好\","
                        + "\"Words\":[{\"Word\":\"你\",\"OffsetStartMs\":120,\"OffsetEndMs\":640}]}]},"
                        + "\"RequestId\":\"req-2\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            TaskStatus status = r.describeTaskStatus("task-2");
            assertEquals(1, status.getResultDetail().size());
            assertEquals(120, status.getResultDetail().get(0).getWords().get(0).getOffsetStartMs());
            assertEquals(640, status.getResultDetail().get(0).getWords().get(0).getOffsetEndMs());
        }
    }

    @Test
    void describeTaskStatusTaskFailedFields() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-fail\",\"Status\":3,\"StatusStr\":\"failed\","
                        + "\"ErrorMsg\":\"Failed to download audio file\"},\"RequestId\":\"req-456\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            TaskStatus status = r.describeTaskStatus("task-fail");
            assertEquals(FileRecognizer.TASK_STATUS_FAILED, status.getStatus());
            assertEquals("Failed to download audio file", status.getErrorMsg());
        }
    }

    @Test
    void waitForResultImmediateSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-ok\",\"Status\":2,\"StatusStr\":\"success\","
                        + "\"Result\":\"识别结果\",\"AudioDuration\":1.5},\"RequestId\":\"req-ok\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            TaskStatus status = r.waitForResult("task-ok");
            assertEquals("识别结果", status.getResult());
        }
    }

    @Test
    void waitForResultPollingThenSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (MockHttpServer server = new MockHttpServer(req -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return MockHttpServer.MockResponse.json(
                        "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-poll\",\"Status\":1,\"StatusStr\":\"doing\"},\"RequestId\":\"req-poll\"}}");
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-poll\",\"Status\":2,\"StatusStr\":\"success\","
                            + "\"Result\":\"轮询成功\",\"AudioDuration\":3.0},\"RequestId\":\"req-poll\"}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            TaskStatus status = r.waitForResult("task-poll",
                    Duration.ofMillis(50), Duration.ofSeconds(5));
            assertEquals("轮询成功", status.getResult());
            assertTrue(calls.get() >= 3);
        }
    }

    @Test
    void waitForResultTaskFailed() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-err\",\"Status\":3,\"StatusStr\":\"failed\","
                        + "\"ErrorMsg\":\"转码失败\"},\"RequestId\":\"req-err\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            ASRException err = assertThrows(ASRException.class, () -> r.waitForResult("task-err"));
            assertEquals(ErrorCodes.SERVER_ERROR, err.getCode());
            assertTrue(err.getMessage().contains("转码失败"), err.getMessage());
        }
    }

    @Test
    void waitForResultTimeout() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"Data\":{\"RecTaskId\":\"task-slow\",\"Status\":0,\"StatusStr\":\"waiting\"},\"RequestId\":\"req-slow\"}}"))) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            ASRException err = assertThrows(ASRException.class, () -> r.waitForResult(
                    "task-slow", Duration.ofMillis(20), Duration.ofMillis(100)));
            assertEquals(ErrorCodes.TIMEOUT, err.getCode());
            assertTrue(err.getMessage().contains("not completed"), err.getMessage());
        }
    }

    @Test
    void requestSerializationSkipsNullAndEmpty() throws Exception {
        CreateRecTaskRequest req = new CreateRecTaskRequest();
        req.setEngineModelType("16k_zh");
        req.setChannelNum(1);
        req.setResTextFormat(1);
        req.setSourceType(SentenceRecognizer.SOURCE_TYPE_URL);
        req.setUrl("https://example.com/a.wav");
        String json = MAPPER.writeValueAsString(req);
        for (String key : new String[]{"VadLevel", "NoiseThreshold", "SpeakerRoles",
                "VoiceprintIds", "SpeakerDiarization", "Data", "CallbackUrl", "Language"}) {
            org.junit.jupiter.api.Assertions.assertFalse(json.contains(key),
                    key + " should be omitted: " + json);
        }
    }
}
