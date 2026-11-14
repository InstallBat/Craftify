package fm.island;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.os.storage.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

final class LibraryScan {
    private static final Pattern AUDIO = Pattern.compile("(?i).+\\.(mp3|wav|wave|ogg|oga|opus|m4a|m4b|aac|aif|aiff|aifc|wma|flac|alac|amr|awb|ape|ac3|eac3|dts|mp2|mka|mid|midi|w64|wv|tta|au|ra|spx|dsf|dff|mpc|caf|aob|m4p|m4r|adts|a52|tak|voc)$");
    private static final String EXCLUDED = "/download/ayugram/saved attachments";

    static boolean fullAccess(Context context) {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() :
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean excludedPath(String path) {
        if (path == null) return false;
        String lower = path.replace('\\','/').toLowerCase(Locale.ROOT);
        String primary = Environment.getExternalStorageDirectory().getAbsolutePath().toLowerCase(Locale.ROOT);
        for (String root : new String[]{primary,"/storage/emulated/0","/sdcard","/storage/self/primary"}) {
            String target = root + EXCLUDED;
            if (lower.equals(target) || lower.startsWith(target + "/")) return true;
        }
        return false;
    }

    static boolean excludedUri(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) return excludedPath(uri.getPath());
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            try {
                String id = DocumentsContract.getDocumentId(uri);
                if (id.regionMatches(true,0,"primary:",0,8))
                    return excludedPath("/storage/emulated/0/" + id.substring(8));
            } catch (IllegalArgumentException ignored) {}
        }
        try (android.database.Cursor row = context.getContentResolver().query(uri,new String[]{"_data"},null,null,null)) {
            return row != null && row.moveToFirst() && excludedPath(row.getString(0));
        } catch (RuntimeException ignored) { return false; }
    }

    static void walk(Context context, Map<String,Track> indexed, Map<String,Track> result,
                     Map<String,Track> previous) {
        Set<File> roots = new LinkedHashSet<>();
        roots.add(Environment.getExternalStorageDirectory());
        if (Build.VERSION.SDK_INT >= 30) {
            for (StorageVolume volume : context.getSystemService(StorageManager.class).getStorageVolumes())
                if (volume.getDirectory()!=null) roots.add(volume.getDirectory());
        } else {
            for (File folder : context.getExternalFilesDirs(null)) {
                if (folder==null) continue;
                File root=folder;
                for(int i=0;i<4 && root!=null;i++) root=root.getParentFile();
                if(root!=null) roots.add(root);
            }
        }
        Deque<File> pending = new ArrayDeque<>(roots);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
            File directory = pending.removeFirst();
            String path = canonical(directory);
            if (excludedPath(path) || !visited.add(path)) continue;
            File[] children = directory.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory()) {
                    if (!excludedPath(child.getAbsolutePath())) pending.addLast(child);
                } else if (child.isFile() && AUDIO.matcher(child.getName()).matches()) {
                    String real = canonical(child);
                    if (excludedPath(real) || indexed.containsKey(real)) continue;
                    Uri uri = Uri.fromFile(new File(real));
                    Track cached = previous.get(uri.toString());
                    long timestamp = child.lastModified()/1000;
                    Track track = cached!=null && cached.added==timestamp ? cached :
                        Track.open(context,uri).withAdded(timestamp);
                    result.put(track.key(),track);
                }
            }
        }
    }

    static String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException e) { return file.getAbsolutePath(); }
    }
}

