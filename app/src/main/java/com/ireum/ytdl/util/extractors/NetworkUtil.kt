package com.ireum.ytdl.util.extractors

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {

    fun genericRequest(url: String): JSONObject {
        Log.e(NetworkUtil.toString(), url)
        val reader: BufferedReader
        var line: String?
        val responseContent = StringBuilder()
        val conn: HttpURLConnection
        var json = JSONObject()
        try {
            val req = URL(url)
            conn = req.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val responseCode = conn.responseCode
            val stream = if (responseCode < 300) conn.inputStream else conn.errorStream
            if (stream != null) {
                reader = BufferedReader(InputStreamReader(stream))
                while (reader.readLine().also { line = it } != null) {
                    responseContent.append(line)
                }
                reader.close()
                if (responseContent.isNotEmpty()) {
                    runCatching {
                        json = JSONObject(responseContent.toString())
                    }.getOrElse {
                        json = JSONObject()
                        json.put("_raw", responseContent.toString())
                    }
                }
            }
            json.put("_httpCode", responseCode)
            if (responseCode < 300) {
                Log.e(NetworkUtil.toString(), "HTTP $responseCode ok")
            } else {
                Log.e(NetworkUtil.toString(), "HTTP $responseCode error")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(NetworkUtil.toString(), e.toString())
            runCatching { json.put("_httpCode", -1) }
        }
        return json
    }

    fun genericArrayRequest(url: String): JSONArray {
        Log.e(NetworkUtil.toString(), url)
        val reader: BufferedReader
        var line: String?
        val responseContent = StringBuilder()
        val conn: HttpURLConnection
        var json = JSONArray()
        try {
            val req = URL(url)
            conn = req.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val responseCode = conn.responseCode
            val stream = if (responseCode < 300) conn.inputStream else conn.errorStream
            if (stream != null) {
                reader = BufferedReader(InputStreamReader(stream))
                while (reader.readLine().also { line = it } != null) {
                    responseContent.append(line)
                }
                reader.close()
                if (responseContent.isNotEmpty()) {
                    runCatching {
                        json = JSONArray(responseContent.toString())
                    }.getOrElse {
                        json = JSONArray()
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(NetworkUtil.toString(), e.toString())
        }
        return json
    }
}
