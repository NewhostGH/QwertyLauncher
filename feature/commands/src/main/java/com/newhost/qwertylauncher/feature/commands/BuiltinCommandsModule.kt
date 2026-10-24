package com.newhost.qwertylauncher.feature.commands

import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/** Everyday commands: web, calc (with conversions and a tape), settings, torch, alarm, timer. */
class BuiltinCommandsModule : LauncherModule {
    override val id = "commands"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*CommandsSettings.all)
        val settings = scope.settings
        scope.searchProvider(WebSearchProvider(settings))
        val tape = CalcTape(scope.appContext)
        val calc = Calc(settings, tape)
        val pane = CalcPane(calc, tape)
        val openTape = { host: LauncherHost -> host.showPane(pane) }
        scope.searchProvider(CalcSearchProvider(settings, calc, openTape))
        scope.command(WebCommand(settings))
        scope.command(CalcCommand(calc, openTape))
        scope.command(SettingsCommand())
        scope.command(TorchCommand(TorchController(scope.appContext)))
        scope.command(AlarmCommand())
        scope.command(TimerCommand(scope.services))
    }
}
