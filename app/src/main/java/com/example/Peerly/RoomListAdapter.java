package com.example.Peerly;

import android.content.Context;
import android.view.*;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.List;

public class RoomListAdapter extends ArrayAdapter<RoomListAdapter.RoomItem> {

    public RoomListAdapter(Context context, List<RoomItem> items) {
        super(context, 0, items);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.list_item_room, parent, false);
        }

        RoomItem item = getItem(position);
        if (item == null) return convertView;

        TextView name    = convertView.findViewById(R.id.roomName);
        TextView creator = convertView.findViewById(R.id.roomCreator);
        TextView status  = convertView.findViewById(R.id.roomStatus);

        name.setText(item.roomId);
        creator.setText("by " + item.createdBy);
        status.setText(item.status);

        return convertView;
    }

    public static class RoomItem {
        public final String roomId;
        public final String createdBy;
        public final String status;

        public RoomItem(String roomId, String createdBy, String status) {
            this.roomId    = roomId;
            this.createdBy = createdBy;
            this.status    = status;
        }
    }
}