package ru.papasheets.ui

import androidx.compose.runtime.staticCompositionLocalOf
import ru.papasheets.AppGraph

/** Доступ к репозиториям из Compose-дерева без Hilt: пробрасывается один раз в MainActivity. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("LocalAppGraph не предоставлен — оберни дерево в CompositionLocalProvider")
}
