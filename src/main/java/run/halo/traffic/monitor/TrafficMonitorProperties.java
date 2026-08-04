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

}
