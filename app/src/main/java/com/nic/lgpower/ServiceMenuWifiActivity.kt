package com.nic.lgpower

import android.hardware.ConsumerIrManager
import android.view.View
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service Menu (Wi-Fi): opens IN-START and EZ-ADJUST through the launcher and
 * drives the menu with the digit and nav keys over the pointer socket. Works on
 * every phone, blaster or not. POWER-ONLY and IN-STOP are not offered here:
 * a factory reset or a locked TV is not something to fire from a network call.
 */
class ServiceMenuWifiActivity : ServiceRemoteBase() {

    private val client by lazy { WebOsClient(this) }

    // One thread so digits typed in a row reach the TV in the order they were tapped
    private val wifiExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "service-remote-wifi").apply { isDaemon = true }
    }

    // A double tap would launch or close the factory app twice
    private val menuBusy = AtomicBoolean(false)

    override val subtitleText = "Over Wi-Fi · the TV must be on"
    override val footerText = "KEYS OVER WI-FI · THE TV MUST BE ON"

    private val hasIr
        get() = (getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true

    private fun toastLong(text: String) {
        runOnUiThread { if (!isFinishing) Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun sendKey(view: View, tvKey: String) {
        haptic(view)
        wifiExecutor.execute {
            when (val r = client.pressKey(tvKey)) {
                is WebOsClient.Result.NeedsPairing -> toastLong(PAIRING_TEXT)
                is WebOsClient.Result.Error -> toastLong(r.message)
                else -> {}
            }
        }
    }

    private fun isUnreachable(message: String) = UNREACHABLE.any { message.startsWith(it, ignoreCase = true) }

    private fun runMenuCall(view: View, call: () -> Unit) {
        if (!menuBusy.compareAndSet(false, true)) return
        haptic(view)
        wifiExecutor.execute {
            try { call() } finally { menuBusy.set(false) }
        }
    }

    private fun open(view: View, menu: WebOsClient.ServiceMenu) = runMenuCall(view) {
        val problem = when (val r = client.launchServiceMenu(menu)) {
            is WebOsClient.Result.NeedsPairing -> PAIRING_TEXT
            is WebOsClient.Result.Error -> if (isUnreachable(r.message)) UNREACHABLE_TEXT else refusedText()
            // The C4 never reports factorywin as the foreground app (it keeps naming the app
            // underneath), so the launch reply is the only signal there is.
            is WebOsClient.Result.Success -> null
        }
        if (problem != null) toastLong(problem)
    }

    private fun close(view: View) = runMenuCall(view) {
        when (val r = client.closeServiceMenu()) {
            is WebOsClient.Result.NeedsPairing -> toastLong(PAIRING_TEXT)
            is WebOsClient.Result.Error -> toastLong(if (isUnreachable(r.message)) UNREACHABLE_TEXT else r.message)
            else -> {}
        }
    }

    private fun refusedText() =
        "Your TV didn't open the service menu over Wi-Fi." + if (hasIr) " Try Service Menu (IR)." else ""

    override fun build(page: Page) = with(page) {
        label("OVER WI-FI")
        row(
            tile("IN-START", service = true) { open(it, WebOsClient.ServiceMenu.IN_START) },
            tile("EZ-ADJUST", service = true) { open(it, WebOsClient.ServiceMenu.EZ_ADJUST) },
            tile("CLOSE MENU") { close(it) }
        )
        keyGrid(withPower = false) { v, key -> key.tv?.let { sendKey(v, it) } }
    }

    override fun showEntryWarning() {
        warningSheet(
            chipText = "FACTORY CONTROLS",
            title = "This can wreck your TV",
            body = "Service menu settings can break your TV. Only change what you know.\n\n" +
                   "Wrong EZ-Adjust values (panel, white balance) can permanently ruin the " +
                   "picture, and wrong IN-START values can leave the TV misconfigured. " +
                   "Note every value down before changing it. If you're just curious: " +
                   "look, don't touch.",
            buttonText = "I understand the risks",
            cancelClosesScreen = true
        )
    }

    override fun onDestroy() {
        wifiExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val PAIRING_TEXT = "Accept pairing on your TV, then try again."
        const val UNREACHABLE_TEXT = "Can't reach the TV. Check it is on and on this Wi-Fi."

        // What WebOsClient says about a TV it never spoke to, as opposed to one that said no
        val UNREACHABLE = listOf(
            "No TV IP", "Timeout", "Can't connect", "Not connected",
            "Connection", "Session closed", "Lost the connection"
        )
    }
}
