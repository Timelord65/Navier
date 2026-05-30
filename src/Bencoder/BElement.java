package Bencoder;

public sealed interface BElement<T> permits BInteger, BDict, BString, BList {
    BencodeType getType();
    T getValue();
    byte[] encode();
}
