package pl.simplysejm.infrastructure;

/** A use-case failure independent of its delivery protocol. */
public class UseCaseException extends RuntimeException {
    public enum Kind { INVALID_INPUT, NOT_FOUND, UNAVAILABLE }
    private final Kind kind;
    public UseCaseException(Kind kind, String detail) { super(detail); this.kind = kind; }
    public UseCaseException(Kind kind, String detail, Throwable cause) { super(detail, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
