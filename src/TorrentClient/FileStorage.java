package TorrentClient;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;

public class FileStorage implements TorrentStorage {

    private final RandomAccessFile raf;
    private final long pieceLength;

    public FileStorage(File outputFile, TorrentMetadata metadata) throws IOException {
        this.pieceLength = metadata.pieceLength;
        this.raf = new RandomAccessFile(outputFile, "rw");
        this.raf.setLength(metadata.totalSize);
    }

    @Override
    public void writePiece(int pieceIndex, byte[] data) throws IOException {
        raf.seek((long) pieceIndex * pieceLength);
        raf.write(data);
    }

    @Override
    public BitSet buildInitialBitSet(TorrentMetadata metadata) throws Exception {
        BitSet verified = new BitSet();
        if (raf.length() < metadata.totalSize) {
            return verified;
        }
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        for (int i = 0; i < metadata.totalPieces; i++) {
            int size = (int) metadata.getActualPieceSize(i);
            long offset = (long) i * metadata.pieceLength;
            if (offset + size > raf.length()) {
                continue;
            }
            byte[] buf = new byte[size];
            raf.seek(offset);
            raf.readFully(buf);
            md.reset();
            byte[] hash = md.digest(buf);
            if (Arrays.equals(hash, metadata.getExpectedHashForPiece(i))) {
                verified.set(i);
            }
        }
        return verified;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
