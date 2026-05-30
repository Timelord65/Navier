package Bencoder;

import java.nio.charset.StandardCharsets;

public final class BencodeUtil {
    private BencodeUtil() {
    }

    /**
     * Returns the raw bencoded bytes of the top-level "info" dictionary value.
     */
    public static byte[] extractInfoDictBytes(byte[] torrentBytes) {
        int pos = 0;
        if (torrentBytes[pos++] != 'd') {
            throw new IllegalArgumentException("Torrent root is not a dictionary");
        }

        while (pos < torrentBytes.length && torrentBytes[pos] != 'e') {
            String key = readStringAt(torrentBytes, pos);
            pos = skipBencodeElement(torrentBytes, pos);

            if ("info".equals(key)) {
                int valueStart = pos;
                pos = skipBencodeElement(torrentBytes, pos);
                byte[] infoBytes = new byte[pos - valueStart];
                System.arraycopy(torrentBytes, valueStart, infoBytes, 0, infoBytes.length);
                return infoBytes;
            }

            pos = skipBencodeElement(torrentBytes, pos);
        }

        throw new IllegalArgumentException("Torrent file has no info dictionary");
    }

    private static String readStringAt(byte[] bytes, int start) {
        int colon = start;
        while (bytes[colon] != ':') {
            colon++;
        }
        int len = Integer.parseInt(new String(bytes, start, colon - start, StandardCharsets.US_ASCII));
        int dataStart = colon + 1;
        return new String(bytes, dataStart, len, StandardCharsets.UTF_8);
    }

    private static int skipBencodeElement(byte[] bytes, int pos) {
        char prefix = (char) bytes[pos];
        if (prefix == 'i') {
            while (bytes[pos++] != 'e') {
            }
            return pos;
        }
        if (prefix == 'l') {
            pos++;
            while (bytes[pos] != 'e') {
                pos = skipBencodeElement(bytes, pos);
            }
            return pos + 1;
        }
        if (prefix == 'd') {
            pos++;
            while (bytes[pos] != 'e') {
                pos = skipBencodeElement(bytes, pos); // key
                pos = skipBencodeElement(bytes, pos); // value
            }
            return pos + 1;
        }
        if (prefix >= '0' && prefix <= '9') {
            int colon = pos;
            while (bytes[colon] != ':') {
                colon++;
            }
            int len = Integer.parseInt(new String(bytes, pos, colon - pos, StandardCharsets.US_ASCII));
            return colon + 1 + len;
        }
        throw new IllegalArgumentException("Invalid bencode at offset " + pos);
    }
}
