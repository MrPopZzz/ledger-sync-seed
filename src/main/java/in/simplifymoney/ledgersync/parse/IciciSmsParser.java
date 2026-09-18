package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Supports both ICICI transaction-alert formats present in corpus-a.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with "
                    + "(?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?) "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) "
                    + "(?<dir>Dr|Cr) "
                    + "(?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?) "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>[^;]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());

        if (v1.find()) {
            return parseV1(m, v1);
        }

        Matcher v2 = V2.matcher(m.body());

        if (v2.find()) {
            return parseV2(m, v2);
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> parseV1(RawMessage m, Matcher matcher) {
        BigDecimal amount = new BigDecimal(
                matcher.group("amount").replace(",", ""))
                .setScale(2);
        OffsetDateTime at = Dates.ist(matcher.group("when"));

        if (amount == null || at == null) return Optional.empty();

        Direction direction =
                "debited".equals(matcher.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                matcher.group("acct"),
                at,
                direction,
                amount,
                matcher.group("merchant").trim(),
                Amounts.statedBalance(m.body()),
                m.messageId()));
    }

    private Optional<ParsedTxn> parseV2(RawMessage m, Matcher matcher) {
        BigDecimal amount = new BigDecimal(
                matcher.group("amount").replace(",", ""))
                .setScale(2);
        OffsetDateTime at = Dates.ist(matcher.group("when"));

        if (amount == null || at == null) return Optional.empty();

        Direction direction =
                "Dr".equalsIgnoreCase(matcher.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                matcher.group("acct"),
                at,
                direction,
                amount,
                matcher.group("merchant").trim(),
                Amounts.statedBalance(m.body()),
                m.messageId()));
    }
}