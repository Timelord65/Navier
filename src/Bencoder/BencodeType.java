package Bencoder;

public enum BencodeType {
    STRING(':'),
    INTEGER('i'),
    LIST('l'),
    DICTIONARY('d');

    private final char indicator;

    BencodeType(char indicator){
        this.indicator = indicator;
    }

    char getIndicator(BencodeType type){
        return type.indicator;
    }

    public static BencodeType fromIndicator(char b){
        if(Character.isDigit(b)){
            return STRING;
        }
        for(BencodeType type: BencodeType.values()){
            if(type.getIndicator(type) == b){
                return type;
            }
        }
        throw new IllegalArgumentException("Illegal Argument. Found bencode indicator: " + b);
    }
}
