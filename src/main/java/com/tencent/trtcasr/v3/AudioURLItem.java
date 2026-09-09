package com.tencent.trtcasr.v3;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One audio piece of a distributed recording task. */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AudioURLItem {
    @JsonProperty("index")
    private long index;

    @JsonProperty("url")
    private String url;

    @JsonProperty("label")
    private String label = "";

    public AudioURLItem() {
    }

    public AudioURLItem(long index, String url) {
        this.index = index;
        this.url = url;
    }

    public AudioURLItem(long index, String url, String label) {
        this.index = index;
        this.url = url;
        this.label = label;
    }

    public long getIndex() {
        return index;
    }

    public void setIndex(long index) {
        this.index = index;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
