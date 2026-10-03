package com.serverwatch.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE="serverwatch"; private const val SERVERS="servers"
    fun loadServers(c:Context):List<ServerConfig>{
        val raw=c.getSharedPreferences(FILE,0).getString(SERVERS,null)?:return emptyList()
        return runCatching{val a=JSONArray(raw);(0 until a.length()).map{val o=a.getJSONObject(it);ServerConfig(o.getString("name"),o.getString("url"),o.getString("token"))}}.getOrDefault(emptyList())
    }
    fun saveServers(c:Context,s:List<ServerConfig>){val a=JSONArray();s.forEach{a.put(JSONObject().apply{put("name",it.name);put("url",it.baseUrl);put("token",it.token)})};c.getSharedPreferences(FILE,0).edit().putString(SERVERS,a.toString()).apply()}
}
