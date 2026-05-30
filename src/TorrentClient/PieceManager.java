package TorrentClient;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class PieceManager {
    private static final int ENDGAME_THRESHOLD = 200;

    private final int totalPieces;
    private final BitSet completedPieces = new BitSet();
    private final BitSet requestedPieces = new BitSet();
    private final int[] availability;

    public PieceManager(int totalPieces) {
        this.totalPieces = totalPieces;
        this.availability = new int[totalPieces];
    }

    public PieceManager(int totalPieces, BitSet alreadyCompleted) {
        this.totalPieces = totalPieces;
        this.availability = new int[totalPieces];
        this.completedPieces.or(alreadyCompleted);
    }

    public synchronized void registerAvailability(BitSet pieces) {
        for (int i = pieces.nextSetBit(0); i >= 0; i = pieces.nextSetBit(i + 1)) {
            if (i < totalPieces) availability[i]++;
        }
    }

    public synchronized void incrementAvailability(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces) availability[pieceIndex]++;
    }

    public synchronized void deregisterAvailability(BitSet pieces) {
        for (int i = pieces.nextSetBit(0); i >= 0; i = pieces.nextSetBit(i + 1)) {
            if (i < totalPieces && availability[i] > 0) availability[i]--;
        }
    }

    public synchronized int getNextPieceToDownload(BitSet peerHas) {
        if (peerHas.isEmpty()) return -1;

        int minAvail = Integer.MAX_VALUE;
        for (int i = 0; i < totalPieces; i++) {
            if (!completedPieces.get(i) && !requestedPieces.get(i) && peerHas.get(i))
                minAvail = Math.min(minAvail, availability[i]);
        }
        if (minAvail == Integer.MAX_VALUE) return -1;

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < totalPieces; i++) {
            if (!completedPieces.get(i) && !requestedPieces.get(i)
                    && peerHas.get(i) && availability[i] == minAvail)
                candidates.add(i);
        }
        int chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        requestedPieces.set(chosen);
        return chosen;
    }

    public synchronized boolean isEndgame() {
        int completed = completedPieces.cardinality();
        return completed > 0 && (totalPieces - completed) <= ENDGAME_THRESHOLD;
    }

    public synchronized int getNextEndgamePiece(BitSet peerHas) {
        for (int i = 0; i < totalPieces; i++) {
            if (!completedPieces.get(i) && peerHas.get(i))
                return i;
        }
        return -1;
    }

    public synchronized boolean markPieceCompleted(int pieceIndex) {
        if (completedPieces.get(pieceIndex)) return false;
        completedPieces.set(pieceIndex);
        requestedPieces.clear(pieceIndex);
        return true;
    }

    public synchronized void markPieceFailed(int pieceIndex) {
        requestedPieces.clear(pieceIndex);
    }

    public synchronized boolean isFinished() {
        return completedPieces.cardinality() == totalPieces;
    }

    public synchronized int completedCount() {
        return completedPieces.cardinality();
    }
}
