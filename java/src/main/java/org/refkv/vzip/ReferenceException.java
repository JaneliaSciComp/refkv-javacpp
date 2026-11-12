package org.refkv.vzip;

/** A failure of a refkv store, with its {@link ErrorClass}. */
public class ReferenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorClass errorClass;

    public ReferenceException(ErrorClass errorClass, String message) {
        super(message);
        this.errorClass = errorClass;
    }

    public ReferenceException(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    /** The message without the class prefix. */
    public String detail() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        return errorClass.name().toLowerCase() + " error: " + super.getMessage();
    }
}
