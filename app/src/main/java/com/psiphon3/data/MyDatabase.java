package com.psiphon3.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {LogEntry.class}, version = 3, exportSchema = false)
public abstract class MyDatabase extends RoomDatabase {

	public abstract LogEntryDao logEntryDao();

	private static volatile MyDatabase INSTANCE;
	private static final int NUMBER_OF_THREADS = 4;
	static final ExecutorService databaseWriteExecutor =
			Executors.newFixedThreadPool(NUMBER_OF_THREADS);

	public static MyDatabase getDatabase(final Context context) {
		if (INSTANCE == null) {
			synchronized (MyDatabase.class) {
				if (INSTANCE == null) {
					INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
							MyDatabase.class, "loggingprovider.db")
							.fallbackToDestructiveMigration()
							.build();
				}
			}
		}
		return INSTANCE;
	}
}
