package fm.island;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class LibraryCache {
    private final AtomicFile file;

    LibraryCache(Context context){
        file=new AtomicFile(new File(context.getFilesDir(),"library-cache-v1.json"));
    }

    static boolean same(List<Track> left,List<Track> right){
        if(left.size()!=right.size())return false;
        Map<String,Track> index=new HashMap<>();
        for(Track t:left)index.put(t.key(),t);
        for(Track t:right){
            Track saved=index.get(t.key());
            if(saved==null || saved.length!=t.length || saved.added!=t.added ||
                !Objects.equals(saved.path,t.path) || !saved.title.equals(t.title) ||
                !saved.artist.equals(t.artist) || !saved.album.equals(t.album))return false;
        }
        return true;
    }

    List<Track> load(){
        List<Track> items=new ArrayList<>();
        try(JsonReader reader=new JsonReader(new InputStreamReader(file.openRead(),StandardCharsets.UTF_8))){
            reader.beginArray();
            while(reader.hasNext()){
                reader.beginArray();
                Uri uri=Uri.parse(reader.nextString());
                Track t=new Track(uri,reader.nextString(),reader.nextString(),reader.nextString(),
                    reader.nextLong(),reader.nextLong());
                if(reader.peek()==android.util.JsonToken.NULL)reader.nextNull();else t.path=reader.nextString();
                reader.endArray();
                if(!LibraryScan.excludedPath(t.path) &&
                    !("file".equals(uri.getScheme())&&LibraryScan.excludedPath(uri.getPath())))items.add(t);
            }
            reader.endArray();
            return items;
        } catch(IOException | RuntimeException e){return Collections.emptyList();}
    }

    void save(List<Track> tracks) throws IOException {
        FileOutputStream stream=file.startWrite();
        try {
            JsonWriter writer=new JsonWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));
            writer.beginArray();
            for(Track t:tracks){
                writer.beginArray().value(t.key()).value(t.title).value(t.artist).value(t.album)
                    .value(t.length).value(t.added).value(t.path).endArray();
            }
            writer.endArray();
            writer.flush();
            file.finishWrite(stream);
        } catch(IOException | RuntimeException error){
            file.failWrite(stream);
            throw error;
        }
    }
}

