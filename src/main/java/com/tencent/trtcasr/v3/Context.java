package com.tencent.trtcasr.v3;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Recognition context, aligned with Soniox / Volcengine semantics. All three
 * parts are optional; empty parts are omitted on the wire. LLM-class engines
 * can consume all of them; traditional engines degrade {@code terms} to
 * hotwords and ignore {@code text}/{@code general}.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class Context {
    /** Background text of the session. */
    @JsonProperty("text")
    private String text = "";

    /** Domain terms / jargon. */
    @JsonProperty("terms")
    private List<String> terms = new ArrayList<>();

    /** Domain key-value pairs. */
    @JsonProperty("general")
    private List<KV> general = new ArrayList<>();

    /** A domain key-value pair of {@link #general}. */
    public static class KV {
        @JsonProperty("key")
        private String key;

        @JsonProperty("value")
        private String value;

        public KV() {
        }

        public KV(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getTerms() {
        return terms;
    }

    public void setTerms(List<String> terms) {
        this.terms = terms;
    }

    public List<KV> getGeneral() {
        return general;
    }

    public void setGeneral(List<KV> general) {
        this.general = general;
    }
}
