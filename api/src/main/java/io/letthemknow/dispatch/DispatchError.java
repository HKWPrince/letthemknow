package io.letthemknow.dispatch;

/** Classified delivery failure; {@code code} and {@code message} are stored on the recipient row. */
public record DispatchError(ErrorClass errorClass, String code, String message) {

    public boolean isTransient() {
        return errorClass == ErrorClass.TRANSIENT;
    }

    public static DispatchError transientError(String code, String message) {
        return new DispatchError(ErrorClass.TRANSIENT, code, message);
    }

    public static DispatchError terminal(String code, String message) {
        return new DispatchError(ErrorClass.TERMINAL, code, message);
    }
}
