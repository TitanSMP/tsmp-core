package de.titansmp.core.teleport

import de.titansmp.api.teleport.*
import java.sql.Connection
import java.sql.SQLException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTeleportRepository(private val dataSource: DataSource) {
    fun setHome(player: UUID, name: String, displayName:String, limit: Int?, destination: StoredTeleportLocation): TeleportHome = tx { c ->
        lockPlayer(c, player)
        val exists = c.prepareStatement("SELECT 1 FROM player_homes WHERE player_uuid=? AND normalized_name=?").use { s -> s.uuid(1, player); s.setString(2,name); s.executeQuery().use { it.next() } }
        if (exists) throw TeleportOperationException(TeleportFailure.ALREADY_EXISTS)
        val count = c.prepareStatement("SELECT COUNT(*) FROM player_homes WHERE player_uuid=?").use { s -> s.uuid(1,player); s.executeQuery().use { it.next(); it.getInt(1) } }
        if (limit != null && limit != 0 && count >= limit) throw TeleportOperationException(TeleportFailure.HOME_LIMIT)
        insertLocation(c,"player_homes","player_uuid",player.toString(),name,displayName,destination)
        TeleportHome(player,name,displayName,destination)
    }
    fun deleteHome(player: UUID,name:String):Boolean = dataSource.connection.use { c -> c.prepareStatement("DELETE FROM player_homes WHERE player_uuid=? AND normalized_name=?").use { it.uuid(1,player);it.setString(2,name);it.executeUpdate()==1 } }
    fun getHome(player:UUID,name:String):TeleportHome?=dataSource.connection.use { c -> c.prepareStatement("SELECT * FROM player_homes WHERE player_uuid=? AND normalized_name=?").use { it.uuid(1,player);it.setString(2,name);it.executeQuery().use { r -> if(!r.next()) null else TeleportHome(player,r.getString("normalized_name"),r.getString("display_name"),location(r)) } } }
    fun listHomes(player:UUID):List<TeleportHome> = dataSource.connection.use { c -> c.prepareStatement("SELECT * FROM player_homes WHERE player_uuid=? ORDER BY normalized_name").use { it.uuid(1,player);it.executeQuery().use { r -> buildList { while(r.next()) add(TeleportHome(player,r.getString("normalized_name"),r.getString("display_name"),location(r))) } } } }
    fun setClanHome(actor:UUID,clan:UUID,name:String,displayName:String,limit:Int?,destination:StoredTeleportLocation):ClanTeleportHome = tx { c ->
        c.prepareStatement("SELECT clan_uuid FROM clans WHERE clan_uuid=? FOR UPDATE").use { s -> s.uuid(1,clan); s.executeQuery().use { r -> if(!r.next()) throw TeleportOperationException(TeleportFailure.NOT_IN_CLAN) } }
        val role=c.prepareStatement("SELECT role FROM clan_members WHERE clan_uuid=? AND player_uuid=? FOR UPDATE").use { it.uuid(1,clan);it.uuid(2,actor);it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        if(role==null) throw TeleportOperationException(TeleportFailure.NOT_IN_CLAN)
        if(role!="ADMIN") throw TeleportOperationException(TeleportFailure.NOT_ADMIN)
        val exists=c.prepareStatement("SELECT 1 FROM clan_homes WHERE clan_uuid=? AND normalized_name=?").use { it.uuid(1,clan);it.setString(2,name);it.executeQuery().use { it.next() } }
        if(exists) throw TeleportOperationException(TeleportFailure.ALREADY_EXISTS)
        val count=c.prepareStatement("SELECT COUNT(*) FROM clan_homes WHERE clan_uuid=?").use { it.uuid(1,clan);it.executeQuery().use { it.next();it.getInt(1) } }
        if(limit!=null && limit!=0 && count>=limit) throw TeleportOperationException(TeleportFailure.CLAN_HOME_LIMIT)
        insertLocation(c,"clan_homes","clan_uuid",clan.toString(),name,displayName,destination)
        ClanTeleportHome(clan,name,displayName,destination)
    }
    fun deleteClanHome(actor:UUID,clan:UUID,name:String):Boolean=tx { c ->
        val role=c.prepareStatement("SELECT role FROM clan_members WHERE clan_uuid=? AND player_uuid=? FOR UPDATE").use { it.uuid(1,clan);it.uuid(2,actor);it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        if(role==null) throw TeleportOperationException(TeleportFailure.NOT_IN_CLAN)
        if(role!="ADMIN") throw TeleportOperationException(TeleportFailure.NOT_ADMIN)
        c.prepareStatement("DELETE FROM clan_homes WHERE clan_uuid=? AND normalized_name=?").use { it.uuid(1,clan);it.setString(2,name);it.executeUpdate()==1 }
    }
    fun getClanHome(clan:UUID,name:String?):ClanTeleportHome?=dataSource.connection.use { c -> val sql=if(name==null) "SELECT * FROM clan_homes WHERE clan_uuid=? ORDER BY normalized_name LIMIT 2" else "SELECT * FROM clan_homes WHERE clan_uuid=? AND normalized_name=?"; c.prepareStatement(sql).use { it.uuid(1,clan);if(name!=null) it.setString(2,name);it.executeQuery().use { r -> if(!r.next()) null else { val first=ClanTeleportHome(clan,r.getString("normalized_name"),r.getString("display_name"),location(r)); if(name==null && r.next()) throw TeleportOperationException(TeleportFailure.INVALID_NAME,"More than one clan home exists; choose a name.");first } } } }
    fun listClanHomes(clan:UUID):List<ClanTeleportHome> = dataSource.connection.use { c -> c.prepareStatement("SELECT * FROM clan_homes WHERE clan_uuid=? ORDER BY normalized_name").use { it.uuid(1,clan);it.executeQuery().use { r -> buildList { while(r.next()) add(ClanTeleportHome(clan,r.getString("normalized_name"),r.getString("display_name"),location(r))) } } } }
    fun createRequest(request:TeleportRequest):TeleportRequest=tx { c ->
        c.prepareStatement("DELETE FROM teleport_requests WHERE expires_at<=?").use { it.setTimestamp(1,java.sql.Timestamp.from(request.createdAt));it.executeUpdate() }
        val ordered=listOf(request.requesterUuid,request.targetUuid).sortedBy(UUID::toString)
        val found=c.prepareStatement("SELECT uuid FROM players WHERE uuid IN (?,?) ORDER BY uuid FOR UPDATE").use { s -> s.uuid(1,ordered[0]);s.uuid(2,ordered[1]);s.executeQuery().use { r -> var count=0;while(r.next())count++;count } }
        if(found!=2) throw TeleportOperationException(TeleportFailure.OFFLINE)
        try { c.prepareStatement("INSERT INTO teleport_requests(request_id,requester_uuid,target_uuid,requester_name,direction,created_at,expires_at) VALUES(?,?,?,?,?,?,?)").use { s -> s.uuid(1,request.requestId);s.uuid(2,request.requesterUuid);s.uuid(3,request.targetUuid);s.setString(4,request.requesterName);s.setString(5,request.direction.name);s.setTimestamp(6,java.sql.Timestamp.from(request.createdAt));s.setTimestamp(7,java.sql.Timestamp.from(request.expiresAt));s.executeUpdate() } } catch(e:SQLException) { if(e.errorCode==1062) throw TeleportOperationException(TeleportFailure.ALREADY_EXISTS,"A request is already pending") else throw e }
        request
    }
    fun resolveRequest(target:UUID,now:Instant):TeleportRequest {
        val row=tx { c ->
        c.prepareStatement("DELETE FROM teleport_requests WHERE expires_at<=?").use { it.setTimestamp(1,java.sql.Timestamp.from(now));it.executeUpdate() }
        val row=c.prepareStatement("SELECT * FROM teleport_requests WHERE target_uuid=? ORDER BY created_at,request_id LIMIT 1 FOR UPDATE").use { it.uuid(1,target);it.executeQuery().use { r -> if(!r.next()) null else TeleportRequest(r.uuid("request_id"),r.uuid("requester_uuid"),r.uuid("target_uuid"),r.getString("requester_name"),TeleportDirection.valueOf(r.getString("direction")),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant()) } } ?: throw TeleportOperationException(TeleportFailure.NO_PENDING_REQUEST)
        c.prepareStatement("DELETE FROM teleport_requests WHERE request_id=?").use { it.uuid(1,row.requestId);it.executeUpdate() }
        if(row.expiresAt.isAfter(now)) row else null
        }
        return row ?: throw TeleportOperationException(TeleportFailure.REQUEST_EXPIRED)
    }
    private fun insertLocation(c:Connection,table:String,key:String,id:String,name:String,display:String,d:StoredTeleportLocation) { c.prepareStatement("INSERT INTO $table($key,normalized_name,display_name,server_id,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?,?,?,?)").use { s -> s.uuid(1,UUID.fromString(id));s.setString(2,name);s.setString(3,display);s.setString(4,d.serverId);s.setString(5,d.location.world);s.setDouble(6,d.location.x);s.setDouble(7,d.location.y);s.setDouble(8,d.location.z);s.setFloat(9,d.location.yaw);s.setFloat(10,d.location.pitch);s.executeUpdate() } }
    private fun location(r:java.sql.ResultSet)=StoredTeleportLocation(r.getString("server_id"),TeleportLocation(r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")))
    private fun lockPlayer(c:Connection,p:UUID) { c.prepareStatement("SELECT uuid FROM players WHERE uuid=? FOR UPDATE").use { it.uuid(1,p);it.executeQuery().use { if(!it.next()) throw TeleportOperationException(TeleportFailure.OFFLINE) } } }
    private fun java.sql.PreparedStatement.uuid(i:Int,u:UUID)=setBytes(i,ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array())
    private fun java.sql.ResultSet.uuid(c:String):UUID=ByteBuffer.wrap(getBytes(c)).let { UUID(it.long,it.long) }
    private fun <T> tx(block:(Connection)->T):T=dataSource.connection.use { c -> c.autoCommit=false;c.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED;try { val v=block(c);c.commit();v } catch(e:Throwable) { c.rollback();throw e } }
}
