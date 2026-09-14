package io.letthemknow.campaign;

/** User-facing CSV import failure; the message is stored in {@code campaigns.import_error}. */
class ImportException extends RuntimeException {

    ImportException(String message) {
        super(message);
    }

    ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
