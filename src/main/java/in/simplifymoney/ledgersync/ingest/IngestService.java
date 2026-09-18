package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int parsed = 0;
        int skipped = 0;

        Map<TransactionKey, NormalizedTxnBuilder> transactions =
                new LinkedHashMap<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);

            if (p.isEmpty()) {
                skipped++;
                continue;
            }

            parsed++;

            ParsedTxn parsedTxn = p.get();
            TransactionKey key = TransactionKey.of(parsedTxn);

            transactions
                    .computeIfAbsent(
                            key,
                            ignored -> new NormalizedTxnBuilder(parsedTxn))
                    .addSourceMessageId(parsedTxn.sourceMessageId());
        }

        List<NormalizedTxn> normalized = classify(transactions);

        for (NormalizedTxn txn : normalized) {
            boolean alreadyExists = store.all().stream()
                    .anyMatch(existing ->
                            existing.accountLast4()
                                    .equals(txn.accountLast4())
                                    && existing.occurredAt()
                                    .equals(txn.occurredAt())
                                    && existing.direction()
                                    == txn.direction()
                                    && existing.amount()
                                    .compareTo(txn.amount()) == 0);

            if (!alreadyExists) {
                store.save(txn);
            }
        }

        return new Stats(messages.size(), normalized.size(), skipped);
    }

    private static List<NormalizedTxn> classify(
            Map<TransactionKey, NormalizedTxnBuilder> transactions) {

        List<NormalizedTxnBuilder> builders =
                new ArrayList<>(transactions.values());

        List<NormalizedTxn> result = new ArrayList<>();

        for (NormalizedTxnBuilder builder : builders) {
            Category category = categoryFor(builder.parsed, builders);
            result.add(builder.build(category));
        }

        return result;
    }

    private static Category categoryFor(
            ParsedTxn txn,
            List<NormalizedTxnBuilder> all) {

        if (isTransfer(txn, all)) {
            return Category.TRANSFER;
        }

        if (isMicro(txn)) {
            return Category.MICRO;
        }

        return txn.direction() == Direction.DEBIT
                ? Category.SPEND
                : Category.INCOME;
    }

    private static boolean isMicro(ParsedTxn txn) {
        return txn.direction() == Direction.DEBIT
                && txn.amount()
                        .compareTo(new BigDecimal("100.00")) <= 0
                && txn.merchant()
                        .toUpperCase()
                        .contains("UPI");
    }

    private static boolean isTransfer(
            ParsedTxn txn,
            List<NormalizedTxnBuilder> all) {

        if (!txn.merchant()
                .toUpperCase()
                .contains("IMPS/P2A/PARAG KAPOOR")) {
            return false;
        }

        for (NormalizedTxnBuilder other : all) {
            ParsedTxn candidate = other.parsed;

            if (candidate == txn) {
                continue;
            }

            if (!candidate.merchant()
                    .toUpperCase()
                    .contains("IMPS/P2A/PARAG KAPOOR")) {
                continue;
            }

            if (candidate.direction() == txn.direction()) {
                continue;
            }

            if (candidate.amount()
                    .compareTo(txn.amount()) != 0) {
                continue;
            }

            if (candidate.accountLast4()
                    .equals(txn.accountLast4())) {
                continue;
            }

            long seconds = Math.abs(
                    candidate.occurredAt().toEpochSecond()
                            - txn.occurredAt().toEpochSecond());

            if (seconds <= 180) {
                return true;
            }
        }

        return false;
    }

    public static List<RawMessage> readCorpus(Path corpus)
            throws IOException {

        List<RawMessage> out = new ArrayList<>();

        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line :
                    (Iterable<String>) lines
                            .filter(s -> !s.isBlank())
                            ::iterator) {

                Map<String, Object> o = Json.parseObject(line);

                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse(
                                (String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }

        return out;
    }

    private static final class NormalizedTxnBuilder {

        private final ParsedTxn parsed;
        private final List<String> sourceMessageIds =
                new ArrayList<>();

        private NormalizedTxnBuilder(ParsedTxn parsed) {
            this.parsed = parsed;
        }

        private void addSourceMessageId(String messageId) {
            if (!sourceMessageIds.contains(messageId)) {
                sourceMessageIds.add(messageId);
            }
        }

        private NormalizedTxn build(Category category) {
            sourceMessageIds.sort(String::compareTo);

            return new NormalizedTxn(
                    parsed.accountLast4(),
                    parsed.occurredAt(),
                    parsed.direction(),
                    parsed.amount(),
                    category,
                    parsed.merchant(),
                    sourceMessageIds);
        }
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped) {
    }
}