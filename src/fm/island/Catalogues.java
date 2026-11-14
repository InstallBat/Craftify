package fm.island;

import android.text.Html;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class Catalogues {
    private static final String NETEASE="https://music.163.com/";
    private static final String QQ="https://c.y.qq.com/";
    private static final String KUGOU="https://www.kugou.com/";
    private static final String DEEZER="https://www.deezer.com/";

    static Lyrics lookup(String title,String artist,long ms,int sources) throws Exception {
        String q=Lyrics.encode((artist+" "+title).trim());
        for(int source=0;source<5;source++) {
            if((sources&(1<<(source+1)))==0)continue;
            try {
                Lyrics result=find(source,q,title,artist,ms);
                if(result!=null) return result;
            } catch(Exception ignored) {}
            if(Thread.currentThread().isInterrupted()) break;
        }
        return null;
    }

    private static Lyrics find(int source,String q,String title,String artist,long ms) throws Exception {
        JSONArray hits;
        String name, by, id, duration;
        double scale=1;
        switch(source) {
            case 0:
                hits=array(get("https://api.deezer.com/search?limit=10&q="+q,DEEZER),"data");
                name="title";by="artist";id="id";duration="duration";break;
            case 1:
                hits=array(get(NETEASE+"api/cloudsearch/pc?type=1&limit=10&s="+q,NETEASE),"result","songs");
                name="name";by="ar";id="id";duration="dt";scale=1000;break;
            case 2:
                hits=array(get(QQ+"soso/fcgi-bin/client_search_cp?format=json&p=1&n=10&w="+q,"https://y.qq.com/"),"data","song","list");
                name="songname";by="singer";id="songmid";duration="interval";break;
            case 3:
                hits=array(get("https://songsearch.kugou.com/song_search_v2?page=1&pagesize=10&platform=WebFilter&format=json&keyword="+q,KUGOU),"data","lists");
                name="SongName";by="SingerName";id="FileHash";duration="Duration";break;
            default:
                hits=array(get("https://itunes.apple.com/search?entity=song&limit=10&country=ru&term="+q,null),"results");
                name="trackName";by="artistName";id="trackId";duration="trackTimeMillis";scale=1000;
        }
        if(hits==null)return null;
        int tried=0;
        for(int i=0;i<hits.length() && tried<3;i++) {
            JSONObject hit=hits.optJSONObject(i);
            if(hit==null || !Lyrics.normalize(title).equals(Lyrics.normalize(html(hit.optString(name)))))continue;
            if(!artist.isEmpty() && !artistMatches(hit.opt(by),Lyrics.normalize(artist)))continue;
            double seconds=hit.optDouble(duration,0)/scale;
            if(ms>0 && Math.abs(ms/1000.0-seconds)>4)continue;
            tried++;
            Lyrics result=new Lyrics(download(source,hit.optString(id)));
            if(result.hasTimeline(ms))return result;
        }
        return null;
    }

    private static String download(int source,String rawId) throws Exception {
        String id=Lyrics.encode(rawId);
        switch(source) {
            case 0:
                String token=get("https://auth.deezer.com/login/anonymous?jo=p&rto=c&i=c",DEEZER).optString("jwt");
                if(token.isEmpty())return "";
                JSONObject payload=new JSONObject().put("operationName","TrackLyrics")
                    .put("variables",new JSONObject().put("trackId",rawId))
                    .put("query","query TrackLyrics($trackId: String!) { track(trackId: $trackId) { lyrics { synchronizedLines { lrcTimestamp line } } } }");
                JSONArray lines=array(new JSONObject(Lyrics.request("https://pipe.deezer.com/api",DEEZER,token,payload.toString())),
                    "data","track","lyrics","synchronizedLines");
                StringBuilder text=new StringBuilder();
                for(int i=0;lines!=null && i<lines.length();i++) {
                    JSONObject line=lines.optJSONObject(i);
                    if(line!=null)text.append(line.optString("lrcTimestamp")).append(line.optString("line")).append('\n');
                }
                return text.toString();
            case 1:
                JSONObject reply;
                try {reply=get(NETEASE+"api/song/lyric/v1?lv=-1&kv=-1&tv=-1&yv=-1&id="+id,NETEASE);}
                catch(Exception unavailable){reply=new JSONObject();}
                JSONObject yrc=reply.optJSONObject("yrc");
                String enhanced=yrc==null?"":convertYrc(yrc.optString("lyric"));
                if(!enhanced.isEmpty())return enhanced;
                JSONObject lrc=reply.optJSONObject("lrc");
                if(lrc==null)lrc=get(NETEASE+"api/song/lyric?lv=-1&kv=-1&tv=-1&id="+id,NETEASE).optJSONObject("lrc");
                return lrc==null?"":lrc.optString("lyric");
            case 2:
                return html(decode(get(QQ+"lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&songmid="+id,"https://y.qq.com/").optString("lyric")));
            case 3:
                JSONArray versions=array(get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash="+id,KUGOU),"candidates");
                JSONObject version=versions==null?null:versions.optJSONObject(0);
                if(version==null)return "";
                return decode(get("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id="+
                    Lyrics.encode(version.optString("id"))+"&accesskey="+Lyrics.encode(version.optString("accesskey")),KUGOU).optString("content"));
            default:
                return get("https://lyrics.paxsenix.org/apple-music/lyrics?id="+id,null).optString("lrc");
        }
    }

    static String convertYrc(String source){
        java.util.regex.Pattern line=java.util.regex.Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})\\](.*)$");
        java.util.regex.Pattern word=java.util.regex.Pattern.compile("\\((\\d{1,9}),(\\d{1,9}),\\d+\\)([^\\r\\n]*?)(?=\\(\\d{1,9},\\d{1,9},\\d+\\)|$)");
        StringBuilder result=new StringBuilder();
        for(String row:source.split("\\r?\\n")){
            java.util.regex.Matcher cue=line.matcher(row.trim());
            if(!cue.matches())continue;
            java.util.regex.Matcher token=word.matcher(cue.group(3));
            if(!token.find())continue;
            long start=Long.parseLong(cue.group(1)), end=start+Long.parseLong(cue.group(2));
            result.append('[').append(lrcTime(start)).append(']');
            do{
                result.append('<').append(lrcTime(Long.parseLong(token.group(1)))).append('>').append(token.group(3));
            }while(token.find());
            result.append('<').append(lrcTime(end)).append(">\n");
        }
        return result.toString();
    }
    private static String lrcTime(long ms){
        return String.format(java.util.Locale.ROOT,"%02d:%02d.%03d",ms/60000,ms/1000%60,ms%1000);
    }
    private static boolean artistMatches(Object value,String artist) {
        if(value instanceof JSONArray) {
            JSONArray people=(JSONArray)value;
            for(int i=0;i<people.length();i++)if(artistMatches(people.opt(i),artist))return true;
            return false;
        }
        String text=value instanceof JSONObject ? ((JSONObject)value).optString("name") : value instanceof String ? (String)value : "";
        String found=Lyrics.normalize(html(text));
        return !artist.isEmpty() && !found.isEmpty() && (found.contains(artist)||artist.contains(found));
    }
    private static JSONObject get(String url,String referrer) throws Exception {
        return new JSONObject(Lyrics.request(url,referrer,null,null));
    }
    private static JSONArray array(JSONObject object,String... path) {
        for(int i=0;i<path.length-1 && object!=null;i++)object=object.optJSONObject(path[i]);
        return object==null?null:object.optJSONArray(path[path.length-1]);
    }
    private static String decode(String value){return new String(Base64.decode(value,Base64.DEFAULT),StandardCharsets.UTF_8).replace("\uFEFF","");}
    private static String html(String value){return Html.fromHtml(value,Html.FROM_HTML_MODE_LEGACY).toString();}
}




