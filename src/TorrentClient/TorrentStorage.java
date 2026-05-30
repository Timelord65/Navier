package TorrentClient;

import java.io.IOException;
import java.util.BitSet;

public interface TorrentStorage {
    void writePiece(int pieceIndex, byte[] data) throws IOException;
    BitSet buildInitialBitSet(TorrentMetadata metadata) throws Exception;
    void close() throws IOException;
}
