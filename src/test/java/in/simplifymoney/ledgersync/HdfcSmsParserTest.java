package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HdfcSmsParserTest {

    @Test
    void usesTransactionAmountNotAvailableBalance() {
        RawMessage message = new RawMessage(
                "m-00022-2f118b",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T11:56:00+05:30"),
                "dev-34aed0f15820",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 "
                        + "to UPI/WATER CAN. Avl Bal: Rs.92,213.10. "
                        + "Not you? Call 18002586161");

        Optional<ParsedTxn> result = new HdfcSmsParser().parse(message);

        assertTrue(result.isPresent());
        assertEquals("5.00", result.get().amount().toPlainString());
        assertEquals("4821", result.get().accountLast4());
        assertEquals("UPI/WATER CAN", result.get().merchant());
    }
}
