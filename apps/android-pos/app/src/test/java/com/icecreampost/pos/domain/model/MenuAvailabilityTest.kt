package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuAvailabilityTest {
    private fun product(id: String, stock: Double, type: String = "sellable") = ProductEntity(
        id = id, stallId = "stall-1", name = id, category = "Menu", priceCents = 2500,
        productType = type, isSellable = type == "sellable", unitsInStock = stock, updatedAt = "now",
    )
    private fun recipe(parent: String, ingredient: String, quantity: Double) = ProductRecipeEntity(
        id = "$parent-$ingredient", stallId = "stall-1", parentProductId = parent,
        ingredientProductId = ingredient, quantity = quantity, updatedAt = "now",
    )

    @Test fun `recipe availability is limited by the scarcest ingredient`() {
        val menu = product("twirl", 0.0)
        val powder = product("powder", 240.0, "raw")
        val cup = product("cup", 2.0, "packaging")
        assertEquals(2, additionalMenuPortions(menu, listOf(menu, powder, cup),
            listOf(recipe("twirl", "powder", 80.0), recipe("twirl", "cup", 1.0)), emptyMap()))
    }

    @Test fun `cart reserves ingredients shared by different menu items`() {
        val vanilla = product("vanilla", 0.0)
        val ube = product("ube", 0.0)
        val cup = product("cup", 2.0, "packaging")
        val recipes = listOf(recipe("vanilla", "cup", 1.0), recipe("ube", "cup", 1.0))
        assertEquals(0, additionalMenuPortions(ube, listOf(vanilla, ube, cup), recipes,
            mapOf("vanilla" to 1, "ube" to 1)))
    }

    @Test fun `missing recipe and zero finished stock cannot be sold`() {
        val menu = product("missing-recipe", 0.0)
        assertEquals(0, additionalMenuPortions(menu, listOf(menu), emptyList(), emptyMap()))
    }

    @Test fun `legacy standalone stock remains available`() {
        val menu = product("standalone", 3.0)
        assertEquals(1, additionalMenuPortions(menu, listOf(menu), emptyList(), mapOf(menu.id to 2)))
    }

    @Test fun `zero cones blocks sale even when powder remains`() {
        val menu = product("twirl", 0.0)
        val powder = product("powder", 10_000.0, "raw")
        val cone = product("cone", 0.0, "packaging")
        val result = menuAvailability(menu, listOf(menu, powder, cone),
            listOf(recipe("twirl", "powder", 45.0), recipe("twirl", "cone", 1.0)), emptyMap())
        assertEquals(0, result.additionalPortions)
        assertEquals(listOf("cone"), result.limitingIngredients)
    }

    @Test fun `cart reservation is distinguished from physically depleted ingredients`() {
        val menu = product("twirl", 0.0)
        val cone = product("cone", 1.0, "packaging")
        val result = menuAvailability(menu, listOf(menu, cone),
            listOf(recipe("twirl", "cone", 1.0)), mapOf("twirl" to 1))
        assertEquals(0, result.additionalPortions)
        assertEquals(emptyList<String>(), result.limitingIngredients)
        assertEquals(true, result.limitedByCart)
    }

    @Test fun `missing recipe ingredient is not mistaken for depleted stock`() {
        val menu = product("twirl", 0.0)
        val result = menuAvailability(menu, listOf(menu),
            listOf(recipe("twirl", "deleted-cone", 1.0)), emptyMap())
        assertEquals(0, result.additionalPortions)
        assertEquals(true, result.invalidRecipe)
        assertEquals(emptyList<String>(), result.limitingIngredients)
    }
}
