package app.aaps.pump.common.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import app.aaps.pump.common.ble.BondStateReceiver
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class PumpCommonModuleAbstract {

    @ContributesAndroidInjector abstract fun contributesBondStateReceiver(): BondStateReceiver

}
