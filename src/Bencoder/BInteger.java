package Bencoder;

import java.nio.charset.StandardCharsets;

public record BInteger(Long value) implements BElement<Long> {
    @Override public Long getValue() { return value; }
    @Override public BencodeType getType() { return BencodeType.INTEGER; }

    @Override
    public byte[] encode() {
        return ("i" + value + "e").getBytes(StandardCharsets.US_ASCII);
    }
}