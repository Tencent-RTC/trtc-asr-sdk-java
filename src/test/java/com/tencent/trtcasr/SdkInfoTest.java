package com.tencent.trtcasr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.tencent.trtcasr.asr.FileRecognizer;
import com.tencent.trtcasr.asr.FileRecognizer.TaskStatus;
import com.tencent.trtcasr.asr.SentenceRecognizer;
import com.tencent.trtcasr.asr.SpeechRecognizer;
import com.tencent.trtcasr.common.Credential;
import com.tencent.trtcasr.common.SdkInfo;
import com.tencent.trtcasr.common.SignatureParams;

/**
 * SDK self-identification tests, ported from the Go SDK's sdkinfo_test.go.
 * Every transport must report the same language/version/platform triple, and
 * adding it must not disturb the pre-existing protocol parameters.
 */
class SdkInfoTest {

    private static Credential testCredential() {
        return new Credential(1300000000L, 1400000000L, "test-secret");
    }

    /** Asserts a captured HTTP request carries the SDK identification. */
    private static void assertSdkReportParams(MockHttpServer.CapturedRequest req) {
        assertEquals(SdkInfo.SDK_LANGUAGE, req.queryParam("sdk_lang"));
        assertEquals(SdkInfo.SDK_TYPE, req.queryParam("sdk_type"));
        assertEquals(SdkInfo.SDK_VERSION, req.queryParam("version"));
        assertEquals(SdkInfo.platform(), req.queryParam("platform"));
    }

    @Test
    void reportParamsCarryLanguageVersionAndPlatform() {
        Map<String, String> params = SdkInfo.reportParams();
        assertEquals("java", params.get("sdk_lang"));
        assertEquals("server", params.get("sdk_type"));
        assertEquals(SdkInfo.SDK_VERSION, params.get("version"));
        assertEquals(SdkInfo.platform(), params.get("platform"));
        assertEquals(4, params.size());
    }

