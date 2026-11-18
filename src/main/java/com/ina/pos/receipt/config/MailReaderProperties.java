package com.ina.pos.receipt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "mail.reader")
public class MailReaderProperties {

    /**
     * Mail server host (e.g. imap.gmail.com, outlook.office365.com, mail.yourdomain.com)
     */
    private String host = "imap.gmail.com";

    /**
     * Mail server port (e.g. 993 for IMAPS, 143 for IMAP, 995 for POP3S)
     */
    private int port = 993;

    /**
     * Mail protocol (imaps, imap, pop3s, pop3)
     */
    private String protocol = "imaps";

    /**
     * Mail account username / email address
     */
    private String username;

    /**
     * Mail account password or App Password
     */
    private String password;

    /**
     * Mailbox folder to monitor (default INBOX)
     */
    private String folder = "INBOX";

    /**
     * SSL enabled
     */
    private boolean ssl = true;

    /**
     * Maximum messages to inspect per check
     */
    private int maxMessages = 25;

    /**
     * Connection timeout in milliseconds
     */
    private int connectionTimeout = 10000;

    /**
     * Read timeout in milliseconds
     */
    private int readTimeout = 10000;
}
