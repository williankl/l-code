package dev.lemonadecode.platform.architecture

/**
 * Work that runs for the life of the app, started once at launch. Contribute implementations
 * with `@ContributesIntoSet(AppScope::class)`; the app launches each one in its own coroutine.
 */
public interface AppInitializer {
  public suspend fun run()
}
