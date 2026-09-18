package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();

        Map<TransactionKey, NormalizedTxn> unique = new LinkedHashMap<>();

        for (NormalizedTxn txn : rows) {
            TransactionKey key = TransactionKey.of(txn);

            NormalizedTxn existing = unique.get(key);

            if (existing == null) {
                unique.put(key, txn);
            } else {
                unique.put(key, merge(existing, txn));
            }
        }

        for (NormalizedTxn txn : unique.values()) {
            target.save(txn);
        }

        long skipped = rows.size() - unique.size();

        return new Result(rows.size(), unique.size(), skipped);
    }

    private static NormalizedTxn merge(
            NormalizedTxn first,
            NormalizedTxn second) {

        java.util.LinkedHashSet<String> sourceIds =
                new java.util.LinkedHashSet<>(first.sourceMessageIds());

        sourceIds.addAll(second.sourceMessageIds());

        return new NormalizedTxn(
                first.accountLast4(),
                first.occurredAt(),
                first.direction(),
                first.amount().setScale(2),
                first.category(),
                first.merchant(),
                List.copyOf(sourceIds)
        );
    }

    public record Result(long read, long written, long skipped) {}

    private record TransactionKey(
            String accountLast4,
            java.time.OffsetDateTime occurredAt,
            in.simplifymoney.ledgersync.model.Direction direction,
            BigDecimal amount) {

        static TransactionKey of(NormalizedTxn txn) {
            return new TransactionKey(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    txn.amount().setScale(2)
            );
        }
    }
}