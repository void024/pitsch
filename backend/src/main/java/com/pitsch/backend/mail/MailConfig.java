package com.pitsch.backend.mail;

import com.pitsch.backend.config.PitschProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class MailConfig {

    @Bean
    public MailService mailService(PitschProperties props, ObjectProvider<JavaMailSender> javaMailSender) {
        if ("smtp".equalsIgnoreCase(props.getMail().getProvider())) {
            JavaMailSender sender = javaMailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("MAIL_PROVIDER=smtp requires SMTP_HOST (spring.mail.host)");
            }
            return new SmtpMailService(sender, props.getMail().getFrom());
        }
        return new LogMailService();
    }
}
