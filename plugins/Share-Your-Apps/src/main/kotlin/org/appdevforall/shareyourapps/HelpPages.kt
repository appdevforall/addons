package org.appdevforall.shareyourapps

/**
 * Tier 3 of the plugin's help: for each tooltip tag, the button to its section of the offline pages in
 * src/main/assets/docs/, which CodeOnTheGo serves (DocumentationExtension.getTier3DocsAssetPath).
 * Uris are relative, so CodeOnTheGo scopes them to this plugin's own pages.
 */
object HelpPages {
    val buttons: Map<String, Pair<String, String>> = mapOf(
        "shareyourapps.overview" to ("Share Your Apps help" to "index.html"),
        "shareyourapps.store_name" to ("The store" to "store.html#name"),
        "shareyourapps.store_description" to ("The store" to "store.html#description"),
        "shareyourapps.header_image" to ("The store" to "store.html#header"),
        "shareyourapps.pin" to ("The store's PIN" to "store.html#pin"),
        "shareyourapps.products" to ("Products" to "products.html#list"),
        "shareyourapps.product_order" to ("Products" to "products.html#list"),
        "shareyourapps.product_name" to ("A product's name" to "products.html#name"),
        "shareyourapps.product_project" to ("Products" to "products.html#project"),
        "shareyourapps.product_apk" to ("A product's APK" to "products.html#apk"),
        "shareyourapps.publish_apk" to ("What to publish" to "products.html#publish"),
        "shareyourapps.publish_source" to ("What to publish" to "products.html#publish"),
        "shareyourapps.product_description" to ("Products" to "products.html#description"),
        "shareyourapps.product_extra" to ("A product's Extra JSON" to "products.html#extra"),
        "shareyourapps.product_media" to ("Pictures and videos" to "media.html#pictures"),
        "shareyourapps.media_describe" to ("Describing pictures and videos" to "media.html#describe"),
        "shareyourapps.captions" to ("Captions" to "media.html#captions"),
        "shareyourapps.media_remove" to ("Pictures and videos" to "media.html#remove"),
        "shareyourapps.template" to ("Templates" to "templates.html#template"),
        "shareyourapps.extra" to ("Extra JSON" to "templates.html#extra"),
        "shareyourapps.publish" to ("Publishing" to "publishing.html#publish"),
    )
}
