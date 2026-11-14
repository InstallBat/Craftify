package fm.island;

import android.content.*;
import android.os.SystemClock;
import org.json.*;
import java.util.*;

final class Store {
    static final String[] LYRIC_SOURCES={"LRCLIB","Deezer","NetEase","QQ Music","Kugou","Paxsenix"};
    static final int ALL_LYRIC_SOURCES=(1<<LYRIC_SOURCES.length)-1;
    final SharedPreferences prefs;
    int previewLetters(){return prefs.getInt("previewLetters",5);}
    boolean karaokeEnabled(){return prefs.getBoolean("karaokeEnabled",false);}
    void setKaraokeEnabled(boolean enabled){prefs.edit().putBoolean("karaokeEnabled",enabled).apply();}
    int lyricSources(){return prefs.getInt("lyricSources",ALL_LYRIC_SOURCES);}
    Store(Context c) { prefs=c.getSharedPreferences("library",0); }
    Set<String> set(String key) { return new HashSet<>(prefs.getStringSet(key,Collections.emptySet())); }
    void toggle(String list, String track) {
        Set<String> values=set(list);
        if (!values.remove(track)) values.add(track);
        prefs.edit().putStringSet(list,values).apply();
    }
    void add(String list, String track) {
        Set<String> values=set(list);
        values.add(track);
        prefs.edit().putStringSet(list,values).apply();
    }
    List<String> lists() { List<String> names=new ArrayList<>(set("lists")); Collections.sort(names); return names; }
    void removeList(String name) {
        Set<String> names=set("lists");
        names.remove(name);
        prefs.edit().putStringSet("lists",names).remove("list:"+name).apply();
    }
    JSONObject stats() {
        try { return new JSONObject(prefs.getString("stats","{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    void log(String key,String title,String artist,long ms,boolean play) {
        JSONObject all=stats();
        JSONObject item=all.optJSONObject(key);
        if(item==null) item=new JSONObject();
        try {
            item.put("title",title).put("artist",artist).put("ms",item.optLong("ms")+ms)
                .put("plays",item.optInt("plays")+(play?1:0)).put("last",System.currentTimeMillis());
            all.put(key,item);
            prefs.edit().putString("stats",all.toString()).apply();
        } catch(JSONException ignored) {}
    }
    static final class Meter {
        final Store store;
        String key="";
        long last, accumulated, pending;
        boolean counted, wasPlaying;
        Meter(Context c) { store=new Store(c); }
        void sample(String id,String title,String artist,boolean playing) {
            long now=SystemClock.elapsedRealtime();
            if (!key.equals(id)) {
                key=id;
                accumulated=0;
                pending=0;
                counted=false;
                wasPlaying=false;
            }
            long delta=wasPlaying ? Math.min(2000,now-last) : 0;
            last=now;
            wasPlaying=playing;
            if(title.isEmpty()) return;
            accumulated+=delta;
            pending+=delta;
            boolean count=!counted && accumulated>=10000;
            if (count || pending>=5000 || (!playing && pending>0)) {
                store.log(key,title,artist,pending,count);
                pending=0;
                counted |= count;
            }
        }
    }
}




