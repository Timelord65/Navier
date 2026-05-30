package TorrentClient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ExecutorService;

public class PeerWorker implements Runnable {
    private static final int BLOCK_SIZE = 16 * 1024;
    private static final int MAX_IN_FLIGHT = 10;
    private static final int MAX_MESSAGE_LENGTH = BLOCK_SIZE + 9;

    private final TrackerClient.Peer peer;
    private final TorrentMetadata metadata;
    private final byte[] myPeerId;
    private final PieceManager pieceManager;
    private final TorrentStorage storage;
    private final ExecutorService diskExecutor;
    private final Runnable onComplete;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private boolean isUnchoked = false;
    private boolean isInterested = false;
    private final BitSet peerPieces = new BitSet();

    private ActivePieceDownload activePiece;

    public PeerWorker(TrackerClient.Peer peer, TorrentMetadata metadata, byte[] myPeerId,
                      PieceManager pieceManager, TorrentStorage storage, ExecutorService diskExecutor,
                      Runnable onComplete) {
        this.peer = peer;
        this.metadata = metadata;
        this.myPeerId = myPeerId;
        this.pieceManager = pieceManager;
        this.storage = storage;
        this.diskExecutor = diskExecutor;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        while (!pieceManager.isFinished()) {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(peer.ip(), peer.port()), 5000);
                socket.setSoTimeout(120_000);

                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                resetConnectionState();
                handshake();
                sendInterested();
                isInterested = true;
                socket.setSoTimeout(5000);

                while (!pieceManager.isFinished()) {
                    try {
                        readNextMessage();
                    } catch (SocketTimeoutException ignored) {
                    }
                    requestMoreBlocks();
                }

            } catch (Exception e) {
                if (!isExpectedConnectionError(e)) {
                    System.err.println("\nPeer " + peer + " error: " + e);
                }
            } finally {
                releaseActivePiece();
                closeSocket();
            }

