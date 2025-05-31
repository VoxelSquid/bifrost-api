package me.voxelsquid.bifrost

import me.voxelsquid.bifrost.ai.BifrostClient
import me.voxelsquid.bifrost.ai.KeyManager
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Bifrost : JavaPlugin() {

    private val logo = listOf(" ___ _  __            _   ", "| _ |_)/ _|_ _ ___ __| |_ ", "| _ \\ |  _| '_/ _ (_-<  _|", "|___/_|_| |_| \\___/__/\\__|")

    lateinit var client : BifrostClient
    lateinit var namingStyle: String
    lateinit var setting: String

    override fun onEnable() {
        val keys = config.getStringList("api-keys")
        if (keys.contains("ADD_YOUR_KEY_HERE")) {
            logger.warning("Looks like you didn't configured Bifrost yet. You need to add at least one Gemini API key to the config.yml to use this plugin!")
            server.pluginManager.disablePlugin(this)
            return
        }
        this.client = BifrostClient(keyManager = KeyManager(keys), plugin = this)
        for (line in logo) this.logger.info(line)
        namingStyle = config.getString("naming-style")!!
        setting = config.getString("setting")!!
    }

    fun reload() {
        reloadConfig()
        client = BifrostClient(keyManager = KeyManager(config.getStringList("gemini-keys")), plugin = this)
        for (line in logo) this.logger.info(line)
    }

    init {
        pluginInstance = this
        saveDefaultConfig()
    }

    companion object {
        lateinit var pluginInstance: Bifrost
    }

}
