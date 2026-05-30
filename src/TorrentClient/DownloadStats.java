package TorrentClient;

public class DownloadStats {

    private static final int WINDOW = 20;

    private final long totalBytes;
    private final long[] timestamps = new long[WINDOW];
    private final long[] bytesCompleted = new long[WINDOW];
    private int head = 0;
    private int count = 0;

    public DownloadStats(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public synchronized void recordProgress(long completed) {
        timestamps[head] = System.currentTimeMillis();
        bytesCompleted[head] = completed;
        head = (head + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    public synchronized String formatLine(long completed) {
        double pct = totalBytes > 0 ? (completed * 100.0 / totalBytes) : 0.0;
        String speedStr = "calculating...";
        String etaStr = "";

        if (count >= 2) {
            int tail = (head - count + WINDOW) % WINDOW;
            int newest = (head - 1 + WINDOW) % WINDOW;
            long deltaMs = timestamps[newest] - timestamps[tail];
            long deltaBytes = bytesCompleted[newest] - bytesCompleted[tail];

            if (deltaMs > 0 && deltaBytes >= 0) {
                double speedBps = deltaBytes * 1000.0 / deltaMs;
                speedStr = formatSpeed(speedBps);

                long remaining = totalBytes - completed;
                if (speedBps > 0 && remaining > 0) {
                    long etaSec = (long) (remaining / speedBps);
                    etaStr = " — ETA " + formatEta(etaSec);
                } else if (remaining <= 0) {
                    etaStr = " — done";
                }
            }
        }

        return String.format("%.1f%% | %s%s   ", pct, speedStr, etaStr);
    }

    private static String formatSpeed(double bps) {
        if (bps >= 1_048_576) return String.format("%.1f MB/s", bps / 1_048_576);
        if (bps >= 1_024) return String.format("%.1f KB/s", bps / 1_024);
        return String.format("%.0f B/s", bps);
    }

    private static String formatEta(long seconds) {
        if (seconds >= 3600) return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return String.format("%dm %ds", seconds / 60, seconds % 60);
        return seconds + "s";
    }
}
