package com.icecreampost.pos.ui.screen.catalog

import com.icecreampost.pos.domain.model.MenuAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductCatalogAvailabilityLabelTest {
    @Test fun `recipe item is simply available rather than possible`() {
        assertEquals("In stock", menuAvailabilityLabel(MenuAvailability(171, true), 0.0))
    }

    @Test fun `sold out recipe names depleted component`() {
        assertEquals("Sold out · Need Large Cones", menuAvailabilityLabel(
            MenuAvailability(0, true, listOf("Large Cones")), 0.0))
    }

    @Test fun `reserved ingredients explain no more for order`() {
        assertEquals("No more for this order", menuAvailabilityLabel(
            MenuAvailability(0, true, limitedByCart = true), 0.0))
    }

}
