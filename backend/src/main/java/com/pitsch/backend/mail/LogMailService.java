package com.pitsch.backend.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development/demo only: prints emails to the log so links (verification, reset, invitations) can be followed locally.
 * ProductionConfigValidator refuses to start production with this implementation.
 */
public class LogMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(LogMailService.class);

    @Override
    public void send(MailMessage message) {
        log.info("[mail:log] to={} subject=\"{}\"\n{}", message.to(), message.subject(), message.text());
    }

    @Override
    public String kind() {
        return "log";
    }
}
