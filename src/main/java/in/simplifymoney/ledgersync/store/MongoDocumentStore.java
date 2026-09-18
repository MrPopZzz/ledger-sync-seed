package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> transactions;
    private final MongoCollection<Document> summaries;

    public MongoDocumentStore(String uri, String databaseName) {
        this.client = MongoClients.create(uri);
        this.database = client.getDatabase(databaseName);
        this.transactions = database.getCollection("transactions");
        this.summaries = database.getCollection("account_summaries");

        ensureIndexes();
    }

    private void ensureIndexes() {
        transactions.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("accountLast4"),
                        Indexes.ascending("month"),
                        Indexes.descending("occurredAtEpochMillis")
                )
        );

        transactions.createIndex(
                Indexes.ascending("sourceMessageIds")
        );
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        String monthValue = month.toString();

        List<NormalizedTxn> result = new ArrayList<>();

        transactions.find(
                Filters.and(
                        Filters.eq("accountLast4", accountLast4),
                        Filters.eq("month", monthValue)
                )
        ).sort(
                Indexes.descending("occurredAtEpochMillis")
        ).forEach(doc -> result.add(fromDocument(doc)));

        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Document doc = summaries.find(
                Filters.eq("_id", accountLast4)
        ).first();

        Map<Category, BigDecimal> result = new EnumMap<>(Category.class);

        for (Category category : Category.values()) {
            result.put(category, BigDecimal.ZERO.setScale(2));
        }

        if (doc == null) {
            return result;
        }

        Document totals = doc.get("totals", Document.class);

        if (totals != null) {
            for (Category category : Category.values()) {
                Object value = totals.get(category.name());

                if (value != null) {
                    result.put(
                            category,
                            new BigDecimal(value.toString()).setScale(2)
                    );
                }
            }
        }

        return result;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document doc = transactions.find(
                Filters.eq("sourceMessageIds", messageId)
        ).first();

        return doc == null
                ? Optional.empty()
                : Optional.of(fromDocument(doc));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String id = transactionId(txn);

        Document transaction = toDocument(txn, id);

        UpdateResult result = transactions.updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.set("accountLast4", txn.accountLast4()),
                        Updates.set("occurredAt", txn.occurredAt().toString()),
                        Updates.set(
                                "occurredAtEpochMillis",
                                txn.occurredAt().toInstant().toEpochMilli()
                        ),
                        Updates.set(
                                "month",
                                YearMonth.from(txn.occurredAt()).toString()
                        ),
                        Updates.set("direction", txn.direction().name()),
                        Updates.set("amount", txn.amount().toPlainString()),
                        Updates.set("category", txn.category().name()),
                        Updates.set("merchant", txn.merchant()),
                        Updates.addEachToSet(
                                "sourceMessageIds",
                                txn.sourceMessageIds()
                        )
                ),
                new UpdateOptions().upsert(true)
        );

        if (result.getUpsertedId() != null) {
            incrementSummary(txn);
        }
    }

    private void incrementSummary(NormalizedTxn txn) {
        String category = txn.category().name();
        String amount = txn.amount().toPlainString();

        summaries.updateOne(
                Filters.eq("_id", txn.accountLast4()),
                Updates.combine(
                        Updates.setOnInsert(
                                "accountLast4",
                                txn.accountLast4()
                        ),
                        Updates.inc(
                                "totals." + category,
                                new BigDecimal(amount)
                        )
                ),
                new UpdateOptions().upsert(true)
        );
    }

    private static String transactionId(NormalizedTxn txn) {
        return txn.accountLast4()
                + "|"
                + txn.occurredAt()
                + "|"
                + txn.direction()
                + "|"
                + txn.amount().setScale(2).toPlainString();
    }

    private static Document toDocument(NormalizedTxn txn, String id) {
        return new Document("_id", id)
                .append("accountLast4", txn.accountLast4())
                .append("occurredAt", txn.occurredAt().toString())
                .append(
                        "occurredAtEpochMillis",
                        txn.occurredAt().toInstant().toEpochMilli()
                )
                .append(
                        "month",
                        YearMonth.from(txn.occurredAt()).toString()
                )
                .append("direction", txn.direction().name())
                .append("amount", txn.amount().setScale(2).toPlainString())
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("sourceMessageIds", txn.sourceMessageIds());
    }

    @SuppressWarnings("unchecked")
    private static NormalizedTxn fromDocument(Document doc) {
        List<String> sourceIds =
                (List<String>) doc.get("sourceMessageIds", List.class);

        return new NormalizedTxn(
                doc.getString("accountLast4"),
                java.time.OffsetDateTime.parse(doc.getString("occurredAt")),
                Direction.valueOf(doc.getString("direction")),
                new BigDecimal(doc.getString("amount")).setScale(2),
                Category.valueOf(doc.getString("category")),
                doc.getString("merchant"),
                sourceIds == null ? List.of() : List.copyOf(sourceIds)
        );
    }

    @Override
    public void close() {
        client.close();
    }
}
