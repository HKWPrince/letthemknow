package io.letthemknow.dispatch;

/** TRANSIENT → re-enqueue with backoff (max 3 attempts); TERMINAL → mark FAILED immediately. */
public enum ErrorClass {
    TRANSIENT,
    TERMINAL
}
