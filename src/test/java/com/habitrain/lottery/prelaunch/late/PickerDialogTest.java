package com.habitrain.lottery.prelaunch.late;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PickerDialogTest {
    @Test void edtInitializationFailureReleasesWaitAndPreservesCause() {
        var closed = new AtomicBoolean();
        var cause = new IllegalStateException("window initialization failed");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var failure = assertThrows(IllegalStateException.class,
                    () -> PickerDialog.awaitChoice(done -> { throw cause; }, () -> closed.set(true)));
            assertSame(cause, failure.getCause());
        });
        assertTrue(closed.get());
    }

    @Test void immediateChoiceReturnsWithoutDeadlock() {
        var expected = new PickerDialog.Choice(PickerDialog.Action.DEFAULT, null, false);
        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertEquals(expected, PickerDialog.awaitChoice(done -> done.accept(expected), () -> { })));
    }
}
