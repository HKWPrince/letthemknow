package io.letthemknow.dispatch;

/** Result of trying to deliver to one recipient. */
public sealed interface SendOutcome {

    record Sent(String externalMessageId) implements SendOutcome {}

    record Failed(DispatchError error) implements SendOutcome {}
}
