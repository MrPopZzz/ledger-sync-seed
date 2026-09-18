package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // SQL is the source of truth for the backfill scope, but legacy SQL
        // may contain duplicate physical rows. Collapse them first.
        Map<Key, NormalizedTxn> expected = canonicalSql();

        // Compare logical transactions by account/month using the document
        // store's account-month query.
        Map<AccountMonth, Map<Key, NormalizedTxn>> expectedByMonth =
                new LinkedHashMap<>();

        for (NormalizedTxn txn : expected.values()) {
            AccountMonth scope = new AccountMonth(
                    txn.accountLast4(),
                    YearMonth.from(txn.occurredAt()));

            expectedByMonth
                    .computeIfAbsent(scope, ignored -> new LinkedHashMap<>())
                    .put(Key.of(txn), txn);
        }

        for (Map.Entry<AccountMonth, Map<Key, NormalizedTxn>> entry
                : expectedByMonth.entrySet()) {

            AccountMonth scope = entry.getKey();

            List<NormalizedTxn> actualRows =
                    documents.forAccountMonth(scope.accountLast4(), scope.month());

            Map<Key, NormalizedTxn> actual = new LinkedHashMap<>();

            for (NormalizedTxn txn : actualRows) {
                actual.put(Key.of(txn), txn);
            }

            for (Map.Entry<Key, NormalizedTxn> expectedEntry
                    : entry.getValue().entrySet()) {

                NormalizedTxn wanted = expectedEntry.getValue();
                NormalizedTxn found = actual.remove(expectedEntry.getKey());

                if (found == null) {
                    divergences.add(new Divergence(
                            "missing transaction "
                                    + expectedEntry.getKey(),
                            describe(wanted),
                            "<missing>"));
                    continue;
                }

                compareTransaction(wanted, found, divergences);
            }

            // Anything left came from Mongo but not from canonical SQL.
            for (NormalizedTxn extra : actual.values()) {
                divergences.add(new Divergence(
                        "extra transaction " + Key.of(extra),
                        "<missing>",
                        describe(extra)));
            }
        }

        // Compare category totals independently of transaction matching.
        Map<String, Map<Category, BigDecimal>> sqlTotals = categoryTotals(expected);

        for (Map.Entry<String, Map<Category, BigDecimal>> entry
                : sqlTotals.entrySet()) {

            String account = entry.getKey();
            Map<Category, BigDecimal> actual =
                    documents.categoryTotals(account);

            for (Category category : Category.values()) {
                BigDecimal expectedTotal = money(
                        entry.getValue().getOrDefault(category, BigDecimal.ZERO));

                BigDecimal actualTotal = money(
                        actual.getOrDefault(category, BigDecimal.ZERO));

                if (expectedTotal.compareTo(actualTotal) != 0) {
                    divergences.add(new Divergence(
                            "category total "
                                    + account + "/" + category,
                            expectedTotal.toPlainString(),
                            actualTotal.toPlainString()));
                }
            }
        }

        // Every source message ID in SQL must resolve to the same logical
        // transaction in Mongo. This catches broken traceability even when
        // the transaction itself exists.
        Set<String> checkedMessageIds = new LinkedHashSet<>();

        for (NormalizedTxn expectedTxn : expected.values()) {
            for (String messageId : expectedTxn.sourceMessageIds()) {
                if (!checkedMessageIds.add(messageId)) {
                    continue;
                }

                var actual = documents.byMessageId(messageId);

                if (actual.isEmpty()) {
                    divergences.add(new Divergence(
                            "message mapping " + messageId,
                            describe(expectedTxn),
                            "<missing>"));
                } else if (!Key.of(expectedTxn).equals(Key.of(actual.get()))) {
                    divergences.add(new Divergence(
                            "message mapping " + messageId,
                            describe(expectedTxn),
                            describe(actual.get())));
                }
            }
        }

        return divergences;
    }

    private Map<Key, NormalizedTxn> canonicalSql() {
        Map<Key, NormalizedTxn> canonical = new LinkedHashMap<>();

        for (NormalizedTxn txn : sql.all()) {
            Key key = Key.of(txn);
            NormalizedTxn existing = canonical.get(key);

            if (existing == null) {
                canonical.put(key, txn);
            } else {
                canonical.put(key, merge(existing, txn));
            }
        }

        return canonical;
    }

    private static Map<String, Map<Category, BigDecimal>> categoryTotals(
            Map<Key, NormalizedTxn> transactions) {

        Map<String, Map<Category, BigDecimal>> totals = new LinkedHashMap<>();

        for (NormalizedTxn txn : transactions.values()) {
            Map<Category, BigDecimal> accountTotals =
                    totals.computeIfAbsent(
                            txn.accountLast4(),
                            ignored -> new LinkedHashMap<>());

            accountTotals.merge(
                    txn.category(),
                    money(txn.amount()),
                    BigDecimal::add);
        }

        return totals;
    }

    private static void compareTransaction(
            NormalizedTxn expected,
            NormalizedTxn actual,
            List<Divergence> divergences) {

        if (!sameNullable(expected.merchant(), actual.merchant())) {
            divergences.add(new Divergence(
                    "merchant " + Key.of(expected),
                    String.valueOf(expected.merchant()),
                    String.valueOf(actual.merchant())));
        }

        Set<String> expectedIds =
                new LinkedHashSet<>(expected.sourceMessageIds());

        Set<String> actualIds =
                new LinkedHashSet<>(actual.sourceMessageIds());

        if (!expectedIds.equals(actualIds)) {
            divergences.add(new Divergence(
                    "source message IDs " + Key.of(expected),
                    expectedIds.toString(),
                    actualIds.toString()));
        }
    }

    private static NormalizedTxn merge(
            NormalizedTxn first,
            NormalizedTxn second) {

        LinkedHashSet<String> sourceIds =
                new LinkedHashSet<>(first.sourceMessageIds());

        sourceIds.addAll(second.sourceMessageIds());

        return new NormalizedTxn(
                first.accountLast4(),
                first.occurredAt(),
                first.direction(),
                money(first.amount()),
                first.category(),
                first.merchant(),
                List.copyOf(sourceIds));
    }

    private static String describe(NormalizedTxn txn) {
        return "account=" + txn.accountLast4()
                + ", occurredAt=" + txn.occurredAt()
                + ", direction=" + txn.direction()
                + ", amount=" + money(txn.amount()).toPlainString()
                + ", category=" + txn.category()
                + ", merchant=" + txn.merchant()
                + ", sourceMessageIds=" + txn.sourceMessageIds();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2)
                : value.setScale(2);
    }

    private static boolean sameNullable(String first, String second) {
        return first == null
                ? second == null
                : first.equals(second);
    }

    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {
    }

    private record AccountMonth(
            String accountLast4,
            YearMonth month) {
    }

    private record Key(
            String accountLast4,
            java.time.OffsetDateTime occurredAt,
            in.simplifymoney.ledgersync.model.Direction direction,
            BigDecimal amount) {

        static Key of(NormalizedTxn txn) {
            return new Key(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    money(txn.amount()));
        }
    }
}
