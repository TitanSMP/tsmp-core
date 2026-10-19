package de.titansmp.discord

import de.titansmp.core.discord.role.DiscordRoleNotifier
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import java.util.concurrent.CompletionStage

/**
 * Verified-role adapter backed by JDA.
 *
 * The role to grant or revoke comes from configuration only ([verifiedRoleId]); this class never
 * searches for a role by name. Members are addressed through [UserSnowflake], so the GUILD_MEMBERS
 * gateway intent is not required and no member cache is used.
 */
class JdaDiscordRoleNotifier(
    private val guild: Guild,
    private val verifiedRoleId: Long,
) : DiscordRoleNotifier {

    override fun grant(discordUserId: Long): CompletionStage<Unit> =
        guild.addRoleToMember(UserSnowflake.fromId(discordUserId), verifiedRole()).submit().thenApply { }

    override fun revoke(discordUserId: Long): CompletionStage<Unit> =
        guild.removeRoleFromMember(UserSnowflake.fromId(discordUserId), verifiedRole()).submit().thenApply { }

    /** Fails loudly instead of silently skipping when the configured role was deleted or renamed. */
    private fun verifiedRole(): Role = guild.getRoleById(verifiedRoleId)
        ?: throw IllegalStateException("The configured verified role is not available in the configured guild.")
}
