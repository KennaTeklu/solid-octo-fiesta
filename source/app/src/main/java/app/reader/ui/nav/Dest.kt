package app.reader.ui.nav

/** Top-level navigation destinations. Routes are simple strings for nav-compose. */
sealed interface Dest {
    val route: String

    data object Library : Dest { const val ROUTE = "library"; override val route = ROUTE }
    data object Reader : Dest {
        const val ARG_DOC_ID = "docId"
        const val ROUTE = "reader/{$ARG_DOC_ID}"
        override val route = ROUTE
        fun path(id: String) = "reader/$id"
    }
    data object Queue : Dest { const val ROUTE = "queue"; override val route = ROUTE }
    data object Settings : Dest { const val ROUTE = "settings"; override val route = ROUTE }
    data object Paste : Dest { const val ROUTE = "paste"; override val route = ROUTE }
}
