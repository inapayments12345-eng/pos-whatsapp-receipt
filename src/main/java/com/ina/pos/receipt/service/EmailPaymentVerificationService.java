package com.ina.pos.receipt.service;

import com.ina.pos.receipt.config.MailReaderProperties;
import com.ina.pos.receipt.config.UpiPaymentProperties;
import com.ina.pos.receipt.dao.UpiTransactionRepository;
import com.ina.pos.receipt.dao.entity.UpiTransaction;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailPaymentVerificationService {

    private final MailReaderProperties mailProperties;
    private final UpiPaymentProperties upiProperties;
    private final UpiTransactionRepository upiTransactionRepository;

    // Regex patterns for extracting bank details from notification emails
    private static final Pattern UTR_PATTERN = Pattern.compile("(?i)(?:UTR|RRN|Ref(?:erence)?(?:\\s*No)?|Txn(?:\\s*Id)?)[\\s:#-]*([0-9A-Za-z]{8,25})");
    private static final Pattern NUMERIC_UTR_PATTERN = Pattern.compile("\\b(\\d{12})\\b");
    private static final Pattern VPA_PATTERN = Pattern.compile("\\b([a-zA-Z0-9.\\-_]{2,50}@[a-zA-Z]{2,20})\\b");

    // In-memory cache for recent email scans (valid for 2.5 seconds) to avoid throttling IMAP logins
    private static final long CACHE_TTL_MS = 2500L;
    private long lastScanTime = 0L;
    private final List<CachedEmailMessage> cachedMessages = new ArrayList<>();
    private final Object scanLock = new Object();

    @Data
    @AllArgsConstructor
    private static class CachedEmailMessage {
        private String subject;
        private String body;
        private String combined;
        private Date sentDate;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class VerificationResult {
        private String status; // PENDING, SUCCESS, FAILED
        private String bankReference;
        private String transactionId;
        private String payerVpa;
        private String message;

        public static VerificationResult pending(String message) {
            return VerificationResult.builder().status("PENDING").message(message).build();
        }

        public static VerificationResult success(String bankRef, String txnId, String payerVpa, String message) {
            return VerificationResult.builder()
                    .status("SUCCESS")
                    .bankReference(bankRef)
                    .transactionId(txnId)
                    .payerVpa(payerVpa)
                    .message(message)
                    .build();
        }

        public static VerificationResult failed(String message) {
            return VerificationResult.builder().status("FAILED").message(message).build();
        }
    }

    /**
     * Checks if email confirmation for transaction has arrived.
     * Enforces the 3-minute timeout rule: if 3 minutes elapsed with no confirmation email, marks FAILED.
     */
    public VerificationResult checkPaymentStatus(UpiTransaction tx) {
        long timeoutMillis = upiProperties.getTimeoutSeconds() * 1000L; // 180,000 ms (3 minutes)
        long elapsedMillis = System.currentTimeMillis() - tx.getPaymentTime().getTime();

        String rawUser = mailProperties.getUsername();
        String rawPass = mailProperties.getPassword();

        // 1. If mail reader credentials are not configured, check elapsed timeout
        if (rawUser == null || rawUser.trim().isEmpty() || rawPass == null || rawPass.trim().isEmpty()) {
            if (elapsedMillis >= timeoutMillis) {
                log.info("Order {} reached {}s (3 min) timeout without email configuration. Marking as FAILED.",
                        tx.getOrderId(), upiProperties.getTimeoutSeconds());
                return VerificationResult.failed("Payment timed out after 3 minutes - no confirmation received");
            }
            return VerificationResult.pending("Waiting for payment... (timeout in " +
                    Math.max(0, (timeoutMillis - elapsedMillis) / 1000) + "s)");
        }

        // 2. Connect to mail store or scan cache for matching transaction
        try {
            VerificationResult mailResult = scanEmailsForTransaction(tx);
            if (mailResult != null && !"PENDING".equalsIgnoreCase(mailResult.getStatus())) {
                return mailResult;
            }
        } catch (Exception ex) {
            log.error("Error while checking email for order {}: {}", tx.getOrderId(), ex.getMessage());
        }

        // 3. Check if 3-minute (180s) timeout has elapsed
        if (elapsedMillis >= timeoutMillis) {
            log.info("Order {} reached 3-minute timeout with no confirmation email. Marking FAILED.", tx.getOrderId());
            return VerificationResult.failed("Payment timed out after 3 minutes - no confirmation email received");
        }

        return VerificationResult.pending("Awaiting payment confirmation email (timeout in " +
                Math.max(0, (timeoutMillis - elapsedMillis) / 1000) + "s)...");
    }

    /**
     * Fast in-memory check against recently cached email messages.
     * Never performs blocking IMAP network operations, returning in < 1 millisecond.
     */
    public VerificationResult checkPaymentStatusFast(UpiTransaction tx) {
        long timeoutMillis = upiProperties.getTimeoutSeconds() * 1000L;
        long elapsedMillis = System.currentTimeMillis() - tx.getPaymentTime().getTime();

        List<CachedEmailMessage> messagesToScan;
        synchronized (scanLock) {
            messagesToScan = new ArrayList<>(cachedMessages);
        }

        if (!messagesToScan.isEmpty()) {
            VerificationResult match = matchTransactionInMessages(tx, messagesToScan);
            if (match != null && !"PENDING".equalsIgnoreCase(match.getStatus())) {
                return match;
            }
        }

        if (elapsedMillis >= timeoutMillis) {
            return VerificationResult.failed("Payment timed out after 3 minutes - no confirmation email received");
        }

        return VerificationResult.pending("Awaiting payment confirmation...");
    }

    private VerificationResult scanEmailsForTransaction(UpiTransaction tx) {
        List<CachedEmailMessage> messagesToScan;
        synchronized (scanLock) {
            long now = System.currentTimeMillis();
            if (now - lastScanTime > CACHE_TTL_MS || cachedMessages.isEmpty()) {
                fetchRecentMessagesFromImap();
            }
            messagesToScan = new ArrayList<>(cachedMessages);
        }

        if (messagesToScan.isEmpty()) {
            return null;
        }

        return matchTransactionInMessages(tx, messagesToScan);
    }

    private VerificationResult matchTransactionInMessages(UpiTransaction tx, List<CachedEmailMessage> messagesToScan) {
        String orderId = tx.getOrderId();
        BigDecimal amount = tx.getAmount();
        long txCreatedTime = tx.getPaymentTime().getTime();

        for (CachedEmailMessage item : messagesToScan) {
            // Skip messages sent more than 2 minutes before the transaction was initiated
            if (item.getSentDate() != null) {
                if (item.getSentDate().getTime() < (txCreatedTime - 120_000L)) {
                    continue;
                }
            }

            String combined = item.getCombined();
            String subject = item.getSubject();

            boolean matchesOrder = orderId != null && !orderId.isEmpty() && combined.contains(orderId.toLowerCase());
            boolean matchesAmount = isAmountMatching(combined, amount);
            boolean hasUpiCreditMarkers = hasCreditKeywords(combined);

            if (matchesOrder || (matchesAmount && hasUpiCreditMarkers)) {
                log.info("Matching payment email found for order {}! Subject: {}", orderId, subject);

                // Check for failure / decline
                if (combined.contains("failed") || combined.contains("declined") ||
                        combined.contains("rejected") || combined.contains("reversed")) {
                    return VerificationResult.failed("Payment declined or failed according to bank email alert");
                }

                // Check for credit / success
                if (combined.contains("credited") || combined.contains("received") ||
                        combined.contains("successful") || combined.contains("success") ||
                        combined.contains("paid") || combined.contains("completed") ||
                        combined.contains("slice")) {

                    String bankRef = extractUtr(combined);
                    String payerVpa = extractVpa(combined);

                    // Check if this bank reference was already used by another order
                    if (bankRef != null && !bankRef.isEmpty()) {
                        Optional<UpiTransaction> existing = upiTransactionRepository.findByBankReference(bankRef);
                        if (existing.isPresent() && !existing.get().getOrderId().equalsIgnoreCase(orderId)) {
                            log.info("Bank reference {} in email was already claimed by order {}. Skipping.",
                                    bankRef, existing.get().getOrderId());
                            continue;
                        }
                    }

                    String txnId = (bankRef != null && !bankRef.isEmpty()) ? bankRef : orderId;

                    return VerificationResult.success(
                            bankRef != null ? bankRef : "",
                            txnId,
                            payerVpa != null ? payerVpa : "",
                            "Payment confirmed via email alert: " + subject
                    );
                }
            }
        }

        return null;
    }

    private void fetchRecentMessagesFromImap() {
        String host = resolveHost();
        int port = resolvePort(host);
        String protocol = resolveProtocol(host, port);
        String username = mailProperties.getUsername().trim();
        String password = resolvePassword();

        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".ssl.enable", "true");
        props.put("mail." + protocol + ".ssl.trust", "*");
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(mailProperties.getConnectionTimeout()));
        props.put("mail." + protocol + ".timeout", String.valueOf(mailProperties.getReadTimeout()));
        props.put("mail." + protocol + ".partialfetch", "false");

        Session session = Session.getInstance(props);
        Store store = null;
        Folder folder = null;

        try {
            log.info("📧 [Email Reader] Polling Gmail INBOX ({}:{}) for user: {}...", host, port, username);
            store = session.getStore(protocol);
            store.connect(host, port, username, password);

            folder = store.getFolder(mailProperties.getFolder());
            if (!folder.exists()) {
                log.warn("Mail folder {} does not exist", mailProperties.getFolder());
                return;
            }

            folder.open(Folder.READ_ONLY);
            int total = folder.getMessageCount();
            if (total == 0) {
                cachedMessages.clear();
                lastScanTime = System.currentTimeMillis();
                log.info("📧 [Email Reader] Inbox is currently empty (0 messages).");
                return;
            }

            int count = Math.min(Math.min(mailProperties.getMaxMessages(), 10), total);
            int start = Math.max(1, total - count + 1);
            Message[] messages = folder.getMessages(start, total);

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(messages, fp);

            List<CachedEmailMessage> fresh = new ArrayList<>();
            long fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000L);

            // Iterate newest to oldest
            for (int i = messages.length - 1; i >= 0; i--) {
                Message msg = messages[i];
                try {
                    Date date = msg.getSentDate() != null ? msg.getSentDate() : msg.getReceivedDate();
                    // Skip downloading heavy bodies for emails older than 15 minutes
                    if (date != null && date.getTime() < fifteenMinsAgo) {
                        continue;
                    }
                    String subject = msg.getSubject() != null ? msg.getSubject() : "";
                    String body = getTextFromMessage(msg);
                    String combined = (subject + " " + body).toLowerCase();
                    fresh.add(new CachedEmailMessage(subject, body, combined, date));
                } catch (Exception ex) {
                    log.debug("Failed reading single email message: {}", ex.getMessage());
                }
            }

            cachedMessages.clear();
            cachedMessages.addAll(fresh);
            lastScanTime = System.currentTimeMillis();
            log.info("📧 [Email Reader] Connected to Gmail INBOX successfully! Total messages: {}, inspected latest {} messages.", total, fresh.size());

        } catch (Exception e) {
            log.error("Failed to connect to IMAP server {}:{} for user {}: {}", host, port, username, e.getMessage());
        } finally {
            if (folder != null && folder.isOpen()) {
                try { folder.close(false); } catch (Exception ignored) {}
            }
            if (store != null && store.isConnected()) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isAmountMatching(String text, BigDecimal amount) {
        if (amount == null) return false;
        String plain = amount.stripTrailingZeros().toPlainString();
        String twoDec = String.format(Locale.US, "%.2f", amount);
        String whole = String.valueOf(amount.longValue());
        String withComma = String.format(Locale.US, "%,.2f", amount);

        // Match patterns like "?1", "₹1", "rs. 1", "inr 1", "received ?1", "credited 1"
        // In ASCII headers, ₹ is often received as ? (e.g. "Received ?1 in your slice account")
        String regex = "(?i)(?:₹|\\?|rs\\.?|inr|received|credited|amt|amount|for)\\s*(?:" +
                Pattern.quote(plain) + "|" + Pattern.quote(twoDec) + "|" + Pattern.quote(whole) + "|" + Pattern.quote(withComma) + ")\\b";

        if (Pattern.compile(regex).matcher(text).find()) {
            return true;
        }

        if (text.contains("?" + plain) || text.contains("₹" + plain) ||
                text.contains("rs." + plain) || text.contains("rs " + plain) ||
                text.contains("inr " + plain) || text.contains("?" + twoDec) ||
                text.contains("₹" + twoDec) || text.contains("inr " + twoDec)) {
            return true;
        }

        return false;
    }

    private boolean hasCreditKeywords(String text) {
        return (text.contains("upi") || text.contains("slice") || text.contains("vpa") ||
                text.contains("credited") || text.contains("received") || text.contains("bank account"));
    }

    private String resolveHost() {
        String host = mailProperties.getHost();
        if (host == null || host.trim().isEmpty()) {
            return "imap.gmail.com";
        }
        host = host.trim();
        if (host.equalsIgnoreCase("smtp.gmail.com")) {
            return "imap.gmail.com";
        }
        if (host.toLowerCase().startsWith("smtp.")) {
            return "imap." + host.substring(5);
        }
        return host;
    }

    private int resolvePort(String host) {
        int port = mailProperties.getPort();
        if (port == 587 || port == 25 || port == 465 || port <= 0) {
            return 993;
        }
        return port;
    }

    private String resolveProtocol(String host, int port) {
        String protocol = mailProperties.getProtocol();
        if (protocol == null || protocol.trim().isEmpty() || protocol.toLowerCase().startsWith("smtp")) {
            return "imaps";
        }
        return protocol.trim();
    }

    private String resolvePassword() {
        String password = mailProperties.getPassword();
        if (password == null) return "";
        // Remove spaces (common in Google 16-character App Passwords)
        return password.replaceAll("\\s+", "").trim();
    }

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            return message.getContent().toString();
        } else if (message.isMimeType("text/html")) {
            return message.getContent().toString();
        } else if (message.isMimeType("multipart/*")) {
            MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
            return getTextFromMimeMultipart(mimeMultipart);
        }
        return "";
    }

    private String getTextFromMimeMultipart(MimeMultipart mimeMultipart) throws MessagingException, IOException {
        StringBuilder result = new StringBuilder();
        int count = mimeMultipart.getCount();
        for (int i = 0; i < count; i++) {
            BodyPart bodyPart = mimeMultipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                result.append(bodyPart.getContent());
            } else if (bodyPart.isMimeType("text/html")) {
                result.append(bodyPart.getContent());
            } else if (bodyPart.getContent() instanceof MimeMultipart) {
                result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
            }
        }
        return result.toString();
    }

    private String extractUtr(String text) {
        Matcher m = UTR_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        Matcher m2 = NUMERIC_UTR_PATTERN.matcher(text);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return null;
    }

    private String extractVpa(String text) {
        Matcher m = VPA_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Diagnostic test method: Connects directly to Gmail IMAP, verifies authentication,
     * reads INBOX message count, and returns status details.
     */
    public Map<String, Object> testEmailConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        String host = resolveHost();
        int port = resolvePort(host);
        String protocol = resolveProtocol(host, port);
        String username = mailProperties.getUsername() != null ? mailProperties.getUsername().trim() : "";
        String password = resolvePassword();

        result.put("host", host);
        result.put("port", port);
        result.put("protocol", protocol);
        result.put("username", username);

        if (username.isEmpty() || password.isEmpty()) {
            result.put("connected", false);
            result.put("status", "FAILED");
            result.put("message", "Mail username or password is not configured in application.yml");
            return result;
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".ssl.enable", "true");
        props.put("mail." + protocol + ".ssl.trust", "*");
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(mailProperties.getConnectionTimeout()));
        props.put("mail." + protocol + ".timeout", String.valueOf(mailProperties.getReadTimeout()));

        Session session = Session.getInstance(props);
        Store store = null;
        Folder folder = null;

        try {
            store = session.getStore(protocol);
            store.connect(host, port, username, password);

            folder = store.getFolder(mailProperties.getFolder());
            folder.open(Folder.READ_ONLY);
            int total = folder.getMessageCount();

            result.put("connected", true);
            result.put("status", "SUCCESS");
            result.put("folder", mailProperties.getFolder());
            result.put("totalMessagesInInbox", total);

            if (total > 0) {
                Message lastMsg = folder.getMessage(total);
                result.put("latestEmailSubject", lastMsg.getSubject());
                result.put("latestEmailDate", lastMsg.getSentDate() != null ? lastMsg.getSentDate().toString() : "N/A");
            }
            result.put("message", "Email verification is ACTIVE and successfully connected to Gmail INBOX!");

        } catch (Exception ex) {
            result.put("connected", false);
            result.put("status", "ERROR");
            result.put("errorMessage", ex.getMessage());
            result.put("message", "Failed to connect to Gmail: " + ex.getMessage());
        } finally {
            if (folder != null && folder.isOpen()) {
                try { folder.close(false); } catch (Exception ignored) {}
            }
            if (store != null && store.isConnected()) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }

        return result;
    }
}
