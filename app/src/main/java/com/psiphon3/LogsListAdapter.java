package com.psiphon3;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.psiphon3.data.LogEntry;

import java.util.List;

public class LogsListAdapter extends RecyclerView.Adapter<LogsListAdapter.WordViewHolder> {

	class WordViewHolder extends RecyclerView.ViewHolder {
		private final TextView timestampView;
		private final TextView messageView;

		private WordViewHolder(View itemView) {
			super(itemView);
			timestampView = itemView.findViewById(R.id.MessageRow_Timestamp);
			messageView = itemView.findViewById(R.id.MessageRow_Text);
		}
	}

	private final LayoutInflater mInflater;
	private List<LogEntry> logEntries; // Cached copy of log entries

	LogsListAdapter(Context context) { mInflater = LayoutInflater.from(context); }

	@Override
	public WordViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View itemView = mInflater.inflate(R.layout.message_row, parent, false);
		return new WordViewHolder(itemView);
	}

	@Override
	public void onBindViewHolder(WordViewHolder holder, int position) {
		if (logEntries != null) {
			LogEntry current = logEntries.get(position);
			holder.messageView.setText(current.getLogJson());
		}
	}

	void setLogs(List<LogEntry> logs){
		Log.d("HACK", "setLogs: ");
		logEntries = logs;
		notifyDataSetChanged();
	}

	// getItemCount() is called many times, and when it is first called,
	// mWords has not been updated (means initially, it's null, and we can't return null).
	@Override
	public int getItemCount() {
		if (logEntries != null)
			return logEntries.size();
		else return 0;
	}
}
