package fm.island;

import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.*;
import java.util.*;

final class Track {
    final Uri uri;
    final String title, artist, album;
    final long length, added;
    String path;
    Track(Uri uri, String title, String artist, String album, long length, long added) {
        this.uri = uri;
        this.title = title == null || title.isEmpty() ? "Без названия" : title;
        this.artist = clean(artist);
        this.album = clean(album);
        this.length = length;
        this.added = added;
    }
    static String clean(String s) { return s == null || s.equals("<unknown>") ? "" : s; }
    Track withAdded(long timestamp) { return new Track(uri,title,artist,album,length,timestamp); }
    String key() { return uri.toString(); }
    static List<Track> scan(Context context) {
        List<Track> items = new ArrayList<>();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] columns = {"_id","title","artist","album","duration","date_added","_data"};
        try (Cursor c = context.getContentResolver().query(base, columns, null, null, null)) {
            if(c==null)throw new IllegalStateException("MediaStore result unavailable");
            while (c.moveToNext()) {
                Track track=new Track(ContentUris.withAppendedId(base,c.getLong(0)),
                    c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5));
                track.path=c.getString(6);
                if (!LibraryScan.excludedPath(track.path)) items.add(track);
            }
        }
        return items;
    }
    static Track open(Context context, Uri uri) {
        String title = uri.getLastPathSegment();
        try (Cursor c = context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c != null && c.moveToFirst()) title = c.getString(0);
        } catch (RuntimeException ignored) {}
        if (title != null) title = title.replaceFirst("\\.[A-Za-z0-9]{2,5}$","");
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            String tagged = r.extractMetadata(7);
            return new Track(uri, tagged == null ? title : tagged, r.extractMetadata(2),
                r.extractMetadata(1), Long.parseLong(Optional.ofNullable(r.extractMetadata(9)).orElse("0")),0);
        } catch (RuntimeException ignored) { return new Track(uri,title,"","",0,0); }
        finally { try { r.release(); } catch (Exception ignored) {} }
    }
}




