package com.psiphon3.data;

import android.content.ContentValues;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.psiphon3.psiphonlibrary.LoggingProvider;

import java.util.List;

import io.reactivex.Flowable;

@Dao
public interface LogEntryDao {
	static LogEntry fromContentValues(ContentValues values) {
		return new LogEntry(values.getAsString(LoggingProvider.LogDatabaseHelper.COLUMN_NAME_LOGJSON),
				values.getAsBoolean(LoggingProvider.LogDatabaseHelper.COLUMN_NAME_IS_DIAGNOSTIC));
	}

	@Query("SELECT * FROM log")
	Flowable<List<LogEntry>> getAll();

	@Query("DELETE FROM log")
	void deleteAll();

	@Query("DELETE FROM log WHERE timestamp < :beforeDate")
	void deleteBefore(String beforeDate);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	void insert(LogEntry logEntry);
}
