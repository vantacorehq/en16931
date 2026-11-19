package en16931;

public final class EnException extends RuntimeException {
    public final String code;

    public EnException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }
}
