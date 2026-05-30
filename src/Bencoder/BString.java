package Bencoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record BString(byte[] value) implements BElement<byte[]> {
    @Override
    public byte[] getValue(){
        return value;
    }

    @Override
    public BencodeType getType(){
        return BencodeType.STRING;
    }

    @Override
    public byte[] encode(){
        byte[] prefix = String.valueOf(value.length).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(prefix.length + 1 + value.length);
        return buffer.put(prefix).put((byte) ':').put(value).array();
    }

    public String asString(){
        return new String(value, StandardCharsets.UTF_8);
    }
}
