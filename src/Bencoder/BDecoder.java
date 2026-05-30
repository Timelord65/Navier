package Bencoder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class BDecoder {
    private final PushbackInputStream in;

    public BDecoder(InputStream in) {
        this.in = new PushbackInputStream(in);
    }

    public BElement<?> decode() throws IOException {
        int b = in.read();
        if (b == -1) return null; // EOF

        if (b == 'i') return decodeInt();
        if (b == 'l') return decodeList();
        if (b == 'd') return decodeDict();

        // If it starts with a digit, it's a string length
        if (b >= '0' && b <= '9') {
            in.unread(b);
            return decodeString();
        }

        throw new IOException("Invalid bencode prefix: " + (char) b);
    }

    private BInteger decodeInt() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != 'e') {
            if (b == -1) throw new EOFException("Unexpected EOF while reading integer");
            sb.append((char) b);
        }
        return new BInteger(Long.parseLong(sb.toString()));
    }

    private BString decodeString() throws IOException {
        StringBuilder lenSb = new StringBuilder();
        int b;
        while ((b = in.read()) != ':') {
            if (b == -1) throw new EOFException("Unexpected EOF while reading string length");
            lenSb.append((char) b);
        }

        int len = Integer.parseInt(lenSb.toString());
        byte[] data = new byte[len];
        int bytesRead = 0;

        while (bytesRead < len) {
            int r = in.read(data, bytesRead, len - bytesRead);
            if (r == -1) throw new EOFException("Unexpected EOF while reading string data");
            bytesRead += r;
        }

        return new BString(data);
    }

    private BList decodeList() throws IOException {
        List<BElement<?>> list = new ArrayList<>();
        int b;
        while ((b = in.read()) != 'e') {
            if (b == -1) throw new EOFException("Unexpected EOF in list");
            in.unread(b);
            list.add(decode());
        }
        return new BList(list);
    }

    private BDict decodeDict() throws IOException {
        TreeMap<String, BElement<?>> map = new TreeMap<>();
        int b;
        while ((b = in.read()) != 'e') {
            if (b == -1) throw new EOFException("Unexpected EOF in dictionary");
            in.unread(b);

            BString keyElement = decodeString();
            String key = new String(keyElement.getValue(), StandardCharsets.UTF_8);
            BElement<?> value = decode();

            map.put(key, value);
        }
        return new BDict(map);
    }
}
