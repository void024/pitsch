package com.pitsch.backend.mail;

/** A transactional email. {@code html} is optional; the plain-text part is always sent. */
public record MailMessage(String to, String subject, String text, String html) {
}
