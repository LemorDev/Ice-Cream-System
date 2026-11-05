package com.icecreampost.pos.ui.component

import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity

enum class ServingFilter(val label: String) {
    CUP("Cup"), CONE("Cone");

    private val words = Regex("\\b${label.lowercase()}s?\\b", RegexOption.IGNORE_CASE)
    fun matches(text: String): Boolean = words.containsMatchIn(text)
}

/** Recipe packaging takes precedence over legacy menu labels when it specifies a serving. */
internal fun menuServings(
    product: ProductEntity,
    productsById: Map<String, ProductEntity>,
    recipes: List<ProductRecipeEntity>,
): Set<ServingFilter> {
    val packagingNames = recipes.asSequence()
        .filter { it.parentProductId == product.id && it.quantity > 0 }
        .mapNotNull { productsById[it.ingredientProductId] }
        .filter { it.productType == "packaging" && it.deletedAt == null }
        .map { "${it.name} ${it.category} ${it.unit}" }.toList()
    val fromRecipe = ServingFilter.entries.filter { serving -> packagingNames.any(serving::matches) }.toSet()
    return fromRecipe.ifEmpty {
        ServingFilter.entries.filter { it.matches("${product.name} ${product.category} ${product.unit}") }.toSet()
    }
}
