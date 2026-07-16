package ru.papasheets

import android.app.Application

class App : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Ручной контейнер зависимостей приложения (без Hilt). Наполняется по мере роста M1+. */
class AppGraph(context: android.content.Context)
