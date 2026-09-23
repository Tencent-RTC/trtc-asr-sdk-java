package com.tencent.trtcasr;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.trtcasr.asr.SpeechRecognizer;
import com.tencent.trtcasr.asr.SpeakerContinue;
import com.tencent.trtcasr.asr.SpeechRecognitionResponse;
import com.tencent.trtcasr.common.ASRException;
import com.tencent.trtcasr.common.SignatureParams;
import com.tencent.trtcasr.v3.Wire;  // 常量与 v3 数值一致

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v2 断点续传：query 参数下发、本地校验与首响应 speaker_continue 解析。 */
class SpeakerContextTest {

    private SignatureParams params(int mode, String ctxId) {
        SignatureParams p = new SignatureParams(1400000000, "bigmodel", "v1");
        p.setSpeakerDiarization(SignatureParams.SPEAKER_DIARIZATION_CLUSTER);
        p.setEnableSpeakerContext(mode);
        p.setSpeakerContextId(ctxId);
        return p;
    }

    @Test
    void queryEmitsContextParams() {
        String query = params(Wire.SPEAKER_CONTEXT_SYNC, "abc123")
                .buildQueryString();
        assertTrue(query.contains("enable_speaker_context=1"), query);
        assertTrue(query.contains("speaker_context_id=abc123"), query);

        // 异步模式值为 2
        String async = params(Wire.SPEAKER_CONTEXT_ASYNC, "abc123")
                .buildQueryString();
        assertTrue(async.contains("enable_speaker_context=2"), async);
    }

    @Test
    void queryOmitsContextParamsWhenOff() {
        String query = params(0, "abc123").buildQueryString();
        assertFalse(query.contains("speaker_context"), query);
    }

    @Test
    void enableSpeakerContextRequiresDiarization() {
        SpeechRecognizer recognizer = new SpeechRecognizer(
                Wire.newCredential(1400000000, "test-secret"), "bigmodel", null);
        recognizer.setEnableSpeakerContext(Wire.SPEAKER_CONTEXT_SYNC);
        ASRException err = assertThrows(ASRException.class, recognizer::start);
        assertTrue(err.getMessage().contains("setSpeakerDiarization"), err.getMessage());
    }

    @Test
    void enableSpeakerContextRejectsUnknownMode() {
        SpeechRecognizer recognizer = new SpeechRecognizer(
                Wire.newCredential(1400000000, "test-secret"), "bigmodel", null);
        recognizer.setSpeakerDiarization(SignatureParams.SPEAKER_DIARIZATION_CLUSTER);
        recognizer.setEnableSpeakerContext(3);
        ASRException err = assertThrows(ASRException.class, recognizer::start);
        assertTrue(err.getMessage().contains("EnableSpeakerContext"), err.getMessage());
    }

    @Test
    void responseParsesSpeakerContinue() throws Exception {
        // 首响应解析由共享的 SpeechRecognitionResponse 承担（v2/v3 同构，
        // v2 帧经 Jackson treeToValue 反序列化）。
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"code\":0,\"message\":\"success\",\"voice_id\":\"v1\","
                + "\"speaker_continue\":{\"continue_status\":\"resumed\","
                + "\"speaker_context_id\":\"abc123\"}}";
        SpeechRecognitionResponse resp = mapper.treeToValue(
                mapper.readTree(json), SpeechRecognitionResponse.class);
        assertNotNull(resp.getSpeakerContinue());
        assertEquals("resumed", resp.getSpeakerContinue().getContinueStatus());
        assertEquals("abc123", resp.getSpeakerContinue().getSpeakerContextId());

        // 未开启时缺省为 null
        SpeechRecognitionResponse plain = mapper.treeToValue(
                mapper.readTree("{\"code\":0,\"voice_id\":\"v1\"}"),
                SpeechRecognitionResponse.class);
        assertNull(plain.getSpeakerContinue());
    }
}
