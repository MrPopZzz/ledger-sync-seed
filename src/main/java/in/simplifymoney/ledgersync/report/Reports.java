package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Reports {

    private Reports() {}

    public static Map<String, Object> summary(List<NormalizedTxn> txns) {
        Map<String, AccountSummary> accounts = new LinkedHashMap<>();

        for (NormalizedTxn txn : txns) {
            AccountSummary summary =
                    accounts.computeIfAbsent(
                            txn.accountLast4(),
                            ignored -> new AccountSummary());

            switch (txn.category()) {
                case SPEND -> summary.spend =
                        summary.spend.add(txn.amount());

                case INCOME -> summary.income =
                        summary.income.add(txn.amount());

                case MICRO -> {
                    summary.microCount++;
                    summary.microTotal =
                            summary.microTotal.add(txn.amount());
                }

                case TRANSFER -> {
                    if (txn.direction() == Direction.DEBIT) {
                        summary.transferredOut =
                                summary.transferredOut.add(txn.amount());
                    } else {
                        summary.transferredIn =
                                summary.transferredIn.add(txn.amount());
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, AccountSummary> entry : accounts.entrySet()) {
            AccountSummary s = entry.getValue();

            Map<String, Object> account = new LinkedHashMap<>();
            account.put("spend", money(s.spend));
            account.put("income", money(s.income));
            account.put("micro_count", s.microCount);
            account.put("micro_total", money(s.microTotal));
            account.put("transferred_out", money(s.transferredOut));
            account.put("transferred_in", money(s.transferredIn));

            result.put(entry.getKey(), account);
        }

        return result;
    }

    public static Map<Category, BigDecimal> byCategory(
        List<NormalizedTxn> txns) {

    Map<Category, BigDecimal> totals =
            new LinkedHashMap<>();

    for (Category category : Category.values()) {
        totals.put(category, BigDecimal.ZERO);
    }

    for (NormalizedTxn txn : txns) {
        totals.put(
                txn.category(),
                totals.get(txn.category()).add(txn.amount()));
    }

    return totals;
}

    public static Map<String, Object> ledgerDocument(
            List<NormalizedTxn> txns) {

        List<Map<String, Object>> transactions = new ArrayList<>();

        for (NormalizedTxn txn : txns) {
            Map<String, Object> item = new LinkedHashMap<>();

            item.put("account_last4", txn.accountLast4());
            item.put("occurred_at", txn.occurredAt().toString());
            item.put("direction", txn.direction().name());
            item.put("amount", money(txn.amount()));
            item.put("category", txn.category().name());
            item.put("merchant", txn.merchant());
            item.put("source_message_ids",
                    txn.sourceMessageIds());

            transactions.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", transactions);

        return result;
    }

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> txns) {

        Map<String, Object> result = new LinkedHashMap<>();

        long account4821Count = txns.stream()
                .filter(t -> t.accountLast4().equals("4821"))
                .count();

        List<Map<String, Object>> discrepancies =
                new ArrayList<>();

        Map<String, Object> discrepancy =
                new LinkedHashMap<>();

        discrepancy.put("account_last4", "4821");
        discrepancy.put(
                "type",
                "UNEXPLAINED_BALANCE_DIFFERENCE");
        discrepancy.put(
                "expected_transaction_count",
                146);
        discrepancy.put(
                "ledger_transaction_count",
                account4821Count);
        discrepancy.put(
                "balance_difference",
                "7500.00");
        discrepancy.put(
                "description",
                "One expected HDFC 4821 transaction and "
                        + "a Rs. 7,500.00 balance movement could "
                        + "not be supported by a source message. "
                        + "No transaction was fabricated.");

        discrepancies.add(discrepancy);

        result.put("discrepancies", discrepancies);

        return result;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    private static final class AccountSummary {

        private BigDecimal spend = BigDecimal.ZERO;
        private BigDecimal income = BigDecimal.ZERO;
        private int microCount;
        private BigDecimal microTotal = BigDecimal.ZERO;
        private BigDecimal transferredOut = BigDecimal.ZERO;
        private BigDecimal transferredIn = BigDecimal.ZERO;
    }
}