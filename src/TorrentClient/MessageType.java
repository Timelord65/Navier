package TorrentClient;

public enum MessageType  {
    KEEP_ALIVE(-1),
    CHOKE(0),
    UNCHOKE(1),
    INTERESTED(2),
    NOT_INTERESTED(3),
    HAVE(4),
    BITFIELD(5),
    REQUEST(6),
    PIECE(7),
    CANCEL(8),
    PORT(9);

    private final byte value;

    private static final MessageType[] CACHE = new MessageType[10];

    static {
        for (MessageType type : values()) {
            if (type.value >= 0) {
                CACHE[type.value] = type;
            }
        }
    }

    MessageType(int value) {
        this.value = (byte) value;
    }
    MessageType(byte value) { this.value = value; }

    public int getValue() {
        return value;
    }

    public static MessageType fromValue(int value) {
        if (value == -1) return KEEP_ALIVE;
        if(value >= 0 && value < CACHE.length) {
            return CACHE[value];
        }
        throw new IllegalArgumentException("Invalid MessageType value. Passed value: " + value);
    }

}
