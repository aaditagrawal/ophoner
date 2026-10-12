package dev.ophoner.device

import androidx.annotation.Keep

@Keep
class PrivilegedService : IPrivilegedService.Stub() {
    private val runner = CommandRunner()
    override fun execute(command: String, timeoutMs: Long): Array<String> = runner.execute(command, timeoutMs)
    override fun cancelAll() = runner.cancelAll()
    override fun destroy() { runner.cancelAll(); kotlin.system.exitProcess(0) }
}
