package com.iruixos.nexora;

import java.sql.*;
import java.nio.file.Path;
import java.util.*;

final class GateStore implements AutoCloseable {
    private final Connection db;
    GateStore(Path path) throws SQLException {
        db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(Statement s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS networks (name_key TEXT PRIMARY KEY, display_name TEXT NOT NULL, owner TEXT NOT NULL, routing TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS gates (id TEXT PRIMARY KEY, world TEXT NOT NULL, owner TEXT NOT NULL, network_key TEXT NOT NULL, display_name TEXT NOT NULL, plane TEXT NOT NULL, cells TEXT NOT NULL, originals TEXT NOT NULL)");
        }
    }
    record Network(String key,String display,UUID owner,String routing) {}
    Network network(String key) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM networks WHERE name_key=?")){s.setString(1,key);try(ResultSet r=s.executeQuery()) {return r.next()?new Network(key,r.getString("display_name"),UUID.fromString(r.getString("owner")),r.getString("routing")):null;}}
    }
    void insertNetwork(Network n) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT INTO networks VALUES (?,?,?,?)")){s.setString(1,n.key());s.setString(2,n.display());s.setString(3,n.owner().toString());s.setString(4,n.routing());s.executeUpdate();}
    }
    void updateRouting(String key,String routing) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("UPDATE networks SET routing=? WHERE name_key=?")){s.setString(1,routing);s.setString(2,key);s.executeUpdate();}
    }
    void insert(Gate g) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT INTO gates VALUES (?,?,?,?,?,?,?,?)")) {
            s.setString(1,g.id.toString());s.setString(2,g.worldId.toString());s.setString(3,g.owner.toString());s.setString(4,g.networkKey);s.setString(5,g.networkDisplay);s.setString(6,g.plane);
            s.setString(7,String.join(";",g.cells));
            StringJoiner j=new StringJoiner(";");g.originals.forEach((k,v)->j.add(k+"="+v));s.setString(8,j.toString());s.executeUpdate();
        }
    }
    List<Gate> load() throws SQLException {
        List<Gate> result=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM gates")) {
            while(r.next()) {
                Map<String,String> originals=new HashMap<>();String raw=r.getString("originals");
                if(!raw.isEmpty())for(String pair:raw.split(";")){int i=pair.indexOf('=');if(i>0)originals.put(pair.substring(0,i),pair.substring(i+1));}
                result.add(new Gate(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("world")),UUID.fromString(r.getString("owner")),r.getString("network_key"),r.getString("display_name"),r.getString("plane"),List.of(r.getString("cells").split(";")),originals));
            }
        }
        return result;
    }
    void delete(UUID id) throws SQLException {try(PreparedStatement s=db.prepareStatement("DELETE FROM gates WHERE id=?")){s.setString(1,id.toString());s.executeUpdate();}}
    void deleteEmptyNetwork(String key) throws SQLException {try(PreparedStatement s=db.prepareStatement("DELETE FROM networks WHERE name_key=? AND NOT EXISTS (SELECT 1 FROM gates WHERE network_key=?)")){s.setString(1,key);s.setString(2,key);s.executeUpdate();}}
    @Override public void close() throws SQLException {db.close();}
}
