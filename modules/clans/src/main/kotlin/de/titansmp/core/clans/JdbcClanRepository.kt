package de.titansmp.core.clans

import de.titansmp.api.clans.*
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

/** All membership transitions lock the clan row first, then player lock rows in UUID order. */
class JdbcClanRepository(private val dataSource: DataSource) {
    fun create(player: UUID, name: String, tag: String, now: Instant): Clan = tx { c ->
        lockPlayer(c, player)
        if (member(c, player) != null) throw ClanOperationException(ClanFailure.ALREADY_IN_CLAN)
        val id = UUID.randomUUID()
        val color = ClanColor(ClanColorMode.STANDARD, "white")
        c.prepareStatement("INSERT INTO clans(clan_uuid,name,normalized_name,tag,normalized_tag,color_mode,color_value,created_at) VALUES(?,?,?,?,?,?,?,?)").use {
            it.uuid(1,id); it.setString(2,name); it.setString(3,name.lowercase(Locale.ROOT)); it.setString(4,tag); it.setString(5,tag.lowercase(Locale.ROOT)); it.setString(6,color.mode.name); it.setString(7,color.color); it.setTimestamp(8,Timestamp.from(now)); it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO clan_members(player_uuid,clan_uuid,role,joined_at) VALUES(?,?,?,?)").use { it.uuid(1,player); it.uuid(2,id); it.setString(3,ClanRole.ADMIN.name); it.setTimestamp(4,Timestamp.from(now)); it.executeUpdate() }
        Clan(id,name,tag,color,now)
    }

    fun disband(actor: UUID): Pair<Clan,List<UUID>> = tx { c ->
        val clanId = member(c, actor)?.clanUuid ?: throw ClanOperationException(ClanFailure.NOT_IN_CLAN)
        val clan = lockClan(c, clanId)
        requireRole(c, actor, setOf(ClanRole.ADMIN))
        val members = members(c, clanId)
        c.prepareStatement("DELETE FROM clans WHERE clan_uuid=?").use { it.uuid(1,clanId); it.executeUpdate() }
        clan to members.map { it.playerUuid }
    }

    fun get(id: UUID): Clan? = dataSource.connection.use { c -> clan(c,id) }
    fun nameExists(name: String): Boolean = dataSource.connection.use { c -> c.prepareStatement("SELECT 1 FROM clans WHERE normalized_name=?").use { it.setString(1,name.lowercase(Locale.ROOT));it.executeQuery().use { r->r.next() } } }
    fun tagExists(tag: String): Boolean = dataSource.connection.use { c -> c.prepareStatement("SELECT 1 FROM clans WHERE normalized_tag=?").use { it.setString(1,tag.lowercase(Locale.ROOT));it.executeQuery().use { r->r.next() } } }
    fun invitationInviter(player: UUID, clan: UUID): UUID? = dataSource.connection.use { c -> c.prepareStatement("SELECT inviter_uuid FROM clan_invitations WHERE player_uuid=? AND clan_uuid=? AND expires_at>CURRENT_TIMESTAMP(3)").use { it.uuid(1,player);it.uuid(2,clan);it.executeQuery().use { r->if(r.next()) r.uuid("inviter_uuid") else null } } }
    fun byName(name: String): Clan? = dataSource.connection.use { c -> c.prepareStatement("SELECT clan_uuid FROM clans WHERE normalized_name=?").use { it.setString(1,name.lowercase(Locale.ROOT)); it.executeQuery().use { r -> if (r.next()) clan(c,r.uuid("clan_uuid")) else null } } }
    fun byMember(player: UUID): Clan? = dataSource.connection.use { c -> member(c,player)?.let { clan(c,it.clanUuid) } }

    fun promote(actor: UUID, target: UUID): ClanRole = tx { c ->
        val id = actorClan(c, actor); val clan = lockClan(c,id); lockPlayers(c,actor,target)
        requireRole(c, actor, setOf(ClanRole.ADMIN)); sameClan(c,id,target)
        if (actor == target) throw ClanOperationException(ClanFailure.SELF_TARGET)
        val current = member(c,target)!!.role
        if (current == ClanRole.ADMIN) throw ClanOperationException(ClanFailure.ALREADY_ADMIN)
        val next = if (current == ClanRole.MEMBER) ClanRole.MODERATOR else ClanRole.ADMIN
        updateRole(c,target,next); next
    }

    fun demote(actor: UUID, target: UUID): ClanRole = tx { c ->
        val id = actorClan(c,actor); lockClan(c,id); lockPlayers(c,actor,target)
        requireRole(c,actor,setOf(ClanRole.ADMIN)); sameClan(c,id,target)
        if (actor == target) throw ClanOperationException(ClanFailure.SELF_TARGET)
        val current = member(c,target)!!.role
        if (current == ClanRole.MEMBER) throw ClanOperationException(ClanFailure.TARGET_NOT_MEMBER)
        if (current == ClanRole.ADMIN && countAdmins(c,id) <= 1) throw ClanOperationException(ClanFailure.LAST_ADMIN)
        val next = if (current == ClanRole.ADMIN) ClanRole.MODERATOR else ClanRole.MEMBER
        updateRole(c,target,next); next
    }

    fun kick(actor: UUID,target: UUID): Pair<Clan,ClanRole> = tx { c ->
        val id = actorClan(c,actor); val clan = lockClan(c,id); lockPlayers(c,actor,target)
        val actorRole = requireRole(c,actor,setOf(ClanRole.MODERATOR,ClanRole.ADMIN)); sameClan(c,id,target)
        if (actor == target) throw ClanOperationException(ClanFailure.SELF_TARGET)
        val targetRole = member(c,target)!!.role
        if (actorRole == ClanRole.MODERATOR && targetRole == ClanRole.ADMIN) throw ClanOperationException(ClanFailure.CANNOT_KICK_ADMIN)
        if (targetRole == ClanRole.ADMIN && countAdmins(c,id) <= 1) throw ClanOperationException(ClanFailure.LAST_ADMIN)
        c.prepareStatement("DELETE FROM clan_members WHERE player_uuid=?").use { it.uuid(1,target); it.executeUpdate() }
        clan to targetRole
    }

    fun leave(player: UUID): Pair<Clan,Boolean> = tx { c ->
        val id = actorClan(c,player); val clan = lockClan(c,id); lockPlayer(c,player)
        val role = member(c,player)!!.role
        if (role == ClanRole.ADMIN && countAdmins(c,id) <= 1) throw ClanOperationException(ClanFailure.LAST_ADMIN,"Promote another member to Admin before leaving.")
        c.prepareStatement("DELETE FROM clan_members WHERE player_uuid=?").use { it.uuid(1,player); it.executeUpdate() }
        val empty = countMembers(c,id) == 0
        if (empty) c.prepareStatement("DELETE FROM clans WHERE clan_uuid=?").use { it.uuid(1,id); it.executeUpdate() }
        clan to empty
    }

    fun invite(actor: UUID,target: UUID,now: Instant,expires: Instant): ClanInvitation = tx { c ->
        val id = actorClan(c,actor); val clan = lockClan(c,id); lockPlayers(c,actor,target)
        requireRole(c,actor,setOf(ClanRole.MODERATOR,ClanRole.ADMIN))
        if (member(c,target) != null) throw ClanOperationException(ClanFailure.TARGET_IN_CLAN)
        c.prepareStatement("DELETE FROM clan_invitations WHERE clan_uuid=? AND player_uuid=? AND expires_at<=?").use { it.uuid(1,id); it.uuid(2,target); it.setTimestamp(3,Timestamp.from(now)); it.executeUpdate() }
        val exists = c.prepareStatement("SELECT 1 FROM clan_invitations WHERE clan_uuid=? AND player_uuid=?").use { it.uuid(1,id); it.uuid(2,target); it.executeQuery().use(ResultSet::next) }
        if (exists) throw ClanOperationException(ClanFailure.INVITATION_EXISTS)
        c.prepareStatement("INSERT INTO clan_invitations(clan_uuid,player_uuid,inviter_uuid,created_at,expires_at) VALUES(?,?,?,?,?)").use { it.uuid(1,id); it.uuid(2,target); it.uuid(3,actor); it.setTimestamp(4,Timestamp.from(now)); it.setTimestamp(5,Timestamp.from(expires)); it.executeUpdate() }
        ClanInvitation(id,clan.name,clan.tag,target,actor,now,expires)
    }

    fun accept(player: UUID, clanId: UUID, now: Instant): Clan = tx { c ->
        val clan = lockClan(c,clanId); lockPlayer(c,player)
        if (member(c,player) != null) throw ClanOperationException(ClanFailure.ALREADY_IN_CLAN)
        val invite = c.prepareStatement("SELECT expires_at FROM clan_invitations WHERE clan_uuid=? AND player_uuid=? FOR UPDATE").use { it.uuid(1,clanId); it.uuid(2,player); it.executeQuery().use { r -> if (r.next()) r.getTimestamp(1).toInstant() else null } }
            ?: throw ClanOperationException(ClanFailure.INVITATION_NOT_FOUND)
        if (!invite.isAfter(now)) throw ClanOperationException(ClanFailure.INVITATION_EXPIRED)
        c.prepareStatement("INSERT INTO clan_members(player_uuid,clan_uuid,role,joined_at) VALUES(?,?,?,?)").use { it.uuid(1,player); it.uuid(2,clanId); it.setString(3,ClanRole.MEMBER.name); it.setTimestamp(4,Timestamp.from(now)); it.executeUpdate() }
        deleteInvitation(c,clanId,player)
        clan
    }

    fun reject(player: UUID, clanId: UUID): UUID = tx { c ->
        lockClan(c,clanId)
        val inviter = c.prepareStatement("SELECT inviter_uuid FROM clan_invitations WHERE clan_uuid=? AND player_uuid=? FOR UPDATE").use { it.uuid(1,clanId); it.uuid(2,player); it.executeQuery().use { r -> if (r.next()) r.uuid("inviter_uuid") else null } }
            ?: throw ClanOperationException(ClanFailure.INVITATION_NOT_FOUND)
        deleteInvitation(c,clanId,player); inviter
    }

    fun invitations(player: UUID,page: Int,size: Int,now: Instant): ClanPage<ClanInvitation> = dataSource.connection.use { c ->
        val p=page.coerceAtLeast(1); val s=size.coerceIn(1,100)
        c.prepareStatement("DELETE FROM clan_invitations WHERE player_uuid=? AND expires_at<=?").use { it.uuid(1,player); it.setTimestamp(2,Timestamp.from(now)); it.executeUpdate() }
        val total=c.prepareStatement("SELECT COUNT(*) FROM clan_invitations WHERE player_uuid=?").use { it.uuid(1,player); it.executeQuery().use { r->r.next();r.getLong(1) } }
        val list=c.prepareStatement("SELECT i.clan_uuid,c.name,c.tag,i.player_uuid,i.inviter_uuid,i.created_at,i.expires_at FROM clan_invitations i JOIN clans c ON c.clan_uuid=i.clan_uuid WHERE i.player_uuid=? ORDER BY i.created_at DESC LIMIT ? OFFSET ?").use {
            it.uuid(1,player); it.setInt(2,s); it.setLong(3,(p-1L)*s); it.executeQuery().use { r -> buildList { while(r.next()) add(ClanInvitation(r.uuid("clan_uuid"),r.getString("name"),r.getString("tag"),r.uuid("player_uuid"),r.uuid("inviter_uuid"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant())) } }
        }
        ClanPage(list,p,s,total)
    }

    fun members(clanId: UUID,page: Int,size: Int): ClanPage<ClanMember> = dataSource.connection.use { c ->
        val p=page.coerceAtLeast(1); val s=size.coerceIn(1,100)
        val total=c.prepareStatement("SELECT COUNT(*) FROM clan_members WHERE clan_uuid=?").use { it.uuid(1,clanId);it.executeQuery().use { r->r.next();r.getLong(1) } }
        val rows=c.prepareStatement("SELECT clan_uuid,player_uuid,role,joined_at FROM clan_members WHERE clan_uuid=? ORDER BY FIELD(role,'ADMIN','MODERATOR','MEMBER'),joined_at LIMIT ? OFFSET ?").use { it.uuid(1,clanId);it.setInt(2,s);it.setLong(3,(p-1L)*s);it.executeQuery().use { r -> buildList { while(r.next()) add(ClanMember(r.uuid("clan_uuid"),r.uuid("player_uuid"),ClanRole.valueOf(r.getString("role")),r.getTimestamp("joined_at").toInstant())) } } }
        ClanPage(rows,p,s,total)
    }

    fun setColor(player: UUID,color: ClanColor): Clan = tx { c ->
        val id=actorClan(c,player); val clan=lockClan(c,id); requireRole(c,player,setOf(ClanRole.ADMIN))
        c.prepareStatement("UPDATE clans SET color_mode=?,color_value=?,rainbow_base_color=? WHERE clan_uuid=?").use { it.setString(1,color.mode.name);it.setString(2,color.color);it.setString(3,if(color.mode==ClanColorMode.RAINBOW) color.color else null);it.uuid(4,id);it.executeUpdate() }
        clan.copy(color=color)
    }

    private fun <T> tx(block:(Connection)->T):T=dataSource.connection.use { c-> c.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED; c.autoCommit=false; try { block(c).also { c.commit() } } catch(t:Throwable){ c.rollback(); throw t } }
    private fun lockPlayer(c:Connection,id:UUID){ c.prepareStatement("INSERT IGNORE INTO clan_player_locks(player_uuid) VALUES(?)").use { it.uuid(1,id);it.executeUpdate() }; c.prepareStatement("SELECT player_uuid FROM clan_player_locks WHERE player_uuid=? FOR UPDATE").use { it.uuid(1,id);it.executeQuery().use { r->r.next() } } }
    private fun lockPlayers(c:Connection,vararg ids:UUID){ ids.distinct().sortedBy(UUID::toString).forEach { lockPlayer(c,it) } }
    private fun lockClan(c:Connection,id:UUID):Clan { c.prepareStatement("SELECT clan_uuid FROM clans WHERE clan_uuid=? FOR UPDATE").use { it.uuid(1,id);it.executeQuery().use { r->if(!r.next()) throw ClanOperationException(ClanFailure.CLAN_NOT_FOUND) } }; return clan(c,id)!! }
    private fun clan(c:Connection,id:UUID):Clan?=c.prepareStatement("SELECT clan_uuid,name,tag,color_mode,color_value,created_at FROM clans WHERE clan_uuid=?").use { it.uuid(1,id);it.executeQuery().use { r->if(r.next()) Clan(r.uuid("clan_uuid"),r.getString("name"),r.getString("tag"),ClanColor(ClanColorMode.valueOf(r.getString("color_mode")),r.getString("color_value")),r.getTimestamp("created_at").toInstant()) else null } }
    private fun member(c:Connection,id:UUID):ClanMember?=c.prepareStatement("SELECT clan_uuid,player_uuid,role,joined_at FROM clan_members WHERE player_uuid=?").use { it.uuid(1,id);it.executeQuery().use { r->if(r.next()) ClanMember(r.uuid("clan_uuid"),r.uuid("player_uuid"),ClanRole.valueOf(r.getString("role")),r.getTimestamp("joined_at").toInstant()) else null } }
    private fun members(c:Connection,id:UUID):List<ClanMember> = c.prepareStatement("SELECT clan_uuid,player_uuid,role,joined_at FROM clan_members WHERE clan_uuid=?").use { it.uuid(1,id);it.executeQuery().use { r->buildList { while(r.next()) add(ClanMember(r.uuid("clan_uuid"),r.uuid("player_uuid"),ClanRole.valueOf(r.getString("role")),r.getTimestamp("joined_at").toInstant())) } } }
    private fun actorClan(c:Connection,actor:UUID)=member(c,actor)?.clanUuid?:throw ClanOperationException(ClanFailure.NOT_IN_CLAN)
    private fun requireRole(c:Connection,player:UUID,allowed:Set<ClanRole>):ClanRole { val role=member(c,player)?.role?:throw ClanOperationException(ClanFailure.NOT_IN_CLAN); if(role !in allowed) throw ClanOperationException(if(allowed==setOf(ClanRole.ADMIN)) ClanFailure.NOT_ADMIN else ClanFailure.NOT_MODERATOR); return role }
    private fun sameClan(c:Connection,id:UUID,target:UUID){ if(member(c,target)?.clanUuid!=id) throw ClanOperationException(ClanFailure.TARGET_NOT_MEMBER) }
    private fun updateRole(c:Connection,id:UUID,role:ClanRole){ c.prepareStatement("UPDATE clan_members SET role=? WHERE player_uuid=?").use { it.setString(1,role.name);it.uuid(2,id);it.executeUpdate() } }
    private fun countAdmins(c:Connection,id:UUID)=c.prepareStatement("SELECT COUNT(*) FROM clan_members WHERE clan_uuid=? AND role='ADMIN'").use { it.uuid(1,id);it.executeQuery().use { r->r.next();r.getInt(1) } }
    private fun countMembers(c:Connection,id:UUID)=c.prepareStatement("SELECT COUNT(*) FROM clan_members WHERE clan_uuid=?").use { it.uuid(1,id);it.executeQuery().use { r->r.next();r.getInt(1) } }
    private fun deleteInvitation(c:Connection,clan:UUID,player:UUID){ c.prepareStatement("DELETE FROM clan_invitations WHERE clan_uuid=? AND player_uuid=?").use { it.uuid(1,clan);it.uuid(2,player);it.executeUpdate() } }
    private fun java.sql.PreparedStatement.uuid(i:Int,v:UUID)=setBytes(i,ByteBuffer.allocate(16).putLong(v.mostSignificantBits).putLong(v.leastSignificantBits).array())
    private fun ResultSet.uuid(column:String):UUID=ByteBuffer.wrap(getBytes(column)).let { UUID(it.long,it.long) }
}
