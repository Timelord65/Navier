package Bencoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

public record BList(List<BElement<?>> value) implements BElement<List<BElement<?>>> {
    @Override public List<BElement<?>> getValue() { return value; }
    @Override public BencodeType getType() { return BencodeType.LIST; }

    @Override
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('l');
        for (BElement<?> element : value) {
            try {
                out.write(element.encode());
            } catch (IOException e) {
                throw new RuntimeException("Failed to encode list item", e);
            }
        }
        out.write('e');
        return out.toByteArray();
    }
}