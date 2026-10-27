package com.pitsch.backend.mail;

/** Sends Pitsch's own transactional email (not the investor's Gmail — that goes through EmailProvider). */
public interface MailService {

    /** @throws MailDeliveryException if the message could not be handed to the mail server */
    void send(MailMessage message);

    /** "smtp" or "log". */
    String kind();

    class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
