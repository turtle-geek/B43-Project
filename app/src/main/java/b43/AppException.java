package b43;

/** An error caused by user input or a violated business rule; shown to the user as-is. */
public class AppException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AppException(String message) {
        super(message);
    }
}
