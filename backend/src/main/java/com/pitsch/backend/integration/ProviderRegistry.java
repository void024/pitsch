package com.pitsch.backend.integration;

import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.integration.google.GmailProvider;
import com.pitsch.backend.integration.google.GoogleCalendarProvider;
import com.pitsch.backend.integration.google.GoogleSheetsProvider;
import com.pitsch.backend.integration.mock.MockCalendarProvider;
import com.pitsch.backend.integration.mock.MockEmailProvider;
import com.pitsch.backend.integration.mock.MockSpreadsheetProvider;
import com.pitsch.backend.integration.provider.CalendarProvider;
import com.pitsch.backend.integration.provider.EmailProvider;
import com.pitsch.backend.integration.provider.SpreadsheetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Selects provider implementations by mode. Real Google providers are the default for every mode except
 * PITSCH_MODE=demo, which must be chosen explicitly. There is no silent fallback to mocks.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final EmailProvider email;
    private final CalendarProvider calendar;
    private final SpreadsheetProvider sheets;

    public ProviderRegistry(PitschProperties props, GmailProvider gmail, GoogleCalendarProvider googleCalendar,
                            GoogleSheetsProvider googleSheets) {
        if (props.isDemo()) {
            log.warn("PITSCH_MODE=demo: Gmail, Calendar and Sheets are MOCKED. Nothing is sent to Google.");
            this.email = new MockEmailProvider();
            this.calendar = new MockCalendarProvider();
            this.sheets = new MockSpreadsheetProvider();
        } else {
            this.email = gmail;
            this.calendar = googleCalendar;
            this.sheets = googleSheets;
        }
    }

    public EmailProvider email() {
        return email;
    }

    public CalendarProvider calendar() {
        return calendar;
    }

    public SpreadsheetProvider sheets() {
        return sheets;
    }

    public boolean demo() {
        return email.isDemo();
    }
}
