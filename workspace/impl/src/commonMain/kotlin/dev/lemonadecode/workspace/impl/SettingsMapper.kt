package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.MobilePlatform
import dev.lemonadecode.domain.settings.AppLanguage
import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.plugins_mobile_status
import dev.lemonadecode.workspace.strings.time_days_ago
import dev.lemonadecode.workspace.strings.time_hours_ago
import dev.lemonadecode.workspace.strings.time_just_now
import dev.lemonadecode.workspace.strings.time_minutes_ago
import dev.lemonadecode.workspace.strings.time_months_ago
import dev.lemonadecode.workspace.strings.time_weeks_ago
import dev.lemonadecode.workspace.strings.time_years_ago
import dev.lemonadecode.workspace.strings.time_yesterday
import dev.lemonadecode.workspace.strings.update_failed_checksum
import dev.lemonadecode.workspace.strings.update_failed_download
import dev.lemonadecode.workspace.strings.update_failed_install
import dev.lemonadecode.workspace.strings.updates_available
import dev.lemonadecode.workspace.strings.updates_checking
import dev.lemonadecode.workspace.strings.updates_development
import dev.lemonadecode.workspace.strings.updates_downloaded
import dev.lemonadecode.workspace.strings.updates_downloading
import dev.lemonadecode.workspace.strings.updates_downloading_unknown
import dev.lemonadecode.workspace.strings.updates_homebrew
import dev.lemonadecode.workspace.strings.updates_not_checked
import dev.lemonadecode.workspace.strings.updates_restarting
import dev.lemonadecode.workspace.strings.updates_snap
import dev.lemonadecode.workspace.strings.updates_summary_available
import dev.lemonadecode.workspace.strings.updates_summary_homebrew
import dev.lemonadecode.workspace.strings.updates_summary_snap
import dev.lemonadecode.workspace.strings.updates_summary_up_to_date
import dev.lemonadecode.workspace.strings.updates_unsupported
import dev.lemonadecode.workspace.strings.updates_up_to_date
import dev.lemonadecode.workspace.strings.updates_version
import dev.lemonadecode.workspace.strings.updates_version_development
import dev.lemonadecode.workspace.strings.value_off
import dev.lemonadecode.workspace.uimodel.LanguageOption
import dev.lemonadecode.workspace.uimodel.MobilePluginModel
import dev.lemonadecode.workspace.uimodel.ReleaseNotesModel
import dev.lemonadecode.workspace.uimodel.UpdatePhase
import dev.lemonadecode.workspace.uimodel.UpdatesModel

/** The parts of the settings popover that need more than a copy of a setting. */
internal object SettingsMapper {
  fun updates(installation: AppInstallation, state: UpdateState, checks: UpdateChecks, nowMillis: Long): UpdatesModel {
    val version = installation.version?.toString()
    val title = version?.let { Res.string.updates_version.asLemonadeCodeString(it) } ?: Res.string.updates_version_development.asLemonadeCodeString()
    val elsewhere = when (installation.channel) {
      UpdateChannel.SelfUpdate -> null
      UpdateChannel.Homebrew -> Res.string.updates_homebrew to Res.string.updates_summary_homebrew.asLemonadeCodeString()
      UpdateChannel.Snap -> Res.string.updates_snap to Res.string.updates_summary_snap.asLemonadeCodeString()
      UpdateChannel.Development -> Res.string.updates_development to Res.string.value_off.asLemonadeCodeString()
      UpdateChannel.Unsupported -> Res.string.updates_unsupported to Res.string.value_off.asLemonadeCodeString()
    }
    if (elsewhere != null) {
      return UpdatesModel(title = title, phase = UpdatePhase.Elsewhere, status = elsewhere.first.asLemonadeCodeString(), summary = elsewhere.second)
    }
    val available = state as? UpdateState.Available
    if (available == null) {
      val checked = checks.lastCheckedMillis
      val status = when {
        checks.checking -> Res.string.updates_checking.asLemonadeCodeString()
        checked == null -> Res.string.updates_not_checked.asLemonadeCodeString()
        else -> Res.string.updates_up_to_date.asLemonadeCodeString(timeAgo(checked, nowMillis))
      }
      return UpdatesModel(
        title = title,
        phase = if (checks.checking) UpdatePhase.Checking else UpdatePhase.UpToDate,
        status = status,
        summary = if (checks.checking) status else Res.string.updates_summary_up_to_date.asLemonadeCodeString(),
      )
    }
    val newVersion = available.release.version.toString()
    val release = ReleaseNotesModel(version = newVersion, notes = available.release.notes, url = available.release.pageUrl)
    val summary = Res.string.updates_summary_available.asLemonadeCodeString(newVersion)
    return when (val progress = available.progress) {
      UpdateProgress.Ready -> UpdatesModel(title, UpdatePhase.Available, Res.string.updates_available.asLemonadeCodeString(newVersion), summary, release)
      is UpdateProgress.Downloading -> {
        val percent = progress.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val status = percent?.let { Res.string.updates_downloading.asLemonadeCodeString("$it%") } ?: Res.string.updates_downloading_unknown.asLemonadeCodeString()
        UpdatesModel(title, UpdatePhase.Downloading, status, status, release, progress = progress.fraction?.coerceIn(0f, 1f))
      }
      UpdateProgress.Downloaded -> UpdatesModel(title, UpdatePhase.Downloaded, Res.string.updates_downloaded.asLemonadeCodeString(), summary, release)
      UpdateProgress.Restarting -> {
        val status = Res.string.updates_restarting.asLemonadeCodeString()
        UpdatesModel(title, UpdatePhase.Restarting, status, status, release)
      }
      is UpdateProgress.Failed -> UpdatesModel(title, UpdatePhase.Failed, failure(progress.reason), summary, release)
    }
  }