            if (!pieceManager.isFinished()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!peerPieces.isEmpty()) pieceManager.deregisterAvailability(peerPieces);
        onComplete.run();
    }

    private void resetConnectionState() {
        isUnchoked = false;
        isInterested = false;
        if (!peerPieces.isEmpty()) {
            pieceManager.deregisterAvailability(peerPieces);
        }
        peerPieces.clear();
        activePiece = null;
    }

    private void releaseActivePiece() {
        if (activePiece != null) {
            pieceManager.markPieceFailed(activePiece.pieceIndex);
            activePiece = null;
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    private void requestMoreBlocks() throws Exception {
        if (activePiece == null) {
            int next = pieceManager.isEndgame()
                    ? pieceManager.getNextEndgamePiece(peerPieces)
                    : pieceManager.getNextPieceToDownload(peerPieces);
            if (next == -1) return;
            activePiece = new ActivePieceDownload(next, (int) metadata.getActualPieceSize(next));
        }

        if (!isInterested) {
            sendInterested();
            isInterested = true;
            return;
        }

        if (!isUnchoked || activePiece == null) return;

        while (activePiece.inFlight < MAX_IN_FLIGHT) {
            int blockOffset = activePiece.nextBlockOffsetToRequest();
            if (blockOffset < 0) break;
            int blockLength = activePiece.blockLengthAt(blockOffset);
            sendRequest(activePiece.pieceIndex, blockOffset, blockLength);
            activePiece.inFlight++;
        }
    }

    private void readNextMessage() throws Exception {
        int length = in.readInt();
        if (length == 0) return;
        if (length < 1 || length > MAX_MESSAGE_LENGTH) {
            throw new IOException("Invalid message length: " + length + " from " + peer);
        }

        byte id = in.readByte();
        int payloadLength = length - 1;
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) in.readFully(payload);

        MessageType type;
        try {
            type = MessageType.fromValue(id);
        } catch (IllegalArgumentException e) {
            return;
        }

        switch (type) {
            case CHOKE -> handleChoke();
            case UNCHOKE -> {
                isUnchoked = true;
                requestMoreBlocks();
            }
            case HAVE -> {
                handleHave(payload);
                requestMoreBlocks();
            }
            case BITFIELD -> {
                handleBitfield(payload);
                requestMoreBlocks();
            }
            case PIECE -> handlePiece(payload);
            case INTERESTED, NOT_INTERESTED, REQUEST, CANCEL, PORT, KEEP_ALIVE -> {
            }
        }
    }

    private void handleChoke() {
        isUnchoked = false;
        if (activePiece != null) activePiece.resetPipeline();
    }

    private void handleHave(byte[] payload) {
        if (payload.length < 4) return;
        int pieceIndex = ByteBuffer.wrap(payload).getInt();
        if (isValidPieceIndex(pieceIndex)) {
            peerPieces.set(pieceIndex);
            pieceManager.incrementAvailability(pieceIndex);
        }
    }

    private void handleBitfield(byte[] payload) {
        int maxBits = metadata.totalPieces;
        for (int i = 0; i < payload.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                int pieceIndex = i * 8 + bit;
                if (pieceIndex >= maxBits) return;
                if ((payload[i] & (1 << (7 - bit))) != 0) {
                    peerPieces.set(pieceIndex);
                }
            }
        }
        pieceManager.registerAvailability(peerPieces);
    }

    private void handlePiece(byte[] payload) throws Exception {
        if (activePiece == null || payload.length < 8) return;

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int pieceIndex = buffer.getInt();
        int blockOffset = buffer.getInt();
        int dataLength = payload.length - 8;

        if (pieceIndex != activePiece.pieceIndex) return;
        if (!activePiece.isValidBlock(blockOffset, dataLength)) return;

        byte[] blockData = new byte[dataLength];
        buffer.get(blockData);

        if (activePiece.inFlight > 0) activePiece.inFlight--;

        boolean newBlock = activePiece.recordBlock(blockOffset, blockData);
        if (!newBlock) {
            requestMoreBlocks();
            return;
        }

        if (!activePiece.isComplete()) {
            requestMoreBlocks();
            return;
        }

        byte[] pieceData = activePiece.buffer;
        int completedPiece = activePiece.pieceIndex;
        activePiece = null;

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] calculatedHash = md.digest(pieceData);
        byte[] expectedHash = metadata.getExpectedHashForPiece(completedPiece);

        if (!Arrays.equals(calculatedHash, expectedHash)) {
            System.err.println("\nHash mismatch for piece " + completedPiece + " from " + peer);
            pieceManager.markPieceFailed(completedPiece);
            requestMoreBlocks();
            return;
        }

        if (pieceManager.markPieceCompleted(completedPiece)) {
            diskExecutor.submit(() -> {
                try {
                    storage.writePiece(completedPiece, pieceData);
                } catch (Exception e) {
                    System.err.println("\nDisk write failed for piece " + completedPiece + ": " + e.getMessage());
                    pieceManager.markPieceFailed(completedPiece);
                }
            });
        }

        requestMoreBlocks();
    }

    private static boolean isExpectedConnectionError(Exception e) {
        return e instanceof IOException;
    }

    private boolean isValidPieceIndex(int pieceIndex) {
        return pieceIndex >= 0 && pieceIndex < metadata.totalPieces;
    }

    private void handshake() throws Exception {
        byte[] handshake = new byte[68];
        int offset = 0;
        handshake[offset++] = 19;
        byte[] pstr = "BitTorrent protocol".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(pstr, 0, handshake, offset, pstr.length);
        offset += pstr.length;
        offset += 8;
        System.arraycopy(metadata.infoHashBytes, 0, handshake, offset, metadata.infoHashBytes.length);
        offset += metadata.infoHashBytes.length;
        System.arraycopy(myPeerId, 0, handshake, offset, myPeerId.length);

        out.write(handshake);
        out.flush();

        byte[] response = new byte[68];
        in.readFully(response);
        if (response[0] != 19 || !Arrays.equals(metadata.infoHashBytes, Arrays.copyOfRange(response, 28, 48))) {
            throw new RuntimeException("Invalid handshake from " + peer);
        }
    }

    private void sendInterested() throws Exception {
        out.writeInt(1);
        out.writeByte(MessageType.INTERESTED.getValue());
        out.flush();
    }

    private void sendRequest(int pieceIndex, int blockOffset, int blockLength) throws Exception {
        out.writeInt(13);
        out.writeByte(MessageType.REQUEST.getValue());
        out.writeInt(pieceIndex);
        out.writeInt(blockOffset);
        out.writeInt(blockLength);
        out.flush();
    }

    private static final class ActivePieceDownload {
        final int pieceIndex;
        final int pieceSize;
        final byte[] buffer;
        final BitSet receivedBlocks = new BitSet();
        final int totalBlocks;
        int inFlight;

        ActivePieceDownload(int pieceIndex, int pieceSize) {
            this.pieceIndex = pieceIndex;
            this.pieceSize = pieceSize;
            this.buffer = new byte[pieceSize];
            this.totalBlocks = (pieceSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
        }

        void resetPipeline() {
            inFlight = 0;
        }

        boolean isValidBlock(int blockOffset, int dataLength) {
            if (blockOffset < 0 || blockOffset >= pieceSize || dataLength <= 0) return false;
            if (blockOffset % BLOCK_SIZE != 0) return false;
            return dataLength == blockLengthAt(blockOffset);
        }

        int blockLengthAt(int blockOffset) {
            return Math.min(BLOCK_SIZE, pieceSize - blockOffset);
        }

        int blockIndexForOffset(int blockOffset) {
            return blockOffset / BLOCK_SIZE;
        }

        boolean recordBlock(int blockOffset, byte[] blockData) {
            int blockIndex = blockIndexForOffset(blockOffset);
            if (receivedBlocks.get(blockIndex)) return false;
            System.arraycopy(blockData, 0, buffer, blockOffset, blockData.length);
            receivedBlocks.set(blockIndex);
            return true;
        }

        boolean isComplete() {
            return receivedBlocks.cardinality() == totalBlocks;
        }

        int nextBlockOffsetToRequest() {
            for (int blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
                if (!receivedBlocks.get(blockIndex)) return blockIndex * BLOCK_SIZE;
            }
            return -1;
        }
    }
}
