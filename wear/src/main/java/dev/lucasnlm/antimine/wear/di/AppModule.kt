package dev.lucasnlm.antimine.di

import dev.lucasnlm.antimine.core.analytics.DebugAnalyticsManager
import dev.lucasnlm.antimine.core.analytics.IAnalyticsManager
import org.koin.dsl.bind
import org.koin.dsl.module

val AppModule = module {
    single {
            DebugAnalyticsManager()
    } bind IAnalyticsManager::class
}
