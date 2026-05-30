package Bencoder;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public record BDict(Map<String, BElement<?>> value) implements BElement<Map<String, BElement<?>>> {
    @Override public Map<String, BElement<?>> getValue() { return value; }
    @Override public BencodeType getType() { return BencodeType.DICTIONARY; }

    @Override
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('d');

        for (Map.Entry<String, BElement<?>> entry : value.entrySet()) {
            try {
                BString key = new BString(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(key.encode());
                out.write(entry.getValue().encode());
            } catch (IOException e) {
                throw new RuntimeException("Failed to encode dictionary entry", e);
            }
        }

        out.write('e');
        return out.toByteArray();
    }
}