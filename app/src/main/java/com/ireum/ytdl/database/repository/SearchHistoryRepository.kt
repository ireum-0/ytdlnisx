package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.dao.SearchHistoryDao
import com.ireum.ytdl.database.models.SearchHistoryItem

class SearchHistoryRepository(private val searchHistoryDao: SearchHistoryDao) {
    fun getAll() : List<SearchHistoryItem> {
        return searchHistoryDao.getAll()
    }

    fun getAllByKeyword(keyword: String) : List<SearchHistoryItem> {
        return searchHistoryDao.getAllByKeyword(keyword)
    }

    suspend fun insert(query: String){
        searchHistoryDao.insert(SearchHistoryItem(0, query))
    }

    suspend fun deleteAll(){
        searchHistoryDao.deleteAll()
    }

    suspend fun delete(query: String){
        searchHistoryDao.delete(query)
    }
}
