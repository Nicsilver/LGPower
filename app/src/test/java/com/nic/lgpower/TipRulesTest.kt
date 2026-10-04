package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Test

class TipRulesTest {
    @Test fun purchasedTipIsConsumed() =
        assertEquals(TipAction.CONSUME, TipRules.actionFor(TipRules.STATE_PURCHASED, listOf("tip_medium")))

    @Test fun pendingTipWaits() =
        assertEquals(TipAction.PENDING, TipRules.actionFor(TipRules.STATE_PENDING, listOf("tip_small")))

    @Test fun unspecifiedStateIsIgnored() =
        assertEquals(TipAction.IGNORE, TipRules.actionFor(0, listOf("tip_large")))

    @Test fun otherProductsAreLeftAlone() =
        assertEquals(TipAction.IGNORE, TipRules.actionFor(TipRules.STATE_PURCHASED, listOf("something_else")))

    @Test fun bundleWithATipTierCounts() =
        assertEquals(TipAction.CONSUME, TipRules.actionFor(TipRules.STATE_PURCHASED, listOf("other", "tip_large")))
}