  private fun failure(reason: UpdateFailure): LemonadeCodeString = when (reason) {
    UpdateFailure.Download -> Res.string.update_failed_download
    UpdateFailure.Checksum -> Res.string.update_failed_checksum
    UpdateFailure.Install -> Res.string.update_failed_install
  }.asLemonadeCodeString()

  fun mobile(enabled: Boolean, devices: List<MobileDevice>): MobilePluginModel = MobilePluginModel(
    enabled = enabled,
    status = if (enabled) {
      Res.string.plugins_mobile_status.asLemonadeCodeString(
        devices.count { it.platform == MobilePlatform.Android },
        devices.count { it.platform == MobilePlatform.Ios },
      )
    } else {
      null
    },
  )

  fun AppLanguage.toModel(): LanguageOption = when (this) {
    AppLanguage.System -> LanguageOption.System
    AppLanguage.English -> LanguageOption.English
    AppLanguage.German -> LanguageOption.German
    AppLanguage.Spanish -> LanguageOption.Spanish
    AppLanguage.French -> LanguageOption.French
    AppLanguage.Portuguese -> LanguageOption.Portuguese
    AppLanguage.Slovak -> LanguageOption.Slovak
  }

  fun LanguageOption.toDomain(): AppLanguage = when (this) {
    LanguageOption.System -> AppLanguage.System
    LanguageOption.English -> AppLanguage.English
    LanguageOption.German -> AppLanguage.German
    LanguageOption.Spanish -> AppLanguage.Spanish
    LanguageOption.French -> AppLanguage.French
    LanguageOption.Portuguese -> AppLanguage.Portuguese
    LanguageOption.Slovak -> AppLanguage.Slovak
  }

  /** The language Lemonade Code speaks for [code] (`de`, `pt-BR`…); English for any it doesn't. */
  fun languageFor(code: String?): LanguageOption {
    val language = code?.substringBefore('-')?.substringBefore('_')?.lowercase()
    return AppLanguage.entries.firstOrNull { it.code != null && it.code == language }?.toModel() ?: LanguageOption.English
  }
}

/** "just now", "5 min ago", "2 h ago", "yesterday", "4 days ago"… for a time [thenMillis]. */
internal fun timeAgo(thenMillis: Long, nowMillis: Long): LemonadeCodeString {
  val minutes = ((nowMillis - thenMillis).coerceAtLeast(0) / 60_000).toInt()
  val hours = minutes / 60
  val days = hours / 24
  return when {
    minutes < 1 -> Res.string.time_just_now.asLemonadeCodeString()
    minutes < 60 -> Res.plurals.time_minutes_ago.asLemonadeCodeString(minutes)
    hours < 24 -> Res.plurals.time_hours_ago.asLemonadeCodeString(hours)
    days < 2 -> Res.string.time_yesterday.asLemonadeCodeString()
    days < 14 -> Res.plurals.time_days_ago.asLemonadeCodeString(days)
    days < 60 -> Res.plurals.time_weeks_ago.asLemonadeCodeString(days / 7)
    days < 365 -> Res.plurals.time_months_ago.asLemonadeCodeString(days / 30)
    else -> Res.plurals.time_years_ago.asLemonadeCodeString(days / 365)
  }
}
