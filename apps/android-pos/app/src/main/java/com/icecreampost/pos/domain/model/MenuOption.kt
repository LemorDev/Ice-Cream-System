package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity

internal val ProductEntity.servingLabel: String?
    get() = when (servingType) {
        "cup" -> servingSize?.let { "$it cup" }
        "cone" -> servingSize?.let { "$it cone" }
        else -> null
    }

internal val ProductEntity.optionDetails: String
    get() = listOfNotNull(servingLabel, flavorName).joinToString(" · ")

internal val ProductEntity.receiptName: String
    get() = menuGroup?.let { group ->
        listOfNotNull(group, optionDetails.takeIf { it.isNotEmpty() }).joinToString(" · ")
    } ?: name
