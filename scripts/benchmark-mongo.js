const dbName = db.getSiblingDB("ledger_benchmark");

dbName.transactions.drop();
dbName.account_summaries.drop();

dbName.transactions.createIndex(
  { accountLast4: 1, month: 1, occurredAtEpochMillis: -1 }
);

dbName.transactions.createIndex(
  { sourceMessageIds: 1 }
);

const bulk = dbName.transactions.initializeUnorderedBulkOp();

for (let i = 0; i < 100000; i++) {
    const account = i % 2 === 0 ? "4821" : "9075";
    const month = "2026-07";
    const category = i % 4 === 0 ? "SPEND"
        : i % 4 === 1 ? "INCOME"
        : i % 4 === 2 ? "MICRO"
        : "TRANSFER";

    bulk.insert({
        _id: `${account}|2026-07-${String((i % 28) + 1).padStart(2, "0")}T12:00:00+05:30|DEBIT|${(i + 1).toFixed(2)}`,
        accountLast4: account,
        occurredAt: `2026-07-${String((i % 28) + 1).padStart(2, "0")}T12:00:00+05:30`,
        occurredAtEpochMillis: 1783060200000 + i,
        month: month,
        direction: "DEBIT",
        amount: (i + 1).toFixed(2),
        category: category,
        merchant: "BENCHMARK",
        sourceMessageIds: [`bench-${i}`]
    });

    if ((i + 1) % 10000 === 0) {
        bulk.execute();
        bulk = dbName.transactions.initializeUnorderedBulkOp();
    }
}


dbName.account_summaries.insertOne({
    _id: "4821",
    totals: {
        SPEND: "1250000000.00",
        INCOME: "1250000000.00",
        MICRO: "1250000000.00",
        TRANSFER: "1250000000.00"
    }
});

print("documents: " + dbName.transactions.countDocuments());

print("\nQ1 account-month:");
printjson(
    dbName.transactions.find({
        accountLast4: "4821",
        month: "2026-07"
    }).sort({
        occurredAtEpochMillis: -1
    }).limit(20).explain("executionStats").executionStats
);

print("\nQ2 category totals:");
printjson(
    dbName.account_summaries.find({
        _id: "4821"
    }).explain("executionStats").executionStats
);

print("\nQ3 message ID:");
printjson(
    dbName.transactions.find({
        sourceMessageIds: "bench-50000"
    }).explain("executionStats").executionStats
);
