package run.halo.traffic.monitor;

public class TrafficMonitorProperties {
    private boolean enabled = true;
    private boolean trustProxyHeaders = false;
    private boolean includeApiTraffic = false;
    private boolean ignoreStaticResources = true;
    private int requestsPerMinuteThreshold = 120;
    private long bytesPerMinuteThreshold = 52_428_800L;
    private long singleRequestBytesThreshold = 10_485_760L;
    private int serverErrorsPerMinuteThreshold = 10;
    private int alertCooldownSeconds = 60;
    private String ipWhitelist = "127.0.0.1\n::1";
    private boolean autoBlockFailedLogins = true;
    private int failedLoginThreshold = 5;
    private int failedLoginWindowMinutes = 10;
    private int blockDurationMinutes = 1_440;
    private boolean captureResponseHeaders = true;
    private boolean captureResponseBody = true;
    private int responseBodyMaxBytes = 32_768;
    private int maxStoredRecords = 2_000;
    private boolean autoBlockTrafficAnomalies = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustProxyHeaders() {
        return trustProxyHeaders;
    }

    public void setTrustProxyHeaders(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public boolean isIncludeApiTraffic() {
        return includeApiTraffic;
    }

    public void setIncludeApiTraffic(boolean includeApiTraffic) {
        this.includeApiTraffic = includeApiTraffic;
    }

    public boolean isIgnoreStaticResources() {
        return ignoreStaticResources;
    }

    public void setIgnoreStaticResources(boolean ignoreStaticResources) {
        this.ignoreStaticResources = ignoreStaticResources;
    }

    public int getRequestsPerMinuteThreshold() {
        return requestsPerMinuteThreshold;
    }

    public void setRequestsPerMinuteThreshold(int value) {
        this.requestsPerMinuteThreshold = Math.max(1, value);
    }

    public long getBytesPerMinuteThreshold() {
        return bytesPerMinuteThreshold;
    }

    public void setBytesPerMinuteThreshold(long value) {
        this.bytesPerMinuteThreshold = Math.max(1, value);
    }

    public long getSingleRequestBytesThreshold() {
        return singleRequestBytesThreshold;
    }

    public void setSingleRequestBytesThreshold(long value) {
        this.singleRequestBytesThreshold = Math.max(1, value);
    }

    public int getServerErrorsPerMinuteThreshold() {
        return serverErrorsPerMinuteThreshold;
    }

    public void setServerErrorsPerMinuteThreshold(int value) {
        this.serverErrorsPerMinuteThreshold = Math.max(1, value);
    }

    public int getAlertCooldownSeconds() {
        return alertCooldownSeconds;
    }

    public void setAlertCooldownSeconds(int value) {
        this.alertCooldownSeconds = Math.max(1, value);
    }

    public String getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(String value) {
        this.ipWhitelist = value == null ? "" : value;
    }

    public boolean isAutoBlockFailedLogins() {
        return autoBlockFailedLogins;
    }

    public void setAutoBlockFailedLogins(boolean value) {
        this.autoBlockFailedLogins = value;
    }

    public int getFailedLoginThreshold() {
        return failedLoginThreshold;
    }

    public void setFailedLoginThreshold(int value) {
        this.failedLoginThreshold = Math.max(1, value);
    }

    public int getFailedLoginWindowMinutes() {
        return failedLoginWindowMinutes;
    }

    public void setFailedLoginWindowMinutes(int value) {
        this.failedLoginWindowMinutes = Math.max(1, value);
    }

    public int getBlockDurationMinutes() {
        return blockDurationMinutes;
    }

    public void setBlockDurationMinutes(int value) {
        this.blockDurationMinutes = Math.max(1, value);
    }

    public boolean isCaptureResponseHeaders() {
        return captureResponseHeaders;
    }

    public void setCaptureResponseHeaders(boolean value) {
        this.captureResponseHeaders = value;
    }

    public boolean isCaptureResponseBody() {
        return captureResponseBody;
    }

    public void setCaptureResponseBody(boolean value) {
        this.captureResponseBody = value;
    }

    public int getResponseBodyMaxBytes() {
        return responseBodyMaxBytes;
    }

    public void setResponseBodyMaxBytes(int value) {
        this.responseBodyMaxBytes = Math.max(1_024, Math.min(1_048_576, value));
    }

    public int getMaxStoredRecords() {
        return maxStoredRecords;
    }

    public void setMaxStoredRecords(int value) {
        this.maxStoredRecords = Math.max(100, Math.min(20_000, value));
    }

    public boolean isAutoBlockTrafficAnomalies() {
        return autoBlockTrafficAnomalies;
    }

    public void setAutoBlockTrafficAnomalies(boolean value) {
        this.autoBlockTrafficAnomalies = value;
    }

}
