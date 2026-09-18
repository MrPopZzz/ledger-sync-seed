package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Supports the HDFC/ICICI transaction-email shape present in corpus-a:
 *
 * Date: 01 Jul 2026 09:02:00 +0530
 * Your account ending 4821 has been credited with INR 45000.00.
 * Merchant / Remarks: SALARY
 * Transaction reference: 123456
 */
public final class EmailParser implements MessageParser {

    private static final Pattern ACCOUNT = Pattern.compile(
            "account\\s+ending\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRANSACTION = Pattern.compile(
            "has\\s+been\\s+(credited|debited)\\s+with\\s+(?:INR|Rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE = Pattern.compile(
            "(?:^|\\R)Date:\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MERCHANT = Pattern.compile(
            "(?:^|\\R)Merchant\\s*/\\s*Remarks:\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss xx",
                    Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher accountMatcher = ACCOUNT.matcher(body);
        Matcher transactionMatcher = TRANSACTION.matcher(body);
        Matcher dateMatcher = DATE.matcher(body);

        if (!accountMatcher.find()
                || !transactionMatcher.find()
                || !dateMatcher.find()) {
            return Optional.empty();
        }

        String accountLast4 = accountMatcher.group(1);

        Direction direction = "credited".equalsIgnoreCase(transactionMatcher.group(1))
                ? Direction.CREDIT
                : Direction.DEBIT;

        BigDecimal amount = new BigDecimal(
                transactionMatcher.group(2).replace(",", ""))
                .setScale(2);

        OffsetDateTime occurredAt = OffsetDateTime
                .parse(dateMatcher.group(1).trim(), EMAIL_DATE)
                .withOffsetSameInstant(Dates.IST);

        String merchant = "";
        Matcher merchantMatcher = MERCHANT.matcher(body);
        if (merchantMatcher.find()) {
            merchant = merchantMatcher.group(1).trim();
        }

        return Optional.of(new ParsedTxn(
                accountLast4,
                occurredAt,
                direction,
                amount,
                merchant,
                null,
                m.messageId()));
    }
}