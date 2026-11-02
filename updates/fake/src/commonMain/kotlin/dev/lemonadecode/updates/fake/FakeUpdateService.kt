package dev.lemonadecode.updates.fake

import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.updates.api.UpdateService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [UpdateService] whose state tests set by hand; checks, installs and restarts are only counted. */
public class FakeUpdateService(
  initial: UpdateState = UpdateState.None,
  override val installation: AppInstallation = AppInstallation(AppVersion(1, 2, 0), UpdateChannel.SelfUpdate),
  checks: UpdateChecks = UpdateChecks(),
) : UpdateService {
  private val mutableState = MutableStateFlow(initial)
  override val state: StateFlow<UpdateState> = mutableState

  private val mutableChecks = MutableStateFlow(checks)
  override val checks: StateFlow<UpdateChecks> = mutableChecks

  public var checkCount: Int = 0
    private set
  public var installCount: Int = 0
    private set
  public var restartCount: Int = 0
    private set

  public fun setState(state: UpdateState) {
    mutableState.value = state
  }

  public fun setChecks(checks: UpdateChecks) {
    mutableChecks.value = checks
  }

  override suspend fun check() {
    checkCount++
  }

  override fun install() {
    installCount++
  }

  override fun restart() {
    restartCount++
  }
}
