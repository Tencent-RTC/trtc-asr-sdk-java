package com.tencent.trtcasr.common;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SDK's self-identification carried by every request.
 *
 * <p>Every request (WebSocket handshake and HTTP API calls) reports which SDK
 * language, version and OS platform produced it. Without this, a customer
 * issue can only be traced to an AppID — not to the concrete client build that
 * triggered it, which is what makes cross-version regressions diagnosable.
 *
 * <p>The values travel as URL query parameters rather than headers because a
 * browser-originated WebSocket handshake cannot set custom headers, and the
 * three transports must report identically.
 */
public final class SdkInfo {
    /**
     * The released version of this SDK. Must be kept in sync with the
     * {@code <version>} element in pom.xml.
     *
     * <p>Deliberately a compile-time constant rather than a value read from
     * the MANIFEST or a packaged pom.properties: the SDK may be shaded into a
     * fat jar or unpacked into a plain classpath, where those resources are
     * absent — a runtime lookup would then report nothing at all, which is
     * worse than a constant that a release checklist keeps current.
     */
    public static final String SDK_VERSION = "1.2.1";

    /** Identifies the SDK implementation language. */
    public static final String SDK_LANGUAGE = "java";

    /**
     * Distinguishes this family of SDKs from the client-side ones. All six
     * language bindings here run server-side, so the value is constant; it
     * exists so server-side telemetry can bucket traffic the same way it does
     * for the mobile/desktop client SDKs.
     */
    public static final String SDK_TYPE = "server";

    private SdkInfo() {
    }

    /**
     * Reports the OS platform the SDK is running on, normalized to the
     * vocabulary the service expects: windows, linux, mac, android, ios. Any
     * other platform is reported verbatim (lowercased, whitespace stripped)
     * so it shows up in telemetry instead of being silently misattributed.
     */
    public static String platform() {
        String osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "mac";
        }
        if (osName.contains("linux")) {
            // Android also reports os.name as "Linux", so the VM has to break
            // the tie; otherwise every mobile client would look like a server.
            return isAndroid() ? "android" : "linux";
        }
        if (osName.contains("ios")) {
            return "ios";
        }
        return osName.replaceAll("\\s+", "");
    }

    /**
     * Returns the SDK identification parameters shared by every transport.
     * The returned map is a fresh copy, so callers may merge it into their own
     * parameter map.
     */
    public static Map<String, String> reportParams() {
        // TreeMap keeps keys sorted, matching Go's sort.Strings.
        Map<String, String> params = new TreeMap<>();
        params.put("platform", platform());
        params.put("sdk_lang", SDK_LANGUAGE);
        params.put("sdk_type", SDK_TYPE);
        params.put("version", SDK_VERSION);
        return params;
    }

    /**
     * Returns the SDK identification parameters as an encoded query fragment
     * (no leading {@code &}), for the transports that build their URL by
     * string concatenation.
     */
    public static String reportQuery() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : reportParams().entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(SignatureParams.queryEscape(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * Detects an Android runtime. {@code java.vm.name} is "Dalvik" on every
     * current Android release (ART kept the legacy name); "art" is matched as
     * a whole value rather than a substring because three letters are too
     * short to test safely against arbitrary JVM names.
     */
    private static boolean isAndroid() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
        return vmName.contains("dalvik") || vmName.trim().equals("art")
                || vmVendor.contains("android");
    }
}