    @Test
    void platformIsNormalized() {
        String platform = SdkInfo.platform();
        assertFalse(platform.isEmpty());
        // Normalization contract: lowercase and free of whitespace, so the
        // value never needs escaping or case-folding server-side.
        assertEquals(platform.toLowerCase(java.util.Locale.ROOT), platform);
        assertFalse(platform.matches(".*\\s.*"), platform);

        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("mac") || osName.contains("darwin")) {
            assertEquals("mac", platform);
        } else if (osName.contains("win")) {
            assertEquals("windows", platform);
        } else if (osName.contains("linux")) {
            // Android reports os.name as "Linux" too; the test JVM is not ART.
            assertEquals("linux", platform);
        }
    }

    @Test
    void reportQueryIsEncodedAndSorted() {
        // Sorted keys keep the fragment byte-identical across the transports,
        // which makes captured URLs comparable in server-side logs.
        assertEquals("platform=" + SignatureParams.queryEscape(SdkInfo.platform())
                + "&sdk_lang=java&sdk_type=server&version=" + SdkInfo.SDK_VERSION, SdkInfo.reportQuery());
    }

    @Test
    void versionMatchesThePomManifest() throws Exception {
        // The version is written down twice: SdkInfo.SDK_VERSION and pom.xml. A
        // release that bumps only one ships a jar whose reported version disagrees
        // with its own metadata, which is exactly what happened to the Node.js
        // SDK's 1.2.3. Read the manifest instead of hard-coding the value, so the
        // assertion keeps holding for every future release.
        Path pom = Path.of("pom.xml");
        assertTrue(Files.exists(pom), "pom.xml must be readable from the test working directory");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // A manifest is data, not code: refuse DTDs and external entities.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(pom.toFile());

        String manifestVersion = null;
        for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            // Only the project's own <version>; dependency versions are nested deeper.
            if (n.getNodeType() == Node.ELEMENT_NODE && "version".equals(n.getNodeName())) {
                manifestVersion = n.getTextContent().trim();
                break;
            }
        }
        assertNotNull(manifestVersion, "pom.xml has no project <version>");
        assertEquals(SdkInfo.SDK_VERSION, manifestVersion);
    }

    @Test
    void sentenceRecognizerReportsSdkIdentity() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> MockHttpServer.MockResponse.json(
                "{\"Response\":{\"RequestId\":\"req-1\",\"Result\":\"hello\"}}"))) {
            SentenceRecognizer r = new SentenceRecognizer(testCredential());
            r.setEndpoint(server.url());
            r.recognizeUrl("https://example.com/test.wav", "wav", "16k_zh");

            List<MockHttpServer.CapturedRequest> requests = server.requests();
            assertEquals(1, requests.size());
            MockHttpServer.CapturedRequest req = requests.get(0);
            assertSdkReportParams(req);
            // The pre-existing protocol parameters must survive the addition.
            assertEquals("1300000000", req.queryParam("AppId"));
            assertEquals("1300000000", req.queryParam("Secretid"));
            assertNotNull(req.queryParam("RequestId"));
            assertNotNull(req.queryParam("Timestamp"));
        }
    }

    @Test
    void fileRecognizerReportsSdkIdentityOnBothEndpoints() throws Exception {
        try (MockHttpServer server = new MockHttpServer(req -> {
            if (req.path.equals("/v1/CreateRecTask")) {
                return MockHttpServer.MockResponse.json(
                        "{\"Response\":{\"RequestId\":\"req-1\",\"Data\":{\"RecTaskId\":\"task-42\"}}}");
            }
            return MockHttpServer.MockResponse.json(
                    "{\"Response\":{\"RequestId\":\"req-2\",\"Data\":{\"RecTaskId\":\"task-42\","
                            + "\"Status\":2,\"StatusStr\":\"success\"}}}");
        })) {
            FileRecognizer r = new FileRecognizer(testCredential());
            r.setEndpoint(server.url());

            String taskId = r.createTaskFromUrl("https://example.com/test.wav", "16k_zh");
            assertEquals("task-42", taskId);
            TaskStatus status = r.describeTaskStatus(taskId);
            assertEquals(FileRecognizer.TASK_STATUS_SUCCESS, status.getStatus());

            List<MockHttpServer.CapturedRequest> requests = server.requests();
            assertEquals(2, requests.size());
            assertEquals("/v1/CreateRecTask", requests.get(0).path);
            assertEquals("/v1/DescribeTaskStatus", requests.get(1).path);
            for (MockHttpServer.CapturedRequest req : requests) {
                assertSdkReportParams(req);
                assertEquals("1300000000", req.queryParam("AppId"));
                assertEquals("1300000000", req.queryParam("Secretid"));
                assertNotNull(req.queryParam("RequestId"));
                assertNotNull(req.queryParam("Timestamp"));
            }
        }
    }

    @Test
    void websocketHandshakeReportsSdkIdentity() throws Exception {
        try (MiniWebSocketServer server = new MiniWebSocketServer(s -> {
            MiniWebSocketServer.Frame f;
            while ((f = s.read()) != null) {
                if (f.isText() && f.text().equals("{\"type\":\"end\"}")) {
                    s.sendText("{\"code\":0,\"message\":\"ok\",\"voice_id\":\"v1\",\"final\":1,"
                            + "\"result\":{\"slice_type\":2}}");
                    return;
                }
            }
        })) {
            SpeechRecognizer r = new SpeechRecognizer(testCredential(), "16k_zh_en",
                    new SpeechRecognizerTest.RecordingListener());
            r.setEndpoint(server.url());
            r.setStopTimeout(Duration.ofSeconds(2));
            r.setVoiceId("voice-sdkinfo");
            r.start();

            long deadline = System.currentTimeMillis() + 2000;
            while (server.requestTarget() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            String target = server.requestTarget();
            assertNotNull(target);
            String query = target.substring(target.indexOf('?') + 1);
            assertEquals(SdkInfo.SDK_LANGUAGE, queryParam(query, "sdk_lang"));
            assertEquals(SdkInfo.SDK_TYPE, queryParam(query, "sdk_type"));
            assertEquals(SdkInfo.SDK_VERSION, queryParam(query, "version"));
            assertEquals(SdkInfo.platform(), queryParam(query, "platform"));
            // The pre-existing protocol parameters must survive the addition.
            assertEquals("1400000000", queryParam(query, "sdkappid"));
            assertEquals("1300000000", queryParam(query, "secretid"));
            assertEquals("voice-sdkinfo", queryParam(query, "voice_id"));
            assertEquals("16k_zh_en", queryParam(query, "engine_model_type"));
            assertNotNull(queryParam(query, "usersig"));
            assertNotNull(queryParam(query, "signature"));

            r.stop();
            server.join(3000);
        }
    }

    @Test
    void signatureParamsReportSdkIdentity() {
        SignatureParams p = new SignatureParams(1300000000L, "16k_zh", "voice-1");
        String query = p.buildQueryStringWithSignature("sig");
        assertEquals(SdkInfo.SDK_LANGUAGE, queryParam(query, "sdk_lang"));
        assertEquals(SdkInfo.SDK_TYPE, queryParam(query, "sdk_type"));
        assertEquals(SdkInfo.SDK_VERSION, queryParam(query, "version"));
        assertEquals(SdkInfo.platform(), queryParam(query, "platform"));
        assertTrue(query.contains("signature=sig"));
    }

    /** Returns the decoded value of a query parameter, or null. */
    private static String queryParam(String query, String key) {
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && SignatureParamsTest.percentDecode(pair.substring(0, idx)).equals(key)) {
                return SignatureParamsTest.percentDecode(pair.substring(idx + 1));
            }
        }
        return null;
    }
}
