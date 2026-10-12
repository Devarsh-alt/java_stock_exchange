package exchange;

/** Business-level exception for validation and rule violations. */
public class ExchangeException extends RuntimeException {
    public ExchangeException(String message)                  { super(message); }
    public ExchangeException(String message, Throwable cause) { super(message, cause); }
}
