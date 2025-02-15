package dev.sora.protohax.relay

import dev.sora.protohax.MyApplication
import dev.sora.protohax.relay.modules.ModuleESP
import dev.sora.protohax.relay.netty.channel.NativeRakConfig
import dev.sora.protohax.relay.netty.channel.NativeRakServerChannel
import dev.sora.protohax.util.ContextUtils.readBoolean
import dev.sora.relay.MinecraftRelayListener
import dev.sora.relay.cheat.command.CommandManager
import dev.sora.relay.cheat.command.impl.CommandDownloadWorld
import dev.sora.relay.cheat.config.ConfigManagerFileSystem
import dev.sora.relay.cheat.module.ModuleManager
import dev.sora.relay.cheat.module.impl.ModuleResourcePackSpoof
import dev.sora.relay.game.GameSession
import dev.sora.relay.session.MinecraftRelaySession
import dev.sora.relay.session.listener.RelayListenerAutoCodec
import dev.sora.relay.session.listener.RelayListenerEncryptedSession
import dev.sora.relay.session.listener.RelayListenerNetworkSettings
import dev.sora.relay.session.listener.xbox.RelayListenerXboxLogin
import dev.sora.relay.utils.logInfo
import io.netty.channel.ChannelFactory
import io.netty.channel.ServerChannel
import org.cloudburstmc.netty.channel.raknet.RakReliability
import java.net.InetSocketAddress
import kotlin.concurrent.thread

object MinecraftRelay {

    private var relay: Relay? = null

    val session = GameSession()
    val moduleManager: ModuleManager
    val configManager: ConfigManagerFileSystem

	var loaderThread: Thread? = null

    init {
        moduleManager = ModuleManager(session)

		// load asynchronously
		loaderThread = thread {
			moduleManager.init()
			registerAdditionalModules(moduleManager)
			MyApplication.instance.getExternalFilesDir("resource_packs")?.also {
				if (!it.exists()) it.mkdirs()
				ModuleResourcePackSpoof.resourcePackProvider = ModuleResourcePackSpoof.FileSystemResourcePackProvider(it)
			}

			if (MyApplication.instance.readBoolean(Constants.KEY_ENABLE_COMMAND_MANAGER, Constants.KEY_ENABLE_COMMAND_MANAGER_DEFAULT)) {
				// command manager will register listener itself
				val commandManager = CommandManager(session)
				commandManager.init(moduleManager)
				MyApplication.instance.getExternalFilesDir("downloaded_worlds")?.also {
					commandManager.registerCommand(CommandDownloadWorld(session.eventManager, it))
				}
			}

			// clean-up
			loaderThread = null
		}

        configManager = ConfigManagerFileSystem(MyApplication.instance.getExternalFilesDir("configs")!!, ".json", moduleManager)
    }

    private fun registerAdditionalModules(moduleManager: ModuleManager) {
		moduleManager.registerModule(ModuleESP())
	}

    private fun constructRelay(): Relay {
        var sessionEncryptor: RelayListenerEncryptedSession? = null
        return Relay(object : MinecraftRelayListener {
            override fun onSessionCreation(session: MinecraftRelaySession): InetSocketAddress {
                // add listeners
                session.listeners.add(RelayListenerNetworkSettings(session))
                session.listeners.add(RelayListenerAutoCodec(session))
                this@MinecraftRelay.session.netSession = session
                session.listeners.add(this@MinecraftRelay.session)
                if (sessionEncryptor == null) {
                    sessionEncryptor = AccountManager.currentAccount?.let {
                        val accessToken = it.refresh()
                        logInfo("logged in as ${it.remark}")
                        RelayListenerXboxLogin(accessToken, it.platform)
                    }
                } else if (MyApplication.instance.readBoolean(Constants.KEY_OFFLINE_SESSION_ENCRYPTION, Constants.KEY_OFFLINE_SESSION_ENCRYPTION_DEFAULT)) {
					sessionEncryptor = RelayListenerEncryptedSession()
				}
                sessionEncryptor?.let {
                    it.session = session
                    session.listeners.add(it)
                }

                // resolve original ip and pass to relay client
                val address = session.peer.channel.config().getOption(NativeRakConfig.RAK_NATIVE_TARGET_ADDRESS)
                logInfo("SessionCreation $address")
				return address
            }
        }).also {
			it.optionReliability = if (MyApplication.instance.readBoolean(Constants.KEY_ENABLE_RAK_RELIABILITY, Constants.KEY_ENABLE_RAK_RELIABILITY_DEFAULT))
				RakReliability.RELIABLE_ORDERED else RakReliability.RELIABLE
		}
    }

	fun announceRelayUp() {
		if (relay == null) {
			relay = constructRelay()
		}
		loaderThread?.join()
		if (!relay!!.isRunning) {
			relay!!.bind(InetSocketAddress("0.0.0.0", 1337))
			logInfo("relay started")
		}
	}

	class Relay(listener: MinecraftRelayListener) : dev.sora.relay.MinecraftRelay(listener) {

		override fun channelFactory(): ChannelFactory<out ServerChannel> {
			return ChannelFactory {
				NativeRakServerChannel()
			}
		}
	}

	object Constants {

		const val KEY_OFFLINE_SESSION_ENCRYPTION = "OFFLINE_SESSION_ENCRYPTION"
		const val KEY_OFFLINE_SESSION_ENCRYPTION_DEFAULT = false

		const val KEY_ENABLE_COMMAND_MANAGER = "ENABLE_COMMAND_MANAGER"
		const val KEY_ENABLE_COMMAND_MANAGER_DEFAULT = true

		const val KEY_ENABLE_RAK_RELIABILITY = "ENABLE_RAK_RELIABILITY"
		const val KEY_ENABLE_RAK_RELIABILITY_DEFAULT = true
	}
}
