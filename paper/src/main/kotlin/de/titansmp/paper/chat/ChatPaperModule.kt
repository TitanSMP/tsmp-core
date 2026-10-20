package de.titansmp.paper.chat

import de.titansmp.api.chat.ChatException
import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatRenderer
import de.titansmp.api.chat.ChatService
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.core.display.PlayerDisplayFormat
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

class ChatPaperModule(
    private val plugin: JavaPlugin,
    private val service: ChatService,
    private val eventBus: EventBus,
    private val serverId: String,
    private val executor: java.util.concurrent.Executor,
    // Renders by source, so Discord, web, and system lines are marked while Minecraft lines stay plain.
    private val renderer: ChatRenderer = ChatRenderer { message ->
        PlayerDisplayFormat.chatMessage(message.source, message.prefix, message.senderName, message.message)
    },
    private val receivedIds: de.titansmp.chat.RecentChatMessageCache = de.titansmp.chat.RecentChatMessageCache(),
    /** Returns true when a feature consumed this message before network chat publication. */
    private val consumeChatInput: (java.util.UUID, String) -> Boolean = { _, _ -> false },
) : Listener, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        subscriptions += eventBus.subscribe(NetworkChatMessage.TYPE, EventListener(::onChatMessage))
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChat(event: AsyncChatEvent) {
        // Vanilla distribution is always suppressed; only Redis success can cause local rendering.
        event.isCancelled = true
        val player = event.player
        val content = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (consumeChatInput(player.uniqueId, content)) return
        executor.execute {
            service.send(player.uniqueId, player.name, content, serverId).whenComplete { _, failure ->
                if (failure != null) {
                    val cause = unwrap(failure)
                    val message = (cause as? ChatException)?.message ?: "Network chat is temporarily unavailable. Your message was not sent."
                    Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.sendMessage(Component.text(message)) })
                    if (cause !is ChatException || cause.code == ChatException.Code.PUBLISH_FAILED) {
                        plugin.logger.severe("Network chat send failed for ${player.uniqueId}: ${cause.message}")
                    }
                }
            }
        }
    }

    private fun onChatMessage(envelope: EventEnvelope) {
        val event = envelope.event as? NetworkChatMessage ?: return
        val message = event.message
        if (!receivedIds.markIfNew(message.messageId)) return
        val rendered = Component.text(renderer.render(message))
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { it.sendMessage(rendered) }
        })
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while (current.cause != null) current = current.cause!!
        return current
    }
}
