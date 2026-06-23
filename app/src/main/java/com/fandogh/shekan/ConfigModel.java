package com.fandogh.shekan;

public class ConfigModel {
    private int id;
    private String name;
    private String encryptedData;
    private String decryptedData;
    private String status; // "pending", "tested", "failed"
    private long pingMs;
    private String speed; // "fast", "slow", "unknown"
    private boolean isSelected;

    public ConfigModel(int id, String name, String encryptedData) {
        this.id = id;
        this.name = name;
        this.encryptedData = encryptedData;
        this.status = "pending";
        this.pingMs = -1;
        this.speed = "unknown";
        this.isSelected = false;
    }

    // Getters
    public int getId() { return id; }
    public String getName() { return name; }
    public String getEncryptedData() { return encryptedData; }
    public String getDecryptedData() { return decryptedData; }
    public String getStatus() { return status; }
    public long getPingMs() { return pingMs; }
    public String getSpeed() { return speed; }
    public boolean isSelected() { return isSelected; }

    // Setters
    public void setDecryptedData(String decryptedData) { this.decryptedData = decryptedData; }
    public void setStatus(String status) { this.status = status; }
    public void setPingMs(long pingMs) { this.pingMs = pingMs; }
    public void setSpeed(String speed) { this.speed = speed; }
    public void setSelected(boolean selected) { isSelected = selected; }

    // Calculate speed based on ping
    public void calculateSpeed() {
        if (pingMs <= 0) {
            this.speed = "unknown";
        } else if (pingMs < 50) {
            this.speed = "فوق‌العاده سریع ⚡";
        } else if (pingMs < 100) {
            this.speed = "سریع ✓";
        } else if (pingMs < 200) {
            this.speed = "معمول ≈";
        } else {
            this.speed = "آهسته ⚠";
        }
    }

    @Override
    public String toString() {
        return String.format("%s [%s] - Ping: %dms - %s", name, status, pingMs, speed);
    }
}
