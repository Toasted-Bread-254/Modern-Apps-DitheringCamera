package com.vayunmathur.photos.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

@Fts4
@Entity(tableName = "PhotoOCR")
data class PhotoOCR(
    @PrimaryKey @ColumnInfo(name = "rowid") override val id: Long = 0,
    val ocrText: String,
    val description: String = "",
) : DatabaseItem
