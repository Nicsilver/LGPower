package com.nic.lgpower

import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.View
import android.widget.Toast

/**
 * Service Menu (IR): IR-only replica of LG's factory service remote (105-201M / MKJ39170828).
 * Service menus don't reliably react to network (SSAP) input, so every button
 * here goes out over the IR blaster. The phone must point at the TV.
 *
 * Codes verified against a capture of the real service remote: same NEC
 * address (0x04) as all other LG remote traffic, service entries are just
 * high command bytes (IN-START 0xFB, EZ-ADJUST 0xFF, POWER-ONLY 0xFE,
 * IN-STOP 0xFA).
 */
class ServiceRemoteActivity : ServiceRemoteBase() {

    private var irManager: ConsumerIrManager? = null

    override val subtitleText = "NEC 0x04 over IR · aim at the TV"
    override val footerText = "MKJ39170828 REPLICA · IR ONLY"

    override fun onCreate(savedInstanceState: Bundle?) {
        irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        super.onCreate(savedInstanceState)
    }

    // NEC address 0x04 encoded (0x20DF prefix); CC/DD from the command byte
    private fun lgNecPattern(cmd: Int): IntArray {
        val rev = { b: Int -> Integer.reverse(b and 0xFF) ushr 24 }
        val code = (0x20DFL shl 16) or (rev(cmd).toLong() shl 8) or rev(cmd.inv()).toLong()
        return LGPowerWidget.buildNecPattern(code)
    }

    private fun sendIr(view: View, cmd: Int) {
        haptic(view)
        val ir = irManager
        if (ir == null || !ir.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { ir.transmit(38000, lgNecPattern(cmd)) }
    }

    override fun build(page: Page) = with(page) {
        label("FACTORY ENTRY")
        row(
            tile("IN-START", service = true) { sendIr(it, 0xFB) },
            tile("EZ-ADJUST", service = true) { sendIr(it, 0xFF) }
        )
        row(
            tile("POWER-ONLY", service = true) { v ->
                confirmSheet(v, 0xFE, "POWER-ONLY",
                    "Locks the TV into power-only mode: it stops responding to everything " +
                    "except the power button, and the only way back out is through the " +
                    "service menu. Don't send this unless you know why you need it.")
            },
            tile("IN-STOP", service = true) { v ->
                confirmSheet(v, 0xFA, "IN-STOP",
                    "FACTORY RESETS THE TV. Instantly, with no prompt on the TV.\n\n" +
                    "This is the production line's 'prepare for shipment' command. It wipes " +
                    "all settings, accounts, apps and pairings and restarts the TV into " +
                    "out-of-box setup. Panel calibration survives, nothing else does.")
            }
        )
        keyGrid(withPower = true) { v, key -> sendIr(v, key.ir) }
    }

    override fun showEntryWarning() {
        if (irManager?.hasIrEmitter() != true) {
            warningSheet(
                chipText = "NO IR BLASTER",
                title = "This phone can't transmit",
                body = "The service remote sends raw IR and this phone has no IR emitter. " +
                       "It needs a phone with a built-in IR blaster.",
                buttonText = "Got it",
                cancelClosesScreen = true
            )
        } else {
            warningSheet(
                chipText = "FACTORY CONTROLS",
                title = "This can wreck your TV",
                body = "The TV obeys these instantly and never asks first. Wrong EZ-Adjust " +
                       "values (panel, white balance) can permanently ruin the picture, " +
                       "wrong IN-START values can leave the TV misconfigured, and IN-STOP " +
                       "factory resets it on the spot.\n\n" +
                       "Note every value down before changing it. If you're just curious: " +
                       "look, don't touch.",
                buttonText = "I understand the risks",
                cancelClosesScreen = true
            )
        }
    }

    private fun confirmSheet(origin: View, cmd: Int, title: String, body: String) {
        haptic(origin)
        warningSheet(
            chipText = "CONFIRM",
            title = title,
            body = body,
            buttonText = "Send $title",
            cancelClosesScreen = false
        ) { sendIr(origin, cmd) }
    }
}
