package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import kotlin.math.floor

data class MenuAvailability(
    val additionalPortions: Int,
    val hasRecipe: Boolean,
    val limitingIngredients: List<String> = emptyList(),
    val limitedByCart: Boolean = false,
    val invalidRecipe: Boolean = false,
)

/** Additional portions available after ingredients already reserved by this cart. */
fun additionalMenuPortions(
    product: ProductEntity,
    products: List<ProductEntity>,
    recipes: List<ProductRecipeEntity>,
    cart: Map<String, Int>,
): Int = menuAvailability(product, products, recipes, cart).additionalPortions

fun menuAvailability(
    product: ProductEntity,
    products: List<ProductEntity>,
    recipes: List<ProductRecipeEntity>,
    cart: Map<String, Int>,
): MenuAvailability {
    val recipe = recipes.filter { it.parentProductId == product.id }
    if (recipe.isEmpty()) {
        // Older standalone products can still be sold from their own stock.
        return MenuAvailability(
            floor(product.unitsInStock - (cart[product.id] ?: 0)).toInt().coerceAtLeast(0),
            hasRecipe = false,
        )
    }

    val productsById = products.associateBy { it.id }
    val invalidRecipe = recipe.any { ingredient ->
        val stock = productsById[ingredient.ingredientProductId]
        stock == null || stock.deletedAt != null || stock.stallId != product.stallId ||
            stock.productType == "sellable" || ingredient.quantity <= 0.0
    }
    val reserved = mutableMapOf<String, Double>()
    recipes.filter { (cart[it.parentProductId] ?: 0) > 0 }.forEach { ingredient ->
        val quantity = cart[ingredient.parentProductId] ?: 0
        reserved[ingredient.ingredientProductId] =
            (reserved[ingredient.ingredientProductId] ?: 0.0) + ingredient.quantity * quantity
    }
    val capacities = recipe.map { ingredient ->
        val stock = productsById[ingredient.ingredientProductId]
        val valid = stock != null && stock.deletedAt == null && stock.stallId == product.stallId &&
            stock.productType != "sellable" && ingredient.quantity > 0.0
        val beforeCart = if (!valid) 0 else floor(stock!!.unitsInStock / ingredient.quantity + 1e-9).toInt().coerceAtLeast(0)
        val afterCart = if (!valid) 0 else floor(((stock!!.unitsInStock - (reserved[ingredient.ingredientProductId] ?: 0.0)) /
            ingredient.quantity) + 1e-9).toInt().coerceAtLeast(0)
        Triple(stock?.name ?: "Missing recipe ingredient", beforeCart, afterCart)
    }
    val available = capacities.minOf { it.third }
    return MenuAvailability(
        additionalPortions = available,
        hasRecipe = true,
        limitingIngredients = if (available == 0 && !invalidRecipe) capacities.filter { it.second == 0 }.map { it.first } else emptyList(),
        limitedByCart = available == 0 && capacities.all { it.second > 0 },
        invalidRecipe = invalidRecipe,
    )
}
