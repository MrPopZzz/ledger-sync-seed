package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Semantic identity of a real transaction.
 *
 * Raw message IDs are deliberately excluded because the same bank transaction
 * can appear in multiple uploads/messages.
 */
public record TransactionKey(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount) {

    public static TransactionKey of(ParsedTxn p) {
        return new TransactionKey(
                p.accountLast4(),
                p.occurredAt(),
                p.direction(),
                p.amount().setScale(2));
    }
}
