# Ledger Sync

Implementation for the Simplify Money Software Engineering Intern (Backend, Java) take-home.

## Overview

Ledger Sync reads bank SMS/email messages, normalizes real transactions into a trusted ledger, produces reconciliation reports, and maintains a document-store projection for fast queries.

The implementation covers:

- Bank message parsing and normalization
- Idempotent ledger ingestion
- Transaction categorization
- Ledger, summary, and reconciliation reports
- Production incident reproduction and regression test
- MongoDB document-store projection
- SQL → MongoDB backfill
- SQL/Mongo consistency checking
- Query indexes and benchmark metrics
- Reproducible Gradle builds
- Docker Compose MongoDB

## Quick Start

Requirements:

- Java 21
- Docker
- Docker Compose

From the repository root:

```bash
docker compose up -d
./gradlew test
./gradlew selfCheck
./gradlew run --args="report fixtures"

The main commands are:

./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report fixtures"
./gradlew run --args="backfill"
./gradlew run --args="check"

The existing verification script also works:

./verify.sh
Task 2 — Ledger Processing

The input corpus contains 522 bank messages.

The ingestion pipeline:

Reads each message.
Detects whether it represents a real transaction.
Parses account, timestamp, amount, direction and source message ID.
Normalizes the transaction.
Classifies it as SPEND, INCOME, MICRO, or TRANSFER.
Persists it idempotently.
Generates ledger, summary and reconciliation reports.

The implementation does not hardcode row-level answers from the fixture.

Current corpus result

The corpus produced:

522 messages read
256 normalized transactions written
43 messages skipped
256 unique logical transactions in the ledger

The ledger also contains the 15 existing seed rows, giving 271 SQL ledger rows before duplicate canonicalization.

Money handling

Amounts are represented with BigDecimal and scaled to two decimal places. No floating-point arithmetic is used for monetary values.

Idempotency

Transactions use their logical identity rather than the upload message_id alone. This is important because the same underlying bank message can be uploaded again with a different message ID.

Repeated ingestion therefore does not create another logical transaction.

Categorization
SPEND: outgoing spending excluding micro-transactions and transfers
INCOME: incoming money excluding transfers
MICRO: UPI debit of ₹100 or less
TRANSFER: one leg of a transfer between the user's own accounts

Micro-transactions remain individually visible in the ledger but are rolled into the micro totals in the summary.

Reports

Running:

./gradlew run --args="report fixtures"

produces:

fixtures/ledger.json
fixtures/summary.json
fixtures/reconciliation.json

Generated report files are ignored by Git because they are reproducible artifacts.

The current classification totals are:

Category	Amount
Spend	₹142,567.64
Income	₹142,791.16
Micro	₹4,443.85
Transfers	₹62,000.00

The reconciliation report also identifies an unresolved ₹7,500 discrepancy for the HDFC savings account.

This discrepancy is intentionally reported rather than invented away. The corpus contains a sequence of HDFC transactions whose balances imply a ₹7,500 movement, but there is no corresponding transaction message that can safely be attributed to it.

Task 3 — Incident
Incident

The production screen reported:

“You spent ₹92,213.10 on a water can.”

The actual transaction was:

Amount: ₹5.00
Account: HDFC savings ending 4821
Merchant: UPI/WATER CAN

The fixture message contains:

Rs.5 debited from a/c **4821 ... to UPI/WATER CAN.
Avl Bal: Rs.92,213.10.
Root cause

The parser was interpreting the available-balance value as the transaction amount.

This is especially dangerous because the value is valid money and therefore passed normal numeric parsing.

Fix

The parser now extracts the transaction amount from the debit/credit amount field and does not treat Avl Bal as the transaction amount.

A regression test verifies:

amount = 5.00
account = 4821
merchant = UPI/WATER CAN

The regression test fails with the buggy parser and passes with the fixed parser.

Why the existing tests did not catch it

The existing tests covered normal transaction parsing but did not contain a case where the message included a misleading available-balance amount that could be mistaken for the transaction amount.

The production incident therefore exposed a missing parser-boundary test rather than a failure of the entire test suite.

Incident Note

Five-line incident summary

HDFC SMS parsing selected the available balance instead of the debit amount.
The water-can transaction was ₹5.00, while the available balance was ₹92,213.10.
The issue affected messages matching the vulnerable parser pattern containing both a transaction amount and an available balance.
The parser was changed to bind the amount only to the explicit debit/credit transaction field.
A regression test now verifies the ₹5 water-can case and prevents the bug from returning.
Task 4 — MongoDB Document Store

MongoDB was selected as the acceptable document-store implementation for the take-home.

MongoDB is started through:

docker compose up -d

The service uses:

mongodb://localhost:27017

Database:

ledger_sync
Document model

Each transaction is stored as one MongoDB document containing:

accountLast4
occurredAt
occurredAtEpochMillis
month
direction
amount
category
merchant
sourceMessageIds

The document identity is based on the logical transaction identity.

Indexes

The account/month query uses:

{ accountLast4: 1, month: 1, occurredAtEpochMillis: -1 }

The message lookup uses an index on:

sourceMessageIds

Account summaries are also maintained for category totals so the category-total query does not need to scan all transaction documents.

Supported queries

The document-store interface is intentionally limited to the three required query patterns:

Transactions for one account in one month, newest first.
Running category totals for one account.
Find the transaction associated with a message ID.
MongoDB Benchmark

A benchmark script is available at:

scripts/benchmark-mongo.js

It loads 100,000 synthetic transaction documents and measures MongoDB's query execution statistics.

Results:

Query	Documents examined	Documents returned
Account + month	20	20
Category totals	1	1
Message ID	1	1

The corresponding key examination counts were:

Query	Keys examined
Account + month	20
Category totals	1
Message ID	1

The benchmark demonstrates that the three required access patterns are served directly by the document model and indexes rather than by scanning the 100,000-document collection.

Backfill

The backfill reads the existing SQL ledger and creates the MongoDB projection.

The SQL seed contains intentional duplicate logical rows, so the backfill canonicalizes them before writing to MongoDB.

Current result:

SQL rows read: 271
logical transactions written: 266
duplicate SQL rows skipped: 5

The backfill is safe to rerun because the MongoDB transaction identity is deterministic and writes are idempotent.

Consistency Checker

The consistency checker compares the SQL ledger and MongoDB projection.

It does not rely only on row counts.

It canonicalizes the SQL side and compares:

transaction identity
account
month
direction
amount
category
merchant
source message IDs
category totals
message-ID lookups

Run:

./gradlew run --args="check"

A successful run reports:

CONSISTENT: SQL and Mongo agree
Architecture Decisions
1. H2/SQL remains the source ledger

The existing SQL ledger provides durable transactional storage and was already part of the seed application.

2. MongoDB is a projection

MongoDB is used as the document-store read model rather than replacing the SQL ledger.

3. One transaction document

A transaction is represented as one document because the required reads are transaction-oriented.

4. Compound account/month index

The required account-month query filters by account and month and sorts newest first, so the index follows that access pattern.

5. Message IDs are indexed

Message IDs are evidence links and are needed for direct lookup.

6. Account summaries are materialized

Category totals are maintained separately so the category-total query does not scan all transactions.

7. Canonical identity is not message ID

A message ID identifies an upload/message, not necessarily a unique underlying transaction.

8. Duplicate SQL rows are canonicalized

The existing SQL seed deliberately contains duplicate logical rows. Backfill therefore groups by transaction identity.

9. Reconciliation reports unexplained money

The implementation does not manufacture a transaction to force the expected totals to match.

10. MongoDB instead of DynamoDB

MongoDB was selected because it was one of the explicitly accepted document-store options and was practical to run locally through Docker Compose.

What Data Made These Decisions

The decisions above were driven by the actual required queries and fixture behavior:

The specification requires account/month transaction retrieval.
It requires category totals.
It requires message-ID lookup.
The SQL seed contains duplicate logical rows.
The corpus contains re-uploaded messages with different message IDs.
The benchmark uses 100,000 documents.
The corpus contains an unexplained ₹7,500 balance discrepancy.

These observations led to indexed transaction documents, materialized summaries, deterministic transaction identity, and explicit reconciliation.

AI Disclosure

AI assistance was kept limited and was used mainly as a learning/reference aid for the MongoDB portion because I had less prior experience with MongoDB.

The implementation was tested locally rather than accepting generated code without verification.

In particular, the MongoDB document model, indexes, backfill behavior, consistency checking, and benchmark results were verified against the actual application and local MongoDB instance.

No AI-generated result is being presented as a substitute for the application's own test, benchmark, or reconciliation evidence.

Verification

The complete verification command is:

./verify.sh

It runs:

./gradlew test
./gradlew selfCheck

The Gradle wrapper is included so that the project does not depend on a globally installed Gradle version.

Unfinished / Known Limitations
Task 0 app screenshots and the personal one-page teardown are submission artifacts rather than application code.
Task 1 app screenshots and the Track teardown are submission artifacts.
The ₹7,500 HDFC discrepancy remains explicitly unresolved because the corpus does not provide enough evidence to invent a transaction.
The final walkthrough recording and final submission email must be prepared separately.
The implementation is designed for the take-home's required access patterns rather than as a complete production banking platform.
Submission Checklist
 Ledger ingestion and normalization
 Idempotent ingestion
 Ledger report
 Summary report
 Reconciliation report
 Incident reproduction
 Incident regression test
 MongoDB document store
 Docker Compose MongoDB
 SQL → MongoDB backfill
 Consistency checker
 100k-document benchmark
 Gradle wrapper
 Verification script
 Task 0 app screenshots / one-page teardown
 Task 1 Track screenshots / teardown
 Final walkthrough recording
 Final submission package