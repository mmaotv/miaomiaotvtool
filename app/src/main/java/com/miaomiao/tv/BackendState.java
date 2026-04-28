package com.miaomiao.tv;

public class BackendState {
    public final BackendStatus status;
    public final String summary;
    public final String detail;
    public final String deviceSerial;
    public final boolean ready;
    public final boolean usesShizuku;

    public BackendState(BackendStatus status, String summary, String detail,
                        String deviceSerial, boolean ready, boolean usesShizuku) {
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.deviceSerial = deviceSerial;
        this.ready = ready;
        this.usesShizuku = usesShizuku;
    }
}
