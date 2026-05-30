package TorrentClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TorrentDownloader {

    private static final long TRACKER_REFRESH_MS = 60 * 1000;

    private final TorrentMetadata metadata;
    private final TorrentStorage storage;
    private final byte[] myPeerId;
    private final String trackerOverride;

    public TorrentDownloader(TorrentMetadata metadata, TorrentStorage storage,
                             byte[] myPeerId, String trackerOverride) {
        this.metadata = metadata;
        this.storage = storage;
        this.myPeerId = myPeerId;
        this.trackerOverride = trackerOverride;
    }

    public void download() throws Exception {
        System.out.println("Verifying existing data...");
        var initialBitSet = storage.buildInitialBitSet(metadata);
        int alreadyDone = initialBitSet.cardinality();
        if (alreadyDone > 0) {
            System.out.printf("Resuming: %d / %d pieces already verified%n", alreadyDone, metadata.totalPieces);
        }

        PieceManager pieceManager = new PieceManager(metadata.totalPieces, initialBitSet);

        System.out.println("Torrent:  " + metadata.name);
        System.out.printf("Size:     %.2f MB (%d bytes)%n", metadata.totalSize / 1_048_576.0, metadata.totalSize);
        System.out.println("Pieces:   " + metadata.totalPieces + " x " + metadata.pieceLength + " bytes");

        if (pieceManager.isFinished()) {
            System.out.println("Already complete.");
            return;
        }

        ExecutorService diskExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "disk-writer");
            t.setDaemon(true);
            return t;
        });
        ExecutorService networkExecutor = Executors.newVirtualThreadPerTaskExecutor();
        DownloadStats stats = new DownloadStats(metadata.totalSize);

        try {
            List<String> trackerUrls = trackerOverride != null
                    ? List.of(trackerOverride)
                    : metadata.announceUrls;

            Set<String> connectedPeers = ConcurrentHashMap.newKeySet();
            List<TrackerClient.Peer> initialPeers = TrackerClient.collectPeersFromUrls(trackerUrls, metadata, myPeerId);
            System.out.println("Found " + initialPeers.size() + " peers from "
                    + (trackerOverride != null ? "override tracker" : metadata.announceUrls.size() + " trackers"));
            connectToPeers(initialPeers, connectedPeers, pieceManager, storage, diskExecutor, networkExecutor);

            long lastTrackerRefresh = System.currentTimeMillis();

            while (!pieceManager.isFinished()) {
                Thread.sleep(2000);

                long completedBytes = Math.min(
                        (long) pieceManager.completedCount() * metadata.pieceLength, metadata.totalSize);
                stats.recordProgress(completedBytes);
                System.out.print("\r" + stats.formatLine(completedBytes));

                if (System.currentTimeMillis() - lastTrackerRefresh >= TRACKER_REFRESH_MS) {
                    lastTrackerRefresh = System.currentTimeMillis();
                    try {
                        List<TrackerClient.Peer> peers = TrackerClient.collectPeersFromUrls(
                                trackerUrls, metadata, myPeerId);
                        System.out.println("\nTracker refresh: " + peers.size() + " peers");
                        connectToPeers(peers, connectedPeers, pieceManager, storage, diskExecutor, networkExecutor);
                    } catch (Exception e) {
                        System.err.println("\nTracker refresh failed: " + e.getMessage());
                    }
                }
            }

            System.out.println("\nDownload complete.");

        } finally {
            networkExecutor.shutdownNow();
            diskExecutor.shutdown();
            try {
                if (!diskExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    diskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                diskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            storage.close();
        }
    }

    private void connectToPeers(List<TrackerClient.Peer> peers, Set<String> connectedPeers,
                                PieceManager pieceManager, TorrentStorage storage,
                                ExecutorService diskExecutor, ExecutorService networkExecutor) {
        for (TrackerClient.Peer peer : peers) {
            String key = peer.ip() + ":" + peer.port();
            if (!connectedPeers.add(key)) {
                continue;
            }
            PeerWorker worker = new PeerWorker(peer, metadata, myPeerId, pieceManager, storage, diskExecutor,
                    () -> connectedPeers.remove(key));
            networkExecutor.submit(worker);
        }
    }
}
