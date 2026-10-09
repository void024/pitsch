package com.pitsch.backend.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** SMTP delivery (works with SES, Postmark, SendGrid, Mailgun, Google Workspace SMTP relay, ...). */
public class SmtpMailService implements MailService {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailService(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.html() != null, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.html() != null) {
                helper.setText(message.text(), message.html());
            } else {
                helper.setText(message.text(), false);
            }
            sender.send(mime);
        } catch (MessagingException | MailException e) {
            throw new MailDeliveryException("Could not send email", e);
        }
    }

    @Override
    public String kind() {
        return "smtp";
    }
}
