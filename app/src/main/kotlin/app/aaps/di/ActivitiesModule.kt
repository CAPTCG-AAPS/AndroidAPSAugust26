package app.aaps.di

import app.aaps.MainActivity
import app.aaps.activities.HistoryBrowseActivity
import app.aaps.plugins.source.activities.EversenseCalibrationActivity
import app.aaps.plugins.source.activities.EversensePlacementActivity
import app.aaps.plugins.source.activities.EversenseStatusActivity
import app.aaps.plugins.source.activities.RequestEversensePermissionActivity
import dagger.Module
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class ActivitiesModule {

    @ContributesAndroidInjector abstract fun contributesHistoryBrowseActivity(): HistoryBrowseActivity
    @ContributesAndroidInjector abstract fun contributesMainActivity(): MainActivity
    @ContributesAndroidInjector abstract fun contributesEversenseCalibrationActivity(): EversenseCalibrationActivity
    @ContributesAndroidInjector abstract fun contributesEversensePlacementActivity(): EversensePlacementActivity
    @ContributesAndroidInjector abstract fun contributesEversenseStatusActivity(): EversenseStatusActivity
    @ContributesAndroidInjector abstract fun contributesRequestEversensePermissionActivity(): RequestEversensePermissionActivity
}