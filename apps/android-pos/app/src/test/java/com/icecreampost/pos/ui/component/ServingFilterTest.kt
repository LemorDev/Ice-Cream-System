package com.icecreampost.pos.ui.component

import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ServingFilterTest {
    private fun product(id: String, name: String, category: String = "Menu", type: String = "sellable") =
        ProductEntity(id = id, name = name, category = category, priceCents = 4000, unitsInStock = 10.0,
            updatedAt = "2026-10-01", productType = type, isSellable = type == "sellable")

    private fun recipe(ingredient: String, quantity: Double = 1.0) =
        ProductRecipeEntity(ingredient, "stall", "menu", ingredient, quantity, "2026-10-01")

    @Test fun `serving comes from packaging even when menu name has no serving`() {
        val menu = product("menu", "Chocolate sundae")
        val cup = product("cup", "Medium sundae cup", type = "packaging")
        assertEquals(setOf(ServingFilter.CUP), menuServings(menu, mapOf(cup.id to cup), listOf(recipe("cup"))))
    }

    @Test fun `packaging overrides menu labels and supports cones plural`() {
        val menu = product("menu", "Cup special")
        val cone = product("cone", "Waffle cones", type = "packaging")
        assertEquals(setOf(ServingFilter.CONE), menuServings(menu, mapOf(cone.id to cone), listOf(recipe("cone"))))
    }

    @Test fun `legacy names categories and units are supported without substring false positives`() {
        assertEquals(setOf(ServingFilter.CUP), menuServings(product("menu", "Vanilla", "Cups"), emptyMap(), emptyList()))
        assertEquals(setOf(ServingFilter.CONE), menuServings(product("menu", "Vanilla cone"), emptyMap(), emptyList()))
        assertEquals(setOf(ServingFilter.CUP), menuServings(product("menu", "Vanilla").copy(unit = "cup"), emptyMap(), emptyList()))
        assertEquals(emptySet<ServingFilter>(), menuServings(product("menu", "Cupcake scone"), emptyMap(), emptyList()))
    }

    @Test fun `deleted zero quantity and raw ingredients do not classify menus`() {
        val menu = product("menu", "Chocolate")
        val cup = product("cup", "Cup powder", type = "raw")
        val cone = product("cone", "Cone", type = "packaging").copy(deletedAt = "2026-10-01")
        val zeroCup = product("zero", "Cup", type = "packaging")
        assertEquals(emptySet<ServingFilter>(), menuServings(menu, listOf(cup, cone, zeroCup).associateBy { it.id },
            listOf(recipe("cup"), recipe("cone"), recipe("zero", 0.0))))
    }
}
