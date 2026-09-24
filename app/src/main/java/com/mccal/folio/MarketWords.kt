package com.mccal.folio

import androidx.annotation.StringRes
import com.mccal.folio.market.FeaturedStyle

/**
 * The words for the Market's own choices.
 *
 * `:market` holds the model and knows nothing about resources, so the sentences people read live here, where they
 * reach a translator like the rest of the app.
 */
@get:StringRes
internal val FeaturedStyle.label: Int
    get() = when (this) {
        FeaturedStyle.CAROUSEL -> R.string.carousel
        FeaturedStyle.CALM -> R.string.calm
    }

@get:StringRes
internal val FeaturedStyle.description: Int
    get() = when (this) {
        FeaturedStyle.CAROUSEL -> R.string.large_banners_you_can_swipe_through
        FeaturedStyle.CALM -> R.string.a_simple_list_like_settings
    }

/** What to tell someone who doesn't have the Market yet, where they can turn Beta Updates on. */
internal val MARKET_NOT_YET = R.string.the_market_arrives_in_0_7_0_turn_on_beta
