package com.psiphon3.data;

import android.app.Application;

import java.util.List;

import io.reactivex.Flowable;

public class LogEntryRepository {
	private LogEntryDao logEntryDao;
	private Flowable<List<LogEntry>> allLogEntries;

	// Note that in order to unit test the WordRepository, you have to remove the Application
	// dependency. This adds complexity and much more code, and this sample is not about testing.
	// See the BasicSample in the android-architecture-components repository at
	// https://github.com/googlesamples
	public LogEntryRepository(Application application) {
		MyDatabase db = MyDatabase.getDatabase(application);
		logEntryDao = db.logEntryDao();
		allLogEntries = logEntryDao.getAll();
	}

	// Room executes all queries on a separate thread.
	// Observed LiveData will notify the observer when the data has changed.
	public Flowable<List<LogEntry>> getAllLogEntries() {
		return allLogEntries;
	}

	// You must call this on a non-UI thread or your app will throw an exception. Room ensures
	// that you're not doing any long running operations on the main thread, blocking the UI.
	public void insert(LogEntry logEntry) {
		MyDatabase.databaseWriteExecutor.execute(() -> {
			logEntryDao.insert(logEntry);
		});
	}
}