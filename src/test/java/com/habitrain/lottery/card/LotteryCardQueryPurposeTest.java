package com.habitrain.lottery.card;

import com.habitrain.core.api.role.v2.QueryPurpose;
import com.habitrain.core.api.role.v2.RoleQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LotteryCardQueryPurposeTest {

    @Test
    void lotteryCardQueryExcludesOtherModeWithoutGameMode() {
        RoleQuery query = RoleQuery.builder().purpose(QueryPurpose.LOTTERY_CARD).build();
        assertTrue(query.excludesOtherModeRoles());
    }
}
