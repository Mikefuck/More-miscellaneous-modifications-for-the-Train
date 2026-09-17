package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailTargetParseTest {
    @Test
    void splitsCommaNewlineAndDedupes() {
        List<String> names = MailTargetParse.splitNames("Alice,Bob\nAlice; Carol  Dave");
        assertEquals(List.of("Alice", "Bob", "Carol", "Dave"), names);
    }

    @Test
    void emptyInput() {
        assertEquals(List.of(), MailTargetParse.splitNames("  \n, ;"));
    }
}
