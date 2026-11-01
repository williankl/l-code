package dev.lemonadecode.updates.fake

import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.updates.api.UpdateService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [UpdateService] whose state tests set by hand; checks and installs are only counted. */
public class FakeUpdateService(initial: UpdateState = UpdateState.None) : UpdateService {
  private val mutableState = MutableStateFlow(initial)
  override val state: StateFlow<UpdateState> = mutableState

  public var checkCount: Int = 0
    private set
  public var installCount: Int = 0
    private set

  public fun setState(state: UpdateState) {
    mutableState.value = state
  }

  override suspend fun check() {
    checkCount++
  }

  override fun install() {
    installCount++
  }
}
