package dev.lemonadecode.platform.preferences

import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@BindingContainer
@ContributesTo(AppScope::class)
public object PreferencesBindings {
  @Provides
  public fun providePreferencesFile(): PreferencesFile = PreferencesFile.default()
}
