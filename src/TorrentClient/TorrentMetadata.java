package TorrentClient;

import Bencoder.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TorrentMetadata {
    public final String announceUrl;
    public final List<String> announceUrls;
    public final byte[] infoHashBytes;
    public final String urlEncodedInfoHash;
    public final long pieceLength;
    public final String name;
    public final long totalSize;

    public final int totalPieces;
    public final byte[] piecesHashes;
    public final boolean multiFile;

    public TorrentMetadata(File torrentFile) throws Exception {
        byte[] allBytes = Files.readAllBytes(torrentFile.toPath());
        BDecoder decoder = new BDecoder(new ByteArrayInputStream(allBytes));
        BElement<?> root = decoder.decode();

        if (!(root instanceof BDict rootDict)) {
            throw new IllegalArgumentException("Root of torrent file must be a Bencoded Dictionary");
        }

        BString announceElem = (BString) rootDict.getValue().get("announce");
        if (announceElem == null) {
            throw new IllegalArgumentException("Torrent missing announce URL");
        }
        this.announceUrl = new String(announceElem.getValue(), StandardCharsets.UTF_8);
        this.announceUrls = parseAnnounceUrls(rootDict, this.announceUrl);

        BDict infoDict = (BDict) rootDict.getValue().get("info");
        if (infoDict == null) {
            throw new IllegalArgumentException("Torrent missing info dictionary");
        }

        BInteger pieceLenElem = (BInteger) infoDict.getValue().get("piece length");
        this.pieceLength = pieceLenElem.getValue();

        BString nameElem = (BString) infoDict.getValue().get("name");
        this.name = new String(nameElem.getValue(), StandardCharsets.UTF_8);

        BInteger lengthElem = (BInteger) infoDict.getValue().get("length");
        if (lengthElem != null) {
            this.totalSize = lengthElem.getValue();
            this.multiFile = false;
        } else {
            long sum = 0;
            BList filesList = (BList) infoDict.getValue().get("files");
            if (filesList == null) {
                throw new IllegalArgumentException("Torrent info has neither length nor files");
            }
            this.multiFile = true;
            for (BElement<?> fileElem : filesList.getValue()) {
                BDict fileDict = (BDict) fileElem;
                BInteger fileLen = (BInteger) fileDict.getValue().get("length");
                sum += fileLen.getValue();
            }
            this.totalSize = sum;
        }

        BString piecesElem = (BString) infoDict.getValue().get("pieces");
        this.piecesHashes = piecesElem.getValue();
        this.totalPieces = this.piecesHashes.length / 20;

        byte[] exactInfoBytes = BencodeUtil.extractInfoDictBytes(allBytes);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        this.infoHashBytes = md.digest(exactInfoBytes);
        this.urlEncodedInfoHash = urlEncodeHash(this.infoHashBytes);
    }

    public long getActualPieceSize(int pieceIndex) {
        if (pieceIndex == totalPieces - 1) {
            long remainder = totalSize % pieceLength;
            return remainder == 0 ? pieceLength : remainder;
        }
        return pieceLength;
    }

    public static String urlEncodeHash(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    public byte[] getExpectedHashForPiece(int pieceIndex) {
        byte[] expectedHash = new byte[20];
        System.arraycopy(piecesHashes, pieceIndex * 20, expectedHash, 0, 20);
        return expectedHash;
    }

    private static List<String> parseAnnounceUrls(BDict rootDict, String primaryAnnounce) {
        Set<String> urls = new LinkedHashSet<>();
        urls.add(primaryAnnounce);

        BElement<?> announceListElem = rootDict.getValue().get("announce-list");
        if (announceListElem instanceof BList tiers) {
            for (BElement<?> tierElem : tiers.getValue()) {
                if (tierElem instanceof BList tier) {
                    for (BElement<?> urlElem : tier.getValue()) {
                        if (urlElem instanceof BString urlStr) {
                            urls.add(new String(urlStr.getValue(), StandardCharsets.UTF_8));
                        }
                    }
                } else if (tierElem instanceof BString urlStr) {
                    urls.add(new String(urlStr.getValue(), StandardCharsets.UTF_8));
                }
            }
        }

        return new ArrayList<>(urls);
    }
}
