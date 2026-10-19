package de.titansmp.discord

import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

/**
 * The complete Discord command surface.
 *
 * Only `/link` and `/unlink` exist in v1. Neither requires a Discord role: identity comes from the
 * Discord account that runs the command, so any member may link or unlink their own account.
 * Commands are registered guild-scoped by [DiscordBot] so they are available immediately.
 */
internal object DiscordBotCommands {
    const val LINK = "link"
    const val UNLINK = "unlink"
    const val LINK_CODE_OPTION = "code"

    fun definitions(): List<SlashCommandData> = listOf(
        Commands.slash(LINK, "Link your Discord account with your Minecraft account.")
            .addOption(OptionType.STRING, LINK_CODE_OPTION, "The link code shown by /link in Minecraft.", true),
        Commands.slash(UNLINK, "Remove the link between your Discord and Minecraft accounts."),
    )
}
