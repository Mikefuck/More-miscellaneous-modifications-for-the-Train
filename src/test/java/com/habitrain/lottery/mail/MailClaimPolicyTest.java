package com.habitrain.lottery.mail;

import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailClaimPolicyTest {

    @Test
    void rewardsFailureDoesNotMarkClaimed() {
        MailJson mail = new MailJson();
        mail.claimed = false;
        assertFalse(MailService.finishClaim(mail, false, () -> true));
        assertFalse(mail.claimed);
    }

    @Test
    void persistFailureRollsClaimedBack() {
        MailJson mail = new MailJson();
        mail.claimed = false;
        assertFalse(MailService.finishClaim(mail, true, () -> false));
        assertFalse(mail.claimed);
    }

    @Test
    void claimedOnlyAfterRewardsAndPersist() {
        MailJson mail = new MailJson();
        mail.claimed = false;
        mail.read = false;
        assertTrue(MailService.finishClaim(mail, true, () -> true));
        assertTrue(mail.claimed);
        assertTrue(mail.read);
    }

    @Test
    void persistIsInvokedWithClaimedAlreadyTrue() {
        MailJson mail = new MailJson();
        boolean[] sawClaimed = {false};
        assertTrue(MailService.finishClaim(mail, true, () -> {
            sawClaimed[0] = mail.claimed;
            return true;
        }));
        assertTrue(sawClaimed[0]);
    }
}
