package com.psiphon3.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = LogEntry.TABLE_NAME)
public class LogEntry {
	public static final String TABLE_NAME = "log";

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "_ID")
	@NonNull
	private int id;

	@ColumnInfo(name = "logjson")
	@NonNull
	private String logJson;

	@ColumnInfo(name = "is_diagnostic")
	private boolean isDiagnostic;

	public LogEntry(@NonNull String logJson, boolean isDiagnostic) {
		this.logJson = logJson;
		this.isDiagnostic = isDiagnostic;
	}

	public long getTimestamp() {
		return timestamp;
	}

	@ColumnInfo(name = "timestamp", defaultValue = "CURRENT_TIMESTAMP")
	private long timestamp;

	public String getLogJson() {
		return logJson;
	}

	public int getId() {
		return id;
	}

	public boolean isDiagnostic() {
		return isDiagnostic;
	}

	public void setId(int id) {
		this.id = id;
	}

	public void setLogJson(String logJson) {
		this.logJson = logJson;
	}

	public void setDiagnostic(boolean diagnostic) {
		isDiagnostic = diagnostic;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}
}
